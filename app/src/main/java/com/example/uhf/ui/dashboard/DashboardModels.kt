package com.example.uhf.ui.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.Fragment
import java.util.Locale

enum class DashboardLayoutMode {
    STANDARD,
    POS_ONLY,
    GATE_ONLY
}

data class KaratInventory(
    val karat: Int,
    val itemCount: Int,
    val weight: Double
)

data class GoldPriceData(
    val price24: Double,
    val price21: Double,
    val price18: Double,
    val ouncePrice: Double,
    val source: String
)

data class QuickActionItem(
    val titleAr: String,
    val titleEn: String,
    val icon: ImageVector,
    val accentColor: Color,
    val tag: String,
    val fragmentProvider: () -> Fragment
)

data class SalesPoint(
    val label: String,
    val amount: Double
)

data class KaratSalesSlice(
    val label: String,
    val amount: Double
)

internal fun formatCurrencyShort(amount: Double): String {
    if (amount <= 0) return "0"
    return when {
        amount >= 1_000_000 -> String.format(Locale.US, "%.1fM", amount / 1_000_000)
        amount >= 1_000 -> String.format(Locale.US, "%.1fK", amount / 1_000)
        else -> String.format(Locale.US, "%.0f", amount)
    }
}

internal fun formatWeight(weightInGrams: Double): String {
    if (weightInGrams <= 0) return "0.0 g"
    return if (weightInGrams >= 1000) {
        String.format(Locale.US, "%.2f kg", weightInGrams / 1000)
    } else {
        String.format(Locale.US, "%.1f g", weightInGrams)
    }
}

internal fun extractTime(dateStr: String): String {
    if (dateStr.isEmpty()) return ""
    return try {
        val parts = dateStr.split(" ")
        if (parts.size >= 2) {
            val timeParts = parts[1].split(":")
            if (timeParts.size >= 2) "${timeParts[0]}:${timeParts[1]}" else dateStr
        } else dateStr
    } catch (_: Exception) {
        dateStr
    }
}

internal fun getKaratAccentColor(karat: Int): Color {
    return when (karat) {
        24 -> Color(0xFFD4AF37)
        22 -> Color(0xFFE6B800)
        21 -> Color(0xFFF59E0B)
        18 -> Color(0xFFCD853F)
        else -> Color(0xFFA0522D)
    }
}
