package uz.tuit.lms.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uz.tuit.lms.data.I18n
import uz.tuit.lms.data.LessonUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.hhmm
import uz.tuit.lms.ui.components.kindLabel
import uz.tuit.lms.widget.Widgets

/**
 * Напоминания о парах по расписанию на устройстве — будильники AlarmManager, работают без сети
 * и при закрытом приложении: за N минут до пары, сводка дня за час до первой пары,
 * преподавателю — перекличка в начале пары. Плюс «тики» для виджетов на границах пар.
 */
object Reminders {
    private const val ACTION = "uz.tuit.lms.REMINDER"
    private const val HORIZON_MS = 36L * 3600 * 1000
    private const val DIGEST_BEFORE_MS = 60L * 60 * 1000

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private data class Plan(val at: Long, val type: String, val lesson: LessonUi?, val date: String?)

    fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        // Сначала снимаем всё, что ставили раньше.
        Prefs.str("alarms")?.split(',')?.mapNotNull { it.toIntOrNull() }?.forEach { code ->
            PendingIntent.getBroadcast(context, code, Intent(context, AlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { am.cancel(it); it.cancel() }
        }
        Prefs.setStr("alarms", null)
        if (!Prefs.loggedIn) return

        val sem = Repo.currentSemester()
        if (sem <= 0) return
        val lessons = Repo.peek(Repo.schedule(sem))?.value?.lessons ?: return
        val now = System.currentTimeMillis()
        val soon = lessons.filter { it.endTs > now && it.ts < now + HORIZON_MS }
        val lead = Prefs.int("lesson_lead", 10) * 60_000L
        val teacher = Prefs.teacher

        val plans = ArrayList<Plan>()
        for (l in soon) {
            if (Prefs.flag("lessons")) plans += Plan(l.ts - lead, "lesson", l, null)
            if (teacher && Prefs.flag("rollcall") && l.courseId != null) plans += Plan(l.ts + 60_000, "rollcall", l, null)
            for (tick in listOf(l.ts - 60 * 60_000L, l.ts - 15 * 60_000L, l.ts, l.endTs)) plans += Plan(tick, "tick", null, null)
        }
        if (Prefs.flag("digest")) {
            soon.groupBy { it.date }.forEach { (date, list) ->
                val first = list.minOf { it.ts }
                plans += Plan(first - DIGEST_BEFORE_MS, "digest", null, date)
            }
        }

        val codes = ArrayList<Int>()
        val exact = canExact(context)
        plans.filter { it.at > now }.distinctBy { it.type + it.at + (it.lesson?.subject ?: "") }.forEach { p ->
            val code = (p.type + p.at + (p.lesson?.subject ?: "") + (p.lesson?.stream ?: "")).hashCode()
            val i = Intent(context, AlarmReceiver::class.java).setAction(ACTION)
                .putExtra("type", p.type)
                .putExtra("date", p.date ?: p.lesson?.date)
                .putExtra("ts", p.lesson?.ts ?: 0L)
                .putExtra("subject", p.lesson?.subject)
                .putExtra("stream", p.lesson?.stream)
                .putExtra("courseId", p.lesson?.courseId ?: 0)
            val pi = PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
            else if (p.type == "tick") am.set(AlarmManager.RTC, p.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
            codes += code
        }
        Prefs.setStr("alarms", codes.joinToString(","))
    }

    /** Уведомление о паре — по сохранённому расписанию (оно же у виджетов). */
    internal fun fire(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return
        if (type == "tick") return
        val t = I18n.current()
        val sem = Repo.currentSemester()
        val lessons = Repo.peek(Repo.schedule(sem))?.value?.lessons ?: emptyList()
        val ts = intent.getLongExtra("ts", 0)
        val subject = intent.getStringExtra("subject")
        val l = lessons.firstOrNull { it.ts == ts && it.subject == subject }
        when (type) {
            "lesson" -> {
                if (l == null || !Prefs.flag("lessons")) return
                val min = ((l.ts - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                val details = listOfNotNull(
                    "${l.time.take(5)}–${l.endTs.hhmm()} · ${t(kindLabel(l.kind))}",
                    l.room.takeIf { it.isNotBlank() }?.let { "${t("room")}: $it" },
                    l.teacher.takeIf { it.isNotBlank() },
                    l.topic?.takeIf { it.isNotBlank() },
                )
                val teacherLink = if (Prefs.teacher && l.courseId != null) "rollcall:${l.courseId}:${l.date}" else if (l.courseId != null) "course:${l.courseId}:${l.subject}" else "schedule"
                Notifier.show(
                    context, Notifier.idFor("lesson|${l.ts}|${l.subject}"), Notifier.CH_LESSONS,
                    t("notif_lesson_title", "m" to min, "subject" to l.subject),
                    details.take(2).joinToString(" · "),
                    teacherLink, big = details.joinToString("\n"),
                    actions = listOf(Notifier.Action(t("schedule_title"), Notifier.openIntent(context, "schedule", Notifier.idFor("sched|${l.ts}")))),
                    whenMs = l.ts, timeoutMs = l.endTs - System.currentTimeMillis(),
                )
            }
            "digest" -> {
                if (!Prefs.flag("digest")) return
                val date = intent.getStringExtra("date") ?: return
                val day = lessons.filter { it.date == date }.sortedBy { it.ts }
                if (day.isEmpty()) return
                Notifier.show(
                    context, Notifier.idFor("digest|$date"), Notifier.CH_DIGEST,
                    t("notif_digest_title", "n" to day.size),
                    day.joinToString(" · ") { it.time.take(5) + " " + it.subject }.take(120),
                    "schedule",
                    lines = day.map { "${it.time.take(5)}  ${it.subject}" + (if (it.room.isNotBlank()) " · ${it.room}" else "") + (if (it.last) " 🏁" else "") },
                    whenMs = day.first().ts,
                )
            }
            "rollcall" -> {
                if (l == null || l.courseId == null || !Prefs.flag("rollcall")) return
                Notifier.show(
                    context, Notifier.idFor("roll|${l.ts}|${l.courseId}"), Notifier.CH_TEACHER,
                    t("notif_rollcall_title", "subject" to l.subject),
                    "${l.time.take(5)}" + (l.stream?.let { " · $it" } ?: "") + " — " + t("notif_rollcall_sub"),
                    "rollcall:${l.courseId}:${l.date}",
                    whenMs = l.ts,
                )
            }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                Reminders.fire(context, intent)
                Widgets.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** После перезагрузки, обновления приложения и смены времени будильники ставятся заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.reschedule(context)
        SyncWorker.schedule(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { Widgets.updateAll(context) } finally { pending.finish() }
        }
    }
}

/** «Напомнить через…» в уведомлении о дедлайне и о проверке работ. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val minutes = intent.getIntExtra("minutes", 60)
        Prefs.setLong("next_$key", System.currentTimeMillis() + minutes * 60_000L)
        Notifier.cancel(context, intent.getIntExtra("id", 0))
        // Проверка в нужный момент — отдельной разовой задачей.
        SyncWorker.runAt(context, minutes.toLong())
    }

    companion object {
        fun intent(context: Context, key: String, id: Int, minutes: Int): android.app.PendingIntent {
            val i = Intent(context, SnoozeReceiver::class.java).putExtra("key", key).putExtra("id", id).putExtra("minutes", minutes)
            return android.app.PendingIntent.getBroadcast(context, (key + minutes).hashCode(), i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
