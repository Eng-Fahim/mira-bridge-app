package com.example.uhf.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.ui.theme.MiraTheme
import java.util.Locale

@Composable
internal fun PosVaultSummaryCard(
    isArabic: Boolean,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    totalWeight: Double,
    totalValue: Double,
    cashBalance: Double,
    formattedCash: String
) {
    val colors = MiraTheme.colors

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.2.dp, colors.goldPrimary.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.PointOfSale,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) "ملخص نقطة البيع والصندوق" else "POS & Cash Drawer",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }
                IconButton(onClick = onToggleHidden, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isArabic) "رصيد الصندوق / مبيعات اليوم" else "Cash Drawer / Today Sales",
                fontSize = 11.sp,
                color = colors.textSecondary
            )
            Text(
                text = if (isHidden) "•••••••" else (if (cashBalance > 0) formattedCash else "0"),
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF10B981)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                VaultStatChip(
                    label = if (isArabic) "وزن المعروضات" else "Display Weight",
                    value = if (isHidden) "•••••" else String.format(Locale.US, "%,.2f g", totalWeight),
                    icon = Icons.Rounded.Scale,
                    modifier = Modifier.weight(1f)
                )
                VaultStatChip(
                    label = if (isArabic) "القيمة التقديرية" else "Est. Value",
                    value = if (isHidden) "•••••••" else String.format(Locale.US, "$%,.2f", totalValue),
                    icon = Icons.Rounded.Diamond,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun PosKaratChipsRow(
    isHidden: Boolean,
    karats: List<KaratInventory>,
    priceManager: MiraPriceManager?
) {
    val colors = MiraTheme.colors
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(karats) { inv ->
            val kColor = getKaratAccentColor(inv.karat)
            val gramPrice = priceManager?.getGoldPricePerGram(inv.karat) ?: 0.0
            val estValue = inv.weight * gramPrice
            val formattedVal = if (estValue > 0) {
                priceManager?.formatPriceDefault(estValue)
                    ?: String.format(Locale.US, "$%,.0f", estValue)
            } else "—"

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                border = BorderStroke(1.dp, kColor.copy(alpha = 0.5f)),
                modifier = Modifier.width(142.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "${inv.karat}K",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = kColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isHidden) "•••" else String.format(Locale.US, "%,.2f g", inv.weight),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (isHidden) "•••••" else formattedVal,
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun GateSecurityTelemetryCard(
    isArabic: Boolean,
    scansToday: Int,
    allowedToday: Int,
    deniedToday: Int,
    onOpenGate: () -> Unit
) {
    val colors = MiraTheme.colors
    val cyan = Color(0xFF38BDF8)
    val green = Color(0xFF10B981)
    val red = Color(0xFFEF4444)

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.2.dp, cyan.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = cyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isArabic) "مراقبة البوابات الأمنية اليوم" else "Today's Gate Security Telemetry",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SalesMetricBox(
                    label = if (isArabic) "إجمالي المسح" else "Total Scans",
                    value = scansToday.toString(),
                    accent = cyan,
                    modifier = Modifier.weight(1f)
                )
                SalesMetricBox(
                    label = if (isArabic) "مسموح" else "Allowed",
                    value = allowedToday.toString(),
                    accent = green,
                    modifier = Modifier.weight(1f)
                )
                SalesMetricBox(
                    label = if (isArabic) "مرفوض" else "Denied",
                    value = deniedToday.toString(),
                    accent = red,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onOpenGate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cyan,
                    contentColor = Color(0xFF060B19)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sensors,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isArabic) "فتح شاشة البوابة الأمنية الحية" else "Open Live Security Gate",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
