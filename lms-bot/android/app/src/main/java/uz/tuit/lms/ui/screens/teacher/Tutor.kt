package uz.tuit.lms.ui.screens.teacher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SupervisorAccount
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.gpa
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.data.roman
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.Tab
import uz.tuit.lms.ui.components.AppCard
import uz.tuit.lms.ui.components.Avatar
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.IconTile
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListRow
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Meta
import uz.tuit.lms.ui.components.MetaRow
import uz.tuit.lms.ui.components.Opt
import uz.tuit.lms.ui.components.Pill
import uz.tuit.lms.ui.components.RowSub
import uz.tuit.lms.ui.components.RowTitle
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.Segmented
import uz.tuit.lms.ui.components.StatCard
import uz.tuit.lms.ui.components.StreamChip
import uz.tuit.lms.ui.components.TabScreen
import uz.tuit.lms.ui.components.TextTile
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.screens.dateLine
import uz.tuit.lms.ui.screens.gradeTone
import uz.tuit.lms.ui.theme.Tabular

fun nbTone(n: Int) = when {
    n >= 5 -> Tone.DANGER
    n >= 3 -> Tone.WARNING
    n > 0 -> Tone.ACCENT
    else -> Tone.SUCCESS
}

/**
 * Переключатель «Преподаватель / Тьютор». Показывается только тем, кому LMS дала группу:
 * в меню сайта есть переключатель роли (LmsService.hasTutor).
 */
@Composable
fun RoleSwitch() {
    val t = LocalTr.current
    val tutorMode by Prefs.tutorMode.collectAsState()
    AppCard(Modifier.padding(bottom = 14.dp), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) {
            Icon(Icons.Rounded.SupervisorAccount, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(t("role_mode"), style = MaterialTheme.typography.titleSmall)
        }
        Segmented(
            listOf(Opt(false, t.tt("role_teacher")), Opt(true, t.tt("role_tutor").replaceFirstChar { it.titlecase() })),
            tutorMode, Prefs::setTutorMode,
        )
    }
}

/** Главная тьютора: группы, сколько студентов и у кого больше всего НБ. */
@Composable
fun TutorHomeScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val groups = rememberQuery("tgroups") { Repo.tGroups() }
    val photo = rememberQuery("photo") { Repo.photo() }

    TabScreen(
        title = t.tt("my_groups"),
        subtitle = dateLine(t) + " · " + t.tt("role_tutor").replaceFirstChar { it.titlecase() },
        refreshing = groups.refreshing,
        onRefresh = { groups.refresh(); Repo.invalidate("tgroup:") },
        actions = {
            IconButton(onClick = { nav.select(Tab.PROFILE) }, modifier = Modifier.padding(end = 6.dp)) {
                Avatar(photo.data?.dataUrl, Prefs.displayName, 36.dp, shape = CircleShape)
            }
        },
    ) {
        item { RoleSwitch() }
        val list = groups.data
        when {
            groups.initialLoading -> item { ListSkeleton(3) }
            list == null -> item { ErrorState(groups.error, groups::refresh) }
            list.isEmpty() -> item { EmptyState(Icons.Rounded.Groups, t.tt("no_groups")) }
            else -> list.forEach { g ->
                item(key = "g${g.id()}") { GroupDigest(g.id(), g.name() ?: "", g.speciality()) }
            }
        }
    }
}

@Composable
private fun GroupDigest(id: Int, name: String, speciality: String?) {
    val t = LocalTr.current
    val nav = LocalNav.current
    val q = rememberQuery("tgroup:$id") { Repo.tGroup(id) }
    val students = q.data ?: emptyList()
    val risk = students.count { it.attendance() >= 5 }
    val nbTotal = students.sumOf { it.attendance() }
    Column {
        SectionHeader(name, action = t("see_all")) { nav.push(Route.TGroup(id, name, speciality)) }
        if (!speciality.isNullOrBlank()) Text(speciality, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(Icons.Rounded.Groups, Tone.VIOLET, if (q.data != null) students.size.toString() else "—", t.tt("stat_students"), q.initialLoading, Modifier.weight(1f)) { nav.push(Route.TGroup(id, name, speciality)) }
            StatCard(Icons.Rounded.PersonOff, if (nbTotal > 0) Tone.DANGER else Tone.SUCCESS, if (q.data != null) nbTotal.toString() else "—", t("stat_nb"), q.initialLoading, Modifier.weight(1f)) { nav.push(Route.TGroup(id, name, speciality)) }
            StatCard(Icons.Rounded.WarningAmber, if (risk > 0) Tone.WARNING else Tone.SUCCESS, if (q.data != null) risk.toString() else "—", t("risk_students"), q.initialLoading, Modifier.weight(1f)) { nav.push(Route.TGroup(id, name, speciality)) }
        }
        val top = students.filter { it.attendance() > 0 }.sortedByDescending { it.attendance() }.take(5)
        when {
            q.initialLoading -> ListSkeleton(3)
            q.data == null -> ErrorState(q.error, q::refresh)
            top.isEmpty() -> EmptyState(Icons.Rounded.CheckCircle, t("all_attended"), tone = Tone.SUCCESS)
            else -> ListGroup(top, key = { it.id() }) { _, s, shape -> StudentRow(s, shape) { nav.push(Route.TStudent(s.id(), s.fio() ?: "")) } }
        }
    }
}

