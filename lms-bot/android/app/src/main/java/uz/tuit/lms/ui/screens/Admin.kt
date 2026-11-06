package uz.tuit.lms.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uz.tuit.lms.data.AdminApi
import uz.tuit.lms.data.AdminPage
import uz.tuit.lms.data.AdminStudent
import uz.tuit.lms.data.ApiException
import uz.tuit.lms.data.Files
import uz.tuit.lms.data.Tr
import uz.tuit.lms.data.toLocal
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.components.AppCard
import uz.tuit.lms.ui.components.Avatar
import uz.tuit.lms.ui.components.ChipRow
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
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
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.components.cardColor
import uz.tuit.lms.ui.theme.Tabular

/** «5 мин назад», «3 ч назад», «2 дн назад», дальше — дата. */
fun ago(ts: Long, t: Tr): String {
    if (ts <= 0) return "—"
    val min = (System.currentTimeMillis() - ts) / 60000
    return when {
        min < 1 -> t("just_now")
        min < 60 -> t("ago_min", "n" to min)
        min < 24 * 60 -> t("ago_h", "n" to min / 60)
        min < 7 * 24 * 60 -> t("ago_d", "n" to min / (24 * 60))
        else -> ts.toLocal().toLocalDate().let { t.dayMonth(it, true) + " " + it.year }
    }
}

private fun gpaTone(g: Double?) = when {
    g == null -> Tone.NEUTRAL
    g >= 4.5 -> Tone.SUCCESS
    g >= 4 -> Tone.ACCENT
    g >= 3 -> Tone.WARNING
    else -> Tone.DANGER
}

private val FILTERS = listOf("group" to "group", "course" to "course_year", "direction" to "direction", "gender" to "gender", "studyType" to "col_type", "language" to "col_lang", "role" to "role")

