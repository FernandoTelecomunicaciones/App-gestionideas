package com.fernando.ahora.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.fernando.ahora.domain.model.ThemeMode

/** 0 dp radius everywhere is a deliberate brand rule (D-20). Pickers keep their internal round day cells. */
private val Square = RoundedCornerShape(0.dp)
private val SquareShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)

/** Resolves [ThemeMode] against the system setting. */
@Composable
fun ThemeMode.useDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AhoraTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    AhoraTheme(dark = mode.useDark(), content = content)
}

@Composable
fun AhoraTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkAhoraColors else LightAhoraColors
    val scheme = remember(dark) { colors.toMaterial() }
    val typography = remember { ahoraTypography() }
    CompositionLocalProvider(
        LocalAhoraColors provides colors,
        LocalContentColor provides colors.text,
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.accent,
            backgroundColor = colors.accent.copy(alpha = 0.30f),
        ),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = SquareShapes, content = content)
    }
}