@Composable
private fun StudentRow(s: uz.tuit.lmsbot.service.TeacherService.TStudent, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val t = LocalTr.current
    ListRow(shape, onClick = onClick, leading = { TextTile(s.attendance().toString(), nbTone(s.attendance())) }) {
        Text(s.fio() ?: "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        RowSub("${t.tt("nb")}: ${s.attendance()}")
    }
}

@Composable
fun TutorGroupsScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val q = rememberQuery("tgroups") { Repo.tGroups() }
    DetailScreen(title = t.tt("my_groups"), subtitle = t.tt("tutor"), refreshing = q.refreshing, onRefresh = q::refresh) {
        when {
            q.initialLoading -> item { ListSkeleton(3) }
            q.data == null -> item { ErrorState(q.error, q::refresh) }
            q.data!!.isEmpty() -> item { EmptyState(Icons.Rounded.Groups, t.tt("no_groups")) }
            else -> item {
                ListGroup(q.data!!) { _, g, shape ->
                    ListRow(shape, onClick = { nav.push(Route.TGroup(g.id(), g.name() ?: "", g.speciality())) }, leading = { IconTile(Icons.Rounded.Groups, Tone.VIOLET) }) {
                        RowTitle(g.name() ?: "")
                        if (!g.speciality().isNullOrBlank()) RowSub(g.speciality())
                    }
                }
            }
        }
    }
}

