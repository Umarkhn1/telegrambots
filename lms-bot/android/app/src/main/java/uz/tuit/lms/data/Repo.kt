package uz.tuit.lms.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import uz.tuit.lmsbot.config.AppConfig
import uz.tuit.lmsbot.service.LmsService
import uz.tuit.lmsbot.service.SemesterSchedule
import uz.tuit.lmsbot.service.TeacherService
import uz.tuit.lmsbot.service.TeacherService.FormResult
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class NotLoggedInException : Exception("not_logged_in")
class LmsUnavailableException(msg: String = "lms_unavailable") : Exception(msg)
class ApiException(val code: String) : Exception(code)

/** Описание запроса: ключ кэша, как сохранить на диск (null — только в памяти) и как загрузить. */
class Res<T>(
    val key: String,
    val ser: KSerializer<T>?,
    val ttl: Long = Repo.CACHE_MS,
    val load: suspend () -> T,
)

data class Cached<T>(val ts: Long, val value: T)

sealed interface Auth {
    data object Unknown : Auth
    data object LoggedOut : Auth
    data class LoggedIn(val teacher: Boolean, val tutor: Boolean, val offline: Boolean = false) : Auth
}

/**
 * Единая точка доступа к LMS. Приложение ходит на lms.tuit.uz прямо с телефона тем же
 * Java-кодом, что и бот (LmsService/TeacherService), без промежуточного сервера:
 * пароль и cookie не покидают устройство.
 */
object Repo {
    /** Сервисы бота рассчитаны на многих пользователей Telegram; в приложении он один. */
    const val UID = 1L
    const val CACHE_MS = 3L * 60 * 1000
    private const val PROBE_MS = 10L * 60 * 1000

    lateinit var lms: LmsService
        private set
    lateinit var semesterSchedule: SemesterSchedule
        private set
    val ts: TeacherService get() = lms.teacher()

    private lateinit var cacheDir: File
    private val mem = ConcurrentHashMap<String, Cached<Any?>>()
    private val inflight = ConcurrentHashMap<String, Mutex>()
    @Volatile private var lastProbe = 0L

    private val _auth = MutableStateFlow<Auth>(Auth.Unknown)
    val auth: StateFlow<Auth> = _auth

    /** Ключи, данные по которым обновились или устарели: экраны перечитывают их, виджеты перерисовываются. */
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val changes: SharedFlow<String> = _changes

    private val _invalidations = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val invalidations: SharedFlow<String> = _invalidations

    fun init(context: Context) {
        if (::lms.isInitialized) return
        // Сервисы хранят cookie и токен OneID в ~/.tuit-lms-bot/sessions — на Android это внутренняя папка приложения.
        System.setProperty("user.home", context.filesDir.absolutePath)
        val config = AppConfig()
        config.bot.token = Prefs.installSecret
        config.bot.username = "lmstgbot"
        lms = LmsService(config)
        semesterSchedule = SemesterSchedule(lms)
        cacheDir = File(context.filesDir, "cache_json").apply { mkdirs() }
        if (Prefs.loggedIn) _auth.value = Auth.LoggedIn(Prefs.teacher, Prefs.tutor, offline = true)
    }

    // ─────────────────────────────────────────────
    //  СЕССИЯ
    // ─────────────────────────────────────────────

    /** При запуске и возвращении в приложение: жива ли сессия, не истекла ли, не сменилась ли роль. */
    suspend fun ensureSession(force: Boolean = false): Auth = withContext(Dispatchers.IO) {
        if (!Prefs.loggedIn && !hasSavedSession()) {
            _auth.value = Auth.LoggedOut
            return@withContext Auth.LoggedOut
        }
        val now = System.currentTimeMillis()
        if (!force && lms.isLoggedIn(UID) && now - lastProbe < PROBE_MS) return@withContext _auth.value
        val state = lms.checkSession(UID)
        val result = when (state) {
            LmsService.SessionState.ALIVE, LmsService.SessionState.RESTORED -> {
                lastProbe = now
                onLoggedIn(fetchProfileName = false)
                _auth.value
            }
            LmsService.SessionState.DEAD -> {
                if (Prefs.loggedIn) clearLocal()
                Auth.LoggedOut
            }
            else -> if (Prefs.loggedIn) Auth.LoggedIn(Prefs.teacher, Prefs.tutor, offline = true) else Auth.LoggedOut
        }
        _auth.value = result
        result
    }

