package uz.tuit.lms.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import uz.tuit.lms.BuildConfig
import uz.tuit.lms.data.Updater
import uz.tuit.lms.ui.components.LocalSnack
import uz.tuit.lms.ui.components.LocalTr

/** Окно «Доступно обновление»: проверка при запуске и возвращении в приложение, установка в один тап. */
@Composable
fun UpdateDialog() {
    val t = LocalTr.current
    val context = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val update by Updater.available.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { Updater.check() } }

    val u = update ?: return
    if (dismissed || Updater.snoozed(u)) return

    AlertDialog(
        onDismissRequest = { if (progress == null) { Updater.snooze(u); dismissed = true } },
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text(t("update_title")) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(t("update_text", "name" to u.name, "current" to BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                if (u.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(u.notes.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val p = progress
                if (p != null) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(enabled = progress == null, onClick = {
                if (!Updater.canInstall(context)) {
                    snack.show(t("update_allow"))
                    Updater.openInstallPermission(context)
                    return@Button
                }
                progress = 0f
                scope.launch {
                    try {
                        val apk = Updater.download(context, u) { progress = it }
                        Updater.install(context, apk)
                    } catch (e: Exception) {
                        snack.show(t("update_failed"), error = true)
                    } finally {
                        progress = null
                    }
                }
            }) { Text(if (progress != null) t("update_downloading") else t("update_now")) }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = { Updater.snooze(u); dismissed = true }) { Text(t("update_later")) }
        },
    )
}
