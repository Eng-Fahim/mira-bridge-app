package com.example.uhf.dialog

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.uhf.api.MiraApiClient
import com.example.uhf.api.dto.PaymentMethodDto
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

class POSCheckoutDialog : DialogFragment() {

    companion object {
        private const val TAG = "POSCheckoutDialog"
        private const val ARG_TOTAL_AMOUNT = "total_amount"
        private const val ARG_ITEM_COUNT = "item_count"
        private const val ARG_RATES_JSON = "rates_json"
        private const val ARG_SCRAP_DETAILS_JSON = "scrap_details_json"
        private const val ARG_USD_TO_YER_RATE = "usd_to_yer_rate"
        private const val MATCH_TOLERANCE_YER = 5.0

        @JvmStatic
        fun newInstance(
            totalAmount: Double,
            itemCount: Int,
            ratesJson: String?,
            scrapDetailsJson: String?,
            usdToYerRate: Double
        ): POSCheckoutDialog {
            return POSCheckoutDialog().apply {
                arguments = Bundle().apply {
                    putDouble(ARG_TOTAL_AMOUNT, totalAmount)
                    putInt(ARG_ITEM_COUNT, itemCount)
                    putString(ARG_RATES_JSON, ratesJson ?: "{}")
                    putString(ARG_SCRAP_DETAILS_JSON, scrapDetailsJson ?: "{}")
                    putDouble(ARG_USD_TO_YER_RATE, usdToYerRate)
                }
            }
        }

        @JvmStatic
        fun newInstance(
            totalAmount: Double,
            itemCount: Int,
            ratesJson: String?,
            scrapDetailsJson: String?
        ): POSCheckoutDialog {
            return newInstance(totalAmount, itemCount, ratesJson, scrapDetailsJson, 0.0)
        }

        @JvmStatic
        fun newInstance(totalAmount: Double, itemCount: Int, ratesJson: String?): POSCheckoutDialog {
            return newInstance(totalAmount, itemCount, ratesJson, "{}", 0.0)
        }

        @JvmStatic
        fun newInstance(totalAmount: Double, itemCount: Int): POSCheckoutDialog {
            return newInstance(totalAmount, itemCount, "{}", "{}", 0.0)
        }
    }

    interface OnCheckoutListener {
        fun onCheckout(data: POSCheckoutData?)
    }

    interface OnDialogDismissListener {
        fun onDialogDismissed()
    }

    class POSCheckoutData {
        @JvmField var customerName: String = ""
        @JvmField var customerPhone: String = ""
        @JvmField var customerNationalId: String = ""
        @JvmField var customerEmail: String = ""
        @JvmField var customerAddress: String = ""
        @JvmField var customerNotes: String = ""
        @JvmField var paymentMethod: String = ""
        @JvmField var accountCode: String = ""
        @JvmField var currency: String = "MIX"
        @JvmField var originalTotal: Double = 0.0
        @JvmField var discount: Double = 0.0
        @JvmField var discountAmount: Double = 0.0
        @JvmField var finalAmount: Double = 0.0
        @JvmField var usdToYerRate: Double = 0.0
        @JvmField var payments: JSONArray = JSONArray()
        @JvmField var tradeIns: JSONArray = JSONArray()
        @JvmField var tradeInTotal: Double = 0.0
        @JvmField var localUuid: String = ""
    }

    private data class PaymentRowUiModel(
        val id: String = UUID.randomUUID().toString(),
        val method: PaymentMethodDto,
        val currency: String = "YER",
        val amountText: String = ""
    )

    private var totalAmount: Double = 0.0
    private var itemCount: Int = 0
    private var scrapDetailsJson: String = "{}"
    private var listener: OnCheckoutListener? = null
    private var dismissListener: OnDialogDismissListener? = null
    private var isArabic: Boolean = true
    private var priceManager: MiraPriceManager? = null

    private val ratesToYerMap = HashMap<String, Double>()
    private var serverUsdToYerRate: Double = 0.0

    private var tradeInLines = JSONArray()
    private var currentTotalWithDiscountUsd: Double = 0.0

    private val paymentMethodsState = mutableStateListOf<PaymentMethodDto>()
    private val currenciesState = mutableStateListOf("YER")
    private val paymentRowsState = mutableStateListOf<PaymentRowUiModel>()

    private var customerNameState by mutableStateOf("")
    private var customerPhoneState by mutableStateOf("")
    private var customerNationalIdState by mutableStateOf("")

    private var tradeInTotalState by mutableDoubleStateOf(0.0)
    private var inlineErrorState by mutableStateOf<String?>(null)
    private var isSubmittingState by mutableStateOf(false)
    private var isLoadingConfigState by mutableStateOf(true)

    fun setCheckoutListener(listener: OnCheckoutListener?) {
        this.listener = listener
    }

