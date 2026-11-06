package uz.tuit.lms.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import uz.tuit.lms.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Обновления без Google Play: новые версии лежат в релизах GitHub (тег android-<versionCode>,
 * файл .apk). Приложение само находит новую версию, качает APK и открывает системную установку —
 * Android ставит её поверх, данные и вход сохраняются (подпись у всех версий одна).
 */
object Updater {
    private const val REPO = "Umarkhn1/telegrambots"
    private const val CHECK_EVERY_MS = 6L * 3600 * 1000

    data class Update(val code: Int, val name: String, val notes: String, val apkUrl: String, val size: Long)

    private val _available = MutableStateFlow<Update?>(null)
    val available: StateFlow<Update?> = _available

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    /** Проверить (не чаще раза в 6 часов, если не force). null — новее нет. */
    suspend fun check(force: Boolean = false): Update? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.long("update_checked") < CHECK_EVERY_MS) {
            return@withContext _available.value
        }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TUIT-LMS-Android")
            .build()
        val found = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val arr = Json.parseToJsonElement(resp.body?.string() ?: "[]").jsonArray
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
                    val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val code = Regex("^android-(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    val asset = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                        ?: return@mapNotNull null
                    Update(
                        code = code,
                        name = o["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: tag,
                        notes = o["body"]?.jsonPrimitive?.content ?: "",
                        apkUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                    )
                }.maxByOrNull { it.code }
            }
        }.getOrNull()
        Prefs.setLong("update_checked", now)
        val newer = found?.takeIf { it.code > BuildConfig.VERSION_CODE }
        _available.value = newer
        newer
    }

    /** Пользователь нажал «Позже» — не показывать окно для этой версии до следующей. */
    fun snooze(u: Update) = Prefs.setInt("update_snoozed", u.code)
    fun snoozed(u: Update) = Prefs.int("update_snoozed", 0) >= u.code

    /** Скачать APK с прогрессом 0..1. */
    suspend fun download(context: Context, u: Update, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "TUIT-LMS-${u.code}.apk")
        val req = Request.Builder().url(u.apkUrl).header("User-Agent", "TUIT-LMS-Android").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("empty")
            val total = body.contentLength().takeIf { it > 0 } ?: u.size
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** Можно ли приложению ставить APK (на Android 8+ разрешение «Установка неизвестных приложений»). */
    fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
