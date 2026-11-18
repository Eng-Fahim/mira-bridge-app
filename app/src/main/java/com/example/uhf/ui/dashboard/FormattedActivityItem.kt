package com.example.uhf.ui.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.json.JSONObject
import java.util.Locale

data class FormattedActivityItem(
    val title: String,
    val subtitle: String,
    val timeText: String,
    val badgeText: String?,
    val icon: ImageVector,
    val accentColor: Color,
    val rawJson: JSONObject
)

object ActivityFormatter {

    fun format(raw: JSONObject, isArabic: Boolean, maskAmounts: Boolean = false): FormattedActivityItem {
        val actionKey = raw.optString("action")
            .ifBlank { raw.optString("type") }
            .ifBlank { raw.optString("event") }
            .uppercase(Locale.US)

        // أحياناً تكون التفاصيل بداخل كائن JSON أو نص JSON في حقل details / meta / payload
        val detailsObj = extractNestedJson(raw, "details")
            ?: extractNestedJson(raw, "meta")
            ?: extractNestedJson(raw, "payload")
            ?: raw

        val saleNumber = detailsObj.optString("sale_number")
            .ifBlank { raw.optString("sale_number") }
            .ifBlank { detailsObj.optString("invoice_number") }

        val entryId = detailsObj.optString("entry_id")
            .ifBlank { raw.optString("entry_id") }

        val rawAmount = firstNonZeroDouble(
            detailsObj.optDouble("amount", Double.NaN),
            detailsObj.optDouble("total_debit", Double.NaN),
            detailsObj.optDouble("total_amount", Double.NaN),
            raw.optDouble("amount", Double.NaN)
        )

        val pureWeight = firstNonZeroDouble(
            detailsObj.optDouble("pure_weight", Double.NaN),
            detailsObj.optDouble("weight", Double.NaN),
            raw.optDouble("pure_weight", Double.NaN)
        )

        val timeText = extractTime(raw)

        val (title, icon, accentColor) = when {
            actionKey.contains("REFUND") && actionKey.contains("JOURNAL") -> Triple(
                if (isArabic) "قيد يومية لمرتجع مبيعات" else "Sales Refund Journal Entry",
                Icons.Rounded.AssignmentReturn,
                Color(0xFFF59E0B)
            )
            actionKey.contains("REFUND") -> Triple(
                if (isArabic) "مرتجع فاتورة مبيعات" else "Sales Refund",
                Icons.Rounded.Undo,
                Color(0xFFEF4444)
            )
            actionKey.contains("SALE") && actionKey.contains("JOURNAL") -> Triple(
                if (isArabic) "قيد يومية لفاتورة بيع" else "Sales Journal Entry",
                Icons.Rounded.ReceiptLong,
                Color(0xFF38BDF8)
            )
            actionKey.contains("SALE_COMPLETED") || actionKey.contains("POS_SALE") -> Triple(
                if (isArabic) "إتمام عملية بيع" else "Sale Completed",
                Icons.Rounded.PointOfSale,
                Color(0xFF22C55E)
            )
            actionKey.contains("SCRAP") -> Triple(
                if (isArabic) "عملية شراء كسر ذهب" else "Scrap Gold Operation",
                Icons.Rounded.Recycling,
                Color(0xFFD4AF37)
            )
            actionKey.contains("TRANSFER") -> Triple(
                if (isArabic) "تحويل قطع بين الفروع" else "Branch Transfer",
                Icons.Rounded.SwapHoriz,
                Color(0xFFA855F7)
            )
            actionKey.contains("GATE") -> Triple(
                if (isArabic) "تنبيه بوابة أمنية" else "Security Gate Event",
                Icons.Rounded.Shield,
                Color(0xFFEF4444)
            )
            else -> Triple(
                humanizeActionKey(actionKey, isArabic),
                Icons.Rounded.History,
                Color(0xFFD4AF37)
            )
        }

        val parts = mutableListOf<String>()
        if (saleNumber.isNotBlank()) {
            parts.add(if (isArabic) "فاتورة: $saleNumber" else "Inv: $saleNumber")
        }
        if (entryId.isNotBlank()) {
            parts.add(if (isArabic) "قيد #$entryId" else "Entry #$entryId")
        }
        if (!rawAmount.isNaN()) {
            val formattedAmt = if (maskAmounts) "••••" else String.format(Locale.US, "%,.2f", rawAmount)
            parts.add(if (isArabic) "القيمة: $formattedAmt" else "Amount: $formattedAmt")
        }
        if (!pureWeight.isNaN()) {
            val formattedWt = if (maskAmounts) "•••" else String.format(Locale.US, "%.2f", pureWeight)
            parts.add(if (isArabic) "الوزن الصافي: $formattedWt جم" else "Pure: ${formattedWt}g")
        }

        val subtitle = if (parts.isNotEmpty()) {
            parts.joinToString("  •  ")
        } else {
            val readableMsg = raw.optString("message").ifBlank { raw.optString("description") }
            if (readableMsg.isNotBlank() && !readableMsg.trimStart().startsWith("{")) {
                readableMsg
            } else {
                if (isArabic) "عملية مسجلة في النظام" else "System recorded activity"
            }
        }

        val badgeText = when {
            saleNumber.isNotBlank() -> saleNumber
            entryId.isNotBlank() -> "#$entryId"
            else -> null
        }

        return FormattedActivityItem(
            title = title,
            subtitle = subtitle,
            timeText = timeText,
            badgeText = badgeText,
            icon = icon,
            accentColor = accentColor,
            rawJson = raw
        )
    }

    private fun extractNestedJson(parent: JSONObject, key: String): JSONObject? {
        parent.optJSONObject(key)?.let { return it }
        val str = parent.optString(key, "")
        if (str.trimStart().startsWith("{")) {
            return try {
                JSONObject(str)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun firstNonZeroDouble(vararg values: Double): Double {
        for (v in values) {
            if (!v.isNaN() && v != 0.0) return v
        }
        return Double.NaN
    }

    private fun extractTime(raw: JSONObject): String {
        val candidate = raw.optString("time")
            .ifBlank { raw.optString("created_at") }
            .ifBlank { raw.optString("timestamp") }
        if (candidate.isBlank()) return ""
        // إذا كان بصيغة HH:mm:ss أو yyyy-MM-dd HH:mm:ss نأخذ الساعة والدقيقة
        val timeMatch = Regex("""\b(\d{2}:\d{2})(?::\d{2})?\b""").find(candidate)
        return timeMatch?.groupValues?.getOrNull(1) ?: candidate
    }

    private fun humanizeActionKey(key: String, isArabic: Boolean): String {
        if (key.isBlank()) return if (isArabic) "نشاط النظام" else "System Activity"
        val cleaned = key.replace('_', ' ').lowercase(Locale.US).trim()
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}
