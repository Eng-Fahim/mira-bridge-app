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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.goldPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ReceiptLong,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = if (isArabic) "آخر العمليات والمبيعات" else "Recent Operations & Sales",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = colors.goldPrimary.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f)),
                    modifier = Modifier.clickable { onViewAll() }
                ) {
                    Text(
                        text = if (isArabic) "عرض الكل ←" else "View All →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.goldPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (activities.isEmpty()) {
                Text(
                    text = if (isArabic) "لا توجد عمليات مسجلة حديثاً" else "No recent operations",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    activities.forEach { item ->
                        UnifiedOperationCard(
                            item = item,
                            isArabic = isArabic,
                            maskAmount = maskAmounts,
                            onClick = { onItemClick(item) }
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  بطاقة العملية الموحدة الراقية (مشتركة بين الرئيسية وشاشة عرض الكل)
// ═══════════════════════════════════════════════════════════════════

private data class UnifiedCardModel(
    val refCode: String,
    val categoryLabel: String,
    val categoryColor: Color,
    val hasTradeIn: Boolean,
    val statusLabel: String,
    val statusColor: Color,
    val statusIcon: ImageVector,
    val primaryParty: String,
    val partyIcon: ImageVector,
    val formattedDate: String,
    val countOrWeightText: String,
    val countIcon: ImageVector,
    val amountDisplayText: String
)

@Composable
fun UnifiedOperationCard(
    item: JSONObject,
    isArabic: Boolean,
    maskAmount: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MiraTheme.colors
    val model = remember(item, isArabic) { buildUnifiedCardModel(item, isArabic) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundTop.copy(alpha = 0.65f)),
        border = BorderStroke(1.dp, model.statusColor.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // الصف الأول: رقم الفاتورة/المرجع + شارة الدفع/النوع + شارة الحالة
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.goldPrimary.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = model.refCode,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = model.categoryColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = model.categoryLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = model.categoryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (model.hasTradeIn) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFD97706).copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "🥇",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    shape = RoundedCornerShape(50),
                    color = model.statusColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, model.statusColor.copy(alpha = 0.42f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = model.statusIcon,
                            contentDescription = null,
                            tint = model.statusColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = model.statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = model.statusColor
                        )
                    }
                }
            }

            // الصف الثاني: اسم العميل/الموظف + التاريخ والوقت المنسق
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = model.partyIcon,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = model.primaryParty,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarToday,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = model.formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }

            HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.12f))

            // الصف الثالث: عدد القطع/الوزن + المبلغ بالعملتين + سهم التفاصيل
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = model.countIcon,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = model.countOrWeightText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = if (maskAmount) "•••••" else model.amountDisplayText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (maskAmount) colors.textSecondary else colors.goldPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isArabic) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = colors.goldPrimary.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun buildUnifiedCardModel(item: JSONObject, isArabic: Boolean): UnifiedCardModel {
    val saleNumber = item.optString("sale_number",
        item.optString("invoice_number",
            item.optString("sale_id", "")
        )
    ).trim()

    val isSaleOrRefund = saleNumber.isNotEmpty() ||
        item.optString("type", "").lowercase(Locale.ROOT) in setOf("sale", "refund")

    val rawDate = item.optString("created_at",
        item.optString("timestamp", item.optString("date", ""))
    )
    val formattedDate = formatCleanOperationDate(rawDate, isArabic)

    if (isSaleOrRefund) {
        val refCode = if (saleNumber.isNotEmpty()) "#$saleNumber" else "#${item.optString("id", "—")}"
        val customerName = item.optString("customer_name",
            item.optString("client_name", if (isArabic) "عميل نقدي" else "Cash Customer")
        ).ifBlank { if (isArabic) "عميل نقدي" else "Cash Customer" }

        val rawCurrency = item.optString("currency", item.optString("currency_code", ""))
        val isMixCurrency = rawCurrency.equals("MIX", ignoreCase = true)
        val currency = if (rawCurrency.isBlank() || isMixCurrency) "YER" else rawCurrency.uppercase(Locale.US)

        val rate = item.optDouble("exchange_rate", 0.0)
        val amountUsdRaw = item.optDouble("final_amount_usd", item.optDouble("total_amount_usd", 0.0))
        val amountLocalRaw = item.optDouble(
            "final_amount",
            item.optDouble("total_amount", item.optDouble("final_amount_local", item.optDouble("amount", 0.0)))
        )

        val resolvedUsd = when {
            amountUsdRaw > 0 -> amountUsdRaw
            currency == "USD" -> amountLocalRaw
            rate > 0 -> amountLocalRaw / rate
            else -> 0.0
        }
        val resolvedLocal = when {
            amountLocalRaw > 0 -> amountLocalRaw
            currency == "USD" -> resolvedUsd
            rate > 0 -> resolvedUsd * rate
            else -> 0.0
        }

        val tradeInsArr = item.optJSONArray("trade_ins")
            ?: item.optJSONArray("gold_payments")
            ?: item.optJSONArray("trade_in_items")
        val hasTradeIn = (tradeInsArr != null && tradeInsArr.length() > 0) ||
            item.optDouble("trade_in_total", 0.0) > 0.0

        val paymentsArr = item.optJSONArray("payments")
        val payMethodRaw = item.optString("payment_method", item.optString("pay_type", "CASH"))
        val isMixed = isMixCurrency ||
            (paymentsArr != null && paymentsArr.length() > 1) ||
            (hasTradeIn && paymentsArr != null && paymentsArr.length() > 0) ||
            payMethodRaw.uppercase(Locale.ROOT) in setOf("MIX", "MIXED", "SPLIT")

        val (payLabel, payColor) = resolveUnifiedPaymentBadge(payMethodRaw, currency, isMixed, hasTradeIn, isArabic)

        val refundStatus = item.optString("refund_status", item.optString("status", "completed")).lowercase(Locale.ROOT)
        val (statusText, statusColor, statusIcon) = when (refundStatus) {
            "refunded", "fully_refunded" -> Triple(
                if (isArabic) "مرتجع كلي" else "Refunded",
                Color(0xFFEF4444),
                Icons.Rounded.Autorenew
            )
            "partially_refunded" -> Triple(
                if (isArabic) "مرتجع جزئي" else "Partial Refund",
                Color(0xFFF59E0B),
                Icons.Rounded.Autorenew
            )
            else -> Triple(
                if (isArabic) "مكتمل" else "Completed",
                Color(0xFF10B981),
                Icons.Rounded.CheckCircle
            )
        }

        val itemsArr = item.optJSONArray("items")
            ?: item.optJSONArray("sold_items")
            ?: item.optJSONArray("products")
        val parsedCount = item.optInt(
            "item_count",
            item.optInt("total_items", item.optInt("count", itemsArr?.length() ?: 0))
        )
        val countText = "$parsedCount " + (if (isArabic) "قطع" else "pcs")

        val amountDisplay = if (currency == "USD" || resolvedLocal <= 0) {
            String.format(Locale.US, "$%,.2f", resolvedUsd)
        } else {
            val localStr = String.format(Locale.US, "%,.0f %s", resolvedLocal, currency)
            val usdStr = if (resolvedUsd > 0) " ($${String.format(Locale.US, "%,.2f", resolvedUsd)})" else ""
            "$localStr$usdStr"
        }

        return UnifiedCardModel(
            refCode = refCode,
            categoryLabel = payLabel,
            categoryColor = payColor,
            hasTradeIn = hasTradeIn && isMixed,
            statusLabel = statusText,
            statusColor = statusColor,
            statusIcon = statusIcon,
            primaryParty = customerName,
            partyIcon = Icons.Rounded.Person,
            formattedDate = formattedDate,
            countOrWeightText = countText,
            countIcon = Icons.Rounded.Inventory2,
            amountDisplayText = amountDisplay
        )
    }

    // العمليات التشغيلية الأخرى (كسر ذهب، تحويلات فروع، جرد، RFID)
    val opType = item.optString("type", item.optString("action", "system")).lowercase(Locale.ROOT)
    val idStr = item.optString("id", "").ifBlank { item.optString("reference_id", "OP") }
    val (catLabel, catColor, catIcon) = when {
        opType.contains("scrap") -> Triple(if (isArabic) "شراء كسر 🥇" else "Scrap Buy", Color(0xFFD97706), Icons.Rounded.Scale)
        opType.contains("transfer") -> Triple(if (isArabic) "تحويل فروع" else "Transfer", Color(0xFFA855F7), Icons.Rounded.SwapHoriz)
        opType.contains("inventory") -> Triple(if (isArabic) "جرد مخزون" else "Inventory", Color(0xFF06B6D4), Icons.Rounded.Inventory2)
        opType.contains("rfid") -> Triple("RFID", Color(0xFF3B82F6), Icons.Rounded.Contactless)
        else -> Triple(if (isArabic) "عملية نظام" else "System Op", Color(0xFF6366F1), Icons.Rounded.SettingsSuggest)
    }

    val titleOrParty = (if (isArabic) item.optString("title_ar", item.optString("title", ""))
    else item.optString("title_en", item.optString("title", "")))
        .ifBlank { item.optString("user", item.optString("username", catLabel)) }

    val weight = item.optDouble("weight", item.optDouble("total_weight", 0.0))
    val count = item.optInt("item_count", item.optInt("count", 0))
    val leftStat = when {
        weight > 0 -> String.format(Locale.US, "%.2f g", weight)
        count > 0 -> "$count " + (if (isArabic) "قطع" else "pcs")
        else -> catLabel
    }

    val amt = item.optDouble("amount", 0.0)
    val curr = item.optString("currency", "USD").ifBlank { "USD" }
    val rightAmt = if (amt > 0) String.format(Locale.US, "%,.2f %s", amt, curr)
    else (if (isArabic) "عرض التفاصيل" else "View Details")

    return UnifiedCardModel(
        refCode = "#$idStr",
        categoryLabel = catLabel,
        categoryColor = catColor,
        hasTradeIn = false,
        statusLabel = if (isArabic) "مكتمل" else "Completed",
        statusColor = Color(0xFF10B981),
        statusIcon = Icons.Rounded.CheckCircle,
        primaryParty = titleOrParty,
        partyIcon = Icons.Rounded.Badge,
        formattedDate = formattedDate,
        countOrWeightText = leftStat,
        countIcon = catIcon,
        amountDisplayText = rightAmt
    )
}

