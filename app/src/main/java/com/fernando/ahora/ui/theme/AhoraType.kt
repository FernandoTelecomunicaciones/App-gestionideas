package com.fernando.ahora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fernando.ahora.R

/** Archivo only, bundled in the APK (D-04). One variable file serves 400 / 600 / 800. */
@OptIn(ExperimentalTextApi::class)
val Archivo = FontFamily(
    Font(R.font.archivo_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.archivo_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.archivo_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.archivo_variable, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

/** The design's type scale (PRODUCT_SPEC §9.3). Sizes are `sp`, so they follow the system font scale. */
object AhoraType {
    private fun style(size: Int, weight: FontWeight, line: Float = 1.35f, spacing: Float = 0f) = TextStyle(
        fontFamily = Archivo,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * line).sp,
        letterSpacing = spacing.em,
    )

    val focusClock = style(44, FontWeight.ExtraBold, 1.1f)
    val headline = style(24, FontWeight.ExtraBold, 1.2f)
    val title = style(22, FontWeight.ExtraBold, 1.2f)
    val sheetTitle = style(19, FontWeight.ExtraBold, 1.2f)
    val cardTitle = style(17, FontWeight.ExtraBold, 1.3f)
    val body = style(14, FontWeight.Normal, 1.4f)
    val bodyStrong = style(14, FontWeight.ExtraBold, 1.2f)
    val bodySmall = style(13, FontWeight.Normal, 1.4f)
    val bodySmallStrong = style(13, FontWeight.SemiBold, 1.4f)
    val label = style(12, FontWeight.SemiBold, 1.3f)
    val labelStrong = style(12, FontWeight.Bold, 1.3f)
    val caption = style(11, FontWeight.Normal, 1.3f)
    val captionStrong = style(11, FontWeight.SemiBold, 1.3f)
    val navLabel = style(11, FontWeight.SemiBold, 1.2f, 0.02f)
    val kicker = style(10, FontWeight.SemiBold, 1.3f, 0.1f)
    val focusKicker = style(10, FontWeight.SemiBold, 1.3f, 0.14f)
}

internal fun ahoraTypography(): Typography = Typography(
    displayLarge = AhoraType.focusClock,
    displayMedium = AhoraType.focusClock,
    displaySmall = AhoraType.headline,
    headlineLarge = AhoraType.headline,
    headlineMedium = AhoraType.title,
    headlineSmall = AhoraType.sheetTitle,
    titleLarge = AhoraType.sheetTitle,
    titleMedium = AhoraType.cardTitle,
    titleSmall = AhoraType.bodyStrong,
    bodyLarge = AhoraType.body,
    bodyMedium = AhoraType.body,
    bodySmall = AhoraType.bodySmall,
    labelLarge = AhoraType.bodyStrong,
    labelMedium = AhoraType.label,
    labelSmall = AhoraType.caption,
)
