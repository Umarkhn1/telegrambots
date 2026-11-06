package uz.tuit.lms.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uz.tuit.lms.data.Auth
import uz.tuit.lms.data.I18n
import uz.tuit.lms.data.Prefs
import uz.tuit.lms.data.Repo
import uz.tuit.lms.ui.components.LocalBottomInset
import uz.tuit.lms.ui.components.LocalSnack
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Snack
import uz.tuit.lms.ui.screens.CourseDetailScreen
import uz.tuit.lms.ui.screens.CoursesScreen
import uz.tuit.lms.ui.screens.DeadlinesScreen
import uz.tuit.lms.ui.screens.GradesScreen
import uz.tuit.lms.ui.screens.HomeScreen
import uz.tuit.lms.ui.screens.LoginScreen
import uz.tuit.lms.ui.screens.ProfileScreen
import uz.tuit.lms.ui.screens.ScheduleScreen
import uz.tuit.lms.ui.screens.SettingsScreen
import uz.tuit.lms.ui.screens.SplashScreen
import uz.tuit.lms.ui.screens.teacher.AppealsScreen
import uz.tuit.lms.ui.screens.teacher.GradingScreen
import uz.tuit.lms.ui.screens.teacher.MaterialScreen
import uz.tuit.lms.ui.screens.teacher.MaterialsScreen
import uz.tuit.lms.ui.screens.teacher.RollCallScreen
import uz.tuit.lms.ui.screens.teacher.TeacherCourseScreen
import uz.tuit.lms.ui.screens.teacher.TeacherCoursesScreen
import uz.tuit.lms.ui.screens.teacher.TeacherFinalsScreen
import uz.tuit.lms.ui.screens.teacher.TeacherHomeScreen
import uz.tuit.lms.ui.screens.teacher.TeacherProfileScreen
import uz.tuit.lms.ui.screens.teacher.TutorGroupScreen
import uz.tuit.lms.ui.screens.teacher.TutorGroupsScreen
import uz.tuit.lms.ui.screens.teacher.TutorHomeScreen
import uz.tuit.lms.ui.screens.teacher.TutorStudentScreen

@Composable
fun AppRoot(nav: Nav, onLoggedIn: () -> Unit) {
    val lang by Prefs.lang.collectAsState()
    val t = remember(lang) { I18n.of(lang) }
    val auth by Repo.auth.collectAsState()
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snack = remember { Snack(host, scope) }
    var splashDone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1100); splashDone = true }

    CompositionLocalProvider(LocalTr provides t, LocalNav provides nav, LocalSnack provides snack) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = if (!splashDone) 0 else when (auth) { is Auth.LoggedIn -> 2; Auth.LoggedOut -> 1; else -> 0 },
                transitionSpec = { (fadeIn(tween(380)) + scaleIn(initialScale = 0.96f, animationSpec = tween(380))) togetherWith fadeOut(tween(260)) },
                label = "auth",
            ) { s ->
                when (s) {
                    0 -> SplashScreen()
                    1 -> LoginScreen()
                    else -> {
                        val a = auth as? Auth.LoggedIn ?: Auth.LoggedIn(Prefs.teacher, Prefs.tutor)
                        LaunchedEffect(Unit) { onLoggedIn() }
                        MainShell(nav, a, host)
                    }
                }
            }
            if (splashDone) UpdateDialog()
            if (auth !is Auth.LoggedIn) {
                SnackbarHost(host, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(16.dp)) { Snackbar(it) }
            }
        }
    }
    LaunchedEffect(auth) { if (auth is Auth.LoggedOut) nav.reset() }
}

