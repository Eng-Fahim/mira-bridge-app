package com.example.uhf.ui.activities

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ═══════════════════════════════════════════════════════════════════════
//  Top Bar
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun AllActivitiesTopBar(
    isArabic: Boolean,
    totalCount: Int,
    visibleCount: Int,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onRefresh: () -> Unit
) {
    val colors = MiraTheme.colors
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(colors.backgroundMid.copy(alpha = 0.9f), Color.Transparent)
                )
            )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassIconButton(
                    icon = Icons.Rounded.ArrowBack,
                    contentDescription = if (isArabic) "رجوع" else "Back",
                    onClick = onBack
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isArabic) "جميع العمليات" else "All Activities",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = buildSubtitle(isArabic, totalCount, visibleCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }

                GlassIconButton(
                    icon = Icons.Rounded.FileDownload,
                    contentDescription = if (isArabic) "تصدير" else "Export",
                    onClick = onExport,
                    tint = Color(0xFF10B981)
                )

                GlassIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = if (isArabic) "تحديث" else "Refresh",
                    onClick = onRefresh,
                    tint = colors.primary,
                    spinning = isRefreshing
                )
            }
        }
    }
}

private fun buildSubtitle(isArabic: Boolean, total: Int, visible: Int): String {
    return when {
        total == 0 -> if (isArabic) "لا عمليات" else "No activities"
        total == visible -> if (isArabic) "$total عملية" else "$total activities"
        else -> if (isArabic) "$visible من $total" else "$visible of $total"
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MiraTheme.colors.onSurface,
    spinning: Boolean = false
) {
    val colors = MiraTheme.colors
    val rotation by animateFloatAsState(
        targetValue = if (spinning) 360f else 0f,
        label = "spin"
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.surface.copy(alpha = 0.85f),
        tonalElevation = 1.dp,
        modifier = Modifier.size(42.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .then(
                        if (spinning)
                            Modifier
                                .graphicsLayerRotation(rotation)
                        else Modifier
                    )
            )
        }
    }
}

@Composable
private fun Modifier.graphicsLayerRotation(degrees: Float): Modifier =
    androidx.compose.ui.graphics.graphicsLayer { rotationZ = degrees }

// ═══════════════════════════════════════════════════════════════════════
//  Search Bar
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ActivitySearchBar(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit
) {
    val colors = MiraTheme.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface.copy(alpha = 0.9f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                placeholder,
                                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 15.sp
                            )
                        }
                        inner()
                    }
                }
            )
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Filter Chips
// ═══════════════════════════════════════════════════════════════════════

private data class FilterDef(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val icon: ImageVector,
    val color: Color
)

private val FILTERS = listOf(
    FilterDef("all",       "الكل",     "All",       Icons.Rounded.Apps,           Color(0xFF94A3B8)),
    FilterDef("sale",      "مبيعات",   "Sales",     Icons.Rounded.PointOfSale,    Color(0xFFD4AF37)),
    FilterDef("inventory", "جرد",      "Inventory", Icons.Rounded.Inventory2,     Color(0xFF38BDF8)),
    FilterDef("transfer",  "تحويلات",  "Transfers", Icons.Rounded.SwapHoriz,      Color(0xFFA855F7)),
    FilterDef("rfid",      "RFID",     "RFID",      Icons.Rounded.Radar,          Color(0xFF06B6D4)),
    FilterDef("system",    "نظام",     "System",    Icons.Rounded.Settings,       Color(0xFF64748B))
)

