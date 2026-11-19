package com.example.uhf.ui.dashboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AssignmentReturn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Recycling
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun DashboardTopHeader(
    isArabic: Boolean,
    orgTitle: String,
    userName: String,
    isOnline: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val colors = MiraTheme.colors
    val spinTransition = rememberInfiniteTransition(label = "refreshSpin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isRefreshing) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = orgTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (userName.isNotEmpty()) {
                Text(
                    text = if (isArabic) "مرحباً، $userName" else "Welcome, $userName",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val statusColor = if (isOnline) Color(0xFF10B981) else Color(0xFFEF4444)
            Surface(
                shape = RoundedCornerShape(50),
                color = statusColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isOnline) (if (isArabic) "متصل" else "Online")
                        else (if (isArabic) "غير متصل" else "Offline"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = colors.cardSurface,
                border = BorderStroke(1.dp, colors.cardBorder),
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !isRefreshing) { onRefresh() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh",
                        tint = colors.goldPrimary,
                        modifier = Modifier
                            .size(19.dp)
                            .rotate(if (isRefreshing) spinAngle else 0f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun AlertsBannerCard(
    isArabic: Boolean,
    count: Int,
    message: String,
    onClick: () -> Unit
) {
    val warnColor = Color(0xFFF59E0B)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = warnColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, warnColor.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = warnColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiraTheme.colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isArabic) "عرض ($count)" else "View ($count)",
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = warnColor
            )
        }
    }
}

@Composable
internal fun VaultStatChip(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val colors = MiraTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.backgroundTop.copy(alpha = 0.5f))
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.goldPrimary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(text = label, fontSize = 10.sp, color = colors.textSecondary)
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        }
    }
}

@Composable
internal fun SalesMetricBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val colors = MiraTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = colors.textSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            color = accent
        )
    }
}

@Composable
internal fun QuickActionsSection(
    isArabic: Boolean,
    actions: List<QuickActionItem>,
    onActionClick: (QuickActionItem) -> Unit
) {
    val colors = MiraTheme.colors
    val view = LocalView.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isArabic) "الوصول السريع" else "Quick Actions",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))

            val rows = actions.chunked(4)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rowItems.forEach { item ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.backgroundTop.copy(alpha = 0.45f))
                                    .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                        onActionClick(item)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(item.accentColor.copy(alpha = 0.16f))
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = item.accentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isArabic) item.titleAr else item.titleEn,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        repeat(4 - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RecentActivitySection(
    isArabic: Boolean,
    activities: List<JSONObject>,
    maskAmounts: Boolean,
    onViewAll: () -> Unit,
    onItemClick: (JSONObject) -> Unit
) {
    val colors = MiraTheme.colors

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isArabic) "آخر العمليات والنشاط" else "Recent Activity",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Text(
                    text = if (isArabic) "عرض الكل ←" else "View All →",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.goldPrimary,
                    modifier = Modifier.clickable { onViewAll() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (activities.isEmpty()) {
                Text(
                    text = if (isArabic) "لا توجد عمليات مسجلة حديثاً" else "No recent activities",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    activities.forEach { item ->
                        ActivityTimelineRow(
                            isArabic = isArabic,
                            item = item,
                            maskAmount = maskAmounts,
                            onClick = { onItemClick(item) }
                        )
                    }
                }
            }
        }
    }
}

private data class ActivityRowPresentation(
    val title: String,
    val subtitle: String,
    val user: String,
    val timestamp: String,
    val amount: Double,
    val currency: String,
    val accuracy: Double,
    val status: String,
    val icon: ImageVector,
    val accentColor: Color
)

