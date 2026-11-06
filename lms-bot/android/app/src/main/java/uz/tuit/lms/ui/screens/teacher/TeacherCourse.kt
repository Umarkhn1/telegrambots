package uz.tuit.lms.ui.screens.teacher

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.FilePresent
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uz.tuit.lms.data.ApiException
import uz.tuit.lms.data.Files
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.fmtNum
import uz.tuit.lms.data.parseDmy
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.data.today
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.components.AppCard
import uz.tuit.lms.ui.components.ChipRow
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.FileRow
import uz.tuit.lms.ui.components.IconTile
import uz.tuit.lms.ui.components.KvRow
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListRow
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalSnack
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Meta
import uz.tuit.lms.ui.components.MetaRow
import uz.tuit.lms.ui.components.Opt
import uz.tuit.lms.ui.components.Pill
import uz.tuit.lms.ui.components.RowSub
import uz.tuit.lms.ui.components.RowTitle
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.Segmented
import uz.tuit.lms.ui.components.StreamChip
import uz.tuit.lms.ui.components.TextTile
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.components.content
import uz.tuit.lms.ui.components.rememberConfirm
import uz.tuit.lms.ui.theme.Tabular
import uz.tuit.lmsbot.service.TeacherService.GCell
import uz.tuit.lmsbot.service.TeacherService.GRow
import uz.tuit.lmsbot.service.TeacherService.GradeForm
import uz.tuit.lmsbot.service.TeacherService.GradeSheet
import uz.tuit.lmsbot.service.TeacherService.TActivities
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private fun GCell.pending() = submitted() && grade().isNullOrBlank()
private fun GCell.graded() = !grade().isNullOrBlank()
private fun GCell.tone() = when {
    pending() -> Tone.WARNING
    graded() -> Tone.SUCCESS
    submitted() -> Tone.ACCENT
    else -> Tone.NEUTRAL
}

