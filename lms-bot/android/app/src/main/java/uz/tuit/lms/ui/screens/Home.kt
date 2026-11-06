package uz.tuit.lms.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.tuit.lms.data.ActStatus
import uz.tuit.lms.data.ActivityUi
import uz.tuit.lms.data.LessonUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Query
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.ScheduleUi
import uz.tuit.lms.data.Tr
import uz.tuit.lms.data.gpa
import uz.tuit.lms.data.greetingKey
import uz.tuit.lms.data.pickDay
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.data.today
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.Tab
import uz.tuit.lms.ui.components.ActivityRow
import uz.tuit.lms.ui.components.ActivitySheet
import uz.tuit.lms.ui.components.Avatar
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.LessonRow
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.OfflineBanner
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.StatCard
import uz.tuit.lms.ui.components.TabScreen
import uz.tuit.lms.ui.components.Tone
import java.time.LocalTime

fun greetingTitle(t: Tr): String {
    val name = Repo.firstName()
    val g = t(greetingKey(LocalTime.now(uz.tuit.lms.data.ZONE).hour))
    return if (name.isNotBlank()) "$g, $name" else g
}

fun dateLine(t: Tr): String {
    val d = today()
    return t.weekday(d).replaceFirstChar { it.titlecase() } + ", " + t.dayMonth(d)
}

@Composable
fun HomeScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    var open by remember { mutableStateOf<ActivityUi?>(null) }

    val schedule = rememberQuery("sched:$sem") { Repo.schedule(sem) }
    val deadlines = rememberQuery("dl:$sem") { Repo.deadlines(sem) }
    val courses = rememberQuery("courses:$sem") { Repo.courses(sem) }
    val plan = rememberQuery("plan") { Repo.studyPlan() }
    val photo = rememberQuery("photo") { Repo.photo() }

    val pending = deadlines.data?.filter { it.status() == ActStatus.OPEN } ?: emptyList()
    val gpaValue = plan.data?.let { gpa(it, false).value }
    val nb = courses.data?.sumOf { it.attendance }
    val refreshing = schedule.refreshing || deadlines.refreshing || courses.refreshing
    val offline = listOf(schedule, deadlines, courses).any { it.error != null && it.data != null }

    TabScreen(
        title = greetingTitle(t),
        subtitle = dateLine(t),
        refreshing = refreshing,
        onRefresh = { schedule.refresh(); deadlines.refresh(); courses.refresh(); plan.refresh() },
        actions = {
            IconButton(onClick = { nav.select(Tab.PROFILE) }, modifier = Modifier.padding(end = 6.dp)) {
                Avatar(photo.data?.dataUrl, Prefs.displayName, 36.dp, shape = CircleShape)
            }
        },
    ) {
        item { OfflineBanner(offline) { schedule.refresh(); deadlines.refresh(); courses.refresh() } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(Icons.Rounded.TrendingUp, Tone.ACCENT, gpaValue?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—", t("stat_gpa"), plan.initialLoading, Modifier.weight(1f)) { nav.select(Tab.GRADES) }
                StatCard(Icons.Rounded.PersonOff, if ((nb ?: 0) > 0) Tone.DANGER else Tone.SUCCESS, nb?.toString() ?: "—", t("stat_nb"), courses.initialLoading, Modifier.weight(1f)) { nav.select(Tab.COURSES) }
                StatCard(Icons.Rounded.Checklist, if (pending.isNotEmpty()) Tone.WARNING else Tone.SUCCESS, if (deadlines.data != null) pending.size.toString() else "—", t("deadlines_title"), deadlines.initialLoading, Modifier.weight(1f)) { nav.push(Route.Deadlines) }
            }
        }
        daySection(t, schedule, onAll = { nav.select(Tab.SCHEDULE) }, onOpen = nav::openLesson)

        item { SectionHeader(t("upcoming_deadlines"), action = if (pending.isNotEmpty()) t("see_all") else null) { nav.push(Route.Deadlines) } }
        when {
            deadlines.initialLoading -> item { ListSkeleton(3, tall = true) }
            pending.isNotEmpty() -> item {
                ListGroup(pending.take(4), key = { it.key }) { _, d, shape -> ActivityRow(d, shape, showCourse = true) { open = d } }
            }
            else -> item { EmptyState(Icons.Rounded.TaskAlt, t("no_deadlines"), t("no_deadlines_sub"), Tone.SUCCESS) }
        }
    }
    ActivitySheet(open, open?.courseId ?: 0, open?.course) { open = null }
}

/** Пары на сегодня (или на завтра, если сегодня уже всё) — и для студента, и для преподавателя. */
fun LazyListScope.daySection(t: Tr, schedule: Query<ScheduleUi>, onAll: () -> Unit, onRollCall: ((LessonUi) -> Unit)? = null, onOpen: ((LessonUi) -> Unit)? = null) {
    val day = pickDay(schedule.data?.lessons ?: emptyList())
    item { SectionHeader(t(day.key), action = t("schedule_title"), onAction = onAll) }
    when {
        schedule.initialLoading -> item { ListSkeleton(3) }
        day.list.isNotEmpty() -> item {
            ListGroup(day.list, key = { it.ts.toString() + it.subject + it.stream }) { _, l, shape ->
                LessonRow(l, shape, onRollCall = if (onRollCall != null && l.courseId != null) ({ onRollCall(l) }) else null,
                    onClick = if (onOpen != null && l.courseId != null) ({ onOpen(l) }) else null)
            }
        }
        else -> item {
            EmptyState(Icons.Rounded.EventAvailable, if (day.over) t("classes_over") else t("no_classes_today"), t("no_classes_sub"), Tone.SUCCESS)
        }
    }
}
