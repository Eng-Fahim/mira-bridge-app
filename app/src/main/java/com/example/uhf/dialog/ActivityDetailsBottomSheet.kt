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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.fragment.MiraAllActivitiesFragment
import com.example.uhf.fragment.MiraInventoryProFragment
import com.example.uhf.fragment.MiraSalesHistoryFragment
import com.example.uhf.fragment.MiraTransferFragment
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private data class ActionSpec(
        val label: String,
        val icon: ImageVector,
        val tint: Color,
        val onClick: () -> Unit
    )

    private var activity: JSONObject = JSONObject()
    private var isArabic: Boolean = true
    private var actionListener: OnActionClickListener? = null

    fun setOnActionClickListener(listener: OnActionClickListener?) {
        this.actionListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = sPendingActivity
        if (pending != null) {
            activity = pending
            sPendingActivity = null
        } else {
            activity = JSONObject()
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
                            activity = activity,
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
        activity: JSONObject,
        isArabic: Boolean,
        onClose: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val scrollState = rememberScrollState()

        val actionKey = remember(activity) {
            activity.optString("action", "")
                .ifBlank { activity.optString("type", "") }
                .ifBlank { activity.optString("event", "") }
                .uppercase(Locale.US)
        }

        val normalizedType = remember(actionKey, activity) {
            resolveNormalizedType(actionKey, activity.optString("type", ""))
        }

        val nestedPayload = remember(activity) {
            extractNestedJson(activity, "details")
                ?: extractNestedJson(activity, "meta")
                ?: extractNestedJson(activity, "payload")
                ?: activity
        }

        val title = remember(activity, isArabic, actionKey) {
            val rawTitle = if (isArabic) {
                activity.optString("title_ar", "").ifBlank { activity.optString("title", "") }
            } else {
                activity.optString("title_en", "").ifBlank { activity.optString("title", "") }
            }.trim()

            if (rawTitle.isNotBlank() && !isRawEventKey(rawTitle)) {
                rawTitle
            } else {
                resolveFriendlyTitle(actionKey, isArabic)
            }
        }

        val formattedTime = remember(activity) {
            val ts = activity.optString("timestamp", "").ifBlank { activity.optString("created_at", "") }
            formatFullTime(ts, isArabic)
        }

        val statusRaw = activity.optString("status", "neutral").lowercase(Locale.ROOT)
        val statusColor = resolveStatusColor(statusRaw)
        val headerIcon = resolveHeaderIcon(normalizedType, actionKey)

        val detailRows = remember(activity, nestedPayload, isArabic, normalizedType) {
            buildDetailRows(activity, nestedPayload, normalizedType, isArabic)
        }

        val soldItems = remember(activity, isArabic) {
            extractSoldItems(activity, isArabic)
        }

        val actions = remember(activity, normalizedType, isArabic) {
            buildActionSpecs(activity, normalizedType, isArabic)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
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
                // مقبض السحب العلوي
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.textSecondary.copy(alpha = 0.35f))
                )

                Spacer(modifier = Modifier.height(14.dp))

                // رأس النافذة
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(statusColor.copy(alpha = 0.16f))
                            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = headerIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formattedTime,
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = colors.cardBorder)
                Spacer(modifier = Modifier.height(12.dp))

                // المحتوى القابل للتمرير
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    detailRows.forEach { row ->
                        DetailInfoCard(row = row)
                    }

                    if (soldItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isArabic) "القطع المرتبطة بالعملية (${soldItems.size})"
                            else "Associated Items (${soldItems.size})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary
                        )

                        soldItems.forEach { sold ->
                            SoldItemCard(sold = sold, isArabic = isArabic)
                        }
                    }

                    if (actions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isArabic) "الإجراءات المتاحة" else "Available Actions",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary
                        )

                        actions.forEach { spec ->
                            ActionButtonCard(spec = spec)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
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
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = row.icon,
                    contentDescription = null,
                    tint = colors.goldPrimary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(top = 1.dp)
                )

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
                        fontWeight = FontWeight.SemiBold,
                        color = row.valueColor ?: colors.textPrimary
                    )
                }
            }
        }
    }

    @Composable
    private fun SoldItemCard(sold: SoldItemDisplay, isArabic: Boolean) {
        val colors = MiraTheme.colors
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.cardSurface.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "#${sold.index} • ${sold.title}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                if (sold.serial.isNotEmpty()) {
                    Text(
                        text = (if (isArabic) "الرقم التسلسلي: " else "Serial: ") + sold.serial,
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
                Text(
                    text = "RFID: " + if (sold.epc.isNotEmpty()) sold.epc
                    else (if (isArabic) "غير مربوط" else "Not bound"),
                    fontSize = 11.sp,
                    color = if (sold.epc.isNotEmpty()) Color(0xFF10B981) else colors.textSecondary
                )
                val metaLine = listOfNotNull(
                    if (sold.karat.isNotEmpty()) (if (isArabic) "عيار ${sold.karat}" else "Karat ${sold.karat}") else null,
                    sold.weightText,
                    sold.priceText
                ).joinToString("  •  ")
                if (metaLine.isNotEmpty()) {
                    Text(
                        text = metaLine,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
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
            color = colors.cardSurface,
            border = BorderStroke(1.dp, spec.tint.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        }
    }

    private fun buildDetailRows(
        activity: JSONObject,
        nested: JSONObject,
        normalizedType: String,
        isArabic: Boolean
    ): List<DetailEntry> {
        val rows = mutableListOf<DetailEntry>()

        rows.add(
            DetailEntry(
                label = if (isArabic) "نوع العملية" else "Type",
                value = getTypeName(normalizedType, isArabic),
                icon = Icons.Rounded.Category
            )
        )

        val user = activity.optString("user").ifBlank { activity.optString("username") }.trim()
        if (user.isNotEmpty() && user != "—") {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "المستخدم" else "User",
                    value = user,
                    icon = Icons.Rounded.Person
                )
            )
        }

        val saleNumber = nested.optString("sale_number")
            .ifBlank { activity.optString("sale_number") }
            .ifBlank { nested.optString("invoice_number") }
            .trim()
        if (saleNumber.isNotEmpty()) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "رقم الفاتورة" else "Invoice #",
                    value = saleNumber,
                    icon = Icons.Rounded.ReceiptLong,
                    valueColor = Color(0xFF38BDF8)
                )
            )
        }

        val entryId = nested.optString("entry_id")
            .ifBlank { activity.optString("entry_id") }
            .trim()
        if (entryId.isNotEmpty()) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "رقم القيد المحاسبي" else "Journal Entry #",
                    value = "#$entryId",
                    icon = Icons.Rounded.AccountBalanceWallet
                )
            )
        }

        val currencyCode = activity.optString("currency")
            .ifBlank { nested.optString("currency") }
            .ifBlank { "USD" }
            .trim()

        val amountPrimary = firstValidAmount(
            activity.optDouble("final_amount_usd", Double.NaN),
            activity.optDouble("amount_usd", Double.NaN),
            activity.optDouble("amount", Double.NaN),
            nested.optDouble("amount", Double.NaN),
            nested.optDouble("total_debit", Double.NaN),
            nested.optDouble("total_amount", Double.NaN)
        )
        if (!amountPrimary.isNaN() && amountPrimary > 0.0) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "المبلغ ($currencyCode)" else "Amount ($currencyCode)",
                    value = formatAmount(amountPrimary, currencyCode),
                    icon = Icons.Rounded.Payments,
                    valueColor = Color(0xFF10B981)
                )
            )
        }

        val pureWeight = firstValidAmount(
            nested.optDouble("pure_weight", Double.NaN),
            nested.optDouble("weight", Double.NaN),
            activity.optDouble("total_weight", Double.NaN),
            activity.optDouble("weight", Double.NaN)
        )
        if (!pureWeight.isNaN() && pureWeight > 0.0) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "الوزن" else "Weight",
                    value = String.format(Locale.US, "%.2f g", pureWeight),
                    icon = Icons.Rounded.Scale
                )
            )
        }

        when (normalizedType) {
            "inventory" -> {
                val accuracy = activity.optDouble("accuracy", -1.0)
                if (accuracy >= 0.0) {
                    rows.add(
                        DetailEntry(
                            label = if (isArabic) "نسبة الدقة" else "Accuracy",
                            value = String.format(Locale.US, "%.1f%%", accuracy),
                            icon = Icons.Rounded.GpsFixed
                        )
                    )
                }
                val risk = activity.optString("risk_level", "none")
                if (risk.isNotEmpty() && risk != "none") {
                    rows.add(
                        DetailEntry(
                            label = if (isArabic) "مستوى الخطورة" else "Risk Level",
                            value = getRiskName(risk, isArabic),
                            icon = Icons.Rounded.WarningAmber
                        )
                    )
                }
            }
            "transfer" -> {
                val transferStatus = activity.optString("transfer_status", "")
                if (transferStatus.isNotEmpty()) {
                    rows.add(
                        DetailEntry(
                            label = if (isArabic) "حالة التحويل" else "Transfer Status",
                            value = transferStatus,
                            icon = Icons.Rounded.SwapHoriz
                        )
                    )
                }
            }
        }

        val rawDetails = if (isArabic) {
            activity.optString("details_ar", "").ifBlank { activity.optString("details", "") }
        } else {
            activity.optString("details_en", "").ifBlank { activity.optString("details", "") }
        }.trim()

        if (rawDetails.isNotEmpty() && !looksLikeRawJson(rawDetails)) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "بيان العملية" else "Description",
                    value = rawDetails,
                    icon = Icons.Rounded.Notes
                )
            )
        }

        val refId = activity.optInt("reference_id", 0)
        if (refId > 0) {
            rows.add(
                DetailEntry(
                    label = if (isArabic) "المرجع" else "Reference",
                    value = "#$refId",
                    icon = Icons.Rounded.Tag
                )
            )
        }

        return rows
    }

    private fun extractSoldItems(activity: JSONObject, isArabic: Boolean): List<SoldItemDisplay> {
        val itemsArr: JSONArray = activity.optJSONArray("items") ?: return emptyList()
        val result = mutableListOf<SoldItemDisplay>()
        val currencyCode = activity.optString("currency", "USD")
        val limit = minOf(itemsArr.length(), 30)

        for (i in 0 until limit) {
            val item = itemsArr.optJSONObject(i) ?: continue
            val itemTitle = item.optString("title")
                .ifBlank { item.optString("item_title") }
                .ifBlank { (if (isArabic) "قطعة" else "Item") + " #${i + 1}" }

            val epc = item.optString("epc").ifBlank { item.optString("rfid_epc") }.trim()
            val serial = item.optString("serial").ifBlank { item.optString("serial_number") }.trim()
            val karat = item.optString("karat").ifBlank { item.optString("gold_karat") }.trim()
            val weight = item.optDouble("weight", item.optDouble("gross_weight_grams", 0.0))
            val priceUsd = item.optDouble("price_usd", 0.0)
            val priceLocal = item.optDouble("price_local", 0.0)

            val weightText = if (weight > 0.0) String.format(Locale.US, "%.2f g", weight) else null
            val priceText = when {
                priceUsd > 0.0 && priceLocal > 0.0 && currencyCode != "USD" ->
                    String.format(Locale.US, "$%,.2f • %s", priceUsd, formatAmount(priceLocal, currencyCode))
                priceUsd > 0.0 -> String.format(Locale.US, "$%,.2f", priceUsd)
                priceLocal > 0.0 -> formatAmount(priceLocal, currencyCode)
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
        isArabic: Boolean
    ): List<ActionSpec> {
        val list = mutableListOf<ActionSpec>()

        when (normalizedType) {
            "sale", "refund" -> {
                list.add(
                    ActionSpec(
                        label = if (isArabic) "عرض سجل المبيعات" else "View Sales History",
                        icon = Icons.Rounded.ReceiptLong,
                        tint = Color(0xFF10B981)
                    ) {
                        dismiss()
                        navigateTo(MiraSalesHistoryFragment(), "SalesHistory")
                    }
                )
                list.add(
                    ActionSpec(
                        label = if (isArabic) "طباعة الفاتورة" else "Print Invoice",
                        icon = Icons.Rounded.Print,
                        tint = Color(0xFF38BDF8)
                    ) {
                        dismiss()
                        val listener = actionListener
                        if (listener != null) {
                            listener.onPrintInvoice(activity)
                        } else {
                            Toast.makeText(
                                context,
                                if (isArabic) "جاري تحضير الفاتورة..." else "Preparing invoice...",
                                Toast.LENGTH_SHORT
                            ).show()
                            navigateTo(MiraSalesHistoryFragment(), "SalesHistory")
                        }
                    }
                )
                list.add(
                    ActionSpec(
                        label = if (isArabic) "فتح الاسترجاع" else "Open Refund",
                        icon = Icons.Rounded.AssignmentReturn,
                        tint = Color(0xFFF59E0B)
                    ) {
                        dismiss()
                        val listener = actionListener
                        if (listener != null) {
                            listener.onOpenRefund(activity)
                        } else {
                            navigateTo(MiraSalesHistoryFragment(), "SalesHistory")
                        }
                    }
                )
            }
            "inventory" -> {
                list.add(
                    ActionSpec(
                        label = if (isArabic) "عرض شاشة الجرد" else "Open Inventory",
                        icon = Icons.Rounded.Inventory2,
                        tint = Color(0xFF06B6D4)
                    ) {
                        dismiss()
                        navigateTo(MiraInventoryProFragment(), "Scan")
                    }
                )
            }
            "transfer" -> {
                list.add(
                    ActionSpec(
                        label = if (isArabic) "فتح التحويلات" else "Open Transfers",
                        icon = Icons.Rounded.SwapHoriz,
                        tint = Color(0xFFA855F7)
                    ) {
                        dismiss()
                        navigateTo(MiraTransferFragment(), "Transfers")
                    }
                )
            }
            else -> {
                list.add(
                    ActionSpec(
                        label = if (isArabic) "عرض كل العمليات" else "View All Activities",
                        icon = Icons.Rounded.History,
                        tint = Color(0xFFD4AF37)
                    ) {
                        dismiss()
                        navigateTo(MiraAllActivitiesFragment(), "AllActivities")
                    }
                )
            }
        }

        list.add(
            ActionSpec(
                label = if (isArabic) "نسخ تفاصيل العملية" else "Copy Details",
                icon = Icons.Rounded.ContentCopy,
                tint = Color(0xFF94A3B8)
            ) {
                try {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("Activity", activity.toString(2))
                    clipboard?.setPrimaryClip(clip)
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
            (activity as? UHFMainActivity)?.loadFragment(fragment, tag)
        } catch (e: Exception) {
            Log.e(TAG, "navigate error", e)
        }
    }

    private fun resolveNormalizedType(actionKey: String, rawType: String): String {
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

    private fun resolveHeaderIcon(normalizedType: String, actionKey: String): ImageVector {
        return when {
            normalizedType == "refund" -> Icons.Rounded.AssignmentReturn
            normalizedType == "sale" && actionKey.contains("JOURNAL") -> Icons.Rounded.ReceiptLong
            normalizedType == "sale" -> Icons.Rounded.PointOfSale
            normalizedType == "scrap" -> Icons.Rounded.Recycling
            normalizedType == "inventory" -> Icons.Rounded.Inventory2
            normalizedType == "transfer" -> Icons.Rounded.SwapHoriz
            normalizedType == "rfid" -> Icons.Rounded.Contactless
            else -> Icons.Rounded.History
        }
    }

    private fun resolveFriendlyTitle(actionKey: String, isArabic: Boolean): String {
        return when {
            actionKey.contains("REFUND") && actionKey.contains("JOURNAL") ->
                if (isArabic) "قيد يومية لمرتجع مبيعات" else "Sales Refund Journal Entry"
            actionKey.contains("REFUND") ->
                if (isArabic) "مرتجع فاتورة مبيعات" else "Sales Refund"
            actionKey.contains("SALE") && actionKey.contains("JOURNAL") ->
                if (isArabic) "قيد يومية لفاتورة بيع" else "Sales Journal Entry"
            actionKey.contains("SALE") || actionKey.contains("POS") ->
                if (isArabic) "إتمام عملية بيع" else "Sale Completed"
            actionKey.contains("SCRAP") ->
                if (isArabic) "عملية شراء كسر ذهب" else "Scrap Gold Operation"
            actionKey.contains("TRANSFER") ->
                if (isArabic) "تحويل قطع بين الفروع" else "Branch Transfer"
            else -> if (isArabic) "تفاصيل العملية" else "Activity Details"
        }
    }

    private fun resolveStatusColor(status: String): Color {
        return when {
            status.contains("success") || status.contains("complete") || status.contains("done") ->
                Color(0xFF10B981)
            status.contains("refund") || status.contains("warn") || status.contains("partial") ->
                Color(0xFFF59E0B)
            status.contains("danger") || status.contains("fail") || status.contains("error") ->
                Color(0xFFEF4444)
            else -> Color(0xFFD4AF37)
        }
    }

    private fun getTypeName(type: String, isArabic: Boolean): String {
        return when (type) {
            "sale" -> if (isArabic) "مبيعات" else "Sale"
            "refund" -> if (isArabic) "مرتجع" else "Refund"
            "scrap" -> if (isArabic) "كسر ذهب" else "Scrap Gold"
            "inventory" -> if (isArabic) "جرد" else "Inventory"
            "transfer" -> if (isArabic) "تحويل" else "Transfer"
            "rfid" -> "RFID"
            else -> if (isArabic) "نظام" else "System"
        }
    }

    private fun getRiskName(risk: String, isArabic: Boolean): String {
        return when (risk.lowercase(Locale.ROOT)) {
            "none" -> if (isArabic) "آمن" else "None"
            "low" -> if (isArabic) "منخفض" else "Low"
            "medium" -> if (isArabic) "متوسط" else "Medium"
            "high" -> if (isArabic) "مرتفع" else "High"
            "critical" -> if (isArabic) "حرج" else "Critical"
            else -> risk
        }
    }

    private fun formatFullTime(timestamp: String, isArabic: Boolean): String {
        if (timestamp.isBlank()) return "—"
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val output = SimpleDateFormat(
                if (isArabic) "EEEE، d MMMM yyyy - HH:mm" else "EEEE, MMM d, yyyy - HH:mm",
                if (isArabic) Locale("ar") else Locale.US
            )
            val parsed: Date? = input.parse(timestamp)
            if (parsed != null) output.format(parsed) else timestamp
        } catch (_: Exception) {
            timestamp
        }
    }

    private fun formatAmount(amount: Double, currency: String): String {
        return try {
            val isLarge = currency in setOf("YER", "EGP", "IQD", "SYP")
            val fmt = NumberFormat.getNumberInstance(Locale.US).apply {
                maximumFractionDigits = if (isLarge) 0 else 2
                minimumFractionDigits = if (isLarge) 0 else 2
            }
            "${fmt.format(amount)} $currency"
        } catch (_: Exception) {
            String.format(Locale.US, "%.2f %s", amount, currency)
        }
    }

    private fun extractNestedJson(parent: JSONObject, key: String): JSONObject? {
        parent.optJSONObject(key)?.let { return it }
        val str = parent.optString(key, "").trim()
        if (str.startsWith("{")) {
            return try {
                JSONObject(str)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun firstValidAmount(vararg values: Double): Double {
        for (v in values) {
            if (!v.isNaN() && v != 0.0) return v
        }
        return Double.NaN
    }

    private fun isRawEventKey(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.uppercase(Locale.US) == trimmed && trimmed.contains("_")
    }

    private fun looksLikeRawJson(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.contains("\":")
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
