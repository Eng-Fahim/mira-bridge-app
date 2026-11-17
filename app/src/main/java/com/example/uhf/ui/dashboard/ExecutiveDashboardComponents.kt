package com.example.uhf.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.ui.theme.MiraTheme
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExecutiveCarouselSection(
    cardTypes: List<String>,
    isArabic: Boolean,
    isHeroHidden: Boolean,
    onToggleHeroHidden: () -> Unit,
    totalInventoryValue: Double,
    totalWeight: Double,
    totalItemCount: Int,
    goldPrice24: Double,
    goldPrice21: Double,
    goldPrice18: Double,
    ouncePrice: Double,
    priceSourceText: String,
    goldChangePercent: Double,
    hasGoldTrend: Boolean,
    canRefreshGoldManually: Boolean,
    isGoldLoading: Boolean,
    onRefreshGoldClick: () -> Unit,
    isSalesHidden: Boolean,
    onToggleSalesHidden: () -> Unit,
    salesToday: Double,
    salesMonth: Double,
    salesYear: Double
) {
    val colors = MiraTheme.colors
    val pagerState = rememberPagerState(pageCount = { cardTypes.size })

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = if (cardTypes.size > 1) 8.dp else 0.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            when (cardTypes[page]) {
                "HERO" -> HeroVaultCard(
                    isArabic = isArabic,
                    isHidden = isHeroHidden,
                    onToggleHidden = onToggleHeroHidden,
                    totalValue = totalInventoryValue,
                    totalWeight = totalWeight,
                    totalItemCount = totalItemCount
                )
                "GOLD" -> LiveGoldPricesCard(
                    isArabic = isArabic,
                    price24 = goldPrice24,
                    price21 = goldPrice21,
                    price18 = goldPrice18,
                    ouncePrice = ouncePrice,
                    sourceText = priceSourceText,
                    changePercent = goldChangePercent,
                    hasTrend = hasGoldTrend,
                    canRefreshManually = canRefreshGoldManually,
                    isLoading = isGoldLoading,
                    onRefreshClick = onRefreshGoldClick
                )
                "SALES" -> SalesSummaryCard(
                    isArabic = isArabic,
                    isHidden = isSalesHidden,
                    onToggleHidden = onToggleSalesHidden,
                    salesToday = salesToday,
                    salesMonth = salesMonth,
                    salesYear = salesYear
                )
            }
        }

        if (cardTypes.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(cardTypes.size) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(if (active) 20.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) colors.goldPrimary else colors.cardBorder)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroVaultCard(
    isArabic: Boolean,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    totalValue: Double,
    totalWeight: Double,
    totalItemCount: Int
) {
    val colors = MiraTheme.colors

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.2.dp, colors.goldPrimary.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.goldPrimary.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Diamond,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "إجمالي قيمة الخزنة والمخزون" else "Total Vault & Inventory Value",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textSecondary
                        )
                    }

                    IconButton(
                        onClick = onToggleHidden,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle Visibility",
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isHidden) "••••••••" else String.format(Locale.US, "$%,.2f", totalValue),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.goldPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VaultStatChip(
                        label = if (isArabic) "الوزن الكلي" else "Total Weight",
                        value = if (isHidden) "••• g" else String.format(Locale.US, "%.1f g", totalWeight),
                        icon = Icons.Rounded.Scale,
                        modifier = Modifier.weight(1f)
                    )
                    VaultStatChip(
                        label = if (isArabic) "عدد القطع" else "Total Items",
                        value = if (isHidden) "•••" else String.format(Locale.getDefault(), "%d قطعة", totalItemCount),
                        icon = Icons.Rounded.Category,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveGoldPricesCard(
    isArabic: Boolean,
    price24: Double,
    price21: Double,
    price18: Double,
    ouncePrice: Double,
    sourceText: String,
    changePercent: Double,
    hasTrend: Boolean,
    canRefreshManually: Boolean,
    isLoading: Boolean,
    onRefreshClick: () -> Unit
) {
    val colors = MiraTheme.colors

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, colors.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
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
                        imageVector = Icons.Rounded.ShowChart,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) "أسعار الذهب المباشرة" else "Live Gold Bullion",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasTrend) {
                        val trendColor = when {
                            changePercent > 0 -> Color(0xFF16A34A)
                            changePercent < 0 -> Color(0xFFDC2626)
                            else -> colors.textSecondary
                        }
                        Text(
                            text = String.format(Locale.US, "%+.2f%%", changePercent),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = trendColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    if (canRefreshManually) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = colors.goldPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            IconButton(
                                onClick = onRefreshClick,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = "Update Gold",
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                GoldKaratBox("24K", price24, Modifier.weight(1f))
                GoldKaratBox("21K", price21, Modifier.weight(1f))
                GoldKaratBox("18K", price18, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (ouncePrice > 0) {
                        String.format(Locale.US, "OZ: $%,.2f", ouncePrice)
                    } else "OZ: —",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.goldPrimary
                )
                Text(
                    text = sourceText,
                    fontSize = 10.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun GoldKaratBox(karatLabel: String, price: Double, modifier: Modifier = Modifier) {
    val colors = MiraTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.backgroundTop.copy(alpha = 0.55f))
            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Text(
            text = karatLabel,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.goldPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (price > 0) String.format(Locale.US, "$%,.2f", price) else "—",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        Text(
            text = "/g",
            fontSize = 10.sp,
            color = colors.textSecondary
        )
    }
}

@Composable
private fun SalesSummaryCard(
    isArabic: Boolean,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    salesToday: Double,
    salesMonth: Double,
    salesYear: Double
) {
    val colors = MiraTheme.colors

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, colors.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
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
                        imageVector = Icons.Rounded.TrendingUp,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) "ملخص أداء المبيعات" else "Sales Performance",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                IconButton(
                    onClick = onToggleHidden,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle Sales",
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SalesMetricBox(
                    label = if (isArabic) "اليوم" else "Today",
                    value = if (isHidden) "••••" else formatCurrencyShort(salesToday),
                    accent = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
                SalesMetricBox(
                    label = if (isArabic) "الشهر" else "Month",
                    value = if (isHidden) "••••" else formatCurrencyShort(salesMonth),
                    accent = colors.goldPrimary,
                    modifier = Modifier.weight(1f)
                )
                SalesMetricBox(
                    label = if (isArabic) "السنة" else "Year",
                    value = if (isHidden) "••••" else formatCurrencyShort(salesYear),
                    accent = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun InventoryByKaratCard(
    isArabic: Boolean,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    items: List<KaratInventory>
) {
    val colors = MiraTheme.colors
    val totalWeight = items.sumOf { it.weight }.coerceAtLeast(0.001)

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
                    text = if (isArabic) "توزيع المخزون حسب العيار" else "Inventory by Karat",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                IconButton(onClick = onToggleHidden, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (items.isEmpty()) {
                Text(
                    text = if (isArabic) "لا توجد بيانات مخزون متاحة" else "No inventory data available",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.forEach { inv ->
                        val kColor = getKaratAccentColor(inv.karat)
                        val ratio = (inv.weight / totalWeight).toFloat().coerceIn(0.04f, 1f)

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = kColor.copy(alpha = 0.18f),
                                        border = BorderStroke(1.dp, kColor.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "${inv.karat}K",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = kColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isHidden) "•• قطعة" else "${inv.itemCount} قطعة",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary
                                    )
                                }

                                Text(
                                    text = if (isHidden) "•• g" else formatWeight(inv.weight),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.trackBackground)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio)
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(kColor)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScrapGoldSummaryCard(
    isArabic: Boolean,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    weight: Double,
    formattedValue: String,
    onOpenBuy: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBatches: () -> Unit
) {
    val colors = MiraTheme.colors
    val amber = Color(0xFFF59E0B)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, amber.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Recycling,
                        contentDescription = null,
                        tint = amber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) "خزنة ذهب الكسر" else "Scrap Gold Vault",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }
                IconButton(onClick = onToggleHidden, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = amber,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                VaultStatChip(
                    label = if (isArabic) "إجمالي الوزن" else "Scrap Weight",
                    value = if (isHidden) "••••••" else String.format(Locale.US, "%.2f g", weight),
                    icon = Icons.Rounded.Scale,
                    modifier = Modifier.weight(1f)
                )
                VaultStatChip(
                    label = if (isArabic) "القيمة التقديرية" else "Est. Value",
                    value = if (isHidden) "••••••" else formattedValue,
                    icon = Icons.Rounded.Paid,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onOpenBuy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = amber,
                        contentColor = Color(0xFF060B19)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isArabic) "شراء كسر" else "Buy Scrap",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onOpenHistory,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isArabic) "السجل" else "History",
                        fontSize = 11.sp,
                        color = colors.textPrimary
                    )
                }
                OutlinedButton(
                    onClick = onOpenBatches,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isArabic) "الدفعات" else "Batches",
                        fontSize = 11.sp,
                        color = colors.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScrapAgingAlertCard(
    isArabic: Boolean,
    totalItems: Int,
    totalWeight: Double,
    oldCount: Int,
    oldWeight: Double,
    onOpenReport: () -> Unit
) {
    val colors = MiraTheme.colors
    val isWarning = oldCount > 0
    val statusColor = if (isWarning) Color(0xFFEF4444) else Color(0xFF10B981)
    val statusLabel = when {
        isWarning -> if (isArabic) "تحذير تقادم" else "Aging Warning"
        totalItems > 0 -> if (isArabic) "سليم" else "Healthy"
        else -> if (isArabic) "مُصفّى" else "Cleared"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenReport() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isArabic) "مؤشر تقادم ذهب الكسر" else "Scrap Aging Monitor",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isArabic) {
                    "${String.format(Locale.US, "%.3f g", totalWeight)}  •  $totalItems فاتورة"
                } else {
                    "${String.format(Locale.US, "%.3f g", totalWeight)}  •  $totalItems invoices"
                },
                fontSize = 12.sp,
                color = colors.textSecondary
            )

            if (isWarning) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) {
                            "$oldCount فاتورة كسر مضى عليها +60 يوماً (${String.format(Locale.US, "%.2fg", oldWeight)})"
                        } else {
                            "$oldCount scrap invoices older than 60 days (${String.format(Locale.US, "%.2fg", oldWeight)})"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }
        }
    }
}
