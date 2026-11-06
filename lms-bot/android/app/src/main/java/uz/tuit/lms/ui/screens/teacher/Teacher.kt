package uz.tuit.lms.ui.screens.teacher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.Tab
import uz.tuit.lms.ui.components.Avatar
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.IconTile
import uz.tuit.lms.ui.components.KvRow
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListRow
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Meta
import uz.tuit.lms.ui.components.MetaRow
import uz.tuit.lms.ui.components.Pill
import uz.tuit.lms.ui.components.RowSub
import uz.tuit.lms.ui.components.RowTitle
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.SemesterChip
import uz.tuit.lms.ui.components.SemesterSheet
import uz.tuit.lms.ui.components.StatCard
import uz.tuit.lms.ui.components.StreamChip
import uz.tuit.lms.ui.components.TabScreen
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.screens.PhotoDialog
import uz.tuit.lms.ui.screens.ProfileCard
import uz.tuit.lms.ui.screens.TONES
import uz.tuit.lms.ui.screens.accountSection
import uz.tuit.lms.ui.screens.dateLine
import uz.tuit.lms.ui.screens.daySection
import uz.tuit.lms.ui.screens.greetingTitle
import uz.tuit.lmsbot.service.TeacherService.GradingItem
import uz.tuit.lmsbot.service.TeacherService.TCourse

/** Сроки, по которым сейчас есть работа: идёт проверка (awaiting) или ещё приём (accepting). */
fun splitGrading(items: List<GradingItem>, now: Long = System.currentTimeMillis()): Pair<List<GradingItem>, List<GradingItem>> {
    val awaiting = items.filter { it.studentTs() <= now && (it.teacherTs() <= 0 || it.teacherTs() > now) }
    val accepting = items.filter { it.studentTs() > now }
    return awaiting to accepting
}

fun routeFor(g: GradingItem, courses: List<TCourse>?): Route.TCourse {
    val d = g.deadline()
    val c = courses?.firstOrNull { it.id() == d.courseId() }
    return Route.TCourse(d.courseId() ?: 0, c?.subject() ?: d.subject() ?: "", c?.type() ?: "", c?.students() ?: 0, c?.stream() ?: d.stream(), g.column().takeIf { it >= 0 })
}

fun routeFor(c: TCourse) = Route.TCourse(c.id(), c.subject() ?: "", c.type() ?: "", c.students(), c.stream())

@Composable
fun TeacherHomeScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    val schedule = rememberQuery("sched:$sem") { Repo.schedule(sem) }
    val courses = rememberQuery("tcourses:$sem") { Repo.tCourses(sem) }
    val grading = rememberQuery("tgrading") { Repo.tGrading() }
    val photo = rememberQuery("photo") { Repo.photo() }

    val (awaiting, _) = splitGrading(grading.data ?: emptyList())
    val toGrade = awaiting.sumOf { it.pending() }
    val students = courses.data?.sumOf { it.students() }

    TabScreen(
        title = greetingTitle(t),
        subtitle = dateLine(t) + " · " + t.tt("role_teacher"),
        refreshing = schedule.refreshing || courses.refreshing || grading.refreshing,
        onRefresh = { schedule.refresh(); courses.refresh(); grading.refresh() },
        actions = {
            IconButton(onClick = { nav.select(Tab.PROFILE) }, modifier = Modifier.padding(end = 6.dp)) {
                Avatar(photo.data?.dataUrl, Prefs.displayName, 36.dp, shape = CircleShape)
            }
        },
    ) {
        if (Prefs.tutor) item { RoleSwitch() }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(Icons.Rounded.MenuBook, Tone.ACCENT, courses.data?.size?.toString() ?: "—", t.tt("stat_streams"), courses.initialLoading, Modifier.weight(1f)) { nav.select(Tab.COURSES) }
                StatCard(Icons.Rounded.Groups, Tone.VIOLET, students?.toString() ?: "—", t.tt("stat_students"), courses.initialLoading, Modifier.weight(1f)) { nav.select(Tab.COURSES) }
                StatCard(Icons.Rounded.HourglassTop, if (toGrade > 0) Tone.WARNING else Tone.SUCCESS, if (grading.data != null) toGrade.toString() else "—", t.tt("stat_pending"), grading.initialLoading, Modifier.weight(1f)) { nav.select(Tab.GRADING) }
            }
        }
        daySection(t, schedule, onAll = { nav.select(Tab.SCHEDULE) }) { l -> nav.push(Route.RollCall(l.courseId!!, l.subject, l.date)) }

        item { SectionHeader(t.tt("awaiting"), action = t.tt("nav_grading")) { nav.select(Tab.GRADING) } }
        when {
            grading.initialLoading -> item { ListSkeleton(2, tall = true) }
            grading.data == null -> item { ErrorState(grading.error, grading::refresh) }
            awaiting.isNotEmpty() -> item {
                ListGroup(awaiting.take(4)) { _, g, shape -> GradingRow(g, shape) { nav.push(routeFor(g, courses.data)) } }
            }
            else -> item { EmptyState(Icons.Rounded.FactCheck, t.tt("no_grading"), t.tt("no_grading_sub"), Tone.SUCCESS) }
        }

        item { SectionHeader(t.tt("sections")) }
        item {
            val appeals = rememberQuery("tappeals") { Repo.tAppeals() }
            val rows = buildList {
                add(Triple(Icons.Rounded.FolderOpen to Tone.ACCENT, t.tt("materials"), Route.Materials as Route))
                add(Triple(Icons.Rounded.ReportProblem to Tone.DANGER, appeals.data?.page?.title()?.takeIf { it.isNotBlank() } ?: t.tt("appeals"), Route.Appeals))
                add(Triple(Icons.Rounded.EmojiEvents to Tone.WARNING, t.tt("finals"), Route.TFinals))
            }
            ListGroup(rows) { _, r, shape ->
                ListRow(shape, onClick = { nav.push(r.third) }, leading = { IconTile(r.first.first, r.first.second, 38.dp, 20.dp) }) { RowTitle(r.second) }
            }
        }
    }
}

