package uz.tuit.lms.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.security.SecureRandom
import java.util.Base64

/** Настройки приложения. Всё, что влияет на интерфейс, ещё и лежит в StateFlow — экраны перерисуются сами. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("lms", Context.MODE_PRIVATE)
        _lang.value = sp.getString("lang", null) ?: guessLang()
        _theme.value = sp.getString("theme", "system") ?: "system"
        _dynamic.value = sp.getBoolean("dynamic", false)
        _semester.value = sp.getInt("semester", 0)
        _tutorMode.value = sp.getBoolean("tutor_mode", false)
    }

    private fun guessLang(): String {
        val l = java.util.Locale.getDefault()
        return when (l.language) {
            "ru", "be", "kk", "ky", "tg" -> "ru"
            "uz" -> if (l.script.equals("Cyrl", true)) "uz_cyr" else "uz_lat"
            "en" -> "en"
            else -> "uz_lat"
        }
    }

    /**
     * Ключ шифрования токена OneID на этом устройстве. Боту его задаёт токен бота,
     * здесь — случайные байты, которые не покидают телефон.
     */
    val installSecret: String
        get() {
            sp.getString("secret", null)?.let { return it }
            val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val s = Base64.getEncoder().encodeToString(b)
            sp.edit { putString("secret", s) }
            return s
        }

    private val _lang = MutableStateFlow("ru")
    val lang: StateFlow<String> = _lang
    fun setLang(v: String) { sp.edit { putString("lang", v) }; _lang.value = v }

    private val _theme = MutableStateFlow("system")
    val theme: StateFlow<String> = _theme
    fun setTheme(v: String) { sp.edit { putString("theme", v) }; _theme.value = v }

    private val _dynamic = MutableStateFlow(false)
    val dynamic: StateFlow<Boolean> = _dynamic
    fun setDynamic(v: Boolean) { sp.edit { putBoolean("dynamic", v) }; _dynamic.value = v }

    private val _semester = MutableStateFlow(0)
    val semester: StateFlow<Int> = _semester
    fun setSemester(v: Int) { sp.edit { putInt("semester", v) }; _semester.value = v }

    /** Режим тьютора у преподавателя с группой: своя главная с группами и студентами. */
    private val _tutorMode = MutableStateFlow(false)
    val tutorMode: StateFlow<Boolean> = _tutorMode
    fun setTutorMode(v: Boolean) { sp.edit { putBoolean("tutor_mode", v) }; _tutorMode.value = v }

    /** Был вход: без сети показываем сохранённые данные, а не экран входа. */
    var loggedIn: Boolean
        get() = sp.getBoolean("logged_in", false)
        set(v) = sp.edit { putBoolean("logged_in", v) }

    var teacher: Boolean
        get() = sp.getBoolean("teacher", false)
        set(v) = sp.edit { putBoolean("teacher", v) }

    var tutor: Boolean
        get() = sp.getBoolean("tutor", false)
        set(v) = sp.edit { putBoolean("tutor", v) }

    var currentSemester: Int
        get() = sp.getInt("current_semester", 0)
        set(v) = sp.edit { putInt("current_semester", v) }

    var displayName: String
        get() = sp.getString("display_name", "") ?: ""
        set(v) = sp.edit { putString("display_name", v) }

    var semesters: List<SemesterUi>
        get() = sp.getString("semesters", null)?.let {
            runCatching { Json.decodeFromString(ListSerializer(SemesterUi.serializer()), it) }.getOrNull()
        } ?: emptyList()
        set(v) = sp.edit { putString("semesters", Json.encodeToString(ListSerializer(SemesterUi.serializer()), v)) }

    /** Семестр экранов: выбранный пользователем, иначе текущий из LMS. */
    fun semesterId(): Int {
        val s = semester.value
        val list = semesters
        if (s > 0 && (list.isEmpty() || list.any { it.id == s })) return s
        return currentSemester.takeIf { it > 0 } ?: list.firstOrNull()?.id ?: 0
    }

    // ── Уведомления ──
    fun flag(key: String, def: Boolean = true) = sp.getBoolean("n_$key", def)
    fun setFlag(key: String, v: Boolean) = sp.edit { putBoolean("n_$key", v) }
    fun int(key: String, def: Int) = sp.getInt("i_$key", def)
    fun setInt(key: String, v: Int) = sp.edit { putInt("i_$key", v) }
    fun long(key: String, def: Long = 0) = sp.getLong("l_$key", def)
    fun setLong(key: String, v: Long) = sp.edit { putLong("l_$key", v) }
    fun str(key: String): String? = sp.getString("s_$key", null)
    fun setStr(key: String, v: String?) = sp.edit { if (v == null) remove("s_$key") else putString("s_$key", v) }

    /** Стереть всё, что связано с аккаунтом (при выходе). Язык и тема остаются. */
    fun clearAccount() {
        val keep = setOf("lang", "theme", "dynamic", "secret")
        sp.edit {
            for (k in sp.all.keys) if (k !in keep && !k.startsWith("n_") && !k.startsWith("i_")) remove(k)
        }
        _semester.value = 0
        _tutorMode.value = false
    }
}
