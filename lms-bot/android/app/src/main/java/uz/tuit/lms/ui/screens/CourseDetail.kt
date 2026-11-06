package uz.tuit.lms.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uz.tuit.lms.data.ActStatus
import uz.tuit.lms.data.ActivitiesUi
import uz.tuit.lms.data.ActivityUi
import uz.tuit.lms.data.CalendarEntryUi
import uz.tuit.lms.data.CourseUi
import uz.tuit.lms.data.LessonUi
import uz.tuit.lms.data.planDate
import uz.tuit.lms.data.tabKind
import uz.tuit.lms.data.today
import uz.tuit.lms.data.hhmm
import java.time.LocalDate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Place
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Query
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.num
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.data.tabLabel
import uz.tuit.lms.ui.components.ActivityRow
import uz.tuit.lms.ui.components.ActivitySheet
import uz.tuit.lms.ui.components.AnimatedProgress
import uz.tuit.lms.ui.components.AppCard
import uz.tuit.lms.ui.components.ChipRow
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.FileRow
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
import uz.tuit.lms.ui.components.Skeleton
import uz.tuit.lms.ui.components.StreamChip
import uz.tuit.lms.ui.components.TextTile
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.theme.Tabular

@Composable
fun CourseDetailScreen(course: CourseUi, initialTab: Int, lesson: LessonUi? = null) {
    val t = LocalTr.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var open by remember { mutableStateOf<ActivityUi?>(null) }
    var entry by remember { mutableStateOf<CalendarEntryUi?>(null) }
    var topicTab by rememberSaveable { mutableStateOf<String?>(null) }
    val sem = remember { Prefs.semesterId() }

    val acts = rememberQuery("act:${course.id}") { Repo.activities(course.id) }
    val att = rememberQuery(if (tab == 1) "att:${course.id}:$sem" else null) { Repo.attendance(course.id, sem, course.subject) }
    val cal = rememberQuery(if (tab == 2 || lesson != null) "cal:${course.id}" else null) { Repo.calendar(course.id) }
    val current: Query<*> = when (tab) { 1 -> att; 2 -> cal; else -> acts }

    DetailScreen(
        title = course.subject.ifBlank { t("courses_title") },
        refreshing = current.refreshing,
        onRefresh = { current.refresh(); if (tab != 0) acts.refresh() },
    ) {
        if (course.teachers.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                course.teachers.forEach { x ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (x.stream.isNotBlank()) { StreamChip(x.stream); Spacer(Modifier.width(8.dp)) }
                        Text(x.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (lesson != null) item { LessonCard(course, lesson, cal) }
        item { Summary(acts.data, acts.initialLoading) }
        item {
            Segmented(
                listOf(Opt(0, t("tab_tasks")), Opt(1, t("tab_attendance")), Opt(2, t("tab_topics"))),
                tab, { tab = it }, Modifier.padding(top = 18.dp, bottom = 14.dp),
            )
        }
        when (tab) {
            0 -> tasks(acts, filter, { filter = it }) { open = it }
            1 -> attendance(att)
            else -> topics(cal, topicTab, lesson, { topicTab = it }) { entry = it }
        }
    }

    ActivitySheet(open, course.id, course.subject) { open = null }
    TopicSheet(entry) { entry = null }
}

@Composable
private fun Summary(data: ActivitiesUi?, loading: Boolean) {
    val t = LocalTr.current
    val pct = num(data?.progress)
    AppCard(Modifier.padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf(
                t("score") to (if (data != null) "${data.earned ?: "—"}/${data.maxScore ?: "—"}" else ""),
                t("progress") to (data?.progress ?: "—"),
                t("grade") to (data?.grade ?: "—"),
            ).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (loading) Skeleton(Modifier.fillMaxWidth(0.7f), height = 22.dp)
                    else Text(value.ifBlank { "—" }, style = MaterialTheme.typography.titleLarge.merge(Tabular).copy(fontWeight = FontWeight.Bold))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedProgress(((pct ?: 0.0) / 100).toFloat())
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tasks(q: Query<ActivitiesUi>, filter: String, setFilter: (String) -> Unit, onOpen: (ActivityUi) -> Unit) {
    val list = q.data?.activities
    when {
        q.initialLoading -> { item { ListSkeleton(4, tall = true) }; return }
        list == null -> { item { ErrorState(q.error, q::refresh) }; return }
        list.isEmpty() -> { item { EmptyState(Icons.Rounded.Checklist, LocalTrText("no_tasks")) }; return }
    }
    val now = System.currentTimeMillis()
    // Открытые — по близости дедлайна, остальные — от свежих к старым.
    val sorted = list!!.sortedWith(compareBy<ActivityUi> { if (it.status(now) == ActStatus.OPEN) 0 else 1 }
        .thenComparator { a, b -> if (a.status(now) == ActStatus.OPEN) compareValues(a.deadlineTs ?: 0, b.deadlineTs ?: 0) else compareValues(b.deadlineTs ?: 0, a.deadlineTs ?: 0) })
    fun pick(f: String) = sorted.filter {
        when (f) {
            "todo" -> it.status(now) == ActStatus.OPEN
            "done" -> it.submitted
            "missed" -> it.status(now) == ActStatus.MISSED
            else -> true
        }
    }
    val shown = pick(filter)
    item {
        val t = LocalTr.current
        ChipRow(
            listOf(
                Opt("all", t("filter_all"), list.size),
                Opt("todo", t("filter_todo"), pick("todo").size),
                Opt("done", t("filter_done"), pick("done").size),
                Opt("missed", t("filter_missed"), pick("missed").size),
            ),
            filter, setFilter, Modifier.padding(bottom = 12.dp),
        )
    }
    item {
        if (shown.isEmpty()) EmptyState(Icons.Rounded.CheckCircle, LocalTr.current("no_tasks_filter"), tone = Tone.SUCCESS)
        else ListGroup(shown, key = { it.index }) { _, a, shape -> ActivityRow(a, shape) { onOpen(a) } }
    }
}

@Composable
private fun LocalTrText(key: String) = LocalTr.current(key)

private fun androidx.compose.foundation.lazy.LazyListScope.attendance(q: Query<uz.tuit.lms.data.AttendanceUi>) {
    val items = q.data?.items
    when {
        q.initialLoading -> { item { ListSkeleton(3) }; return }
        items == null -> { item { ErrorState(q.error, q::refresh) }; return }
        items.isEmpty() -> { item { EmptyState(Icons.Rounded.CheckCircle, LocalTrText("all_attended"), LocalTrText("all_attended_sub"), Tone.SUCCESS) }; return }
    }
    item {
        val t = LocalTr.current
        AppCard(Modifier.padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextTile(items!!.size.toString(), Tone.DANGER, size = 50.dp, fontSize = 20)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(t("missed_total"), style = MaterialTheme.typography.titleMedium)
                    Text("${t("excused")}: ${items.count { it.excused }} · ${t("unexcused")}: ${items.count { !it.excused }}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    item {
        val t = LocalTr.current
        ListGroup(items!!) { _, r, shape ->
            ListRow(shape, leading = { IconTile(Icons.Rounded.EventBusy, if (r.excused) Tone.WARNING else Tone.DANGER) }, verticalAlignment = Alignment.Top) {
                Text(r.date, style = MaterialTheme.typography.titleMedium.merge(Tabular))
                Spacer(Modifier.height(6.dp))
                MetaRow {
                    if (!r.type.isNullOrBlank()) Pill(r.type, if (r.lecture) Tone.ACCENT else Tone.VIOLET)
                    Pill(if (r.excused) t("excused") else t("unexcused"), if (r.excused) Tone.WARNING else Tone.DANGER)
                }
                if (!r.topic.isNullOrBlank()) RowSub(r.topic, maxLines = 4, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.topics(
    q: Query<List<uz.tuit.lms.data.CalendarTabUi>>,
    key: String?,
    lesson: LessonUi?,
    setKey: (String) -> Unit,
    onOpen: (CalendarEntryUi) -> Unit,
) {
    val tabs = q.data
    when {
        q.initialLoading -> { item { ListSkeleton(5) }; return }
        tabs == null -> { item { ErrorState(q.error, q::refresh) }; return }
        tabs.isEmpty() -> { item { EmptyState(Icons.Rounded.Notes, LocalTrText("no_topics")) }; return }
    }
    val todayD = today()
    val active = tabs!!.firstOrNull { it.key == key } ?: tabFor(tabs, lesson?.let { LocalDate.parse(it.date) } ?: todayD, lesson?.kind)
    val dates = active.entries.map { planDate(it.date) }
    // Сегодня занятия нет — черта «Сегодня» встаёт там, где сегодняшний день был бы в плане.
    val next = dates.indexOfFirst { it != null && it > todayD }
    val lineAt = if (todayD in dates || dates.all { it == null }) -1 else if (next >= 0) next else dates.size
    if (tabs.size > 1) item {
        val t = LocalTr.current
        ChipRow(tabs.map { Opt(it.key, tabLabel(it.key, t), it.entries.size) }, active.key, setKey, Modifier.padding(bottom = 12.dp))
    }
    item {
        val t = LocalTr.current
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            active.entries.forEachIndexed { i, e ->
                if (i == lineAt) TodayLine(t)
                val isToday = dates[i] == todayD
                ListRow(
                    uz.tuit.lms.ui.components.groupShape(i, active.entries.size), onClick = { onOpen(e) },
                    color = if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    leading = { TextTile(e.number.toString(), if (isToday) Tone.ACCENT else Tone.NEUTRAL) }, verticalAlignment = Alignment.Top,
                ) {
                    RowTitle(e.topic, maxLines = 4)
                    Spacer(Modifier.height(5.dp))
                    MetaRow {
                        if (isToday) Pill(t("today"), Tone.ACCENT)
                        if (!e.date.isNullOrBlank()) Meta(null, e.date)
                        if (e.files.isNotEmpty()) Meta(Icons.Rounded.AttachFile, t("files", "n" to e.files.size))
                    }
                }
            }
            if (lineAt == active.entries.size) TodayLine(t)
        }
    }
}

/** Вкладка плана с нужным днём: сначала того же вида занятий, что и пара. */
private fun tabFor(tabs: List<uz.tuit.lms.data.CalendarTabUi>, day: LocalDate, kind: String?): uz.tuit.lms.data.CalendarTabUi {
    fun has(x: uz.tuit.lms.data.CalendarTabUi) = x.entries.any { planDate(it.date) == day }
    return tabs.firstOrNull { tabKind(it.key) == kind && has(it) }
        ?: tabs.firstOrNull { has(it) }
        ?: tabs.firstOrNull { tabKind(it.key) == kind }
        ?: tabs.first()
}

@Composable
private fun TodayLine(t: uz.tuit.lms.data.Tr) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.primary))
        Text("  ${t("today")}, ${t.dayMonth(today())}  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/** Занятие, с которого пришёл студент: тема, преподаватель, группа, аудитория и файлы этой темы из плана. */
@Composable
private fun LessonCard(course: CourseUi, lesson: LessonUi, plan: Query<List<uz.tuit.lms.data.CalendarTabUi>>) {
    val t = LocalTr.current
    val profile = rememberQuery("profile") { Repo.profile() }
    val day = LocalDate.parse(lesson.date)
    val tabs = plan.data ?: emptyList()
    val entry = if (tabs.isNotEmpty()) tabFor(tabs, day, lesson.kind).entries.firstOrNull { planDate(it.date) == day } else null
    val dayLabel = when (day) {
        today() -> t("today")
        today().plusDays(1) -> t("tomorrow")
        else -> t.dayMonth(day)
    }
    val facts = listOf(
        Triple(Icons.AutoMirrored.Rounded.Notes, t("topic"), lesson.topic?.ifBlank { null } ?: entry?.topic),
        Triple(Icons.Rounded.Person, t("teacher"), lesson.teacher.ifBlank { null }),
        Triple(Icons.Rounded.Groups, t("group"), lesson.stream ?: profile.data?.group),
        Triple(Icons.Rounded.Place, t("lesson_room"), lesson.room.ifBlank { null }),
    ).filter { !it.third.isNullOrBlank() }
    AppCard(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
        MetaRow {
            Text("$dayLabel, ${lesson.time.take(5)}–${lesson.endTs.hhmm()}", style = MaterialTheme.typography.titleMedium.merge(Tabular))
            Pill(t(uz.tuit.lms.ui.components.kindLabel(lesson.kind)), uz.tuit.lms.ui.components.kindTone(lesson.kind))
        }
        facts.forEach { (icon, label, value) ->
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value!!, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(t("materials"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        when {
            plan.initialLoading -> Skeleton(Modifier.fillMaxWidth(), height = 48.dp, shape = MaterialTheme.shapes.medium)
            entry?.files?.isNotEmpty() == true -> ListGroup(entry.files) { _, f, shape -> FileRow(f.name, f.url, shape, type = f.type, color = MaterialTheme.colorScheme.surfaceContainerLowest) }
            else -> Text(t("no_files"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicSheet(entry: CalendarEntryUi?, onClose: () -> Unit) {
    if (entry == null) return
    val t = LocalTr.current
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            Text("${entry.number}. ${entry.topic}", style = MaterialTheme.typography.titleLarge)
            if (!entry.date.isNullOrBlank()) Text(entry.date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader(t("materials"))
            if (entry.files.isEmpty()) EmptyState(Icons.Rounded.FolderOpen, t("no_files"))
            else ListGroup(entry.files) { _, f, shape -> FileRow(f.name, f.url, shape, type = f.type, color = MaterialTheme.colorScheme.surfaceContainerHigh) }
        }
    }
}
