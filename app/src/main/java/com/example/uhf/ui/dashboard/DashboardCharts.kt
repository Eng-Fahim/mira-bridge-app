package com.example.uhf.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.ui.theme.MiraTheme
import java.util.Locale

@Composable
internal fun SalesTrendChartCard(
    title: String,
    points: List<SalesPoint>,
    showDualCurve: Boolean
) {
    val colors = MiraTheme.colors

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(14.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(155.dp)
            ) {
                if (points.isEmpty()) return@Canvas
                val maxVal = (points.maxOfOrNull { it.amount } ?: 1.0).coerceAtLeast(1.0)
                val w = size.width
                val h = size.height
                val stepX = if (points.size > 1) w / (points.size - 1) else w / 2f

                for (g in 1..3) {
                    val gy = h * (g / 4f)
                    drawLine(
                        color = colors.cardBorder.copy(alpha = 0.35f),
                        start = Offset(0f, gy),
                        end = Offset(w, gy),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val primaryCoords = points.mapIndexed { idx, pt ->
                    val x = if (points.size > 1) idx * stepX else w / 2f
                    val y = h - ((pt.amount / maxVal).toFloat() * (h * 0.85f)) - (h * 0.08f)
                    Offset(x, y)
                }

                val curvePath = Path().apply {
                    moveTo(primaryCoords.first().x, primaryCoords.first().y)
                    for (i in 0 until primaryCoords.size - 1) {
                        val p0 = primaryCoords[i]
                        val p1 = primaryCoords[i + 1]
                        val midX = (p0.x + p1.x) / 2f
                        cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                    }
                }

                val fillPath = Path().apply {
                    addPath(curvePath)
                    lineTo(primaryCoords.last().x, h)
                    lineTo(primaryCoords.first().x, h)
                    close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(colors.goldPrimary.copy(alpha = 0.28f), Color.Transparent)
                    )
                )

                drawPath(
                    path = curvePath,
                    color = colors.goldPrimary,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                if (showDualCurve && primaryCoords.size > 1) {
                    val estCoords = points.mapIndexed { idx, pt ->
                        val x = idx * stepX
                        val y = h - (((pt.amount * 0.92) / maxVal).toFloat() * (h * 0.85f)) - (h * 0.08f)
                        Offset(x, y)
                    }
                    val estPath = Path().apply {
                        moveTo(estCoords.first().x, estCoords.first().y)
                        for (i in 0 until estCoords.size - 1) {
                            val p0 = estCoords[i]
                            val p1 = estCoords[i + 1]
                            val midX = (p0.x + p1.x) / 2f
                            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }
                    }
                    drawPath(
                        path = estPath,
                        color = Color(0xFF4ADE80),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                primaryCoords.forEach { pt ->
                    drawCircle(color = colors.goldPrimary, radius = 4.dp.toPx(), center = pt)
                    drawCircle(color = colors.cardSurface, radius = 2.dp.toPx(), center = pt)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                points.forEach { pt ->
                    Text(
                        text = pt.label,
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
internal fun KaratDistributionDonutCard(
    isArabic: Boolean,
    slices: List<KaratSalesSlice>
) {
    val colors = MiraTheme.colors
    val palette = listOf(
        Color(0xFFD4AF37),
        Color(0xFF10B981),
        Color(0xFF38BDF8),
        Color(0xFFF59E0B),
        Color(0xFFA855F7)
    )
    val total = slices.sumOf { it.amount }.coerceAtLeast(0.001)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isArabic) "نسبة المبيعات حسب العيار" else "Sales Share by Karat",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Canvas(modifier = Modifier.size(118.dp)) {
                    var startAngle = -90f
                    val strokeWidth = 22.dp.toPx()
                    slices.forEachIndexed { index, slice ->
                        val sweep = ((slice.amount / total) * 360.0).toFloat()
                        drawArc(
                            color = palette[index % palette.size],
                            startAngle = startAngle,
                            sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                            useCenter = false,
                            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                            size = Size(size.width - strokeWidth, size.height - strokeWidth),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                        startAngle += sweep
                    }
                }

                Spacer(modifier = Modifier.width(18.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    slices.forEachIndexed { index, slice ->
                        val pct = (slice.amount / total) * 100.0
                        val sliceColor = palette[index % palette.size]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(sliceColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = slice.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "%.1f%%", pct),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
