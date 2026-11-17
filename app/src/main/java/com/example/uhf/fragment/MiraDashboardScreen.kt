package com.example.uhf.ui.fragment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun MiraDashboardScreen(
    totalWeight: Double,
    totalValueUsd: Double,
    cashBalanceYer: Double,
    karatBalances: List<KaratDataModel>,
    onToggleVisibility: () -> Unit,
    isVisible: Boolean,
    onQuickActionClick: (String) -> Unit
) {
    // تدرج بطاقة الهيرو الملكية (كحلي عميق إلى برونزي وذهبي لامع)
    val goldCardBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF1E283A),
            Color(0xFF7A5D1E),
            Color(0xFFC59B27)
        )
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. بطاقة الهيرو الفاخرة للرصيد
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(goldCardBrush)
                            .padding(20.dp)
                    ) {
                        Column {
                            // زر إخفاء/إظهار القيم + العنوان
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = onToggleVisibility,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.2f))
                                ) {
                                    Icon(
                                        imageVector = if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle Visibility",
                                        tint = Color.White
                                    )
                                }
                                Text(
                                    text = "رصيد الذهب الصافي",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // الوزن الإجمالي البارز
                            Row(
                                modifier = Modifier.align(Alignment.End),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    text = "جم",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp, end = 4.dp)
                                )
                                Text(
                                    text = if (isVisible) String.format(Locale.US, "%,.2f", totalWeight) else "••••••",
                                    color = Color.White,
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                            Spacer(Modifier.height(14.dp))

                            // عنوان تفصيل العيارات
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${karatBalances.size} عيار",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "تفصيل العيارات",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            // كروت العيارات الزجاجية المصغرة
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                karatBalances.forEach { karat ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color.White.copy(alpha = 0.15f))
                                            .padding(12.dp)
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(18.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFFFFD700).copy(alpha = 0.4f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = karat.karat.toString(),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFFFD700)
                                                    )
                                                }
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = "عيار ${karat.karat}",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Spacer(Modifier.height(8.dp))

                                            Text(
                                                text = if (isVisible) String.format(Locale.US, "%.2f جم", karat.weight) else "•••",
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // الرصيد النقدي والقيمة الحالية في الأسفل
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("الرصيد النقدي", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                    Text(
                                        text = if (isVisible) String.format(Locale.US, "%,.2f YER", cashBalanceYer) else "••••",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("القيمة الحالية", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                    Text(
                                        text = if (isVisible) String.format(Locale.US, "$%,.2f", totalValueUsd) else "••••",
                                        color = Color(0xFFFFD700),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. الأزرار السريعة
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionButton("🛒", "بيع", Modifier.weight(1f)) { onQuickActionClick("sell") }
                    QuickActionButton("⚖️", "شراء كسر", Modifier.weight(1f)) { onQuickActionClick("scrap") }
                    QuickActionButton("📋", "التقارير", Modifier.weight(1f)) { onQuickActionClick("reports") }
                }
            }
        }
    }
}

@Composable
fun QuickActionButton(emoji: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = emoji, fontSize = 24.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class KaratDataModel(val karat: Int, val weight: Double)
