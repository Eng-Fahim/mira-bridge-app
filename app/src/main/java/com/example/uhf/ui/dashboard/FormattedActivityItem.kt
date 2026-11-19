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
        val detailsObj = extractNestedJson(raw, "details")
            ?: extractNestedJson(raw, "meta")
            ?: extractNestedJson(raw, "payload")
            ?: extractNestedJson(raw, "data")
            ?: raw

        // تجميع كل مفاتيح التصنيف المحتملة من السيرفر
        val rawAction = firstNonBlankString(
            raw.optString("action"),
            raw.optString("type"),
            raw.optString("event"),
            raw.optString("entity_type"),
            raw.optString("module"),
            raw.optString("category"),
            detailsObj.optString("action"),
            detailsObj.optString("type")
        )
        val actionKey = rawAction.uppercase(Locale.US)

        val explicitTitle = if (isArabic) {
            firstNonBlankString(
                raw.optString("title_ar"),
                detailsObj.optString("title_ar"),
                raw.optString("title"),
                detailsObj.optString("title")
            )
        } else {
            firstNonBlankString(
                raw.optString("title_en"),
                detailsObj.optString("title_en"),
                raw.optString("title"),
                detailsObj.optString("title")
            )
        }

        // استخراج أرقام المراجع والفواتير والتحويلات
        val docNumber = firstNonBlankString(
            detailsObj.optString("sale_number"),
            raw.optString("sale_number"),
            detailsObj.optString("invoice_number"),
            raw.optString("invoice_number"),
            detailsObj.optString("receipt_number"),
            detailsObj.optString("order_number"),
            detailsObj.optString("transfer_number"),
            detailsObj.optString("batch_number"),
            detailsObj.optString("reference"),
            raw.optString("reference")
        )

        val entryId = firstNonBlankString(
            detailsObj.optString("entry_id"),
            raw.optString("entry_id"),
            detailsObj.optString("entity_id"),
            raw.optString("entity_id"),
            raw.optString("id")
        ).takeIf { it != "0" } ?: ""

        val customerOrBranch = firstNonBlankString(
            detailsObj.optString("customer_name"),
            raw.optString("customer_name"),
            detailsObj.optString("client_name"),
            detailsObj.optString("to_branch_name"),
            detailsObj.optString("branch_name"),
            raw.optString("branch_name"),
            detailsObj.optString("item_name"),
            detailsObj.optString("epc")
        )

        val rawAmount = firstNonZeroDouble(
            detailsObj.optDouble("net_amount", Double.NaN),
            detailsObj.optDouble("total_amount", Double.NaN),
            detailsObj.optDouble("grand_total", Double.NaN),
            detailsObj.optDouble("amount", Double.NaN),
            detailsObj.optDouble("total_debit", Double.NaN),
            detailsObj.optDouble("total_price", Double.NaN),
            detailsObj.optDouble("total", Double.NaN),
            raw.optDouble("net_amount", Double.NaN),
            raw.optDouble("total_amount", Double.NaN),
            raw.optDouble("amount", Double.NaN)
        )

        val weightVal = firstNonZeroDouble(
            detailsObj.optDouble("pure_weight", Double.NaN),
            detailsObj.optDouble("net_weight", Double.NaN),
            detailsObj.optDouble("total_weight", Double.NaN),
            detailsObj.optDouble("weight", Double.NaN),
            detailsObj.optDouble("weight_grams", Double.NaN),
            raw.optDouble("pure_weight", Double.NaN),
            raw.optDouble("total_weight", Double.NaN),
            raw.optDouble("weight", Double.NaN)
        )

        val itemsCount = firstPositiveInt(
            detailsObj.optInt("items_count", 0),
            detailsObj.optInt("count", 0),
            raw.optInt("items_count", 0)
        )

        val timeText = extractTime(raw)

        // استنتاج نوع العملية بدقة حتى لو كان actionKey عاماً
        val inferredType = when {
            actionKey.contains("REFUND") && actionKey.contains("JOURNAL") -> "REFUND_JOURNAL"
            actionKey.contains("REFUND") || actionKey.contains("RETURN") -> "REFUND"
            actionKey.contains("SALE") && actionKey.contains("JOURNAL") -> "SALE_JOURNAL"
            actionKey.contains("SALE") || actionKey.contains("POS") || actionKey.contains("INVOICE")
                || docNumber.startsWith("INV", ignoreCase = true)
                || docNumber.startsWith("SAL", ignoreCase = true) -> "SALE"
            actionKey.contains("SCRAP") || docNumber.startsWith("SCR", ignoreCase = true) -> "SCRAP"
            actionKey.contains("TRANSFER") || docNumber.startsWith("TRF", ignoreCase = true) -> "TRANSFER"
            actionKey.contains("INVENTORY") || actionKey.contains("AUDIT") || actionKey.contains("STOCK") -> "INVENTORY"
            actionKey.contains("ENCODE") || actionKey.contains("TAG") || actionKey.contains("RFID") -> "ENCODING"
            actionKey.contains("GATE") -> "GATE"
            actionKey.contains("LOGIN") || actionKey.contains("AUTH") -> "AUTH"
            else -> actionKey
        }

        val (defaultTitle, icon, accentColor) = when (inferredType) {
            "REFUND_JOURNAL" -> Triple(
                if (isArabic) "قيد يومية لمرتجع مبيعات" else "Sales Refund Journal Entry",
                Icons.Rounded.AssignmentReturn,
                Color(0xFFF59E0B)
            )
            "REFUND" -> Triple(
                if (isArabic) "مرتجع فاتورة مبيعات" else "Sales Refund",
                Icons.Rounded.Undo,
                Color(0xFFEF4444)
            )
            "SALE_JOURNAL" -> Triple(
                if (isArabic) "قيد يومية لفاتورة بيع" else "Sales Journal Entry",
                Icons.Rounded.ReceiptLong,
                Color(0xFF38BDF8)
            )
            "SALE" -> Triple(
                if (isArabic) "فاتورة مبيعات" else "POS Sale",
                Icons.Rounded.PointOfSale,
                Color(0xFF22C55E)
            )
            "SCRAP" -> Triple(
                if (isArabic) "عملية شراء كسر ذهب" else "Scrap Gold Purchase",
                Icons.Rounded.Recycling,
                Color(0xFFD4AF37)
            )
            "TRANSFER" -> Triple(
                if (isArabic) "تحويل قطع بين الفروع" else "Branch Transfer",
                Icons.Rounded.SwapHoriz,
                Color(0xFFA855F7)
            )
            "INVENTORY" -> Triple(
                if (isArabic) "جرد ومطابقة المخزون" else "Inventory Audit",
                Icons.Rounded.Inventory2,
                Color(0xFF38BDF8)
            )
            "ENCODING" -> Triple(
                if (isArabic) "تكويد وربط بطاقة RFID" else "RFID Tag Encoding",
                Icons.Rounded.QrCode2,
                Color(0xFF10B981)
            )
            "GATE" -> Triple(
                if (isArabic) "تنبيه بوابة أمنية" else "Security Gate Event",
                Icons.Rounded.Shield,
                Color(0xFFEF4444)
            )
            else -> Triple(
                humanizeActionKey(rawAction, isArabic),
                Icons.Rounded.History,
                Color(0xFFD4AF37)
            )
        }

        val finalTitle = if (
            explicitTitle.isNotBlank() &&
            explicitTitle != "عملية في النظام" &&
            !explicitTitle.equals("System Activity", ignoreCase = true)
        ) {
            explicitTitle
        } else {
            defaultTitle
        }

        val parts = mutableListOf<String>()
        if (docNumber.isNotBlank()) {
            parts.add(if (isArabic) "رقم: $docNumber" else "Ref: $docNumber")
        } else if (entryId.isNotBlank()) {
            parts.add("#$entryId")
        }
        if (customerOrBranch.isNotBlank()) {
            parts.add(customerOrBranch)
        }
        if (itemsCount > 0) {
            parts.add(if (isArabic) "$itemsCount قطعة" else "$itemsCount pcs")
        }
        if (!weightVal.isNaN()) {
            val formattedWt = if (maskAmounts) "•••" else String.format(Locale.US, "%.2f", weightVal)
            parts.add(if (isArabic) "الوزن: $formattedWt جم" else "Wt: ${formattedWt}g")
        }
        if (!rawAmount.isNaN()) {
            val formattedAmt = if (maskAmounts) "••••" else String.format(Locale.US, "%,.2f", rawAmount)
            parts.add(if (isArabic) "المبلغ: $formattedAmt" else "Amt: $formattedAmt")
        }

        val subtitle = if (parts.isNotEmpty()) {
            parts.joinToString("  •  ")
        } else {
            val readableMsg = firstNonBlankString(
                raw.optString("subtitle"),
                detailsObj.optString("subtitle"),
                raw.optString("summary"),
                detailsObj.optString("summary"),
                raw.optString("message"),
                detailsObj.optString("message"),
                raw.optString("description"),
                detailsObj.optString("description"),
                raw.optString("note")
            )
            if (readableMsg.isNotBlank() &&
                !readableMsg.trimStart().startsWith("{") &&
                readableMsg != "عملية مسجلة في النظام"
            ) {
                readableMsg
            } else {
                if (isArabic) "تم التوثيق في سجل النظام" else "Recorded in system log"
            }
        }

        val badgeText = when {
            docNumber.isNotBlank() -> docNumber
            entryId.isNotBlank() -> "#$entryId"
            else -> null
        }

        return FormattedActivityItem(
            title = finalTitle,
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

    private fun firstNonBlankString(vararg values: String?): String {
        for (v in values) {
            if (!v.isNullOrBlank() && !v.equals("null", ignoreCase = true)) {
                return v.trim()
            }
        }
        return ""
    }

    private fun firstNonZeroDouble(vararg values: Double): Double {
        for (v in values) {
            if (!v.isNaN() && v != 0.0) return v
        }
        return Double.NaN
    }

    private fun firstPositiveInt(vararg values: Int): Int {
        for (v in values) {
            if (v > 0) return v
        }
        return 0
    }

    private fun extractTime(raw: JSONObject): String {
        val candidate = firstNonBlankString(
            raw.optString("time"),
            raw.optString("created_at"),
            raw.optString("timestamp"),
            raw.optString("date")
        )
        if (candidate.isBlank()) return ""
        val timeMatch = Regex("""\b(\d{2}:\d{2})(?::\d{2})?\b""").find(candidate)
        return timeMatch?.groupValues?.getOrNull(1) ?: candidate
    }

    private fun humanizeActionKey(key: String, isArabic: Boolean): String {
        if (key.isBlank() || key.equals("SYSTEM", ignoreCase = true) || key.equals("ACTIVITY", ignoreCase = true)) {
            return if (isArabic) "نشاط النظام" else "System Activity"
        }
        val cleaned = key.replace('_', ' ').lowercase(Locale.US).trim()
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}