@Composable
fun GradingRow(g: GradingItem, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val t = LocalTr.current
    val d = g.deadline()
    val accepting = g.studentTs() > System.currentTimeMillis()
    val info = g.info()
    ListRow(shape, onClick = onClick, verticalAlignment = Alignment.Top,
        leading = { IconTile(if (accepting) Icons.Rounded.MenuBook else Icons.Rounded.HourglassTop, if (accepting) Tone.ACCENT else if (g.pending() > 0) Tone.WARNING else Tone.SUCCESS) }) {
        Text(d.activity() ?: "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        RowSub(d.subject() ?: "", maxLines = 2)
        Spacer(Modifier.height(6.dp))
        MetaRow {
            if (!d.stream().isNullOrBlank()) StreamChip(d.stream())
            if (accepting) Meta(Icons.Rounded.CalendarMonth, "${t.tt("student_deadline")}: ${info?.studentDeadline() ?: d.start()}")
            else if (!info?.teacherDeadline().isNullOrBlank()) Meta(Icons.Rounded.CalendarMonth, "${t.tt("teacher_deadline")}: ${info!!.teacherDeadline()}")
        }
        Spacer(Modifier.height(6.dp))
        MetaRow {
            Pill("${t.tt("submitted")}: ${g.submitted()}", Tone.ACCENT)
            if (!accepting) Pill("${t.tt("pending")}: ${g.pending()}", if (g.pending() > 0) Tone.WARNING else Tone.SUCCESS)
            if (g.graded() > 0) Pill("${t.tt("graded")}: ${g.graded()}", Tone.SUCCESS)
        }
    }
}

@Composable
fun TeacherCoursesScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    var semOpen by remember { mutableStateOf(false) }
    val q = rememberQuery("tcourses:$sem") { Repo.tCourses(sem) }
    val groups = remember(q.data) { (q.data ?: emptyList()).groupBy { it.subject() ?: "" }.entries.toList() }

    TabScreen(title = t.tt("my_streams"), subtitle = q.data?.let { t.tt("streams_n", "n" to it.size) }, refreshing = q.refreshing, onRefresh = q::refresh) {
        item { Box(Modifier.padding(bottom = 6.dp)) { SemesterChip { semOpen = true } } }
        when {
            q.initialLoading -> item { ListSkeleton(6, tall = true) }
            q.data == null -> item { ErrorState(q.error, q::refresh) }
            groups.isEmpty() -> item { EmptyState(Icons.Rounded.MenuBook, t.tt("no_streams")) }
            else -> groups.forEachIndexed { gi, (subject, list) ->
                item(key = "h$gi") { SectionHeader(subject) }
                item(key = "g$gi") {
                    ListGroup(list, key = { it.id() }) { _, c, shape ->
                        ListRow(shape, onClick = { nav.push(routeFor(c)) }, leading = { IconTile(Icons.Rounded.MenuBook, TONES[gi % TONES.size]) }) {
                            RowTitle(c.stream() ?: c.type() ?: "")
                            Spacer(Modifier.height(5.dp))
                            MetaRow {
                                if (!c.type().isNullOrBlank()) Meta(null, c.type())
                                Meta(Icons.Rounded.Groups, c.students().toString())
                                if (c.rejected() > 0) Pill("✉ ${c.rejected()}", Tone.WARNING)
                            }
                        }
                    }
                }
            }
        }
    }
    SemesterSheet(semOpen) { semOpen = false }
}