private fun buildActivityPresentation(
    isArabic: Boolean,
    item: JSONObject
): ActivityRowPresentation {
    val actionKey = item.optString("action", "")
        .ifBlank { item.optString("type", "") }
        .ifBlank { item.optString("event", "") }
        .uppercase(Locale.US)

    val nestedObj = extractNestedObject(item, "details")
        ?: extractNestedObject(item, "meta")
        ?: extractNestedObject(item, "payload")
        ?: item

    val (friendlyTitle, icon, accent) = resolveActionVisuals(actionKey, isArabic)

    val candidateTitle = if (isArabic) {
        item.optString("title_ar", "").ifBlank { item.optString("title", "") }
    } else {
        item.optString("title_en", "").ifBlank { item.optString("title", "") }
    }.trim()

    val finalTitle = if (candidateTitle.isNotBlank() && !isRawKey(candidateTitle)) {
        candidateTitle
    } else {
        friendlyTitle
    }

    val saleNumber = nestedObj.optString("sale_number")
        .ifBlank { item.optString("sale_number") }
        .ifBlank { nestedObj.optString("invoice_number") }
        .trim()

    val entryId = nestedObj.optString("entry_id")
        .ifBlank { item.optString("entry_id") }
        .trim()

    val nestedAmount = firstValidAmount(
        item.optDouble("amount", Double.NaN),
        nestedObj.optDouble("amount", Double.NaN),
        nestedObj.optDouble("total_debit", Double.NaN),
        nestedObj.optDouble("total_amount", Double.NaN)
    )

    val pureWeight = firstValidAmount(
        nestedObj.optDouble("pure_weight", Double.NaN),
        nestedObj.optDouble("weight", Double.NaN),
        item.optDouble("weight", Double.NaN)
    )

    val currency = item.optString("currency")
        .ifBlank { nestedObj.optString("currency") }
        .ifBlank { "USD" }
        .trim()

    val detailParts = mutableListOf<String>()
    if (saleNumber.isNotBlank()) {
        detailParts.add(if (isArabic) "فاتورة: $saleNumber" else "Invoice: $saleNumber")
    }
    if (entryId.isNotBlank()) {
        detailParts.add(if (isArabic) "قيد #$entryId" else "Entry #$entryId")
    }
    if (!nestedAmount.isNaN()) {
        detailParts.add(
            if (isArabic) "القيمة: ${String.format(Locale.US, "%,.2f", nestedAmount)} $currency"
            else "Amount: ${String.format(Locale.US, "%,.2f", nestedAmount)} $currency"
        )
    }
    if (!pureWeight.isNaN()) {
        detailParts.add(
            if (isArabic) "الوزن الصافي: ${String.format(Locale.US, "%.2f", pureWeight)} جم"
            else "Pure: ${String.format(Locale.US, "%.2f", pureWeight)}g"
        )
    }

    val rawDetails = if (isArabic) {
        item.optString("details_ar", "").ifBlank { item.optString("details", "") }
    } else {
        item.optString("details_en", "").ifBlank { item.optString("details", "") }
    }.trim()

    val subtitle = when {
        detailParts.isNotEmpty() -> detailParts.joinToString("  •  ")
        rawDetails.isNotEmpty() && !looksLikeJson(rawDetails) -> rawDetails
        else -> if (isArabic) "عملية مسجلة في النظام" else "Recorded system operation"
    }

    val user = item.optString("user", "").ifBlank { item.optString("username", "") }.trim()
    val timestamp = extractTime(item.optString("timestamp", "").ifBlank { item.optString("created_at", "") })
    val amount = if (!nestedAmount.isNaN()) nestedAmount else item.optDouble("amount", 0.0)
    val accuracy = item.optDouble("accuracy", -1.0)
    val status = item.optString("status", "neutral").lowercase(Locale.ROOT)

    return ActivityRowPresentation(
        title = finalTitle,
        subtitle = subtitle,
        user = user,
        timestamp = timestamp,
        amount = amount,
        currency = currency,
        accuracy = accuracy,
        status = status,
        icon = icon,
        accentColor = accent
    )
}