@Composable
fun TutorGroupScreen(route: Route.TGroup) {
    val t = LocalTr.current
    val nav = LocalNav.current
    val q = rememberQuery("tgroup:${route.id}") { Repo.tGroup(route.id) }
    var sort by rememberSaveable { mutableStateOf("name") }
    DetailScreen(title = route.name, subtitle = route.speciality, refreshing = q.refreshing, onRefresh = q::refresh) {
        item { Segmented(listOf(Opt("name", t("sort_name")), Opt("nb", t("sort_nb"))), sort, { sort = it }, Modifier.padding(bottom = 14.dp)) }
        when {
            q.initialLoading -> item { ListSkeleton(8) }
            q.data == null -> item { ErrorState(q.error, q::refresh) }
            q.data!!.isEmpty() -> item { EmptyState(Icons.Rounded.Groups, "—") }
            else -> item {
                val list = if (sort == "nb") q.data!!.sortedByDescending { it.attendance() } else q.data!!
                ListGroup(list, key = { it.id() }) { _, s, shape -> StudentRow(s, shape) { nav.push(Route.TStudent(s.id(), s.fio() ?: "")) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorStudentScreen(route: Route.TStudent) {
    val t = LocalTr.current
    var tab by rememberSaveable { mutableStateOf("courses") }
    var semPick by rememberSaveable { mutableStateOf<String?>(null) }
    var semOpen by remember { mutableStateOf(false) }
    val info = rememberQuery("tstudent:${route.id}") { Repo.tStudent(route.id) }
    val st = info.data
    val semester = semPick ?: st?.currentSemester()
    val semName = st?.semesters()?.firstOrNull { it.id() == semester }?.text()
    val user = st?.userId()

    val courses = rememberQuery(if (tab == "courses" && user != null && semester != null) "tsc:$user:$semester" else null) { Repo.tStudentCourses(user!!, semester!!) }
    val plan = rememberQuery(if (tab == "plan") "tsp:${route.id}" else null) { Repo.tStudentPlan(route.id) }
    val sched = rememberQuery(if (tab == "schedule" && semester != null) "tss:${route.id}:$semester" else null) { Repo.tStudentSchedule(route.id, user, semester!!) }

    DetailScreen(
        title = route.title, subtitle = st?.title(),
        refreshing = info.refreshing || courses.refreshing || plan.refreshing || sched.refreshing,
        onRefresh = { info.refresh(); when (tab) { "courses" -> courses.refresh(); "plan" -> plan.refresh(); else -> sched.refresh() } },
    ) {
        item {
            Segmented(listOf(Opt("courses", t.tt("subjects_tab")), Opt("plan", t.tt("plan_tab")), Opt("schedule", t.tt("schedule_tab"))), tab, { tab = it }, Modifier.padding(bottom = 12.dp))
        }
        if (tab != "plan" && st != null && st.semesters().isNotEmpty()) item {
            androidx.compose.material3.AssistChip(onClick = { semOpen = true }, label = { Text(semName ?: t.tt("semester")) },
                leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null) }, modifier = Modifier.padding(bottom = 10.dp))
        }
        when {
            info.initialLoading -> item { ListSkeleton(4) }
            st == null -> item { ErrorState(info.error, info::refresh) }
            tab == "courses" -> when {
                user == null -> item { EmptyState(Icons.Rounded.MenuBook, "—") }
                courses.initialLoading -> item { ListSkeleton(5, tall = true) }
                courses.data == null -> item { ErrorState(courses.error, courses::refresh) }
                courses.data!!.isEmpty() -> item { EmptyState(Icons.Rounded.MenuBook, t("no_courses")) }
                else -> item {
                    ListGroup(courses.data!!) { _, c, shape ->
                        ListRow(shape, verticalAlignment = Alignment.Top, leading = { TextTile(c.attendance().toString(), nbTone(c.attendance())) }) {
                            RowTitle(c.subject() ?: "")
                            c.teachers().forEach { x ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                                    if (!x.getOrNull(0).isNullOrBlank()) { StreamChip(x[0]); Spacer(Modifier.width(6.dp)) }
                                    Text(x.getOrNull(1) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            MetaRow {
                                Pill("${t.tt("nb")}: ${c.attendance()}", nbTone(c.attendance()))
                                if (c.failed()) Pill(t("failed"), Tone.WARNING, Icons.Rounded.WarningAmber)
                            }
                        }
                    }
                }
            }
            tab == "plan" -> when {
                plan.initialLoading -> item { ListSkeleton(6) }
                plan.data == null -> item { ErrorState(plan.error, plan::refresh) }
                plan.data!!.isEmpty() -> item { EmptyState(Icons.Rounded.School, "—") }
                else -> {
                    val g = gpa(plan.data!!, false)
                    if (g.value != null) item {
                        AppCard(Modifier.padding(bottom = 4.dp)) {
                            Text(t("stat_gpa"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format(java.util.Locale.US, "%.2f", g.value), style = MaterialTheme.typography.headlineMedium.merge(Tabular), fontWeight = FontWeight.Bold)
                        }
                    }
                    plan.data!!.groupBy { it.semester }.toSortedMap().forEach { (n, list) ->
                        item(key = "s$n") { SectionHeader("${roman(n)} · ${t.tt("semester")}") }
                        item(key = "l$n") {
                            ListGroup(list) { _, s, shape ->
                                ListRow(shape, trailing = { Pill(s.grade?.toString() ?: "—", gradeTone(s.grade)) }) {
                                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                    RowSub("${s.credits} ${t("credits_short")}")
                                }
                            }
                        }
                    }
                }
            }
            else -> when {
                sched.initialLoading -> item { ListSkeleton(5) }
                sched.data == null -> item { ErrorState(sched.error, sched::refresh) }
                sched.data!!.events.isEmpty() -> item { EmptyState(Icons.Rounded.CalendarMonth, t("no_classes_today")) }
                else -> {
                    val meta = sched.data!!.meta
                    val byDay = sched.data!!.events.mapNotNull { e ->
                        val d = runCatching { java.time.LocalDateTime.parse((e.start ?: "").replace(' ', 'T')) }.getOrNull() ?: return@mapNotNull null
                        (d.dayOfWeek.value - 1) to e
                    }.groupBy({ it.first }, { it.second }).toSortedMap()
                    byDay.forEach { (k, list) ->
                        item(key = "d$k") { SectionHeader(t.weekdays[k].replaceFirstChar { it.titlecase() }) }
                        item(key = "e$k") {
                            ListGroup(list.sortedBy { it.start }) { _, e, shape ->
                                val lines = (e.title ?: "").split("\n")
                                val room = lines.first()
                                val rest = lines.drop(1).joinToString(" ").trim('/', ' ')
                                val week = when (e.type) { 2 -> meta?.oddWeek(); 3 -> meta?.evenWeek(); else -> null }
                                ListRow(shape, leading = { Text((e.start ?: "").drop(11).take(5), style = MaterialTheme.typography.titleMedium.merge(Tabular)) }) {
                                    Text(rest.ifBlank { room }, style = MaterialTheme.typography.bodyLarge)
                                    MetaRow(Modifier.padding(top = 4.dp)) {
                                        if (rest.isNotBlank()) Meta(null, room)
                                        if (!week.isNullOrBlank()) Pill(week, Tone.VIOLET)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (semOpen && st != null) {
        ModalBottomSheet(onDismissRequest = { semOpen = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                Text(t.tt("semester"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                ListGroup(st.semesters()) { _, s, shape ->
                    ListRow(shape, color = if (s.id() == semester) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = { semPick = s.id(); semOpen = false }) { RowTitle(s.text() ?: "") }
                }
            }
        }
    }
}
