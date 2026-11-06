package uz.tuit.lms.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as UnitColorProvider
import uz.tuit.lms.MainActivity
import uz.tuit.lms.R
import uz.tuit.lms.data.ActStatus
import uz.tuit.lms.data.ActivityUi
import uz.tuit.lms.data.GradingUi
import uz.tuit.lms.data.I18n
import uz.tuit.lms.data.LessonUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.Tr
import uz.tuit.lms.data.Urgency
import uz.tuit.lms.data.duration
import uz.tuit.lms.data.gpa
import uz.tuit.lms.data.hhmm
import uz.tuit.lms.data.nextLesson
import uz.tuit.lms.data.pickDay
import uz.tuit.lms.data.today
import uz.tuit.lms.data.urgency
import uz.tuit.lms.notify.SyncWorker
import uz.tuit.lms.ui.components.kindLabel
import uz.tuit.lms.ui.theme.DarkColors
import uz.tuit.lms.ui.theme.DarkTones
import uz.tuit.lms.ui.theme.LightColors
import uz.tuit.lms.ui.theme.LightTones

/* ─────────────────────────────────────────────
 *  Данные виджетов: только то, что уже сохранено на телефоне,
 *  — виджет не ходит в сеть сам, его обновляют фоновая проверка и будильники пар.
 * ───────────────────────────────────────────── */

data class Snapshot(
    val t: Tr,
    val loggedIn: Boolean,
    val teacher: Boolean,
    val lessons: List<LessonUi>,
    val deadlines: List<ActivityUi>,
    val grading: List<GradingUi>,
    val gpa: Double?,
    val nb: Int?,
    val name: String,
)

fun snapshot(): Snapshot {
    val sem = Repo.currentSemester()
    val teacher = Prefs.teacher
    val now = System.currentTimeMillis()
    return Snapshot(
        t = I18n.current(),
        loggedIn = Prefs.loggedIn,
        teacher = teacher,
        lessons = if (sem > 0) Repo.peek(Repo.schedule(sem))?.value?.lessons ?: emptyList() else emptyList(),
        deadlines = if (sem > 0) (Repo.peek(Repo.deadlines(sem))?.value ?: emptyList()).filter { it.status(now) == ActStatus.OPEN } else emptyList(),
        grading = if (teacher) Repo.gradingSummary().filter { it.pending > 0 && it.studentTs <= now && (it.teacherTs == null || it.teacherTs > now) } else emptyList(),
        gpa = Repo.peek(Repo.studyPlan())?.value?.let { gpa(it, false).value },
        nb = if (sem > 0) Repo.peek(Repo.courses(sem))?.value?.sumOf { it.attendance } else null,
        name = Repo.firstName(),
    )
}

/* ─── Тема виджетов: фирменная или Material You из обоев ─── */

private val BrandColors = ColorProviders(light = LightColors, dark = DarkColors)

@Composable
fun WidgetTheme(content: @Composable () -> Unit) {
    if (Prefs.dynamic.value && Build.VERSION.SDK_INT >= 31) GlanceTheme(content = content)
    else GlanceTheme(colors = BrandColors, content = content)
}

private fun tone(light: Color, dark: Color): UnitColorProvider = ColorProvider(day = light, night = dark)

object WColors {
    val success = tone(LightTones.success, DarkTones.success)
    val successBg = tone(LightTones.successContainer, DarkTones.successContainer)
    val warning = tone(LightTones.warning, DarkTones.warning)
    val warningBg = tone(LightTones.warningContainer, DarkTones.warningContainer)
    val danger = tone(LightTones.danger, DarkTones.danger)
    val dangerBg = tone(LightTones.dangerContainer, DarkTones.dangerContainer)
    val violet = tone(LightTones.violet, DarkTones.violet)
}

fun openApp(context: Context, link: String) = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_OPEN, link)
        .setAction("open:$link")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SyncWorker.runNow(context)
        Widgets.updateAll(context)
    }
}

/* ─── Общие кусочки ─── */

@Composable
private fun Card(context: Context, link: String, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp)
            .background(GlanceTheme.colors.widgetBackground).clickable(openApp(context, link)).padding(14.dp),
    ) { content() }
}