private fun resolveActionVisuals(actionKey: String, isArabic: Boolean): Triple<String, ImageVector, Color> {
    return when {
        actionKey.contains("REFUND") && actionKey.contains("JOURNAL") -> Triple(
            if (isArabic) "قيد يومية لمرتجع مبيعات" else "Sales Refund Journal Entry",
            Icons.Rounded.AssignmentReturn,
            Color(0xFFF59E0B)
        )
        actionKey.contains("REFUND") -> Triple(
            if (isArabic) "مرتجع فاتورة مبيعات" else "Sales Refund",
            Icons.Rounded.AssignmentReturn,
            Color(0xFFEF4444)
        )
        actionKey.contains("SALE") && actionKey.contains("JOURNAL") -> Triple(
            if (isArabic) "قيد يومية لفاتورة بيع" else "Sales Journal Entry",
            Icons.Rounded.ReceiptLong,
            Color(0xFF38BDF8)
        )
        actionKey.contains("SALE") || actionKey.contains("POS") -> Triple(
            if (isArabic) "إتمام عملية بيع" else "Sale Completed",
            Icons.Rounded.PointOfSale,
            Color(0xFF10B981)
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
            if (isArabic) "عملية في النظام" else "System Activity",
            Icons.Rounded.History,
            Color(0xFFD4AF37)
        )
    }
}

private fun extractNestedObject(parent: JSONObject, key: String): JSONObject? {
    parent.optJSONObject(key)?.let { return it }
    val str = parent.optString(key, "").trim()
    if (str.startsWith("{")) {
        return try {
            JSONObject(str)
        } catch (_: Exception) {
            null
        }
    }
    return null
}

private fun firstValidAmount(vararg values: Double): Double {
    for (v in values) {
        if (!v.isNaN() && v != 0.0) return v
    }
    return Double.NaN
}

private fun isRawKey(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.uppercase(Locale.US) == trimmed && trimmed.contains("_")
}

private fun looksLikeJson(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.contains("\":")
}

private fun extractTime(raw: String): String {
    if (raw.isBlank()) return ""
    val match = Regex("""\b(\d{2}:\d{2})(?::\d{2})?\b""").find(raw)
    return match?.groupValues?.getOrNull(1) ?: raw
}

@Composable
private fun ActivityTimelineRow(
    isArabic: Boolean,
    item: JSONObject,
    maskAmount: Boolean,
    onClick: () -> Unit
) {
    val colors = MiraTheme.colors
    val presentation = buildActivityPresentation(isArabic, item)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.backgroundTop.copy(alpha = 0.45f))
            .border(1.dp, presentation.accentColor.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(presentation.accentColor.copy(alpha = 0.14f))
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                tint = presentation.accentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = presentation.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (presentation.subtitle.isNotEmpty()) {
                Text(
                    text = presentation.subtitle,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (presentation.user.isNotEmpty() && presentation.user != "—") {
                    Text(
                        text = "👤 ${presentation.user}",
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (presentation.timestamp.isNotEmpty()) {
                    Text(
                        text = presentation.timestamp,
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            if (presentation.amount != 0.0) {
                val isNegative = presentation.amount < 0 || presentation.status == "refund"
                val amtColor = if (isNegative) Color(0xFFEF4444) else Color(0xFF10B981)
                Text(
                    text = if (maskAmount) "•••••" else String.format(
                        Locale.US,
                        "%s%,.2f %s",
                        if (isNegative) "-" else "",
                        abs(presentation.amount),
                        presentation.currency
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (maskAmount) colors.textSecondary else amtColor
                )
            } else if (presentation.accuracy >= 0) {
                Text(
                    text = String.format(Locale.US, "🎯 %.1f%%", presentation.accuracy),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.goldPrimary
                )
            }

            if (presentation.status == "danger" || presentation.status == "warning") {
                Spacer(modifier = Modifier.height(3.dp))
                val chipColor = if (presentation.status == "danger") Color(0xFFEF4444) else Color(0xFFF59E0B)
                Text(
                    text = if (presentation.status == "danger") (if (isArabic) "حرج" else "Critical")
                    else (if (isArabic) "تحذير" else "Warning"),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = chipColor
                )
            }
        }
    }
}
