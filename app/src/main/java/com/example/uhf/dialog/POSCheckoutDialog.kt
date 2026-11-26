package com.example.uhf.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.uhf.api.MiraApiClient
import com.example.uhf.api.dto.PaymentMethodDto
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
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
        private const val MATCH_TOLERANCE_YER = 5.0

        @JvmStatic
        fun newInstance(
            totalAmount: Double,
            itemCount: Int,
            ratesJson: String?,
            scrapDetailsJson: String?
        ): POSCheckoutDialog {
            return POSCheckoutDialog().apply {
                arguments = Bundle().apply {
                    putDouble(ARG_TOTAL_AMOUNT, totalAmount)
                    putInt(ARG_ITEM_COUNT, itemCount)
                    putString(ARG_RATES_JSON, ratesJson ?: "{}")
                    putString(ARG_SCRAP_DETAILS_JSON, scrapDetailsJson ?: "{}")
                }
            }
        }

        @JvmStatic
        fun newInstance(totalAmount: Double, itemCount: Int, ratesJson: String?): POSCheckoutDialog {
            return newInstance(totalAmount, itemCount, ratesJson, "{}")
        }

        @JvmStatic
        fun newInstance(totalAmount: Double, itemCount: Int): POSCheckoutDialog {
            return newInstance(totalAmount, itemCount, "{}", "{}")
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
        @JvmField var paymentMethod: String = "CASH"
        @JvmField var accountCode: String = "1101"
        @JvmField var currency: String = "MIX"
        @JvmField var originalTotal: Double = 0.0
        @JvmField var discount: Double = 0.0
        @JvmField var discountAmount: Double = 0.0
        @JvmField var finalAmount: Double = 0.0
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
    private val serverRates = HashMap<String, Double>()

    private var tradeInLines = JSONArray()
    private var currentTotalWithDiscountUsd: Double = 0.0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val paymentMethodsState = mutableStateListOf<PaymentMethodDto>()
    private val currenciesState = mutableStateListOf("YER", "SAR", "USD")
    private val paymentRowsState = mutableStateListOf<PaymentRowUiModel>()

    private var customerNameState by mutableStateOf("")
    private var customerPhoneState by mutableStateOf("")
    private var customerNationalIdState by mutableStateOf("")

    private var tradeInTotalState by mutableDoubleStateOf(0.0)
    private var inlineErrorState by mutableStateOf<String?>(null)
    private var isSubmittingState by mutableStateOf(false)

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

        arguments?.let { args ->
            totalAmount = args.getDouble(ARG_TOTAL_AMOUNT, 0.0)
            itemCount = args.getInt(ARG_ITEM_COUNT, 0)
            currentTotalWithDiscountUsd = totalAmount
            scrapDetailsJson = args.getString(ARG_SCRAP_DETAILS_JSON, "{}") ?: "{}"

            try {
                val ratesJson = args.getString(ARG_RATES_JSON, "{}") ?: "{}"
                val ratesObj = JSONObject(ratesJson)
                val keys = ratesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = ratesObj.optDouble(k, 0.0)
                    if (v > 0) {
                        serverRates[k.uppercase(Locale.US)] = v
                    }
                }
                Log.i(TAG, "✅ Server rates loaded: $serverRates")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse server rates: ${e.message}")
            }
        }

        isArabic = try {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (tags.isNotEmpty()) tags.contains("ar")
            else Locale.getDefault().language == "ar"
        } catch (_: Exception) {
            Locale.getDefault().language == "ar"
        }

        context?.let {
            priceManager = MiraPriceManager.getInstance(it)
        }

        paymentMethodsState.clear()
        paymentMethodsState.addAll(getFallbackMethods())
        if (paymentRowsState.isEmpty()) {
            paymentRowsState.add(
                PaymentRowUiModel(
                    method = paymentMethodsState.first(),
                    currency = "YER",
                    amountText = ""
                )
            )
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
        loadPaymentMethods()
        loadCurrencies()
    }

    private fun loadPaymentMethods() {
        val appCtx = context?.applicationContext ?: return
        Thread {
            var loaded: MutableList<PaymentMethodDto>? = null
            try {
                val client = MiraApiClient.getInstance(appCtx)
                val response = client.paymentMethods

                if (response != null && response.isSuccess && response.data != null) {
                    val dataObj = response.data!!
                    val arr = dataObj.optJSONArray("data")
                        ?: dataObj.optJSONArray("methods")
                        ?: dataObj.optJSONArray("payment_methods")
                        ?: dataObj.optJSONArray("items")

                    if (arr != null && arr.length() > 0) {
                        val gson = Gson()
                        loaded = ArrayList()
                        for (i in 0 until arr.length()) {
                            try {
                                val m = gson.fromJson(
                                    arr.getJSONObject(i).toString(),
                                    PaymentMethodDto::class.java
                                )
                                if (m != null && !m.code.isNullOrEmpty()) {
                                    loaded.add(m)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "skip malformed method", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPaymentMethods error", e)
            }

            if (!loaded.isNullOrEmpty()) {
                loaded.removeAll { m ->
                    m.code != null && (
                        m.code.equals("TRADE_IN", ignoreCase = true) ||
                        m.code.equals("CUSTOMER_CREDIT", ignoreCase = true)
                    )
                }
            }

            val finalList = if (!loaded.isNullOrEmpty()) loaded else getFallbackMethods()

            mainHandler.post {
                if (!isAdded) return@post
                paymentMethodsState.clear()
                paymentMethodsState.addAll(finalList)

                val defaultMethod = finalList.firstOrNull() ?: return@post
                for (i in paymentRowsState.indices) {
                    val row = paymentRowsState[i]
                    val stillExists = finalList.any { it.code == row.method.code }
                    if (!stillExists) {
                        paymentRowsState[i] = row.copy(method = defaultMethod)
                    } else {
                        val matched = finalList.first { it.code == row.method.code }
                        paymentRowsState[i] = row.copy(method = matched)
                    }
                }
            }
        }.start()
    }

    private fun loadCurrencies() {
        val appCtx = context?.applicationContext ?: return
        Thread {
            val currencies = LinkedHashSet<String>()
            currencies.add("YER")

            try {
                val client = MiraApiClient.getInstance(appCtx)
                val response = client.exchangeRates
                if (response != null && response.isSuccess && response.data != null) {
                    val dataObj = response.data!!
                    val arr = dataObj.optJSONArray("data") ?: dataObj.optJSONArray("rates")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val r = arr.getJSONObject(i)
                            val from = r.optString("from_currency", "").uppercase(Locale.US)
                            if (from.isNotEmpty()) currencies.add(from)
                            val rateVal = r.optDouble("rate", r.optDouble("exchange_rate", 0.0))
                            if (from.isNotEmpty() && rateVal > 0 && !serverRates.containsKey(from)) {
                                serverRates[from] = rateVal
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadCurrencies error", e)
            }

            if (currencies.size < 2) {
                currencies.add("USD")
                currencies.add("SAR")
            }

            val finalList = ArrayList(currencies)
            mainHandler.post {
                if (!isAdded) return@post
                currenciesState.clear()
                currenciesState.addAll(finalList)
            }
        }.start()
    }

    private fun getFallbackMethods(): List<PaymentMethodDto> {
        val list = ArrayList<PaymentMethodDto>()
        val fallback = arrayOf(
            arrayOf("CASH", "نقداً", "Cash", "💵", "111001", "الصندوق الرئيسي", "Main Cash Box"),
            arrayOf("POS_MADA", "شبكة مدى", "POS Mada", "💳", "112521", "شبكة مدى", "Mada Network"),
            arrayOf("VISA_MASTER", "فيزا/ماستر", "Visa/Master", "💳", "112521", "فيزا/ماستر", "Visa/Mastercard"),
            arrayOf("BANK_TRANSFER", "تحويل بنكي", "Bank Transfer", "🏦", "1102", "البنك", "Bank"),
            arrayOf("E_WALLET", "محفظة إلكترونية", "E-Wallet", "📱", "1103", "محفظة", "Wallet")
        )
        for (f in fallback) {
            val m = PaymentMethodDto().apply {
                code = f[0]
                nameAr = f[1]
                nameEn = f[2]
                icon = f[3]
                accountCode = f[4]
                accountNameAr = f[5]
                accountName = f[6]
            }
            list.add(m)
        }
        return list
    }

    private fun getRateToYER(currency: String?): Double {
        if (currency == null) return 1.0
        val curr = currency.uppercase(Locale.US).trim()
        if (curr == "YER") return 1.0

        val srvRate = serverRates[curr]
        if (srvRate != null && srvRate > 0) return srvRate

        priceManager?.let { pm ->
            val currencyRate = pm.getExchangeRate(curr)
            if (currencyRate > 0) return currencyRate
        }

        return if (curr == "USD") 530.0 else 1.0
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
            total += amt * getRateToYER(row.currency)
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
        val maxValue = currentTotalWithDiscountUsd * getRateToYER("USD")
        val tradeDialog = TradeInDialog.newInstance(maxValue, tradeInLines, scrapDetailsJson)
        tradeDialog.setOnTradeInListener { tradeIns, totalValue ->
            this.tradeInLines = tradeIns
            this.tradeInTotalState = totalValue
            inlineErrorState = null
        }
        tradeDialog.show(parentFragmentManager, "TradeInDialog")
    }

    private fun validateBeforeSubmit(): String? {
        val requiredYer = currentTotalWithDiscountUsd * getRateToYER("USD")
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
        val data = POSCheckoutData().apply {
            customerName = customerNameState.trim()
            customerPhone = customerPhoneState.trim()
            customerNationalId = customerNationalIdState.trim()
            originalTotal = totalAmount
            finalAmount = totalAmount
            tradeIns = tradeInLines
            tradeInTotal = tradeInTotalState
        }

        val paymentsArray = JSONArray()
        for (row in paymentRowsState) {
            val amt = parseDouble(row.amountText)
            if (amt <= 0) continue

            val curr = row.currency
            val rate = getRateToYER(curr)
            val methodCode = row.method.code?.takeIf { it.isNotEmpty() } ?: "CASH"

            try {
                val pObj = JSONObject().apply {
                    put("currency", curr)
                    put("amount", amt)
                    put("exchange_rate", rate)
                    put("base_amount", amt * rate)
                    put("method", methodCode)
                    if (!row.method.accountCode.isNullOrEmpty()) {
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
                data.paymentMethod = paymentsArray.getJSONObject(0).optString("method", "CASH")
                val firstAcc = paymentsArray.getJSONObject(0).optString("account_code", "")
                if (firstAcc.isNotEmpty()) {
                    data.accountCode = firstAcc
                }
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

        val requiredTotalYer = currentTotalWithDiscountUsd * getRateToYER("USD")
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
                                    val defaultM = paymentMethodsState.firstOrNull()
                                        ?: getFallbackMethods().first()
                                    paymentRowsState.add(
                                        PaymentRowUiModel(
                                            method = defaultM,
                                            currency = "YER",
                                            amountText = ""
                                        )
                                    )
                                    inlineErrorState = null
                                },
                                enabled = !isSubmittingState,
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
                                val currentRowYer = parseDouble(row.amountText) * getRateToYER(row.currency)
                                val otherPaidYer = getTotalPaidYER() - currentRowYer
                                val remYer = requiredTotalYer - otherPaidYer - tradeInTotalState
                                if (remYer > 0.01) {
                                    val rate = getRateToYER(row.currency)
                                    val formatted = if (row.currency.equals("YER", ignoreCase = true)) {
                                        if (abs(remYer - remYer.roundToLong()) < 0.01) {
                                            remYer.roundToLong().toString()
                                        } else {
                                            String.format(Locale.US, "%.2f", remYer)
                                        }
                                    } else {
                                        String.format(Locale.US, "%.2f", remYer / rate)
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
                        enabled = !isSubmittingState,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
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
        diffYer: Double,
        tradeInTotal: Double
    ) {
        val colors = MiraTheme.colors

        val (statusColor, statusIcon, statusLabel, statusAmountText) = when {
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
                        text = String.format(Locale.US, "%,.0f YER", requiredYer),
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
            val icon = row.method.icon?.takeIf { it.isNotEmpty() } ?: "💳"
            if (!acc.isNullOrEmpty()) "$icon $disp ($acc)" else "$icon $disp"
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

                if (rateToYer != 1.0 && parsedAmount > 0) {
                    Text(
                        text = String.format(Locale.US, "≈ %,.2f YER (سعر الصرف: %,.1f)", equivalentYer, rateToYer),
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

        val filtered = remember(searchQuery, methods.size) {
            val q = searchQuery.trim().lowercase(Locale.US)
            if (q.isEmpty()) methods
            else methods.filter { m ->
                val name = m.getDisplayName(isArabic).lowercase(Locale.US)
                val acc = (m.getAccountDisplay(isArabic) ?: "").lowercase(Locale.US)
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
                        items(filtered, key = { it.code ?: UUID.randomUUID().toString() }) { method ->
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
                                    Text(
                                        text = method.icon?.takeIf { it.isNotEmpty() } ?: "💳",
                                        fontSize = 20.sp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = method.getDisplayName(isArabic),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                        val acc = method.getAccountDisplay(isArabic)
                                        if (!acc.isNullOrEmpty()) {
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
