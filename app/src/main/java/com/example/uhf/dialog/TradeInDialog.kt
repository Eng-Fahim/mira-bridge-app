package com.example.uhf.dialog

import android.app.Dialog
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.uhf.api.MiraApiClient
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.round

/**
 * TradeInDialog v3.0 — Jetpack Compose Luxury Edition
 * - متطابق بصرياً مع POSCheckoutDialog (MiraJewelryTheme)
 * - يعتمد أسعار شراء الكسر المعتمدة من السيرفر (scrap_details) كـ Single Source of Truth
 * - يستعيد الأسطر السابقة عند إعادة الفتح
 */
class TradeInDialog : DialogFragment() {

    companion object {
        private const val TAG = "TradeInDialog"
        private const val ARG_MAX_VALUE = "max_value"
        private const val ARG_EXISTING_LINES = "existing_lines"
        private const val ARG_SCRAP_DETAILS_JSON = "scrap_details_json"
        private val KARATS = listOf("24K", "22K", "21K", "18K", "14K")

        @JvmStatic
        fun newInstance(maxValue: Double): TradeInDialog {
            return newInstance(maxValue, null, null)
        }

        @JvmStatic
        fun newInstance(maxValue: Double, existingLines: JSONArray?): TradeInDialog {
            return newInstance(maxValue, existingLines, null)
        }

        @JvmStatic
        fun newInstance(
            maxValue: Double,
            existingLines: JSONArray?,
            scrapDetailsJson: String?
        ): TradeInDialog {
            return TradeInDialog().apply {
                arguments = Bundle().apply {
                    putDouble(ARG_MAX_VALUE, maxValue)
                    if (existingLines != null) {
                        putString(ARG_EXISTING_LINES, existingLines.toString())
                    }
                    if (!scrapDetailsJson.isNullOrEmpty()) {
                        putString(ARG_SCRAP_DETAILS_JSON, scrapDetailsJson)
                    }
                }
            }
        }
    }

    fun interface OnTradeInListener {
        fun onConfirm(tradeIns: JSONArray, totalValue: Double)
    }

    private data class TradeInLineUiModel(
        val id: String = UUID.randomUUID().toString(),
        val karat: String = "21K",
        val weightText: String = "",
        val weight: Double = 0.0,
        val value: Double = 0.0,
        val pure24k: Double = 0.0,
        val pricePerGram: Double = 0.0,
        val discountPct: Double = 0.0,
        val description: String = "",
        val calculating: Boolean = false
    )

    private var maxValue: Double = 0.0
    private var isArabic: Boolean = true
    private var listener: OnTradeInListener? = null

    private val linesState = mutableStateListOf<TradeInLineUiModel>()
    private val serverScrapDetails = HashMap<String, JSONObject>()
    private val calcJobs = HashMap<String, Job>()

    fun setOnTradeInListener(l: OnTradeInListener?) {
        this.listener = l
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            maxValue = args.getDouble(ARG_MAX_VALUE, 0.0)
            parseServerScrapDetails(args.getString(ARG_SCRAP_DETAILS_JSON, "{}"))
        }

        isArabic = try {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (tags.isNotEmpty()) tags.contains("ar")
            else Locale.getDefault().language == "ar"
        } catch (_: Exception) {
            Locale.getDefault().language == "ar"
        }