@Composable
fun TeacherCourseScreen(route: Route.TCourse) {
    val t = LocalTr.current
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableStateOf("sheet") }
    val sheet = rememberQuery("tsheet:${route.id}") { Repo.tSheet(route.id) }
    val plan = rememberQuery(if (tab == "plan") "tcal:${route.id}" else null) { Repo.tCalendar(route.id) }
    val acts = rememberQuery(if (tab == "activities") "tact:${route.id}" else null) { Repo.tActivities(route.id) }
    var col by rememberSaveable { mutableStateOf(route.column?.toString()) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var open by remember { mutableStateOf<GRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    val confirm = rememberConfirm()
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()

    DetailScreen(
        title = route.subject.ifBlank { sheet.data?.title() ?: t.tt("my_streams") },
        refreshing = sheet.refreshing || plan.refreshing || acts.refreshing,
        onRefresh = { when (tab) { "plan" -> plan.refresh(); "activities" -> acts.refresh(); else -> sheet.refresh() } },
        actions = {
            FilledTonalButton(onClick = { nav.push(Route.RollCall(route.id, route.subject, null)) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp), modifier = Modifier.height(38.dp).padding(end = 6.dp)) {
                Icon(Icons.Rounded.HowToReg, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.tt("roll_call"), fontSize = 14.sp)
            }
        },
    ) {
        item {
            MetaRow(Modifier.padding(bottom = 14.dp)) {
                if (route.type.isNotBlank()) Pill(route.type, Tone.ACCENT)
                if (!route.stream.isNullOrBlank()) StreamChip(route.stream)
                if (route.students > 0) Meta(Icons.Rounded.Groups, t.tt("students_n", "n" to route.students))
            }
        }
        item {
            Segmented(listOf(Opt("sheet", t.tt("tab_sheet")), Opt("plan", t.tt("tab_plan")), Opt("activities", t.tt("tab_activities"))), tab, { tab = it }, Modifier.padding(top = 16.dp, bottom = 14.dp))
        }
        when (tab) {
            "sheet" -> {
                val s = sheet.data
                when {
                    sheet.initialLoading -> item { ListSkeleton(5) }
                    s == null -> item { ErrorState(sheet.error, sheet::refresh) }
                    s.columns().isEmpty() -> item { EmptyState(Icons.Rounded.Checklist, t.tt("no_columns")) }
                    else -> {
                        val current = when {
                            col == "totals" -> -1
                            col?.toIntOrNull()?.let { it < s.columns().size } == true -> col!!.toInt()
                            // По умолчанию — активность, где больше всего непроверенных работ.
                            else -> s.columns().indices.maxByOrNull { k -> s.rows().count { it.cells().getOrNull(k)?.pending() == true } } ?: 0
                        }
                        val rows = if (current >= 0) s.rows().sortedWith(compareBy<GRow>({ r -> r.cells().getOrNull(current)?.let { if (it.pending()) 0 else if (it.graded()) 1 else 2 } ?: 2 }, { it.number() })) else s.rows()
                        fun matches(c: GCell?, f: String) = c != null && when (f) {
                            "pending" -> c.pending(); "graded" -> c.graded(); "none" -> !c.submitted() && !c.graded(); else -> true
                        }
                        val shown = if (current >= 0) rows.filter { matches(it.cells().getOrNull(current), filter) } else rows

                        if (!s.note().isNullOrBlank()) item { Text(s.note(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) }
                        item {
                            ChipRow(
                                s.columns().mapIndexed { k, c -> Opt(k.toString(), c.name() ?: "#${k + 1}", s.rows().count { it.cells().getOrNull(k)?.pending() == true }.takeIf { it > 0 }) } + Opt("totals", t.tt("totals")),
                                if (current < 0) "totals" else current.toString(), { col = it; filter = "all" },
                            )
                        }
                        if (current >= 0) {
                            val info = s.columns()[current]
                            item {
                                AppCard(Modifier.padding(top = 12.dp)) {
                                    MetaRow {
                                        if (!info.studentDeadline().isNullOrBlank()) Meta(Icons.Rounded.CalendarMonth, "${t.tt("student_deadline")}: ${info.studentDeadline()}")
                                        if (!info.teacherDeadline().isNullOrBlank()) Meta(Icons.Rounded.Schedule, "${t.tt("teacher_deadline")}: ${info.teacherDeadline()}")
                                        if (!info.max().isNullOrBlank()) Meta(null, "${s.labels()["max"] ?: "max"} ${info.max()}")
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row {
                                        listOf(
                                            Triple(t.tt("submitted"), rows.count { it.cells().getOrNull(current)?.submitted() == true }, Tone.ACCENT),
                                            Triple(t.tt("graded"), rows.count { matches(it.cells().getOrNull(current), "graded") }, Tone.SUCCESS),
                                            Triple(t.tt("pending"), rows.count { matches(it.cells().getOrNull(current), "pending") }, Tone.WARNING),
                                        ).forEach { (label, v, tone) ->
                                            Column(Modifier.weight(1f)) {
                                                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(v.toString(), style = MaterialTheme.typography.headlineSmall.merge(Tabular), fontWeight = FontWeight.Bold, color = tone.content())
                                            }
                                        }
                                    }
                                }
                            }
                            item {
                                ChipRow(
                                    listOf(
                                        Opt("all", "∑", rows.size),
                                        Opt("pending", t.tt("pending"), rows.count { matches(it.cells().getOrNull(current), "pending") }),
                                        Opt("graded", t.tt("graded"), rows.count { matches(it.cells().getOrNull(current), "graded") }),
                                        Opt("none", t.tt("not_submitted"), rows.count { matches(it.cells().getOrNull(current), "none") }),
                                    ),
                                    filter, { filter = it }, Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                        item {
                            if (shown.isEmpty()) EmptyState(Icons.Rounded.Checklist, "—", modifier = Modifier.padding(top = 12.dp))
                            else ListGroup(shown, Modifier.padding(top = 12.dp), key = { it.number() }) { _, r, shape ->
                                val c = if (current >= 0) r.cells().getOrNull(current) else null
                                val clickable = c != null && c.studentId() != null && c.activityId() != null
                                ListRow(shape, onClick = if (clickable) ({ open = r }) else null,
                                    leading = { TextTile(r.number().toString(), c?.tone() ?: Tone.NEUTRAL, fontSize = 14) },
                                    trailing = {
                                        when {
                                            c == null -> Column(horizontalAlignment = Alignment.End) {
                                                Text(r.total() ?: "", style = MaterialTheme.typography.titleMedium.merge(Tabular))
                                                Text(r.percent() ?: "", style = MaterialTheme.typography.bodySmall.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            c.graded() -> Pill(c.grade(), Tone.SUCCESS)
                                            c.submitted() -> Pill("", Tone.WARNING, Icons.Rounded.HourglassTop)
                                            else -> {}
                                        }
                                    }) {
                                    RowTitle(r.name() ?: "", maxLines = 2)
                                    if (!r.group().isNullOrBlank()) RowSub(r.group())
                                }
                            }
                        }
                        if (current >= 0 && open != null) {
                            item { GradeModal(route, s, current, rows, open!!, onMove = { open = it }, onClose = { open = null }, onSaved = { sheet.refresh(); Repo.invalidate("tgrading") }) }
                        }
                    }
                }
            }
            "plan" -> {
                val cal = plan.data
                when {
                    plan.initialLoading -> item { ListSkeleton(6) }
                    cal == null -> item { ErrorState(plan.error, plan::refresh) }
                    cal.lessons().isEmpty() -> item { EmptyState(Icons.Rounded.Notes, t.tt("plan_empty")) }
                    else -> {
                        if (!cal.note().isNullOrBlank()) item { Text(cal.note(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) }
                        val regular = cal.lessons().filter { !it.moved() }
                        val moved = cal.lessons().filter { it.moved() }
                        listOf("" to regular, t.tt("moved") to moved).filter { it.second.isNotEmpty() }.forEach { (title, list) ->
                            if (title.isNotEmpty()) item { SectionHeader(title) }
                            item {
                                val todayD = today()
                                ListGroup(list) { _, l, shape ->
                                    val d = parseDmy(l.date())
                                    val overdue = !l.marked() && d != null && d < todayD
                                    val isToday = d == todayD
                                    val tone = if (l.marked()) Tone.SUCCESS else if (overdue) Tone.DANGER else if (isToday) Tone.ACCENT else Tone.NEUTRAL
                                    ListRow(shape, verticalAlignment = Alignment.Top,
                                        onClick = if (l.lessonId() != null && d != null) ({ nav.push(Route.RollCall(route.id, route.subject, d.toString())) }) else null,
                                        leading = { IconTile(if (l.marked()) Icons.Rounded.EventAvailable else if (overdue) Icons.Rounded.EventBusy else Icons.Rounded.RadioButtonUnchecked, tone) }) {
                                        RowTitle("${l.number()}. ${l.topic()}", maxLines = 3)
                                        Spacer(Modifier.height(5.dp))
                                        MetaRow {
                                            Meta(null, l.date() ?: "")
                                            Pill(if (l.marked()) t.tt("marked") else if (overdue) t.tt("not_marked") else t.tt("upcoming"), tone)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val a = acts.data
                when {
                    acts.initialLoading -> item { ListSkeleton(3, tall = true) }
                    a == null -> item { ErrorState(acts.error, acts::refresh) }
                    else -> {
                        if (!a.budgetText().isNullOrBlank() || !a.note().isNullOrBlank()) item {
                            AppCard(Modifier.padding(bottom = 12.dp)) {
                                if (!a.budgetText().isNullOrBlank()) Text(a.budgetText(), style = MaterialTheme.typography.titleSmall)
                                if (!a.note().isNullOrBlank()) Text(a.note(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        item {
                            FilledTonalButton(onClick = { adding = true }, modifier = Modifier.padding(bottom = 12.dp).height(40.dp)) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t.tt("add_activity"), fontSize = 14.sp)
                            }
                        }
                        if (a.items().isEmpty()) item { EmptyState(Icons.Rounded.Checklist, t.tt("no_columns")) }
                        a.items().forEach { x ->
                            item(key = "a${x.id()}") {
                                val statusTone = when (x.status()) { 1 -> Tone.SUCCESS; 2 -> Tone.DANGER; 3 -> Tone.WARNING; else -> Tone.ACCENT }
                                AppCard(Modifier.padding(bottom = 10.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Column(Modifier.weight(1f)) {
                                            Text(x.name() ?: "", style = MaterialTheme.typography.titleMedium)
                                            Spacer(Modifier.height(6.dp))
                                            MetaRow {
                                                Pill(a.statusLabels()[x.status()] ?: x.status().toString(), statusTone)
                                                if (x.module()) Pill(t.tt("module"), Tone.VIOLET)
                                                Meta(Icons.Rounded.CalendarMonth, x.deadline() ?: "")
                                                Meta(null, "${a.labels()["max_point"] ?: "max"} ${x.maxPoint()}")
                                            }
                                        }
                                        if (!x.hasActivities() && !x.module()) IconButton(onClick = {
                                            confirm.ask(t.tt("delete_confirm", "name" to (x.name() ?: "")), t.tt("delete"), danger = true) {
                                                scope.launch {
                                                    val res = runCatching { Repo.tDeleteActivity(route.id, x.id()) }.getOrNull()
                                                    if (res?.ok() == true) snack.show(t.tt("deleted"))
                                                    else snack.show(res?.errors()?.joinToString(", ")?.ifBlank { null } ?: t.tt("lms_rejected"), error = true)
                                                }
                                            }
                                        }) { Icon(Icons.Rounded.Delete, t.tt("delete"), tint = MaterialTheme.colorScheme.error) }
                                    }
                                    if (x.status() == 3 && !x.comment().isNullOrBlank()) Text(x.comment(), color = uz.tuit.lms.ui.theme.LocalTones.current.warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                                    if (x.criteria().isNotEmpty()) {
                                        Spacer(Modifier.height(10.dp))
                                        ListGroup(x.criteria()) { _, c, shape -> KvRow(c.getOrNull(0) ?: "", c.getOrNull(1), shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) }
                                    }
                                    if (!x.sampleUrl().isNullOrBlank()) {
                                        Spacer(Modifier.height(10.dp))
                                        FileRow(x.sampleName() ?: "file", x.sampleUrl(), uz.tuit.lms.ui.components.groupShape(0, 1), color = MaterialTheme.colorScheme.surfaceContainerHigh)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val a = acts.data
    if (adding && a != null) ActivityForm(route, a, onClose = { adding = false }) { adding = false; snack.show(t.tt("created")) }
}

/* ─── Окно оценивания ─── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeModal(route: Route.TCourse, sheet: GradeSheet, col: Int, order: List<GRow>, row: GRow, onMove: (GRow) -> Unit, onClose: () -> Unit, onSaved: () -> Unit) {
    val t = LocalTr.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirm()
    val L = sheet.labels()
    val cell = row.cells().getOrNull(col)
    var form by remember { mutableStateOf<GradeForm?>(null) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var grade by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun fill(f: GradeForm) {
        form = f
        values.clear()
        f.criteria().forEach { values[it.id()] = it.value() ?: "" }
        grade = f.grade() ?: ""
        comment = f.comment() ?: ""
    }

    LaunchedEffect(cell?.studentId(), cell?.activityId(), reload) {
        val sid = cell?.studentId() ?: return@LaunchedEffect
        val aid = cell.activityId() ?: return@LaunchedEffect
        form = null
        loadError = null
        error = null
        try { fill(Repo.tGradeForm(sid, aid)) } catch (e: Exception) { loadError = e }
    }

    val idx = order.indexOfFirst { it.number() == row.number() }
    fun move(d: Int) { if (idx >= 0 && order.isNotEmpty()) onMove(order[(idx + d + order.size) % order.size]) }

    fun validate(f: GradeForm): String? {
        fun check(v: String, max: String?): String? {
            if (v.isBlank()) return t.tt("bad_number")
            val n = v.replace(',', '.').toDoubleOrNull() ?: return t.tt("bad_number")
            if (n < 0 || (max?.toDoubleOrNull()?.let { n > it } == true)) return t.tt("out_of_range")
            return null
        }
        if (f.module() || f.criteria().isEmpty()) return check(grade, f.maxPoint())
        for (c in f.criteria()) check(values[c.id()] ?: "", c.max())?.let { return "${c.name()}: $it" }
        return null
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(row.name() ?: "", style = MaterialTheme.typography.titleLarge)
                Text("${sheet.columns()[col].name() ?: ""} · ${route.stream ?: route.subject}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val f = form
            when {
                loadError != null -> ErrorState(loadError, { reload++ })
                f == null -> Row(Modifier.fillMaxWidth().padding(30.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                else -> {
                    ListGroup(listOf(t.tt("student_deadline") to (f.deadline() ?: "—"), (L["max"] ?: "max") to (f.maxPoint() ?: ""))) { _, r, shape -> KvRow(r.first, r.second, shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) }
                    if (f.files().isNotEmpty()) ListGroup(f.files()) { _, x, shape -> FileRow(x.name() ?: "file", x.url(), shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) }
                    else Meta(Icons.Rounded.FilePresent, f.fileText()?.ifBlank { null } ?: t.tt("file_none"))
                    if (!f.editable()) Text(t.tt("locked"), color = uz.tuit.lms.ui.theme.LocalTones.current.warning, style = MaterialTheme.typography.bodyMedium)

                    if (f.module() || f.criteria().isEmpty()) {
                        NumberField(grade, { grade = it }, "${L["grade"] ?: "grade"} (0 – ${f.maxPoint()})", f.editable())
                    } else {
                        Row(Modifier.padding(horizontal = 4.dp)) {
                            Text(L["criterion"] ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("${L["points"] ?: ""} / ${L["max"] ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        f.criteria().forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name() ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 10.dp))
                                OutlinedTextField(
                                    value = values[c.id()] ?: "", onValueChange = { values[c.id()] = it }, enabled = f.editable(), singleLine = true,
                                    suffix = { Text("/${c.max()}") }, textStyle = MaterialTheme.typography.bodyLarge.merge(Tabular).copy(textAlign = TextAlign.Center),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp), modifier = Modifier.width(130.dp),
                                )
                            }
                        }
                        val total = f.criteria().sumOf { values[it.id()]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0 }
                        Text("${L["total"] ?: "∑"}: ${fmtNum(Math.round(total * 100) / 100.0)} / ${f.maxPoint()}", style = MaterialTheme.typography.titleSmall.merge(Tabular), textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(comment, { comment = it }, enabled = f.editable(), label = { Text(L["comment"] ?: t.tt("comment_ph")) }, placeholder = { Text(t.tt("comment_ph")) },
                        minLines = 2, maxLines = 5, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    if (!sheet.hint().isNullOrBlank()) Text(sheet.hint(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

                    if (f.editable()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (f.canClear() && !f.grade().isNullOrBlank()) OutlinedButton(onClick = {
                            confirm.ask(t.tt("clear_confirm"), t.tt("delete"), danger = true) {
                                busy = "clear"
                                scope.launch {
                                    try {
                                        val res = Repo.tClearGrade(cell!!.studentId(), cell.activityId())
                                        if (!res.ok()) error = res.errors().joinToString("\n")
                                        else { snack.show(t.tt("cleared")); fill(Repo.tGradeForm(cell.studentId(), cell.activityId())); onSaved() }
                                    } catch (e: Exception) { error = t("error_lms") } finally { busy = null }
                                }
                            }
                        }, enabled = busy == null, modifier = Modifier.heightIn(min = 52.dp)) {
                            if (busy == "clear") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = {
                            val err = validate(f)
                            if (err != null) { error = err; return@Button }
                            busy = "save"
                            error = null
                            scope.launch {
                                try {
                                    val crit = !(f.module() || f.criteria().isEmpty())
                                    val (res, newForm) = Repo.tSetGrade(cell!!.studentId(), cell.activityId(),
                                        if (crit) values.mapValues { it.value.replace(',', '.') } else null,
                                        if (!crit) grade.replace(',', '.') else null, comment)
                                    if (!res.ok()) error = res.errors().joinToString("\n").ifBlank { t.tt("lms_rejected") }
                                    else { newForm?.let(::fill); snack.show(t.tt("saved")); onSaved() }
                                } catch (e: ApiException) {
                                    error = if (e.code == "out_of_range") t.tt("out_of_range") else if (e.code == "locked") t.tt("locked") else t("error_lms")
                                } catch (e: Exception) { error = t("error_lms") } finally { busy = null }
                            }
                        }, enabled = busy == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            if (busy == "save") { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(L["save"] ?: "OK")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { move(-1) }, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null); Text(t.tt("prev")) }
                        OutlinedButton(onClick = { move(1) }, modifier = Modifier.weight(1f)) { Text(t.tt("next")); Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
}

/* ─── Новая активность ─── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityForm(route: Route.TCourse, meta: TActivities, onClose: () -> Unit, onCreated: () -> Unit) {
    val t = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val L = meta.labels()
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var max by remember { mutableStateOf("") }
    val crit = remember { mutableStateListOf("" to "") }
    var file by remember { mutableStateOf<Files.Picked?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var dateOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { file = runCatching { Files.copyFromUri(context, uri) }.getOrNull() }
    }

    fun n(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    val sum = crit.sumOf { n(it.second) }
    val maxN = n(max)
    val ready = name.isNotBlank() && date != null && maxN > 0 && crit.all { it.first.isNotBlank() && n(it.second) > 0 } && Math.abs(sum - maxN) < 1e-9

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(t.tt("add_activity"), style = MaterialTheme.typography.titleLarge)
                Text(route.subject + (route.stream?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(name, { name = it }, label = { Text(L["name"] ?: "") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { dateOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.CalendarMonth, null)
                Spacer(Modifier.width(8.dp))
                Text(date?.let { t.dayMonth(it) + " " + it.year } ?: (L["deadline"] ?: t("deadline")), modifier = Modifier.weight(1f))
            }
            if (!meta.note().isNullOrBlank()) Text(meta.note(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberField(max, { max = it }, L["max_point"] ?: "max", true)
            if (!meta.budgetText().isNullOrBlank()) Text(meta.budgetText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(L["criterion"] ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(L["points"] ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            crit.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(c.first, { crit[i] = it to c.second }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                    OutlinedTextField(c.second, { crit[i] = c.first to it }, singleLine = true, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center), modifier = Modifier.width(84.dp))
                    IconButton(onClick = { crit.removeAt(i) }, enabled = crit.size > 1) { Icon(Icons.Rounded.Close, t.tt("delete")) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { crit.add("" to "") }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text(t.tt("add_criterion")) }
                Spacer(Modifier.weight(1f))
                Text(t.tt("criteria_sum", "sum" to fmtNum(Math.round(sum * 100) / 100.0), "max" to max.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall.merge(Tabular),
                    color = if (max.isNotBlank() && Math.abs(sum - maxN) > 1e-9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.AttachFile, null)
                Spacer(Modifier.width(8.dp))
                Text(file?.name ?: (L["file"]?.let { "$it · " } ?: "") + t.tt("pick_file"), maxLines = 1, modifier = Modifier.weight(1f))
            }
            if (meta.fileHints().isNotEmpty()) Text(meta.fileHints().joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (errors.isNotEmpty()) Text(errors.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                busy = true
                errors = emptyList()
                scope.launch {
                    try {
                        val d = date!!.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                        val res = Repo.tCreateActivity(route.id, name, d, max.replace(',', '.'), crit.map { it.first to it.second.replace(',', '.') }, file?.file, file?.name)
                        if (!res.ok()) errors = res.errors().ifEmpty { listOf(t.tt("lms_rejected")) }
                        else { file?.file?.delete(); onCreated() }
                    } catch (e: Exception) { errors = listOf(t("error_lms")) } finally { busy = false }
                }
            }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(t.tt("create"))
            }
        }
    }

    if (dateOpen) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (date ?: today().plusDays(7)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; dateOpen = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { dateOpen = false }) { Text(t("cancel")) } },
        ) { DatePicker(st) }
    }
}
