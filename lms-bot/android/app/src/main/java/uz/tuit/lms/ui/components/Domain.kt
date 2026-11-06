package uz.tuit.lms.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChecklistRtl
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uz.tuit.lms.data.ActStatus
import uz.tuit.lms.data.ActivityUi
import uz.tuit.lms.data.Files
import uz.tuit.lms.data.LessonUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.SemesterUi
import uz.tuit.lms.data.Urgency
import uz.tuit.lms.data.deadlineLabel
import uz.tuit.lms.data.duration
import uz.tuit.lms.data.hhmm
import uz.tuit.lms.data.urgency
import uz.tuit.lms.ui.theme.Tabular

/* ─── Задания ─── */

data class StatusInfo(val label: String, val tone: Tone, val icon: ImageVector)

fun statusInfo(s: ActStatus): StatusInfo = when (s) {
    ActStatus.UPLOADED -> StatusInfo("status_uploaded", Tone.SUCCESS, Icons.Rounded.CheckCircle)
    ActStatus.GRADED -> StatusInfo("status_graded", Tone.SUCCESS, Icons.Rounded.CheckCircle)
    ActStatus.OPEN -> StatusInfo("status_open", Tone.WARNING, Icons.Rounded.Schedule)
    ActStatus.MISSED -> StatusInfo("status_missed", Tone.DANGER, Icons.Rounded.Cancel)
    ActStatus.UNKNOWN -> StatusInfo("status_unknown", Tone.NEUTRAL, Icons.Rounded.HelpOutline)
}

fun Urgency.tone() = when (this) {
    Urgency.DANGER -> Tone.DANGER
    Urgency.WARNING -> Tone.WARNING
    Urgency.ACCENT -> Tone.ACCENT
    Urgency.NEUTRAL -> Tone.NEUTRAL
}

fun scoreText(a: ActivityUi) = "${a.earned ?: "—"} / ${a.max ?: "—"}"

