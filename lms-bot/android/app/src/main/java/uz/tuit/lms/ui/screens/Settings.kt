package uz.tuit.lms.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import uz.tuit.lms.data.Repo
import uz.tuit.lms.ui.components.rememberConfirm
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import uz.tuit.lms.data.Files
import uz.tuit.lms.data.I18n
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.notify.Reminders
import uz.tuit.lms.notify.SyncWorker
import uz.tuit.lms.ui.components.ChipRow
import uz.tuit.lms.ui.components.DetailScreen
import uz.tuit.lms.ui.components.IconTile
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListRow
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Opt
import uz.tuit.lms.ui.components.RowSub
import uz.tuit.lms.ui.components.RowTitle
import uz.tuit.lms.ui.components.SectionHeader
import uz.tuit.lms.ui.components.Segmented
import uz.tuit.lms.ui.components.Tone
import uz.tuit.lms.widget.Widgets

private data class Toggle(val key: String, val icon: ImageVector, val tone: Tone, val title: String, val sub: String?)

@Composable
fun SettingsScreen(teacher: Boolean) {
    val t = LocalTr.current
    val context = LocalContext.current
    val lang by Prefs.lang.collectAsState()
    val theme by Prefs.theme.collectAsState()
    val dynamic by Prefs.dynamic.collectAsState()
    var langOpen by remember { mutableStateOf(false) }
    var pwOpen by remember { mutableStateOf(false) }
    // Счётчик правок: переключатели читают Prefs напрямую, а перерисовку вызывает он.
    var rev by remember { mutableIntStateOf(0) }

    DetailScreen(title = t("app_settings")) {
        item { SectionHeader(t("appearance")) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ListRow(uz.tuit.lms.ui.components.groupShape(0, 3), onClick = { langOpen = true },
                    leading = { IconTile(Icons.Rounded.Language, Tone.ACCENT, 38.dp, 20.dp) },
                    trailing = { Text(I18n.langName(lang), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }) {
                    RowTitle(t("language"))
                }
                ListRow(uz.tuit.lms.ui.components.groupShape(1, 3), leading = { IconTile(if (theme == "dark") Icons.Rounded.DarkMode else Icons.Rounded.WbSunny, Tone.VIOLET, 38.dp, 20.dp) }) {
                    RowTitle(t("theme"))
                    Spacer(Modifier.height(10.dp))
                    Segmented(listOf(Opt("system", t("theme_system")), Opt("light", t("theme_light")), Opt("dark", t("theme_dark"))), theme, Prefs::setTheme)
                }
                ListRow(uz.tuit.lms.ui.components.groupShape(2, 3),
                    onClick = if (Build.VERSION.SDK_INT >= 31) ({ Prefs.setDynamic(!dynamic) }) else null,
                    leading = { IconTile(Icons.Rounded.ColorLens, Tone.SUCCESS, 38.dp, 20.dp) },
                    trailing = { Switch(dynamic, onCheckedChange = Prefs::setDynamic, enabled = Build.VERSION.SDK_INT >= 31) }, chevron = false) {
                    RowTitle(t("dynamic_color"))
                    RowSub(if (Build.VERSION.SDK_INT >= 31) t("dynamic_color_sub") else t("dynamic_color_na"))
                }
            }
        }

        item { SectionHeader(t("notifications")) }
        val toggles = buildList {
            add(Toggle("lessons", Icons.Rounded.Event, Tone.ACCENT, t("n_lessons"), t("n_lessons_sub")))
            add(Toggle("digest", Icons.Rounded.WbSunny, Tone.WARNING, t("n_digest"), t("n_digest_sub")))
            if (!teacher) {
                add(Toggle("deadlines", Icons.Rounded.Assignment, Tone.DANGER, t("n_deadlines"), t("n_deadlines_sub")))
                add(Toggle("nb", Icons.Rounded.PersonOff, Tone.DANGER, t("n_nb"), t("n_nb_sub")))
                add(Toggle("grades", Icons.Rounded.EmojiEvents, Tone.SUCCESS, t("n_grades"), t("n_grades_sub")))
            } else {
                add(Toggle("grading", Icons.Rounded.FactCheck, Tone.WARNING, t("n_grading"), t("n_grading_sub")))
                add(Toggle("rollcall", Icons.Rounded.HowToReg, Tone.ACCENT, t("n_rollcall"), t("n_rollcall_sub")))
            }
        }
        item {
            @Suppress("UNUSED_EXPRESSION") rev
            ListGroup(toggles) { _, tg, shape ->
                val on = Prefs.flag(tg.key)
                ListRow(shape, onClick = { Prefs.setFlag(tg.key, !on); rev++; Reminders.reschedule(context) },
                    leading = { IconTile(tg.icon, tg.tone, 38.dp, 20.dp) },
                    trailing = { Switch(on, onCheckedChange = { Prefs.setFlag(tg.key, it); rev++; Reminders.reschedule(context) }) }, chevron = false) {
                    RowTitle(tg.title)
                    if (tg.sub != null) RowSub(tg.sub)
                    if (tg.key == "lessons" && on) {
                        Spacer(Modifier.height(10.dp))
                        val lead = Prefs.int("lesson_lead", 10)
                        ChipRow(listOf(5, 10, 15, 30).map { Opt(it, "$it ${t("u_min")}") }, lead, { Prefs.setInt("lesson_lead", it); rev++; Reminders.reschedule(context) })
                    }
                    if (tg.key == "deadlines" && on) {
                        Spacer(Modifier.height(10.dp))
                        val iv = Prefs.int("deadline_interval", 240)
                        ChipRow(listOf(60, 120, 240, 480).map { Opt(it, "${it / 60} ${t("u_h")}") }, iv, { Prefs.setInt("deadline_interval", it); rev++ })
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val exact = Reminders.canExact(context)
                val count = if (Build.VERSION.SDK_INT >= 31 && !exact) 2 else 1
                ListRow(uz.tuit.lms.ui.components.groupShape(0, count), onClick = {
                    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(i) }
                }, leading = { IconTile(Icons.Rounded.NotificationsActive, Tone.NEUTRAL, 38.dp, 20.dp) }) {
                    RowTitle(t("system_notifications"))
                    RowSub(t("system_notifications_sub"))
                }
                if (count == 2) {
                    ListRow(uz.tuit.lms.ui.components.groupShape(1, 2), onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName))) }
                    }, leading = { IconTile(Icons.Rounded.AlarmOn, Tone.WARNING, 38.dp, 20.dp) }) {
                        RowTitle(t("exact_alarms"))
                        RowSub(t("exact_alarms_sub"))
                    }
                }
            }
        }

        item { SectionHeader(t("widgets")) }
        item {
            val list = Widgets.catalog(teacher)
            ListGroup(list) { _, w, shape ->
                ListRow(shape, onClick = if (Widgets.canPin(context)) ({ Widgets.requestPin(context, w.receiver) }) else null,
                    leading = { IconTile(Icons.Rounded.Widgets, Tone.VIOLET, 38.dp, 20.dp) }) {
                    RowTitle(t(w.titleKey))
                    RowSub(t(w.subKey))
                }
            }
        }
        item {
            Text(t("widgets_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
        }

        item { SectionHeader(t("account")) }
        item {
            val confirm = rememberConfirm()
            val scope = rememberCoroutineScope()
            ListGroup(listOf(0, 1)) { i, _, shape ->
                if (i == 0) ListRow(shape, onClick = { pwOpen = true }, leading = { IconTile(Icons.Rounded.Key, Tone.WARNING, 38.dp, 20.dp) }) {
                    RowTitle(t("change_password"))
                } else ListRow(shape, onClick = { confirm.ask(t("logout_confirm"), t("logout"), danger = true) { scope.launch { Repo.logout() } } },
                    leading = { IconTile(Icons.AutoMirrored.Rounded.Logout, Tone.DANGER, 38.dp, 20.dp) }, chevron = false) {
                    RowTitle(t("logout"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item { SectionHeader(t("about")) }
        item {
            val scope = rememberCoroutineScope()
            val snack = uz.tuit.lms.ui.components.LocalSnack.current
            var checking by remember { mutableStateOf(false) }
            ListGroup(listOf(0, 1)) { i, _, shape ->
                if (i == 0) ListRow(shape, onClick = {
                    if (checking) return@ListRow
                    checking = true
                    scope.launch {
                        val u = uz.tuit.lms.data.Updater.check(force = true)
                        checking = false
                        if (u == null) snack.show(t("update_latest")) else Prefs.setInt("update_snoozed", 0)
                    }
                }, leading = { IconTile(Icons.Rounded.Info, Tone.ACCENT, 38.dp, 20.dp) },
                    trailing = { if (checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(t("update_check"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }) {
                    RowTitle("TUIT LMS ${uz.tuit.lms.BuildConfig.VERSION_NAME}")
                    RowSub(t("about_sub"))
                } else ListRow(shape, onClick = { Files.openUrl(context, "https://t.me/lmstgbot") }, leading = { IconTile(Icons.Rounded.Send, Tone.SUCCESS, 38.dp, 20.dp) }) {
                    RowTitle(t("open_bot"))
                    RowSub(t("author", "name" to "@umarkhodja_1"))
                }
            }
        }
    }

    PasswordSheet(pwOpen) { pwOpen = false }
    if (langOpen) {
        AlertDialog(
            onDismissRequest = { langOpen = false },
            title = { Text(t("language")) },
            text = {
                Column {
                    I18n.LANGS.forEach { l ->
                        Row(
                            Modifier.fillMaxWidth().selectable(l == lang, role = Role.RadioButton) { Prefs.setLang(l); langOpen = false; SyncWorker.runNow(context) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(l == lang, onClick = null)
                            Spacer(Modifier.width(14.dp))
                            Text(I18n.langName(l), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { langOpen = false }) { Text(t("close")) } },
        )
    }
}
