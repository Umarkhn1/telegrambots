package uz.tuit.lms.data

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * Словари: web.json — тексты мини-приложения (генерируются tools/gen-i18n.cjs из webapp),
 * extra.json — то, что есть только в Android-приложении (вход, виджеты, уведомления).
 * Формы для чисел — через «|», как в мини-приложении.
 */
object I18n {
    val LANGS = listOf("ru", "uz_lat", "uz_cyr", "en")

    private val dicts = HashMap<String, Tr>()

    fun init(context: Context) {
        val web = Json.parseToJsonElement(context.assets.open("i18n/web.json").bufferedReader().use { it.readText() }).jsonObject
        val extra = Json.parseToJsonElement(context.assets.open("i18n/extra.json").bufferedReader().use { it.readText() }).jsonObject
        for (lang in LANGS) {
            val w = web[lang]!!.jsonObject
            fun map(o: JsonObject?) = o?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            fun list(k: String) = w[k]!!.jsonArray.map { it.jsonPrimitive.content }
            dicts[lang] = Tr(
                lang = lang,
                main = map(w["strings"]?.jsonObject) + map(extra[lang]?.jsonObject),
                teacher = map(w["teacher"]?.jsonObject),
                months = list("months"),
                monthsShort = list("monthsShort"),
                weekdays = list("weekdays"),
                weekdaysShort = list("weekdaysShort"),
                name = w["name"]!!.jsonPrimitive.content,
            )
        }
        val ru = dicts["ru"]!!
        for (lang in LANGS) dicts[lang]!!.fallback = ru
    }

    fun of(lang: String): Tr = dicts[lang] ?: dicts["ru"]!!
    fun current(): Tr = of(Prefs.lang.value)
    fun langName(lang: String) = of(lang).name
}

class Tr(
    val lang: String,
    private val main: Map<String, String>,
    private val teacher: Map<String, String>,
    val months: List<String>,
    val monthsShort: List<String>,
    /** Понедельник первым. */
    val weekdays: List<String>,
    val weekdaysShort: List<String>,
    val name: String,
) {
    internal var fallback: Tr? = null

    /** Основной словарь (студент, общие тексты, Android). */
    operator fun invoke(key: String, vararg vars: Pair<String, Any?>): String =
        format(main[key] ?: fallback?.main?.get(key) ?: key, vars)

    /** Словарь кабинета преподавателя. */
    fun tt(key: String, vararg vars: Pair<String, Any?>): String =
        format(teacher[key] ?: fallback?.teacher?.get(key) ?: main[key] ?: key, vars)

    private fun format(raw: String, vars: Array<out Pair<String, Any?>>): String {
        var s = raw
        if (s.contains('|')) {
            val forms = s.split('|')
            val n = (vars.firstOrNull { it.first == "n" }?.second as? Number)?.toInt() ?: 0
            s = when (lang) {
                "ru" -> ruPlural(n, forms)
                "en" -> forms.getOrElse(if (n == 1) 0 else 1) { forms[0] }
                else -> forms[0]
            }
        }
        for ((k, v) in vars) s = s.replace("{$k}", v.toString())
        return s
    }

    private fun ruPlural(n: Int, f: List<String>): String {
        val a = kotlin.math.abs(n) % 100
        val b = a % 10
        return when {
            a in 11..19 -> f.getOrElse(2) { f.last() }
            b in 2..4 -> f.getOrElse(1) { f.last() }
            b == 1 -> f[0]
            else -> f.getOrElse(2) { f.last() }
        }
    }

    /** «5 марта», «5-mart», «March 5». */
    fun dayMonth(d: LocalDate, short: Boolean = false): String {
        val m = (if (short) monthsShort else months)[d.monthValue - 1]
        return when (lang) {
            "en" -> "$m ${d.dayOfMonth}"
            "ru" -> "${d.dayOfMonth} $m"
            else -> "${d.dayOfMonth}-$m"
        }
    }

    fun weekday(d: LocalDate) = weekdays[d.dayOfWeek.value - 1]
    fun weekdayShort(d: LocalDate) = weekdaysShort[d.dayOfWeek.value - 1]
}
