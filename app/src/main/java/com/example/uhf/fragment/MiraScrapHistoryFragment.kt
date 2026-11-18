package com.example.uhf.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class MiraScrapHistoryFragment : Fragment() {

    companion object {
        private const val TAG = "ScrapHistoryFragment"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ScrapHistory-Net").apply { isDaemon = true }
    }

    private var isArabic: Boolean = true

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive States
    // ═══════════════════════════════════════════════════════════════════

    private var isLoadingState by mutableStateOf(false)
    private var errorMessageState by mutableStateOf<String?>(null)
    private var historyItemsState = mutableStateListOf<JSONObject>()

    private fun checkIsArabic(): Boolean {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    ScrapHistoryScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isArabic = checkIsArabic()
        loadHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!networkExecutor.isShutdown) {
            networkExecutor.shutdownNow()
        }
    }

    private fun loadHistory() {
        if (!isAdded || networkExecutor.isShutdown) return

        val ctx = context ?: return
        isLoadingState = true
        errorMessageState = null

        val branchId = MiraBranchesManager.getInstance(ctx).getActiveBranchId()

        networkExecutor.execute {
            try {
                val api = MiraApiClient.getInstance(ctx)
                val response = api.getWithAuth("/scrap/history?branch_id=$branchId&limit=100")

                if (response != null && response.isSuccess && response.data != null) {
                    val dataArr = response.data!!.optJSONArray("data")
                        ?: response.data!!.optJSONArray("items")

                    val newList = ArrayList<JSONObject>()
                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            val obj = dataArr.optJSONObject(i)
                            if (obj != null) newList.add(obj)
                        }
                    }

                    mainHandler.post {
                        if (!isAdded) return@post
                        isLoadingState = false
                        historyItemsState.clear()
                        historyItemsState.addAll(newList)
                    }
                } else {
                    mainHandler.post {
                        if (!isAdded) return@post
                        isLoadingState = false
                        errorMessageState = if (isArabic) "تعذر جلب سجلات الشراء" else "Failed to load scrap history"
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isAdded) return@post
                    isLoadingState = false
                    errorMessageState = if (isArabic) "خطأ في الاتصال بالسيرفر" else "Server connection error"
                }
            }
        }
    }

    private fun openReceipt(item: JSONObject) {
        try {
            val purchaseId = item.optInt("id", item.optInt("purchase_id", 0))
            if (purchaseId > 0) {
                val receipt = MiraScrapReceiptFragment.newInstance(purchaseId, item.toString())
                receipt.show(parentFragmentManager, "ScrapReceipt")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun ScrapHistoryScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic

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
                    // 1. الشريط العلوي
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ReceiptLong,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isAr) "سجلات شراء الكسر" else "Scrap Purchase History",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isAr) "استعراض ومراجعة عمليات الشراء السابقة" else "Review past purchases",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val infiniteTransition = rememberInfiniteTransition(label = "history_spin")
                        val spinAngle by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(900, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "history_spin_angle"
                        )

                        Surface(
                            modifier = Modifier
                                .size(38.dp)
                                .clickable(enabled = !isLoadingState) { loadHistory() },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.cardSurface,
                            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(if (isLoadingState) spinAngle else 0f)
                                )
                            }
                        }
                    }

                    // رسالة خطأ إن وجدت
                    errorMessageState?.let { err ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFCA5A5),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { loadHistory() }) {
                                    Text(text = if (isAr) "إعادة المحاولة" else "Retry", color = colors.goldPrimary)
                                }
                            }
                        }
                    }

                    // 2. قائمة السجلات أو حالة الفراغ
                    if (historyItemsState.isEmpty() && !isLoadingState) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(colors.goldPrimary.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Inbox,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Text(
                                    text = if (isAr) "📭 لا توجد عمليات شراء كسر مسجلة" else "📭 No scrap purchases recorded",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = if (isAr) "ستظهر عمليات الشراء هنا فور إتمامها" else "Purchases will appear here once saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(historyItemsState, key = { it.optInt("id", it.optInt("purchase_id", it.hashCode())) }) { item ->
                                ScrapHistoryCard(
                                    item = item,
                                    isArabic = isAr,
                                    onClick = { openReceipt(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScrapHistoryCard(
        item: JSONObject,
        isArabic: Boolean,
        onClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        val purchaseNumber = item.optString("purchase_number", "#---")
        val customerName = item.optString("customer_name", if (isArabic) "عميل نقدي" else "Cash Customer")
        val grossWeight = item.optDouble("gross_weight_grams", item.optDouble("weight_grams", 0.0))
        val karat = item.optString("gold_karat", item.optString("karat", "21K"))
        val totalAmount = item.optDouble("total_amount", item.optDouble("total", 0.0))
        val currency = item.optString("currency", "SAR")
        val dateStr = item.optString("created_at", item.optString("date", ""))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.28f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.goldPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Diamond,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = purchaseNumber,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary
                            )
                            if (dateStr.isNotEmpty()) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = dateStr.take(10),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = customerName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = String.format(Locale.US, "%s | %.2f g", karat, grossWeight),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "%,.2f", totalAmount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF10B981)
                    )
                    Text(
                        text = currency,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}
