@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.uhf.fragment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AssignmentReturn
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.model.CartItem
import com.example.uhf.ui.theme.MiraTheme
import java.util.Locale

@Composable
fun MiraPosScreen(
    isArabic: Boolean,
    scaleStatusText: String,
    itemCount: Int,
    totalAmountText: String,
    cartStatusText: String,
    pendingSalesCount: Int,
    canCheckout: Boolean,
    canRefund: Boolean,
    items: List<CartItem>,
    isRfidListening: Boolean,
    rfidCount: Int,
    onScaleClick: () -> Unit,
    onPendingBadgeClick: () -> Unit,
    onRefundClick: () -> Unit,
    onLastReceiptClick: () -> Unit,
    onCloseClick: () -> Unit,
    onScanRfidClick: () -> Unit,
    onScanRfidLongClick: () -> Unit,
    onScanGs1Click: () -> Unit,
    onManualSubmit: (String) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onClearCart: () -> Unit,
    onCheckoutClick: () -> Unit,
    formatItemPrice: (CartItem) -> String = { item ->
        String.format(Locale.US, "$%,.2f", item.price)
    }
) {
    val colors = MiraTheme.colors
    val focusManager = LocalFocusManager.current
    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    var manualInputText by remember { mutableStateOf("") }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.backgroundTop
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.backgroundTop, colors.backgroundMid, colors.backgroundTop)
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // ═══════════════════════════════════════════════════════
                    // 1. الشريط العلوي التنفيذي (Header Bar)
                    // ═══════════════════════════════════════════════════════
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // مؤشر الميزان الذكي + شارة الأوفلاين
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = colors.cardSurface,
                                border = BorderStroke(1.dp, colors.cardBorder),
                                modifier = Modifier.clickable { onScaleClick() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = scaleStatusText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.goldPrimary
                                    )
                                }
                            }

                            if (pendingSalesCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFEF4444),
                                    modifier = Modifier.clickable { onPendingBadgeClick() }
                                ) {
                                    Text(
                                        text = "📥 $pendingSalesCount",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // أزرار العمليات السريعة (إرجاع، آخر إيصال، إغلاق)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (canRefund) {
                                IconButton(
                                    onClick = onRefundClick,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(colors.cardSurface)
                                        .border(1.dp, colors.cardBorder, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AssignmentReturn,
                                        contentDescription = "Refund",
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onLastReceiptClick,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(colors.cardSurface)
                                    .border(1.dp, colors.cardBorder, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Receipt,
                                    contentDescription = "Last Receipt",
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onCloseClick,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(colors.cardSurface)
                                    .border(1.dp, colors.cardBorder, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close POS",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // ═══════════════════════════════════════════════════════
                    // 2. مركز المسح الثلاثي الذكي والبحث اليدوي
                    // ═══════════════════════════════════════════════════════
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // أزرار التحكم بالماسح (RFID + GS1 Camera)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // زر RFID التفاعلي مع دعم الضغط الطويل
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isRfidListening) {
                                        Color(0xFF10B981).copy(alpha = 0.2f)
                                    } else {
                                        colors.goldPrimary.copy(alpha = 0.15f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isRfidListening) Color(0xFF10B981) else colors.goldPrimary
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .combinedClickable(
                                            onClick = onScanRfidClick,
                                            onLongClick = onScanRfidLongClick
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.RssFeed,
                                            contentDescription = null,
                                            tint = if (isRfidListening) Color(0xFF10B981) else colors.goldPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isRfidListening) {
                                                if (isArabic) "🟢 يستمع ($rfidCount)" else "🟢 Listening ($rfidCount)"
                                            } else {
                                                if (isArabic) "🎯 قارئ RFID" else "🎯 RFID Scanner"
                                            },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                    }
                                }

                                // زر ماسح الكاميرا الدقيق (Micro-Tag)
                                OutlinedButton(
                                    onClick = onScanGs1Click,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, colors.cardBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.QrCodeScanner,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isArabic) "📷 مسح كود/QR" else "📷 Scan Micro-Tag",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }
                            }

                            // حقل البحث اليدوي والرموز الجزئية
                            OutlinedTextField(
                                value = manualInputText,
                                onValueChange = { manualInputText = it },
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        text = if (isArabic) {
                                            "أدخل رمز القطعة أو الرقم التسلسلي..."
                                        } else {
                                            "Enter item code or serial..."
                                        },
                                        fontSize = 12.sp,
                                        color = colors.textSecondary.copy(alpha = 0.5f)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = colors.goldPrimary
                                    )
                                },
                                trailingIcon = {
                                    if (manualInputText.isNotEmpty()) {
                                        IconButton(onClick = {
                                            onManualSubmit(manualInputText)
                                            manualInputText = ""
                                            focusManager.clearFocus()
                                        }) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = "Submit",
                                                tint = Color(0xFF10B981)
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        if (manualInputText.isNotBlank()) {
                                            onManualSubmit(manualInputText)
                                            manualInputText = ""
                                            focusManager.clearFocus()
                                        }
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.goldPrimary,
                                    unfocusedBorderColor = colors.cardBorder,
                                    focusedContainerColor = colors.backgroundTop.copy(alpha = 0.3f),
                                    unfocusedContainerColor = colors.backgroundTop.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // ═══════════════════════════════════════════════════════
                    // 3. عرض عناصر السلة أو الحالة الفارغة
                    // ═══════════════════════════════════════════════════════
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        if (items.isEmpty()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ShoppingCart,
                                    contentDescription = null,
                                    tint = colors.goldPrimary.copy(alpha = 0.4f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = cartStatusText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = items,
                                    key = { index, item ->
                                        val uniqueTag = item.epc?.takeIf { it.isNotBlank() }
                                            ?: item.serialNo?.takeIf { it.isNotBlank() }
                                            ?: item.name.takeIf { it.isNotBlank() }
                                            ?: "item"
                                        "${uniqueTag}_$index"
                                    }
                                ) { index, item ->
                                    CartItemCard(
                                        item = item,
                                        isArabic = isArabic,
                                        priceText = formatItemPrice(item),
                                        onDelete = { onRemoveItem(index) }
                                    )
                                }
                            }
                        }
                    }

                    // ═══════════════════════════════════════════════════════
                    // 4. الشريط السفلي لإتمام البيع (Sticky Footer)
                    // ═══════════════════════════════════════════════════════
                    Surface(
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        color = colors.cardSurface,
                        border = BorderStroke(1.dp, colors.cardBorder),
                        shadowElevation = 12.dp,
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
                                Column {
                                    Text(
                                        text = if (isArabic) "إجمالي القطع: $itemCount" else "Total Items: $itemCount",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = totalAmountText,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black,
                                        color = colors.goldPrimary
                                    )
                                }

                                if (items.isNotEmpty()) {
                                    TextButton(onClick = onClearCart) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteSweep,
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isArabic) "تفريغ السلة" else "Clear Cart",
                                            fontSize = 12.sp,
                                            color = Color(0xFFEF4444),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = onCheckoutClick,
                                enabled = canCheckout,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.goldPrimary,
                                    contentColor = Color(0xFF060B19),
                                    disabledContainerColor = colors.trackBackground,
                                    disabledContentColor = colors.textSecondary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Payment,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isArabic) "إتمام البيع والدفع" else "Complete Checkout",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold
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
private fun CartItemCard(
    item: CartItem,
    isArabic: Boolean,
    priceText: String,
    onDelete: () -> Unit
) {
    val colors = MiraTheme.colors
    val effectiveWeight = if (item.scaleWeight > 0) item.scaleWeight else item.weight

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.name.ifBlank {
                        if (isArabic) "قطعة مجوهرات" else "Jewelry Item"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.karat.ifBlank { "21K" },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.goldPrimary
                    )
                    Text(
                        text = "•",
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = String.format(Locale.US, "%.2fg", effectiveWeight),
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                    if (!item.serialNo.isNullOrEmpty()) {
                        Text(
                            text = "• SN: ${item.serialNo}",
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = priceText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = colors.goldPrimary
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
