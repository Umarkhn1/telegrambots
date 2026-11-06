package uz.tuit.lms.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/*
 * Админ-панель: список студентов, которые пользовались ботом, есть только на сервере бота.
 * Приложение спрашивает его по ключу, который бот выдаёт админам командой /adminapp
 * (код вида LMSADMIN:<адрес сервиса в base64url>.<ключ>).
 */

@Serializable
data class AdminStudent(
    val telegramId: Long = 0,
    val tgName: String? = null,
    val tgUsername: String? = null,
    val fullName: String? = null,
    val recordBook: String? = null,
    val group: String? = null,
    val direction: String? = null,
    val course: String? = null,
    val gender: String? = null,
    val birthDate: String? = null,
    val curator: String? = null,
    val studyType: String? = null,
    val language: String? = null,
    val gpa: Double? = null,
    val role: String? = null,
    val department: String? = null,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
)

@Serializable
data class AdminStats(
    val total: Int = 0,
    val active24h: Int = 0,
    val active7d: Int = 0,
    val avgGpa: Double? = null,
    val loggedIn: Int? = null,
    val sessionsAlive: Int? = null,
    val sessionsOnDisk: Int? = null,
)

@Serializable
data class AdminPage(
    val items: List<AdminStudent> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pages: Int = 1,
    val size: Int = 20,
    val stats: AdminStats = AdminStats(),
    val facets: Map<String, List<String>> = emptyMap(),
)

object AdminApi {
    /** Владелец бота: у этой зачётки пункт админ-панели виден сразу, ещё до ввода кода. */
    private val ADMIN_RECORD_BOOKS = setOf("32118-23")

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()

    data class Access(val baseUrl: String, val key: String)

    fun access(): Access? {
        val code = Prefs.str("admin_code") ?: return null
        return parse(code)
    }

    /** «LMSADMIN:<base64url(адрес)>.<ключ>» → адрес и ключ; null — код не тот. */
    fun parse(raw: String): Access? {
        val code = Regex("LMSADMIN:([A-Za-z0-9_-]+)\\.([0-9a-f]{16,})").find(raw.trim()) ?: return null
        val url = runCatching { String(Base64.getUrlDecoder().decode(code.groupValues[1])) }.getOrNull() ?: return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        return Access(if (url.endsWith("/")) url else "$url/", code.groupValues[2])
    }

    fun save(raw: String): Boolean {
        val a = parse(raw) ?: return false
        Prefs.setStr("admin_code", "LMSADMIN:" + Base64.getUrlEncoder().withoutPadding().encodeToString(a.baseUrl.toByteArray()) + "." + a.key)
        return true
    }

    fun forget() = Prefs.setStr("admin_code", null)

    /** Показывать ли пункт админ-панели: код уже введён или это зачётка владельца. */
    fun visible(): Boolean {
        if (access() != null) return true
        val rb = Repo.peek(Repo.profile())?.value?.recordBook?.trim()
        return rb != null && rb in ADMIN_RECORD_BOOKS
    }

    suspend fun students(params: Map<String, String>): AdminPage = withContext(Dispatchers.IO) {
        val a = access() ?: throw ApiException("admin_no_code")
        val url = (a.baseUrl + "api/app/admin/students").toHttpUrlOrNull()?.newBuilder() ?: throw ApiException("admin_bad_code")
        params.forEach { (k, v) -> if (v.isNotBlank()) url.addQueryParameter(k, v) }
        val req = Request.Builder().url(url.build()).header("X-Admin-Key", a.key).header("User-Agent", "TUIT-LMS-Android").build()
        try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 403 -> throw ApiException("admin_forbidden")
                    resp.code == 404 -> throw ApiException("admin_old_server")
                    !resp.isSuccessful -> throw LmsUnavailableException("admin_http_${resp.code}")
                }
                Json.decodeFromString(AdminPage.serializer(), resp.body?.string() ?: "{}")
            }
        } catch (e: java.io.IOException) {
            throw LmsUnavailableException("offline")
        }
    }
}
