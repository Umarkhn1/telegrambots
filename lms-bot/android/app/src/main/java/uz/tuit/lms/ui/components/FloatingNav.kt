package uz.tuit.lms.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.tuit.lms.ui.theme.LocalDark

data class NavItem<T>(val key: T, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Высота плавающей панели вместе с отступами — столько оставляем снизу у списков. */
val FloatingNavSpace = 66.dp + 20.dp

/**
 * Плавающая нижняя панель: капсула над содержимым, под выбранной вкладкой — индикатор,
 * который на пружине переезжает к нажатой вкладке, а значок чуть подпрыгивает.
 * Идея — из мини-приложения, форма и цвета — Material 3.
 */
@Composable
fun <T> FloatingNav(items: List<NavItem<T>>, selected: T, visible: Boolean, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val dark = LocalDark.current
    val idx = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    AnimatedVisibility(
        visible, modifier,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it * 2 } + fadeIn(),
        exit = slideOutVertically(spring(stiffness = 500f)) { it * 2 } + fadeOut(),
    ) {
        val shape = RoundedCornerShape(26.dp)
        val container = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f) else Color.White.copy(alpha = 0.97f)
        Box(
            Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth().height(66.dp)
                .shadow(if (dark) 0.dp else 14.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
                .clip(shape).background(container)
                .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (dark) 0.8f else 0.5f), shape)
                .padding(6.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = maxWidth / items.size
                val x by animateDpAsState(w * idx, spring(dampingRatio = 0.72f, stiffness = 420f), label = "navpill")
                // Индикатор выбранной вкладки — тональная капсула Material 3.
                Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(horizontal = 2.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    items.forEachIndexed { i, item ->
                        val active = i == idx
                        val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "navtint")
                        val lift by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "navlift")
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                                .clickable(remember { MutableInteractionSource() }, indication = null) {
                                    if (!active) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onSelect(item.key)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                if (active) item.selectedIcon else item.icon, item.label, tint = tint,
                                modifier = Modifier.size(23.dp).graphicsLayer {
                                    translationY = -2.dp.toPx() * lift
                                    scaleX = 1f + 0.08f * lift
                                    scaleY = 1f + 0.08f * lift
                                },
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.label, color = tint, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
