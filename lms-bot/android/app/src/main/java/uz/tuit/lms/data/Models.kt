package uz.tuit.lms.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KJson

val Json = KJson {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/*
 * Данные экранов студента — те же поля, что отдаёт мини-приложению WebApi (webapp/src/lib/types.ts).
 * Сериализуются: последние ответы лежат на диске, поэтому приложение и виджеты работают без сети.
 */

@Serializable
data class SemesterUi(val id: Int, val name: String, val year: String? = null)

@Serializable
data class TeacherRef(val name: String, val stream: String)

@Serializable
data class CourseUi(
    val id: Int,
    val subject: String,
    val attendance: Int,
    val failed: Boolean,
    val teachers: List<TeacherRef>,
)

@Serializable
data class FileRef(val url: String, val name: String)

@Serializable
data class CriterionUi(val name: String, val points: String?)

enum class ActStatus { UPLOADED, GRADED, OPEN, MISSED, UNKNOWN }

@Serializable
data class ActivityUi(
    val index: Int,
    val type: String?,
    val lecture: Boolean,
    val teacher: String?,
    val task: String,
    val deadline: String?,
    val deadlineTs: Long?,
    val earned: String?,
    val max: String?,
    val criteria: String?,
    val criteriaItems: List<CriterionUi>,
    val sample: FileRef?,
    val uploaded: FileRef?,
    val activityId: String?,
    val graded: Boolean,
    val courseId: Int = 0,
    val course: String? = null,
) {
    /** Статус считается на момент показа: сохранённое задание могло закрыться, пока телефон был без сети. */
    fun status(now: Long = System.currentTimeMillis()): ActStatus {
        val dl = deadlineTs ?: 0
        val open = dl > 0 && dl > now
        return when {
            uploaded != null -> ActStatus.UPLOADED
            graded -> ActStatus.GRADED
            open -> ActStatus.OPEN
            dl > 0 -> ActStatus.MISSED
            else -> ActStatus.UNKNOWN
        }
    }

    fun canUpload(now: Long = System.currentTimeMillis()) =
        !activityId.isNullOrBlank() && (deadlineTs ?: 0) > now

    val submitted get() = uploaded != null || graded
    val key get() = "$courseId:$index:${task.hashCode()}"
}

@Serializable
data class ActivitiesUi(
    val earned: String?,
    val maxScore: String?,
    val progress: String?,
    val grade: String?,
    val activities: List<ActivityUi>,
)

@Serializable
data class AbsenceUi(val date: String, val type: String?, val lecture: Boolean, val topic: String?, val excused: Boolean)

@Serializable
data class AttendanceUi(val missed: Int, val items: List<AbsenceUi>)

@Serializable
data class CalendarFileUi(val name: String, val url: String, val type: String)

@Serializable
data class CalendarEntryUi(val number: Int, val topic: String, val date: String?, val files: List<CalendarFileUi>)

@Serializable
data class CalendarTabUi(val key: String, val entries: List<CalendarEntryUi>)

@Serializable
data class LessonUi(
    /** ISO yyyy-MM-dd */
    val date: String,
    val time: String,
    val ts: Long,
    val subject: String,
    /** lecture | practice | lab */
    val kind: String,
    val room: String,
    val teacher: String,
    val stream: String?,
    val topic: String?,
    val courseId: Int?,
    val last: Boolean,
) {
    val endTs get() = ts + PAIR_MS
    fun live(now: Long) = now in ts until endTs
    fun done(now: Long) = now >= endTs

    companion object {
        /** Пара в ТУИТ идёт 80 минут. */
        const val PAIR_MS = 80L * 60 * 1000
    }
}

@Serializable
data class ScheduleUi(
    val start: String? = null,
    val end: String? = null,
    val weeks: Int = 0,
    val currentWeek: Int = 0,
    val lessons: List<LessonUi> = emptyList(),
)

@Serializable
data class PlanSubjectUi(val name: String, val credits: Int, val grade: Int?, val semester: Int)

@Serializable
data class FinalUi(
    val subject: String?,
    val stream: String?,
    val date: String?,
    val from: String?,
    val room: String?,
    val grade: String?,
)

@Serializable
data class ProfileUi(
    val fullName: String? = null,
    val birthDate: String? = null,
    val gender: String? = null,
    val recordBook: String? = null,
    val address: String? = null,
    val direction: String? = null,
    val language: String? = null,
    val degree: String? = null,
    val studyType: String? = null,
    val course: String? = null,
    val group: String? = null,
    val curator: String? = null,
    val scholarship: String? = null,
)

@Serializable
data class ContractUi(
    val found: Boolean,
    val notice: String? = null,
    val year: String? = null,
    val total: Long? = null,
    val paid: Long? = null,
    val debt: Long? = null,
)

/** Кратко о проверке работ преподавателя — для главной, виджетов и напоминаний. */
@Serializable
data class GradingUi(
    val courseId: Int?,
    val subject: String?,
    val activity: String?,
    val stream: String?,
    val column: Int,
    val activityId: Int?,
    val studentDeadline: String?,
    val teacherDeadline: String?,
    val studentTs: Long,
    val teacherTs: Long?,
    val submitted: Int,
    val graded: Int,
    val pending: Int,
)

/** Фото профиля храним байтами — data URL из LMS. */
@Serializable
data class PhotoUi(val dataUrl: String?)
