package uz.tuit.lms.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uz.tuit.lms.data.ContractUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.ProfileUi
import uz.tuit.lms.data.Query
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.money
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.components.AnimatedProgress
import uz.tuit.lms.ui.components.AppCard
import uz.tuit.lms.ui.components.Avatar
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.IconTile
import uz.tuit.lms.ui.components.KvRow
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListRow
import uz.tuit.lms.ui.components.LocalSnack
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Pill
import uz.tuit.lms.ui.components.RowTitle
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.Skeleton
import uz.tuit.lms.ui.components.TabScreen
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.ui.components.rememberConfirm
import uz.tuit.lms.ui.theme.LocalTones
import uz.tuit.lms.ui.theme.Tabular

@Composable
fun ProfileScreen() {
    val t = LocalTr.current
    val info = rememberQuery("profile") { Repo.profile() }
    val photo = rememberQuery("photo") { Repo.photo() }
    val contract = rememberQuery("contract") { Repo.contract() }
    var photoOpen by remember { mutableStateOf(false) }
    val p = info.data
    val name = p?.fullName ?: Prefs.displayName

    TabScreen(title = t("profile_title"), refreshing = info.refreshing || contract.refreshing, onRefresh = { info.refresh(); contract.refresh() }, actions = { SettingsAction() }) {
        item {
            ProfileCard(photo.data?.dataUrl, photo.initialLoading, name, info.initialLoading, onPhoto = { photoOpen = true }) {
                if (!p?.recordBook.isNullOrBlank()) Text("${t("record_book")} ${p!!.recordBook}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!p?.group.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Pill(p!!.group!!, Tone.ACCENT) }
            }
        }
        item { ContractCard(contract) }
        if (info.data == null && info.error != null) item { Column(Modifier.padding(top = 16.dp)) { ErrorState(info.error, info::refresh) } }
        if (p != null) {
            val study = studyRows(p, t)
            if (study.isNotEmpty()) {
                item { SectionHeader(t("study")) }
                item { ListGroup(study) { _, r, shape -> KvRow(r.first, r.second, shape) } }
            }
            val personal = listOf(t("birth") to p.birthDate, t("gender") to p.gender, t("address") to p.address).filter { !it.second.isNullOrBlank() }
            if (personal.isNotEmpty()) {
                item { SectionHeader(t("personal")) }
                item { ListGroup(personal) { _, r, shape -> KvRow(r.first, r.second, shape) } }
            }
        }
        item { AdminEntry() }
        accountSection()
    }
    PhotoDialog(photoOpen, photo.data?.dataUrl, name) { photoOpen = false }
}

private fun studyRows(p: ProfileUi, t: uz.tuit.lms.data.Tr) = listOf(
    t("direction") to p.direction, t("course_year") to p.course, t("group") to p.group, t("study_lang") to p.language,
    t("degree") to p.degree, t("study_type") to p.studyType, t("curator") to p.curator, t("scholarship") to p.scholarship,
).filter { !it.second.isNullOrBlank() }

@Composable
fun ProfileCard(dataUrl: String?, photoLoading: Boolean, name: String, loading: Boolean, onPhoto: () -> Unit, extra: @Composable () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dataUrl != null) {
                Surface(onClick = onPhoto, shape = RoundedCornerShape(20.dp)) {
                    Avatar(dataUrl, name, 96.dp, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(width = 84.dp, height = 108.dp))
                }
            } else if (photoLoading) Skeleton(Modifier.size(width = 84.dp, height = 108.dp), height = 108.dp, shape = RoundedCornerShape(20.dp))
            else Avatar(null, name, 84.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (loading && name.isBlank()) {
                    Skeleton(Modifier.fillMaxWidth(0.8f), height = 18.dp)
                    Spacer(Modifier.height(8.dp))
                    Skeleton(Modifier.fillMaxWidth(0.5f), height = 13.dp)
                } else {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    extra()
                }
            }
        }
    }
}

@Composable
fun PhotoDialog(open: Boolean, dataUrl: String?, name: String, onClose: () -> Unit) {
    if (!open || dataUrl == null) return
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(LocalTr.current("close")) } },
        title = { Text(name, style = MaterialTheme.typography.titleMedium) },
        text = { Avatar(dataUrl, name, 280.dp, shape = RoundedCornerShape(24.dp), modifier = Modifier.size(width = 240.dp, height = 310.dp)) },
    )
}