    private fun hasSavedSession(): Boolean =
        File(System.getProperty("user.home"), ".tuit-lms-bot/sessions").listFiles()?.any { it.name.contains("_$UID") } == true

    /** После успешного входа: роль, семестры, имя. */
    suspend fun onLoggedIn(fetchProfileName: Boolean = true) = withContext(Dispatchers.IO) {
        val teacher = lms.isTeacher(UID)
        val tutor = lms.hasTutor(UID)
        val roleChanged = Prefs.loggedIn && Prefs.teacher != teacher
        Prefs.loggedIn = true
        Prefs.teacher = teacher
        Prefs.tutor = tutor
        lastProbe = System.currentTimeMillis()
        runCatching {
            val list = lms.getSemesters(UID).map { SemesterUi(it.id, it.name ?: "", it.year) }
            if (list.isNotEmpty()) Prefs.semesters = list
            val cur = lms.getCurrentSemesterId(UID)
            if (cur > 0) Prefs.currentSemester = cur
        }
        if (fetchProfileName || Prefs.displayName.isBlank()) runCatching { loadDisplayName(teacher) }
        if (roleChanged) dropAll()
        _auth.value = Auth.LoggedIn(teacher, tutor)
    }

    private fun loadDisplayName(teacher: Boolean) {
        val full = if (teacher) ts.info(UID)?.fullName() else lms.getStudentInfo(UID)?.fullName
        if (!full.isNullOrBlank()) Prefs.displayName = full.trim()
    }

