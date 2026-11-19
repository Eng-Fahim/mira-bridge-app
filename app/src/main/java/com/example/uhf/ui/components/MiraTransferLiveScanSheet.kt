package com.example.uhf.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.ui.theme.MiraTheme
import java.util.Locale

data class ScannedTransferPiece(
    val code: String,
    val title: String = "",
    val karat: String = "21K",
    val weightGrams: Double = 0.0,
    val source: String = "MICRO-CAM"
) {
    val fine24kGrams: Double
        get() {
            val factor = when {
                karat.contains("24") -> 1.0
                karat.contains("22") -> 22.0 / 24.0
                karat.contains("21") -> 21.0 / 24.0
                karat.contains("18") -> 18.0 / 24.0
                else -> 21.0 / 24.0
            }
            return weightGrams * factor
        }
}

@Composable
fun MiraTransferLiveScanCard(
    items: List<ScannedTransferPiece>,
    manualInput: String,
    isScanningRfid: Boolean,
    isSaving: Boolean,
    isArabic: Boolean,
    onManualInputChange: (String) -> Unit,
    onManualAdd: () -> Unit,
    onOpenMicroCamera: () -> Unit,
    onToggleRfidSweep: () -> Unit,
    onRemoveItemAt: (Int) -> Unit,
    onConfirmTransfer: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MiraTheme.colors

    val totalWeight = remember(items) { items.sumOf { it.weightGrams } }
    val totalFine24k = remember(items) { items.sumOf { it.fine24kGrams } }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1224)),
        border = BorderStroke(1.25.dp, colors.goldPrimary.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // العنوان وحالة المسح
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isArabic) "سجل تدقيق ومسح الشحنة المباشر" else "Live Transfer Audit & Scan Log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = if (isArabic)
                            "دعم الأكواد المجهرية 5×5 مم + باركود الليزر + RFID"
                        else
                            "5×5mm Micro-Codes + Laser 2D + UHF RFID",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF38BDF8)
                    )
                }

                FilledTonalButton(
                    onClick = onToggleRfidSweep,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isScanningRfid) Color(0xFF10B981).copy(alpha = 0.2f)
                        else colors.goldPrimary.copy(alpha = 0.16f),
                        contentColor = if (isScanningRfid) Color(0xFF10B981) else colors.goldPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sensors,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isScanningRfid) {
                            if (isArabic) "إيقاف RFID" else "Stop RFID"
                        } else {
                            if (isArabic) "مسح RFID" else "RFID Scan"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            // شريط الإجماليات الفورية (عدد القطع، الوزن القائم، عيار 24)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill(
                    label = if (isArabic) "القطع" else "Pieces",
                    value = items.size.toString(),
                    accent = colors.goldPrimary,
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = if (isArabic) "الوزن (جم)" else "Gross (g)",
                    value = String.format(Locale.US, "%.2f", totalWeight),
                    accent = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = if (isArabic) "صافي 24" else "24K Fine",
                    value = String.format(Locale.US, "%.3f", totalFine24k),
                    accent = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
            }

            // صف الإدخال + زر الكاميرا المجهرية 5x5mm + زر الإضافة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = onManualInputChange,
                    singleLine = true,
                    enabled = !isSaving,
                    label = {
                        Text(
                            text = if (isArabic) "EPC / Serial / باركود مجهري" else "EPC / Serial / Micro-Code",
                            fontSize = 12.sp
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.goldPrimary,
                        unfocusedBorderColor = Color(0xFF263554),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = colors.goldPrimary,
                        unfocusedLabelColor = Color(0xFF94A3B8)
                    ),
                    modifier = Modifier.weight(1f)
                )

                FilledTonalButton(
                    onClick = onOpenMicroCamera,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.goldPrimary.copy(alpha = 0.22f),
                        contentColor = colors.goldPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Button(
                    onClick = onManualAdd,
                    enabled = !isSaving && manualInput.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF38BDF8),
                        contentColor = Color(0xFF060C18)
                    )
                ) {
                    Text(
                        text = if (isArabic) "إضافة" else "Add",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1E2D4A))

            // قائمة القطع الممسوحة مباشرة مع العيار والوزن
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F1930)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isArabic)
                            "لم يتم مسح أي قطعة بعد • استخدم الكاميرا المجهرية أو RFID"
                        else
                            "No pieces scanned yet • Use Micro Camera or RFID",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(items, key = { index, item -> "${item.code}_$index" }) { index, piece ->
                        ScannedPieceRow(
                            index = index + 1,
                            piece = piece,
                            isArabic = isArabic,
                            onRemove = { onRemoveItemAt(index) }
                        )
                    }
                }
            }

            // أزرار الاعتماد والإغلاق
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving
                ) {
                    Text(
                        text = if (isArabic) "إغلاق" else "Close",
                        color = Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onConfirmTransfer,
                    enabled = !isSaving && items.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        contentColor = Color(0xFF05110C)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic)
                            "اعتماد الشحنة (${items.size})"
                        else
                            "Confirm Transfer (${items.size})",
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111C35),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = accent
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun ScannedPieceRow(
    index: Int,
    piece: ScannedTransferPiece,
    isArabic: Boolean,
    onRemove: () -> Unit
) {
    val colors = MiraTheme.colors

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF121F3B),
        border = BorderStroke(1.dp, Color(0xFF1F3054))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.goldPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.goldPrimary
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (piece.title.isNotBlank()) piece.title else piece.code,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${piece.code} • ${piece.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (piece.weightGrams > 0.0) {
                        String.format(Locale.US, "%.2f جم • %s", piece.weightGrams, piece.karat)
                    } else {
                        piece.karat
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.goldPrimary
                )
                if (piece.weightGrams > 0.0) {
                    Text(
                        text = if (isArabic) {
                            String.format(Locale.US, "24K: %.3f جم", piece.fine24kGrams)
                        } else {
                            String.format(Locale.US, "24K: %.3fg", piece.fine24kGrams)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF10B981)
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
