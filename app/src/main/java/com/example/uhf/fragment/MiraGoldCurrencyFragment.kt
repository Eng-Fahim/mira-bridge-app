package com.example.uhf.fragment

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.PermissionManager
import com.example.uhf.model.ApiProvider
import com.example.uhf.utils.MiraExecutorService
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraGoldCurrencyFragment v3.0 — Kotlin Edition
 *  إدارة أسعار الذهب والعملات والهوامش والمصنعيات وفق أحدث المعايير الآمنة
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MiraGoldCurrencyFragment : Fragment() {

    companion object {
        private const val TAG = "MiraGoldCurrency"
        private const val PREFS_NAME = "MIRA_GOLD_SETTINGS"
        private const val OUNCE_TO_GRAM = 31.1035
    }

    private var permissionManager: PermissionManager? = null
    private var prefs: SharedPreferences? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null

    private val decimalFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    private val largeFormat = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))

    private var isArabic = true
    private var selectedCurrency = "USD"
    private var useMarketRate = false
    private var isUpdatingFields = false
    private var currentGoldPrice24kUsd = 0.0

    private var providersList = ArrayList<ApiProvider>()
    private var selectedProvider: ApiProvider? = null

    // UI Elements
    private var tvSelectedProvider: TextView? = null
    private var tvActiveProviderStatus: TextView? = null
    private var tvGoldPriceStatus: TextView? = null
    private var tvLastUpdate: TextView? = null
    private var tvOuncePrice: TextView? = null
    private var tvOuncePriceConverted: TextView? = null

    private var etGoldPrice24k: EditText? = null
    private var etGoldPrice22k: EditText? = null
    private var etGoldPrice21k: EditText? = null
    private var etGoldPrice18k: EditText? = null
    private var etGoldPrice14k: EditText? = null

    private var etMargin24k: EditText? = null
    private var etMargin22k: EditText? = null
    private var etMargin21k: EditText? = null
    private var etMargin18k: EditText? = null
    private var etMargin14k: EditText? = null
    private var etMargin12k: EditText? = null

    private var etMaking24k: EditText? = null
    private var etMaking22k: EditText? = null
    private var etMaking21k: EditText? = null
    private var etMaking18k: EditText? = null
    private var etMaking14k: EditText? = null
    private var etMaking12k: EditText? = null

    private var spGoldApiProvider: Spinner? = null
    private var spGoldCurrency: Spinner? = null
    private var etGoldApiKey: EditText? = null
    private var btnFetchGoldPrice: Button? = null
    private var btnSaveGoldSettings: Button? = null
    private var pbGoldLoading: ProgressBar? = null

    private var spDefaultCurrency: Spinner? = null
    private var spChartCurrency: Spinner? = null

    private var etExchangeYER: EditText? = null
    private var etExchangeEGP: EditText? = null
    private var etExchangeSAR: EditText? = null
    private var etExchangeAED: EditText? = null

    private var etExchangeMarketYER: EditText? = null
    private var etExchangeMarketEGP: EditText? = null
    private var etExchangeMarketSAR: EditText? = null
    private var etExchangeMarketAED: EditText? = null

    private var btnSaveCurrencySettings: Button? = null
    private var btnRefreshRates: Button? = null
    private var pbCurrencyLoading: ProgressBar? = null
    private var rgExchangeRateType: RadioGroup? = null
    private var rbOfficialRate: RadioButton? = null
    private var rbMarketRate: RadioButton? = null

    private var tvLiveGold24k: TextView? = null
    private var tvLiveGold22k: TextView? = null
    private var tvLiveGold21k: TextView? = null
    private var tvLiveGold18k: TextView? = null

    private var tvLiveUSD_YER: TextView? = null
    private var tvLiveUSD_EGP: TextView? = null
    private var tvLiveUSD_SAR: TextView? = null
    private var tvLiveUSD_AED: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_mira_gold_currency, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            val ctx = requireContext()
            prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            permissionManager = PermissionManager.getInstance(ctx)
            isArabic = Locale.getDefault().language == "ar"
            selectedCurrency = prefs?.getString("preferred_currency", "USD") ?: "USD"
            useMarketRate = prefs?.getBoolean("use_market_rate", false) == true

            executor = MiraExecutorService.getInstance().networkExecutor ?: Executors.newSingleThreadExecutor()

            initViews(view)
            setupSpinners()
            setupListeners()
            applyPermissions()
            loadActiveProviders()
            loadLocalSettings()
            loadServerSettings()
            updateAllPrices()

        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated error", e)
            Toast.makeText(context, "Error initializing screen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPermissions() {
        if (!isAdded) return
        val pm = permissionManager ?: return
        val canEdit = pm.canEdit("gold_rates") || pm.isAdmin || pm.isBranchManager

        btnFetchGoldPrice?.isEnabled = canEdit
        btnSaveGoldSettings?.isEnabled = canEdit
        btnSaveCurrencySettings?.isEnabled = canEdit
    }

    private fun normalizeArabicNumbers(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return input
            .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3')
            .replace('٤', '4').replace('٥', '5').replace('٦', '6').replace('٧', '7')
            .replace('٨', '8').replace('٩', '9').replace('٫', '.').replace('٬', ',')
    }

    private fun getDouble(et: EditText?): Double {
        return try {
            if (et == null) return 0.0
            val text = normalizeArabicNumbers(et.text.toString().trim())
            if (text.isEmpty()) 0.0 else text.toDouble()
        } catch (e: Exception) {
            0.0
        }
    }

    private fun getDoubleOrDefault(et: EditText?, defaultValue: Double): Double {
        if (et == null) return defaultValue
        val text = et.text.toString().trim()
        if (text.isEmpty() || text == ".") return defaultValue
        return try {
            val value = normalizeArabicNumbers(text).toDouble()
            maxOf(value, 0.0) // يسمح بالصفر ويمنع القيم السالبة
        } catch (e: Exception) {
            defaultValue
        }
    }

    private fun initViews(view: View) {
        tvGoldPriceStatus = view.findViewById(R.id.tvGoldPriceStatus)
        tvLastUpdate = view.findViewById(R.id.tvLastUpdate)
        tvOuncePrice = view.findViewById(R.id.tvOuncePrice)
        tvOuncePriceConverted = view.findViewById(R.id.tvOuncePriceConverted)
        tvSelectedProvider = view.findViewById(R.id.tvSelectedProvider)
        tvActiveProviderStatus = view.findViewById(R.id.tvActiveProviderStatus)

        etGoldPrice24k = view.findViewById(R.id.etGoldPrice24k)
        etGoldPrice22k = view.findViewById(R.id.etGoldPrice22k)
        etGoldPrice21k = view.findViewById(R.id.etGoldPrice21k)
        etGoldPrice18k = view.findViewById(R.id.etGoldPrice18k)
        etGoldPrice14k = view.findViewById(R.id.etGoldPrice14k)

        etMargin24k = view.findViewById(R.id.etMargin24k)
        etMargin22k = view.findViewById(R.id.etMargin22k)
        etMargin21k = view.findViewById(R.id.etMargin21k)
        etMargin18k = view.findViewById(R.id.etMargin18k)
        etMargin14k = view.findViewById(R.id.etMargin14k)
        etMargin12k = view.findViewById(R.id.etMargin12k)

        etMaking24k = view.findViewById(R.id.etMaking24k)
        etMaking22k = view.findViewById(R.id.etMaking22k)
        etMaking21k = view.findViewById(R.id.etMaking21k)
        etMaking18k = view.findViewById(R.id.etMaking18k)
        etMaking14k = view.findViewById(R.id.etMaking14k)
        etMaking12k = view.findViewById(R.id.etMaking12k)

        spGoldApiProvider = view.findViewById(R.id.spGoldApiProvider)
        spGoldCurrency = view.findViewById(R.id.spGoldCurrency)
        etGoldApiKey = view.findViewById(R.id.etGoldApiKey)
        btnFetchGoldPrice = view.findViewById(R.id.btnFetchGoldPrice)
        btnSaveGoldSettings = view.findViewById(R.id.btnSaveGoldSettings)
        pbGoldLoading = view.findViewById(R.id.pbGoldLoading)

        spDefaultCurrency = view.findViewById(R.id.spDefaultCurrency)
        spChartCurrency = view.findViewById(R.id.spChartCurrency)

        etExchangeYER = view.findViewById(R.id.etExchangeYER)
        etExchangeEGP = view.findViewById(R.id.etExchangeEGP)
        etExchangeSAR = view.findViewById(R.id.etExchangeSAR)
        etExchangeAED = view.findViewById(R.id.etExchangeAED)

        etExchangeMarketYER = view.findViewById(R.id.etExchangeMarketYER)
        etExchangeMarketEGP = view.findViewById(R.id.etExchangeMarketEGP)
        etExchangeMarketSAR = view.findViewById(R.id.etExchangeMarketSAR)
        etExchangeMarketAED = view.findViewById(R.id.etExchangeMarketAED)

        btnSaveCurrencySettings = view.findViewById(R.id.btnSaveCurrencySettings)
        btnRefreshRates = view.findViewById(R.id.btnRefreshRates)
        pbCurrencyLoading = view.findViewById(R.id.pbCurrencyLoading)

        rgExchangeRateType = view.findViewById(R.id.rgExchangeRateType)
        rbOfficialRate = view.findViewById(R.id.rbOfficialRate)
        rbMarketRate = view.findViewById(R.id.rbMarketRate)

        tvLiveGold24k = view.findViewById(R.id.tvLiveGold24k)
        tvLiveGold22k = view.findViewById(R.id.tvLiveGold22k)
        tvLiveGold21k = view.findViewById(R.id.tvLiveGold21k)
        tvLiveGold18k = view.findViewById(R.id.tvLiveGold18k)

        tvLiveUSD_YER = view.findViewById(R.id.tvLiveUSD_YER)
        tvLiveUSD_EGP = view.findViewById(R.id.tvLiveUSD_EGP)
        tvLiveUSD_SAR = view.findViewById(R.id.tvLiveUSD_SAR)
        tvLiveUSD_AED = view.findViewById(R.id.tvLiveUSD_AED)

        etGoldApiKey?.visibility = View.GONE
        etGoldApiKey?.isEnabled = false

        if (useMarketRate) {
            rgExchangeRateType?.check(R.id.rbMarketRate)
        } else {
            rgExchangeRateType?.check(R.id.rbOfficialRate)
        }
    }

    private fun setupSpinners() {
        if (!isAdded) return
        val currencies = arrayOf("USD", "YER", "EGP", "SAR", "AED")
        val currencyAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, currencies)
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spGoldCurrency?.adapter = currencyAdapter
        spDefaultCurrency?.adapter = currencyAdapter
        spChartCurrency?.adapter = currencyAdapter

        val pos = getCurrencyPosition(selectedCurrency)
        spGoldCurrency?.setSelection(pos)
        spDefaultCurrency?.setSelection(pos)
        spChartCurrency?.setSelection(pos)

        val emptyList = listOf(if (isArabic) "جاري التحميل..." else "Loading...")
        val providerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, emptyList)
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spGoldApiProvider?.adapter = providerAdapter
        spGoldApiProvider?.isEnabled = false
    }

    private fun setupListeners() {
        spGoldApiProvider?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position < providersList.size) {
                    selectedProvider = providersList[position]

                    tvSelectedProvider?.let { tv ->
                        tv.text = if (isArabic) {
                            "المزود المحدد: ${selectedProvider?.providerName}"
                        } else {
                            "Selected Provider: ${selectedProvider?.providerName}"
                        }
                        tv.visibility = View.VISIBLE
                    }

                    tvActiveProviderStatus?.let { tv ->
                        if (selectedProvider?.isActive == true) {
                            tv.text = if (isArabic) "✅ نشط" else "✅ Active"
                            tv.setTextColor(0xFF16A34A.toInt())
                        } else {
                            tv.text = if (isArabic) "⚠️ سيتم تفعيله عند الحفظ" else "⚠️ Will be activated on save"
                            tv.setTextColor(0xFFF59E0B.toInt())
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        btnFetchGoldPrice?.setOnClickListener {
            val pm = permissionManager
            if (pm != null && (pm.canEdit("gold_rates") || pm.isAdmin || pm.isBranchManager)) {
                fetchGoldPricesAutomatically()
            } else {
                Toast.makeText(context, "⛔ غير مصرح", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveGoldSettings?.setOnClickListener {
            val pm = permissionManager
            if (pm != null && (pm.canEdit("gold_rates") || pm.isAdmin || pm.isBranchManager)) {
                saveGoldSettings()
            } else {
                Toast.makeText(context, "⛔ غير مصرح", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveCurrencySettings?.setOnClickListener {
            val pm = permissionManager
            if (pm != null && (pm.canEdit("gold_rates") || pm.isAdmin || pm.isBranchManager)) {
                saveCurrencySettings()
            } else {
                Toast.makeText(context, "⛔ غير مصرح", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefreshRates?.setOnClickListener {
            updateAllPrices()
            Toast.makeText(context, "✅", Toast.LENGTH_SHORT).show()
        }

        spGoldCurrency?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedCurrency = parent.getItemAtPosition(position).toString()
                prefs?.edit()?.putString("preferred_currency", selectedCurrency)?.apply()
                updateAllPrices()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        rgExchangeRateType?.setOnCheckedChangeListener { _, checkedId ->
            useMarketRate = (checkedId == R.id.rbMarketRate)
            prefs?.edit()?.putBoolean("use_market_rate", useMarketRate)?.apply()
            updateAllPrices()
        }

        etGoldPrice24k?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFields) return
                val price24k = getDouble(etGoldPrice24k)
                if (price24k > 0) {
                    currentGoldPrice24kUsd = price24k
                    autoFillKaratPrices(price24k)
                }
            }
        })

        val rateWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count) {}
            override fun afterTextChanged(s: Editable?) { updateLiveCurrencyRates() }
        }
        etExchangeYER?.addTextChangedListener(rateWatcher)
        etExchangeEGP?.addTextChangedListener(rateWatcher)
        etExchangeSAR?.addTextChangedListener(rateWatcher)
        etExchangeAED?.addTextChangedListener(rateWatcher)
        etExchangeMarketYER?.addTextChangedListener(rateWatcher)
        etExchangeMarketEGP?.addTextChangedListener(rateWatcher)
        etExchangeMarketSAR?.addTextChangedListener(rateWatcher)
        etExchangeMarketAED?.addTextChangedListener(rateWatcher)
    }

    private fun loadActiveProviders() {
        if (!isAdded) return
        pbGoldLoading?.visibility = View.VISIBLE

        executor?.execute {
            try {
                val appCtx = requireContext().applicationContext
                val client = MiraApiClient.getInstance(appCtx)
                providersList = client.activeProviders as ArrayList<ApiProvider>

                mainHandler.post {
                    if (!isAdded || context == null) return@post
                    pbGoldLoading?.visibility = View.GONE

                    if (providersList.isEmpty()) {
                        addDefaultProviders()
                    }

                    val providerNames = providersList.map { provider ->
                        if (provider.isActive) "${provider.providerName} ✓" else provider.providerName
                    }

                    val providerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providerNames)
                    providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spGoldApiProvider?.adapter = providerAdapter
                    spGoldApiProvider?.isEnabled = true

                    val savedProviderId = prefs?.getInt("selected_provider_id", -1) ?: -1
                    var activeIndex = 0

                    for (i in providersList.indices) {
                        if (providersList[i].isActive) {
                            activeIndex = i
                            break
                        }
                    }

                    if (savedProviderId != -1) {
                        for (i in providersList.indices) {
                            if (providersList[i].id == savedProviderId) {
                                spGoldApiProvider?.setSelection(i)
                                return@post
                            }
                        }
                    }

                    spGoldApiProvider?.setSelection(activeIndex)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading providers: ${e.message}")
                mainHandler.post {
                    if (!isAdded || context == null) return@post
                    pbGoldLoading?.visibility = View.GONE
                    addDefaultProviders()

                    val providerNames = providersList.map { it.providerName }
                    val providerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providerNames)
                    providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spGoldApiProvider?.adapter = providerAdapter
                    spGoldApiProvider?.isEnabled = true
                    spGoldApiProvider?.setSelection(0)
                }
            }
        }
    }

    private fun addDefaultProviders() {
        val names = arrayOf("Metal Price", "Gold API", "Metals Dev")
        val types = arrayOf("metalprice", "goldapi", "metalsdev")

        for (i in names.indices) {
            val provider = ApiProvider().apply {
                id = i + 1
                providerName = names[i]
                providerType = types[i]
                apiKey = ""
                isActive = (i == 0)
            }
            providersList.add(provider)
        }
    }

    private fun fetchGoldPricesAutomatically() {
        val provider = selectedProvider
        if (!isAdded || provider == null) {
            Toast.makeText(context, if (isArabic) "يرجى اختيار مزود أولاً" else "Please select a provider first", Toast.LENGTH_SHORT).show()
            return
        }

        pbGoldLoading?.visibility = View.VISIBLE
        btnFetchGoldPrice?.isEnabled = false
        tvGoldPriceStatus?.text = if (isArabic) "⏳ جاري جلب الأسعار..." else "⏳ Fetching..."
        tvGoldPriceStatus?.setTextColor(0xFFF59E0B.toInt())

        executor?.execute {
            try {
                val appCtx = requireContext().applicationContext
                val client = MiraApiClient.getInstance(appCtx)
                val response = client.fetchGoldPriceWithProvider(provider.id)

                if (response.isSuccess && response.data != null) {
                    val data = response.data.optJSONObject("data") ?: response.data
                    val prices = data.optJSONObject("prices") ?: data

                    val price24k = prices.optDouble("24k", 0.0)
                    val isCached = data.optBoolean("is_cached", false)
                    val isTooOld = data.optBoolean("is_too_old", false)
                    val isStale = data.optBoolean("is_stale", false)
                    val warning = data.optString("warning", "")
                    val ageHours = data.optDouble("age_hours", 0.0)

                    mainHandler.post {
                        if (!isAdded || context == null) return@post

                        if (price24k > 0) {
                            currentGoldPrice24kUsd = price24k
                            etGoldPrice24k?.setText(decimalFormat.format(price24k))
                            autoFillKaratPrices(price24k)

                            if (isTooOld) {
                                tvGoldPriceStatus?.text = if (isArabic) {
                                    "⚠️ سعر قديم جداً (${String.format(Locale.US, "%.1f", ageHours)} ساعة) - حدّث قبل البيع"
                                } else {
                                    "⚠️ Price too old (${String.format(Locale.US, "%.1f", ageHours)}h) - update before selling"
                                }
                                tvGoldPriceStatus?.setTextColor(0xFFDC2626.toInt())
                                if (warning.isNotEmpty()) {
                                    Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
                                }
                            } else if (isCached || isStale) {
                                tvGoldPriceStatus?.text = if (isArabic) {
                                    "⚠️ سعر محفوظ (${String.format(Locale.US, "%.1f", ageHours)} ساعة) - قد لا يعكس السوق"
                                } else {
                                    "⚠️ Cached price (${String.format(Locale.US, "%.1f", ageHours)}h) - may not reflect market"
                                }
                                tvGoldPriceStatus?.setTextColor(0xFFF59E0B.toInt())
                            } else {
                                tvGoldPriceStatus?.text = if (isArabic) "✅ تم تحديث الأسعار" else "✅ Updated"
                                tvGoldPriceStatus?.setTextColor(0xFF16A34A.toInt())
                            }

                            tvLastUpdate?.text = (if (isArabic) "آخر تحديث: " else "Last: ") +
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

                            prefs?.edit()?.putFloat("last_gold_price_24k", price24k.toFloat())?.apply()
                            prefs?.edit()?.putLong("last_update_time", System.currentTimeMillis())?.apply()
                        } else {
                            tvGoldPriceStatus?.text = if (isArabic) "❌ فشل في جلب البيانات" else "❌ Failed to fetch data"
                            tvGoldPriceStatus?.setTextColor(0xFFDC2626.toInt())
                        }
                        pbGoldLoading?.visibility = View.GONE
                        btnFetchGoldPrice?.isEnabled = true
                    }
                } else {
                    val serverMessage = response?.data?.optString("message", "") ?: ""
                    val errorMsg = response?.error ?: if (serverMessage.isEmpty()) "Unknown error" else serverMessage

                    mainHandler.post {
                        if (!isAdded || context == null) return@post
                        pbGoldLoading?.visibility = View.GONE
                        btnFetchGoldPrice?.isEnabled = true
                        tvGoldPriceStatus?.text = if (isArabic) "❌ فشل في الاتصال" else "❌ Connection failed"
                        tvGoldPriceStatus?.setTextColor(0xFFDC2626.toInt())
                        Toast.makeText(
                            context,
                            if (isArabic) "فشل جلب الأسعار: $errorMsg" else "Fetch failed: $errorMsg",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching prices: ${e.message}")
                mainHandler.post {
                    if (!isAdded || context == null) return@post
                    pbGoldLoading?.visibility = View.GONE
                    btnFetchGoldPrice?.isEnabled = true
                    tvGoldPriceStatus?.text = if (isArabic) "❌ خطأ" else "❌ Error"
                    tvGoldPriceStatus?.setTextColor(0xFFDC2626.toInt())
                }
            }
        }
    }

    private fun autoFillKaratPrices(price24k: Double) {
        isUpdatingFields = true
        etGoldPrice22k?.setText(decimalFormat.format(price24k * 22 / 24))
        etGoldPrice21k?.setText(decimalFormat.format(price24k * 21 / 24))
        etGoldPrice18k?.setText(decimalFormat.format(price24k * 18 / 24))
        etGoldPrice14k?.setText(decimalFormat.format(price24k * 14 / 24))
        isUpdatingFields = false
        updateAllPrices()
    }

    private fun updateAllPrices() {
        if (!isAdded) return
        val price24k = getDouble(etGoldPrice24k)

        if (price24k <= 0) {
            tvGoldPriceStatus?.let { tv ->
                tv.text = if (isArabic) "⚠️ يرجى جلب سعر الذهب أولاً" else "⚠️ Please fetch gold price first"
                tv.setTextColor(0xFFDC2626.toInt())
            }
            return
        }

        currentGoldPrice24kUsd = price24k

        val ouncePriceUsd = price24k * OUNCE_TO_GRAM
        val exchangeRate = getExchangeRate(selectedCurrency)
        val ouncePriceConverted = ouncePriceUsd * exchangeRate

        val currencySymbol = getCurrencySymbol(selectedCurrency)
        tvOuncePrice?.text = String.format(Locale.US, "أوقية (31.10g): $%s", decimalFormat.format(ouncePriceUsd))
        tvOuncePriceConverted?.text = String.format(Locale.US, "= %s %s", largeFormat.format(ouncePriceConverted), currencySymbol)

        updateLiveGoldPrices()
        updateLiveCurrencyRates()
    }

    private fun getCurrencyPosition(currency: String): Int {
        return when (currency) {
            "YER" -> 1
            "EGP" -> 2
            "SAR" -> 3
            "AED" -> 4
            else -> 0
        }
    }

    private fun getCurrencySymbol(currency: String): String {
        return when (currency) {
            "YER" -> "ريال"
            "EGP" -> "ج.م"
            "SAR" -> "ر.س"
            "AED" -> "د.إ"
            else -> "$"
        }
    }

    private fun getExchangeRate(currency: String): Double {
        return when (currency) {
            "YER" -> if (useMarketRate) getDouble(etExchangeMarketYER) else getDouble(etExchangeYER)
            "EGP" -> if (useMarketRate) getDouble(etExchangeMarketEGP) else getDouble(etExchangeEGP)
            "SAR" -> if (useMarketRate) getDouble(etExchangeMarketSAR) else getDouble(etExchangeSAR)
            "AED" -> if (useMarketRate) getDouble(etExchangeMarketAED) else getDouble(etExchangeAED)
            else -> 1.0
        }
    }

    private fun convertToCurrency(usdAmount: Double, targetCurrency: String): Double {
        if (targetCurrency == "USD") return usdAmount
        val rate = getExchangeRate(targetCurrency)
        return if (rate > 0) usdAmount * rate else usdAmount
    }

    private fun formatCurrency(amount: Double, currency: String): String {
        val symbol = getCurrencySymbol(currency)
        return if (currency == "YER" || currency == "EGP") {
            "$symbol ${largeFormat.format(amount)}"
        } else {
            "$symbol ${decimalFormat.format(amount)}"
        }
    }

    private fun saveGoldSettings() {
        val provider = selectedProvider
        if (!isAdded || provider == null) {
            Toast.makeText(context, if (isArabic) "يرجى اختيار مزود أولاً" else "Please select a provider first", Toast.LENGTH_SHORT).show()
            return
        }

        pbGoldLoading?.visibility = View.VISIBLE
        btnSaveGoldSettings?.isEnabled = false

        executor?.execute {
            try {
                val goldData = JSONObject().apply {
                    put("gold_price_24k", getDouble(etGoldPrice24k))
                    put("gold_price_22k", getDouble(etGoldPrice22k))
                    put("gold_price_21k", getDouble(etGoldPrice21k))
                    put("gold_price_18k", getDouble(etGoldPrice18k))
                    put("gold_price_14k", getDouble(etGoldPrice14k))

                    put("margin_24k", getDoubleOrDefault(etMargin24k, 95.00))
                    put("margin_22k", getDoubleOrDefault(etMargin22k, 88.00))
                    put("margin_21k", getDoubleOrDefault(etMargin21k, 90.00))
                    put("margin_18k", getDoubleOrDefault(etMargin18k, 100.80))
                    put("margin_14k", getDoubleOrDefault(etMargin14k, 105.80))
                    put("margin_12k", getDoubleOrDefault(etMargin12k, 110.00))

                    put("making_24k", getDoubleOrDefault(etMaking24k, 5.00))
                    put("making_22k", getDoubleOrDefault(etMaking22k, 8.00))
                    put("making_21k", getDoubleOrDefault(etMaking21k, 10.00))
                    put("making_18k", getDoubleOrDefault(etMaking18k, 12.00))
                    put("making_14k", getDoubleOrDefault(etMaking14k, 15.00))
                    put("making_12k", getDoubleOrDefault(etMaking12k, 18.00))

                    put("provider_id", provider.id)
                    put("gold_default_currency", spGoldCurrency?.selectedItem.toString())
                }

                val appCtx = requireContext().applicationContext
                val client = MiraApiClient.getInstance(appCtx)
                val goldResponse = client.saveGoldSettings(goldData)

                val serverSaved = goldResponse?.isSuccess == true
                val providerActivated = serverSaved && goldResponse?.data?.optBoolean("provider_activated", false) == true

                if (serverSaved) {
                    prefs?.edit()?.putString("gold_settings", goldData.toString())?.apply()
                    prefs?.edit()?.putInt("selected_provider_id", provider.id)?.apply()
                    for (p in providersList) {
                        p.isActive = (p.id == provider.id)
                    }
                }

                val resultMessage = if (serverSaved) {
                    if (providerActivated) {
                        if (isArabic) "✅ تم حفظ الأسعار وتفعيل المزود" else "✅ Prices saved & provider activated"
                    } else {
                        if (isArabic) "✅ تم حفظ الأسعار (لكن فشل تفعيل المزود)" else "✅ Prices saved (but provider activation failed)"
                    }
                } else {
                    val error = goldResponse?.error ?: "null response"
                    (if (isArabic) "❌ فشل الحفظ: " else "❌ Save failed: ") + error
                }

                mainHandler.post {
                    if (!isAdded || context == null) return@post
                    pbGoldLoading?.visibility = View.GONE
                    btnSaveGoldSettings?.isEnabled = true
                    Toast.makeText(context, resultMessage, Toast.LENGTH_LONG).show()
                    if (serverSaved) updateProvidersUI()
                }

            } catch (e: Exception) {
                Log.e(TAG, "saveGoldSettings error", e)
                mainHandler.post {
                    if (!isAdded || context == null) return@post
                    pbGoldLoading?.visibility = View.GONE
                    btnSaveGoldSettings?.isEnabled = true
                    Toast.makeText(context, (if (isArabic) "❌ خطأ: " else "❌ Error: ") + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateProvidersUI() {
        val provider = selectedProvider
        if (!isAdded || providersList.isEmpty() || provider == null) return

        val providerNames = providersList.map { p ->
            if (p.isActive) "${p.providerName} ✓" else p.providerName
        }

        val providerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providerNames)
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spGoldApiProvider?.adapter = providerAdapter

        for (i in providersList.indices) {
            if (providersList[i].id == provider.id) {
                spGoldApiProvider?.setSelection(i)
                break
            }
        }

        tvActiveProviderStatus?.let { tv ->
            if (provider.isActive) {
                tv.text = if (isArabic) "✅ نشط" else "✅ Active"
                tv.setTextColor(0xFF16A34A.toInt())
            } else {
                tv.text = if (isArabic) "⚠️ غير نشط" else "⚠️ Inactive"
                tv.setTextColor(0xFFF59E0B.toInt())
            }
        }
    }

    private fun saveCurrencySettings() {
        if (!isAdded) return
        pbCurrencyLoading?.visibility = View.VISIBLE
        btnSaveCurrencySettings?.isEnabled = false

        executor?.execute {
            try {
                val currencyData = JSONObject().apply {
                    put("global_default_currency", spDefaultCurrency?.selectedItem.toString())
                    put("chart_currency", spChartCurrency?.selectedItem.toString())
                    put("use_market_rate", useMarketRate)
                    put("exchange_official_YER", getDouble(etExchangeYER))
                    put("exchange_official_EGP", getDouble(etExchangeEGP))
                    put("exchange_official_SAR", getDouble(etExchangeSAR))
                    put("exchange_official_AED", getDouble(etExchangeAED))
                    put("exchange_market_YER", getDouble(etExchangeMarketYER))
                    put("exchange_market_EGP", getDouble(etExchangeMarketEGP))
                    put("exchange_market_SAR", getDouble(etExchangeMarketSAR))
                    put("exchange_market_AED", getDouble(etExchangeMarketAED))
                }

                val appCtx = requireContext().applicationContext
                val client = MiraApiClient.getInstance(appCtx)
                val response = client.saveCurrencySettings(currencyData)

                val serverSaved = response?.isSuccess == true

                if (serverSaved) {
                    prefs?.edit()?.putString("currency_settings", currencyData.toString())?.apply()
                    prefs?.edit()?.putBoolean("use_market_rate", useMarketRate)?.apply()
                }

                mainHandler.post {
                    if (!isAdded || context == null) return@post
                    pbCurrencyLoading?.visibility = View.GONE
                    btnSaveCurrencySettings?.isEnabled = true
                    Toast.makeText(
                        context,
                        if (serverSaved) {
                            if (isArabic) "✅ تم الحفظ" else "✅ Saved"
                        } else {
                            if (isArabic) "⚠️ فشل الحفظ في السيرفر" else "⚠️ Failed to save on server"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    if (serverSaved) updateAllPrices()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving currency settings: ${e.message}")
                mainHandler.post {
                    if (!isAdded || context == null) return@post
                    pbCurrencyLoading?.visibility = View.GONE
                    btnSaveCurrencySettings?.isEnabled = true
                }
            }
        }
    }

    private fun updateLiveGoldPrices() {
        if (!isAdded) return
        val karatLabel = if (isArabic) "قيراط" else "K"
        val perGram = if (isArabic) "جرام" else "g"

        val price24kUsd = getDouble(etGoldPrice24k)
        val margin24k = getDouble(etMargin24k)
        val making24k = getDouble(etMaking24k)

        if (price24kUsd > 0) {
            val final24kUsd = price24kUsd + (price24kUsd * margin24k / 100) + making24k
            val final24kConverted = convertToCurrency(final24kUsd, selectedCurrency)
            tvLiveGold24k?.text = String.format(
                Locale.US, "24%s: %s/%s\n($%s/%s)", karatLabel,
                formatCurrency(final24kConverted, selectedCurrency), perGram, decimalFormat.format(final24kUsd), perGram
            )

            val price22kUsd = price24kUsd * 22 / 24
            val final22kUsd = price22kUsd + (price22kUsd * getDouble(etMargin22k) / 100) + getDouble(etMaking22k)
            tvLiveGold22k?.text = String.format(
                Locale.US, "22%s: %s/%s\n($%s/%s)", karatLabel,
                formatCurrency(convertToCurrency(final22kUsd, selectedCurrency), selectedCurrency), perGram, decimalFormat.format(final22kUsd), perGram
            )

            val price21kUsd = price24kUsd * 21 / 24
            val final21kUsd = price21kUsd + (price21kUsd * getDouble(etMargin21k) / 100) + getDouble(etMaking21k)
            tvLiveGold21k?.text = String.format(
                Locale.US, "21%s: %s/%s\n($%s/%s)", karatLabel,
                formatCurrency(convertToCurrency(final21kUsd, selectedCurrency), selectedCurrency), perGram, decimalFormat.format(final21kUsd), perGram
            )

            val price18kUsd = price24kUsd * 18 / 24
            val final18kUsd = price18kUsd + (price18kUsd * getDouble(etMargin18k) / 100) + getDouble(etMaking18k)
            tvLiveGold18k?.text = String.format(
                Locale.US, "18%s: %s/%s\n($%s/%s)", karatLabel,
                formatCurrency(convertToCurrency(final18kUsd, selectedCurrency), selectedCurrency), perGram, decimalFormat.format(final18kUsd), perGram
            )
        }
    }

    private fun updateLiveCurrencyRates() {
        if (!isAdded) return
        val rateType = if (useMarketRate) (if (isArabic) "سعر السوق" else "Market") else (if (isArabic) "السعر الرسمي" else "Official")

        tvLiveUSD_YER?.text = String.format(Locale.US, "1 USD = %s ريال (%s)", largeFormat.format(getExchangeRate("YER")), rateType)
        tvLiveUSD_EGP?.text = String.format(Locale.US, "1 USD = %s ج.م (%s)", decimalFormat.format(getExchangeRate("EGP")), rateType)
        tvLiveUSD_SAR?.text = String.format(Locale.US, "1 USD = %s ر.س (%s)", decimalFormat.format(getExchangeRate("SAR")), rateType)
        tvLiveUSD_AED?.text = String.format(Locale.US, "1 USD = %s د.إ (%s)", decimalFormat.format(getExchangeRate("AED")), rateType)
    }

    private fun applyGoldSettings(json: JSONObject?) {
        if (json == null || !isAdded) return

        try {
            Log.d(TAG, "Applying gold settings: $json")
            isUpdatingFields = true

            if (json.has("gold_price_24k")) {
                val valNum = json.optDouble("gold_price_24k", 0.0)
                if (valNum > 0) etGoldPrice24k?.setText(decimalFormat.format(valNum))
            }
            if (json.has("gold_price_22k")) {
                val valNum = json.optDouble("gold_price_22k", 0.0)
                if (valNum > 0) etGoldPrice22k?.setText(decimalFormat.format(valNum))
            }
            if (json.has("gold_price_21k")) {
                val valNum = json.optDouble("gold_price_21k", 0.0)
                if (valNum > 0) etGoldPrice21k?.setText(decimalFormat.format(valNum))
            }
            if (json.has("gold_price_18k")) {
                val valNum = json.optDouble("gold_price_18k", 0.0)
                if (valNum > 0) etGoldPrice18k?.setText(decimalFormat.format(valNum))
            }
            if (json.has("gold_price_14k")) {
                val valNum = json.optDouble("gold_price_14k", 0.0)
                if (valNum > 0) etGoldPrice14k?.setText(decimalFormat.format(valNum))
            }

            if (json.has("margin_24k")) etMargin24k?.setText(decimalFormat.format(json.optDouble("margin_24k", 0.0)))
            if (json.has("margin_22k")) etMargin22k?.setText(decimalFormat.format(json.optDouble("margin_22k", 0.0)))
            if (json.has("margin_21k")) etMargin21k?.setText(decimalFormat.format(json.optDouble("margin_21k", 0.0)))
            if (json.has("margin_18k")) etMargin18k?.setText(decimalFormat.format(json.optDouble("margin_18k", 0.0)))
            if (json.has("margin_14k")) etMargin14k?.setText(decimalFormat.format(json.optDouble("margin_14k", 0.0)))
            if (json.has("margin_12k")) etMargin12k?.setText(decimalFormat.format(json.optDouble("margin_12k", 0.0)))

            if (json.has("making_24k")) etMaking24k?.setText(decimalFormat.format(json.optDouble("making_24k", 0.0)))
            if (json.has("making_22k")) etMaking22k?.setText(decimalFormat.format(json.optDouble("making_22k", 0.0)))
            if (json.has("making_21k")) etMaking21k?.setText(decimalFormat.format(json.optDouble("making_21k", 0.0)))
            if (json.has("making_18k")) etMaking18k?.setText(decimalFormat.format(json.optDouble("making_18k", 0.0)))
            if (json.has("making_14k")) etMaking14k?.setText(decimalFormat.format(json.optDouble("making_14k", 0.0)))
            if (json.has("making_12k")) etMaking12k?.setText(decimalFormat.format(json.optDouble("making_12k", 0.0)))

            if (json.has("gold_default_currency")) {
                spGoldCurrency?.setSelection(getCurrencyPosition(json.optString("gold_default_currency", "USD")))
            }
            if (json.has("provider_id")) {
                val providerId = json.optInt("provider_id")
                prefs?.edit()?.putInt("selected_provider_id", providerId)?.apply()
            }

            isUpdatingFields = false
            updateAllPrices()
        } catch (e: Exception) {
            isUpdatingFields = false
            Log.e(TAG, "Error applying gold settings: ${e.message}", e)
        }
    }

    private fun loadLocalSettings() {
        if (!isAdded) return
        var hasLoadedFullSettings = false

        val goldSettingsJson = prefs?.getString("gold_settings", "") ?: ""
        if (goldSettingsJson.isNotEmpty()) {
            try {
                applyGoldSettings(JSONObject(goldSettingsJson))
                hasLoadedFullSettings = true
            } catch (_: Exception) {}
        }

        val currencySettingsJson = prefs?.getString("currency_settings", "") ?: ""
        if (currencySettingsJson.isNotEmpty()) {
            try { applyCurrencySettings(JSONObject(currencySettingsJson)) } catch (_: Exception) {}
        }

        if (!hasLoadedFullSettings) {
            val lastUpdateTime = prefs?.getLong("last_update_time", 0L) ?: 0L
            val lastGoldPrice = prefs?.getFloat("last_gold_price_24k", 0f) ?: 0f
            if (lastGoldPrice > 0f && (System.currentTimeMillis() - lastUpdateTime) < 3600000L) {
                etGoldPrice24k?.setText(decimalFormat.format(lastGoldPrice.toDouble()))
                autoFillKaratPrices(lastGoldPrice.toDouble())
            }
        }

        updateAllPrices()
    }

    private fun loadServerSettings() {
        if (!isAdded) return
        executor?.execute {
            try {
                val appCtx = requireContext().applicationContext
                val client = MiraApiClient.getInstance(appCtx)
                val response = client.goldCurrencySettings

                if (response != null && response.isSuccess && response.data != null) {
                    Log.d(TAG, "Server settings response: ${response.data}")

                    val data = response.data.optJSONObject("data") ?: response.data
                    val goldData = data.optJSONObject("gold") ?: data
                    val currencyData = data.optJSONObject("currency")

                    mainHandler.post {
                        if (!isAdded || context == null) return@post
                        applyGoldSettings(goldData)
                        applyCurrencySettings(currencyData)
                        updateAllPrices()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading server settings: ${e.message}")
            }
        }
    }

    private fun applyCurrencySettings(json: JSONObject?) {
        try {
            if (json == null || !isAdded) return
            if (json.has("global_default_currency")) {
                spDefaultCurrency?.setSelection(getCurrencyPosition(json.optString("global_default_currency", "USD")))
            }
            if (json.has("chart_currency")) {
                spChartCurrency?.setSelection(getCurrencyPosition(json.optString("chart_currency", "USD")))
            }
            if (json.has("use_market_rate")) {
                useMarketRate = json.optBoolean("use_market_rate", false)
                rgExchangeRateType?.check(if (useMarketRate) R.id.rbMarketRate else R.id.rbOfficialRate)
            }
            if (json.has("exchange_official_YER")) etExchangeYER?.setText(decimalFormat.format(json.optDouble("exchange_official_YER")))
            if (json.has("exchange_official_EGP")) etExchangeEGP?.setText(decimalFormat.format(json.optDouble("exchange_official_EGP")))
            if (json.has("exchange_official_SAR")) etExchangeSAR?.setText(decimalFormat.format(json.optDouble("exchange_official_SAR")))
            if (json.has("exchange_official_AED")) etExchangeAED?.setText(decimalFormat.format(json.optDouble("exchange_official_AED")))
            if (json.has("exchange_market_YER")) etExchangeMarketYER?.setText(decimalFormat.format(json.optDouble("exchange_market_YER")))
            if (json.has("exchange_market_EGP")) etExchangeMarketEGP?.setText(decimalFormat.format(json.optDouble("exchange_market_EGP")))
            if (json.has("exchange_market_SAR")) etExchangeMarketSAR?.setText(decimalFormat.format(json.optDouble("exchange_market_SAR")))
            if (json.has("exchange_market_AED")) etExchangeMarketAED?.setText(decimalFormat.format(json.optDouble("exchange_market_AED")))
        } catch (e: Exception) {
            Log.e(TAG, "Error applying currency settings: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
