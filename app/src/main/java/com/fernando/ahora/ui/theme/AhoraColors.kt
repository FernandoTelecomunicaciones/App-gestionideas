package com.fernando.ahora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The design's Modernist tokens plus the semantic roles that make it meet its own accessibility rules
 * (PRODUCT_SPEC §9.1–§9.2, D-21). Nothing in `ui` reads a raw hex: everything goes through these roles.
 *
 *  * [accent]      chrome, icons, the FAB fill, 1 px outlines. NEVER small text and never a fill under text.
 *  * [accentFill]  fill under text (primary button, selected chip): accent-600 + white = 4.74:1.
 *  * [accentText]  small red text: accent-700 in light (6.4:1), accent-400 in dark (8.4:1).
 *  * [textSecondary]  text at 70 % (5.8:1 light / 8.2:1 dark).
 *  * [controlOutline] outline of inputs, checkboxes, chips (>= 3:1); [divider] stays decorative.
 */
@Immutable
class AhoraColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val accentFill: Color,
    val accentFillPressed: Color,
    val onAccentFill: Color,
    val accentText: Color,
    val textSecondary: Color,
    val controlOutline: Color,
    val divider: Color,
    val p1Fill: Color,
    val p1Text: Color,
    val p3Fill: Color,
    val p3Text: Color,
    val scrim: Color,
    /** Action text on the inverted Snackbar: accent-400 on the dark ink (light theme), accent-700 on the pale ink (dark theme). */
    val snackbarAction: Color,
)

private val Accent = Color(0xFFEC3013)
private val Accent600 = Color(0xFFDD2B0F)
private val Accent700 = Color(0xFFAE1800)
private val Accent400 = Color(0xFFFF9783)

val LightAhoraColors = AhoraColors(
    isDark = false,
    bg = Color(0xFFF3F2F2),
    surface = Color(0xFFEAE9E9),
    text = Color(0xFF201E1D),
    accent = Accent,
    accentFill = Accent600,
    accentFillPressed = Accent700,
    onAccentFill = Color.White,
    accentText = Accent700,
    textSecondary = Color(0xFF201E1D).copy(alpha = 0.70f),
    controlOutline = Color(0xFF201E1D).copy(alpha = 0.55f),
    divider = Color(0xFF201E1D).copy(alpha = 0.40f),
    p1Fill = Color(0xFFFFF2EF),
    p1Text = Color(0xFF7C1405),
    p3Fill = Color(0xFFF8F4F4),
    p3Text = Color(0xFF444141),
    scrim = Color.Black.copy(alpha = 0.45f),
    snackbarAction = Accent400,
)

val DarkAhoraColors = AhoraColors(
    isDark = true,
    bg = Color(0xFF1A1817),
    surface = Color(0xFF252221),
    text = Color(0xFFF3F1EF),
    accent = Accent,
    accentFill = Accent600,
    accentFillPressed = Accent700,
    onAccentFill = Color.White,
    accentText = Accent400,
    textSecondary = Color(0xFFF3F1EF).copy(alpha = 0.70f),
    controlOutline = Color(0xFFF3F1EF).copy(alpha = 0.55f),
    divider = Color(0xFFF3F1EF).copy(alpha = 0.24f),
    p1Fill = Color(0xFF3A1510),
    p1Text = Color(0xFFFFB3A3),
    p3Fill = Color(0xFF2A2726),
    p3Text = Color(0xFFE5E2E1),
    scrim = Color.Black.copy(alpha = 0.45f),
    snackbarAction = Accent700,
)

val LocalAhoraColors = staticCompositionLocalOf { LightAhoraColors }

/** M3 needs a ColorScheme for its own components; the roles above stay the source of truth. */
internal fun AhoraColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accentFill,
        onPrimary = onAccentFill,
        primaryContainer = accentFill,
        onPrimaryContainer = onAccentFill,
        secondary = accentText,
        onSecondary = bg,
        secondaryContainer = accentFill,
        onSecondaryContainer = onAccentFill,
        tertiary = accentText,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        surfaceBright = surface,
        surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = bg,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        outline = controlOutline,
        outlineVariant = divider,
        error = accentText,
        onError = bg,
        scrim = scrim,
        inverseSurface = text,
        inverseOnSurface = bg,
        inversePrimary = accentText,
    )
}

/** Shorthand used across the composables: `Ahora.colors.accentText`. */
object Ahora {
    val colors: AhoraColors
        @androidx.compose.runtime.Composable @ReadOnlyComposable get() = LocalAhoraColors.current
}
