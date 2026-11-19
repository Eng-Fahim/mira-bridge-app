package com.example.uhf.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.fragment.MiraAllActivitiesFragment
import com.example.uhf.fragment.MiraInventoryProFragment
import com.example.uhf.fragment.MiraPosFragment
import com.example.uhf.fragment.MiraReceiptFragment
import com.example.uhf.fragment.MiraSalesHistoryFragment
import com.example.uhf.fragment.MiraTransferFragment
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ActivityDetailsBottomSheet : BottomSheetDialogFragment() {

    interface OnActionClickListener {
        fun onPrintInvoice(activityData: JSONObject)
        fun onOpenRefund(activityData: JSONObject)
    }

    private data class DetailEntry(
        val label: String,
        val value: String,
        val icon: ImageVector,
        val valueColor: Color? = null
    )

    private data class SoldItemDisplay(
        val index: Int,
        val title: String,
        val serial: String,
        val epc: String,
        val karat: String,
        val weightText: String?,
        val priceText: String?
    )

    private data class PaymentLegDisplay(
        val methodLabel: String,
        val amountText: String,
        val color: Color
    )

    private data class ActionSpec(
        val label: String,
        val icon: ImageVector,
        val tint: Color,
        val isPrimary: Boolean = false,
        val onClick: () -> Unit
    )

    private var initialActivity: JSONObject = JSONObject()
    private var isArabic: Boolean = true
    private var actionListener: OnActionClickListener? = null

    fun setOnActionClickListener(listener: OnActionClickListener?) {
        this.actionListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = sPendingActivity
        if (pending != null) {
            initialActivity = pending
            sPendingActivity = null
        } else {
            initialActivity = JSONObject()
        }
        isArabic = arguments?.getBoolean(ARG_IS_ARABIC, true) ?: true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val direction = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    MiraJewelryTheme {
                        ActivityDetailsSheetContent(
                            baseActivity = initialActivity,
                            isArabic = isArabic,
                            onClose = { dismiss() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sPendingActivity = null
    }

    @Composable
    private fun ActivityDetailsSheetContent(
        baseActivity: JSONObject,
        isArabic: Boolean,
        onClose: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val context = LocalContext.current
        val scrollState = rememberScrollState()

        var activityState by remember(baseActivity) { mutableStateOf(baseActivity) }
        var isHydratingDetails by remember { mutableStateOf(false) }

        val saleNoForFetch: String = remember(baseActivity) {
            baseActivity.optString(
                "sale_number",
                baseActivity.optString("invoice_number", baseActivity.optString("sale_id", ""))
            ).trim()
        }

        LaunchedEffect(saleNoForFetch) {
            val existingItems = activityState.optJSONArray("items")
                ?: activityState.optJSONArray("sold_items")
            if (saleNoForFetch.isNotEmpty() && (existingItems == null || existingItems.length() == 0)) {
                isHydratingDetails = true
                val enriched: JSONObject? = withContext(Dispatchers.IO) {
                    try {
                        val resp = MiraApiClient.getInstance(context).getSaleDetailsByNumber(saleNoForFetch)

                        if (resp != null && resp.isSuccess && resp.data != null) {
                            val saleObj = resp.data.optJSONObject("sale")
                                ?: resp.data.optJSONObject("data")?.optJSONObject("sale")
                                ?: resp.data.optJSONObject("data")
                                ?: resp.data
                            mergeJsonObjects(activityState, saleObj)
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (enriched != null) {
                    activityState = enriched
                }
                isHydratingDetails = false
            }
        }

        val activity = activityState
        val actionKey: String = remember(activity) {
            activity.optString("action", "")
                .ifBlank { activity.optString("type", "") }
                .uppercase(Locale.US)
        }
        val normalizedType: String = remember(actionKey, activity, saleNoForFetch) {
            resolveNormalizedType(actionKey, activity.optString("type", ""), saleNoForFetch)
        }

        val statusRaw = activity.optString("refund_status", activity.optString("status", "completed"))
            .lowercase(Locale.ROOT)
        val isFullyRefunded = statusRaw == "refunded" || statusRaw == "fully_refunded"
        val isPartiallyRefunded = statusRaw == "partially_refunded"

        val statusPair: Pair<String, Color> = when {
            isFullyRefunded -> Pair(if (isArabic) "مرتجع كلي" else "Fully Refunded", Color(0xFFEF4444))
            isPartiallyRefunded -> Pair(if (isArabic) "مرتجع جزئي" else "Partially Refunded", Color(0xFFF59E0B))
            normalizedType == "sale" || normalizedType == "refund" ->
                Pair(if (isArabic) "فاتورة مكتملة" else "Completed", Color(0xFF10B981))
            else -> Pair(if (isArabic) "مكتمل" else "Completed", Color(0xFF10B981))
        }
        val statusText = statusPair.first
        val statusColor = statusPair.second

        val title: String = remember(activity, saleNoForFetch, isArabic) {
            if (saleNoForFetch.isNotEmpty()) {
                if (isArabic) "فاتورة مبيعات #$saleNoForFetch" else "Sales Invoice #$saleNoForFetch"
            } else {
                val raw = if (isArabic) activity.optString("title_ar", activity.optString("title", ""))
                else activity.optString("title_en", activity.optString("title", ""))
                raw.ifBlank { if (isArabic) "تفاصيل العملية" else "Operation Details" }
            }
        }

        val formattedTime: String = remember(activity, isArabic) {
            val ts = activity.optString("created_at", activity.optString("timestamp", ""))
            formatFullTime(ts, isArabic)
        }

        val detailRows: List<DetailEntry> = remember(activity, isArabic, normalizedType) {
            buildDetailRows(activity, normalizedType, isArabic)
        }
        val paymentLegs: List<PaymentLegDisplay> = remember(activity, isArabic) {
            extractPaymentLegs(activity, isArabic)
        }
        val soldItems: List<SoldItemDisplay> = remember(activity, isArabic) {
            extractSoldItems(activity, isArabic)
        }
        val actions: List<ActionSpec> = remember(activity, normalizedType, isFullyRefunded, isArabic) {
            buildActionSpecs(activity, normalizedType, isFullyRefunded, isArabic)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = colors.backgroundTop
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.cardSurface, colors.backgroundTop)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.textSecondary.copy(alpha = 0.35f))
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(colors.goldPrimary.copy(alpha = 0.16f))
                            .border(1.dp, colors.goldPrimary.copy(alpha = 0.4f), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveHeaderIcon(normalizedType),
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(25.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = statusColor.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = statusText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = formattedTime,
                                fontSize = 11.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = colors.cardBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FinancialSummaryHeroBox(
                        activity = activity,
                        isArabic = isArabic,
                        paymentLegs = paymentLegs
                    )

                    if (isFullyRefunded || isPartiallyRefunded) {
                        val reason = activity.optString("refund_reason", "").trim()
                        val refundedAt = activity.optString("refunded_at", "").trim()
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = statusColor.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Autorenew,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = if (isArabic) "بيانات الاسترجاع" else "Refund Information",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = statusColor
                                    )
                                    if (reason.isNotEmpty()) {
                                        Text(
                                            text = (if (isArabic) "السبب: " else "Reason: ") + reason,
                                            fontSize = 12.sp,
                                            color = colors.textPrimary
                                        )
                                    }
                                    if (refundedAt.isNotEmpty()) {
                                        Text(
                                            text = (if (isArabic) "تاريخ الاسترجاع: " else "Refunded At: ") +
                                                formatFullTime(refundedAt, isArabic),
                                            fontSize = 11.sp,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    detailRows.forEach { row ->
                        DetailInfoCard(row = row)
                    }

                    if (isHydratingDetails) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.goldPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isArabic) "جاري تحميل تفاصيل القطع..." else "Loading item details...",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                    } else if (soldItems.isNotEmpty()) {
                        Text(
                            text = if (isArabic) "القطع المرتبطة بالعملية (${soldItems.size})"
                            else "Invoice Items (${soldItems.size})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary
                        )

                        soldItems.forEach { sold ->
                            SoldItemCard(sold = sold, isArabic = isArabic)
                        }
                    }

                    if (actions.isNotEmpty()) {
                        Text(
                            text = if (isArabic) "الإجراءات" else "Actions",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary
                        )

                        actions.forEach { spec ->
                            ActionButtonCard(spec = spec)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }

    @Composable
    private fun FinancialSummaryHeroBox(
        activity: JSONObject,
        isArabic: Boolean,
        paymentLegs: List<PaymentLegDisplay>
    ) {
        val colors = MiraTheme.colors
        val rawCurrency = activity.optString("currency", "YER")
        val currency = if (rawCurrency.isBlank() || rawCurrency.equals("MIX", true)) "YER"
        else rawCurrency.uppercase(Locale.US)

        val rate = activity.optDouble("exchange_rate", 0.0)
        val usdVal = activity.optDouble("final_amount_usd", activity.optDouble("total_amount_usd", 0.0))
        val localVal = activity.optDouble(
            "final_amount",
            activity.optDouble("total_amount", activity.optDouble("amount", 0.0))
        )

        if (localVal <= 0.0 && usdVal <= 0.0) return

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = colors.cardSurface,
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                colors.goldPrimary.copy(alpha = 0.15f),
                                Color(0xFF10B981).copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isArabic) "إجمالي قيمة العملية" else "Total Operation Value",
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (currency == "USD" || localVal <= 0.0) {
                            String.format(Locale.US, "$%,.2f", usdVal)
                        } else {
                            formatAmount(localVal, currency)
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.goldPrimary
                    )

                    if (currency != "USD" && usdVal > 0.0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = String.format(Locale.US, "$%,.2f USD", usdVal),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF10B981),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (rate > 0.0 && currency != "USD") {
                    Text(
                        text = if (isArabic) "سعر الصرف المعتمد: 1 USD = ${String.format(Locale.US, "%,.0f", rate)} $currency"
                        else "Exchange Rate: 1 USD = ${String.format(Locale.US, "%,.0f", rate)} $currency",
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }

                if (paymentLegs.isNotEmpty()) {
                    HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.15f))
                    Text(
                        text = if (isArabic) "تفاصيل السداد:" else "Payment Breakdown:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textSecondary
                    )
                    paymentLegs.forEach { leg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• ${leg.methodLabel}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = leg.color
                            )
                            Text(
                                text = leg.amountText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailInfoCard(row: DetailEntry) {
        val colors = MiraTheme.colors
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.cardSurface.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, colors.cardBorder)
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
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.goldPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = row.icon,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = row.value,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = row.valueColor ?: colors.textPrimary
                    )
                }
            }
        }
    }

    @Composable
    private fun SoldItemCard(sold: SoldItemDisplay, isArabic: Boolean) {
        val colors = MiraTheme.colors
        val context = LocalContext.current

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            color = colors.cardSurface.copy(alpha = 0.78f),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.28f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#${sold.index} • ${sold.title}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (sold.karat.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.goldPrimary.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = if (sold.karat.endsWith("K", true)) sold.karat else "${sold.karat}K",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (sold.serial.isNotEmpty()) {
                    Text(
                        text = (if (isArabic) "الرقم التسلسلي: " else "Serial: ") + sold.serial,
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }

                if (sold.epc.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("EPC", sold.epc))
                            Toast.makeText(
                                context,
                                if (isArabic) "تم نسخ رمز RFID" else "RFID EPC copied",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Contactless,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = sold.epc,
                            fontSize = 11.sp,
                            color = Color(0xFF10B981),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                val metaLine = listOfNotNull(sold.weightText, sold.priceText).joinToString("  •  ")
                if (metaLine.isNotEmpty()) {
                    Text(
                        text = metaLine,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.goldPrimary
                    )
                }
            }
        }
    }

    @Composable
    private fun ActionButtonCard(spec: ActionSpec) {
        val colors = MiraTheme.colors
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = spec.onClick),
            shape = RoundedCornerShape(14.dp),
            color = if (spec.isPrimary) spec.tint.copy(alpha = 0.16f) else colors.cardSurface,
            border = BorderStroke(1.dp, spec.tint.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = spec.tint,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = spec.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (spec.isPrimary) spec.tint else colors.textPrimary
                    )
                }
                Icon(
                    imageVector = if (isArabic) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = spec.tint.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    private fun extractPaymentLegs(activity: JSONObject, isArabic: Boolean): List<PaymentLegDisplay> {
        val list = mutableListOf<PaymentLegDisplay>()
        val payments = activity.optJSONArray("payments")
        if (payments != null) {
            for (i in 0 until payments.length()) {
                val p = payments.optJSONObject(i) ?: continue
                val method = p.optString("method", p.optString("payment_method", "CASH")).uppercase(Locale.ROOT)
                val curr = p.optString("currency", "YER").uppercase(Locale.ROOT)
                val amt = p.optDouble("amount", p.optDouble("amount_local", p.optDouble("amount_usd", 0.0)))
                val label = when {
                    method == "CASH" -> if (isArabic) "نقداً ($curr)" else "Cash ($curr)"
                    method.contains("CARD") || method.startsWith("POS") -> if (isArabic) "بطاقة / شبكة" else "Card"
                    method.contains("TRANSFER") || method.startsWith("BANK") -> if (isArabic) "تحويل بنكي" else "Transfer"
                    method.contains("WALLET") -> if (isArabic) "محفظة إلكترونية" else "E-Wallet"
                    else -> method
                }
                if (amt > 0.0) {
                    list.add(
                        PaymentLegDisplay(
                            methodLabel = label,
                            amountText = formatAmount(amt, curr),
                            color = Color(0xFF10B981)
                        )
                    )
                }
            }
        }
        val tradeIns = activity.optJSONArray("trade_ins") ?: activity.optJSONArray("gold_payments")
        if (tradeIns != null && tradeIns.length() > 0) {
            val tradeTotal = activity.optDouble("trade_in_total", 0.0)
            list.add(
                PaymentLegDisplay(
                    methodLabel = if (isArabic) "استبدال ذهب كسر (${tradeIns.length()} قطعة)" else "Gold Trade-In (${tradeIns.length()})",
                    amountText = if (tradeTotal > 0.0) formatAmount(tradeTotal, "USD") else "🥇",
                    color = Color(0xFFD97706)
                )
            )
        }
        return list
    }

    private fun buildDetailRows(
        activity: JSONObject,
        normalizedType: String,
        isArabic: Boolean
    ): List<DetailEntry> {
        val rows = mutableListOf<DetailEntry>()

        val customer = activity.optString("customer_name", activity.optString("client_name", "")).trim()
        if (customer.isNotEmpty()) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "العميل" else "Customer",
                    value = customer,
                    icon = Icons.Rounded.Person
                )
            )
        }

        val customerPhone = activity.optString("customer_phone", "").trim()
        if (customerPhone.isNotEmpty() && customerPhone != "null") {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "هاتف العميل" else "Customer Phone",
                    value = customerPhone,
                    icon = Icons.Rounded.Phone
                )
            )
        }

        val user = activity.optString(
            "cashier_name",
            activity.optString("user", activity.optString("username", ""))
        ).trim()
        if (user.isNotEmpty() && user != "—") {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "الموظف المنفذ" else "Processed By",
                    value = user,
                    icon = Icons.Rounded.Badge
                )
            )
        }

        val branchName = activity.optString("branch_name", "").trim()
        if (branchName.isNotEmpty() && branchName != "null") {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "الفرع" else "Branch",
                    value = branchName,
                    icon = Icons.Rounded.Store
                )
            )
        }

        val itemsArr = activity.optJSONArray("items") ?: activity.optJSONArray("sold_items")
        val itemCount = activity.optInt("item_count", itemsArr?.length() ?: 0)
        if (itemCount > 0) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "عدد القطع" else "Items Count",
                    value = "$itemCount " + (if (isArabic) "قطع" else "pcs"),
                    icon = Icons.Rounded.Inventory2,
                    valueColor = Color(0xFF06B6D4)
                )
            )
        }

        val weight = activity.optDouble("total_weight", activity.optDouble("weight", 0.0))
        if (weight > 0.0) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "إجمالي الوزن" else "Total Weight",
                    value = String.format(Locale.US, "%.2f g", weight),
                    icon = Icons.Rounded.Scale
                )
            )
        }

        if (normalizedType != "sale" && normalizedType != "refund") {
            val details = (if (isArabic) activity.optString("details_ar", activity.optString("details", ""))
            else activity.optString("details_en", activity.optString("details", ""))).trim()
            if (details.isNotEmpty() && !details.startsWith("{")) {
                rows.add(
                    DetailEntry(
                        label = if (isArabic) "بيان العملية" else "Description",
                        value = details,
                        icon = Icons.Rounded.Notes
                    )
                )
            }
        }

        return rows
    }

    private fun extractSoldItems(activity: JSONObject, isArabic: Boolean): List<SoldItemDisplay> {
        val itemsArr: JSONArray = activity.optJSONArray("items")
            ?: activity.optJSONArray("sold_items")
            ?: return emptyList()
        val result = mutableListOf<SoldItemDisplay>()
        val currencyCode = activity.optString("currency", "YER")
        val limit = minOf(itemsArr.length(), 40)

        for (i in 0 until limit) {
            val item = itemsArr.optJSONObject(i) ?: continue
            val itemTitle = item.optString("title")
                .ifBlank { item.optString("item_title") }
                .ifBlank { (if (isArabic) "قطعة مجوهرات" else "Jewelry Item") + " #${i + 1}" }

            val epc = item.optString("epc").ifBlank { item.optString("rfid_epc") }.trim()
            val serial = item.optString("serial").ifBlank { item.optString("serial_number") }.trim()
            val karat = item.optString("karat").ifBlank { item.optString("gold_karat") }.trim()
            val weight = item.optDouble("weight", item.optDouble("gross_weight_grams", 0.0))
            val priceUsd = item.optDouble("price_usd", item.optDouble("final_price_usd", 0.0))
            val priceLocal = item.optDouble("price_local", item.optDouble("final_price", 0.0))

            val weightText = if (weight > 0.0) String.format(Locale.US, "%.2f g", weight) else null
            val priceText = when {
                priceLocal > 0.0 && priceUsd > 0.0 && !currencyCode.equals("USD", true) ->
                    "${formatAmount(priceLocal, currencyCode)} ($${String.format(Locale.US, "%,.2f", priceUsd)})"
                priceLocal > 0.0 -> formatAmount(priceLocal, currencyCode)
                priceUsd > 0.0 -> String.format(Locale.US, "$%,.2f", priceUsd)
                else -> null
            }

            result.add(
                SoldItemDisplay(
                    index = i + 1,
                    title = itemTitle,
                    serial = serial,
                    epc = epc,
                    karat = karat,
                    weightText = weightText,
                    priceText = priceText
                )
            )
        }
        return result
    }

    private fun buildActionSpecs(
        activity: JSONObject,
        normalizedType: String,
        isFullyRefunded: Boolean,
        isArabic: Boolean
    ): List<ActionSpec> {
        val list = mutableListOf<ActionSpec>()

        if (normalizedType == "sale" || normalizedType == "refund") {
            list.add(
                ActionSpec(
                    label = if (isArabic) "عرض وطباعة الفاتورة" else "View & Print Invoice",
                    icon = Icons.Rounded.Print,
                    tint = Color(0xFF10B981),
                    isPrimary = true
                ) {
                    val hostAct = getActivity()
                    val fm = parentFragmentManager
                    dismiss()
                    val listener = actionListener
                    if (listener != null) {
                        listener.onPrintInvoice(activity)
                    } else if (hostAct is UHFMainActivity) {
                        hostAct.openReceiptFragment(activity.toString())
                    } else if (!fm.isStateSaved) {
                        MiraReceiptFragment.newInstance(activity.toString())
                            .show(fm, "MiraReceiptFragment")
                    }
                }
            )

            if (!isFullyRefunded) {
                list.add(
                    ActionSpec(
                        label = if (isArabic) "إجراء استرجاع للفاتورة" else "Process Invoice Refund",
                        icon = Icons.Rounded.AssignmentReturn,
                        tint = Color(0xFFF59E0B)
                    ) {
                        val hostAct = getActivity()
                        dismiss()
                        val listener = actionListener
                        if (listener != null) {
                            listener.onOpenRefund(activity)
                        } else if (hostAct is UHFMainActivity) {
                            val salesFragment = MiraPosFragment().apply {
                                arguments = Bundle().apply {
                                    putString("refund_data_json", activity.toString())
                                    putBoolean("auto_open_refund", true)
                                }
                            }
                            hostAct.loadFragment(salesFragment, "Sales")
                        }
                    }
                )
            }

            list.add(
                ActionSpec(
                    label = if (isArabic) "الانتقال إلى سجل المبيعات" else "Open Sales History",
                    icon = Icons.Rounded.ReceiptLong,
                    tint = Color(0xFF38BDF8)
                ) {
                    dismiss()
                    navigateTo(MiraSalesHistoryFragment(), "SalesHistory")
                }
            )
        } else {
            when (normalizedType) {
                "inventory" -> list.add(
                    ActionSpec(
                        label = if (isArabic) "فتح شاشة الجرد" else "Open Inventory",
                        icon = Icons.Rounded.Inventory2,
                        tint = Color(0xFF06B6D4),
                        isPrimary = true
                    ) {
                        dismiss()
                        navigateTo(MiraInventoryProFragment(), "Scan")
                    }
                )
                "transfer" -> list.add(
                    ActionSpec(
                        label = if (isArabic) "فتح التحويلات" else "Open Transfers",
                        icon = Icons.Rounded.SwapHoriz,
                        tint = Color(0xFFA855F7),
                        isPrimary = true
                    ) {
                        dismiss()
                        navigateTo(MiraTransferFragment(), "Transfers")
                    }
                )
                else -> list.add(
                    ActionSpec(
                        label = if (isArabic) "عرض سجل العمليات الكامل" else "View All Operations",
                        icon = Icons.Rounded.History,
                        tint = Color(0xFFD4AF37),
                        isPrimary = true
                    ) {
                        dismiss()
                        navigateTo(MiraAllActivitiesFragment(), "AllActivities")
                    }
                )
            }
        }

        list.add(
            ActionSpec(
                label = if (isArabic) "نسخ رقم الفاتورة / البيانات" else "Copy Reference / Details",
                icon = Icons.Rounded.ContentCopy,
                tint = Color(0xFF94A3B8)
            ) {
                try {
                    val saleNo = activity.optString("sale_number", "")
                    val copyText = if (saleNo.isNotEmpty()) saleNo else activity.toString(2)
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Operation", copyText))
                    Toast.makeText(
                        context,
                        if (isArabic) "تم النسخ إلى الحافظة" else "Copied to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "copy error", e)
                }
                dismiss()
            }
        )

        return list
    }

    private fun navigateTo(fragment: Fragment, tag: String) {
        try {
            (getActivity() as? UHFMainActivity)?.loadFragment(fragment, tag)
        } catch (e: Exception) {
            Log.e(TAG, "navigate error", e)
        }
    }

    private fun resolveNormalizedType(actionKey: String, rawType: String, saleNo: String): String {
        if (saleNo.isNotEmpty()) {
            return if (actionKey.contains("REFUND") || rawType.contains("refund", true)) "refund" else "sale"
        }
        val combined = "$actionKey $rawType".lowercase(Locale.ROOT)
        return when {
            combined.contains("refund") -> "refund"
            combined.contains("sale") || combined.contains("pos") -> "sale"
            combined.contains("scrap") -> "scrap"
            combined.contains("inventory") || combined.contains("audit") -> "inventory"
            combined.contains("transfer") || combined.contains("move") -> "transfer"
            combined.contains("rfid") || combined.contains("gate") -> "rfid"
            else -> "system"
        }
    }

    private fun resolveHeaderIcon(normalizedType: String): ImageVector {
        return when (normalizedType) {
            "refund" -> Icons.Rounded.AssignmentReturn
            "sale" -> Icons.Rounded.ReceiptLong
            "scrap" -> Icons.Rounded.Scale
            "inventory" -> Icons.Rounded.Inventory2
            "transfer" -> Icons.Rounded.SwapHoriz
            "rfid" -> Icons.Rounded.Contactless
            else -> Icons.Rounded.History
        }
    }

    private fun formatFullTime(timestamp: String, isArabic: Boolean): String {
        if (timestamp.isBlank()) return "—"
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val input = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) input.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = input.parse(timestamp)
                if (parsed != null) {
                    val output = SimpleDateFormat(
                        if (isArabic) "d MMMM yyyy • hh:mm a" else "MMM d, yyyy • hh:mm a",
                        if (isArabic) Locale("ar") else Locale.US
                    )
                    return output.format(parsed)
                }
            } catch (_: Exception) {
            }
        }
        return timestamp.replace("T", " ").substringBefore(".")
    }

    private fun formatAmount(amount: Double, currency: String): String {
        return try {
            val isLarge = currency.uppercase(Locale.US) in setOf("YER", "EGP", "IQD", "SYP")
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                maximumFractionDigits = if (isLarge) 0 else 2
                minimumFractionDigits = if (isLarge) 0 else 2
            }
            "${fmt.format(amount)} $currency"
        } catch (_: Exception) {
            String.format(Locale.US, "%.2f %s", amount, currency)
        }
    }

    private fun mergeJsonObjects(base: JSONObject, extra: JSONObject): JSONObject {
        return try {
            val merged = JSONObject(base.toString())
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = extra.opt(k)
                if (v != null && v != JSONObject.NULL) {
                    merged.put(k, v)
                }
            }
            merged
        } catch (_: Exception) {
            extra
        }
    }

    companion object {
        private const val TAG = "ActivityDetailsSheet"
        private const val ARG_IS_ARABIC = "is_arabic"
        private var sPendingActivity: JSONObject? = null

        @JvmStatic
        fun newInstance(activity: JSONObject, isArabic: Boolean): ActivityDetailsBottomSheet {
            return ActivityDetailsBottomSheet().apply {
                sPendingActivity = activity
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_ARABIC, isArabic)
                }
            }
        }
    }
}
