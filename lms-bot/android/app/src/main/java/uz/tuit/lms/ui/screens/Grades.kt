package uz.tuit.lms.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.tuit.lms.data.FinalUi
import uz.tuit.lms.data.PlanSubjectUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Query
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.currentPlanSemester
import uz.tuit.lms.data.courseOfSemester
import uz.tuit.lms.data.currentCourse
import uz.tuit.lms.data.fmtNum
import uz.tuit.lms.data.gpa
import uz.tuit.lms.data.gpaComment
import uz.tuit.lms.data.num
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.ui.components.AppCard
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
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
import uz.tuit.lms.ui.components.Segmented
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.SemesterChip
import uz.tuit.lms.ui.components.SemesterSheet
import uz.tuit.lms.ui.components.Skeleton
import uz.tuit.lms.ui.components.TabScreen
import uz.tuit.lms.ui.components.TextTile
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.components.colors
import uz.tuit.lms.ui.theme.Tabular

@Composable
fun GradesScreen() {
    val t = LocalTr.current
    var view by rememberSaveable { mutableStateOf("plan") }
    var withZero by rememberSaveable { mutableStateOf(false) }
    var semOpen by remember { mutableStateOf(false) }
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    val plan = rememberQuery("plan") { Repo.studyPlan() }
    val finals = rememberQuery(if (view == "exams") "finals:$sem" else null) { Repo.finals(sem) }
    val profile = rememberQuery(if (view == "calc") "profile" else null) { Repo.profile() }
    // Подставленные оценки живут только здесь: ни LMS, ни настоящий GPA они не трогают.
    val edits = remember { mutableStateMapOf<String, Int?>() }
    val q: Query<*> = if (view == "exams") finals else plan

    TabScreen(title = t("grades_title"), refreshing = q.refreshing, onRefresh = q::refresh) {
        item { Segmented(listOf(Opt("plan", t("tab_plan")), Opt("calc", t("tab_calc")), Opt("exams", t("tab_exams"))), view, { view = it }, Modifier.padding(bottom = 16.dp)) }
        if (view == "plan") plan(plan, withZero) { withZero = it }
        else if (view == "calc") calc(plan, profile.data?.course, profile.initialLoading, edits)
        else {
            item { Box(Modifier.padding(bottom = 14.dp)) { SemesterChip { semOpen = true } } }
            exams(finals)
        }
    }
    SemesterSheet(semOpen) { semOpen = false }
}

fun gradeTone(g: Int?) = when {
    g == null -> Tone.NEUTRAL
    g >= 5 -> Tone.SUCCESS
    g == 4 -> Tone.ACCENT
    g == 3 -> Tone.WARNING
    else -> Tone.DANGER
}

