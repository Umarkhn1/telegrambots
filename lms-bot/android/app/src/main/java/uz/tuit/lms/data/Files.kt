package uz.tuit.lms.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Файлы LMS на телефоне: скачать (той же сессией), открыть в подходящем приложении,
 * поделиться, сохранить в «Загрузки». Вместо «прислать в чат» из мини-приложения.
 */
object Files {
    private const val KEEP_MS = 60L * 60 * 1000

    private fun dir(context: Context) = File(context.cacheDir, "lms_files").apply { mkdirs() }

    fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_").trim().ifEmpty { "file" }

    /** Скачанный файл; повторное открытие в течение часа не качает заново. */
    suspend fun fetch(context: Context, url: String, name: String): File = withContext(Dispatchers.IO) {
        val folder = File(dir(context), Integer.toHexString(url.hashCode())).apply { mkdirs() }
        folder.listFiles()?.firstOrNull()?.let { if (System.currentTimeMillis() - it.lastModified() < KEEP_MS && it.length() > 0) return@withContext it }
        folder.listFiles()?.forEach { it.delete() }
        val dl = Repo.download(url, name)
        try {
            var fname = safeName(dl.filename() ?: name)
            if (!fname.contains('.') && name.contains('.')) fname = safeName(name)
            val target = File(folder, fname)
            dl.file().copyTo(target, overwrite = true)
            target
        } finally {
            dl.file().delete()
        }
    }

    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "rar" -> "application/vnd.rar"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }

    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** false — на телефоне нет приложения для такого файла. */
    fun open(context: Context, file: File): Boolean {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent.createChooser(i, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun share(context: Context, file: File) {
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime(file.name))
            .putExtra(Intent.EXTRA_STREAM, uri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(i, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Копия в общую папку «Загрузки/TUIT LMS». */
    suspend fun saveToDownloads(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime(file.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TUIT LMS")
                }
                val target = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                true
            } else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TUIT LMS").apply { mkdirs() }
                file.copyTo(File(folder, file.name), overwrite = true)
                true
            }
        }.getOrDefault(false)
    }

    fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    data class Picked(val file: File, val name: String, val size: Long)

    /** Файл, выбранный пользователем, — во временную копию, чтобы отправить в LMS. */
    suspend fun copyFromUri(context: Context, uri: Uri): Picked = withContext(Dispatchers.IO) {
        var name = "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        name = safeName(name)
        val tmp = File(context.cacheDir, "upload_" + System.currentTimeMillis() + "_" + name)
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        Picked(tmp, name, if (size >= 0) size else tmp.length())
    }
}
