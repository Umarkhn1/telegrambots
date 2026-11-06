package uz.tuit.lms.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.tuit.lms.data.Tr
import uz.tuit.lms.data.errorKey
import uz.tuit.lms.data.initials
import uz.tuit.lms.ui.theme.LocalDark
import uz.tuit.lms.ui.theme.LocalTones
import uz.tuit.lms.ui.theme.Tabular

val LocalTr = staticCompositionLocalOf<Tr> { error("no Tr") }

/** Радиус карточек и групп списков — как --radius в мини-приложении. */
val CardRadius = 18.dp

enum class Tone { ACCENT, VIOLET, SUCCESS, WARNING, DANGER, NEUTRAL }

/** (фон, содержимое) для смыслового цвета. */
@Composable
fun Tone.colors(): Pair<Color, Color> {
    val s = MaterialTheme.colorScheme
    val t = LocalTones.current
    return when (this) {
        Tone.ACCENT -> s.primaryContainer to s.primary
        Tone.VIOLET -> t.violetContainer to t.violet
        Tone.SUCCESS -> t.successContainer to t.success
        Tone.WARNING -> t.warningContainer to t.warning
        Tone.DANGER -> t.dangerContainer to t.danger
        Tone.NEUTRAL -> s.surfaceContainerHigh to s.onSurfaceVariant
    }
}

@Composable
fun Tone.content(): Color = colors().second

/** Цвет карточки: белая на сером фоне (в тёмной теме — чуть светлее фона). */
@Composable
fun cardColor() = MaterialTheme.colorScheme.surfaceContainerLow

@Composable
fun IconTile(icon: ImageVector, tone: Tone = Tone.ACCENT, size: Dp = 38.dp, iconSize: Dp = 20.dp, shape: Shape = RoundedCornerShape(size * 0.32f)) {
    val (bg, fg) = tone.colors()
    Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun TextTile(text: String, tone: Tone = Tone.NEUTRAL, size: Dp = 38.dp, fontSize: Int = 14) {
    val (bg, fg) = tone.colors()
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(bg), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = fontSize.sp, style = Tabular, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, tone: Tone = Tone.NEUTRAL, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val (bg, fg) = tone.colors()
    Row(
        modifier.height(22.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(4.dp))
        }
        if (text.isNotEmpty()) Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Мелкая подпись с иконкой: время, аудитория, преподаватель. */
@Composable
fun Meta(icon: ImageVector?, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = color, fontSize = 13.sp, style = Tabular, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Цвет чипа потока — стабильный по названию: «RVI201-2» всегда одного цвета. */
@Composable
fun StreamChip(stream: String) {
    val tones = listOf(Tone.ACCENT, Tone.VIOLET, Tone.SUCCESS, Tone.WARNING)
    val base = stream.substringBefore('-')
    Pill(stream, tones[(base.hashCode() and 0x7fffffff) % tones.size])
}

/** Заголовок раздела: мелкий серый, как в мини-приложении; справа — «Все ›». */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, top = 22.dp, bottom = 8.dp).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 2)
        if (action != null && onAction != null) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Нажатие слегка «вдавливает» элемент — как :active в мини-приложении. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, scale: Float = 0.985f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    return this.scale(s)
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = cardColor(),
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CardRadius)
    if (onClick != null) {
        val src = remember { MutableInteractionSource() }
        Surface(onClick = onClick, interactionSource = src, modifier = modifier.fillMaxWidth().pressScale(src), shape = shape, color = color) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

/* ─── Списки в стиле Android 16: отдельные плашки с общими крупными скруглениями ─── */

private val LocalRowIndex = compositionLocalOf { 0 }
private val Inner = 5.dp

fun groupShape(index: Int, count: Int): Shape {
    val r = CardRadius + 2.dp
    return when {
        count <= 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(r, r, Inner, Inner)
        index == count - 1 -> RoundedCornerShape(Inner, Inner, r, r)
        else -> RoundedCornerShape(Inner)
    }
}

/** Отметить строку как не первую в самодельной группе (разделитель сверху). */
@Composable
fun GroupItem(index: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRowIndex provides index, content = content)
}

@Composable
fun <T> ListGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    color: Color = cardColor(),
    key: ((T) -> Any)? = null,
    item: @Composable (index: Int, value: T, shape: Shape) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, v ->
            androidx.compose.runtime.key(key?.invoke(v) ?: i) {
                GroupItem(i) { item(i, v, groupShape(i, items.size)) }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun RowDivider(inset: Dp) {}

/** Строка списка: иконка, заголовок, подзаголовок, справа — значение или стрелка. */
@Composable
fun ListRow(
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = cardColor(),
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = onClick != null && trailing == null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inner: @Composable () -> Unit = {
        Column {
            RowDivider(if (leading != null) 67.dp else 16.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = verticalAlignment,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(13.dp))
                }
                Column(Modifier.weight(1f), content = content)
                if (trailing != null) {
                    Spacer(Modifier.width(10.dp))
                    trailing()
                }
                if (chevron) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp).align(Alignment.CenterVertically))
                }
            }
        }
    }
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = color, modifier = modifier.fillMaxWidth()) { inner() }
    else Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth()) { inner() }
}

