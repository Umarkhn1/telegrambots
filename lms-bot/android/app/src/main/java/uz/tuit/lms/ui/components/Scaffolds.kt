package uz.tuit.lms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uz.tuit.lms.ui.LocalNav

/** Всплывающие сообщения (Snackbar) на весь экран приложения. */
@Stable
class Snack(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    var lastError by mutableStateOf(false)
        private set

    fun show(message: String, error: Boolean = false) {
        lastError = error
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message, duration = if (error) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }
}

val LocalSnack = staticCompositionLocalOf<Snack> { error("no Snack") }

/** Нижняя граница содержимого вкладки (высота навигации), чтобы последний элемент не прятался. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/** Прокрутили ли список дальше заголовка — тогда показываем компактную панель сверху. */
@Composable
private fun scrolledPast(state: LazyListState): Boolean {
    val v by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 90 } }
    return v
}

/** Компактная панель, которая проявляется при прокрутке: название, «Назад» и действия. */
@Composable
private fun CompactBar(title: String, visible: Boolean, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit, alwaysOpaque: Boolean = false) {
    val t = LocalTr.current
    val bg by animateColorAsState(
        if (visible || alwaysOpaque) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (visible) 0.97f else 0f) else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
        label = "bar",
    )
    Column(Modifier.fillMaxWidth().background(bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                FilledTonalIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (visible) Color.Transparent else cardColor()),
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, t("back")) }
            } else Spacer(Modifier.width(10.dp))
            AnimatedVisibility(visible, Modifier.weight(1f), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (!visible) Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        AnimatedVisibility(visible) { HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) }
    }
}

/** Большой заголовок страницы прямо в списке: экономит место и уезжает вместе с содержимым. */
@Composable
private fun PageHead(title: String, subtitle: String?, size: androidx.compose.ui.unit.TextUnit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = size, lineHeight = size * 1.15f, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

/** Экран вкладки: заголовок в содержимом, «потяните, чтобы обновить», компактная панель при прокрутке. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(
    title: String,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val past = scrolledPast(listState)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Body(PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp), refreshing, onRefresh, listState, {
            item(key = "_head") { PageHead(title, subtitle, 28.sp) { Row(verticalAlignment = Alignment.CenterVertically, content = actions) } }
            content()
        })
        if (past) CompactBar(title, true, null, actions)
        else Box(Modifier.fillMaxWidth().statusBarsPadding())
    }
}

/** Экран поверх вкладки: «Назад» всегда под рукой, название проявляется в панели при прокрутке. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    title: String,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val nav = LocalNav.current
    val past = scrolledPast(listState)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = bottomBar,
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            Body(
                PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp, bottom = inner.calculateBottomPadding()),
                refreshing, onRefresh, listState,
                {
                    item(key = "_head") { PageHead(title, subtitle, 24.sp) }
                    content()
                },
                bottom = 24.dp,
            )
            CompactBar(title, past, { nav.pop() }, actions)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Body(
    inner: PaddingValues,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    listState: LazyListState,
    content: LazyListScope.() -> Unit,
    bottom: androidx.compose.ui.unit.Dp = LocalBottomInset.current + 24.dp,
) {
    val top = inner.calculateTopPadding()
    val list: @Composable () -> Unit = {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom + inner.calculateBottomPadding()),
            verticalArrangement = Arrangement.Top,
            content = content,
        )
    }
    Box(Modifier.fillMaxSize()) {
        if (onRefresh != null) {
            val state = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing, onRefresh = onRefresh, state = state, modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(state = state, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = top - 8.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, color = MaterialTheme.colorScheme.primary)
                },
            ) { list() }
        } else list()
    }
}

/** Подтверждение действия (удалить, отправить, выйти). */
@Stable
class Confirm {
    var text by mutableStateOf<String?>(null)
    var yes by mutableStateOf("OK")
    var danger by mutableStateOf(false)
    internal var action: (() -> Unit)? = null

    fun ask(text: String, yes: String, danger: Boolean = false, action: () -> Unit) {
        this.text = text
        this.yes = yes
        this.danger = danger
        this.action = action
    }
}

@Composable
fun rememberConfirm(): Confirm {
    val c = remember { Confirm() }
    val t = LocalTr.current
    val text = c.text
    if (text != null) {
        AlertDialog(
            onDismissRequest = { c.text = null },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    c.text = null
                    c.action?.invoke()
                }) {
                    Text(c.yes, color = if (c.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { c.text = null }) { Text(t("cancel")) } },
        )
    }
    return c
}

/** Запуск действия с обработкой ошибок и показом сообщения. */
@Composable
fun rememberLauncher(): CoroutineScope = rememberCoroutineScope()

@Composable
fun Gap(h: Int) = androidx.compose.foundation.layout.Spacer(Modifier.padding(top = h.dp))