/** Строка задания: статус, название, срок с обратным отсчётом и баллы. */
@Composable
fun ActivityRow(a: ActivityUi, shape: Shape, showCourse: Boolean = false, onClick: () -> Unit) {
    val t = LocalTr.current
    val now = System.currentTimeMillis()
    val st = a.status(now)
    val info = statusInfo(st)
    val u = if (st == ActStatus.OPEN) urgency(a.deadlineTs, now) else Urgency.NEUTRAL
    // Несданные различаются срочностью: меньше суток — красный, до трёх дней — жёлтый, дальше — синий.
    val tone = if (st == ActStatus.OPEN && u != Urgency.NEUTRAL) u.tone() else info.tone
    val chip = when {
        a.submitted -> Tone.SUCCESS
        st == ActStatus.MISSED -> Tone.DANGER
        st == ActStatus.OPEN -> Tone.WARNING
        else -> Tone.NEUTRAL
    }
    ListRow(shape = shape, onClick = onClick, leading = { IconTile(info.icon, tone) }, verticalAlignment = Alignment.Top, chevron = false) {
        if (showCourse && !a.course.isNullOrBlank()) {
            Text(a.course, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
        }
        RowTitle(a.task, maxLines = 3)
        Spacer(Modifier.height(8.dp))
        MetaRow {
            Pill(if (a.submitted) t("submitted_yes") else if (st == ActStatus.UNKNOWN) t(info.label) else t("submitted_no"), chip)
            if (a.deadlineTs != null) Meta(Icons.Rounded.AccessTime, deadlineLabel(a.deadlineTs, t))
            Meta(Icons.Rounded.EmojiEvents, scoreText(a))
            if (st == ActStatus.OPEN && a.deadlineTs != null && a.deadlineTs > now) Pill(duration(a.deadlineTs - now, t), u.tone())
        }
    }
}

/** Карточка задания: срок, баллы, критерии, файлы и загрузка работы. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySheet(activity: ActivityUi?, courseId: Int, course: String?, onClose: () -> Unit) {
    if (activity == null) return
    val a = activity
    val t = LocalTr.current
    val snack = LocalSnack.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var uploading by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val st = a.status(now)
    val info = statusInfo(st)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || a.activityId == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            try {
                val p = Files.copyFromUri(context, uri)
                val ext = p.name.substringAfterLast('.', "").lowercase()
                when {
                    ext !in UPLOAD_EXT -> snack.show(t("bad_type"), error = true)
                    p.size > UPLOAD_LIMIT -> snack.show(t("too_large"), error = true)
                    Repo.upload(courseId, a.activityId, p.file, p.name) -> {
                        snack.show(t("upload_done"))
                        state.hide()
                        onClose()
                    }
                    else -> snack.show(t("upload_failed"), error = true)
                }
                p.file.delete()
            } catch (e: Exception) {
                snack.show(t("upload_failed"), error = true)
            } finally {
                uploading = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                if (!course.isNullOrBlank()) Text(course, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(a.task, style = MaterialTheme.typography.titleLarge)
            }
            MetaRow {
                Pill(t(info.label), info.tone, info.icon)
                Pill(a.type?.takeIf { it.isNotBlank() } ?: t(if (a.lecture) "lecture" else "practice"), if (a.lecture) Tone.ACCENT else Tone.VIOLET)
            }
            val dl = a.deadlineTs
            val rows = buildList {
                add(t("deadline") to ((if (dl != null) deadlineLabel(dl, t) else a.deadline ?: "—") +
                    (if (dl != null && dl > now) "\n" + t("left", "x" to duration(dl - now, t)) else "")))
                add(t("score") to scoreText(a))
                if (!a.teacher.isNullOrBlank()) add(t("teacher") to a.teacher)
            }
            KvList(rows, color = MaterialTheme.colorScheme.surfaceContainerHigh)

            if (!a.criteria.isNullOrBlank()) CriteriaList(a)

            val files = listOfNotNull(a.sample?.let { Triple(it, t("task_file"), 0) }, a.uploaded?.let { Triple(it, t("my_upload"), 1) })
            if (files.isNotEmpty()) {
                ListGroup(files) { _, f, shape -> FileRow(f.first.name, f.first.url, shape, label = f.second, color = MaterialTheme.colorScheme.surfaceContainerHigh) }
            }

            if (a.canUpload(now)) {
                Button(
                    onClick = { picker.launch(UPLOAD_MIME) },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Rounded.Upload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (uploading) t("uploading_plain") else if (a.uploaded != null) t("reupload") else t("upload"))
                }
                if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text(t("allowed_types"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (st == ActStatus.MISSED) {
                Row(Modifier.align(Alignment.CenterHorizontally)) { Meta(Icons.Rounded.Lock, t("upload_closed")) }
            }
        }
    }
}

val UPLOAD_EXT = setOf("jpg", "jpeg", "png", "doc", "docx", "pdf", "ppt", "pptx", "zip", "rar")
const val UPLOAD_LIMIT = 50L * 1024 * 1024
val UPLOAD_MIME = arrayOf(
    "image/jpeg", "image/png", "application/pdf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/zip", "application/x-rar-compressed", "application/vnd.rar", "application/octet-stream",
)

/** Критерии оценивания: пункт на строку, баллы справа, внизу — сумма. */
@Composable
private fun CriteriaList(a: ActivityUi) {
    val t = LocalTr.current
    val items = a.criteriaItems.ifEmpty { (a.criteria ?: "").split("\n").filter { it.isNotBlank() }.map { uz.tuit.lms.data.CriterionUi(it, null) } }
    val scored = items.size > 1 && items.all { it.points != null }
    val total = if (scored) items.sumOf { it.points!!.toDoubleOrNull() ?: 0.0 } else null
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
            Icon(Icons.Rounded.ChecklistRtl, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(t("criteria"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val rows = items.mapIndexed { i, it -> Triple((i + 1).toString(), it.name, it.points) } + (if (total != null) listOf(Triple("", t("criteria_total"), uz.tuit.lms.data.fmtNum(total))) else emptyList())
        ListGroup(rows) { _, r, shape ->
            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (r.first.isNotEmpty()) {
                        Text(r.first, style = MaterialTheme.typography.labelLarge.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                    }
                    Text(r.second, style = MaterialTheme.typography.bodyMedium, fontWeight = if (r.first.isEmpty()) FontWeight.SemiBold else null, modifier = Modifier.weight(1f))
                    r.third?.let { Pill(it, if (r.first.isEmpty()) Tone.SUCCESS else Tone.ACCENT) }
                }
            }
        }
    }
}

/* ─── Пары ─── */

fun kindTone(kind: String) = when (kind) {
    "practice" -> Tone.VIOLET
    "lab" -> Tone.WARNING
    else -> Tone.ACCENT
}

fun kindLabel(kind: String) = when (kind) {
    "practice" -> "practice"
    "lab" -> "laboratory"
    else -> "lecture"
}

/** Пара: время начала и конца, вид занятия, аудитория, преподаватель, тема. onRollCall — преподавателю. */
@Composable
fun LessonRow(l: LessonUi, shape: Shape, now: Long = System.currentTimeMillis(), onRollCall: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val t = LocalTr.current
    val live = l.live(now)
    val done = l.done(now)
    val tone = kindTone(l.kind)
    val color = if (live) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow
    val alpha = if (done) 0.6f else 1f
    ListRow(shape = shape, color = color, onClick = onRollCall ?: onClick, chevron = onRollCall == null && onClick != null, verticalAlignment = Alignment.Top, leading = {
        Column(Modifier.widthIn(min = 50.dp), horizontalAlignment = Alignment.Start) {
            Text(l.time.take(5), style = MaterialTheme.typography.titleMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(l.endTs.hhmm(), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
    }, trailing = null) {
        Row {
            Box(Modifier.padding(end = 12.dp).width(4.dp).height(44.dp).clip(RoundedCornerShape(2.dp)).background(tone.content().copy(alpha = alpha)))
            Column(Modifier.weight(1f)) {
                RowTitle(l.subject, maxLines = 3, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                Spacer(Modifier.height(6.dp))
                MetaRow {
                    Pill(t(kindLabel(l.kind)), tone)
                    if (l.stream != null) StreamChip(l.stream)
                    if (live) Pill(t("now"), Tone.SUCCESS)
                    if (onRollCall != null) Pill(t.tt("roll_call"), Tone.ACCENT, Icons.Rounded.HowToReg)
                    if (l.room.isNotBlank()) Meta(Icons.Rounded.Place, l.room)
                    if (l.teacher.isNotBlank()) Meta(Icons.Rounded.Person, l.teacher)
                }
                if (!l.topic.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Meta(Icons.Rounded.Notes, l.topic)
                }
                if (l.last) {
                    Spacer(Modifier.height(6.dp))
                    Meta(Icons.Rounded.Flag, t("last_lesson"), color = uz.tuit.lms.ui.theme.LocalTones.current.warning)
                }
                if (live) {
                    Spacer(Modifier.height(10.dp))
                    val f = ((now - l.ts).toFloat() / LessonUi.PAIR_MS).coerceIn(0f, 1f)
                    AnimatedProgress(f, height = 4.dp)
                }
            }
        }
    }
}

/* ─── Семестр ─── */

fun shortSemester(name: String) = name.replace(Regex("\\s+"), " ").replace(Regex("(?i)\\s*(учебный|o'quv)\\s*(год|yili)?"), "").trim()

@Composable
fun SemesterChip(onClick: () -> Unit) {
    val t = LocalTr.current
    val sem by Prefs.semester.collectAsState()
    val list = remember(sem) { Prefs.semesters }
    val id = Prefs.semesterId()
    val name = list.firstOrNull { it.id == id }?.name ?: t("choose_semester")
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(shortSemester(name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSheet(open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val t = LocalTr.current
    val list: List<SemesterUi> = remember { Prefs.semesters }
    val current = Prefs.currentSemester
    val selected = Prefs.semesterId()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(t("choose_semester"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
            if (list.isEmpty()) Text(t("no_semester"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ListGroup(list) { _, s, shape ->
                    ListRow(
                        shape = shape,
                        color = if (s.id == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        onClick = { Prefs.setSemester(s.id); onClose() },
                        trailing = { if (s.id == selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                    ) {
                        RowTitle(shortSemester(s.name))
                        if (s.id == current) RowSub(t("current"))
                    }
                }
            }
        }
    }
}

/* ─── Плитка-счётчик на главной ─── */

@Composable
fun StatCard(icon: ImageVector, tone: Tone, value: String, label: String, loading: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            IconTile(icon, tone, size = 34.dp, iconSize = 18.dp)
            Spacer(Modifier.height(12.dp))
            if (loading) Skeleton(Modifier.fillMaxWidth(0.6f), height = 26.dp)
            else Text(value, style = MaterialTheme.typography.headlineSmall.merge(Tabular).copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
    }
}