    fun setOnDismissListener(listener: OnDialogDismissListener?) {
        this.dismissListener = listener
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        try {
            dismissListener?.onDialogDismissed()
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        context?.let {
            priceManager = MiraPriceManager.getInstance(it)
        }

        val pmYerRate = try {
            priceManager?.getExchangeRate("YER") ?: 0.0
        } catch (_: Exception) {
            0.0
        }
        serverUsdToYerRate = if (pmYerRate > 0.0 && pmYerRate.isFinite()) pmYerRate else 0.0

        val initialCurrencies = LinkedHashSet<String>().apply { add("YER") }
        ratesToYerMap["YER"] = 1.0

        arguments?.let { args ->
            totalAmount = args.getDouble(ARG_TOTAL_AMOUNT, 0.0)
            itemCount = args.getInt(ARG_ITEM_COUNT, 0)
            currentTotalWithDiscountUsd = totalAmount
            scrapDetailsJson = args.getString(ARG_SCRAP_DETAILS_JSON, "{}") ?: "{}"

            val explicitUsdRate = args.getDouble(ARG_USD_TO_YER_RATE, 0.0)
            if (explicitUsdRate > 0.0 && explicitUsdRate.isFinite()) {
                serverUsdToYerRate = explicitUsdRate
                ratesToYerMap["USD"] = explicitUsdRate
                initialCurrencies.add("USD")
                priceManager?.setAuthoritativeExchangeRate("YER", explicitUsdRate)
            }

            try {
                val ratesJson = args.getString(ARG_RATES_JSON, "{}") ?: "{}"
                val ratesObj = JSONObject(ratesJson)

                val srvUsd = ratesObj.optDouble("USD", 0.0)
                val srvYer = ratesObj.optDouble("YER", 0.0)
                if (srvUsd > 0.0 && srvUsd.isFinite()) {
                    serverUsdToYerRate = srvUsd
                    ratesToYerMap["USD"] = srvUsd
                    initialCurrencies.add("USD")
                    priceManager?.setAuthoritativeExchangeRate("YER", srvUsd)
                } else if (srvYer > 1.0 && srvYer.isFinite()) {
                    serverUsdToYerRate = srvYer
                    ratesToYerMap["USD"] = srvYer
                    initialCurrencies.add("USD")
                    priceManager?.setAuthoritativeExchangeRate("YER", srvYer)
                }

                val keys = ratesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().uppercase(Locale.US).trim()
                    val v = ratesObj.optDouble(k, 0.0)
                    if (v <= 0.0 || !v.isFinite()) continue
                    if (k == "YER") {
                        ratesToYerMap["YER"] = 1.0
                        initialCurrencies.add("YER")
                    } else {
                        ratesToYerMap[k] = v
                        initialCurrencies.add(k)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse ARG_RATES_JSON: ${e.message}")
            }
        }

        if (serverUsdToYerRate > 0.0) {
            ratesToYerMap["USD"] = serverUsdToYerRate
            initialCurrencies.add("USD")
        }

        currenciesState.clear()
        currenciesState.addAll(initialCurrencies)

        isArabic = try {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (tags.isNotEmpty()) tags.contains("ar")
            else Locale.getDefault().language == "ar"
        } catch (_: Exception) {
            Locale.getDefault().language == "ar"
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
                        CheckoutDialogContent()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadServerConfig()
    }

    private fun loadServerConfig() {
        val appCtx = context?.applicationContext ?: return
        isLoadingConfigState = true

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchConfigFromServer(appCtx)
            }

            if (!isAdded) return@launch
            isLoadingConfigState = false

            if (result.methods.isNotEmpty()) {
                paymentMethodsState.clear()
                paymentMethodsState.addAll(result.methods)

                val defaultMethod = result.methods.first()
                if (paymentRowsState.isEmpty()) {
                    paymentRowsState.add(
                        PaymentRowUiModel(
                            method = defaultMethod,
                            currency = "YER",
                            amountText = ""
                        )
                    )
                } else {
                    for (i in paymentRowsState.indices) {
                        val row = paymentRowsState[i]
                        val matched = result.methods.firstOrNull {
                            it.id > 0 && it.id == row.method.id
                        } ?: result.methods.firstOrNull {
                            !it.code.isNullOrBlank() &&
                                it.code.equals(row.method.code, ignoreCase = true)
                        } ?: defaultMethod
                        paymentRowsState[i] = row.copy(method = matched)
                    }
                }
            } else {
                inlineErrorState = if (isArabic) {
                    "⚠️ تعذر تحميل طرق الدفع من السيرفر: ${result.methodsError ?: "لا توجد طرق دفع نشطة"}"
                } else {
                    "⚠️ Failed to load payment methods: ${result.methodsError ?: "No active payment methods"}"
                }
            }

            for ((curr, rateVal) in result.ratesToYer) {
                if (rateVal > 0.0 && rateVal.isFinite()) {
                    ratesToYerMap[curr] = rateVal
                }
            }

            val usdFromServer = result.ratesToYer["USD"] ?: 0.0
            if (usdFromServer > 0.0) {
                serverUsdToYerRate = usdFromServer
                priceManager?.setAuthoritativeExchangeRate("YER", usdFromServer)
            }

            val mergedCurrencies = LinkedHashSet<String>()
            mergedCurrencies.add("YER")
            mergedCurrencies.addAll(currenciesState)
            for ((curr, rateVal) in ratesToYerMap) {
                if (rateVal > 0.0 && rateVal.isFinite()) {
                    mergedCurrencies.add(curr)
                }
            }
            currenciesState.clear()
            currenciesState.addAll(mergedCurrencies)
        }
    }

    private data class ServerConfigResult(
        val methods: List<PaymentMethodDto>,
        val ratesToYer: Map<String, Double>,
        val methodsError: String? = null
    )

    private fun fetchConfigFromServer(appCtx: Context): ServerConfigResult {
        val methods = ArrayList<PaymentMethodDto>()
        val extractedRates = HashMap<String, Double>()
        var methodsError: String? = null

        try {
            val client = MiraApiClient.getInstance(appCtx)

            // 1. GET /api/v1/payment-methods -> PaymentMethodController@index
            val methodsResp = client.paymentMethods
            var arr: JSONArray? = null

            if (methodsResp != null && methodsResp.isSuccess) {
                arr = extractMethodsArrayFromResponse(methodsResp)
            }

            // مسار احتياطي مباشر إذا لم يحتفظ ApiResponse بالمصفوفة داخل حقوله
            if (arr == null) {
                arr = fetchMethodsDirectFallback(appCtx, client)
            }

            if (arr != null) {
                val gson = Gson()
                for (i in 0 until arr.length()) {
                    try {
                        val obj = arr.getJSONObject(i)
                        val dto = parsePaymentMethodDto(gson, obj)
                        if (dto != null && !dto.code.isNullOrBlank()) {
                            methods.add(dto)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Malformed payment method item at $i", e)
                    }
                }
                if (methods.isEmpty() && arr.length() == 0) {
                    methodsError = "0 active payment methods returned"
                }
            } else {
                methodsError = methodsResp?.error ?: "HTTP ${methodsResp?.statusCode ?: -1}"
            }

            // 2. GET /api/v1/currency/rates -> ExchangeRateController@current
            val ratesResp = client.exchangeRates
            if (ratesResp != null && ratesResp.isSuccess && ratesResp.data != null) {
                val payload = ratesResp.data.optJSONObject("data") ?: ratesResp.data
                val keys = payload.keys()
                while (keys.hasNext()) {
                    val key = keys.next().trim()
                    val lower = key.lowercase(Locale.US)
                    if (lower.endsWith("_to_yer")) {
                        val currCode = lower.removeSuffix("_to_yer").uppercase(Locale.US).trim()
                        val valRate = payload.optDouble(key, 0.0)
                        if (currCode.isNotEmpty() && valRate > 0.0 && valRate.isFinite()) {
                            extractedRates[currCode] = if (currCode == "YER") 1.0 else valRate
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchConfigFromServer error", e)
            methodsError = e.message
        }

        return ServerConfigResult(
            methods = methods,
            ratesToYer = extractedRates,
            methodsError = methodsError
        )
    }

    private fun extractMethodsArrayFromResponse(resp: MiraApiClient.ApiResponse): JSONArray? {
        resp.data?.let { d ->
            d.optJSONArray("data")?.let { return it }
            d.optJSONArray("payment_methods")?.let { return it }
            d.optJSONArray("methods")?.let { return it }
        }

        val allFields = (resp.javaClass.fields.toList() + resp.javaClass.declaredFields.toList())
            .distinctBy { it.name }
        for (field in allFields) {
            try {
                field.isAccessible = true
                when (val value = field.get(resp)) {
                    is JSONArray -> if (value.length() > 0) return value
                    is JSONObject -> {
                        value.optJSONArray("data")?.let { return it }
                    }
                    is String -> {
                        val trimmed = value.trim()
                        if (trimmed.startsWith("{")) {
                            JSONObject(trimmed).optJSONArray("data")?.let { return it }
                        } else if (trimmed.startsWith("[")) {
                            return JSONArray(trimmed)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun extractTokenFromObject(target: Any?): String? {
        if (target == null) return null
        val cls = target.javaClass

        val methods = (cls.methods.toList() + cls.declaredMethods.toList()).distinctBy { it.name }
        for (m in methods) {
            if (m.parameterCount == 0 &&
                m.returnType == String::class.java &&
                m.name.contains("token", ignoreCase = true)
            ) {
                try {
                    m.isAccessible = true
                    val v = m.invoke(target) as? String
                    if (!v.isNullOrBlank()) return v.trim()
                } catch (_: Exception) {
                }
            }
        }

        val fields = (cls.fields.toList() + cls.declaredFields.toList()).distinctBy { it.name }
        for (f in fields) {
            if (f.type == String::class.java && f.name.contains("token", ignoreCase = true)) {
                try {
                    f.isAccessible = true
                    val v = f.get(target) as? String
                    if (!v.isNullOrBlank()) return v.trim()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun resolveAuthToken(appCtx: Context, client: MiraApiClient): String? {
        extractTokenFromObject(client)?.let { return it }
        try {
            val session = SessionManager(appCtx)
            extractTokenFromObject(session)?.let { return it }
        } catch (_: Exception) {
        }
        return null
    }

    private fun fetchMethodsDirectFallback(
        appCtx: Context,
        client: MiraApiClient
    ): JSONArray? {
        var conn: HttpURLConnection? = null
        return try {
            val token = resolveAuthToken(appCtx, client)
            if (token.isNullOrBlank()) return null

            var baseUrl: String? = null
            val fields = (client.javaClass.fields.toList() + client.javaClass.declaredFields.toList())
                .distinctBy { it.name }
            for (f in fields) {
                if (f.type == String::class.java) {
                    f.isAccessible = true
                    val v = f.get(client) as? String
                    if (!v.isNullOrBlank() && (v.startsWith("http://") || v.startsWith("https://"))) {
                        baseUrl = v.trimEnd('/')
                        break
                    }
                }
            }
            if (baseUrl.isNullOrBlank()) return null

            val endpoint = if (baseUrl.endsWith("/api/v1")) {
                "$baseUrl/payment-methods"
            } else {
                "$baseUrl/api/v1/payment-methods"
            }

            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }

            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
                    .use { it.readText() }
                    .trim()
                if (body.startsWith("{")) {
                    JSONObject(body).optJSONArray("data")
                } else if (body.startsWith("[")) {
                    JSONArray(body)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchMethodsDirectFallback failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parsePaymentMethodDto(gson: Gson, obj: JSONObject): PaymentMethodDto? {
        return try {
            gson.fromJson(obj.toString(), PaymentMethodDto::class.java)
        } catch (_: Exception) {
            try {
                val normalized = JSONObject(obj.toString())
                if (normalized.has("requires_account")) {
                    val rawReq = normalized.opt("requires_account")
                    if (rawReq is Number) {
                        normalized.put("requires_account", rawReq.toInt() != 0)
                    } else if (rawReq is Boolean) {
                        normalized.put("requires_account", if (rawReq) 1 else 0)
                    }
                }
                gson.fromJson(normalized.toString(), PaymentMethodDto::class.java)
            } catch (e2: Exception) {
                Log.w(TAG, "Failed parsing PaymentMethodDto: ${e2.message}")
                null
            }
        }
    }

    private fun getRateToYER(currency: String?): Double {
        if (currency == null) return 0.0
        val curr = currency.uppercase(Locale.US).trim()
        if (curr.isEmpty()) return 0.0
        if (curr == "YER") return 1.0

        val mappedRate = ratesToYerMap[curr]
        if (mappedRate != null && mappedRate > 0.0 && mappedRate.isFinite()) {
            return mappedRate
        }

        if (curr == "USD") {
            if (serverUsdToYerRate > 0.0 && serverUsdToYerRate.isFinite()) {
                return serverUsdToYerRate
            }
            val pmRate = priceManager?.getExchangeRate("YER") ?: 0.0
            if (pmRate > 0.0 && pmRate.isFinite()) {
                serverUsdToYerRate = pmRate
                ratesToYerMap["USD"] = pmRate
                return pmRate
            }
        }

        return 0.0
    }

    private fun parseDouble(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        return try {
            s.trim().replace(",", "").toDouble()
        } catch (_: Exception) {
            0.0
        }
    }

    private fun getTotalPaidYER(): Double {
        var total = 0.0
        for (row in paymentRowsState) {
            val amt = parseDouble(row.amountText)
            val rate = getRateToYER(row.currency)
            if (amt > 0.0 && rate > 0.0) {
                total += amt * rate
            }
        }
        return total
    }

    private fun formatFinalTotalUsdDisplay(): String {
        return String.format(Locale.US, "$%,.2f", currentTotalWithDiscountUsd)
    }

    private fun formatItemCount(count: Int): String {
        if (isArabic) {
            return when {
                count == 0 -> "0 قطعة"
                count == 1 -> "قطعة واحدة"
                count == 2 -> "قطعتان"
                count <= 10 -> "$count قطع"
                else -> "$count قطعة"
            }
        }
        return count.toString() + if (count == 1) " item" else " items"
    }

    private fun openTradeInDialog() {
        val usdRate = getRateToYER("USD")
        if (usdRate <= 0.0) {
            inlineErrorState = if (isArabic) {
                "⚠️ تعذر تحديد سعر صرف الدولار من السيرفر"
            } else {
                "⚠️ Server USD exchange rate is unavailable"
            }
            return
        }
        val maxValue = currentTotalWithDiscountUsd * usdRate
        val tradeDialog = TradeInDialog.newInstance(maxValue, tradeInLines, scrapDetailsJson)
        tradeDialog.setOnTradeInListener { tradeIns, totalValue ->
            this.tradeInLines = tradeIns
            this.tradeInTotalState = totalValue
            inlineErrorState = null
        }
        tradeDialog.show(parentFragmentManager, "TradeInDialog")
    }

    private fun validateBeforeSubmit(): String? {
        if (paymentMethodsState.isEmpty()) {
            return if (isArabic) {
                "⚠️ لا تتوفر طرق دفع محملة من السيرفر"
            } else {
                "⚠️ No payment methods loaded from server"
            }
        }

        val usdRate = getRateToYER("USD")
        if (currentTotalWithDiscountUsd > 0.0 && usdRate <= 0.0) {
            return if (isArabic) {
                "⚠️ لا يتوفر سعر صرف معتمد من السيرفر لإتمام التحويل"
            } else {
                "⚠️ Server exchange rate is required to complete checkout"
            }
        }

        for (row in paymentRowsState) {
            val amt = parseDouble(row.amountText)
            if (amt > 0.0 && getRateToYER(row.currency) <= 0.0) {
                return if (isArabic) {
                    "⚠️ لا يتوفر سعر صرف لعملة ${row.currency} من السيرفر"
                } else {
                    "⚠️ Missing exchange rate for ${row.currency}"
                }
            }
        }

        val requiredYer = (currentTotalWithDiscountUsd * usdRate).roundToLong().toDouble()
        val paidYer = getTotalPaidYER()
        val totalSettledYer = paidYer + tradeInTotalState
        val diffYer = totalSettledYer - requiredYer

        if (totalSettledYer <= 0.01) {
            return if (isArabic) "⚠️ يرجى إدخال مبلغ الدفع أو إضافة ذهب كسر"
            else "⚠️ Please enter a payment amount or add gold trade-in"
        }

        if (diffYer < -MATCH_TOLERANCE_YER) {
            val remFormatted = String.format(Locale.US, "%,.0f YER", abs(diffYer))
            return if (isArabic) "⚠️ المبلغ المدفوع غير مكتمل — المتبقي: $remFormatted"
            else "⚠️ Payment incomplete — Remaining: $remFormatted"
        }

        if (diffYer > MATCH_TOLERANCE_YER && customerPhoneState.trim().isEmpty()) {
            return if (isArabic) "⚠️ يرجى إدخال رقم هاتف العميل لتسجيل الرصيد الدائن"
            else "⚠️ Customer phone is required to record customer credit"
        }

        if (diffYer > 50_000.0 && customerNationalIdState.trim().isEmpty()) {
            return if (isArabic) "⚠️ الرصيد الدائن يتجاوز 50,000 YER — يرجى إدخال رقم الهوية الوطنية"
            else "⚠️ Credit exceeds 50,000 YER — National ID is required"
        }

        return null
    }

    private fun submitToServer() {
        val validationError = validateBeforeSubmit()
        if (validationError != null) {
            inlineErrorState = validationError
            return
        }
        dispatchCheckout(buildData())
    }

    private fun buildData(): POSCheckoutData {
        val activeUsdRate = getRateToYER("USD")
        val data = POSCheckoutData().apply {
            customerName = customerNameState.trim()
            customerPhone = customerPhoneState.trim()
            customerNationalId = customerNationalIdState.trim()
            originalTotal = totalAmount
            finalAmount = currentTotalWithDiscountUsd
            usdToYerRate = activeUsdRate
            tradeIns = tradeInLines
            tradeInTotal = tradeInTotalState
        }

        val paymentsArray = JSONArray()
        for (row in paymentRowsState) {
            val amt = parseDouble(row.amountText)
            if (amt <= 0.0) continue

            val curr = row.currency
            val rate = getRateToYER(curr)
            if (rate <= 0.0) continue

            val methodCode = row.method.code?.takeIf { it.isNotBlank() } ?: continue

            try {
                val pObj = JSONObject().apply {
                    put("currency", curr)
                    put("amount", amt)
                    put("exchange_rate", rate)
                    put("base_amount", amt * rate)
                    put("method", methodCode)
                    if (!row.method.accountCode.isNullOrBlank()) {
                        put("account_code", row.method.accountCode)
                    }
                }
                paymentsArray.put(pObj)
            } catch (_: Exception) {
            }
        }
        data.payments = paymentsArray

        if (paymentsArray.length() > 0) {
            try {
                data.paymentMethod = paymentsArray.getJSONObject(0).optString("method", "")
                data.accountCode = paymentsArray.getJSONObject(0).optString("account_code", "")
            } catch (_: Exception) {
            }
        }

        return data
    }

    private fun dispatchCheckout(data: POSCheckoutData) {
        isSubmittingState = true
        inlineErrorState = null
        listener?.onCheckout(data)
    }

    fun onCheckoutSuccess() {
        try {
            dismissAllowingStateLoss()
        } catch (_: Exception) {
        }
    }

    fun onCheckoutError(errorMessage: String?) {
        try {
            isSubmittingState = false
            val cleanMsg = if (!errorMessage.isNullOrBlank()) {
                errorMessage
            } else {
                if (isArabic) "خطأ غير معروف" else "Unknown error"
            }
            inlineErrorState = cleanMsg
            Log.w(TAG, "Backend rejected checkout: $cleanMsg")
        } catch (e: Exception) {
            Log.e(TAG, "onCheckoutError", e)
        }
    }

    @Composable
    private fun CheckoutDialogContent() {
        val colors = MiraTheme.colors
        val isAr = isArabic

        val usdRate = getRateToYER("USD")
        val requiredTotalYer = if (usdRate > 0.0) {
            (currentTotalWithDiscountUsd * usdRate).roundToLong().toDouble()
        } else {
            0.0
        }
        val paidYer = getTotalPaidYER()
        val totalWithGoldYer = paidYer + tradeInTotalState
        val diffYer = totalWithGoldYer - requiredTotalYer

        val showNationalIdField = diffYer > 50_000.0 ||
            (inlineErrorState?.contains("الهوية") == true) ||
            customerNationalIdState.isNotEmpty()

        var pickerForRowIndex by remember { mutableIntStateOf(-1) }

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
                                Icons.Rounded.Payments,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isAr) "إتمام الدفع" else "Checkout & Payment",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = formatItemCount(itemCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.goldPrimary
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (!isSubmittingState) dismiss() },
                        enabled = !isSubmittingState
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }

                HorizontalDivider(color = colors.goldPrimary.copy(alpha = 0.2f))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TotalAndReconciliationCard(
                        isArabic = isAr,
                        finalTotalUsdFormatted = formatFinalTotalUsdDisplay(),
                        requiredYer = requiredTotalYer,
                        hasValidRate = usdRate > 0.0,
                        diffYer = diffYer,
                        tradeInTotal = tradeInTotalState
                    )

                    AnimatedVisibility(visible = !inlineErrorState.isNullOrEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = inlineErrorState ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }
                    }

                    CustomerInfoSection(
                        isArabic = isAr,
                        name = customerNameState,
                        phone = customerPhoneState,
                        nationalId = customerNationalIdState,
                        showNationalId = showNationalIdField,
                        enabled = !isSubmittingState,
                        onNameChange = { customerNameState = it },
                        onPhoneChange = {
                            customerPhoneState = it
                            if (inlineErrorState != null) inlineErrorState = null
                        },
                        onNationalIdChange = {
                            customerNationalIdState = it
                            if (inlineErrorState != null) inlineErrorState = null
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAr) "تفاصيل الدفعات" else "Payment Lines",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { openTradeInDialog() },
                                enabled = !isSubmittingState,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.55f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                            ) {
                                Icon(Icons.Rounded.Diamond, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isAr) "دفع بالذهب" else "Pay with Gold",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val defaultM = paymentMethodsState.firstOrNull() ?: return@OutlinedButton
                                    paymentRowsState.add(
                                        PaymentRowUiModel(
                                            method = defaultM,
                                            currency = "YER",
                                            amountText = ""
                                        )
                                    )
                                    inlineErrorState = null
                                },
                                enabled = !isSubmittingState && paymentMethodsState.isNotEmpty(),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.55f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF10B981))
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isAr) "إضافة دفعة" else "Add Line",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    paymentRowsState.forEachIndexed { index, row ->
                        PaymentRowCard(
                            index = index,
                            row = row,
                            isArabic = isAr,
                            currencies = currenciesState,
                            rateToYer = getRateToYER(row.currency),
                            enabled = !isSubmittingState,
                            onOpenMethodPicker = { pickerForRowIndex = index },
                            onCurrencySelected = { newCurr ->
                                paymentRowsState[index] = row.copy(currency = newCurr)
                                inlineErrorState = null
                            },
                            onAmountChanged = { newAmt ->
                                paymentRowsState[index] = row.copy(amountText = newAmt)
                                inlineErrorState = null
                            },
                            onFillRemaining = {
                                val rowRate = getRateToYER(row.currency)
                                if (rowRate <= 0.0 || usdRate <= 0.0) {
                                    inlineErrorState = if (isAr) {
                                        "⚠️ سعر الصرف غير متوفر من السيرفر"
                                    } else {
                                        "⚠️ Exchange rate unavailable"
                                    }
                                    return@PaymentRowCard
                                }
                                val currentRowYer = parseDouble(row.amountText) * rowRate
                                val otherPaidYer = getTotalPaidYER() - currentRowYer
                                val remYer = requiredTotalYer - otherPaidYer - tradeInTotalState
                                if (remYer > 0.01) {
                                    val formatted = if (row.currency.equals("YER", ignoreCase = true)) {
                                        remYer.roundToLong().toString()
                                    } else {
                                        String.format(Locale.US, "%.2f", remYer / rowRate)
                                    }
                                    paymentRowsState[index] = row.copy(amountText = formatted)
                                    inlineErrorState = null
                                }
                            },
                            onRemoveRow = {
                                if (paymentRowsState.size > 1) {
                                    paymentRowsState.removeAt(index)
                                    inlineErrorState = null
                                } else {
                                    Toast.makeText(
                                        requireContext(),
                                        if (isAr) "يجب إبقاء سطر واحد" else "At least one payment row required",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }

                HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.14f))

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
                        onClick = { submitToServer() },
                        enabled = !isSubmittingState && !isLoadingConfigState && paymentMethodsState.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        if (isSubmittingState || isLoadingConfigState) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF0A0F1D)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSubmittingState) {
                                    if (isAr) "جاري التنفيذ..." else "Processing..."
                                } else {
                                    if (isAr) "مزامنة..." else "Syncing..."
                                },
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAr) "تأكيد الدفع" else "Confirm Payment",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }

        if (pickerForRowIndex in paymentRowsState.indices) {
            PaymentMethodPickerDialog(
                isArabic = isAr,
                methods = paymentMethodsState,
                onDismiss = { pickerForRowIndex = -1 },
                onSelect = { selectedMethod ->
                    val idx = pickerForRowIndex
                    if (idx in paymentRowsState.indices) {
                        paymentRowsState[idx] = paymentRowsState[idx].copy(method = selectedMethod)
                        inlineErrorState = null
                    }
                    pickerForRowIndex = -1
                }
            )
        }
    }

    @Composable
    private fun TotalAndReconciliationCard(
        isArabic: Boolean,
        finalTotalUsdFormatted: String,
        requiredYer: Double,
        hasValidRate: Boolean,
        diffYer: Double,
        tradeInTotal: Double
    ) {
        val colors = MiraTheme.colors

        val (statusColor, statusIcon, statusLabel, statusAmountText) = when {
            !hasValidRate -> Quadruple(
                Color(0xFFEF4444),
                Icons.Rounded.WarningAmber,
                if (isArabic) "بانتظار سعر الصرف" else "Rate Required",
                "--"
            )
            abs(diffYer) <= MATCH_TOLERANCE_YER -> Quadruple(
                Color(0xFF10B981),
                Icons.Rounded.CheckCircle,
                if (isArabic) "متطابق" else "Matched",
                "0 YER"
            )
            diffYer < 0 -> Quadruple(
                Color(0xFFEF4444),
                Icons.Rounded.WarningAmber,
                if (isArabic) "المتبقي" else "Remaining",
                String.format(Locale.US, "%,.0f YER", abs(diffYer))
            )
            else -> Quadruple(
                Color(0xFF3B82F6),
                Icons.Rounded.SwapHoriz,
                if (isArabic) "رصيد دائن للعميل" else "Customer Credit",
                String.format(Locale.US, "+%,.0f YER", diffYer)
            )
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
                            text = if (isArabic) "إجمالي الفاتورة" else "Invoice Total",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Text(
                            text = finalTotalUsdFormatted,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textSecondary
                        )
                    }
                    Text(
                        text = if (hasValidRate) String.format(Locale.US, "%,.0f YER", requiredYer) else "--",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.goldPrimary
                    )
                }

                if (tradeInTotal > 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.goldPrimary.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Diamond,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isArabic) "مخصوم ذهب كسر (Trade-in)" else "Gold Trade-in",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.goldPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "-%,.0f YER", tradeInTotal),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.goldPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                        Text(
                            text = statusAmountText,
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
    private fun CustomerInfoSection(
        isArabic: Boolean,
        name: String,
        phone: String,
        nationalId: String,
        showNationalId: Boolean,
        enabled: Boolean,
        onNameChange: (String) -> Unit,
        onPhoneChange: (String) -> Unit,
        onNationalIdChange: (String) -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    enabled = enabled,
                    singleLine = true,
                    label = {
                        Text(
                            text = if (isArabic) "اسم العميل (اختياري)" else "Customer Name",
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = onPhoneChange,
                    enabled = enabled,
                    singleLine = true,
                    label = {
                        Text(
                            text = if (isArabic) "رقم الهاتف" else "Phone",
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = if (showNationalId) ImeAction.Next else ImeAction.Done
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(visible = showNationalId) {
                OutlinedTextField(
                    value = nationalId,
                    onValueChange = onNationalIdChange,
                    enabled = enabled,
                    singleLine = true,
                    label = {
                        Text(
                            text = if (isArabic) "رقم الهوية الوطنية (مطلوب للرصيد > 50,000)" else "National ID (Required > 50k)",
                            fontSize = 12.sp
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Badge, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    private fun PaymentRowCard(
        index: Int,
        row: PaymentRowUiModel,
        isArabic: Boolean,
        currencies: List<String>,
        rateToYer: Double,
        enabled: Boolean,
        onOpenMethodPicker: () -> Unit,
        onCurrencySelected: (String) -> Unit,
        onAmountChanged: (String) -> Unit,
        onFillRemaining: () -> Unit,
        onRemoveRow: () -> Unit
    ) {
        val colors = MiraTheme.colors
        var currencyMenuExpanded by remember { mutableStateOf(false) }

        val methodDisplay = remember(row.method, isArabic) {
            val disp = row.method.getDisplayName(isArabic)
            val acc = row.method.getAccountDisplay(isArabic)
            if (acc.isNotEmpty()) "$disp ($acc)" else disp
        }

        val parsedAmount = parseDouble(row.amountText)
        val equivalentYer = parsedAmount * rateToYer

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.backgroundMid,
            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.16f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) { onOpenMethodPicker() },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.cardSurface,
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = methodDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onRemoveRow,
                        enabled = enabled,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = row.amountText,
                        onValueChange = onAmountChanged,
                        enabled = enabled,
                        singleLine = true,
                        placeholder = {
                            Text("0.00", style = MaterialTheme.typography.bodySmall)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Box {
                        Surface(
                            modifier = Modifier
                                .height(52.dp)
                                .clickable(enabled = enabled) { currencyMenuExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            color = colors.cardSurface,
                            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = row.currency,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.goldPrimary
                                )
                                Icon(
                                    Icons.Rounded.ArrowDropDown,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = currencyMenuExpanded,
                            onDismissRequest = { currencyMenuExpanded = false }
                        ) {
                            currencies.forEach { curr ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = curr,
                                            fontWeight = if (curr == row.currency) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        currencyMenuExpanded = false
                                        onCurrencySelected(curr)
                                    }
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = onFillRemaining,
                        enabled = enabled,
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.goldPrimary.copy(alpha = 0.18f),
                            contentColor = colors.goldPrimary
                        )
                    ) {
                        Text(
                            text = if (isArabic) "المتبقي" else "Rest",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (rateToYer > 0.0 && !row.currency.equals("YER", ignoreCase = true) && parsedAmount > 0.0) {
                    val rateLabel = if (isArabic) "سعر الصرف" else "Rate"
                    Text(
                        text = String.format(Locale.US, "≈ %,.2f YER ($rateLabel: %,.2f)", equivalentYer, rateToYer),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    @Composable
    private fun PaymentMethodPickerDialog(
        isArabic: Boolean,
        methods: List<PaymentMethodDto>,
        onDismiss: () -> Unit,
        onSelect: (PaymentMethodDto) -> Unit
    ) {
        val colors = MiraTheme.colors
        var searchQuery by remember { mutableStateOf("") }

        val filtered = remember(searchQuery, methods.toList()) {
            val q = searchQuery.trim().lowercase(Locale.US)
            if (q.isEmpty()) methods
            else methods.filter { m ->
                val name = m.getDisplayName(isArabic).lowercase(Locale.US)
                val acc = m.getAccountDisplay(isArabic).lowercase(Locale.US)
                name.contains(q) || acc.contains(q)
            }
        }

        ComposeDialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isArabic) "اختر طريقة الدفع" else "Select Payment Method",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textSecondary)
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = if (isArabic) "بحث بالاسم أو الحساب..." else "Search method or account...",
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(filtered, key = { "${it.id}_${it.code}_${it.accountCode}" }) { method ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(method) },
                                shape = RoundedCornerShape(12.dp),
                                color = colors.backgroundMid,
                                border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.14f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = method.getDisplayName(isArabic),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                        val acc = method.getAccountDisplay(isArabic)
                                        if (acc.isNotEmpty()) {
                                            Text(
                                                text = acc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.goldPrimary
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