@Composable
fun GradingScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val sem = remember { Prefs.semesterId() }
    val q = rememberQuery("tgrading") { Repo.tGrading() }
    val courses = rememberQuery("tcourses:$sem") { Repo.tCourses(sem) }
    val (awaiting, accepting) = splitGrading(q.data ?: emptyList())

    TabScreen(title = t.tt("nav_grading"), refreshing = q.refreshing, onRefresh = q::refresh) {
        when {
            q.initialLoading -> item { ListSkeleton(4, tall = true) }
            q.data == null -> item { ErrorState(q.error, q::refresh) }
            awaiting.isEmpty() && accepting.isEmpty() -> item { EmptyState(Icons.Rounded.FactCheck, t.tt("no_grading"), t.tt("no_grading_sub"), Tone.SUCCESS) }
            else -> {
                if (awaiting.isNotEmpty()) {
                    item { SectionHeader(t.tt("awaiting")) }
                    item { ListGroup(awaiting) { _, g, shape -> GradingRow(g, shape) { nav.push(routeFor(g, courses.data)) } } }
                }
                if (accepting.isNotEmpty()) {
                    item { SectionHeader(t.tt("accepting")) }
                    item { ListGroup(accepting) { _, g, shape -> GradingRow(g, shape) { nav.push(routeFor(g, courses.data)) } } }
                }
            }
        }
    }
}

/** Профиль преподавателя: поля и подписи — со страницы LMS «Информация». */
@Composable
fun TeacherProfileScreen(tutor: Boolean) {
    val t = LocalTr.current
    val info = rememberQuery("tprofile") { Repo.tProfile() }
    val photo = rememberQuery("photo") { Repo.photo() }
    var photoOpen by remember { mutableStateOf(false) }
    val name = info.data?.fullName() ?: Prefs.displayName
    val fields = info.data?.fields()?.drop(1) ?: emptyList()

    TabScreen(title = t("profile_title"), refreshing = info.refreshing, onRefresh = info::refresh, actions = { uz.tuit.lms.ui.screens.SettingsAction() }) {
        item {
            ProfileCard(photo.data?.dataUrl, photo.initialLoading, name, info.initialLoading, onPhoto = { photoOpen = true }) {
                Pill(t.tt("role_teacher") + if (tutor) " · " + t.tt("role_tutor") else "", Tone.ACCENT)
            }
        }
        if (tutor) item { Box(Modifier.padding(top = 16.dp)) { RoleSwitch() } }
        if (info.data == null && info.error != null) item { Box(Modifier.padding(top = 16.dp)) { ErrorState(info.error, info::refresh) } }
        if (fields.isNotEmpty()) {
            item { SectionHeader(t.tt("info")) }
            item { ListGroup(fields) { _, f, shape -> KvRow(f.getOrNull(0) ?: "", f.getOrNull(1), shape) } }
        }
        item { uz.tuit.lms.ui.screens.AdminEntry() }
        accountSection()
    }
    PhotoDialog(photoOpen, photo.data?.dataUrl, name) { photoOpen = false }
}

@Composable
fun TeacherFinalsScreen() {
    val t = LocalTr.current
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    var semOpen by remember { mutableStateOf(false) }
    val q = rememberQuery("tfinals:$sem") { Repo.tFinals(sem) }
    DetailScreen(title = t.tt("finals"), refreshing = q.refreshing, onRefresh = q::refresh) {
        item { Box(Modifier.padding(bottom = 14.dp)) { SemesterChip { semOpen = true } } }
        when {
            q.initialLoading -> item { ListSkeleton(3, tall = true) }
            q.data == null -> item { ErrorState(q.error, q::refresh) }
            q.data!!.isEmpty() -> item { EmptyState(Icons.Rounded.EmojiEvents, t.tt("no_finals")) }
            else -> item {
                ListGroup(q.data!!) { _, f, shape ->
                    ListRow(shape, verticalAlignment = Alignment.Top, leading = { IconTile(Icons.Rounded.EmojiEvents, Tone.WARNING) }) {
                        RowTitle(f.subjects() ?: "")
                        if (!f.streams().isNullOrBlank()) RowSub(f.streams())
                        Spacer(Modifier.height(6.dp))
                        MetaRow {
                            Meta(Icons.Rounded.CalendarMonth, "${f.date() ?: ""} ${f.from() ?: ""}".trim())
                            if (!f.room().isNullOrBlank()) Meta(Icons.Rounded.Place, f.room())
                        }
                    }
                }
            }
        }
    }
    SemesterSheet(semOpen) { semOpen = false }
}
