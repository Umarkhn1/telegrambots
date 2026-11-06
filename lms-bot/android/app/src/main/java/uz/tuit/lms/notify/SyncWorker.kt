package uz.tuit.lms.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import uz.tuit.lms.data.ActStatus
import uz.tuit.lms.data.ActivityUi
import uz.tuit.lms.data.Auth
import uz.tuit.lms.data.I18n
import uz.tuit.lms.data.Json
import uz.tuit.lms.data.LessonUi
import uz.tuit.lms.data.Mappers
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.duration
import uz.tuit.lms.data.parseDmy
import uz.tuit.lms.data.toLocal
import uz.tuit.lms.widget.Widgets
import java.util.concurrent.TimeUnit

/**
 * Фоновая проверка LMS раз в полчаса (и по запросу): держит сессию живой, обновляет данные
 * виджетов и присылает уведомления — как бот: новый НБ, срочный дедлайн, новая оценка;
 * преподавателю — работы на проверку и неотмеченная перекличка.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching {
            val u = uz.tuit.lms.data.Updater.check()
            if (u != null && Prefs.int("update_notified", 0) < u.code) {
                Prefs.setInt("update_notified", u.code)
                Notifier.show(applicationContext, Notifier.idFor("update"), Notifier.CH_DIGEST,
                    I18n.current()("update_title"), u.name, "update")
            }
        }
        if (!Prefs.loggedIn) return Result.success()
        val auth = runCatching { Repo.ensureSession() }.getOrNull()
        if (auth !is Auth.LoggedIn || auth.offline) {
            Widgets.updateAll(applicationContext)
            return if (auth is Auth.LoggedOut) Result.success() else Result.retry()
        }
        val sem = Repo.currentSemester()
        try {
            // Расписание само кэшируется на час; свежее — для будильников и виджетов.
            if (sem > 0) runCatching { Repo.get(Repo.schedule(sem)) }
            if (auth.teacher) teacher() else if (sem > 0) student(sem)
        } finally {
            Reminders.reschedule(applicationContext)
            Widgets.updateAll(applicationContext)
        }
        return Result.success()
    }

    private val ctx get() = applicationContext
    private val t get() = I18n.current()
    private val mapSer = MapSerializer(String.serializer(), String.serializer())

    private fun loadMap(key: String): MutableMap<String, String>? =
        Prefs.str(key)?.let { runCatching { Json.decodeFromString(mapSer, it).toMutableMap() }.getOrNull() }

    private fun saveMap(key: String, m: Map<String, String>) = Prefs.setStr(key, Json.encodeToString(mapSer, m))

    private suspend fun student(sem: Int) {
        val courses = runCatching { Repo.get(Repo.courses(sem), fresh = true) }.getOrNull() ?: return

        // Новые НБ: сравниваем с прошлой проверкой.
        val nbPrev = loadMap("nb_map")
        val nbNow = courses.associate { it.id.toString() to it.attendance.toString() }
        if (nbPrev != null && Prefs.flag("nb")) {
            for (c in courses) {
                val old = nbPrev[c.id.toString()]?.toIntOrNull() ?: continue
                if (c.attendance > old) Notifier.show(
                    ctx, Notifier.idFor("nb|${c.id}|${c.attendance}"), Notifier.CH_ATTENDANCE,
                    t("notif_nb_title", "subject" to c.subject),
                    t("notif_nb_text", "n" to c.attendance, "old" to old),
                    "att:${c.id}:${c.subject}",
                )
            }
        }
        saveMap("nb_map", nbNow)

        // Все задания по предметам: срочные дедлайны и новые оценки.
        val now = System.currentTimeMillis()
        val all = ArrayList<ActivityUi>()
        for (c in courses) {
            val acts = runCatching { Repo.get(Repo.activities(c.id), fresh = true) }.getOrNull() ?: continue
            all += acts.activities.map { it.copy(courseId = c.id, course = c.subject) }
        }
        if (all.isEmpty()) return
        Repo.put(Repo.deadlines(sem), all.filter { it.submitted || (it.deadlineTs ?: 0) >= now }.sortedBy { it.deadlineTs ?: Long.MAX_VALUE })

        val gradesPrev = loadMap("grades_map")
        val gradesNow = all.filter { it.graded }.associate { it.key to (it.earned ?: "") }
        if (gradesPrev != null && Prefs.flag("grades")) {
            for (a in all) {
                val v = gradesNow[a.key] ?: continue
                if (gradesPrev[a.key] == v) continue
                Notifier.show(
                    ctx, Notifier.idFor("grade|${a.key}|$v"), Notifier.CH_GRADES,
                    t("notif_grade_title", "score" to "$v / ${a.max ?: "—"}"),
                    "${a.course} · ${a.task}",
                    "course:${a.courseId}:${a.course}",
                    big = "${a.course}\n${a.task}",
                )
            }
        }
        saveMap("grades_map", gradesNow)

        // Баллы итоговых экзаменов — тоже новыми оценками.
        val finals = runCatching { Repo.get(Repo.finals(sem), fresh = true) }.getOrNull()
        if (finals != null) {
            val finPrev = loadMap("finals_map")
            val finNow = finals.filter { uz.tuit.lms.data.num(it.grade)?.let { g -> g > 0 } == true }
                .associate { (it.subject ?: "") + "|" + (it.date ?: "") to (it.grade ?: "") }
            if (finPrev != null && Prefs.flag("grades")) {
                for ((k, v) in finNow) {
                    if (finPrev[k] == v) continue
                    Notifier.show(
                        ctx, Notifier.idFor("final|$k|$v"), Notifier.CH_GRADES,
                        t("notif_final_title", "score" to v),
                        k.substringBefore('|'),
                        "grades",
                    )
                }
            }
            saveMap("finals_map", finNow)
        }

        // Срочные дедлайны (≤ 2 дней, работа не загружена) — с интервалом из настроек и «напомнить позже».
        if (Prefs.flag("deadlines")) {
            val interval = Prefs.int("deadline_interval", 240) * 60_000L
            for (a in all) {
                val dl = a.deadlineTs ?: continue
                if (a.status(now) != ActStatus.OPEN || a.uploaded != null || dl - now > 2 * 24 * 3600_000L) continue
                val key = "dl_" + Integer.toHexString(a.key.hashCode())
                if (now < Prefs.long("next_$key")) continue
                if (now - Prefs.long("last_$key") < interval) continue
                Prefs.setLong("last_$key", now)
                val id = Notifier.idFor(key)
                Notifier.show(
                    ctx, id, Notifier.CH_DEADLINES,
                    t("notif_deadline_title", "x" to duration(dl - now, t)),
                    "${a.course} · ${a.task}",
                    "course:${a.courseId}:${a.course}",
                    big = "${a.course}\n${a.task}\n${t("deadline")}: ${a.deadline ?: ""}",
                    actions = listOf(
                        Notifier.Action(t("notif_snooze", "x" to "1 ${t("u_h")}"), SnoozeReceiver.intent(ctx, key, id, 60)),
                        Notifier.Action(t("notif_snooze", "x" to "3 ${t("u_h")}"), SnoozeReceiver.intent(ctx, key, id, 180)),
                    ),
                    whenMs = dl,
                )
            }
        }
    }

    private suspend fun teacher() {
        val now = System.currentTimeMillis()
        if (Prefs.flag("grading")) {
            val items = runCatching { Repo.get(Repo.tGrading(), fresh = true) }.getOrNull()
            if (items != null) {
                // Как у бота: с закрытия приёма до конца срока оценивания (по умолчанию 3 дня), пока есть непроверенные.
                val due = items.filter {
                    val end = if (it.teacherTs() > 0) it.teacherTs() else it.studentTs() + 3 * 24 * 3600_000L
                    it.pending() > 0 && it.studentTs() <= now && now < end
                }
                val key = "grading"
                if (due.isNotEmpty() && now >= Prefs.long("next_$key") && now - Prefs.long("last_$key") >= 3 * 3600_000L) {
                    Prefs.setLong("last_$key", now)
                    val total = due.sumOf { it.pending() }
                    val first = Mappers.grading(due.first())
                    val id = Notifier.idFor(key)
                    Notifier.show(
                        ctx, id, Notifier.CH_TEACHER,
                        t("notif_grading_title", "n" to total),
                        due.joinToString(" · ") { (it.deadline().activity() ?: "") + " (" + it.pending() + ")" }.take(140),
                        if (due.size == 1 && first.courseId != null) "tcourse:${first.courseId}:${first.column}" else "grading",
                        lines = due.map { "${it.pending()} · ${it.deadline().activity()} · ${it.deadline().stream() ?: it.deadline().subject()}" },
                        actions = listOf(
                            Notifier.Action(t("notif_snooze", "x" to "1 ${t("u_h")}"), SnoozeReceiver.intent(ctx, key, id, 60)),
                            Notifier.Action(t("notif_snooze", "x" to "3 ${t("u_h")}"), SnoozeReceiver.intent(ctx, key, id, 180)),
                        ),
                    )
                }
            }
        }
        // Пара закончилась, а перекличка в LMS не отмечена — напоминаем раз в 2 часа в течение суток.
        if (Prefs.flag("rollcall")) {
            val sem = Repo.currentSemester()
            val lessons: List<LessonUi> = Repo.peek(Repo.schedule(sem))?.value?.lessons ?: emptyList()
            val ended = lessons.filter { it.courseId != null && it.endTs < now && now - it.endTs < 24 * 3600_000L }
            for (l in ended) {
                val key = "roll_${l.courseId}_${l.date}"
                if (now - Prefs.long("last_$key") < 2 * 3600_000L || now < Prefs.long("next_$key")) continue
                val cal = runCatching { Repo.get(Repo.tCalendar(l.courseId!!), fresh = true) }.getOrNull() ?: continue
                val date = l.ts.toLocal().toLocalDate()
                val lesson = cal.lessons().firstOrNull { parseDmy(it.date()) == date } ?: continue
                if (lesson.marked()) continue
                Prefs.setLong("last_$key", now)
                Notifier.show(
                    ctx, Notifier.idFor(key), Notifier.CH_TEACHER,
                    t("notif_rollcall_missed", "subject" to l.subject),
                    "${l.time.take(5)}" + (l.stream?.let { " · $it" } ?: "") + " — " + t("notif_rollcall_sub"),
                    "rollcall:${l.courseId}:${l.date}",
                )
            }
        }
    }

    companion object {
        private const val PERIODIC = "lms-sync"
        private const val NOW = "lms-sync-now"

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Обновить сейчас (кнопка на виджете, смена языка). */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun runAt(context: Context, minutes: Long) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net)
                .setInitialDelay(minutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("lms-sync-at-$minutes", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