@Composable
fun RowTitle(text: String, maxLines: Int = 3, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun RowSub(text: String, maxLines: Int = 3, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, fontSize = 13.sp, lineHeight = 17.sp, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(top = 2.dp))
}

/** Строка «подпись — значение». */
@Composable
fun KvRow(label: String, value: String?, shape: Shape, color: Color = cardColor(), valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Surface(shape = shape, color = color, modifier = Modifier.fillMaxWidth()) {
        Column {
            RowDivider(16.dp)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
                Spacer(Modifier.width(12.dp))
                Text(value ?: "—", fontSize = 15.sp, fontWeight = FontWeight.Medium, style = Tabular, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(0.58f))
            }
        }
    }
}

@Composable
fun KvList(rows: List<Pair<String, String?>>, color: Color = cardColor()) {
    ListGroup(rows, color = color) { _, r, shape -> KvRow(r.first, r.second, shape, color) }
}

/* ─── Состояния ─── */

@Composable
fun EmptyState(icon: ImageVector, title: String, sub: String? = null, tone: Tone = Tone.ACCENT, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(CardRadius), color = cardColor(), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(icon, tone, size = 46.dp, iconSize = 24.dp, shape = CircleShape)
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (sub != null) {
                Spacer(Modifier.height(4.dp))
                Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                action()
            }
        }
    }
}

@Composable
fun ErrorState(error: Throwable?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTr.current
    val key = errorKey(error)
    EmptyState(
        icon = if (key == "error_network") Icons.Rounded.CloudOff else Icons.Rounded.ErrorOutline,
        title = t("error_generic"),
        sub = t(key),
        tone = Tone.DANGER,
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(38.dp)) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("retry"), fontSize = 14.sp)
        }
    }
}

/** Плашка «нет сети — показаны сохранённые данные». */
@Composable
fun OfflineBanner(visible: Boolean, onRetry: () -> Unit) {
    val t = LocalTr.current
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onRetry).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CloudOff, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(t("offline_cached"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Refresh, t("retry"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerLowest
    val tr = rememberInfiniteTransition(label = "shimmer")
    val x by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(
        colors = listOf(base, hi.copy(alpha = 0.6f).compositeOver(base), base),
        start = androidx.compose.ui.geometry.Offset(x * 1600f - 600f, 0f),
        end = androidx.compose.ui.geometry.Offset(x * 1600f, 200f),
    )
}


@Composable
fun Skeleton(modifier: Modifier = Modifier, height: Dp = 16.dp, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.height(height).clip(shape).background(shimmerBrush()))
}

@Composable
fun ListSkeleton(rows: Int = 4, tall: Boolean = false) {
    val brush = shimmerBrush()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(rows) { i ->
            GroupItem(i) {
                Column(Modifier.clip(groupShape(i, rows)).background(cardColor())) {
                    RowDivider(67.dp)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(brush))
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth(0.7f).height(13.dp).clip(RoundedCornerShape(7.dp)).background(brush))
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                            if (tall) {
                                Spacer(Modifier.height(7.dp))
                                Box(Modifier.fillMaxWidth(0.3f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ─── Мелочи ─── */

@Composable
fun AnimatedProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 6.dp) {
    val v by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(Modifier.fillMaxWidth(v).fillMaxHeight().clip(CircleShape).background(color))
    }
}

data class Opt<T>(val value: T, val label: String, val count: Int? = null)

/** Переключатель с «бегунком», который переезжает к выбранному варианту (как .segmented в вебе). */
@Composable
fun <T> Segmented(options: List<Opt<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    if (options.isEmpty()) return
    val idx = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val track = cardColor()
    val thumb = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(50)).background(track).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * idx, spring(dampingRatio = 0.72f, stiffness = 420f), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(50)).background(thumb))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, o ->
                val active = i == idx
                val c by animateColorAsState(if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "segc")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(o.value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(o.label, color = c, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

/** Чипы-фильтры: выбранный заливается акцентом (как .chip в вебе). */
@Composable
fun <T> ChipRow(options: List<Opt<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val active = o.value == selected
            val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.primaryContainer else cardColor(), label = "chipbg")
            val fg by animateColorAsState(if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "chipfg")
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.height(34.dp).widthIn(max = 260.dp).pressScale(src, 0.96f).clip(RoundedCornerShape(50)).background(bg)
                    .clickable(src, indication = null) { onSelect(o.value) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.animation.AnimatedVisibility(active) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, Modifier.padding(end = 6.dp).size(16.dp), tint = fg)
                }
                Text(o.label, color = fg, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (o.count != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(o.count.toString(), color = fg.copy(alpha = 0.7f), fontSize = 12.sp, style = Tabular)
                }
            }
        }
    }
}

/** Фото профиля (data URL из LMS) или инициалы. */
@Composable
fun Avatar(dataUrl: String?, name: String, size: Dp, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    val bmp = remember(dataUrl) {
        dataUrl?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }?.let {
            runCatching {
                val bytes = java.util.Base64.getMimeDecoder().decode(it)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        else Text(initials(name).ifEmpty { "?" }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
fun Bullet(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit) = if (enabled) clickable(onClick = onClick) else this