/** Шкала оплаты контракта. Пока LMS не выставила контракт — «ещё не сформирован». */
@Composable
private fun ContractCard(q: Query<ContractUi>) {
    val t = LocalTr.current
    val c = q.data
    if (q.initialLoading) { Skeleton(Modifier.padding(top = 26.dp).fillMaxWidth(), height = 80.dp, shape = MaterialTheme.shapes.large); return }
    if (c == null) return
    Column {
        SectionHeader(t("contract"))
        if (!c.found) {
            val year = c.notice?.let { Regex("\\d{4}\\s*[-–]\\s*\\d{4}").find(it)?.value?.replace(Regex("\\s"), "")?.replace('–', '-') } ?: c.year ?: ""
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.HourglassEmpty, Tone.NEUTRAL)
                    Spacer(Modifier.width(14.dp))
                    Text(if (year.isNotBlank()) t("contract_not_formed", "year" to year) else t("contract_empty"), style = MaterialTheme.typography.bodyLarge)
                }
            }
            return
        }
        val total = c.total ?: if (c.paid != null && c.debt != null) c.paid + c.debt else null
        val pct = if (total != null && total > 0 && c.paid != null) minOf(100, Math.round(c.paid * 100.0 / total).toInt()) else null
        val done = (c.debt ?: 1) <= 0 || pct == 100
        val tones = LocalTones.current
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.AccountBalanceWallet, if (done) Tone.SUCCESS else Tone.WARNING)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(pct?.let { "$it%" } ?: money(c.paid), style = MaterialTheme.typography.headlineSmall.merge(Tabular).copy(fontWeight = FontWeight.Bold))
                    Text(if (done) t("contract_done") else "${t("contract_paid")} ${money(c.paid)} ${t("currency")}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (pct != null) {
                Spacer(Modifier.height(16.dp))
                AnimatedProgress(pct / 100f, height = 10.dp, color = if (done) tones.success else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(14.dp))
            val rows = listOf(
                Triple(t("contract_paid"), "${money(c.paid)} ${t("currency")}", tones.success),
                Triple(t("contract_debt"), "${money(c.debt)} ${t("currency")}", if ((c.debt ?: 0) > 0) tones.danger else MaterialTheme.colorScheme.onSurface),
                Triple(t("contract_total"), "${money(total)} ${t("currency")}", MaterialTheme.colorScheme.onSurface),
            )
            ListGroup(rows) { _, r, shape -> KvRow(r.first, r.second, shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, valueColor = r.third) }
        }
    }
}

/** Низ профиля: только версия — настройки, пароль и выход живут за значком ⚙ в заголовке. */
fun androidx.compose.foundation.lazy.LazyListScope.accountSection() {
    item {
        Text(
            "TUIT LMS · ${uz.tuit.lms.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Значок настроек в заголовке профиля. */
@Composable
fun SettingsAction() {
    val nav = LocalNav.current
    androidx.compose.material3.FilledTonalIconButton(
        onClick = { nav.push(Route.Settings) },
        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(containerColor = uz.tuit.lms.ui.components.cardColor()),
    ) { Icon(Icons.Rounded.Settings, LocalTr.current("app_settings")) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordSheet(open: Boolean, onClose: () -> Unit) {
    if (!open) return
    val t = LocalTr.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("change_password"), style = MaterialTheme.typography.titleLarge)
            listOf(Triple(t("old_password"), old) { v: String -> old = v }, Triple(t("new_password"), new) { v: String -> new = v }, Triple(t("confirm_password"), confirm) { v: String -> confirm = v })
                .forEach { (label, v, set) ->
                    OutlinedTextField(
                        value = v, onValueChange = set, label = { Text(label) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = { Icon(Icons.Rounded.Key, null) },
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    if (new != confirm) { error = t("password_mismatch"); return@Button }
                    busy = true
                    error = null
                    scope.launch {
                        val ok = runCatching { Repo.changePassword(old, new, confirm) }.getOrDefault(false)
                        busy = false
                        if (ok) { snack.show(t("password_changed")); onClose() } else error = t("password_failed")
                    }
                },
                enabled = !busy && old.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(t("save"))
            }
        }
    }
}
