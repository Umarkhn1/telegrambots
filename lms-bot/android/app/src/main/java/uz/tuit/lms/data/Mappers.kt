package uz.tuit.lms.data

import uz.tuit.lmsbot.config.AppConfig
import uz.tuit.lmsbot.model.Activity
import uz.tuit.lmsbot.model.Course
import uz.tuit.lmsbot.service.SemesterSchedule
import uz.tuit.lmsbot.service.TeacherService
import uz.tuit.lmsbot.util.Criteria
import uz.tuit.lmsbot.util.LmsDates
import java.time.LocalDate
import java.util.Locale

/** Перевод моделей общего Java-слоя в данные экранов — как это делает WebApi для мини-приложения. */
internal object Mappers {

    fun course(c: Course): CourseUi {
        val names = c.teachers?.takeIf { it.isNotBlank() }?.split("###") ?: emptyList()
        val streams = c.streams?.takeIf { it.isNotBlank() }?.split("###") ?: emptyList()
        val teachers = names.mapIndexedNotNull { i, n ->
            if (n.isBlank()) null else TeacherRef(n.trim(), streams.getOrNull(i)?.trim() ?: "")
        }
        return CourseUi(c.id, c.subject ?: "", c.attendance, c.isFailed, teachers)
    }

    fun activity(a: Activity, index: Int, courseId: Int = 0, course: String? = null): ActivityUi {
        val dl = LmsDates.parseDeadline(a.deadline)
        val criteria = a.criteria?.split("\n")?.filter { it.isNotBlank() }?.map {
            val it2 = Criteria.parse(it)
            CriterionUi(it2.name(), it2.points())
        } ?: emptyList()
        return ActivityUi(
            index = index,
            type = a.type,
            lecture = isLecture(a.type),
            teacher = a.teacher,
            task = a.task ?: "",
            deadline = a.deadline,
            deadlineTs = dl.takeIf { it > 0 },
            earned = a.earnedScore,
            max = a.maxScore,
            criteria = a.criteria,
            criteriaItems = criteria,
            sample = a.sampleFileUrl?.takeIf { it.isNotBlank() }?.let { FileRef(it, a.sampleFileName.orFile()) },
            uploaded = a.uploadedFileUrl?.takeIf { it.isNotBlank() }?.let { FileRef(it, a.uploadedFileName.orFile()) },
            activityId = a.activityId,
            graded = isGraded(a.earnedScore),
            courseId = courseId,
            course = course,
        )
    }

    fun lesson(l: SemesterSchedule.Lesson) = LessonUi(
        date = l.date().toString(),
        time = l.time() ?: "",
        ts = l.ts(),
        subject = l.subject() ?: "",
        kind = l.kind() ?: SemesterSchedule.LECTURE,
        room = l.room() ?: "",
        teacher = l.teacher() ?: "",
        stream = l.stream(),
        topic = l.topic(),
        courseId = l.courseId(),
        last = l.last(),
    )

    fun schedule(r: SemesterSchedule.Result?): ScheduleUi {
        if (r == null) return ScheduleUi()
        return ScheduleUi(
            start = r.start().toString(),
            end = r.end().toString(),
            weeks = r.weekCount(),
            currentWeek = r.weekOf(LocalDate.now(AppConfig.LMS_ZONE)),
            lessons = r.lessons().map(::lesson),
        )
    }

    fun grading(g: TeacherService.GradingItem): GradingUi {
        val d = g.deadline()
        val info = g.info()
        return GradingUi(
            courseId = d.courseId(),
            subject = d.subject(),
            activity = d.activity(),
            stream = d.stream(),
            column = g.column(),
            activityId = info?.activityId(),
            studentDeadline = info?.studentDeadline() ?: d.start(),
            teacherDeadline = info?.teacherDeadline(),
            studentTs = g.studentTs(),
            teacherTs = g.teacherTs().takeIf { it > 0 },
            submitted = g.submitted(),
            graded = g.graded(),
            pending = g.pending(),
        )
    }

    private fun String?.orFile() = if (isNullOrBlank()) "file" else this

    fun isGraded(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return raw.replace(',', '.').trim().toDoubleOrNull()?.let { it > 0 } ?: false
    }

    /** Тип занятия приходит на языке LMS, поэтому лекцию узнаём по всем написаниям. */
    fun isLecture(type: String?): Boolean {
        val t = type?.lowercase(Locale.ROOT) ?: return false
        return "лек" in t || "lecture" in t || "ma'ruza" in t || "maruza" in t || "маъруза" in t
    }

    /** Текущий учебный год по Ташкенту: с августа — «2026-2027», до августа — «2025-2026». */
    fun academicYear(): String {
        val d = LocalDate.now(AppConfig.LMS_ZONE)
        val start = if (d.monthValue >= 8) d.year else d.year - 1
        return "$start-${start + 1}"
    }
}
