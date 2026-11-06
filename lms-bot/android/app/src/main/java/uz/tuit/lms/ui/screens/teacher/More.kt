package uz.tuit.lms.ui.screens.teacher

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uz.tuit.lms.data.Files
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
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
import uz.tuit.lms.ui.components.LocalSnack
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Meta
import uz.tuit.lms.ui.components.MetaRow
import uz.tuit.lms.ui.components.Opt
import uz.tuit.lms.ui.components.Pill
import uz.tuit.lms.ui.components.RowSub
import uz.tuit.lms.ui.components.RowTitle
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.SemesterChip
import uz.tuit.lms.ui.components.SemesterSheet
import uz.tuit.lms.ui.components.StreamChip
import uz.tuit.lms.ui.components.TextTile
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.components.rememberConfirm
import uz.tuit.lmsbot.service.TeacherService.AppealPage
import uz.tuit.lmsbot.service.TeacherService.Option
import uz.tuit.lmsbot.service.TeacherService.Select2Item
import uz.tuit.lmsbot.service.TeacherService.TTopic

/* ─── Исправление НБ ─── */

@Composable
fun AppealsScreen() {
    val t = LocalTr.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirm()
    val q = rememberQuery("tappeals") { Repo.tAppeals() }
    var creating by remember { mutableStateOf(false) }
    val page = q.data?.page

    DetailScreen(
        title = page?.title()?.takeIf { it.isNotBlank() } ?: t.tt("appeals"), refreshing = q.refreshing, onRefresh = q::refresh,
        actions = {
            androidx.compose.material3.FilledIconButton(onClick = { creating = true }, enabled = page != null, modifier = Modifier.padding(end = 6.dp)) {
                Icon(Icons.Rounded.Add, t.tt("new_appeal"))
            }
        },
    ) {
        if (!page?.instructions().isNullOrBlank()) item {
            AppCard { Text(page!!.instructions(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { Spacer(Modifier.height(16.dp)) }
        when {
            q.initialLoading -> item { ListSkeleton(3, tall = true) }
            q.data == null -> item { ErrorState(q.error, q::refresh) }
            q.data!!.items.isEmpty() -> item { EmptyState(Icons.Rounded.Description, t.tt("no_appeals")) }
            else -> q.data!!.items.forEach { a ->
                item(key = a.id()) {
                    val tone = when (a.status()) { 1 -> Tone.SUCCESS; 2 -> Tone.DANGER; else -> Tone.ACCENT }
                    AppCard(Modifier.padding(bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                MetaRow {
                                    Meta(null, "#${a.id()}")
                                    if (!a.stream().isNullOrBlank()) StreamChip(a.stream())
                                    Meta(null, a.date() ?: "")
                                    if (!a.pair().isNullOrBlank()) Meta(null, "${page?.labels()?.get("pair") ?: ""}: ${a.pair()}")
                                }
                                if (!a.theme().isNullOrBlank()) Text(a.theme(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                                if (!a.students().isNullOrBlank()) Box(Modifier.padding(top = 4.dp)) { Meta(Icons.Rounded.Person, a.students()) }
                                Pill(a.statusText() ?: "", tone, modifier = Modifier.padding(top = 8.dp))
                            }
                            if (a.status() == 0) IconButton(onClick = {
                                confirm.ask(t.tt("delete_confirm", "name" to "#${a.id()}"), t.tt("delete"), danger = true) {
                                    scope.launch {
                                        val res = runCatching { Repo.tDeleteAppeal(a.id()) }.getOrNull()
                                        if (res?.ok() == true) snack.show(t.tt("deleted")) else snack.show(res?.errors()?.joinToString(", ")?.ifBlank { null } ?: t.tt("lms_rejected"), error = true)
                                    }
                                }
                            }) { Icon(Icons.Rounded.Delete, t.tt("delete"), tint = MaterialTheme.colorScheme.error) }
                        }
                        if (a.status() == 1) Box(Modifier.padding(top = 10.dp)) {
                            FileRow("#${a.id()}.pdf", Repo.tAppealPdfUrl(a.id()), uz.tuit.lms.ui.components.groupShape(0, 1), type = "pdf", color = MaterialTheme.colorScheme.surfaceContainerHigh)
                        }
                    }
                }
            }
        }
    }
    if (creating && page != null) AppealForm(page, onClose = { creating = false }) { creating = false; snack.show(t.tt("appeal_sent")) }
}

/** Пошагово: поток → занятие → студент (сначала с НБ) → номер пары. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppealForm(page: AppealPage, onClose: () -> Unit, onDone: () -> Unit) {
    val t = LocalTr.current
    val scope = rememberCoroutineScope()
    val L = page.labels()
    var stream by remember { mutableStateOf<Option?>(null) }
    var lessons by remember { mutableStateOf<List<Select2Item>?>(null) }
    var lesson by remember { mutableStateOf<Select2Item?>(null) }
    var students by remember { mutableStateOf<List<Select2Item>?>(null) }
    var student by remember { mutableStateOf<Select2Item?>(null) }
    var pair by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val step = when { stream == null -> 0; lesson == null -> 1; student == null -> 2; else -> 3 }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) IconButton(onClick = { when (step) { 1 -> stream = null; 2 -> lesson = null; else -> student = null } }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("back")) }
                Column(Modifier.padding(start = 6.dp)) {
                    Text(t.tt("new_appeal"), style = MaterialTheme.typography.titleLarge)
                    val sub = listOfNotNull(stream?.text(), lesson?.text(), student?.text()).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            Text(when (step) { 0 -> L["course_part_id"]; 1 -> L["teacher_calendar_id"]; 2 -> L["students[]"]; else -> L["pair"] } ?: "",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp))
            when (step) {
                0 -> Picker(page.streams(), { it.text() ?: "" }) { o ->
                    stream = o; lessons = null; error = null
                    scope.launch { lessons = runCatching { Repo.tAppealLessons(o.id()) }.getOrElse { error = t("error_lms"); emptyList() } }
                }
                1 -> lessons?.let { list -> Picker(list, { it.text() ?: "" }) { l ->
                    lesson = l; students = null; error = null
                    scope.launch { students = runCatching { Repo.tAppealStudents(l.id()).sortedBy { if (it.present() == false) 0 else 1 } }.getOrElse { error = t("error_lms"); emptyList() } }
                } } ?: ListSkeleton(4)
                2 -> students?.let { list -> Picker(list, { (if (it.present() == false) "● " else "") + (it.text() ?: "") }) { student = it } } ?: ListSkeleton(5)
                else -> {
                    OutlinedTextField(pair, { pair = it.filter(Char::isDigit).take(2) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val res = Repo.tCreateAppeal(stream!!.id(), lesson!!.id(), pair, student!!.id())
                                if (res.ok()) onDone() else error = res.errors().joinToString("\n").ifBlank { t.tt("lms_rejected") }
                            } catch (e: Exception) { error = t("error_lms") } finally { busy = false }
                        }
                    }, enabled = pair.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(t.tt("submit"))
                    }
                }
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun <T> Picker(items: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    if (items.isEmpty()) { EmptyState(Icons.Rounded.Description, "—"); return }
    ListGroup(items) { _, x, shape -> ListRow(shape, onClick = { onPick(x) }, color = MaterialTheme.colorScheme.surfaceContainerHigh) { RowTitle(label(x)) } }
}

/* ─── Учебные материалы ─── */

@Composable
fun MaterialsScreen() {
    val t = LocalTr.current
    val nav = LocalNav.current
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    var semOpen by remember { mutableStateOf(false) }
    val q = rememberQuery("tsubjects:$sem") { Repo.tSubjects(sem) }
    DetailScreen(title = t.tt("materials"), refreshing = q.refreshing, onRefresh = q::refresh) {
        item { Box(Modifier.padding(bottom = 14.dp)) { SemesterChip { semOpen = true } } }
        when {
            q.initialLoading -> item { ListSkeleton(4, tall = true) }
            q.data == null -> item { ErrorState(q.error, q::refresh) }
            q.data!!.isEmpty() -> item { EmptyState(Icons.Rounded.FolderOpen, t.tt("no_subjects")) }
            else -> item {
                ListGroup(q.data!!) { _, s, shape ->
                    ListRow(shape, onClick = { nav.push(Route.Material(s.id(), s.subject() ?: "", s.language() ?: "ru")) }, leading = { IconTile(Icons.Rounded.MenuBook) }) {
                        RowTitle(s.subject() ?: "")
                        MetaRow(Modifier.padding(top = 5.dp)) {
                            if (!s.language().isNullOrBlank()) Pill(s.language(), Tone.ACCENT)
                            if (!s.code().isNullOrBlank()) Meta(null, s.code())
                        }
                        if (!s.department().isNullOrBlank()) RowSub(s.department(), maxLines = 2)
                    }
                }
            }
        }
    }
    SemesterSheet(semOpen) { semOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialScreen(route: Route.Material) {
    val t = LocalTr.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirm()
    val sem = remember { Prefs.semesterId() }
    val lang = route.language.lowercase()
    val page = rememberQuery("tmpage:${route.id}:$sem:$lang") { Repo.tMaterialPage(route.id, sem, lang) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    val lessonType = type ?: page.data?.lessonTypes()?.firstOrNull()?.id()
    val topics = rememberQuery(lessonType?.let { "ttopics:${route.id}:$sem:$lang:$it" }) { Repo.tTopics(route.id, sem, lang, lessonType!!) }
    var openId by remember { mutableStateOf<Int?>(null) }
    fun typeName(x: String) = page.data?.contentTypes()?.firstOrNull { it.id() == x }?.text() ?: x

    DetailScreen(title = route.subject, subtitle = page.data?.title(), refreshing = page.refreshing || topics.refreshing, onRefresh = { page.refresh(); topics.refresh() }) {
        val types = page.data?.lessonTypes() ?: emptyList()
        if (types.size > 1) item { ChipRow(types.map { Opt(it.id(), it.text() ?: it.id()) }, lessonType ?: "", { type = it }, Modifier.padding(bottom = 14.dp)) }
        when {
            page.initialLoading || topics.initialLoading -> item { ListSkeleton(6) }
            page.data == null -> item { ErrorState(page.error, page::refresh) }
            topics.data == null -> item { ErrorState(topics.error, topics::refresh) }
            topics.data!!.isEmpty() -> item { EmptyState(Icons.Rounded.FolderOpen, t.tt("no_topics")) }
            else -> item {
                ListGroup(topics.data!!, key = { it.id() }) { _, tp, shape ->
                    ListRow(shape, onClick = { openId = tp.id() }, verticalAlignment = Alignment.Top,
                        leading = { TextTile(tp.number() ?: "", if (tp.resources().isNotEmpty()) Tone.SUCCESS else Tone.NEUTRAL, fontSize = 14) }) {
                        RowTitle(tp.name() ?: "", maxLines = 4)
                        if (tp.resources().isNotEmpty()) RowSub(tp.resources().joinToString(" · ") { it.name()?.ifBlank { null } ?: typeName(it.type() ?: "") }, maxLines = 2)
                    }
                }
            }
        }
    }

    val current: TTopic? = topics.data?.firstOrNull { it.id() == openId }
    val pd = page.data
    if (current != null && pd != null) {
        ModalBottomSheet(onDismissRequest = { openId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
                Text("${current.number()}. ${current.name()}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                if (current.resources().isEmpty()) EmptyState(Icons.Rounded.FolderOpen, t.tt("no_materials"))
                else ListGroup(current.resources(), key = { it.id() }) { _, r, shape ->
                    FileRow(
                        r.name()?.ifBlank { null } ?: typeName(r.type() ?: ""), r.url() ?: "", shape,
                        type = if (r.type() == "url" || r.type() == "meeting") "url" else null,
                        label = typeName(r.type() ?: ""), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        trailingExtra = if (!r.pastDate()) ({
                            IconButton(onClick = {
                                confirm.ask(t.tt("delete_confirm", "name" to (r.name() ?: r.id().toString())), t.tt("delete"), danger = true) {
                                    scope.launch {
                                        val res = runCatching { Repo.tDeleteMaterial(r.id()) }.getOrNull()
                                        if (res?.ok() == true) snack.show(t.tt("deleted")) else snack.show(res?.errors()?.joinToString(", ")?.ifBlank { null } ?: t.tt("lms_rejected"), error = true)
                                    }
                                }
                            }) { Icon(Icons.Rounded.Delete, t.tt("delete"), tint = MaterialTheme.colorScheme.error) }
                        }) else null,
                    )
                }
                AddMaterial(route, sem, lang, current, pd.contentTypes().filter { it.id() != "meeting" }, pd.labels())
            }
        }
    }
}

/** Добавление материала: вид — из формы LMS; ссылка — поле URL, остальное — файл и название. */
@Composable
private fun AddMaterial(route: Route.Material, sem: Int, lang: String, topic: TTopic, types: List<Option>, labels: Map<String, String>) {
    val t = LocalTr.current
    val snack = LocalSnack.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(types.firstOrNull { it.id() == "file" }?.id() ?: types.firstOrNull()?.id() ?: "") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Files.Picked?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isUrl = type == "url"
    val ready = if (isUrl) Regex("(?i)^https?://\\S+$").matches(url.trim()) else file != null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { file = runCatching { Files.copyFromUri(context, uri) }.getOrNull() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(t.tt("add"))
        ChipRow(types.map { Opt(it.id(), it.text() ?: it.id()) }, type, { type = it })
        if (isUrl) OutlinedTextField(url, { url = it }, label = { Text(labels["url"] ?: "URL") }, placeholder = { Text("https://") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        else {
            OutlinedTextField(name, { name = it }, label = { Text(labels["name"] ?: "") }, placeholder = { Text(file?.name ?: "") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.AttachFile, null); Spacer(Modifier.width(8.dp)); Text(file?.name ?: t.tt("pick_file"), maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val res = Repo.tAddMaterial(route.id, sem, topic.id(), lang, type,
                        if (isUrl) null else name.trim().ifBlank { file?.name?.substringBeforeLast('.') },
                        if (isUrl) url.trim() else null, if (isUrl) null else file?.file, file?.name)
                    if (res.ok()) { name = ""; url = ""; file?.file?.delete(); file = null; snack.show(t.tt("material_added")) }
                    else error = res.errors().joinToString("\n").ifBlank { t.tt("lms_rejected") }
                } catch (e: Exception) { error = t("error_lms") } finally { busy = false }
            }
        }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp)); Text(t.tt("add"))
        }
    }
}
