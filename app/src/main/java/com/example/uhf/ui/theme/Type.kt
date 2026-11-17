package com.example.uhf.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.uhf.R

val CairoFontFamily = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_medium, FontWeight.Medium),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
    Font(R.font.cairo_bold, FontWeight.ExtraBold)
)

private val BaseTypography = Typography()

val MiraTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = CairoFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = CairoFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = CairoFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = CairoFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = CairoFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = CairoFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = CairoFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = CairoFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = CairoFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = CairoFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = CairoFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = CairoFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = CairoFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = CairoFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = CairoFontFamily)
)