    /** Имя для приветствия: из ФИО «Фамилия Имя Отчество» берём имя. */
    fun firstName(): String {
        val parts = Prefs.displayName.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[1]
            parts.size == 1 -> parts[0]
            else -> ""
        }.lowercase().replaceFirstChar { it.titlecase() }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { lms.logout(UID) }
        clearLocal()
    }

    private fun clearLocal() {
        Prefs.clearAccount()
        dropAll()
        semesterSchedule.invalidate(UID)
        cacheDir.listFiles()?.forEach { it.delete() }
        _auth.value = Auth.LoggedOut
        _changes.tryEmit("*")
    }

    /** Сессия умерла посреди работы (например, вход с другого устройства). */
    private fun sessionLost() {
        clearLocal()
    }

    private fun requireLogin() {
        if (lms.isLoggedIn(UID)) return
        when (lms.checkSession(UID)) {
            LmsService.SessionState.ALIVE, LmsService.SessionState.RESTORED -> {
                lastProbe = System.currentTimeMillis()
                if (_auth.value !is Auth.LoggedIn || (_auth.value as Auth.LoggedIn).offline)
                    _auth.value = Auth.LoggedIn(lms.isTeacher(UID), lms.hasTutor(UID))
            }
            LmsService.SessionState.DEAD -> {
                sessionLost()
                throw NotLoggedInException()
            }
            else -> throw LmsUnavailableException("offline")
        }
    }

    // ─────────────────────────────────────────────
    //  КЭШ
    // ─────────────────────────────────────────────

    private fun file(key: String) = File(cacheDir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    @Serializable
    private data class Disk(val ts: Long, val data: JsonElement)

    @Suppress("UNCHECKED_CAST")
    fun <T> peek(res: Res<T>): Cached<T>? {
        (mem[res.key] as Cached<T>?)?.let { return it }
        val ser = res.ser ?: return null
        val f = file(res.key)
        if (!f.exists()) return null
        return runCatching {
            val d = Json.decodeFromString(Disk.serializer(), f.readText())
            Cached(d.ts, Json.decodeFromJsonElement(ser, d.data)).also { mem[res.key] = it as Cached<Any?> }
        }.getOrNull()
    }

    /** Данные из кэша, если свежие, иначе — запрос в LMS. Одинаковые запросы не идут параллельно. */
    suspend fun <T> get(res: Res<T>, fresh: Boolean = false): T = withContext(Dispatchers.IO) {
        if (!fresh) peek(res)?.takeIf { System.currentTimeMillis() - it.ts < res.ttl }?.let { return@withContext it.value }
        val lock = inflight.getOrPut(res.key) { Mutex() }
        lock.withLock {
            if (!fresh) peek(res)?.takeIf { System.currentTimeMillis() - it.ts < res.ttl }?.let { return@withLock it.value }
            val v = res.load()
            store(res, v)
            v
        }
    }

    /** Положить готовый ответ в кэш (фоновая проверка собирает дедлайны сама). */
    fun <T> put(res: Res<T>, v: T) = store(res, v)

    private fun <T> store(res: Res<T>, v: T) {
        // Пустой ответ чаще всего значит сбой LMS, а не отсутствие данных — не кэшируем.
        val empty = v == null || (v is Collection<*> && v.isEmpty()) || (v is ScheduleUi && v.lessons.isEmpty())
        if (empty) return
        val now = System.currentTimeMillis()
        @Suppress("UNCHECKED_CAST")
        mem[res.key] = Cached(now, v) as Cached<Any?>
        res.ser?.let { ser ->
            runCatching { file(res.key).writeText(Json.encodeToString(Disk.serializer(), Disk(now, Json.encodeToJsonElement(ser, v)))) }
        }
        _changes.tryEmit(res.key)
    }

    /** Сбросить кэш по префиксу ключа; открытые экраны с такими данными перечитают их. */
    fun invalidate(vararg prefixes: String) {
        for (p in prefixes) {
            mem.keys.removeIf { it.startsWith(p) }
            _invalidations.tryEmit(p)
        }
    }

    private fun dropAll() {
        mem.clear()
        _invalidations.tryEmit("")
    }

    // ─────────────────────────────────────────────
    //  ВХОД
    // ─────────────────────────────────────────────

    suspend fun loginLms(login: String, password: String): LmsService.LoginResult = withContext(Dispatchers.IO) {
        val r = lms.loginDetailed(UID, login.trim(), password)
        if (r == LmsService.LoginResult.OK) onLoggedIn()
        r
    }

    /** OK от OneID — это ещё не вход: токен надо обменять на сессию LMS. */
    private suspend fun finishOneId(r: LmsService.OneIdResult): LoginStep = when (r.status) {
        LmsService.OneIdResult.Status.NEED_SMS -> LoginStep.NeedCode
        LmsService.OneIdResult.Status.OK -> if (lms.oneIdFinish(UID)) { onLoggedIn(); LoginStep.Done } else LoginStep.Error("link")
        LmsService.OneIdResult.Status.UNREACHABLE -> LoginStep.Error("unreachable")
        else -> LoginStep.Error(r.message)
    }

    suspend fun oneIdLogin(login: String, password: String) = withContext(Dispatchers.IO) { finishOneId(lms.oneIdLogin(UID, login.trim(), password)) }
    suspend fun oneIdConfirm(login: String, code: String) = withContext(Dispatchers.IO) { finishOneId(lms.oneIdConfirm(UID, login.trim(), code.trim())) }

    suspend fun mobileSend(phoneRaw: String): LoginStep = withContext(Dispatchers.IO) {
        val phone = LmsService.normalizeUzPhone(phoneRaw) ?: return@withContext LoginStep.Error("phone")
        val r = lms.oneIdMobileSendSms(UID, phone)
        when (r.status) {
            LmsService.OneIdResult.Status.NEED_SMS -> LoginStep.CodeSent(phone)
            LmsService.OneIdResult.Status.UNREACHABLE -> LoginStep.Error("unreachable")
            else -> LoginStep.Error(r.message)
        }
    }

    suspend fun mobileConfirm(code: String) = withContext(Dispatchers.IO) { finishOneId(lms.oneIdMobileConfirm(UID, code.trim())) }

    suspend fun qrStart(): LmsService.OneIdQr = withContext(Dispatchers.IO) { lms.oneIdQrStart(UID) }
    suspend fun qrNext(): LmsService.OneIdQr = withContext(Dispatchers.IO) { lms.oneIdQrNext(UID) }
    suspend fun qrCheck(): LmsService.QrStatus = withContext(Dispatchers.IO) { lms.oneIdQrCheck(UID) }
    suspend fun qrFinish(): Boolean = withContext(Dispatchers.IO) { lms.oneIdFinish(UID).also { if (it) onLoggedIn() } }
    fun qrCancel() = runCatching { lms.oneIdQrCancel(UID) }

    // ─────────────────────────────────────────────
    //  СТУДЕНТ
    // ─────────────────────────────────────────────

    private fun sem(s: Int): Int {
        val v = if (s > 0) s else Prefs.semesterId().takeIf { it > 0 } ?: lms.getCurrentSemesterId(UID)
        if (v <= 0) throw ApiException("no_semester")
        return v
    }

    fun courses(semester: Int) = Res("courses:$semester", ListSerializer(CourseUi.serializer())) {
        requireLogin()
        lms.getMyCourses(UID, sem(semester)).map(Mappers::course)
    }

    fun activities(courseId: Int) = Res("act:$courseId", ActivitiesUi.serializer()) {
        requireLogin()
        val cs = lms.getActivities(UID, courseId) ?: throw LmsUnavailableException()
        ActivitiesUi(cs.earned, cs.maxScore, cs.progress, cs.grade,
            (cs.activities ?: emptyList()).mapIndexed { i, a -> Mappers.activity(a, i, courseId) })
    }

    fun attendance(courseId: Int, semester: Int, subject: String?) = Res("att:$courseId:$semester", AttendanceUi.serializer()) {
        requireLogin()
        val items = lms.getAttendance(UID, courseId, sem(semester), subject).filter { !it.isSummary }.map {
            AbsenceUi(it.date ?: "", it.type, Mappers.isLecture(it.type), it.calendar, it.hasReason == 1)
        }
        AttendanceUi(items.size, items)
    }

    fun calendar(courseId: Int) = Res("cal:$courseId", ListSerializer(CalendarTabUi.serializer())) {
        requireLogin()
        lms.getCalendar(UID, courseId).map { (k, list) ->
            CalendarTabUi(k, list.map { c ->
                CalendarEntryUi(c.number, c.topic ?: "", c.date, c.files.map { CalendarFileUi(it.name ?: "file", it.url ?: "", it.type ?: "file") })
            })
        }
    }

    /** Расписание на весь семестр (строится по датам календарных планов). Своё кэширование — 3 часа в SemesterSchedule. */
    fun schedule(semester: Int) = Res("sched:$semester", ScheduleUi.serializer(), ttl = 60L * 60 * 1000) {
        requireLogin()
        Mappers.schedule(semesterSchedule.get(UID, sem(semester), false))
    }

    suspend fun scheduleFresh(semester: Int): ScheduleUi {
        semesterSchedule.invalidate(UID)
        return get(schedule(semester), fresh = true)
    }

    /** Ближайшие дедлайны по всем предметам семестра. Предметы опрашиваются параллельно. */
    fun deadlines(semester: Int) = Res("dl:$semester", ListSerializer(ActivityUi.serializer())) {
        requireLogin()
        val s = sem(semester)
        val courses = lms.getMyCourses(UID, s)
        val now = System.currentTimeMillis()
        val gate = Semaphore(4)
        val all = coroutineScope {
            courses.map { c ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val cs = runCatching { lms.getActivities(UID, c.id) }.getOrNull()
                        (cs?.activities ?: emptyList()).mapIndexedNotNull { i, a ->
                            val m = Mappers.activity(a, i, c.id, c.subject)
                            // Сданное и оценённое нужно и после срока: работу часто оценивают, когда он уже прошёл.
                            if (!m.submitted && (m.deadlineTs ?: 0) < now) null else m
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        // Срока может не быть у сданной работы: такие — в конец.
        all.sortedBy { it.deadlineTs ?: Long.MAX_VALUE }
    }

    fun studyPlan() = Res("plan", ListSerializer(PlanSubjectUi.serializer()), ttl = 30L * 60 * 1000) {
        requireLogin()
        lms.getStudyPlan(UID).map { PlanSubjectUi(it.name ?: "", it.credits, it.grade, it.semester) }
    }

    /** Семестр фоновых проверок: всегда текущий, а не тот, что листают на экране (как в боте). */
    fun currentSemester(): Int = Prefs.currentSemester.takeIf { it > 0 } ?: Prefs.semesterId()

    fun finals(semester: Int) = Res("finals:$semester", ListSerializer(FinalUi.serializer())) {
        requireLogin()
        lms.getFinals(UID, sem(semester)).map { FinalUi(it.subject, it.stream, it.date, it.from, it.room, it.grade) }
    }

    fun profile() = Res("profile", ProfileUi.serializer(), ttl = 30L * 60 * 1000) {
        requireLogin()
        val i = lms.getStudentInfo(UID) ?: throw LmsUnavailableException()
        if (!i.fullName.isNullOrBlank()) Prefs.displayName = i.fullName.trim()
        ProfileUi(i.fullName, i.birthDate, i.gender, i.recordBook, i.address, i.direction, i.language,
            i.degree, i.studyType, i.course, i.group, i.curator, i.scholarship)
    }

    fun photo() = Res("photo", PhotoUi.serializer(), ttl = 24L * 60 * 60 * 1000) {
        requireLogin()
        PhotoUi(lms.getProfilePhotoDataUrl(UID))
    }

    /** Пока LMS не выставила контракт, ответ не кэшируется — суммы покажутся, как только появятся. */
    fun contract() = Res("contract", ContractUi.serializer(), ttl = 2L * 60 * 1000) {
        requireLogin()
        val c = lms.getContract(UID)
        val amounts = c != null && c.notice() == null && (c.total() != null || c.paid() != null || c.debt() != null)
        if (amounts) ContractUi(true, null, Mappers.academicYear(), c!!.total(), c.paid(), c.debt())
        else ContractUi(false, c?.notice(), Mappers.academicYear())
    }

    suspend fun changePassword(old: String, new: String, confirm: String): Boolean = withContext(Dispatchers.IO) {
        requireLogin()
        lms.changePassword(UID, old, new, confirm)
    }

    suspend fun upload(courseId: Int, activityId: String, file: File, name: String): Boolean = withContext(Dispatchers.IO) {
        requireLogin()
        val ok = lms.uploadActivityFile(UID, courseId, activityId, file, name)
        if (ok) invalidate("act:$courseId", "dl:")
        ok
    }

    // ─────────────────────────────────────────────
    //  ФАЙЛЫ
    // ─────────────────────────────────────────────

    /** Скачивать разрешено только с домена университета (lms.tuit.uz, dl.tuit.uz…). */
    fun lmsUrl(url: String): String {
        val base = lms.baseUrl()
        val full = if (url.startsWith("http://") || url.startsWith("https://")) url else base + (if (url.startsWith("/")) "" else "/") + url
        val host = runCatching { URI.create(full.replace(" ", "%20")).host }.getOrNull()?.lowercase()
        val baseHost = URI.create(base).host.lowercase()
        val labels = baseHost.split('.')
        val root = labels.takeLast(2).joinToString(".")
        if (host != null && (host == baseHost || host == root || host.endsWith(".$root"))) return full
        throw ApiException("bad_url")
    }

    suspend fun download(url: String, name: String): LmsService.DownloadedFile = withContext(Dispatchers.IO) {
        requireLogin()
        try {
            lms.downloadFile(UID, lmsUrl(url), name)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 404") == true) throw ApiException("file_not_found")
            throw ApiException("download_failed")
        }
    }

    // ─────────────────────────────────────────────
    //  ПРЕПОДАВАТЕЛЬ И ТЬЮТОР
    // ─────────────────────────────────────────────

    private fun <T> teacher(load: () -> T): T {
        requireLogin()
        if (!lms.isTeacher(UID)) throw ApiException("not_teacher")
        return try {
            load()
        } catch (e: ApiException) {
            throw e
        } catch (e: NotLoggedInException) {
            throw e
        } catch (e: Exception) {
            throw LmsUnavailableException(e.message ?: "lms_unavailable")
        }
    }

    private suspend fun <T> tcall(load: () -> T): T = withContext(Dispatchers.IO) { teacher(load) }

    fun tCourses(semester: Int) = Res<List<TeacherService.TCourse>>("tcourses:$semester", null) { teacher { ts.courses(UID, sem(semester)) } }

    fun tCalendar(courseId: Int) = Res<TeacherService.TCalendar>("tcal:$courseId", null) {
        teacher { ts.calendar(UID, courseId).also { ts.rememberStream(UID, courseId, it.stream()) } }
    }

    fun tAttendance(courseId: Int, lessonId: Int) = Res<TeacherService.TAttendance>("tatt:$courseId:$lessonId", null) {
        teacher { ts.attendance(UID, courseId, lessonId) }
    }

    suspend fun tSaveAttendance(courseId: Int, lessonId: Int, absent: Collection<Int>): FormResult = tcall {
        ts.saveAttendance(UID, courseId, lessonId, absent)
    }.also { invalidate("tatt:$courseId:$lessonId", "tcal:$courseId") }

    fun tSheet(courseId: Int) = Res<TeacherService.GradeSheet>("tsheet:$courseId", null) { teacher { ts.gradeSheet(UID, courseId) } }

    fun tActivities(courseId: Int) = Res<TeacherService.TActivities>("tact:$courseId", null) { teacher { ts.activities(UID, courseId) } }

    suspend fun tCreateActivity(courseId: Int, name: String, deadline: String, max: String, criteria: List<Pair<String, String>>, file: File?, fileName: String?): FormResult = tcall {
        ts.createActivity(UID, courseId, name.trim(), deadline, number(max), criteria.map { arrayOf(it.first.trim(), number(it.second)) }, file, fileName)
    }.also { invalidate("tact:$courseId", "tsheet:$courseId", "tgrading") }

    suspend fun tDeleteActivity(courseId: Int, id: Int): FormResult = tcall { ts.deleteActivity(UID, courseId, id) }
        .also { invalidate("tact:$courseId", "tsheet:$courseId", "tgrading") }

    suspend fun tGradeForm(student: Int, activity: Int): TeacherService.GradeForm = tcall { ts.gradeForm(UID, student, activity) }

    /** Оценка: проверки — как в TeacherApi (числа с точкой, не больше максимума). */
    suspend fun tSetGrade(student: Int, activity: Int, values: Map<String, String>?, grade: String?, comment: String?): Pair<FormResult, TeacherService.GradeForm?> = tcall {
        val form = ts.gradeForm(UID, student, activity)
        if (!form.editable()) throw ApiException("locked")
        val vals = LinkedHashMap<String, String>()
        values?.forEach { (k, v) -> vals[k] = number(v) }
        for (c in form.criteria()) {
            val v = vals[c.id()] ?: continue
            if (!within(v, c.max())) throw ApiException("out_of_range")
        }
        val g = grade?.let { number(it) }
        if (g != null && !within(g, form.maxPoint())) throw ApiException("out_of_range")
        val res = ts.setGrade(UID, student, activity, form, vals, g, comment)
        res to (if (res.ok()) ts.gradeForm(UID, student, activity) else null)
    }.also { invalidate("tsheet:", "tgrading") }

    suspend fun tClearGrade(student: Int, activity: Int): FormResult = tcall { ts.clearGrade(UID, student, activity) }
        .also { invalidate("tsheet:", "tgrading") }

    fun tGrading() = Res<List<TeacherService.GradingItem>>("tgrading", null) {
        teacher { ts.grading(UID) }.also { list ->
            // Краткая выжимка — для виджетов и напоминаний, которые работают без сети.
            runCatching {
                file("grading_summary").writeText(Json.encodeToString(ListSerializer(GradingUi.serializer()), list.map(Mappers::grading)))
                _changes.tryEmit("grading_summary")
            }
        }
    }

    fun gradingSummary(): List<GradingUi> = runCatching {
        Json.decodeFromString(ListSerializer(GradingUi.serializer()), file("grading_summary").readText())
    }.getOrDefault(emptyList())

    fun tFinals(semester: Int) = Res<List<TeacherService.TFinal>>("tfinals:$semester", null) { teacher { ts.finals(UID, sem(semester)) } }

    fun tProfile() = Res<TeacherService.TeacherInfo>("tprofile", null, ttl = 30L * 60 * 1000) {
        teacher { ts.info(UID) }.also { if (!it.fullName().isNullOrBlank()) Prefs.displayName = it.fullName().trim() }
    }

    data class Appeals(val page: TeacherService.AppealPage, val items: List<TeacherService.TAppeal>)

    fun tAppeals() = Res("tappeals", null) { teacher { Appeals(ts.appealPage(UID), ts.appeals(UID)) } }
    suspend fun tAppealLessons(stream: String) = tcall { ts.appealLessons(UID, stream) }
    suspend fun tAppealStudents(lesson: String) = tcall { ts.appealStudents(UID, lesson) }
    suspend fun tCreateAppeal(stream: String, lesson: String, pair: String, student: String): FormResult = tcall {
        if (!pair.matches(Regex("\\d{1,2}"))) throw ApiException("bad_pair")
        ts.createAppeal(UID, stream, lesson, pair, student)
    }.also { invalidate("tappeals") }

    suspend fun tDeleteAppeal(id: Int): FormResult = tcall { ts.deleteAppeal(UID, id) }.also { invalidate("tappeals") }
    fun tAppealPdfUrl(id: Int): String = ts.appealPdfUrl(id)

    fun tSubjects(semester: Int) = Res<List<TeacherService.TSubject>>("tsubjects:$semester", null) { teacher { ts.materialSubjects(UID, sem(semester)) } }
    fun tMaterialPage(subject: Int, semester: Int, lang: String) =
        Res<TeacherService.MaterialPage>("tmpage:$subject:$semester:$lang", null, ttl = 60L * 60 * 1000) { teacher { ts.materialPage(UID, subject, sem(semester), lang) } }
    fun tTopics(subject: Int, semester: Int, lang: String, type: String) =
        Res<List<TeacherService.TTopic>>("ttopics:$subject:$semester:$lang:$type", null) { teacher { ts.materialTopics(UID, subject, sem(semester), type, lang) } }

    suspend fun tAddMaterial(subject: Int, semester: Int, topic: Int, lang: String, type: String, name: String?, url: String?, file: File?, fileName: String?): FormResult = tcall {
        if (file == null && (url == null || !url.matches(Regex("(?i)https?://\\S+")))) throw ApiException("bad_url")
        ts.addMaterial(UID, subject, sem(semester), topic, lang, type, name, url, file, fileName)
    }.also { invalidate("ttopics:") }

    suspend fun tDeleteMaterial(id: Int): FormResult = tcall { ts.deleteMaterial(UID, id) }.also { invalidate("ttopics:") }

    fun tGroups() = Res<List<TeacherService.TGroup>>("tgroups", null) { teacher { ts.tutorGroups(UID) } }
    fun tGroup(id: Int) = Res<List<TeacherService.TStudent>>("tgroup:$id", null) { teacher { ts.tutorStudents(UID, id) } }
    fun tStudent(id: Int) = Res<TeacherService.TutorStudent>("tstudent:$id", null, ttl = 10L * 60 * 1000) { teacher { ts.tutorStudent(UID, id) } }
    fun tStudentCourses(user: Int, semester: String) =
        Res<List<TeacherService.TStudentCourse>>("tsc:$user:$semester", null) { teacher { ts.tutorStudentCourses(UID, user, semester) } }
    fun tStudentPlan(id: Int) = Res("tsp:$id", ListSerializer(PlanSubjectUi.serializer())) {
        teacher { ts.tutorStudyPlan(UID, id).map { PlanSubjectUi(it.name ?: "", it.credits, it.grade, it.semester) } }
    }

    data class TutorSchedule(val events: List<uz.tuit.lmsbot.model.ScheduleEvent>, val meta: TeacherService.ScheduleMeta?)

    fun tStudentSchedule(id: Int, user: Int?, semester: String) = Res("tss:$id:$semester", null, ttl = 10L * 60 * 1000) {
        teacher { TutorSchedule(ts.tutorStudentSchedule(UID, id, user, semester), runCatching { ts.scheduleMeta(UID) }.getOrNull()) }
    }

    // ─────────────────────────────────────────────

    /** Число с точкой: «4,5» → «4.5»; не число — ошибка. */
    private fun number(s: String): String {
        val d = try {
            BigDecimal(s.trim().replace(',', '.')).stripTrailingZeros()
        } catch (e: NumberFormatException) {
            throw ApiException("bad_number")
        }
        if (d.signum() < 0) throw ApiException("out_of_range")
        return if (d.scale() < 0) d.setScale(0).toPlainString() else d.toPlainString()
    }

    private fun within(value: String, max: String?): Boolean =
        runCatching { BigDecimal(value) <= BigDecimal(max) }.getOrDefault(true)
}

sealed interface LoginStep {
    data object Done : LoginStep
    data object NeedCode : LoginStep
    data class CodeSent(val phone: String) : LoginStep
    data class Error(val reason: String?) : LoginStep
}
