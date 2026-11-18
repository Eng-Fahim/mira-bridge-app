package com.example.uhf.dialog

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraLocalItemCache
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class RefundDialog(
    context: Context,
    private val callback: RefundCallback?
) : ComponentDialog(context) {

    companion object {
        private const val TAG = "RefundDialog"
        private const val SEARCH_DEBOUNCE_MS = 600L
        private const val MIN_QUERY_LEN = 3

        private const val KEYCODE_RFID_TRIGGER_1 = 139
        private const val KEYCODE_RFID_TRIGGER_2 = 280
        private const val KEYCODE_RFID_TRIGGER_3 = 293

        private fun firstNonEmpty(obj: JSONObject?, vararg keys: String): String {
            if (obj == null) return ""
            for (key in keys) {
                try {
                    val v = obj.optString(key, "")
                    if (v.isNotEmpty() && v != "null") return v
                } catch (_: Exception) {
                }
            }
            return ""
        }
    }

    interface RefundCallback {
        fun onRefundSuccess(result: JSONObject?)
        fun onBarcodeScan()
        fun onDialogClosed()
    }

    private data class ItemRow(
        val itemId: Int,
        val title: String,
        val karat: String,
        val weight: Double,
        val price: Double,
        val isRefunded: Boolean,
        val isScanned: Boolean,
        val isSelected: Boolean = !isRefunded,
        val condition: String = "in_stock",
        val epc: String = "",
        val serialNo: String = "",
        val gtin: String = "",
        val tid: String = "",
        val rfidEpc: String = "",
        val itemCode: String = ""
    )

    private enum class ScreenState { EMPTY, LOADING, SALE }

    private val isArabic: Boolean =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            context.resources.configuration.locales[0]?.language == "ar"

    private val handler = Handler(Looper.getMainLooper())

    private var currentSale: JSONObject? = null
    private var suppressWatcher = false
    private var pendingSearch: Runnable? = null
    private var searchSeq = 0
    private var hardwareKeyListener: DialogInterface.OnKeyListener? = null

    // Compose Reactive States
    private var queryState by mutableStateOf("")
    private var screenState by mutableStateOf(ScreenState.EMPTY)

    private var saleNumberState by mutableStateOf("")
    private var saleMetaState by mutableStateOf("")
    private var paymentsSummaryState by mutableStateOf("—")

    private val itemRowsState = mutableStateListOf<ItemRow>()

    private var selectedMethodState by mutableStateOf("cash")
    private var hasCustomerState by mutableStateOf(false)
    private var creditRecommendedState by mutableStateOf(false)
    private var creditCreatedState by mutableStateOf(0.0)

    private var bannerMessageState by mutableStateOf<String?>(null)
    private var bannerIsErrorState by mutableStateOf(false)
    private var isSubmittingState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            decorView.layoutDirection =
                if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                MiraJewelryTheme {
                    RefundDialogContent()
                }
            }
        }

        setContentView(composeView)

        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.96).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        setOnDismissListener {
            pendingSearch?.let { handler.removeCallbacks(it) }
            try {
                callback?.onDialogClosed()
            } catch (_: Exception) {
            }
        }
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Search & Sale Rendering
    // ═══════════════════════════════════════════════════════════════════

    private fun onQueryChanged(newValue: String) {
        queryState = newValue
        if (suppressWatcher) return

        pendingSearch?.let { handler.removeCallbacks(it) }
        val q = newValue.trim()
        if (q.length < MIN_QUERY_LEN) {
            screenState = ScreenState.EMPTY
            return
        }
        val task = Runnable { performSearch() }
        pendingSearch = task
        handler.postDelayed(task, SEARCH_DEBOUNCE_MS)
    }

    private fun performSearch() {
        val query = queryState.trim()
        if (query.isEmpty()) return

        val mySeq = ++searchSeq
        screenState = ScreenState.LOADING
        bannerMessageState = null

        val upper = query.uppercase(Locale.US)
        val isSaleNumber = upper.startsWith("INV-") || upper.startsWith("REF-")

        Thread {
            try {
                val client = MiraApiClient.getInstance(context.applicationContext)
                val resp = if (isSaleNumber) {
                    client.findSaleForRefund(query)
                } else {
                    client.findSaleForRefundByItem(query)
                }

                handler.post {
                    if (mySeq != searchSeq) return@post
                    if (resp != null && resp.isSuccess && resp.data != null) {
                        val sale = resp.data!!.optJSONObject("sale")
                        if (sale != null) {
                            renderSale(sale)
                        } else {
                            screenState = ScreenState.EMPTY
                            toast(if (isArabic) "❌ لا توجد فاتورة" else "❌ Not found")
                        }
                    } else {
                        val msg = resp?.error
                            ?: if (isArabic) "لا توجد فاتورة" else "Not found"
                        screenState = ScreenState.EMPTY
                        toast("❌ $msg")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "performSearch error", e)
                handler.post {
                    if (mySeq != searchSeq) return@post
                    screenState = ScreenState.EMPTY
                    toast("⚠️ ${e.message}")
                }
            }
        }.start()
    }

    private fun renderSale(sale: JSONObject) {
        try {
            currentSale = sale
            itemRowsState.clear()

            saleNumberState = sale.optString("sale_number", "")
            val createdAt = sale.optString("created_at", "")
            val customerName = sale.optString("customer_name", "")
            val customerPhone = sale.optString("customer_phone", "")

            val meta = StringBuilder()
            if (createdAt.isNotEmpty()) {
                meta.append(if (createdAt.length > 16) createdAt.substring(0, 16) else createdAt)
            }
            if (customerName.isNotEmpty()) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append(customerName)
            }
            if (customerPhone.isNotEmpty()) {
                if (meta.isNotEmpty()) meta.append(" · ")
                meta.append(customerPhone)
            }
            saleMetaState = meta.toString()

            creditCreatedState = sale.optDouble("credit_created_local", 0.0)
            renderPayments(sale)

            val items = sale.optJSONArray("items")
            if (items != null && items.length() > 0) {
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val isRefunded = it.optBoolean("is_refunded", false)
                    val isScanned = it.optBoolean("is_scanned", false)

                    itemRowsState.add(
                        ItemRow(
                            itemId = it.optInt("item_id", 0),
                            title = it.optString("item_title", if (isArabic) "قطعة" else "Item"),
                            karat = it.optString("gold_karat", "21K"),
                            weight = it.optDouble("net_weight_grams", 0.0),
                            price = it.optDouble("line_total_local", 0.0),
                            isRefunded = isRefunded,
                            isScanned = isScanned,
                            isSelected = !isRefunded,
                            condition = "in_stock",
                            epc = firstNonEmpty(it, "epc", "rfid_epc", "epc_final"),
                            serialNo = firstNonEmpty(it, "serial", "serial_number", "serialNo", "sn"),
                            gtin = firstNonEmpty(it, "gtin", "gtin13", "barcode", "code"),
                            tid = firstNonEmpty(it, "tid", "tag_tid", "rfid_tid"),
                            rfidEpc = firstNonEmpty(it, "rfid_epc", "epc_final"),
                            itemCode = firstNonEmpty(it, "item_code")
                        )
                    )
                }
            }

            hasCustomerState = sale.optInt("customer_id", 0) > 0
            val defaultMethod = sale.optString("default_refund_method", "cash")
            creditRecommendedState = "credit".equals(defaultMethod, ignoreCase = true)

            selectedMethodState = if (creditRecommendedState && hasCustomerState) {
                "credit"
            } else {
                "cash"
            }

            refreshCreditBalanceWarning()
            screenState = ScreenState.SALE
        } catch (e: Exception) {
            Log.e(TAG, "renderSale error", e)
            screenState = ScreenState.EMPTY
            toast("⚠️ ${e.message}")
        }
    }

    private fun renderPayments(sale: JSONObject) {
        try {
            val pay = sale.optJSONArray("payments_summary")
            if (pay == null || pay.length() == 0) {
                paymentsSummaryState = "—"
                return
            }

            val sb = StringBuilder()
            for (i in 0 until pay.length()) {
                val p = pay.getJSONObject(i)
                val name = p.optString("method_name_ar", p.optString("method", "CASH"))
                val yer = p.optDouble("amount_in_yer", 0.0)
                sb.append("• ").append(name).append(": ")
                    .append(String.format(Locale.US, "%,.0f", yer))
                    .append(" YER\n")
            }

            var text = sb.toString().trim()
            if (creditCreatedState > 0.01) {
                text += "\n💰 أنشأت رصيداً: ${String.format(Locale.US, "%,.0f", creditCreatedState)} YER"
            }
            paymentsSummaryState = text
        } catch (e: Exception) {
            Log.e(TAG, "renderPayments error", e)
        }
    }

    private fun setMethod(method: String) {
        if (method == "credit" && !hasCustomerState) return
        selectedMethodState = method
        refreshCreditBalanceWarning()
    }

    private fun refreshCreditBalanceWarning() {
        if (creditRecommendedState && selectedMethodState == "cash" && creditCreatedState > 0.01) {
            bannerIsErrorState = false
            bannerMessageState = if (isArabic) {
                "⚠️ هذه الفاتورة أنشأت رصيداً دائناً بقيمة ${String.format(Locale.US, "%,.0f", creditCreatedState)} YER. الإرجاع كرصيد يحفظ التوازن."
            } else {
                "⚠️ This sale created ${String.format(Locale.US, "%,.0f", creditCreatedState)} YER credit. Refunding as credit keeps balance."
            }
        } else {
            bannerMessageState = null
        }
    }

    private fun submitRefund() {
        val sale = currentSale ?: return

        val selectedItems = itemRowsState.filter { !it.isRefunded && it.isSelected }
        if (selectedItems.isEmpty()) {
            toast(if (isArabic) "⚠️ اختر قطعة واحدة على الأقل" else "⚠️ Select at least one item")
            return
        }
        if (selectedMethodState == "credit" && !hasCustomerState) {
            toast(if (isArabic) "⚠️ الإرجاع كرصيد يتطلب عميلاً مسجلاً" else "⚠️ Credit refund requires registered customer")
            return
        }

        isSubmittingState = true

        try {
            val itemsArr = JSONArray()
            for (ir in selectedItems) {
                val o = JSONObject().apply {
                    put("item_id", ir.itemId)
                    put("condition", ir.condition)
                }
                itemsArr.put(o)
            }

            val payload = JSONObject().apply {
                put("sale_number", sale.optString("sale_number", ""))
                put("refund_method", selectedMethodState)
                put("items", itemsArr)
            }

            Thread {
                try {
                    val client = MiraApiClient.getInstance(context.applicationContext)
                    val resp = client.processRefundV2(payload)

                    handler.post {
                        if (resp != null && resp.isSuccess) {
                            var result = resp.data ?: JSONObject()
                            if (result.has("data") && result.optJSONObject("data") != null) {
                                result = result.optJSONObject("data")!!
                            }

                            invalidateCacheForRefundedItems()

                            try {
                                val existingItems = result.optJSONArray("items")
                                if (existingItems == null || existingItems.length() == 0) {
                                    val currentSaleId = sale.optLong(
                                        "id",
                                        sale.optLong("sale_id", 0L)
                                    )
                                    val enrichedItems = JSONArray()
                                    for (ir in itemRowsState) {
                                        if (ir.isRefunded || !ir.isSelected) continue
                                        val io = JSONObject().apply {
                                            put("epc", ir.epc)
                                            put("serial", ir.serialNo)
                                            put("gtin", ir.gtin)
                                            put("tid", ir.tid)
                                            put("rfid_epc", ir.rfidEpc)
                                            put("item_id", ir.itemId)
                                            put("sale_id", currentSaleId)
                                        }
                                        enrichedItems.put(io)
                                    }
                                    if (enrichedItems.length() > 0) {
                                        result.put("items", enrichedItems)
                                        Log.i(TAG, "✅ Injected ${enrichedItems.length()} items into refund result")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "enrich result error", e)
                            }

                            callback?.onRefundSuccess(result)
                            dismiss()
                        } else {
                            val err = resp?.error
                                ?: if (isArabic) "فشل الإرجاع" else "Refund failed"
                            toast("❌ $err")
                            showRefundItemError(err)
                            isSubmittingState = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "submitRefund error", e)
                    handler.post {
                        toast("⚠️ ${e.message}")
                        isSubmittingState = false
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "submitRefund payload error", e)
            isSubmittingState = false
        }
    }

    private fun invalidateCacheForRefundedItems() {
        try {
            val appCtx = context.applicationContext ?: return
            val cache = MiraLocalItemCache.getInstance(appCtx) ?: return

            var invalidated = 0
            var itemsProcessed = 0

            for (ir in itemRowsState) {
                if (ir.isRefunded || !ir.isSelected) continue
                itemsProcessed++

                if (ir.epc.isNotEmpty()) {
                    cache.markItemAsAvailable(ir.epc)
                    invalidated++
                }
                if (ir.serialNo.isNotEmpty() && ir.serialNo != ir.epc) {
                    cache.markItemAsAvailable(ir.serialNo)
                    invalidated++
                }
                if (ir.gtin.isNotEmpty()) {
                    cache.markItemAsAvailable(ir.gtin)
                    invalidated++
                }
                if (ir.tid.isNotEmpty()) {
                    cache.markItemAsAvailable(ir.tid)
                    invalidated++
                }
                if (ir.rfidEpc.isNotEmpty() && ir.rfidEpc != ir.epc) {
                    cache.markItemAsAvailable(ir.rfidEpc)
                    invalidated++
                }
                if (ir.itemCode.isNotEmpty()) {
                    cache.markItemAsAvailable(ir.itemCode)
                    invalidated++
                }
            }

            Log.i(TAG, "✅ Cache invalidated: $invalidated identifiers for $itemsProcessed refunded item(s)")
        } catch (e: Exception) {
            Log.e(TAG, "invalidateCacheForRefundedItems error", e)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun close() {
        try {
            callback?.onDialogClosed()
        } catch (_: Exception) {
        }
        dismiss()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Public API (100% Compatible with MiraPosFragment)
    // ═══════════════════════════════════════════════════════════════════

    fun setQueryFromScan(code: String?) {
        if (code == null) return
        suppressWatcher = true
        try {
            queryState = code
        } finally {
            suppressWatcher = false
        }
        pendingSearch?.let { handler.removeCallbacks(it) }
        handler.postDelayed({ performSearch() }, 150)
    }

    @Deprecated("استخدم setQueryFromScan بدلاً منها")
    fun setRefundCode(code: String?) {
        setQueryFromScan(code)
    }

    fun setOnHardwareKeyListener(listener: DialogInterface.OnKeyListener?) {
        this.hardwareKeyListener = listener
    }

    fun showRefundItemInfo(title: String?, details: String?) {
        val sb = StringBuilder()
        if (!title.isNullOrEmpty()) sb.append("📦 ").append(title)
        if (!details.isNullOrEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(details)
        }
        if (sb.isNotEmpty()) {
            bannerIsErrorState = false
            bannerMessageState = sb.toString()
        }
    }

    fun showRefundItemError(message: String?) {
        if (message == null) return
        bannerIsErrorState = true
        bannerMessageState = "⚠️ $message"
        toast("⚠️ $message")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        try {
            val code = event.keyCode
            val isTrigger = code == KEYCODE_RFID_TRIGGER_1 ||
                code == KEYCODE_RFID_TRIGGER_2 ||
                code == KEYCODE_RFID_TRIGGER_3
            if (isTrigger && hardwareKeyListener != null) {
                return hardwareKeyListener!!.onKey(this, code, event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatchKeyEvent error", e)
        }
        return super.dispatchKeyEvent(event)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun RefundDialogContent() {
        val colors = MiraTheme.colors
        val isAr = isArabic

        val selectedCount = itemRowsState.count { !it.isRefunded && it.isSelected }
        val refundTotalYer = itemRowsState
            .filter { !it.isRefunded && it.isSelected }
            .sumOf { it.price }

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
                // 1. Header
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
                                .background(Color(0xFFF59E0B).copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.AssignmentReturn,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isAr) "استرجاع القطع" else "Item Refund",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isAr) "ابحث برقم الفاتورة أو امسح القطعة" else "Search invoice or scan item",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (!isSubmittingState) close() },
                        enabled = !isSubmittingState
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }

                Divider(color = colors.goldPrimary.copy(alpha = 0.2f))

                // 2. Search Bar + Barcode Scan Trigger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = queryState,
                        onValueChange = { onQueryChanged(it) },
                        enabled = !isSubmittingState,
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = if (isAr) "INV-... أو كود القطعة / RFID" else "INV-... or Item Serial / EPC",
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (queryState.isNotEmpty()) {
                                IconButton(onClick = { onQueryChanged("") }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )

                    FilledTonalIconButton(
                        onClick = { callback?.onBarcodeScan() },
                        enabled = !isSubmittingState,
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF06B6D4).copy(alpha = 0.18f),
                            contentColor = Color(0xFF06B6D4)
                        )
                    ) {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Info / Warning / Error Banner
                AnimatedVisibility(visible = !bannerMessageState.isNullOrEmpty()) {
                    val bannerColor = if (bannerIsErrorState) Color(0xFFEF4444) else Color(0xFFF59E0B)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = bannerColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = bannerMessageState ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = bannerColor,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // 3. Body by State
                when (screenState) {
                    ScreenState.EMPTY -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 24.dp),
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
                                    Icons.Rounded.ReceiptLong,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Text(
                                text = if (isAr) "بانتظار تحديد الفاتورة أو القطعة" else "Waiting for invoice or item",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isAr)
                                    "اكتب رقم الفاتورة، امسح الباركود بالكاميرا، أو اضغط زناد قارئ RFID للقطعة"
                                else
                                    "Enter invoice number, scan barcode, or pull RFID trigger",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                    }

                    ScreenState.LOADING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 42.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = colors.goldPrimary)
                            Text(
                                text = if (isAr) "جاري البحث عن الفاتورة..." else "Searching sale...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }

                    ScreenState.SALE -> {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // بطاقة بيانات الفاتورة والمدفوعات السابقة
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = colors.backgroundMid,
                                border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.25f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = saleNumberState,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = colors.goldPrimary
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF10B981).copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = if (isAr) "فاتورة صالحة" else "Valid Sale",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF10B981),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    if (saleMetaState.isNotEmpty()) {
                                        Text(
                                            text = saleMetaState,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
                                        )
                                    }

                                    Divider(
                                        color = colors.textSecondary.copy(alpha = 0.14f),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )

                                    Text(
                                        text = if (isAr) "تفاصيل السداد الأصلي:" else "Original Payments:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textSecondary
                                    )
                                    Text(
                                        text = paymentsSummaryState,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textPrimary
                                    )
                                }
                            }

                            // اختيار طريقة الاسترجاع (نقداً / رصيد دائن)
                            Text(
                                text = if (isAr) "طريقة رد المبلغ" else "Refund Method",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RefundMethodCard(
                                    modifier = Modifier.weight(1f),
                                    title = if (isAr) "نقداً (صندوق)" else "Cash Refund",
                                    subtitle = if (isAr) "رد المبلغ نقداً للعميل" else "Return cash directly",
                                    icon = Icons.Rounded.Payments,
                                    accentColor = Color(0xFF3B82F6),
                                    selected = selectedMethodState == "cash",
                                    enabled = !isSubmittingState,
                                    onClick = { setMethod("cash") }
                                )

                                RefundMethodCard(
                                    modifier = Modifier.weight(1f),
                                    title = if (isAr) "رصيد دائن" else "Store Credit",
                                    subtitle = if (!hasCustomerState) {
                                        if (isAr) "يتطلب عميلاً مسجلاً" else "Registered customer only"
                                    } else {
                                        if (isAr) "يُحفظ في حساب العميل" else "Credit customer account"
                                    },
                                    icon = Icons.Rounded.AccountBalanceWallet,
                                    accentColor = Color(0xFF10B981),
                                    selected = selectedMethodState == "credit",
                                    enabled = !isSubmittingState && hasCustomerState,
                                    onClick = { setMethod("credit") }
                                )
                            }

                            // قائمة القطع في الفاتورة
                            Text(
                                text = if (isAr) "اختر القطع المراد استرجاعها" else "Select Items to Refund",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )

                            itemRowsState.forEachIndexed { index, item ->
                                RefundItemCard(
                                    item = item,
                                    isArabic = isAr,
                                    enabled = !isSubmittingState,
                                    onToggleSelect = { checked ->
                                        itemRowsState[index] = item.copy(isSelected = checked)
                                    },
                                    onConditionChanged = { newCond ->
                                        itemRowsState[index] = item.copy(condition = newCond)
                                    }
                                )
                            }
                        }
                    }
                }

                Divider(color = colors.textSecondary.copy(alpha = 0.14f))

                // 4. Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.backgroundMid)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { close() },
                        enabled = !isSubmittingState,
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

                    Button(
                        onClick = { submitRefund() },
                        enabled = !isSubmittingState && screenState == ScreenState.SALE && selectedCount > 0,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF59E0B),
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        if (isSubmittingState) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF0A0F1D)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAr) "جاري التنفيذ..." else "Processing...",
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(Icons.Rounded.AssignmentReturn, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (screenState == ScreenState.SALE && selectedCount > 0) {
                                    if (isAr) {
                                        "تأكيد الإرجاع (${String.format(Locale.US, "%,.0f", refundTotalYer)} YER)"
                                    } else {
                                        "Confirm Refund (${String.format(Locale.US, "%,.0f", refundTotalYer)} YER)"
                                    }
                                } else {
                                    if (isAr) "تأكيد الإرجاع" else "Confirm Refund"
                                },
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RefundMethodCard(
        modifier: Modifier = Modifier,
        title: String,
        subtitle: String,
        icon: ImageVector,
        accentColor: Color,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val borderColor = if (selected) accentColor else colors.textSecondary.copy(alpha = 0.22f)
        val bgColor = if (selected) accentColor.copy(alpha = 0.14f) else colors.backgroundMid

        Surface(
            modifier = modifier
                .alpha(if (enabled) 1f else 0.45f)
                .clickable(enabled = enabled) { onClick() },
            shape = RoundedCornerShape(14.dp),
            color = bgColor,
            border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) accentColor else colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) colors.textPrimary else colors.textSecondary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun RefundItemCard(
        item: ItemRow,
        isArabic: Boolean,
        enabled: Boolean,
        onToggleSelect: (Boolean) -> Unit,
        onConditionChanged: (String) -> Unit
    ) {
        val colors = MiraTheme.colors
        var condMenuExpanded by remember { mutableStateOf(false) }

        val conditions = remember(isArabic) {
            listOf(
                "in_stock" to (if (isArabic) "حالة ممتازة (مخزون)" else "Excellent (in stock)"),
                "scrapped" to (if (isArabic) "كسر (تالف)" else "Scrapped"),
                "damaged" to (if (isArabic) "تالف (فحص)" else "Damaged")
            )
        }

        val currentConditionLabel = conditions.firstOrNull { it.first == item.condition }?.second
            ?: conditions.first().second

        val cardBg = when {
            item.isRefunded -> colors.backgroundMid.copy(alpha = 0.55f)
            item.isScanned -> Color(0xFF3B82F6).copy(alpha = 0.14f)
            else -> colors.backgroundMid
        }

        val cardBorder = when {
            item.isScanned && !item.isRefunded -> Color(0xFF3B82F6).copy(alpha = 0.55f)
            item.isSelected && !item.isRefunded -> colors.goldPrimary.copy(alpha = 0.35f)
            else -> colors.textSecondary.copy(alpha = 0.16f)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = !item.isRefunded && item.isSelected,
                        onCheckedChange = { if (!item.isRefunded) onToggleSelect(it) },
                        enabled = enabled && !item.isRefunded,
                        colors = CheckboxDefaults.colors(
                            checkedColor = colors.goldPrimary,
                            checkmarkColor = Color(0xFF0A0F1D)
                        )
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (item.isScanned && !item.isRefunded) "${item.title}  📷" else item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (item.isRefunded) colors.textSecondary else colors.textPrimary,
                                textDecoration = if (item.isRefunded) TextDecoration.LineThrough else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (item.isRefunded) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFEF4444).copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = if (isArabic) "مُرتجع" else "Refunded",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFEF4444),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = String.format(Locale.US, "%s · %.3fg", item.karat, item.weight),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = String.format(Locale.US, "%,.0f YER", item.price),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (item.isRefunded) Color(0xFF9CA3AF) else colors.goldPrimary,
                        textDecoration = if (item.isRefunded) TextDecoration.LineThrough else null
                    )
                }

                // قائمة تحديد حالة القطعة عند إرجاعها
                if (!item.isRefunded && item.isSelected) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { condMenuExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            color = colors.cardSurface,
                            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentConditionLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = condMenuExpanded,
                            onDismissRequest = { condMenuExpanded = false }
                        ) {
                            conditions.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            fontWeight = if (code == item.condition) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        condMenuExpanded = false
                                        onConditionChanged(code)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
