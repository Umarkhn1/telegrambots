package uz.tuit.lms.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/* Палитра lms.tuit.uz: синий #2f73c8, тёмный #1b2733 — те же цвета, что у мини-приложения. */

val BrandBlue = Color(0xFF2F73C8)

val LightColors = lightColorScheme(
    primary = Color(0xFF2F73C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EEF9),
    onPrimaryContainer = Color(0xFF2361B0),
    inversePrimary = Color(0xFFA8C8FF),
    secondary = Color(0xFF555F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EEF9),
    onSecondaryContainer = Color(0xFF2361B0),
    tertiary = Color(0xFF6254C9),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E0FF),
    onTertiaryContainer = Color(0xFF1C0A6B),
    background = Color(0xFFF1F3F6),
    onBackground = Color(0xFF1B2733),
    surface = Color(0xFFF1F3F6),
    onSurface = Color(0xFF1B2733),
    surfaceVariant = Color(0xFFDFE3EB),
    onSurfaceVariant = Color(0xFF637282),
    surfaceTint = Color(0xFF2F6CC0),
    surfaceBright = Color(0xFFF1F3F6),
    surfaceDim = Color(0xFFD7DAE0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF6F7F9),
    surfaceContainerHigh = Color(0xFFEDF0F4),
    surfaceContainerHighest = Color(0xFFE3E8EE),
    outline = Color(0xFF9AA5B1),
    outlineVariant = Color(0xFFE2E6EB),
    error = Color(0xFFC62B3A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADB),
    onErrorContainer = Color(0xFF410009),
    inverseSurface = Color(0xFF2B3440),
    inverseOnSurface = Color(0xFFEDF1F5),
    scrim = Color.Black,
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF5E9BE6),
    onPrimary = Color(0xFF0A1422),
    primaryContainer = Color(0xFF1B2D44),
    onPrimaryContainer = Color(0xFF8DBAF0),
    inversePrimary = Color(0xFF2F6CC0),
    secondary = Color(0xFFBCC6DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF1B2D44),
    onSecondaryContainer = Color(0xFF8DBAF0),
    tertiary = Color(0xFFC9BFFF),
    onTertiary = Color(0xFF2E1F94),
    tertiaryContainer = Color(0xFF473AAE),
    onTertiaryContainer = Color(0xFFE6E0FF),
    background = Color(0xFF0F151C),
    onBackground = Color(0xFFEDF1F5),
    surface = Color(0xFF0F151C),
    onSurface = Color(0xFFEDF1F5),
    surfaceVariant = Color(0xFF39434F),
    onSurfaceVariant = Color(0xFF98A6B5),
    surfaceTint = Color(0xFFA8C8FF),
    surfaceBright = Color(0xFF353D47),
    surfaceDim = Color(0xFF0F151C),
    surfaceContainerLowest = Color(0xFF0A0F15),
    surfaceContainerLow = Color(0xFF17202A),
    surfaceContainer = Color(0xFF1A232D),
    surfaceContainerHigh = Color(0xFF1F2934),
    surfaceContainerHighest = Color(0xFF283441),
    outline = Color(0xFF627181),
    outlineVariant = Color(0xFF242E39),
    error = Color(0xFFFF8A93),
    onError = Color(0xFF5E0012),
    errorContainer = Color(0xFF8C1D2B),
    onErrorContainer = Color(0xFFFFDADB),
    inverseSurface = Color(0xFFE3E8EE),
    inverseOnSurface = Color(0xFF2B3440),
    scrim = Color.Black,
)

/** Смысловые цвета (успех, срочно, просрочено…) — поверх любой схемы, в том числе Material You. */
@Immutable
data class Tones(
    val success: Color, val successContainer: Color,
    val warning: Color, val warningContainer: Color,
    val danger: Color, val dangerContainer: Color,
    val violet: Color, val violetContainer: Color,
)

val LightTones = Tones(
    success = Color(0xFF1F8D3B), successContainer = Color(0xFFE3F4E7),
    warning = Color(0xFFA77A06), warningContainer = Color(0xFFFFF3D1),
    danger = Color(0xFFD0303F), dangerContainer = Color(0xFFFBE5E7),
    violet = Color(0xFF6254C9), violetContainer = Color(0xFFEDEBFA),
)

val DarkTones = Tones(
    success = Color(0xFF4CC26F), successContainer = Color(0xFF17322A),
    warning = Color(0xFFF0C14B), warningContainer = Color(0xFF332D1A),
    danger = Color(0xFFF07A84), dangerContainer = Color(0xFF3A1F27),
    violet = Color(0xFFA597FF), violetContainer = Color(0xFF282750),
)

val LocalTones = staticCompositionLocalOf { LightTones }
val LocalDark = staticCompositionLocalOf { false }

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Цифры одной ширины — для времени, баллов и счётчиков. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun resolveDark(theme: String): Boolean = when (theme) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun colorSchemeFor(dark: Boolean, dynamic: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors
}

@Composable
fun LmsTheme(theme: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = resolveDark(theme)
    val scheme = colorSchemeFor(dark, dynamic)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalTones provides if (dark) DarkTones else LightTones, LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
