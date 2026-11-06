package uz.tuit.lms.data

import uz.tuit.lmsbot.config.AppConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Все даты LMS — по Ташкенту; телефон тоже почти всегда в этом поясе, но считаем явно. */
val ZONE: ZoneId = AppConfig.LMS_ZONE

fun today(): LocalDate = LocalDate.now(ZONE)
fun Long.toLocal(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZONE)
fun Long.hhmm(): String = toLocal().format(DateTimeFormatter.ofPattern("HH:mm"))

/** Сколько осталось: «45 мин», «5 ч 20 мин», «3 дн 4 ч». */
fun duration(ms: Long, t: Tr): String {
    val min = maxOf(0L, Math.round(ms / 60000.0))
    if (min < 60) return "$min ${t("u_min")}"
    val h = min / 60
    if (h < 24) {
        val m = min % 60
        return if (m > 0) "$h ${t("u_h")} $m ${t("u_min")}" else "$h ${t("u_h")}"
    }
    val d = h / 24
    val rh = h % 24
    return if (rh > 0) "$d ${t("u_d")} $rh ${t("u_h")}" else "$d ${t("u_d")}"
}

/** «24 мар, 23:59». */
fun deadlineLabel(ts: Long, t: Tr): String {
    val d = ts.toLocal()
    return "${t.dayMonth(d.toLocalDate(), short = true)}, ${ts.hhmm()}"
}

enum class Urgency { DANGER, WARNING, ACCENT, NEUTRAL }

fun urgency(ts: Long?, now: Long = System.currentTimeMillis()): Urgency {
    if (ts == null || ts <= 0) return Urgency.NEUTRAL
    val left = ts - now
    return when {
        left < 0 -> Urgency.NEUTRAL
        left < 24 * 3600_000L -> Urgency.DANGER
        left < 3 * 24 * 3600_000L -> Urgency.WARNING
        else -> Urgency.ACCENT
    }
}

fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

fun roman(n: Int): String {
    var x = n
    val map = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    val sb = StringBuilder()
    for ((v, s) in map) while (x >= v) { sb.append(s); x -= v }
    return sb.toString()
}

/** Число из строки LMS: «4,5» → 4.5; null, если числа нет. */
fun num(s: String?): Double? = s?.replace(',', '.')?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }

fun fmtNum(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

fun money(v: Long?): String {
    if (v == null) return "—"
    val s = v.toString()
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0 && c != '-') sb.append(' ')
        sb.append(c)
    }
    return sb.toString()
}

/* ─── GPA ─── */

/** В курсе два семестра. */
fun courseOfSemester(semester: Int) = (semester + 1) / 2

/** Текущий курс: из профиля LMS, а если там пусто — по учебному плану. */
fun currentCourse(profileCourse: String?, subjects: List<PlanSubjectUi>): Int {
    Regex("\\d+").find(profileCourse ?: "")?.value?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    val graded = subjects.filter { it.grade != null }.map { it.semester }
    return courseOfSemester((graded + currentPlanSemester(subjects)).maxOrNull() ?: 1)
}

/** Дата из плана предмета (ГГГГ-ММ-ДД или ДД.ММ.ГГГГ) → дата, как в расписании. */
fun planDate(text: String?): LocalDate? {
    if (text.isNullOrBlank()) return null
    Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(text)?.let { m ->
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }
    val m = Regex("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})").find(text) ?: return null
    return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
}

/** Вкладка плана → вид занятия: то же правило, что SemesterSchedule.tabKind на сервере. */
fun tabKind(key: String): String {
    val k = key.lowercase()
    if ("lec" in k || "лек" in k || "ruza" in k || "руза" in k) return "lecture"
    return if ("lab" in k) "lab" else "practice"
}

/** Текущий семестр — первый, где есть предмет без оценки (как в боте). */
fun currentPlanSemester(subjects: List<PlanSubjectUi>): Int {
    val sems = subjects.map { it.semester }.distinct().sorted()
    for (s in sems) if (subjects.any { it.semester == s && it.grade == null }) return s
    return sems.lastOrNull() ?: 0
}

data class Gpa(val value: Double?, val credits: Int)

fun gpa(subjects: List<PlanSubjectUi>, withZero: Boolean): Gpa {
    val current = currentPlanSemester(subjects)
    var credits = 0
    var points = 0.0
    for (s in subjects) {
        val g = when {
            s.grade != null -> s.grade.toDouble()
            withZero && s.semester == current -> 0.0
            else -> continue
        }
        credits += s.credits
        // Двойка — незачёт: в сумму идёт нулём, но её кредиты остаются в знаменателе (как StudyPlanSubject.gradePoints).
        points += (if (g >= 3) g else 0.0) * s.credits
    }
    return Gpa(if (credits > 0) points / credits else null, credits)
}

fun gpaComment(v: Double, t: Tr): String = when {
    v >= 4.5 -> t("gpa_5")
    v >= 4 -> t("gpa_4")
    v >= 3.5 -> t("gpa_35")
    v >= 3 -> t("gpa_3")
    else -> t("gpa_2")
}

/** Название вкладки плана занятий: ключи LMS — lecture, practice, laboratory… */
fun tabLabel(key: String, t: Tr): String {
    val k = key.lowercase()
    return when {
        "lec" in k || "ruza" in k -> t("lecture")
        "lab" in k -> t("laboratory")
        "sem" in k -> t("seminar")
        "prac" in k || "amal" in k -> t("practice")
        else -> key.replaceFirstChar { it.titlecase() }
    }
}

/** «17-09-2026» из LMS → дата. */
fun parseDmy(s: String?): LocalDate? {
    val m = Regex("(\\d{2})-(\\d{2})-(\\d{4})").find(s ?: return null) ?: return null
    return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
}

fun greetingKey(hour: Int) = when {
    hour < 5 -> "greet_night"
    hour < 12 -> "greet_morning"
    hour < 18 -> "greet_day"
    else -> "greet_evening"
}

/** Что показывать «на сегодня»: пока сегодняшние пары не кончились — их, иначе завтрашние. */
data class DayPick(val key: String, val list: List<LessonUi>, val over: Boolean, val date: LocalDate)

fun pickDay(lessons: List<LessonUi>, now: Long = System.currentTimeMillis()): DayPick {
    val today = now.toLocal().toLocalDate()
    val tomorrow = today.plusDays(1)
    val todays = lessons.filter { it.date == today.toString() }
    if (todays.any { it.endTs > now }) return DayPick("classes_today", todays, false, today)
    val tomorrows = lessons.filter { it.date == tomorrow.toString() }
    if (tomorrows.isNotEmpty()) return DayPick("classes_tomorrow", tomorrows, todays.isNotEmpty(), tomorrow)
    return DayPick("classes_today", emptyList(), todays.isNotEmpty(), today)
}

/** Ближайшая пара: идущая сейчас или следующая (в пределах недели). */
fun nextLesson(lessons: List<LessonUi>, now: Long = System.currentTimeMillis()): LessonUi? =
    lessons.filter { it.endTs > now && it.ts - now < 7L * 24 * 3600_000 }.minByOrNull { it.ts }