private fun resolveUnifiedPaymentBadge(
    paymentMethod: String,
    currency: String,
    isMixed: Boolean,
    hasTradeIn: Boolean,
    isArabic: Boolean
): Pair<String, Color> {
    val m = paymentMethod.uppercase(Locale.ROOT).trim()
    return when {
        isMixed || m in setOf("MIX", "MIXED", "SPLIT") ->
            (if (isArabic) "متعدد 💰" else "Mixed 💰") to Color(0xFF8B5CF6)
        hasTradeIn && (m == "CASH" || m == "TRADE_IN") ->
            (if (isArabic) "دفع بالذهب 🥇" else "Gold Trade-In 🥇") to Color(0xFFD97706)
        m == "CARD" || m == "POS_MADA" || m == "VISA_MASTER" || m.startsWith("POS_") ->
            (if (isArabic) "بطاقة / شبكة" else "Card / POS") to Color(0xFF3B82F6)
        m.startsWith("WALLET_") || m == "E_WALLET" ->
            (if (isArabic) "محفظة إلكترونية" else "E-Wallet") to Color(0xFF06B6D4)
        m == "TRANSFER" || m == "BANK_TRANSFER" || m.startsWith("BANK_") ->
            (if (isArabic) "تحويل بنكي" else "Bank Transfer") to Color(0xFF0EA5E9)
        m == "CREDIT" ->
            (if (isArabic) "آجل (ذمم)" else "Credit") to Color(0xFFF59E0B)
        m == "CUSTOMER_CREDIT" ->
            (if (isArabic) "رصيد دائن" else "Customer Credit") to Color(0xFF6366F1)
        else ->
            (if (isArabic) "نقداً ($currency)" else "Cash ($currency)") to Color(0xFF10B981)
    }
}

private fun formatCleanOperationDate(rawDate: String, isArabic: Boolean): String {
    if (rawDate.isBlank()) return "—"
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    )
    for (pattern in patterns) {
        try {
            val parser = SimpleDateFormat(pattern, Locale.US)
            if (pattern.endsWith("'Z'")) {
                parser.timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = parser.parse(rawDate)
            if (parsed != null) {
                val out = SimpleDateFormat(
                    "dd/MM/yyyy • hh:mm a",
                    if (isArabic) Locale("ar") else Locale.US
                )
                return out.format(parsed)
            }
        } catch (_: Exception) {
        }
    }
    return rawDate.replace("T", " ").substringBefore(".")
}