/** Пункт «Админ-панель» для профиля: виден только администратору. */
@Composable
fun AdminEntry() {
    val t = LocalTr.current
    val nav = LocalNav.current
    if (!AdminApi.visible()) return
    Column {
        SectionHeader(t("admin_panel"))
        ListGroup(listOf(0)) { _, _, shape ->
            ListRow(shape, onClick = { nav.push(Route.Admin) }, leading = { IconTile(Icons.Rounded.AdminPanelSettings, Tone.DANGER) }) {
                RowTitle(t("students"))
                RowSub(t("admin_sub"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdminScreen() {
    val t = LocalTr.current
    var hasCode by remember { mutableStateOf(AdminApi.access() != null) }
    if (!hasCode) {
        AdminCodeScreen { hasCode = true }
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("lastSeen") }
    var desc by rememberSaveable { mutableStateOf(true) }
    val filters = remember { mutableStateMapOf<String, String>() }
    var filtersOpen by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<AdminStudent?>(null) }

    val items = remember { mutableStateListOf<AdminStudent>() }
    var page by remember { mutableStateOf<AdminPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(query) { delay(350); debounced = query.trim() }

    suspend fun load(p: Int) {
        loading = true
        try {
            val params = buildMap {
                put("sort", sort); put("dir", if (desc) "desc" else "asc"); put("page", p.toString()); put("size", "30")
                if (debounced.isNotEmpty()) put("q", debounced)
                putAll(filters)
            }
            val res = AdminApi.students(params)
            if (p == 1) items.clear()
            items.addAll(res.items)
            page = res
            error = null
        } catch (e: Exception) {
            error = e
        } finally {
            loading = false
        }
    }

    // Любое изменение условий — снова с первой страницы.
    LaunchedEffect(debounced, sort, desc, filters.toMap(), reload) { load(1) }
    // Докручивание до конца списка подгружает следующую страницу.
    LaunchedEffect(listState, page) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val p = page ?: return@collect
            if (!loading && p.page < p.pages && last >= listState.layoutInfo.totalItemsCount - 4) load(p.page + 1)
        }
    }

    val forbidden = (error as? ApiException)?.code == "admin_forbidden"
    DetailScreen(
        title = t("admin_panel"),
        subtitle = t("admin_sub"),
        refreshing = loading && items.isNotEmpty() && page?.page == 1,
        onRefresh = { reload++ },
        listState = listState,
        actions = {
            FilledTonalIconButton(onClick = { filtersOpen = true }, modifier = Modifier.padding(end = 6.dp),
                colors = if (filters.isNotEmpty()) IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                else IconButtonDefaults.filledTonalIconButtonColors(containerColor = cardColor())) {
                Icon(Icons.Rounded.Tune, t("filters"))
            }
        },
    ) {
        page?.stats?.let { s ->
            item {
                FlowRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                    listOfNotNull(
                        t("st_total") to s.total.toString(),
                        t("st_24h") to s.active24h.toString(),
                        t("st_7d") to s.active7d.toString(),
                        s.loggedIn?.let { t("st_logged_in") to it.toString() },
                        s.sessionsAlive?.let { t("st_sessions") to "$it/${s.sessionsOnDisk ?: "—"}" },
                        t("st_avg_gpa") to (s.avgGpa?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"),
                    ).forEach { (label, value) ->
                        AppCard(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                            Text(value, style = MaterialTheme.typography.titleLarge.merge(Tabular), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(t("search_ph")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null) } }) else null,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = cardColor(), unfocusedContainerColor = cardColor(),
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                ChipRow(
                    listOf(Opt("lastSeen", t("col_seen")), Opt("gpa", t("stat_gpa")), Opt("fullName", t("col_name")), Opt("group", t("group")), Opt("course", t("course_year")), Opt("birthDate", t("col_birth"))),
                    sort, {
                        if (it == sort) desc = !desc
                        else { sort = it; desc = it == "lastSeen" || it == "gpa" }
                    }, Modifier.weight(1f),
                )
                IconButton(onClick = { desc = !desc }) { Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, tint = MaterialTheme.colorScheme.primary) }
            }
        }
        page?.let { p ->
            item {
                Text(
                    if (p.total == 0) "" else t("shown", "from" to 1, "to" to items.size, "total" to p.total),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 8.dp),
                )
            }
        }
        when {
            items.isEmpty() && loading -> item { ListSkeleton(6) }
            items.isEmpty() && error != null -> item {
                if (forbidden) EmptyState(Icons.Rounded.AdminPanelSettings, t("admin_code_bad"), t("admin_code_hint"), Tone.DANGER) {
                    TextButton(onClick = { AdminApi.forget(); hasCode = false }) { Text(t("admin_code_enter")) }
                } else if ((error as? ApiException)?.code == "admin_old_server") EmptyState(Icons.Rounded.AdminPanelSettings, t("admin_old_server"), null, Tone.WARNING)
                else ErrorState(error, { reload++ })
            }
            items.isEmpty() -> item { EmptyState(Icons.Rounded.Groups, t("no_students"), t("no_students_sub")) }
            else -> item {
                ListGroup(items.toList(), key = { it.telegramId }) { _, s, shape -> StudentRow(s, shape, t) { open = s } }
            }
        }
        if (loading && items.isNotEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) }
    }

    if (filtersOpen) FiltersSheet(page?.facets ?: emptyMap(), filters) { filtersOpen = false }
    open?.let { StudentSheet(it) { open = null } }
}

@Composable
private fun StudentRow(s: AdminStudent, shape: androidx.compose.ui.graphics.Shape, t: Tr, onClick: () -> Unit) {
    val name = s.fullName ?: s.tgName ?: "—"
    ListRow(shape, onClick = onClick, leading = { Avatar(null, name, 38.dp) }, trailing = {
        if (s.gpa != null) Pill(String.format(java.util.Locale.US, "%.2f", s.gpa), gpaTone(s.gpa))
    }) {
        RowTitle(name, maxLines = 2)
        MetaRow(Modifier.padding(top = 4.dp)) {
            if (s.role == "teacher") Pill(t.tt("role_teacher"), Tone.VIOLET)
            if (!s.group.isNullOrBlank()) Meta(null, s.group)
            if (!s.course.isNullOrBlank()) Meta(null, s.course)
            Meta(null, ago(s.lastSeen, t))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(facets: Map<String, List<String>>, filters: MutableMap<String, String>, onClose: () -> Unit) {
    val t = LocalTr.current
    val draft = remember { mutableStateMapOf<String, String>().apply { putAll(filters) } }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("filters"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft.clear() }) { Text(t("reset")) }
            }
            FILTERS.forEach { (key, label) ->
                val values = facets[key].orEmpty()
                if (values.isEmpty()) return@forEach
                SectionHeader(if (key == "role") t.tt("role_teacher") + " / " + t("students") else t(label))
                ChipRow(listOf(Opt("", t("any"))) + values.map { Opt(it, it) }, draft[key] ?: "", { v -> if (v.isEmpty()) draft.remove(key) else draft[key] = v })
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { filters.clear(); filters.putAll(draft); onClose() }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(t("show_results")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentSheet(s: AdminStudent, onClose: () -> Unit) {
    val t = LocalTr.current
    val context = LocalContext.current
    val name = s.fullName ?: s.tgName ?: "—"
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(null, name, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    if (s.gpa != null) Pill("GPA ${String.format(java.util.Locale.US, "%.2f", s.gpa)}", gpaTone(s.gpa), modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            val rows = listOf(
                t("record_book") to s.recordBook, t("group") to s.group, t("direction") to s.direction, t("course_year") to s.course,
                t("gender") to s.gender, t("birth") to s.birthDate, t("curator") to s.curator, t("study_type") to s.studyType,
                t("study_lang") to s.language, t.tt("info") to s.department, t("telegram") to (s.tgName ?: "") + (s.tgUsername?.let { " · @$it" } ?: ""),
                t("first_seen") to ago(s.firstSeen, t), t("col_seen") to ago(s.lastSeen, t),
            ).filter { !it.second.isNullOrBlank() }
            ListGroup(rows) { _, r, shape -> KvRow(r.first, r.second, shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { Files.openUrl(context, if (!s.tgUsername.isNullOrBlank()) "https://t.me/${s.tgUsername}" else "tg://user?id=${s.telegramId}") },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("admin_write_tg"))
            }
        }
    }
}

/** Первый вход: вставить код из бота (/adminapp). */
@Composable
private fun AdminCodeScreen(onSaved: () -> Unit) {
    val t = LocalTr.current
    val context = LocalContext.current
    val snack = LocalSnack.current
    var code by remember { mutableStateOf("") }
    DetailScreen(title = t("admin_panel")) {
        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.AdminPanelSettings, Tone.DANGER, 44.dp, 24.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(t("admin_code_title"), style = MaterialTheme.typography.titleMedium)
                }
                Text(t("admin_code_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    code, { code = it }, singleLine = true, placeholder = { Text("LMSADMIN:…") }, shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { code = it }
                        }) { Icon(Icons.Rounded.ContentPaste, null) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { Files.openUrl(context, "https://t.me/lmstgbot") }) { Text(t("open_bot")) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (AdminApi.save(code)) onSaved() else snack.show(t("admin_code_bad"), error = true)
                    }, enabled = code.isNotBlank()) { Text(t("save")) }
                }
            }
        }
    }
}
