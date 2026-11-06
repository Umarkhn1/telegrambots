package uz.tuit.lms.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import kotlinx.coroutines.launch
import uz.tuit.lms.data.LessonUi
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.data.rememberQuery
import uz.tuit.lms.data.today
import uz.tuit.lms.ui.LocalNav
import uz.tuit.lms.ui.Route
import uz.tuit.lms.ui.components.EmptyState
import uz.tuit.lms.ui.components.ErrorState
import uz.tuit.lms.ui.components.LessonRow
import uz.tuit.lms.ui.components.ListGroup
import uz.tuit.lms.ui.components.ListSkeleton
import uz.tuit.lms.ui.components.LocalBottomInset
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.OfflineBanner
import uz.tuit.lms.ui.components.SemesterChip
import uz.tuit.lms.ui.components.SemesterSheet
import uz.tuit.lms.ui.components.Skeleton
import uz.tuit.lms.ui.theme.Tabular
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Расписание семестра: неделя сверху, дни листаются свайпом. Преподавателю тап по паре
 * открывает перекличку этого занятия.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(teacher: Boolean) {
    val t = LocalTr.current
    val nav = LocalNav.current
    val semState by Prefs.semester.collectAsState()
    val sem = remember(semState) { Prefs.semesterId() }
    var semOpen by remember { mutableStateOf(false) }
    val q = rememberQuery("sched:$sem") { Repo.schedule(sem) }
    val data = q.data
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.statusBarsPadding().fillMaxSize()) {
            Text(
                t("schedule_title"), fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            )
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) { SemesterChip { semOpen = true } }
            Box(Modifier.padding(horizontal = 16.dp)) { OfflineBanner(q.error != null && data != null, q::refresh) }

            val start = data?.start?.let(LocalDate::parse)
            val weeks = data?.weeks ?: 0
            when {
                q.initialLoading -> Column(Modifier.padding(16.dp)) {
                    Skeleton(Modifier.fillMaxWidth(), height = 92.dp, shape = MaterialTheme.shapes.large)
                    Spacer(Modifier.height(14.dp))
                    ListSkeleton(3, tall = true)
                }
                data == null -> Box(Modifier.padding(16.dp)) { ErrorState(q.error, q::refresh) }
                start == null || weeks == 0 -> Box(Modifier.padding(16.dp)) { EmptyState(Icons.Rounded.CalendarMonth, t("no_schedule")) }
                else -> {
                    val byDate = remember(data) { data.lessons.groupBy { it.date } }
                    val total = weeks * 7
                    val todayIdx = ChronoUnit.DAYS.between(start, today()).toInt()
                    val initial = when {
                        todayIdx in 0 until total -> todayIdx
                        todayIdx < 0 -> 0
                        else -> total - 1
                    }
                    val pager = rememberPagerState(initialPage = initial) { total }
                    LaunchedEffect(data.start) { pager.scrollToPage(initial) }
                    val page = pager.currentPage
                    val week = page / 7
                    val monday = start.plusWeeks(week.toLong())

                    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)).background(uz.tuit.lms.ui.components.cardColor()).padding(vertical = 6.dp)) {
                    // Неделя: стрелки, диапазон дат, номер недели.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = week > 0, onClick = { scope.launch { pager.animateScrollToPage((week - 1) * 7 + page % 7) } }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${t.dayMonth(monday, true)} – ${t.dayMonth(monday.plusDays(6), true)}", style = MaterialTheme.typography.titleMedium.merge(Tabular))
                            Text(t("week_n", "n" to week + 1, "total" to weeks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(enabled = week < weeks - 1, onClick = { scope.launch { pager.animateScrollToPage(((week + 1) * 7 + page % 7).coerceAtMost(total - 1)) } }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                        }
                    }
                    // Полоса дней недели.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until 7) {
                            val d = monday.plusDays(i.toLong())
                            val idx = week * 7 + i
                            DayCell(
                                date = d,
                                label = t.weekdayShort(d),
                                selected = idx == page,
                                isToday = d == today(),
                                hasLessons = byDate.containsKey(d.toString()),
                                modifier = Modifier.weight(1f),
                            ) { scope.launch { pager.animateScrollToPage(idx) } }
                        }
                    }
                    }
                    if (week != (todayIdx / 7).coerceIn(0, weeks - 1) || page != initial) {
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            AssistChip(
                                onClick = { scope.launch { pager.animateScrollToPage(initial) } },
                                label = { Text(t("today")) },
                                leadingIcon = { Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp)) },
                                shape = RoundedCornerShape(50),
                            )
                        }
                    }
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { p ->
                        val d = start.plusDays(p.toLong())
                        val lessons = byDate[d.toString()] ?: emptyList()
                        DayPage(d, lessons, q.refreshing, q::refresh, data.end, teacher) { l ->
                            nav.push(Route.RollCall(l.courseId!!, l.subject, l.date))
                        }
                    }
                }
            }
        }
    }
    SemesterSheet(semOpen) { semOpen = false }
}

@Composable
private fun DayCell(date: LocalDate, label: String, selected: Boolean, isToday: Boolean, hasLessons: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "bg")
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = bg, modifier = modifier) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) fg else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium.merge(Tabular), fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Medium, color = fg)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (hasLessons) (if (selected) fg else MaterialTheme.colorScheme.primary) else Color.Transparent))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPage(date: LocalDate, lessons: List<LessonUi>, refreshing: Boolean, onRefresh: () -> Unit, end: String?, teacher: Boolean, onRollCall: (LessonUi) -> Unit) {
    val onOpen = LocalNav.current::openLesson
    val t = LocalTr.current
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomInset.current + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${t.weekday(date).replaceFirstChar { it.titlecase() }}, ${t.dayMonth(date)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (lessons.isNotEmpty()) Text(t("pairs", "n" to lessons.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                if (lessons.isEmpty()) EmptyState(Icons.Rounded.EventBusy, t("no_classes_day"))
                else ListGroup(lessons, key = { it.ts.toString() + it.subject + it.stream }) { _, l, shape ->
                    LessonRow(l, shape, onRollCall = if (teacher && l.courseId != null) ({ onRollCall(l) }) else null,
                        onClick = if (!teacher && l.courseId != null) ({ onOpen(l) }) else null)
                }
            }
            if (end != null) item {
                Text(
                    t("semester_until", "date" to t.dayMonth(LocalDate.parse(end))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                )
            }
        }
    }
}