@Composable
private fun Header(title: String, sub: String?, icon: Int, showRefresh: Boolean = true) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(30.dp).cornerRadius(10.dp).background(GlanceTheme.colors.primaryContainer), contentAlignment = Alignment.Center) {
            Image(ImageProvider(icon), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer))
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (sub != null) Text(sub, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        if (showRefresh) Image(
            ImageProvider(R.drawable.ic_w_refresh), "refresh",
            GlanceModifier.size(28.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun Empty(text: String, sub: String? = null) {
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        if (sub != null) Text(sub, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
    }
}

@Composable
private fun NeedLogin(context: Context, t: Tr) {
    Card(context, "home") { Empty("TUIT LMS", t("widget_login")) }
}

private fun kindColor(kind: String) = when (kind) {
    "practice" -> WColors.violet
    "lab" -> WColors.warning
    else -> null
}

/* ─────────────────────────────────────────────
 *  1. Пары на сегодня
 * ───────────────────────────────────────────── */

class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Данные читаются при каждой перерисовке: updateAll перерисовывает уже открытую сессию виджета.
        provideContent { val s = snapshot(); WidgetTheme { if (!s.loggedIn) NeedLogin(context, s.t) else Content(context, s) } }
    }

    @Composable
    private fun Content(context: Context, s: Snapshot) {
        val t = s.t
        val now = System.currentTimeMillis()
        val day = pickDay(s.lessons, now)
        val compact = LocalSize.current.height < 160.dp
        Card(context, "schedule") {
            Column(GlanceModifier.fillMaxSize()) {
                Header(t(day.key), "${t.weekdayShort(day.date)}, ${t.dayMonth(day.date)}" + if (day.list.isNotEmpty()) " · ${t("pairs", "n" to day.list.size)}" else "", R.drawable.ic_w_event)
                Spacer(GlanceModifier.height(10.dp))
                if (day.list.isEmpty()) Empty(if (day.over) t("classes_over") else t("no_classes_today"), t("no_classes_sub"))
                else LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(day.list, itemId = { it.ts + it.subject.hashCode() }) { l -> LessonItem(l, now, t, compact) }
                }
            }
        }
    }

    @Composable
    private fun LessonItem(l: LessonUi, now: Long, t: Tr, compact: Boolean) {
        val live = l.live(now)
        val done = l.done(now)
        val bg = if (live) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant
        val fg = if (live) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface
        val sub = if (live) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant
        Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Row(GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(bg).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.width(46.dp)) {
                    Text(l.time.take(5), style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text(l.endTs.hhmm(), style = TextStyle(color = sub, fontSize = 11.sp))
                }
                Box(GlanceModifier.width(3.dp).height(30.dp).cornerRadius(2.dp).background(kindColor(l.kind) ?: GlanceTheme.colors.primary)) {}
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(l.subject, style = TextStyle(color = if (done) sub else fg, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = if (compact) 1 else 2)
                    val meta = listOfNotNull(
                        if (live) t("now") + " · " + t("until", "time" to l.endTs.hhmm()) else null,
                        l.room.takeIf { it.isNotBlank() },
                        t(kindLabel(l.kind)),
                    ).joinToString(" · ")
                    Text(meta, style = TextStyle(color = sub, fontSize = 11.sp), maxLines = 1)
                }
            }
        }
    }
}

/* ─────────────────────────────────────────────
 *  2. Следующая пара — компактный
 * ───────────────────────────────────────────── */

class NextLessonWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Данные читаются при каждой перерисовке: updateAll перерисовывает уже открытую сессию виджета.
        provideContent { val s = snapshot(); WidgetTheme { if (!s.loggedIn) NeedLogin(context, s.t) else Content(context, s) } }
    }

    @Composable
    private fun Content(context: Context, s: Snapshot) {
        val t = s.t
        val now = System.currentTimeMillis()
        val l = nextLesson(s.lessons, now)
        val wide = LocalSize.current.width >= 250.dp
        val link = if (l != null && s.teacher && l.courseId != null) "rollcall:${l.courseId}:${l.date}" else "schedule"
        Card(context, link) {
            if (l == null) {
                Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(R.drawable.ic_w_check), null, GlanceModifier.size(26.dp), colorFilter = ColorFilter.tint(WColors.success))
                    Spacer(GlanceModifier.height(6.dp))
                    Text(t("widget_no_next"), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                }
                return@Card
            }
            val live = l.live(now)
            val day = if (l.date == today().toString()) "" else t.weekdayShort(java.time.LocalDate.parse(l.date)) + ", "
            val status = when {
                live -> t("now") + " · " + t("until", "time" to l.endTs.hhmm())
                l.ts - now <= 60 * 60_000L -> t("widget_soon", "time" to l.time.take(5))
                else -> day + l.time.take(5)
            }
            Column(GlanceModifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.cornerRadius(10.dp).background(if (live) WColors.successBg else GlanceTheme.colors.primaryContainer).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(status, style = TextStyle(color = if (live) WColors.success else GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(l.subject, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (wide) 17.sp else 15.sp, fontWeight = FontWeight.Bold), maxLines = if (wide) 2 else 3)
                Spacer(GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (l.room.isNotBlank()) {
                        Image(ImageProvider(R.drawable.ic_w_place), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
                        Spacer(GlanceModifier.width(3.dp))
                        Text(l.room, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
                        Spacer(GlanceModifier.width(8.dp))
                    }
                    if (wide) Text(t(kindLabel(l.kind)) + (if (l.teacher.isNotBlank()) " · " + l.teacher else ""), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
                }
            }
        }
    }
}

/* ─────────────────────────────────────────────
 *  3. Дедлайны (студент) / Ждут оценки (преподаватель)
 * ───────────────────────────────────────────── */

class DeadlinesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { val s = snapshot(); WidgetTheme { if (!s.loggedIn) NeedLogin(context, s.t) else if (s.teacher) Grading(context, s) else Content(context, s) } }
    }

    @Composable
    private fun Content(context: Context, s: Snapshot) {
        val t = s.t
        val now = System.currentTimeMillis()
        Card(context, "deadlines") {
            Column(GlanceModifier.fillMaxSize()) {
                Header(t("deadlines_title"), if (s.deadlines.isNotEmpty()) t("widget_open_n", "n" to s.deadlines.size) else null, R.drawable.ic_w_assignment)
                Spacer(GlanceModifier.height(10.dp))
                if (s.deadlines.isEmpty()) Empty(t("no_deadlines"), t("no_deadlines_sub"))
                else LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(s.deadlines.take(12), itemId = { it.key.hashCode().toLong() }) { a ->
                        val dl = a.deadlineTs ?: 0
                        val u = urgency(dl, now)
                        val (fg, bg) = when (u) {
                            Urgency.DANGER -> WColors.danger to WColors.dangerBg
                            Urgency.WARNING -> WColors.warning to WColors.warningBg
                            else -> GlanceTheme.colors.onPrimaryContainer to GlanceTheme.colors.primaryContainer
                        }
                        Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp).clickable(openApp(context, "course:${a.courseId}:${a.course ?: ""}"))) {
                            Row(GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(GlanceTheme.colors.surfaceVariant).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(GlanceModifier.defaultWeight()) {
                                    Text(a.task, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 2)
                                    Text(a.course ?: "", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
                                }
                                Spacer(GlanceModifier.width(8.dp))
                                Box(GlanceModifier.cornerRadius(10.dp).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                    Text(duration(dl - now, t), style = TextStyle(color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Grading(context: Context, s: Snapshot) {
        val t = s.t
        val total = s.grading.sumOf { it.pending }
        Card(context, "grading") {
            Column(GlanceModifier.fillMaxSize()) {
                Header(t.tt("awaiting"), if (total > 0) "${t.tt("pending")}: $total" else null, R.drawable.ic_w_assignment)
                Spacer(GlanceModifier.height(10.dp))
                if (s.grading.isEmpty()) Empty(t.tt("no_grading"), t.tt("no_grading_sub"))
                else LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(s.grading.take(12), itemId = { ((it.courseId ?: 0) * 31 + (it.activity ?: "").hashCode()).toLong() }) { g ->
                        Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp).clickable(openApp(context, "tcourse:${g.courseId}:${g.column}"))) {
                            Row(GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(GlanceTheme.colors.surfaceVariant).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(GlanceModifier.defaultWeight()) {
                                    Text(g.activity ?: "", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                                    Text(listOfNotNull(g.stream, g.subject).joinToString(" · "), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
                                }
                                Box(GlanceModifier.cornerRadius(10.dp).background(WColors.warningBg).padding(horizontal = 9.dp, vertical = 3.dp)) {
                                    Text(g.pending.toString(), style = TextStyle(color = WColors.warning, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ─────────────────────────────────────────────
 *  4. Сводка: приветствие и три счётчика
 * ───────────────────────────────────────────── */

class StatsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Данные читаются при каждой перерисовке: updateAll перерисовывает уже открытую сессию виджета.
        provideContent { val s = snapshot(); WidgetTheme { if (!s.loggedIn) NeedLogin(context, s.t) else Content(context, s) } }
    }

    @Composable
    private fun Content(context: Context, s: Snapshot) {
        val t = s.t
        val now = System.currentTimeMillis()
        val next = nextLesson(s.lessons, now)
        val todayCount = s.lessons.count { it.date == today().toString() }
        val tiles = if (s.teacher) listOf(
            Triple(todayCount.toString(), t("widget_pairs_today"), "schedule"),
            Triple(s.grading.sumOf { it.pending }.toString(), t.tt("stat_pending"), "grading"),
            Triple(s.grading.size.toString(), t("widget_open_terms"), "grading"),
        ) else listOf(
            Triple(s.gpa?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—", t("stat_gpa"), "grades"),
            Triple(s.nb?.toString() ?: "—", t("stat_nb"), "courses"),
            Triple(s.deadlines.size.toString(), t("deadlines_title"), "deadlines"),
        )
        val d = today()
        Card(context, "home") {
            Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text(uz.tuit.lms.ui.screens.greetingTitle(t), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        Text("${t.weekday(d).replaceFirstChar { it.titlecase() }}, ${t.dayMonth(d)}", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
                    }
                    Image(ImageProvider(R.drawable.ic_w_refresh), "refresh", GlanceModifier.size(28.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
                }
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    tiles.forEachIndexed { i, (value, label, link) ->
                        if (i > 0) Spacer(GlanceModifier.width(6.dp))
                        Column(GlanceModifier.defaultWeight().cornerRadius(16.dp).background(GlanceTheme.colors.surfaceVariant).padding(10.dp).clickable(openApp(context, link))) {
                            Text(value, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                            Text(label, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
                        }
                    }
                }
                if (LocalSize.current.height >= 150.dp && next != null) {
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth().cornerRadius(16.dp).background(GlanceTheme.colors.primaryContainer).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (next.live(now)) t("now") else next.time.take(5), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.width(10.dp))
                        Text(next.subject + (if (next.room.isNotBlank()) " · " + next.room else ""), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp), maxLines = 1)
                    }
                }
            }
        }
    }
}

/* ─── Приёмники и каталог ─── */

class TodayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TodayWidget() }
class NextLessonWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NextLessonWidget() }
class DeadlinesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DeadlinesWidget() }
class StatsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = StatsWidget() }

object Widgets {
    data class Info(val receiver: Class<*>, val titleKey: String, val subKey: String)

    fun catalog(teacher: Boolean) = listOf(
        Info(TodayWidgetReceiver::class.java, "w_today", "w_today_sub"),
        Info(NextLessonWidgetReceiver::class.java, "w_next", "w_next_sub"),
        Info(DeadlinesWidgetReceiver::class.java, if (teacher) "w_grading" else "w_deadlines", if (teacher) "w_grading_sub" else "w_deadlines_sub"),
        Info(StatsWidgetReceiver::class.java, "w_stats", "w_stats_sub"),
    )

    fun canPin(context: Context) = Build.VERSION.SDK_INT >= 26 && AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /** Добавить виджет на рабочий стол прямо из настроек приложения. */
    fun requestPin(context: Context, receiver: Class<*>) {
        if (!canPin(context)) return
        AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, receiver), null, null)
    }

    suspend fun updateAll(context: Context) {
        runCatching {
            TodayWidget().updateAll(context)
            NextLessonWidget().updateAll(context)
            DeadlinesWidget().updateAll(context)
            StatsWidget().updateAll(context)
        }
    }
}
