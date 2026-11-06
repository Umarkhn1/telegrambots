package uz.tuit.lms.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uz.tuit.lms.data.ApiException
import uz.tuit.lms.data.Files
import java.io.File

fun guessFileType(name: String): String {
    val n = name.lowercase()
    return when {
        n.endsWith(".pdf") -> "pdf"
        Regex("\\.(docx?|rtf|txt|odt)$").containsMatchIn(n) -> "doc"
        Regex("\\.pptx?$").containsMatchIn(n) -> "ppt"
        Regex("\\.(mp4|avi|mov|mkv|webm)$").containsMatchIn(n) -> "video"
        Regex("\\.(jpe?g|png|gif|webp|heic)$").containsMatchIn(n) -> "image"
        Regex("\\.(zip|rar|7z)$").containsMatchIn(n) -> "zip"
        else -> "file"
    }
}

fun fileIcon(type: String): ImageVector = when (type) {
    "pdf" -> Icons.Rounded.PictureAsPdf
    "doc" -> Icons.Rounded.Description
    "ppt" -> Icons.Rounded.Slideshow
    "video" -> Icons.Rounded.VideoFile
    "url", "meeting" -> Icons.Rounded.Link
    "image" -> Icons.Rounded.Image
    "zip" -> Icons.Rounded.FolderZip
    else -> Icons.Rounded.InsertDriveFile
}

/**
 * Файл из LMS. Тап — скачать и открыть; справа — сохранить в «Загрузки» и поделиться.
 * Внешние ссылки (видео, встречи) открываются в браузере.
 */
@Composable
fun FileRow(
    name: String,
    url: String,
    shape: Shape,
    type: String? = null,
    label: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    trailingExtra: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val t = LocalTr.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val kind = type ?: guessFileType(name)
    val external = kind == "url" || kind == "meeting"

    fun fail(e: Throwable) {
        val code = (e as? ApiException)?.code
        snack.show(
            when (code) {
                "file_not_found" -> t("file_not_found")
                "file_too_large" -> t("file_too_large")
                else -> t("download_failed")
            },
            error = true,
        )
    }

    fun withFile(what: String, block: suspend (File) -> Unit) {
        if (busy != null) return
        busy = what
        scope.launch {
            try {
                block(Files.fetch(context, url, name))
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = null
            }
        }
    }

    ListRow(
        shape = shape,
        color = color,
        onClick = {
            if (external) Files.openUrl(context, url)
            else withFile("open") { if (!Files.open(context, it)) snack.show(t("no_app_for_file"), error = true) }
        },
        leading = { IconTile(fileIcon(kind), if (external) Tone.VIOLET else Tone.ACCENT, size = 40.dp, iconSize = 20.dp) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (external) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.primary)
                } else if (busy != null) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    // Одна кнопка вместо двух: «Сохранить» и «Поделиться» — в меню.
                    var menu by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { androidx.compose.material3.Text(t("download")) },
                                leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                onClick = {
                                    menu = false
                                    withFile("save") { snack.show(if (Files.saveToDownloads(context, it)) t("saved_to_downloads") else t("download_failed")) }
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { androidx.compose.material3.Text(t("share")) },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                onClick = { menu = false; withFile("share") { Files.share(context, it) } },
                            )
                        }
                    }
                }
                trailingExtra?.invoke()
            }
        },
        chevron = false,
    ) {
        if (label != null) RowSub(label, maxLines = 1, modifier = Modifier)
        RowTitle(name, maxLines = 3)
    }
}
