package com.example.uhf.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class MiraScrapGoldBuyFragment : Fragment() {

    private data class PaymentMethodOption(
        val code: String,
        val nameAr: String,
        val nameEn: String,
        val icon: String
    ) {
        fun displayLabel(isArabic: Boolean): String {
            val label = if (isArabic) nameAr else nameEn
            return "$icon $label"
        }
    }

    private data class PaymentRowState(
        val id: Long = System.nanoTime(),
        val currency: String = "YER",
        val methodCode: String = "CASH",
        val amountText: String = ""
    )

    private val karats = listOf("24", "22", "21", "18", "14")
    private val currencies = listOf("YER", "SAR", "USD")

    private var priceManager: MiraPriceManager? = null
    private var isArabic: Boolean = true

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive States
    // ═══════════════════════════════════════════════════════════════════

    private var customerNameState by mutableStateOf("")
    private var customerPhoneState by mutableStateOf("")
    private var selectedKaratState by mutableStateOf("21")
    private var weightTextState by mutableStateOf("")
    private var marketPriceTextState by mutableStateOf("")
    private var deductionTextState by mutableStateOf("")

    private var availableMethodsState by mutableStateOf<List<PaymentMethodOption>>(emptyList())
    private val paymentRowsState = mutableStateListOf<PaymentRowState>()

    private var isSavingState by mutableStateOf(false)
    private var successDialogDataState by mutableStateOf<Pair<Int, String>?>(null)

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
                    ScrapGoldBuyScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isArabic = checkIsArabic()
        view.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        priceManager = MiraPriceManager.getInstance(requireContext())

        loadPaymentMethods()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Payment Methods & Rates
    // ═══════════════════════════════════════════════════════════════════

    private fun loadPaymentMethods() {
        Thread {
            val loaded = ArrayList<PaymentMethodOption>()
            try {
                val ctx = context ?: return@Thread
                val api = MiraApiClient.getInstance(ctx)
                val response = api.getPaymentMethods()

                if (response != null && response.isSuccess && response.data != null) {
                    val arr = response.data!!.optJSONArray("data")
                        ?: response.data!!.optJSONArray("methods")

                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val code = obj.optString("code", "CASH")
                            val nameAr = obj.optString("name_ar", code)
                            val nameEn = obj.optString("name_en", nameAr)
                            val icon = obj.optString("icon", "💳")
                            loaded.add(PaymentMethodOption(code, nameAr, nameEn, icon))
                        }
                    }
                }
            } catch (_: Exception) {
            }

            if (loaded.isEmpty()) {
                loaded.add(PaymentMethodOption("CASH", "نقداً", "Cash", "💵"))
                loaded.add(PaymentMethodOption("BANK_TRANSFER", "تحويل بنكي", "Bank Transfer", "🏦"))
            }

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                availableMethodsState = loaded
                if (paymentRowsState.isEmpty()) {
                    val defaultCode = loaded.firstOrNull()?.code ?: "CASH"
                    paymentRowsState.add(
                        PaymentRowState(currency = "YER", methodCode = defaultCode, amountText = "")
                    )
                }
            }
        }.start()
    }

    private fun getRateToYER(currency: String): Double {
        if ("YER".equals(currency, ignoreCase = true)) return 1.0

        priceManager?.let { pm ->
            val yerRate = pm.getExchangeRate(MiraPriceManager.CURRENCY_YER)
            val currencyRate = pm.getExchangeRate(currency)

            if (currencyRate > 10) return currencyRate

            if (yerRate > 0 && currencyRate > 0) {
                return yerRate / currencyRate
            }
        }

        if ("SAR".equals(currency, ignoreCase = true)) return 141.33
        if ("USD".equals(currency, ignoreCase = true)) return 530.0
        return 1.0
    }

    private fun addPaymentRow(defaultCurrency: String = "YER", amount: Double = 0.0) {
        val defaultCode = availableMethodsState.firstOrNull()?.code ?: "CASH"
        val amtStr = if (amount > 0) String.format(Locale.US, "%.2f", amount) else ""
        paymentRowsState.add(
            PaymentRowState(
                currency = defaultCurrency,
                methodCode = defaultCode,
                amountText = amtStr
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Save Purchase
    // ═══════════════════════════════════════════════════════════════════

    private fun savePurchase(totalGoldValue: Double, totalPaidBase: Double) {
        val weight = weightTextState.trim().toDoubleOrNull() ?: 0.0
        val marketPrice = marketPriceTextState.trim().toDoubleOrNull() ?: 0.0
        val deduction = deductionTextState.trim().toDoubleOrNull() ?: 0.0

        if (weight <= 0 || marketPrice <= 0) {
            toast(if (isArabic) "الرجاء إدخال الوزن والسعر" else "Please enter weight and price")
            return
        }

        val paymentsArray = JSONArray()
        for (row in paymentRowsState) {
            val amount = row.amountText.trim().toDoubleOrNull() ?: 0.0
            if (amount > 0) {
                try {
                    val rate = getRateToYER(row.currency)
                    val base = amount * rate
                    val pObj = JSONObject().apply {
                        put("currency", row.currency)
                        put("amount", amount)
                        put("exchange_rate", rate)
                        put("base_amount", base)
                        put("method", row.methodCode)
                    }
                    paymentsArray.put(pObj)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (abs(totalPaidBase - totalGoldValue) > 1.0) {
            toast(
                if (isArabic) "⚠️ إجمالي المدفوعات لا يطابق قيمة الذهب"
                else "⚠️ Total payments do not match gold value"
            )
            return
        }

        val netPricePerGram = (marketPrice - deduction).coerceAtLeast(0.0)
        val karatWithSuffix = "${selectedKaratState}K"
        val resolvedCustomerName = customerNameState.trim().ifEmpty {
            if (isArabic) "عميل نقدي" else "Cash Customer"
        }

        val payload = JSONObject()
        try {
            payload.put("customer_name", resolvedCustomerName)
            payload.put("customer_phone", customerPhoneState.trim())

            // إرسال جميع مفاتيح الوزن المطلوبة من السيرفر لتجنب خطأ Validation
            payload.put("weight_grams", weight)
            payload.put("net_weight_grams", weight)
            payload.put("gross_weight_grams", weight)
            payload.put("stone_weight_grams", 0.0)

            // العيار بصيغتي السيرفر
            payload.put("gold_karat", karatWithSuffix)
            payload.put("karat", karatWithSuffix)

            // سعر الجرام والخصم بصيغتي السيرفر
            payload.put("price_per_gram", netPricePerGram)
            payload.put("market_price_per_gram", marketPrice)
            payload.put("deduction_per_gram", deduction)

            payload.put("total_amount", totalGoldValue)
            payload.put("currency", "YER")
            payload.put("payments", paymentsArray)

            val branchesManager = MiraBranchesManager.getInstance(requireContext())
            payload.put("branch_id", branchesManager.activeBranchId)
            val sessionManager = SessionManager(requireContext())
            payload.put("user_id", sessionManager.userId)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        isSavingState = true

        Thread {
            val ctx = context ?: return@Thread
            val api = MiraApiClient.getInstance(ctx)
            val response = api.saveScrapPurchase(payload)

            val act = activity ?: return@Thread
            if (isDetached) return@Thread

            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                isSavingState = false

                if (response != null && response.isSuccess && response.data != null) {
                    val dataObj = response.data!!.optJSONObject("data") ?: response.data!!
                    val purchaseId = dataObj.optInt("purchase_id", dataObj.optInt("id", 0))
                    val purchaseNumber = dataObj.optString("purchase_number", "")
                    successDialogDataState = purchaseId to purchaseNumber
                } else {
                    val detailedError = extractValidationErrors(response?.data, response?.error)
                    toast(detailedError)
                }
            }
        }.start()
    }

    private fun extractValidationErrors(data: JSONObject?, fallbackError: String?): String {
        try {
            val errorsObj = data?.optJSONObject("errors")
            if (errorsObj != null) {
                val messages = ArrayList<String>()
                val keys = errorsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = errorsObj.optJSONArray(k)
                    if (arr != null && arr.length() > 0) {
                        messages.add(arr.optString(0))
                    } else {
                        val single = errorsObj.optString(k, "")
                        if (single.isNotEmpty()) messages.add(single)
                    }
                }
                if (messages.isNotEmpty()) {
                    return messages.joinToString("\n")
                }
            }
            val msg = data?.optString("message", "")
            if (!msg.isNullOrBlank()) return msg
        } catch (_: Exception) {
        }
        return fallbackError ?: if (isArabic) "فشل الاتصال بالسيرفر" else "Server connection failed"
    }

    private fun clearFields() {
        weightTextState = ""
        marketPriceTextState = ""
        deductionTextState = ""
        customerNameState = ""
        customerPhoneState = ""

        paymentRowsState.clear()
        addPaymentRow("YER", 0.0)
    }

    private fun toast(msg: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun ScrapGoldBuyScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic

        val weight = weightTextState.trim().toDoubleOrNull() ?: 0.0
        val marketPrice = marketPriceTextState.trim().toDoubleOrNull() ?: 0.0
        val deduction = deductionTextState.trim().toDoubleOrNull() ?: 0.0

        val grossBeforeDeduction = if (weight > 0 && marketPrice > 0) weight * marketPrice else 0.0
        val totalDeduction = if (weight > 0 && deduction > 0) weight * deduction else 0.0
        val totalGoldValue = if (weight > 0 && marketPrice > 0) {
            (grossBeforeDeduction - totalDeduction).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val totalPaidBase = paymentRowsState.sumOf { row ->
            val amt = row.amountText.trim().toDoubleOrNull() ?: 0.0
            amt * getRateToYER(row.currency)
        }

        val remaining = totalGoldValue - totalPaidBase

        val remainingColor = when {
            abs(remaining) < 1.0 -> Color(0xFF10B981)
            remaining > 0 -> Color(0xFFEF4444)
            else -> Color(0xFFF59E0B)
        }

        val remainingFormatted = when {
            remaining < -1.0 -> String.format(Locale.US, "+ %,.2f YER", abs(remaining))
            else -> String.format(Locale.US, "%,.2f YER", remaining)
        }

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
                ) {
                    // 1. الشريط العلوي مع زر حفظ سريع وزر تصفير
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
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
                                    text = if (isAr) "شراء ذهب كسر" else "Buy Scrap Gold",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isAr) "تقييم الوزن والعيار وتسوية الدفعات" else "Weight & karat valuation",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // زر حفظ علوي سريع يظهر عند اكتمال البيانات
                            Button(
                                onClick = { savePurchase(totalGoldValue, totalPaidBase) },
                                enabled = !isSavingState && totalGoldValue > 0 && abs(remaining) < 1.0,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.goldPrimary,
                                    contentColor = Color(0xFF0A0F1D)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isAr) "حفظ" else "Save",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable(enabled = !isSavingState) { clearFields() },
                                shape = RoundedCornerShape(12.dp),
                                color = colors.cardSurface,
                                border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Autorenew,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 2. بطاقة ملخص القيمة والمتبقي
                    ScrapValuationHeroCard(
                        isArabic = isAr,
                        totalGoldValue = totalGoldValue,
                        totalDeduction = totalDeduction,
                        remainingFormatted = remainingFormatted,
                        remainingColor = remainingColor
                    )

                    // 3. محتوى النموذج + زر الحفظ الرئيسي فوق شريط التنقل السفلي
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 110.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // أ. بيانات البائع / العميل
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.16f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isAr) "بيانات البائع" else "Seller Details",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }

                                OutlinedTextField(
                                    value = customerNameState,
                                    onValueChange = { customerNameState = it },
                                    enabled = !isSavingState,
                                    singleLine = true,
                                    label = { Text(if (isAr) "اسم العميل / البائع" else "Customer Name") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = customerPhoneState,
                                    onValueChange = { customerPhoneState = it },
                                    enabled = !isSavingState,
                                    singleLine = true,
                                    label = { Text(if (isAr) "رقم الهاتف" else "Phone Number") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Phone,
                                        imeAction = ImeAction.Next
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // ب. تفاصيل الذهب والتسعير
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Calculate,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isAr) "تفاصيل الوزن والعيار والتسعير" else "Weight, Karat & Pricing",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }

                                Text(
                                    text = if (isAr) "عيار الذهب" else "Gold Karat",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textSecondary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    karats.forEach { karat ->
                                        val selected = selectedKaratState == karat
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = !isSavingState) {
                                                    selectedKaratState = karat
                                                },
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (selected) colors.goldPrimary.copy(alpha = 0.2f)
                                            else colors.backgroundMid,
                                            border = BorderStroke(
                                                if (selected) 1.5.dp else 1.dp,
                                                if (selected) colors.goldPrimary
                                                else colors.textSecondary.copy(alpha = 0.2f)
                                            )
                                        ) {
                                            Text(
                                                text = "${karat}K",
                                                modifier = Modifier.padding(vertical = 10.dp),
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (selected) colors.goldPrimary else colors.textSecondary
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = weightTextState,
                                    onValueChange = { weightTextState = it },
                                    enabled = !isSavingState,
                                    singleLine = true,
                                    label = { Text(if (isAr) "الوزن (جرام)" else "Weight (grams)") },
                                    suffix = { Text("g", color = colors.goldPrimary, fontWeight = FontWeight.Bold) },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Decimal,
                                        imeAction = ImeAction.Next
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = marketPriceTextState,
                                        onValueChange = { marketPriceTextState = it },
                                        enabled = !isSavingState,
                                        singleLine = true,
                                        label = { Text(if (isAr) "سعر الجرام (YER)" else "Price/g (YER)") },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Decimal,
                                            imeAction = ImeAction.Next
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    )

                                    OutlinedTextField(
                                        value = deductionTextState,
                                        onValueChange = { deductionTextState = it },
                                        enabled = !isSavingState,
                                        singleLine = true,
                                        label = { Text(if (isAr) "الخصم / جرام" else "Deduction/g") },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Decimal,
                                            imeAction = ImeAction.Done
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // ج. قسم الدفعات متعددة العملات
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Payments,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isAr) "دفعات الصرف للعميل" else "Payouts to Customer",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = { addPaymentRow("YER", 0.0) },
                                        enabled = !isSavingState,
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF10B981).copy(alpha = 0.16f),
                                            contentColor = Color(0xFF10B981)
                                        )
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAr) "إضافة دفعة" else "Add Row",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                paymentRowsState.forEachIndexed { index, row ->
                                    ScrapPaymentRowCard(
                                        index = index,
                                        row = row,
                                        isArabic = isAr,
                                        enabled = !isSavingState,
                                        methods = availableMethodsState,
                                        remainingInYer = remaining,
                                        onUpdateRow = { updated ->
                                            paymentRowsState[index] = updated
                                        },
                                        onRemoveRow = {
                                            if (paymentRowsState.size > 1) {
                                                paymentRowsState.removeAt(index)
                                            } else {
                                                toast(
                                                    if (isAr) "يجب أن يبقى سطر دفع واحد على الأقل"
                                                    else "At least one payment row is required"
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // د. زر الحفظ الرئيسي مرفوع بوضوح فوق شريط التنقل السفلي
                        Button(
                            onClick = { savePurchase(totalGoldValue, totalPaidBase) },
                            enabled = !isSavingState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.goldPrimary,
                                contentColor = Color(0xFF0A0F1D)
                            )
                        ) {
                            if (isSavingState) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Color(0xFF0A0F1D)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isAr) "⏳ جاري الحفظ..." else "⏳ Saving...",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAr) "حفظ عملية شراء الكسر" else "Save Scrap Purchase",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // نافذة النجاح وطباعة الإيصال
        successDialogDataState?.let { (purchaseId, purchaseNumber) ->
            AlertDialog(
                onDismissRequest = {},
                containerColor = colors.cardSurface,
                title = {
                    Text(
                        text = if (isAr) "✅ تم الحفظ بنجاح" else "✅ Saved Successfully",
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Text(
                        text = if (isAr) {
                            "تم تسجيل عملية شراء الكسر بنجاح.\n\nرقم العملية: $purchaseNumber\n\nهل تريد طباعة الإيصال للعميل الآن؟"
                        } else {
                            "Scrap purchase recorded successfully.\n\nOperation #: $purchaseNumber\n\nWould you like to print the receipt now?"
                        },
                        color = colors.textPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            successDialogDataState = null
                            if (purchaseId > 0) {
                                val receipt = MiraScrapReceiptFragment.newInstance(purchaseId, null)
                                receipt.show(parentFragmentManager, "ScrapReceipt")
                            }
                            clearFields()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAr) "عرض وطباعة الإيصال" else "View & Print Receipt",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            successDialogDataState = null
                            clearFields()
                        }
                    ) {
                        Text(
                            text = if (isAr) "إغلاق" else "Close",
                            color = colors.textSecondary
                        )
                    }
                }
            )
        }
    }

    @Composable
    private fun ScrapValuationHeroCard(
        isArabic: Boolean,
        totalGoldValue: Double,
        totalDeduction: Double,
        remainingFormatted: String,
        remainingColor: Color
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.45f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                colors.goldPrimary.copy(alpha = 0.16f),
                                Color(0xFF10B981).copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1.1f)) {
                        Text(
                            text = if (isArabic) "صافي قيمة الذهب" else "Net Gold Value",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format(Locale.US, "%,.2f YER", totalGoldValue),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (totalDeduction > 0) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    if (isArabic) "خصم: -%,.2f YER" else "Deducted: -%,.2f YER",
                                    totalDeduction
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(colors.textSecondary.copy(alpha = 0.22f))
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = if (isArabic) "المتبقي للتسوية" else "Remaining Balance",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = remainingFormatted,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = remainingColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ScrapPaymentRowCard(
        index: Int,
        row: PaymentRowState,
        isArabic: Boolean,
        enabled: Boolean,
        methods: List<PaymentMethodOption>,
        remainingInYer: Double,
        onUpdateRow: (PaymentRowState) -> Unit,
        onRemoveRow: () -> Unit
    ) {
        val colors = MiraTheme.colors
        var methodMenuExpanded by remember { mutableStateOf(false) }

        val rate = getRateToYER(row.currency)
        val amountVal = row.amountText.trim().toDoubleOrNull() ?: 0.0
        val baseEquivalent = amountVal * rate

        val selectedMethodObj = methods.firstOrNull { it.code == row.methodCode }
            ?: methods.firstOrNull()
            ?: PaymentMethodOption("CASH", "نقداً", "Cash", "💵")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.backgroundMid,
            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.goldPrimary.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled && methods.isNotEmpty()) {
                                    methodMenuExpanded = true
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = colors.cardSurface,
                            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = selectedMethodObj.displayLabel(isArabic),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = methodMenuExpanded,
                            onDismissRequest = { methodMenuExpanded = false }
                        ) {
                            methods.forEach { method ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = method.displayLabel(isArabic),
                                            fontWeight = if (method.code == row.methodCode)
                                                FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        methodMenuExpanded = false
                                        onUpdateRow(row.copy(methodCode = method.code))
                                    }
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .size(34.dp)
                            .clickable(enabled = enabled) { onRemoveRow() },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.14f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    currencies.forEach { curr ->
                        val selected = row.currency == curr
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = enabled) {
                                    onUpdateRow(row.copy(currency = curr))
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) Color(0xFF06B6D4).copy(alpha = 0.18f)
                            else colors.cardSurface,
                            border = BorderStroke(
                                1.dp,
                                if (selected) Color(0xFF06B6D4)
                                else colors.textSecondary.copy(alpha = 0.2f)
                            )
                        ) {
                            Text(
                                text = curr,
                                modifier = Modifier.padding(vertical = 7.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color(0xFF06B6D4) else colors.textSecondary
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = row.amountText,
                        onValueChange = { onUpdateRow(row.copy(amountText = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(if (isArabic) "المبلغ (${row.currency})" else "Amount (${row.currency})") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    if (remainingInYer > 0.5 && rate > 0) {
                        val neededInRowCurrency = (amountVal * rate + remainingInYer) / rate
                        OutlinedButton(
                            onClick = {
                                onUpdateRow(
                                    row.copy(
                                        amountText = String.format(Locale.US, "%.2f", neededInRowCurrency)
                                    )
                                )
                            },
                            enabled = enabled,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                        ) {
                            Text(
                                text = if (isArabic) "المتبقي" else "Fill",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isArabic) "المعادل الأساسي:" else "Base Equivalent:",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    Text(
                        text = String.format(Locale.US, "%,.2f YER", baseEquivalent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }
    }
}
