package com.example.uhf.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.ui.theme.MiraTheme
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

data class ChartPeriodPoint(
    val label: String,
    val salesAmount: Double,
    val scrapAmount: Double = 0.0,
    val refundAmount: Double = 0.0,
    val invoicesCount: Int = 0,
    val weightGrams: Double = 0.0
)

data class KaratDistributionItem(
    val karat: String,
    val weightGrams: Double,
    val amount: Double,
    val color: Color
)

data class OperationSummaryMetric(
    val labelAr: String,
    val labelEn: String,
    val count: Int,
    val color: Color
)

/**
 * 1) رسم بياني خاص بصلاحية البائع / الكاشير:
 * يوضح سجل إنجازه ومبيعاته خلال الفترة المختارة (7 / 14 / 30 يوماً) مع نسبة تحقيق الهدف
 */
@Composable
fun StaffAchievementChartCard(
    isArabic: Boolean,
    currency: String,
    points: List<ChartPeriodPoint>,
    selectedDays: Int,
    targetAmount: Double,
    maskAmounts: Boolean,
    onPeriodChange: (Int) -> Unit
) {
    val colors = MiraTheme.colors
    val goldAccent = Color(0xFFD4AF37)
    val emeraldAccent = Color(0xFF10B981)

    var selectedIndex by remember(points) {
        mutableIntStateOf((points.size - 1).coerceAtLeast(0))
    }

    val totalPeriodSales = remember(points) { points.sumOf { it.salesAmount } }
    val totalPeriodInvoices = remember(points) { points.sumOf { it.invoicesCount } }
    val totalPeriodWeight = remember(points) { points.sumOf { it.weightGrams } }
    val achievementRatio = remember(totalPeriodSales, targetAmount) {
        if (targetAmount > 0.0) (totalPeriodSales / targetAmount).toFloat().coerceIn(0f, 1.5f)
        else if (totalPeriodSales > 0.0) 1f else 0f
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, goldAccent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // العنوان + مبدّل الفترة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Insights,
                        contentDescription = null,
                        tint = goldAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = if (isArabic) "سجل إنجازك ومبيعاتك" else "Your Sales Performance",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (isArabic) "تطور الأداء خلال آخر $selectedDays يوم"
                            else "Performance trend ($selectedDays days)",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                    }
                }

                PeriodSelectorChips(
                    options = listOf(7, 14, 30),
                    selected = selectedDays,
                    isArabic = isArabic,
                    onSelect = onPeriodChange
                )
            }

            // بطاقة تفاصيل النقطة المختارة عند اللمس
            if (points.isNotEmpty() && selectedIndex in points.indices) {
                val activePoint = points[selectedIndex]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundTop.copy(alpha = 0.6f))
                        .border(1.dp, goldAccent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activePoint.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = goldAccent
                    )
                    Text(
                        text = if (maskAmounts) "•••••"
                        else "${formatChartNumber(activePoint.salesAmount)} $currency  •  ${activePoint.invoicesCount} ${if (isArabic) "فاتورة" else "inv"}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = emeraldAccent
                    )
                }
            }

            // الرسم البياني الخطي المساحي (Area + Line Chart)
            if (points.isEmpty()) {
                EmptyChartPlaceholder(
                    text = if (isArabic) "لا توجد مبيعات مسجلة في هذه الفترة بعد"
                    else "No sales recorded in this period yet"
                )
            } else {
                InteractiveLineAreaChart(
                    points = points,
                    selectedIndex = selectedIndex,
                    lineColor = goldAccent,
                    fillColor = goldAccent.copy(alpha = 0.25f),
                    onSelectIndex = { selectedIndex = it }
                )
            }

            // شريط نسبة تحقيق الهدف للفترة
            if (targetAmount > 0.0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isArabic) "نسبة تحقيق المستهدف" else "Target Achievement",
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                        Text(
                            text = if (maskAmounts) "••%" else "${(achievementRatio * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (achievementRatio >= 1f) emeraldAccent else goldAccent
                        )
                    }
                    LinearProgressIndicator(
                        progress = { achievementRatio.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50)),
                        color = if (achievementRatio >= 1f) emeraldAccent else goldAccent,
                        trackColor = colors.backgroundTop
                    )
                }
            }

            // ملخص الفترة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniChartKpi(
                    label = if (isArabic) "إجمالي الفترة" else "Period Total",
                    value = if (maskAmounts) "••••" else "${formatChartNumber(totalPeriodSales)} $currency",
                    accent = emeraldAccent,
                    modifier = Modifier.weight(1.2f)
                )
                MiniChartKpi(
                    label = if (isArabic) "عدد الفواتير" else "Invoices",
                    value = if (maskAmounts) "••" else "$totalPeriodInvoices",
                    accent = goldAccent,
                    modifier = Modifier.weight(0.8f)
                )
                MiniChartKpi(
                    label = if (isArabic) "الوزن المباع" else "Weight",
                    value = if (maskAmounts) "•••" else String.format(Locale.US, "%.1fg", totalPeriodWeight),
                    accent = Color(0xFF06B6D4),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 2) لوحة الرسوم البيانية المتعددة للمدراء والمشرفين:
 * - رسم مقارنة العمليات اليومية (مبيعات / شراء كسر / مرتجعات)
 * - رسم توزيع العيارات (24K / 21K / 18K)
 * - مؤشرات حركة العمليات التشغيلية
 */
