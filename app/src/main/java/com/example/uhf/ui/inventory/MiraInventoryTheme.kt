package com.example.uhf.ui.inventory

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

object MiraPalette {
    val NavyDeep = Color(0xFF080F1E)
    val NavySurface = Color(0xFF101B33)
    val NavyCard = Color(0xFF162442)
    val NavyElevated = Color(0xFF1E3056)
    val BorderSubtle = Color(0xFF283E6B)

    val GoldPrimary = Color(0xFFD4AF37)
    val GoldLight = Color(0xFFF3D98B)
    val GoldDark = Color(0xFF9E7E1E)
    val GoldContainer = Color(0x33D4AF37)

    val EmeraldMatched = Color(0xFF10B981)
    val EmeraldContainer = Color(0x2610B981)

    val CrimsonMissing = Color(0xFFEF4444)
    val CrimsonContainer = Color(0x26EF4444)

    val AmberWarning = Color(0xFFF59E0B)
    val AmberContainer = Color(0x26F59E0B)

    val CyanExtra = Color(0xFF38BDF8)
    val CyanContainer = Color(0x2638BDF8)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

private val MiraDarkColorScheme = darkColorScheme(
    primary = MiraPalette.GoldPrimary,
    onPrimary = MiraPalette.NavyDeep,
    primaryContainer = MiraPalette.GoldContainer,
    onPrimaryContainer = MiraPalette.GoldLight,
    secondary = MiraPalette.EmeraldMatched,
    onSecondary = MiraPalette.NavyDeep,
    background = MiraPalette.NavyDeep,
    onBackground = MiraPalette.TextPrimary,
    surface = MiraPalette.NavySurface,
    onSurface = MiraPalette.TextPrimary,
    surfaceVariant = MiraPalette.NavyCard,
    onSurfaceVariant = MiraPalette.TextSecondary,
    outline = MiraPalette.BorderSubtle,
    error = MiraPalette.CrimsonMissing
)

@Composable
fun MiraInventoryTheme(
    isArabic: Boolean,
    content: @Composable () -> Unit
) {
    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaterialTheme(
            colorScheme = MiraDarkColorScheme,
            content = content
        )
    }
}