        if (linesState.isEmpty()) {
            restoreExistingLines()
        }
        if (linesState.isEmpty()) {
            linesState.add(TradeInLineUiModel())
        }
    }

    private fun parseServerScrapDetails(json: String?) {
        serverScrapDetails.clear()
        if (json.isNullOrBlank()) return
        try {
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val detail = root.optJSONObject(k)
                if (detail != null) {
                    serverScrapDetails[k.uppercase(Locale.US).trim()] = detail
                }
            }
            Log.i(TAG, "✅ Loaded server scrap details for karats: ${serverScrapDetails.keys}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse scrap_details_json", e)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.96).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setDimAmount(0.65f)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val composeDir = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
        return ComposeView(requireContext()).apply {
            layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides composeDir) {
                    MiraJewelryTheme {
                        TradeInDialogContent()
                    }
                }
            }
        }
    }

    private fun restoreExistingLines() {
        try {
            val json = arguments?.getString(ARG_EXISTING_LINES, null)
            if (json.isNullOrEmpty()) return

            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val karat = obj.optString("karat", "21K").uppercase(Locale.US).trim()
                val weight = obj.optDouble("weight_grams", 0.0)
                val value = obj.optDouble("value_yer", 0.0)
                val desc = obj.optString("description", "")

                val model = TradeInLineUiModel(
                    karat = if (karat in KARATS) karat else "21K",
                    weightText = if (weight > 0.0) String.format(Locale.US, "%.3f", weight) else "",
                    weight = weight,
                    value = value,
                    description = desc
                )
                linesState.add(model)
                if (weight > 0.0) {
                    recalculateLine(model.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreExistingLines error", e)
        }
    }

    private fun parseWeight(raw: String): Double {
        if (raw.isBlank()) return 0.0
        return try {
            raw.trim().replace(",", ".").toDouble().coerceAtLeast(0.0)
        } catch (_: Exception) {
            0.0
        }
    }

    private fun updateLineById(id: String, transform: (TradeInLineUiModel) -> TradeInLineUiModel) {
        val idx = linesState.indexOfFirst { it.id == id }
        if (idx >= 0) {
            linesState[idx] = transform(linesState[idx])
        }
    }

    private fun recalculateLine(lineId: String) {
        val current = linesState.firstOrNull { it.id == lineId } ?: return
        calcJobs[lineId]?.cancel()

        if (current.weight <= 0.0) {
            updateLineById(lineId) {
                it.copy(
                    value = 0.0,
                    pure24k = 0.0,
                    pricePerGram = 0.0,
                    discountPct = 0.0,
                    calculating = false
                )
            }
            return
        }

        val karatKey = current.karat.uppercase(Locale.US).trim()
        val serverDetail = serverScrapDetails[karatKey]

        // المسار الأساسي: الحساب المباشر من أرقام السيرفر المعتمدة في prepare-checkout
        if (serverDetail != null) {
            val factor = serverDetail.optDouble("karat_factor", getDefaultFactor(karatKey))
            val base24k = serverDetail.optDouble("base_24k_yer", 0.0)
            val discountPct = serverDetail.optDouble("discount_percent", 5.0)
            val netGramYer = serverDetail.optDouble("net_gram_yer", 0.0)

            val pure = current.weight * factor
            val rawValue = if (base24k > 0.0) {
                pure * base24k * (1.0 - discountPct / 100.0)
            } else {
                current.weight * netGramYer
            }

            updateLineById(lineId) {
                it.copy(
                    pure24k = round(pure * 1000.0) / 1000.0,
                    pricePerGram = round(base24k * 100.0) / 100.0,
                    discountPct = round(discountPct * 100.0) / 100.0,
                    value = round(rawValue * 100.0) / 100.0,
                    calculating = false
                )
            }
            return
        }

        // مسار احتياطي من السيرفر عند عدم توفر scrap_details
        val appCtx = context?.applicationContext ?: return
        val targetWeight = current.weight
        updateLineById(lineId) { it.copy(calculating = true) }

        calcJobs[lineId] = viewLifecycleOwner.lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                try {
                    MiraApiClient.getInstance(appCtx).calculateScrap(targetWeight, karatKey)
                } catch (e: Exception) {
                    Log.e(TAG, "calculateScrap error", e)
                    null
                }
            }

            if (!isAdded) return@launch
            updateLineById(lineId) { latest ->
                if (latest.weight != targetWeight || !latest.karat.equals(karatKey, ignoreCase = true)) {
                    latest
                } else if (response != null && response.isSuccess && response.data != null) {
                    latest.copy(
                        value = response.data.optDouble("total_value", 0.0),
                        pure24k = response.data.optDouble("pure_24k_grams", 0.0),
                        pricePerGram = response.data.optDouble("price_per_gram", 0.0),
                        discountPct = response.data.optDouble("discount_percent", 0.0),
                        calculating = false
                    )
                } else {
                    latest.copy(value = 0.0, calculating = false)
                }
            }
        }
    }

    private fun getDefaultFactor(karat: String): Double {
        return when (karat) {
            "24K" -> 1.000000
            "22K" -> 0.916667
            "21K" -> 0.875000
            "18K" -> 0.750000
            "14K" -> 0.583333
            else -> 0.875000
        }
    }

    private fun formatPiecesCount(count: Int): String {
        if (isArabic) {
            return when {
                count == 0 -> "0 قطعة"
                count == 1 -> "قطعة واحدة"
                count == 2 -> "قطعتان"
                count <= 10 -> "$count قطع"
                else -> "$count قطعة"
            }
        }
        return "$count " + if (count == 1) "piece" else "pieces"
    }

    private fun onConfirmClicked() {
        val cb = listener
        if (cb == null) {
            dismiss()
            return
        }
        try {
            val arr = JSONArray()
            var total = 0.0
            for (line in linesState) {
                if (line.weight <= 0.0 || line.value <= 0.0) continue
                val desc = line.description.trim().ifEmpty {
                    "${line.karat} · ${String.format(Locale.US, "%.3fg", line.weight)}"
                }
                val obj = JSONObject().apply {
                    put("weight_grams", line.weight)
                    put("karat", line.karat)
                    put("value_yer", line.value)
                    put("description", desc)
                }
                arr.put(obj)
                total += line.value
            }
            cb.onConfirm(arr, total)
            dismiss()
        } catch (e: Exception) {
            Log.e(TAG, "confirm error", e)
        }
    }

    @Composable
    private fun TradeInDialogContent() {
        val colors = MiraTheme.colors
        val isAr = isArabic

        val totalValue = linesState.sumOf { it.value }
        val anyCalculating = linesState.any { it.calculating }
        val excess = totalValue - maxValue
        val validPiecesCount = linesState.count { it.weight > 0.0 && it.value > 0.0 }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ─── Header ───
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.backgroundMid)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(colors.goldPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Diamond,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isAr) "الدفع بالذهب الكسر (مقايضة)" else "Gold Trade-In",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = formatPiecesCount(
                                    if (validPiecesCount > 0) validPiecesCount else linesState.size
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.goldPrimary
                            )
                        }
                    }

                    IconButton(onClick = { dismiss() }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }

                HorizontalDivider(color = colors.goldPrimary.copy(alpha = 0.2f))

                // ─── Body ───
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TradeInSummaryCard(
                        isArabic = isAr,
                        maxInvoiceYer = maxValue,
                        totalScrapYer = totalValue,
                        excessYer = excess
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAr) "قطع الذهب المستبدلة" else "Trade-In Gold Pieces",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )

                        OutlinedButton(
                            onClick = { linesState.add(TradeInLineUiModel()) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isAr) "إضافة قطعة" else "Add Piece",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    linesState.forEachIndexed { index, line ->
                        TradeInLineCard(
                            index = index,
                            line = line,
                            isArabic = isAr,
                            canRemove = linesState.size > 1,
                            onSelectKarat = { selectedKarat ->
                                updateLineById(line.id) { it.copy(karat = selectedKarat) }
                                recalculateLine(line.id)
                            },
                            onWeightChanged = { newText ->
                                val parsed = parseWeight(newText)
                                updateLineById(line.id) {
                                    it.copy(weightText = newText, weight = parsed)
                                }
                                recalculateLine(line.id)
                            },
                            onDescriptionChanged = { newDesc ->
                                updateLineById(line.id) { it.copy(description = newDesc) }
                            },
                            onRemove = {
                                if (linesState.size > 1) {
                                    calcJobs[line.id]?.cancel()
                                    calcJobs.remove(line.id)
                                    linesState.removeAt(index)
                                } else {
                                    Toast.makeText(
                                        requireContext(),
                                        if (isAr) "يجب إبقاء قطعة واحدة على الأقل" else "At least one piece is required",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }

                HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.14f))

                // ─── Footer ───
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.backgroundMid)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { dismiss() },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary)
                    ) {
                        Text(
                            text = if (isAr) "إلغاء" else "Cancel",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    val confirmEnabled = totalValue > 0.0 && !anyCalculating
                    Button(
                        onClick = { onConfirmClicked() },
                        enabled = confirmEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D),
                            disabledContainerColor = colors.goldPrimary.copy(alpha = 0.25f),
                            disabledContentColor = colors.textSecondary
                        )
                    ) {
                        if (anyCalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF0A0F1D)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAr) "جاري الاحتساب..." else "Calculating...",
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAr) {
                                    String.format(Locale.US, "تأكيد (%,.0f YER)", totalValue)
                                } else {
                                    String.format(Locale.US, "Confirm (%,.0f YER)", totalValue)
                                },
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TradeInSummaryCard(
        isArabic: Boolean,
        maxInvoiceYer: Double,
        totalScrapYer: Double,
        excessYer: Double
    ) {
        val colors = MiraTheme.colors

        val hasExcess = excessYer > 0.01
        val hasValue = totalScrapYer > 0.01

        val statusColor = when {
            hasExcess -> Color(0xFF3B82F6)
            hasValue -> Color(0xFF10B981)
            else -> colors.textSecondary
        }

        val statusIcon = when {
            hasExcess -> Icons.Rounded.SwapHoriz
            hasValue -> Icons.Rounded.CheckCircle
            else -> Icons.Rounded.Info
        }

        val statusTitle = when {
            hasExcess -> if (isArabic) "فائض يُضاف كرصيد دائن للعميل" else "Excess Added as Customer Credit"
            hasValue -> if (isArabic) "يُخصم مباشرة من إجمالي الفاتورة" else "Deducted Directly from Invoice"
            else -> if (isArabic) "أدخل وزن القطعة لاحتساب القيمة" else "Enter weight to calculate value"
        }

        val statusTrailing = when {
            hasExcess -> String.format(Locale.US, "+%,.0f YER", excessYer)
            hasValue -> String.format(Locale.US, "-%,.0f YER", totalScrapYer)
            else -> "0 YER"
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.backgroundMid,
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.28f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isArabic) "إجمالي تقييم الذهب الكسر" else "Total Trade-In Value",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        if (maxInvoiceYer > 0.0) {
                            Text(
                                text = if (isArabic) {
                                    String.format(Locale.US, "قيمة الفاتورة: %,.0f YER", maxInvoiceYer)
                                } else {
                                    String.format(Locale.US, "Invoice: %,.0f YER", maxInvoiceYer)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }

                    Text(
                        text = String.format(Locale.US, "%,.0f YER", totalScrapYer),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.goldPrimary
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.42f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusTrailing,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = statusColor
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TradeInLineCard(
        index: Int,
        line: TradeInLineUiModel,
        isArabic: Boolean,
        canRemove: Boolean,
        onSelectKarat: (String) -> Unit,
        onWeightChanged: (String) -> Unit,
        onDescriptionChanged: (String) -> Unit,
        onRemove: () -> Unit
    ) {
        val colors = MiraTheme.colors

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.backgroundMid,
            border = BorderStroke(
                width = 1.dp,
                color = if (line.value > 0.0) {
                    colors.goldPrimary.copy(alpha = 0.35f)
                } else {
                    colors.textSecondary.copy(alpha = 0.16f)
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ─── Top Row: Piece Badge + Calculated Value + Delete ───
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(colors.goldPrimary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.goldPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "قطعة #${index + 1}" else "Piece #${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (line.calculating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.goldPrimary
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (line.value > 0.0) {
                                    colors.goldPrimary.copy(alpha = 0.16f)
                                } else {
                                    colors.cardSurface
                                }
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%,.0f YER", line.value),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (line.value > 0.0) colors.goldPrimary else colors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (canRemove) {
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = onRemove,
                                modifier = Modifier.size(30.dp)
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

                // ─── Karat Selector Chips ───
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KARATS.forEach { karatOption ->
                        val isSelected = line.karat.equals(karatOption, ignoreCase = true)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectKarat(karatOption) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) colors.goldPrimary else colors.cardSurface,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) {
                                    colors.goldPrimary
                                } else {
                                    colors.textSecondary.copy(alpha = 0.25f)
                                }
                            )
                        ) {
                            Text(
                                text = karatOption,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = if (isSelected) Color(0xFF0A0F1D) else colors.textPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                // ─── Weight & Description Fields ───
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = line.weightText,
                        onValueChange = onWeightChanged,
                        singleLine = true,
                        label = {
                            Text(
                                text = if (isArabic) "الوزن (جرام)" else "Weight (g)",
                                fontSize = 12.sp
                            )
                        },
                        placeholder = {
                            Text("0.000", style = MaterialTheme.typography.bodySmall)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Scale,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.46f)
                    )

                    OutlinedTextField(
                        value = line.description,
                        onValueChange = onDescriptionChanged,
                        singleLine = true,
                        label = {
                            Text(
                                text = if (isArabic) "الوصف (اختياري)" else "Description",
                                fontSize = 12.sp
                            )
                        },
                        placeholder = {
                            Text(
                                text = if (isArabic) "مثال: خاتم كسر" else "e.g. Scrap ring",
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Description,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.54f)
                    )
                }

                // ─── Live Valuation Breakdown Strip ───
                AnimatedVisibility(visible = line.value > 0.0 && !line.calculating) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                    ) {
                        val breakdownText = if (isArabic) {
                            String.format(
                                Locale.US,
                                "صافي 24K: %.3fg  •  سعر الجرام: %,.0f  •  خصم الكسر: %.1f%%",
                                line.pure24k,
                                line.pricePerGram,
                                line.discountPct
                            )
                        } else {
                            String.format(
                                Locale.US,
                                "Pure 24K: %.3fg  •  Rate: %,.0f  •  Discount: %.1f%%",
                                line.pure24k,
                                line.pricePerGram,
                                line.discountPct
                            )
                        }

                        Text(
                            text = breakdownText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF10B981),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