@Composable
fun ManagerAnalyticsChartsSection(
    isArabic: Boolean,
    currency: String,
    points: List<ChartPeriodPoint>,
    karatItems: List<KaratDistributionItem>,
    operationsBreakdown: List<OperationSummaryMetric>,
    selectedDays: Int,
    maskAmounts: Boolean,
    onPeriodChange: (Int) -> Unit
) {
    val colors = MiraTheme.colors
    val salesColor = Color(0xFF10B981)
    val scrapColor = Color(0xFFF59E0B)
    val refundColor = Color(0xFFEF4444)

    var selectedBarIndex by remember(points) {
        mutableIntStateOf((points.size - 1).coerceAtLeast(0))
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // البطاقة الأولى: مقارنة المبيعات والكسر والمرتجعات
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BarChart,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = if (isArabic) "تحليل العمليات المالية" else "Operations Financial Analytics",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isArabic) "المبيعات مقابل الكسر والمرتجعات"
                                else "Sales vs Scrap & Refunds",
                                fontSize = 11.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    PeriodSelectorChips(
                        options = listOf(7, 14, 30),
                        selected = selectedDays,
                        isArabic = isArabic,
                        onSelect = onPeriodChange
                    )
                }

                // مفتاح الألوان
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    LegendDot(if (isArabic) "المبيعات" else "Sales", salesColor)
                    LegendDot(if (isArabic) "شراء كسر" else "Scrap", scrapColor)
                    LegendDot(if (isArabic) "مرتجعات" else "Refunds", refundColor)
                }

                if (points.isNotEmpty() && selectedBarIndex in points.indices) {
                    val p = points[selectedBarIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.backgroundTop.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = p.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.goldPrimary
                        )
                        Text(
                            text = if (maskAmounts) "•••••"
                            else "${if (isArabic) "مبيعات:" else "Sales:"} ${formatChartNumber(p.salesAmount)}  |  ${if (isArabic) "كسر:" else "Scrap:"} ${formatChartNumber(p.scrapAmount)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                }

                if (points.isEmpty()) {
                    EmptyChartPlaceholder(
                        text = if (isArabic) "لا توجد بيانات تحليلية للفترة المحددة"
                        else "No analytics data for selected period"
                    )
                } else {
                    MultiSeriesBarChart(
                        points = points,
                        selectedIndex = selectedBarIndex,
                        salesColor = salesColor,
                        scrapColor = scrapColor,
                        refundColor = refundColor,
                        onSelectIndex = { selectedBarIndex = it }
                    )
                }
            }
        }

        // البطاقة الثانية: توزيع العيارات + ملخص العمليات التشغيلية
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DonutLarge,
                        contentDescription = null,
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isArabic) "توزيع العيارات وحركة العمليات" else "Karat Mix & Operations Breakdown",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary
                    )
                }

                if (karatItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        KaratDonutChart(
                            items = karatItems,
                            modifier = Modifier.size(112.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val totalWeight = karatItems.sumOf { it.weightGrams }.coerceAtLeast(0.01)
                            karatItems.forEach { item ->
                                val pct = ((item.weightGrams / totalWeight) * 100).toInt()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(item.color)
                                        )
                                        Text(
                                            text = item.karat,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                    }
                                    Text(
                                        text = if (maskAmounts) "••%"
                                        else "${String.format(Locale.US, "%.1fg", item.weightGrams)} ($pct%)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = item.color
                                    )
                                }
                            }
                        }
                    }
                }

                // أشرطة حركة العمليات التشغيلية (مبيعات، جرد، تحويلات، كسر)
                if (operationsBreakdown.isNotEmpty()) {
                    val maxOps = operationsBreakdown.maxOf { it.count }.coerceAtLeast(1)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        operationsBreakdown.forEach { op ->
                            val ratio = (op.count.toFloat() / maxOps.toFloat()).coerceIn(0.05f, 1f)
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isArabic) op.labelAr else op.labelEn,
                                        fontSize = 11.sp,
                                        color = colors.textSecondary
                                    )
                                    Text(
                                        text = if (maskAmounts) "••" else "${op.count}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = op.color
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(colors.backgroundTop)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(if (op.count == 0) 0f else ratio)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(50))
                                            .background(op.color)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveLineAreaChart(
    points: List<ChartPeriodPoint>,
    selectedIndex: Int,
    lineColor: Color,
    fillColor: Color,
    onSelectIndex: (Int) -> Unit
) {
    val maxVal = remember(points) {
        max(points.maxOfOrNull { it.salesAmount } ?: 1.0, 1.0)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        if (points.isNotEmpty()) {
                            val stepX = size.width / points.size.toFloat()
                            val idx = (offset.x / stepX).toInt().coerceIn(0, points.lastIndex)
                            onSelectIndex(idx)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val padTop = 16.dp.toPx()
            val padBottom = 12.dp.toPx()
            val chartH = (h - padTop - padBottom).coerceAtLeast(10f)

            // خطوط شبكة أفقية خفيفة
            for (i in 0..3) {
                val y = padTop + (chartH * i / 3f)
                drawLine(
                    color = Color.White.copy(alpha = 0.06f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (points.size == 1) {
                val cx = w / 2f
                val cy = padTop + chartH * (1f - (points[0].salesAmount / maxVal).toFloat())
                drawCircle(color = lineColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
                return@Canvas
            }

            val stepX = w / (points.size - 1).toFloat()
            val coords = points.mapIndexed { idx, pt ->
                val x = idx * stepX
                val ratio = (pt.salesAmount / maxVal).toFloat().coerceIn(0f, 1f)
                val y = padTop + chartH * (1f - ratio)
                Offset(x, y)
            }

            val fillPath = Path().apply {
                moveTo(coords.first().x, h - padBottom)
                coords.forEach { lineTo(it.x, it.y) }
                lineTo(coords.last().x, h - padBottom)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(listOf(fillColor, Color.Transparent))
            )

            val linePath = Path().apply {
                moveTo(coords.first().x, coords.first().y)
                for (i in 1 until coords.size) {
                    lineTo(coords[i].x, coords[i].y)
                }
            }
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            coords.forEachIndexed { idx, pt ->
                val isSel = idx == selectedIndex
                if (isSel) {
                    drawLine(
                        color = lineColor.copy(alpha = 0.35f),
                        start = Offset(pt.x, padTop),
                        end = Offset(pt.x, h - padBottom),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    drawCircle(color = lineColor.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = pt)
                }
                drawCircle(
                    color = if (isSel) Color.White else lineColor,
                    radius = if (isSel) 4.5.dp.toPx() else 3.dp.toPx(),
                    center = pt
                )
            }
        }

        // تسميات المحور الأفقي (الأول، المنتصف، الأخير)
        if (points.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = points.first().label, fontSize = 10.sp, color = MiraTheme.colors.textSecondary)
                if (points.size > 2) {
                    Text(
                        text = points[points.size / 2].label,
                        fontSize = 10.sp,
                        color = MiraTheme.colors.textSecondary
                    )
                }
                if (points.size > 1) {
                    Text(text = points.last().label, fontSize = 10.sp, color = MiraTheme.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun MultiSeriesBarChart(
    points: List<ChartPeriodPoint>,
    selectedIndex: Int,
    salesColor: Color,
    scrapColor: Color,
    refundColor: Color,
    onSelectIndex: (Int) -> Unit
) {
    val visiblePoints = remember(points) { points.takeLast(10) }
    val maxVal = remember(visiblePoints) {
        val m = visiblePoints.maxOfOrNull {
            max(it.salesAmount, max(it.scrapAmount, it.refundAmount))
        } ?: 1.0
        max(m, 1.0)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp)
                .pointerInput(visiblePoints) {
                    detectTapGestures { offset ->
                        if (visiblePoints.isNotEmpty()) {
                            val groupW = size.width / visiblePoints.size.toFloat()
                            val idx = (offset.x / groupW).toInt().coerceIn(0, visiblePoints.lastIndex)
                            onSelectIndex(idx)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val padTop = 12.dp.toPx()
            val padBottom = 8.dp.toPx()
            val chartH = (h - padTop - padBottom).coerceAtLeast(10f)
            val groupW = w / visiblePoints.size.toFloat()
            val barW = (groupW * 0.22f).coerceIn(4.dp.toPx(), 12.dp.toPx())

            visiblePoints.forEachIndexed { idx, pt ->
                val centerX = groupW * idx + groupW / 2f
                if (idx == selectedIndex) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.06f),
                        topLeft = Offset(groupW * idx + 2.dp.toPx(), 0f),
                        size = Size(groupW - 4.dp.toPx(), h),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                }

                val values = listOf(
                    pt.salesAmount to salesColor,
                    pt.scrapAmount to scrapColor,
                    pt.refundAmount to refundColor
                )

                values.forEachIndexed { bIdx, (value, color) ->
                    val ratio = (value / maxVal).toFloat().coerceIn(0f, 1f)
                    val barH = (chartH * ratio).coerceAtLeast(if (value > 0) 4.dp.toPx() else 0f)
                    val xOffset = centerX + (bIdx - 1) * (barW + 2.dp.toPx()) - barW / 2f
                    if (barH > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(xOffset, h - padBottom - barH),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            visiblePoints.forEach { pt ->
                Text(
                    text = pt.label.takeLast(5),
                    fontSize = 9.sp,
                    color = MiraTheme.colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun KaratDonutChart(
    items: List<KaratDistributionItem>,
    modifier: Modifier = Modifier
) {
    val total = remember(items) { items.sumOf { it.weightGrams }.coerceAtLeast(0.01) }

    Canvas(modifier = modifier) {
        val strokeWidth = 18.dp.toPx()
        var startAngle = -90f
        items.forEach { item ->
            val sweep = ((item.weightGrams / total) * 360.0).toFloat()
            if (sweep > 0f) {
                drawArc(
                    color = item.color,
                    startAngle = startAngle,
                    sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }
    }
}

@Composable
private fun PeriodSelectorChips(
    options: List<Int>,
    selected: Int,
    isArabic: Boolean,
    onSelect: (Int) -> Unit
) {
    val colors = MiraTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { days ->
            val isSel = days == selected
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSel) colors.goldPrimary.copy(alpha = 0.22f) else colors.backgroundTop.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, if (isSel) colors.goldPrimary else colors.cardBorder),
                modifier = Modifier.clickable { onSelect(days) }
            ) {
                Text(
                    text = if (isArabic) "${days}ي" else "${days}d",
                    fontSize = 10.sp,
                    fontWeight = if (isSel) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isSel) colors.goldPrimary else colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MiraTheme.colors.textSecondary
        )
    }
}

@Composable
private fun MiniChartKpi(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val colors = MiraTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.backgroundTop.copy(alpha = 0.55f))
            .padding(vertical = 7.dp, horizontal = 6.dp)
    ) {
        Text(text = label, fontSize = 10.sp, color = colors.textSecondary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyChartPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiraTheme.colors.backgroundTop.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MiraTheme.colors.textSecondary
        )
    }
}

private fun formatChartNumber(value: Double): String {
    return try {
        NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 0
        }.format(value)
    } catch (_: Exception) {
        String.format(Locale.US, "%.0f", value)
    }
}