/** Кольцо GPA из 5 баллов. */
@Composable
fun GpaRing(value: Double?, size: androidx.compose.ui.unit.Dp = 112.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = MaterialTheme.colorScheme.primary
    val sweep by animateFloatAsState(((value ?: 0.0) / 5.0).toFloat().coerceIn(0f, 1f), label = "gpa")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = 11.dp.toPx()
            val inset = w / 2
            val s = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), s, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * sweep, false, Offset(inset, inset), s, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—", style = MaterialTheme.typography.headlineMedium.merge(Tabular).copy(fontWeight = FontWeight.Bold))
            Text("GPA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.plan(q: Query<List<PlanSubjectUi>>, withZero: Boolean, setZero: (Boolean) -> Unit) {
    val subjects = q.data
    when {
        q.initialLoading -> { item { Skeleton(Modifier.padding(bottom = 14.dp).fillMaxWidth(), height = 170.dp, shape = MaterialTheme.shapes.large) }; item { ListSkeleton(5) }; return }
        subjects == null -> { item { ErrorState(q.error, q::refresh) }; return }
        subjects.isEmpty() -> { item { EmptyState(Icons.Rounded.School, LocalTr.current("no_plan")) }; return }
    }
    val res = gpa(subjects!!, withZero)
    val current = currentPlanSemester(subjects)
    val bySem = subjects.groupBy { it.semester }.toSortedMap()
    item {
        val t = LocalTr.current
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GpaRing(res.value)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(res.value?.let { gpaComment(it, t) } ?: t("no_grades"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(t("gpa_credits", "n" to res.credits), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Segmented(listOf(Opt(false, t("gpa_mode_graded")), Opt(true, t("gpa_mode_zero"))), withZero, setZero)
            if (withZero) Text(t("gpa_hint_zero"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
    bySem.forEach { (s, list) ->
        item(key = "sem$s") { SemesterBlock(s, list, s == current) }
    }
}

@Composable
private fun SemesterBlock(s: Int, list: List<PlanSubjectUi>, isCurrent: Boolean) {
    val t = LocalTr.current
    var expanded by rememberSaveable { mutableStateOf(isCurrent) }
    val semGpa = gpa(list, false).value
    val credits = list.sumOf { it.credits }
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "rot")
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ListRow(
            shape = if (expanded) uz.tuit.lms.ui.components.groupShape(0, 2) else uz.tuit.lms.ui.components.groupShape(0, 1),
            onClick = { expanded = !expanded },
            leading = { TextTile(s.toString(), if (isCurrent) Tone.ACCENT else Tone.NEUTRAL) },
            trailing = { Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rot), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowTitle(t("semester_n", "n" to s))
                if (isCurrent) { Spacer(Modifier.width(8.dp)); Pill(t("current"), Tone.ACCENT) }
            }
            RowSub("$credits ${t("credits_short")}" + (semGpa?.let { " · GPA ${String.format(java.util.Locale.US, "%.2f", it)}" } ?: ""))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                list.forEachIndexed { i, x ->
                    val shape = if (i == list.lastIndex) uz.tuit.lms.ui.components.groupShape(1, 2) else uz.tuit.lms.ui.components.groupShape(1, 3)
                    ListRow(shape, trailing = {
                        val (bg, fg) = gradeTone(x.grade).colors()
                        Surface(shape = MaterialTheme.shapes.small, color = bg) {
                            Text(x.grade?.toString() ?: "—", color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp, style = Tabular, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }) {
                        Text(x.name, style = MaterialTheme.typography.bodyLarge)
                        RowSub("${x.credits} ${t("credits_short")}")
                    }
                }
            }
        }
    }
}

fun examTone(g: String?): Tone {
    val v = num(g) ?: return Tone.NEUTRAL
    return when {
        v >= 86 -> Tone.SUCCESS
        v >= 71 -> Tone.ACCENT
        v >= 56 -> Tone.WARNING
        else -> Tone.DANGER
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.exams(q: Query<List<FinalUi>>) {
    val list = q.data
    when {
        q.initialLoading -> { item { ListSkeleton(4, tall = true) }; return }
        list == null -> { item { ErrorState(q.error, q::refresh) }; return }
        list.isEmpty() -> { item { val t = LocalTr.current; EmptyState(Icons.Rounded.EmojiEvents, t("no_exams"), t("no_exams_sub")) }; return }
    }
    item {
        val t = LocalTr.current
        ListGroup(list!!) { _, e, shape ->
            ListRow(shape, verticalAlignment = Alignment.Top, trailing = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextTile(num(e.grade)?.let(::fmtNum) ?: "—", examTone(e.grade), size = 52.dp, fontSize = 18)
                    Text(t("points"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }) {
                RowTitle(e.subject ?: "")
                Spacer(Modifier.height(8.dp))
                MetaRow {
                    if (!e.date.isNullOrBlank()) Meta(Icons.Rounded.CalendarMonth, e.date)
                    if (!e.from.isNullOrBlank()) Meta(Icons.Rounded.Schedule, e.from)
                    if (!e.room.isNullOrBlank()) Meta(Icons.Rounded.MeetingRoom, e.room)
                    if (!e.stream.isNullOrBlank()) Meta(Icons.Rounded.Tag, e.stream)
                }
            }
        }
    }
}

/* ─── Расчёт GPA «что, если» ─── */

/** Подставить оценку можно только вместо двойки или там, где оценки ещё нет. */
private fun canEdit(s: PlanSubjectUi) = s.grade == null || s.grade <= 2

/**
 * Все предметы с 1-го по текущий курс разом: семестр не выбирается, GPA накопительный.
 * Оценку меняют только у двоек и предметов без оценки; в LMS ничего не меняется.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.calc(
    q: Query<List<PlanSubjectUi>>,
    profileCourse: String?,
    profileLoading: Boolean,
    edits: SnapshotStateMap<String, Int?>,
) {
    val subjects = q.data
    when {
        q.initialLoading || (profileLoading && profileCourse == null) -> { item { ListSkeleton(5) }; return }
        subjects == null -> { item { ErrorState(q.error, q::refresh) }; return }
    }
    val course = currentCourse(profileCourse, subjects!!)
    val rows = subjects.mapIndexed { i, s -> "${s.semester}:$i:${s.name}" to s }.filter { courseOfSemester(it.second.semester) <= course }
    if (rows.isEmpty()) { item { EmptyState(Icons.Rounded.School, LocalTr.current("no_plan")) }; return }
    fun gradeOf(id: String, s: PlanSubjectUi) = if (canEdit(s) && edits.containsKey(id)) edits[id] else s.grade
    val now = gpa(rows.map { it.second }, false).value
    val will = gpa(rows.map { (id, s) -> s.copy(grade = gradeOf(id, s)) }, false)
    val changed = rows.any { (id, s) -> gradeOf(id, s) != s.grade }

    item {
        val t = LocalTr.current
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("calc_now"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(now?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—", style = MaterialTheme.typography.headlineMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 22.dp), tint = MaterialTheme.colorScheme.outline)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t("calc_will"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.animation.AnimatedContent(will.value, label = "will") { v ->
                        Text(v?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—", style = MaterialTheme.typography.displaySmall.merge(Tabular), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            uz.tuit.lms.ui.components.AnimatedProgress(((will.value ?: 0.0) / 5.0).toFloat())
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("gpa_credits", "n" to will.credits), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (changed) androidx.compose.material3.FilledTonalButton(onClick = { edits.clear() }) { Text(t("reset")) }
            }
            Text(if (rows.any { canEdit(it.second) }) t("calc_hint", "n" to course) else t("calc_nothing"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
    rows.groupBy { courseOfSemester(it.second.semester) }.toSortedMap().forEach { (c, list) ->
        item(key = "calc_h$c") { SectionHeader(LocalTr.current("calc_course", "n" to c)) }
        item(key = "calc_l$c") {
            val t = LocalTr.current
            ListGroup(list, key = { it.first }) { _, pair, shape ->
                val (id, s) = pair
                val value = gradeOf(id, s)
                ListRow(shape, trailing = {
                    if (canEdit(s)) GradePicker(value, allowEmpty = s.grade == null) { edits[id] = it }
                    else Pill(s.grade?.toString() ?: "—", gradeTone(s.grade))
                }) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    RowSub("${s.credits} ${t("credits_short")}" + if (value != s.grade) " · " + t("calc_was", "g" to (s.grade?.toString() ?: "—")) else "")
                }
            }
        }
    }
}

/** Выбор оценки 5/4/3/2 (и «—», если оценки ещё нет). */
@Composable
private fun GradePicker(value: Int?, allowEmpty: Boolean, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (bg, fg) = gradeTone(value).colors()
    Box {
        Surface(onClick = { open = true }, shape = MaterialTheme.shapes.small, color = bg) {
            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value?.toString() ?: "—", color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp, style = Tabular)
                Icon(Icons.Rounded.ExpandMore, null, tint = fg, modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
            (listOf<Int?>(5, 4, 3, 2) + if (allowEmpty) listOf(null) else emptyList()).forEach { g ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(g?.toString() ?: "—", style = Tabular) }, onClick = { onPick(g); open = false })
            }
        }
    }
}
