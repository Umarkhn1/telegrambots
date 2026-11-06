package uz.tuit.lms.ui.screens.teacher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uz.tuit.lms.data.Files
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.parseDmy
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.data.today
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.components.AppCard
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.IconTile
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListRow
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalSnack
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.RowSub
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.components.rememberConfirm
import uz.tuit.lms.ui.theme.LocalTones
import uz.tuit.lms.ui.theme.Tabular
import uz.tuit.lmsbot.service.TeacherService.AttStudent
import uz.tuit.lmsbot.service.TeacherService.TLesson
import java.time.LocalDate

/**
 * Пара для переклички. С датой (ISO из расписания) — занятие этого дня; без неё — как в боте:
 * сегодняшнее, иначе последнее неотмеченное, иначе ближайшее.
 */
fun pickLesson(lessons: List<TLesson>, iso: String?): TLesson? {
    if (iso != null) {
        val want = runCatching { LocalDate.parse(iso) }.getOrNull()
        return lessons.firstOrNull { parseDmy(it.date()) == want }
    }
    val today = today()
    lessons.firstOrNull { parseDmy(it.date()) == today }?.let { return it }
    lessons.filter { val d = parseDmy(it.date()); d != null && d < today && !it.marked() }.lastOrNull()?.let { return it }
    return lessons.firstOrNull { val d = parseDmy(it.date()); d != null && d > today }
}

@Composable
fun RollCallScreen(route: Route.RollCall) {
    val t = LocalTr.current
    val cal = rememberQuery("tcal:${route.courseId}") { Repo.tCalendar(route.courseId) }
    val lesson = remember(cal.data, route.date) { cal.data?.let { pickLesson(it.lessons(), route.date) } }
    val lessonId = lesson?.lessonId()
    val att = rememberQuery(if (lessonId != null) "tatt:${route.courseId}:$lessonId" else null) { Repo.tAttendance(route.courseId, lessonId!!) }

    // Правки хранятся отдельно от данных LMS: null — ничего не меняли.
    var edited by remember(lessonId) { mutableStateOf<Set<Int>?>(null) }
    var saved by remember(lessonId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val confirm = rememberConfirm()
    val context = LocalContext.current

    val a = att.data
    val editable = a != null && a.editable() && a.students().all { it.id() != null }
    val serverAbsent = remember(a) { a?.students()?.filter { it.present() == false && it.id() != null }?.map { it.id()!! }?.toSet() ?: emptySet() }
    val absentIds = edited ?: serverAbsent
    // Пока перекличку не трогали и НБ ещё нет, никто не отмечен: пустое состояние, а не «все были».
    val touched = edited != null || serverAbsent.isNotEmpty() || saved || lesson?.marked() == true
    fun isAbsent(s: AttStudent) = if (editable) s.id() in absentIds else s.present() == false
    fun isPresent(s: AttStudent) = if (editable) touched && s.id() !in absentIds else s.present() == true

    fun save() {
        confirm.ask(t.tt("att_confirm", "n" to absentIds.size), t.tt("att_save")) {
            saving = true
            scope.launch {
                val res = runCatching { Repo.tSaveAttendance(route.courseId, lessonId!!, absentIds) }.getOrNull()
                saving = false
                if (res == null || !res.ok()) {
                    val why = res?.errors()?.firstOrNull()
                    snack.show(when (why) {
                        "closed" -> t.tt("att_closed")
                        "not_saved" -> t.tt("att_not_saved")
                        else -> res?.errors()?.joinToString(", ")?.ifBlank { null } ?: t.tt("lms_rejected")
                    }, error = true)
                } else {
                    saved = true
                    edited = null
                    snack.show(t.tt("att_saved"))
                }
            }
        }
    }

    DetailScreen(
        title = lesson?.let { "${it.number()}. ${it.topic()}" } ?: route.title ?: t.tt("roll_call"),
        subtitle = cal.data?.let { c -> c.stream() + (lesson?.let { " · " + it.date() } ?: "") },
        refreshing = att.refreshing || cal.refreshing,
        onRefresh = { cal.refresh(); att.refresh() },
        bottomBar = {
            if (editable) Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { Files.openUrl(context, "${Repo.lms.baseUrl()}/teacher/attendance/show/${route.courseId}/$lessonId") }, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    }
                    Button(onClick = ::save, enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.HowToReg, null)
                        Spacer(Modifier.width(8.dp))
                        Text(t.tt("att_save"))
                    }
                }
            }
        },
    ) {
        when {
            cal.initialLoading || (lessonId != null && att.initialLoading) -> item { ListSkeleton(6) }
            cal.data == null -> item { ErrorState(cal.error, cal::refresh) }
            lessonId == null -> item { EmptyState(Icons.Rounded.HowToReg, t.tt("roll_call_none"), t.tt("roll_call_none_sub")) }
            a == null -> item { ErrorState(att.error, att::refresh) }
            else -> {
                val students = a.students()
                val absent = students.filter(::isAbsent)
                val present = students.filter(::isPresent)
                val ordered = if (editable) students else absent + students.filter { it.present() == null } + present
                item {
                    val tones = LocalTones.current
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppCard(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                            Text(t.tt("present"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (editable && !touched) "—" else present.size.toString(), style = MaterialTheme.typography.headlineSmall.merge(Tabular), fontWeight = FontWeight.Bold, color = tones.success)
                        }
                        AppCard(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                            Text(t.tt("absent"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (editable && !touched) "—" else absent.size.toString(), style = MaterialTheme.typography.headlineSmall.merge(Tabular), fontWeight = FontWeight.Bold, color = if (absent.isNotEmpty()) tones.danger else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                item {
                    val hint = if (editable) t.tt("att_tap_hint") else (if (present.isEmpty() && absent.isEmpty()) t.tt("roll_call_unmarked") + "\n" else "") + t.tt("att_closed")
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                }
                item {
                    ListGroup(ordered, key = { it.number() }) { _, s, shape ->
                        val abs = isAbsent(s)
                        val pres = isPresent(s)
                        ListRow(
                            shape,
                            onClick = if (editable) ({
                                val next = absentIds.toMutableSet()
                                if (!next.remove(s.id()!!)) next.add(s.id()!!)
                                edited = next
                            }) else null,
                            color = if (abs) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
                            leading = {
                                IconTile(
                                    if (abs) Icons.Rounded.Close else if (pres) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    if (abs) Tone.DANGER else if (pres) Tone.SUCCESS else Tone.NEUTRAL,
                                )
                            },
                            chevron = false,
                        ) {
                            Text("${s.number()}. ${s.name()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (!s.group().isNullOrBlank()) RowSub(s.group())
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(saved) { if (saved) att.refresh() }
}