private data class TabItem(val tab: Tab, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val selected: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun MainShell(nav: Nav, auth: Auth.LoggedIn, host: SnackbarHostState) {
    val t = LocalTr.current
    val teacher = auth.teacher
    val tutorModeSaved by Prefs.tutorMode.collectAsState()
    // Режим тьютора доступен только преподавателю, которому LMS дала группу.
    val tutorMode = teacher && auth.tutor && tutorModeSaved
    val tabs = if (tutorMode) TUTOR_TABS else if (teacher) TEACHER_TABS else STUDENT_TABS
    val items = tabs.map {
        when (it) {
            Tab.HOME -> if (tutorMode) TabItem(it, t.tt("my_groups"), Icons.Outlined.Groups, Icons.Rounded.Groups)
                else TabItem(it, t("nav_home"), Icons.Outlined.Home, Icons.Rounded.Home)
            Tab.COURSES -> TabItem(it, t("nav_courses"), Icons.Outlined.AutoStories, Icons.Rounded.AutoStories)
            Tab.SCHEDULE -> TabItem(it, t("nav_schedule"), Icons.Outlined.CalendarMonth, Icons.Rounded.CalendarMonth)
            Tab.GRADES -> TabItem(it, t("nav_grades"), Icons.Outlined.School, Icons.Rounded.School)
            Tab.GRADING -> TabItem(it, t.tt("nav_grading"), Icons.Outlined.FactCheck, Icons.Rounded.FactCheck)
            Tab.PROFILE -> TabItem(it, t("nav_profile"), Icons.Outlined.Person, Icons.Rounded.Person)
        }
    }
    // Вкладка из ссылки может не подходить роли (оценки ↔ проверка).
    // Смена режима — на главную; при первом показе не трогаем (могли открыть ссылку из уведомления).
    var prevTutor by remember { androidx.compose.runtime.mutableStateOf(tutorMode) }
    LaunchedEffect(tutorMode) { if (prevTutor != tutorMode) { prevTutor = tutorMode; nav.select(Tab.HOME) } }
    LaunchedEffect(teacher, tutorMode, nav.tab) {
        if (nav.tab !in tabs) nav.select(if (nav.tab == Tab.GRADES) Tab.GRADING else if (nav.tab == Tab.GRADING) Tab.GRADES else Tab.HOME)
    }

    val holder = rememberSaveableStateHolder()
    val top = nav.stack.lastOrNull()

    val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize()) {
            // Содержимое уходит под плавающую панель: оставляем снизу её высоту.
            CompositionLocalProvider(LocalBottomInset provides navBottom + uz.tuit.lms.ui.components.FloatingNavSpace) {
                val dir = if (tabs.indexOf(nav.tab) >= tabs.indexOf(nav.prevTab)) 1 else -1
                AnimatedContent(
                    targetState = nav.tab,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 60))) togetherWith fadeOut(tween(90))
                    },
                    label = "tab",
                ) { tab ->
                    holder.SaveableStateProvider("tab:$tab") {
                        when (tab) {
                            Tab.HOME -> if (tutorMode) TutorHomeScreen() else if (teacher) TeacherHomeScreen() else HomeScreen()
                            Tab.COURSES -> if (teacher) TeacherCoursesScreen() else CoursesScreen()
                            Tab.SCHEDULE -> ScheduleScreen(teacher)
                            Tab.GRADES -> GradesScreen()
                            Tab.GRADING -> GradingScreen()
                            Tab.PROFILE -> if (teacher) TeacherProfileScreen(auth.tutor) else ProfileScreen()
                        }
                    }
                }
                @Suppress("UNUSED_EXPRESSION") dir
            }
            RouteLayer(nav, holder, auth)
        }
        uz.tuit.lms.ui.components.FloatingNav(
            items.map { uz.tuit.lms.ui.components.NavItem(it.tab, it.label, it.icon, it.selected) },
            nav.tab, visible = top == null, onSelect = nav::select,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
        SnackbarHost(
            host,
            Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                .padding(bottom = if (top == null) navBottom + uz.tuit.lms.ui.components.FloatingNavSpace else navBottom + 12.dp, start = 12.dp, end = 12.dp),
        ) { Snackbar(it, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) }
    }
}

/** Экраны поверх вкладки с жестом «Назад» Android 14+: экран уезжает и уменьшается вслед за пальцем. */
@Composable
private fun RouteLayer(nav: Nav, holder: androidx.compose.runtime.saveable.SaveableStateHolder, auth: Auth.LoggedIn) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val top = nav.stack.lastOrNull()

    PredictiveBackHandler(enabled = top != null) { events ->
        try {
            events.collect { e -> progress.snapTo(e.progress) }
            nav.pop()
            progress.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f) }
            throw e
        }
    }

    AnimatedContent(
        targetState = nav.stack.size to top,
        transitionSpec = {
            val forward = targetState.first > initialState.first
            if (forward) slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(250)) togetherWith fadeOut(tween(150))
            else fadeIn(tween(200)) togetherWith slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(220))
        },
        label = "route",
        contentKey = { it.second?.toString() },
    ) { (_, route) ->
        if (route == null) return@AnimatedContent
        val p = progress.value
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = p * size.width * 0.12f
                    scaleX = 1f - p * 0.08f
                    scaleY = 1f - p * 0.08f
                }
                .clip(RoundedCornerShape((p * 32).dp)),
        ) {
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                holder.SaveableStateProvider(route.toString()) { RouteScreen(route, auth) }
            }
        }
    }
    BackHandler(enabled = false) {}
}

@Composable
private fun RouteScreen(route: Route, auth: Auth.LoggedIn) {
    when (route) {
        is Route.Course -> CourseDetailScreen(route.course, route.tab, route.lesson)
        Route.Deadlines -> DeadlinesScreen()
        Route.Settings -> SettingsScreen(auth.teacher)
        Route.Admin -> uz.tuit.lms.ui.screens.AdminScreen()
        is Route.TCourse -> TeacherCourseScreen(route)
        is Route.RollCall -> RollCallScreen(route)
        Route.Appeals -> AppealsScreen()
        Route.Materials -> MaterialsScreen()
        is Route.Material -> MaterialScreen(route)
        Route.TFinals -> TeacherFinalsScreen()
        Route.Tutor -> TutorGroupsScreen()
        is Route.TGroup -> TutorGroupScreen(route)
        is Route.TStudent -> TutorStudentScreen(route)
    }
}
