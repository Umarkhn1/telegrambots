package uz.tuit.lms.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import uz.tuit.lms.data.CourseUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo

enum class Tab { HOME, COURSES, SCHEDULE, GRADES, GRADING, PROFILE }

val STUDENT_TABS = listOf(Tab.HOME, Tab.COURSES, Tab.SCHEDULE, Tab.GRADES, Tab.PROFILE)
/** У преподавателя вместо «Оценок» — проверка работ. */
val TEACHER_TABS = listOf(Tab.HOME, Tab.COURSES, Tab.SCHEDULE, Tab.GRADING, Tab.PROFILE)
/** Режим тьютора: группы, расписание, профиль. */
val TUTOR_TABS = listOf(Tab.HOME, Tab.SCHEDULE, Tab.PROFILE)

/** Экраны поверх вкладки; закрываются «Назад» (в том числе жестом с предпросмотром). */
sealed interface Route {
    data class Course(val course: CourseUi, val tab: Int = 0, val lesson: uz.tuit.lms.data.LessonUi? = null) : Route
    data object Deadlines : Route
    data object Settings : Route
    data object Admin : Route
    data class TCourse(val id: Int, val subject: String, val type: String, val students: Int, val stream: String?, val column: Int? = null) : Route
    /** Перекличка: с датой пары (ISO) — этого дня, без даты — как в боте: сегодняшняя или последняя неотмеченная. */
    data class RollCall(val courseId: Int, val title: String?, val date: String?) : Route
    data object Appeals : Route
    data object Materials : Route
    data class Material(val id: Int, val subject: String, val language: String) : Route
    data object TFinals : Route
    data object Tutor : Route
    data class TGroup(val id: Int, val name: String, val speciality: String?) : Route
    data class TStudent(val id: Int, val title: String) : Route
}

@Stable
class Nav {
    var tab by mutableStateOf(Tab.HOME)
        private set
    var prevTab by mutableStateOf(Tab.HOME)
        private set
    val stack = mutableStateListOf<Route>()

    fun select(t: Tab) {
        if (t == tab && stack.isEmpty()) return
        prevTab = tab
        stack.clear()
        tab = t
    }

    fun push(r: Route) { stack.add(r) }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset() {
        stack.clear()
        tab = Tab.HOME
    }

    /**
     * Ссылки из уведомлений, виджетов и ярлыков: «schedule», «deadlines», «grades», «grading»,
     * «course:<id>», «att:<id>», «rollcall:<courseId>:<yyyy-MM-dd>», «tcourse:<id>:<column>».
     */
    fun open(link: String?) {
        if (link.isNullOrBlank()) return
        val parts = link.split(':')
        val teacher = Prefs.teacher
        stack.clear()
        when (parts[0]) {
            "home" -> tab = Tab.HOME
            "update" -> { Prefs.setInt("update_snoozed", 0); tab = Tab.HOME }
            "schedule" -> tab = Tab.SCHEDULE
            "courses" -> tab = Tab.COURSES
            "grades" -> tab = if (teacher) Tab.GRADING else Tab.GRADES
            "grading" -> tab = Tab.GRADING
            "profile" -> tab = Tab.PROFILE
            "deadlines" -> { tab = Tab.HOME; push(Route.Deadlines) }
            "course", "att" -> {
                tab = Tab.COURSES
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                val name = parts.drop(2).joinToString(":").ifBlank { null }
                val course = findCourse(id) ?: CourseUi(id, name ?: "", 0, false, emptyList())
                push(Route.Course(course, tab = if (parts[0] == "att") 1 else 0))
            }
            "rollcall" -> {
                tab = Tab.SCHEDULE
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                push(Route.RollCall(id, null, parts.getOrNull(2)))
            }
            "tcourse" -> {
                tab = Tab.GRADING
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                val col = parts.getOrNull(2)?.toIntOrNull()
                val g = Repo.gradingSummary().firstOrNull { it.courseId == id }
                push(Route.TCourse(id, g?.subject ?: "", "", 0, g?.stream, col))
            }
        }
    }

    /** Тап по паре у студента: предмет с этим занятием (тема, аудитория, файлы из плана). */
    fun openLesson(l: uz.tuit.lms.data.LessonUi) {
        val id = l.courseId ?: return
        val course = findCourse(id) ?: CourseUi(id, l.subject, 0, false,
            if (l.teacher.isNotBlank()) listOf(uz.tuit.lms.data.TeacherRef(l.teacher, l.stream ?: "")) else emptyList())
        push(Route.Course(course, tab = 2, lesson = l))
    }

    private fun findCourse(id: Int): CourseUi? =
        Repo.peek(Repo.courses(Prefs.semesterId()))?.value?.firstOrNull { it.id == id }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no Nav") }