@Composable
fun ActivityFilterChips(
    isArabic: Boolean,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(FILTERS, key = { it.key }) { filter ->
            val isSelected = selected == filter.key
            Surface(
                onClick = { onSelected(filter.key) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) filter.color.copy(alpha = 0.18f)
                        else MiraTheme.colors.surface.copy(alpha = 0.75f),
                border = if (isSelected)
                    androidx.compose.foundation.BorderStroke(1.dp, filter.color.copy(alpha = 0.5f))
                    else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        filter.icon,
                        contentDescription = null,
                        tint = if (isSelected) filter.color else MiraTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isArabic) filter.labelAr else filter.labelEn,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) filter.color else MiraTheme.colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Activity List Item
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ActivityListItem(
    activity: JSONObject,
    isArabic: Boolean,
    onClick: () -> Unit
) {
    val type = activity.optString("type", "").lowercase(Locale.ROOT)
    val (icon, color) = styleForType(type)

    val title = pickLocalized(activity, "title", isArabic)
    val details = pickLocalized(activity, "details", isArabic)
    val user = activity.optString("user", activity.optString("username", ""))
    val amount = activity.optDouble("amount", 0.0)
    val currency = activity.optString("currency", "")
    val timeLabel = formatActivityTime(activity.optString("timestamp", ""), isArabic)

    val colors = MiraTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material.ripple.rememberRipple(),
                onClick = onClick
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(color)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title.ifBlank { type.uppercase(Locale.ROOT) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (details.isNotBlank()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (user.isNotBlank()) {
                            Text("👤 $user", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        if (user.isNotBlank() && timeLabel.isNotBlank()) {
                            Text("•", color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                        if (timeLabel.isNotBlank()) {
                            Text("🕐 $timeLabel", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                if (amount > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = color.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = formatAmount(amount, currency),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = color,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun styleForType(type: String): Pair<ImageVector, Color> = when {
    type.contains("sale") || type.contains("pos") ->
        Icons.Rounded.PointOfSale to Color(0xFFD4AF37)
    type.contains("inventory") || type.contains("stock") ->
        Icons.Rounded.Inventory2 to Color(0xFF38BDF8)
    type.contains("transfer") || type.contains("swap") ->
        Icons.Rounded.SwapHoriz to Color(0xFFA855F7)
    type.contains("rfid") || type.contains("scan") || type.contains("gate") ->
        Icons.Rounded.Radar to Color(0xFF06B6D4)
    type.contains("system") || type.contains("login") || type.contains("logout") ->
        Icons.Rounded.Settings to Color(0xFF64748B)
    else -> Icons.Rounded.Info to Color(0xFF94A3B8)
}

private fun pickLocalized(obj: JSONObject, prefix: String, isArabic: Boolean): String {
    val ar = obj.optString("${prefix}_ar", "")
    val en = obj.optString("${prefix}_en", "")
    val plain = obj.optString(prefix, "")
    return when {
        isArabic && ar.isNotBlank() -> ar
        !isArabic && en.isNotBlank() -> en
        plain.isNotBlank() -> plain
        ar.isNotBlank() -> ar
        else -> en
    }
}

private fun formatAmount(amount: Double, currency: String): String {
    val nf = NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }
    val num = nf.format(amount)
    return if (currency.isBlank()) num else "$num $currency"
}

private fun formatActivityTime(timestamp: String, isArabic: Boolean): String {
    if (timestamp.isBlank()) return ""
    val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    )
    var parsed: Date? = null
    for (f in formats) {
        try {
            val sdf = SimpleDateFormat(f, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            parsed = sdf.parse(timestamp); if (parsed != null) break
        } catch (_: Exception) {}
    }
    if (parsed == null) return timestamp

    val diff = System.currentTimeMillis() - parsed.time
    return when {
        diff < 0 -> timestamp
        diff < 60_000 -> if (isArabic) "الآن" else "just now"
        diff < 3_600_000 -> "${diff / 60_000}${if (isArabic) " د" else "m"}"
        diff < 86_400_000 -> "${diff / 3_600_000}${if (isArabic) " س" else "h"}"
        diff < 604_800_000 -> "${diff / 86_400_000}${if (isArabic) " ي" else "d"}"
        else -> SimpleDateFormat("MMM d", if (isArabic) Locale("ar") else Locale.US).format(parsed)
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Empty / Loading / Error / End of List
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ActivityEmptyState(
    isArabic: Boolean,
    icon: String,
    title: String,
    subtitle: String
) {
    val colors = MiraTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = icon, fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
fun ActivityLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "…",
                style = MaterialTheme.typography.bodySmall,
                color = MiraTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ActivityErrorState(
    isArabic: Boolean,
    message: String,
    onRetry: () -> Unit
) {
    val colors = MiraTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFFEF4444).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFEF4444),
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isArabic) "حدث خطأ" else "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            onClick = onRetry,
            shape = RoundedCornerShape(50),
            color = colors.primary
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(
                    if (isArabic) "إعادة المحاولة" else "Retry",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun EndOfListBadge(isArabic: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isArabic) "— نهاية العمليات —" else "— End of activities —",
            style = MaterialTheme.typography.labelSmall,
            color = MiraTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
