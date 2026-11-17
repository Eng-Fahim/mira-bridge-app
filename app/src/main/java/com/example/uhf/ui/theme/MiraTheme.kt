package com.example.uhf.ui.theme

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration

@Immutable
data class MiraExtendedColors(
    val backgroundTop: Color,
    val backgroundMid: Color,
    val cardSurface: Color,
    val cardBorder: Color,
    val goldPrimary: Color,
    val goldHighlight: Color,
    val goldDeep: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val subtitleGold: Color,
    val glowAlpha: Float,
    val trackBackground: Color,
    val isDark: Boolean
)

private val DarkMiraColors = MiraExtendedColors(
    backgroundTop   = Color(0xFF040814),
    backgroundMid   = Color(0xFF0C1833),
    cardSurface     = Color(0xFF101D3D),
    cardBorder      = Color(0x44D4AF37),
    goldPrimary     = Color(0xFFD4AF37),
    goldHighlight   = Color(0xFFFFF0B8),
    goldDeep        = Color(0xFF996515),
    textPrimary     = Color(0xFFFFFFFF),
    textSecondary   = Color(0xB3FFFFFF),
    subtitleGold    = Color(0xE6FFF0B8),
    glowAlpha       = 0.20f,
    trackBackground = Color(0x1FFFFFFF),
    isDark          = true
)

private val LightMiraColors = MiraExtendedColors(
    backgroundTop   = Color(0xFFFAF7F2), // عاجي لؤلؤي فاخر
    backgroundMid   = Color(0xFFF1EAE0), // بيج ذهبي ناعم
    cardSurface     = Color(0xFFFFFFFF),
    cardBorder      = Color(0x66B8860B),
    goldPrimary     = Color(0xFFB8860B), // ذهب معتّق عالي التباين للوضع الفاتح
    goldHighlight   = Color(0xFFE5B842),
    goldDeep        = Color(0xFF7A4F08),
    textPrimary     = Color(0xFF0B152C), // كحلي داكن للنصوص
    textSecondary   = Color(0xFF475569),
    subtitleGold    = Color(0xFF8A5A10),
    glowAlpha       = 0.14f,
    trackBackground = Color(0x1A0B152C),
    isDark          = false
)

val LocalMiraColors = staticCompositionLocalOf { DarkMiraColors }

object MiraTheme {
    val colors: MiraExtendedColors
        @Composable
        get() = LocalMiraColors.current
}

@Composable
fun MiraJewelryTheme(
    darkTheme: Boolean = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    content: @Composable () -> Unit
) {
    val extendedColors = if (darkTheme) DarkMiraColors else LightMiraColors

    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = extendedColors.goldPrimary,
            background = extendedColors.backgroundTop,
            surface = extendedColors.cardSurface,
            onBackground = extendedColors.textPrimary,
            onSurface = extendedColors.textPrimary
        )
    } else {
        lightColorScheme(
            primary = extendedColors.goldPrimary,
            background = extendedColors.backgroundTop,
            surface = extendedColors.cardSurface,
            onBackground = extendedColors.textPrimary,
            onSurface = extendedColors.textPrimary
        )
    }

    CompositionLocalProvider(LocalMiraColors provides extendedColors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = MiraTypography,
            content = content
        )
    }
}
