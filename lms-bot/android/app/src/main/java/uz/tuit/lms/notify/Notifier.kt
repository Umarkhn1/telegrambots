package uz.tuit.lms.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import uz.tuit.lms.MainActivity
import uz.tuit.lms.R
import uz.tuit.lms.data.I18n

/** Системные уведомления: те же напоминания, что присылает бот, только прямо в шторку Android. */
object Notifier {
    const val CH_LESSONS = "lessons"
    const val CH_DIGEST = "digest"
    const val CH_DEADLINES = "deadlines"
    const val CH_ATTENDANCE = "attendance"
    const val CH_GRADES = "grades"
    const val CH_TEACHER = "teacher"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val t = I18n.current()
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            Triple(CH_LESSONS, t("ch_lessons"), NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_DIGEST, t("ch_digest"), NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_DEADLINES, t("ch_deadlines"), NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_ATTENDANCE, t("ch_attendance"), NotificationManager.IMPORTANCE_HIGH),
            Triple(CH_GRADES, t("ch_grades"), NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CH_TEACHER, t("ch_teacher"), NotificationManager.IMPORTANCE_HIGH),
        ).forEach { (id, name, imp) ->
            nm.createNotificationChannel(NotificationChannel(id, name, imp).apply { enableVibration(imp >= NotificationManager.IMPORTANCE_HIGH) })
        }
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Нажатие ведёт в нужное место приложения (см. Nav.open). */
    fun openIntent(context: Context, link: String, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, link)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    data class Action(val title: String, val intent: PendingIntent)

    fun show(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        link: String,
        big: String? = null,
        lines: List<String>? = null,
        actions: List<Action> = emptyList(),
        whenMs: Long? = null,
        timeoutMs: Long? = null,
    ) {
        if (!allowed(context)) return
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2F73C8.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, link, id))
            .setCategory(if (channel == CH_LESSONS || channel == CH_DEADLINES) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setPriority(if (channel == CH_GRADES || channel == CH_DIGEST) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
        when {
            lines != null -> b.setStyle(NotificationCompat.InboxStyle().also { s -> lines.take(7).forEach(s::addLine) }.setBigContentTitle(title))
            big != null -> b.setStyle(NotificationCompat.BigTextStyle().bigText(big))
        }
        if (whenMs != null) b.setWhen(whenMs).setShowWhen(true)
        if (timeoutMs != null) b.setTimeoutAfter(timeoutMs)
        actions.forEach { b.addAction(0, it.title, it.intent) }
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Стабильный id уведомления по ключу (одно и то же событие не плодит копий). */
    fun idFor(key: String) = key.hashCode() and 0x7fffffff
}
