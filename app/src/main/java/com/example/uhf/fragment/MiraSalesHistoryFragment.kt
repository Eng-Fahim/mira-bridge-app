package com.example.uhf.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MiraSalesHistoryFragment : Fragment() {

    companion object {
        private const val TAG = "MiraSalesHistory"
        private const val PAGE_SIZE = 50
        private const val VISIBLE_THRESHOLD = 10
        private const val OFFLINE_PREFS = "sales_history_cache"
    }

    data class MiraSaleItem(
        val saleId: String,
        val customerName: String,
        val paymentMethod: String,
        val date: String,
        val rawJson: String,
        val amount: Double,
        val amountUSD: Double,
        val currency: String,
        val itemCount: Int,
        val branchId: Int,
        val refundStatus: String,
        val refundReason: String,
        val refundedAt: String
    )

    private data class BranchOption(
        val id: Int,
        val name: String
    )

    private data class PaymentFilterOption(
        val code: String,
        val labelAr: String,
        val labelEn: String,
        val icon: ImageVector
    )

    private var isReceiverRegistered = false
    private var dataRefreshReceiver: BroadcastReceiver? = null

    private val saleList = ArrayList<MiraSaleItem>()
    private var currentPage = 1

    @Volatile
    private var isLoading = false
    private var isLastPage = false
    private var hasMoreData = true
    private var totalItems = 0

    private var offlineCache: SharedPreferences? = null
    private var apiClient: MiraApiClient? = null
    private var branchesManager: MiraBranchesManager? = null
    private var priceManager: MiraPriceManager? = null
    private var permissionManager: PermissionManager? = null
    private var sessionManager: SessionManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SalesHistory-Net").apply { isDaemon = true }
    }

    private var isArabic: Boolean = true

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive States
    // ═══════════════════════════════════════════════════════════════════

    private var filteredSalesState by mutableStateOf<List<MiraSaleItem>>(emptyList())
    private var searchQueryState by mutableStateOf("")
    private var currentFilterState by mutableStateOf("ALL")

    private var canViewSalesState by mutableStateOf(true)
    private var canViewAllBranchesState by mutableStateOf(false)
    private var selectedBranchIdState by mutableIntStateOf(0)
    private var selectedBranchNameState by mutableStateOf("")
    private var branchOptionsState by mutableStateOf<List<BranchOption>>(emptyList())

    private var isInitialLoadingState by mutableStateOf(false)
    private var isRefreshingState by mutableStateOf(false)
    private var isLoadingMoreState by mutableStateOf(false)
    private var isEndOfListState by mutableStateOf(false)
    private var emptyMessageState by mutableStateOf("")

    private var totalRevenueTextState by mutableStateOf("—")
    private var invoicesCountTextState by mutableStateOf("0")
    private var showingCountTextState by mutableStateOf("0")

    private fun checkIsArabic(): Boolean {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle & BroadcastReceiver
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(
            "CAPS_DEBUG",
            "fragments: " + PermissionManager.getInstance(requireContext()).canViewAllBranches()
        )
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    SalesHistoryScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isArabic = checkIsArabic()

        context?.let { ctx ->
            apiClient = MiraApiClient.getInstance(ctx)
            branchesManager = MiraBranchesManager.getInstance(ctx)
            priceManager = MiraPriceManager.getInstance(ctx)
            permissionManager = PermissionManager.getInstance(ctx)
            sessionManager = SessionManager(ctx)
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE)
        }

        emptyMessageState = if (isArabic) "📭 لا توجد مبيعات مسجلة" else "📭 No sales recorded"

        applyPermissions()
        setupBranchFilter()

        if (canViewSalesState) {
            loadSalesOffline()
            refreshSales(true)
        }
    }

    override fun onResume() {
        super.onResume()
        registerDataRefreshReceiver()
        if (isAdded && canViewSalesState && saleList.isEmpty()) {
            refreshSales(true)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterDataRefreshReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterDataRefreshReceiver()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!networkExecutor.isShutdown) {
            networkExecutor.shutdownNow()
        }
    }

    private fun registerDataRefreshReceiver() {
        try {
            val ctx = context ?: return
            if (isReceiverRegistered) return

            dataRefreshReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || !isAdded) return
                    val action = intent.action
                    if ("com.example.uhf.REFRESH_SALES" == action ||
                        "com.example.uhf.REFRESH_DASHBOARD" == action ||
                        "com.example.uhf.REFRESH_INVENTORY" == action
                    ) {
                        mainHandler.post {
                            if (isAdded && isResumed) {
                                refreshSales(true)
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction("com.example.uhf.REFRESH_SALES")
                addAction("com.example.uhf.REFRESH_DASHBOARD")
                addAction("com.example.uhf.REFRESH_INVENTORY")
            }

            ContextCompat.registerReceiver(
                ctx,
                dataRefreshReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerDataRefreshReceiver error: ${e.message}")
        }
    }

    private fun unregisterDataRefreshReceiver() {
        try {
            val ctx = context
            if (ctx != null && isReceiverRegistered && dataRefreshReceiver != null) {
                ctx.unregisterReceiver(dataRefreshReceiver)
                isReceiverRegistered = false
            }
        } catch (e: Exception) {
            Log.d(TAG, "unregisterDataRefreshReceiver: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Permissions & Branch Setup
    // ═══════════════════════════════════════════════════════════════════

    private fun hasSalesViewPermission(): Boolean {
        val pm = permissionManager ?: return false
        return pm.canViewSalesHistory() || pm.canViewPos() || pm.isManager()
    }

    private fun applyPermissions() {
        val canView = hasSalesViewPermission()
        canViewSalesState = canView
        if (!canView) {
            emptyMessageState = if (isArabic) "⛔ غير مصرح لك بالوصول لسجل المبيعات" else "⛔ Access Denied"
            isInitialLoadingState = false
            isRefreshingState = false
        }
    }

    private fun setupBranchFilter() {
        val bm = branchesManager
        val userBranchId = bm?.activeBranchId ?: 0
        val canViewAll = permissionManager?.canViewAllBranches() == true

        if (canViewAll) {
            canViewAllBranchesState = true
            selectedBranchIdState = 0
            selectedBranchNameState = if (isArabic) "جميع الفروع" else "All Branches"

            val options = ArrayList<BranchOption>()
            options.add(BranchOption(0, if (isArabic) "جميع الفروع" else "All Branches"))
            bm?.allBranches?.forEach { b ->
                options.add(BranchOption(b.id, b.name ?: "#${b.id}"))
            }
            branchOptionsState = options
        } else {
            canViewAllBranchesState = false
            selectedBranchIdState = userBranchId
            selectedBranchNameState = bm?.activeBranchDisplayName ?: ""
            branchOptionsState = emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Offline Cache & API Data Loading
    // ═══════════════════════════════════════════════════════════════════

    private fun loadSalesOffline() {
        try {
            val cache = offlineCache ?: return
            val cachedJson = cache.getString("sales_cache", "") ?: ""
            if (cachedJson.isEmpty()) return

            val sales = JSONArray(cachedJson)
            saleList.clear()
            saleList.addAll(parseSalesJson(sales))
            filterSales(searchQueryState)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading offline sales: ${e.message}")
        }
    }

    private fun saveSalesOffline(sales: JSONArray) {
        try {
            offlineCache?.edit()?.putString("sales_cache", sales.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    private fun refreshSales(showProgress: Boolean) {
        if (!isAdded || !hasSalesViewPermission()) return

        currentPage = 1
        isLastPage = false
        hasMoreData = true
        isLoading = false
        isEndOfListState = false

        if (showProgress) {
            if (saleList.isEmpty()) {
                isInitialLoadingState = true
                emptyMessageState = if (isArabic) "جاري تحميل المبيعات..." else "Loading sales..."
            } else {
                isRefreshingState = true
            }
        }

        loadSalesPage(1, true)
    }

    private fun loadMoreSales() {
        if (isLoading || !hasMoreData || isLastPage) {
            if (isLastPage && saleList.isNotEmpty()) {
                isEndOfListState = true
            }
            return
        }

        isLoading = true
        isLoadingMoreState = true
        isEndOfListState = false

        loadSalesPage(currentPage + 1, false)
    }

    private fun loadSalesPage(page: Int, isRefresh: Boolean) {
        if (isLoading && !isRefresh) return
        isLoading = true

        if (networkExecutor.isShutdown) return

        val querySnapshot = searchQueryState
        val filterSnapshot = currentFilterState
        val branchSnapshot = selectedBranchIdState

        networkExecutor.execute {
            try {
                if (apiClient == null && context != null) {
                    apiClient = MiraApiClient.getInstance(requireContext())
                }

                val client = apiClient
                if (client == null) {
                    mainHandler.post {
                        if (!isAdded) return@post
                        isInitialLoadingState = false
                        isRefreshingState = false
                        isLoadingMoreState = false
                        isLoading = false
                        if (saleList.isEmpty()) {
                            emptyMessageState =
                                if (isArabic) "⚠️ خطأ في تهيئة النظام" else "⚠️ System initialization error"
                        }
                    }
                    return@execute
                }

                val response = if (querySnapshot.isNotEmpty() || filterSnapshot != "ALL" || branchSnapshot > 0) {
                    client.getSalesHistoryAdvanced(
                        querySnapshot,
                        filterSnapshot,
                        null,
                        null,
                        page,
                        PAGE_SIZE,
                        branchSnapshot
                    )
                } else {
                    client.getSalesHistoryPaginated(page, PAGE_SIZE)
                }

                if (response != null && response.isSuccess && response.data != null) {
                    var sales: JSONArray? = null
                    var totalCount = 0
                    var hasMore = false

                    val rootData = response.data!!
                    if (rootData.has("data")) {
                        val dataObj = rootData.opt("data")
                        if (dataObj is JSONObject) {
                            sales = dataObj.optJSONArray("data")
                                ?: dataObj.optJSONArray("sales")
                                ?: dataObj.optJSONArray("items")

                            totalCount = dataObj.optInt("total", 0)
                            val lastPage = dataObj.optInt("last_page", 1)
                            val currentP = dataObj.optInt("current_page", page)
                            hasMore = currentP < lastPage
                        } else if (dataObj is JSONArray) {
                            sales = dataObj
                        }
                    } else if (rootData.has("sales")) {
                        sales = rootData.optJSONArray("sales")
                    }

                    if (totalCount == 0) totalCount = rootData.optInt("total", 0)
                    if (!hasMore) hasMore = rootData.optBoolean("has_more", false)

                    var items = if (sales != null) parseSalesJson(sales) else ArrayList()

                    if (branchSnapshot > 0) {
                        items = items.filter { it.branchId == branchSnapshot }
                    }

                    if (sales != null && sales.length() > 0) {
                        saveSalesOffline(sales)
                    }

                    val isLast = !hasMore || items.size < PAGE_SIZE

                    mainHandler.post {
                        if (!isAdded) return@post

                        isLoading = false
                        isInitialLoadingState = false
                        isRefreshingState = false
                        isLoadingMoreState = false

                        if (isRefresh) {
                            saleList.clear()
                            saleList.addAll(items)
                            totalItems = if (totalCount > 0) totalCount else items.size
                            currentPage = 1
                            hasMoreData = !isLast
                            isLastPage = isLast
                        } else {
                            saleList.addAll(items)
                            currentPage = page
                            hasMoreData = !isLast
                            isLastPage = isLast
                        }

                        filterSales(searchQueryState)

                        if (saleList.isEmpty()) {
                            emptyMessageState =
                                if (isArabic) "📭 لا توجد مبيعات مسجلة" else "📭 No sales recorded"
                        }

                        isEndOfListState = isLastPage && saleList.isNotEmpty()
                    }
                } else {
                    mainHandler.post {
                        if (!isAdded) return@post
                        isInitialLoadingState = false
                        isRefreshingState = false
                        isLoadingMoreState = false
                        isLoading = false
                        if (saleList.isEmpty()) {
                            emptyMessageState =
                                if (isArabic) "📭 لا توجد مبيعات مسجلة" else "📭 No sales recorded"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading sales", e)
                mainHandler.post {
                    if (!isAdded) return@post
                    isInitialLoadingState = false
                    isRefreshingState = false
                    isLoadingMoreState = false
                    isLoading = false
                    if (saleList.isEmpty()) {
                        emptyMessageState =
                            if (isArabic) "⚠️ خطأ في تحميل البيانات" else "⚠️ Error loading data"
                    }
                }
            }
        }
    }

    private fun parseSalesJson(sales: JSONArray): List<MiraSaleItem> {
        val items = ArrayList<MiraSaleItem>(sales.length())
        for (i in 0 until sales.length()) {
            val sale = sales.optJSONObject(i) ?: continue

            val saleId = sale.optString("sale_id", sale.optString("id", sale.optString("invoice_no", "—")))
            val customerName = sale.optString(
                "customer_name",
                sale.optString("client_name", if (isArabic) "عميل افتراضي" else "Customer")
            )
            val currency = sale.optString("currency", sale.optString("currency_code", "USD"))

            val amountUsdRaw = sale.optDouble("final_amount_usd", sale.optDouble("total_amount_usd", 0.0))
            val amountLocal = sale.optDouble("final_amount", sale.optDouble("total_amount", sale.optDouble("amount", 0.0)))

            val resolvedUsd = if (amountUsdRaw > 0) {
                amountUsdRaw
            } else {
                when (currency.uppercase(Locale.US)) {
                    "USD" -> amountLocal
                    "YER" -> {
                        var rate = priceManager?.getExchangeRate("YER") ?: 530.0
                        if (rate <= 0) rate = 530.0
                        amountLocal / rate
                    }
                    "SAR" -> {
                        var rate = priceManager?.getExchangeRate("SAR") ?: 3.75
                        if (rate <= 0) rate = 3.75
                        amountLocal / rate
                    }
                    else -> amountLocal
                }
            }

            items.add(
                MiraSaleItem(
                    saleId = saleId,
                    customerName = customerName,
                    paymentMethod = sale.optString("payment_method", sale.optString("pay_type", "CASH")),
                    date = sale.optString("created_at", sale.optString("date", "")),
                    rawJson = sale.toString(),
                    amount = resolvedUsd,
                    amountUSD = resolvedUsd,
                    currency = currency,
                    itemCount = sale.optInt("item_count", sale.optInt("total_items", sale.optInt("count", 0))),
                    branchId = sale.optInt("branch_id", 0),
                    refundStatus = sale.optString("refund_status", sale.optString("status", "completed")),
                    refundReason = sale.optString("refund_reason", ""),
                    refundedAt = sale.optString("refunded_at", "")
                )
            )
        }
        return items
    }

    private fun filterSales(query: String?) {
        val branchFilter = selectedBranchIdState
        val paymentFilter = currentFilterState
        val q = query?.trim()?.lowercase(Locale.ROOT) ?: ""

        val newFiltered = if (q.isEmpty() && paymentFilter == "ALL" && branchFilter == 0) {
            ArrayList(saleList)
        } else {
            saleList.filter { item ->
                val branchMatch = branchFilter <= 0 || item.branchId == branchFilter
                val payMatch = paymentFilter == "ALL" ||
                    item.paymentMethod.uppercase(Locale.ROOT) == paymentFilter
                val queryMatch = q.isEmpty() ||
                    item.customerName.lowercase(Locale.ROOT).contains(q) ||
                    item.saleId.lowercase(Locale.ROOT).contains(q)
                branchMatch && payMatch && queryMatch
            }
        }

        filteredSalesState = newFiltered
        updateSummaryCards(newFiltered)

        if (newFiltered.isEmpty()) {
            emptyMessageState = if (isArabic) "📭 لا توجد نتائج مطابقة" else "📭 No matching results"
        }
    }

    private fun updateSummaryCards(list: List<MiraSaleItem>) {
        var totalRevenueUSD = 0.0
        for (item in list) {
            totalRevenueUSD += item.amountUSD
        }

        totalRevenueTextState = formatPrice(totalRevenueUSD)
        val totalCount = if (totalItems > 0) totalItems else list.size
        invoicesCountTextState = String.format(Locale.US, "%,d", totalCount)
        showingCountTextState = String.format(Locale.US, "%,d", list.size)
    }

    private fun formatPrice(usdAmount: Double): String {
        return try {
            val formatted = priceManager?.formatPriceDefault(usdAmount)
            if (!formatted.isNullOrBlank()) formatted
            else String.format(Locale.US, "$%,.2f", usdAmount)
        } catch (_: Exception) {
            String.format(Locale.US, "$%,.2f", usdAmount)
        }
    }

    private fun formatDisplayDate(rawDate: String): String {
        if (rawDate.isBlank()) return "—"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val d = sdf.parse(rawDate)
            val display = SimpleDateFormat(
                "dd/MM/yyyy hh:mm a",
                if (isArabic) Locale("ar") else Locale.US
            )
            if (d != null) display.format(d) else rawDate
        } catch (_: Exception) {
            rawDate
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Open Receipt Navigation
    // ═══════════════════════════════════════════════════════════════════

    private fun openReceipt(sale: MiraSaleItem) {
        try {
            val act = activity
            if (act is UHFMainActivity) {
                act.openReceiptFragment(sale.rawJson)
            } else if (isAdded) {
                openReceiptWithAdd(sale.rawJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening receipt", e)
            context?.let {
                Toast.makeText(
                    it,
                    if (isArabic) "خطأ في فتح الإيصال" else "Error opening receipt",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openReceiptWithAdd(receiptJson: String) {
        try {
            val receipt = MiraReceiptFragment.newInstance(receiptJson)
            val fm = parentFragmentManager
            fm.beginTransaction()
                .hide(this)
                .add(android.R.id.content, receipt, "mira_receipt")
                .addToBackStack("mira_receipt")
                .commit()

            fm.addOnBackStackChangedListener(object : FragmentManager.OnBackStackChangedListener {
                override fun onBackStackChanged() {
                    if (fm.backStackEntryCount == 0 && isAdded) {
                        fm.beginTransaction()
                            .show(this@MiraSalesHistoryFragment)
                            .commit()
                        fm.removeOnBackStackChangedListener(this)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in openReceiptWithAdd", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun SalesHistoryScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic
        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()

        var branchMenuExpanded by remember { mutableStateOf(false) }

        val paymentFilters = remember {
            listOf(
                PaymentFilterOption("ALL", "الكل", "All", Icons.Rounded.FilterList),
                PaymentFilterOption("CASH", "نقداً", "Cash", Icons.Rounded.Payments),
                PaymentFilterOption("CARD", "بطاقة", "Card", Icons.Rounded.CreditCard),
                PaymentFilterOption("TRANSFER", "تحويل", "Transfer", Icons.Rounded.AccountBalance),
                PaymentFilterOption("CREDIT", "آجل", "Credit", Icons.Rounded.Schedule)
            )
        }

        // Debounced search filter (300ms)
        LaunchedEffect(searchQueryState) {
            delay(300)
            filterSales(searchQueryState)
        }

        // Infinite scroll trigger when reaching near bottom
        val shouldLoadMore by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalCount = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalCount > 0 && lastVisible >= (totalCount - VISIBLE_THRESHOLD)
            }
        }

        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore && canViewSalesState && !isLoading && hasMoreData && !isLastPage) {
                loadMoreSales()
            }
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
                    // 1. الشريط العلوي (Header + Branch Selector + Refresh)
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
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ReceiptLong,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isAr) "سجل المبيعات" else "Sales History",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary
                                )
                                if (selectedBranchNameState.isNotEmpty() && !canViewAllBranchesState) {
                                    Text(
                                        text = selectedBranchNameState,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.goldPrimary
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // فلتر الفروع (يظهر فقط لمن يملك صلاحية عرض جميع الفروع)
                            if (canViewAllBranchesState && canViewSalesState) {
                                Box {
                                    Surface(
                                        modifier = Modifier.clickable { branchMenuExpanded = true },
                                        shape = RoundedCornerShape(12.dp),
                                        color = colors.cardSurface,
                                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.4f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Store,
                                                contentDescription = null,
                                                tint = colors.goldPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = selectedBranchNameState,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 110.dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Icon(
                                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = colors.goldPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = branchMenuExpanded,
                                        onDismissRequest = { branchMenuExpanded = false }
                                    ) {
                                        branchOptionsState.forEach { branch ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = branch.name,
                                                        fontWeight = if (branch.id == selectedBranchIdState)
                                                            FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    branchMenuExpanded = false
                                                    selectedBranchIdState = branch.id
                                                    selectedBranchNameState = branch.name
                                                    refreshSales(true)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // زر التحديث
                            val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
                            val spinAngle by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(900, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "spin_angle"
                            )

                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable(enabled = canViewSalesState && !isRefreshingState) {
                                        refreshSales(true)
                                    },
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
                                            .rotate(if (isRefreshingState) spinAngle else 0f)
                                    )
                                }
                            }
                        }
                    }

                    // 2. بطاقة ملخص الإيرادات والفواتير (Executive KPI Card)
                    if (canViewSalesState) {
                        SalesSummaryHeroCard(
                            isArabic = isAr,
                            totalRevenue = totalRevenueTextState,
                            invoicesCount = invoicesCountTextState,
                            showingCount = showingCountTextState
                        )
                    }

                    // 3. شريط البحث + فلاتر طرق الدفع
                    if (canViewSalesState) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQueryState,
                                onValueChange = { searchQueryState = it },
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        text = if (isAr) "بحث برقم الفاتورة أو اسم العميل..." else "Search invoice # or customer...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQueryState.isNotEmpty()) {
                                        IconButton(onClick = {
                                            searchQueryState = ""
                                            refreshSales(true)
                                        }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = null,
                                                tint = colors.textSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    keyboardController?.hide()
                                    refreshSales(true)
                                }),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // رقائق فلترة طرق الدفع
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                paymentFilters.forEach { opt ->
                                    val selected = currentFilterState == opt.code
                                    Surface(
                                        modifier = Modifier.clickable {
                                            if (currentFilterState != opt.code) {
                                                currentFilterState = opt.code
                                                refreshSales(true)
                                            }
                                        },
                                        shape = RoundedCornerShape(50),
                                        color = if (selected) colors.goldPrimary.copy(alpha = 0.18f)
                                        else colors.cardSurface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) colors.goldPrimary
                                            else colors.textSecondary.copy(alpha = 0.2f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = opt.icon,
                                                contentDescription = null,
                                                tint = if (selected) colors.goldPrimary else colors.textSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = if (isAr) opt.labelAr else opt.labelEn,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) colors.goldPrimary else colors.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. قائمة الفواتير أو حالة التحميل / الفراغ
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            !canViewSalesState -> {
                                EmptyOrLoadingStage(
                                    isLoading = false,
                                    message = emptyMessageState,
                                    isAccessDenied = true,
                                    isArabic = isAr,
                                    onRetry = null
                                )
                            }

                            isInitialLoadingState && filteredSalesState.isEmpty() -> {
                                EmptyOrLoadingStage(
                                    isLoading = true,
                                    message = emptyMessageState,
                                    isAccessDenied = false,
                                    isArabic = isAr,
                                    onRetry = null
                                )
                            }

                            filteredSalesState.isEmpty() -> {
                                EmptyOrLoadingStage(
                                    isLoading = false,
                                    message = emptyMessageState,
                                    isAccessDenied = false,
                                    isArabic = isAr,
                                    onRetry = { refreshSales(true) }
                                )
                            }

                            else -> {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 6.dp,
                                        bottom = 90.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    itemsIndexed(
                                        items = filteredSalesState,
                                        key = { index, item -> "${item.saleId}_$index" }
                                    ) { _, sale ->
                                        SaleInvoiceCard(
                                            sale = sale,
                                            isArabic = isAr,
                                            formattedAmount = formatPrice(
                                                if (sale.amountUSD > 0) sale.amountUSD else sale.amount
                                            ),
                                            formattedDate = formatDisplayDate(sale.date),
                                            onClick = { openReceipt(sale) }
                                        )
                                    }

                                    // مؤشر تحميل المزيد
                                    if (isLoadingMoreState) {
                                        item(key = "loading_more_footer") {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 16.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(22.dp),
                                                    strokeWidth = 2.5.dp,
                                                    color = colors.goldPrimary
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = if (isAr) "جاري تحميل المزيد..." else "Loading more...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.textSecondary
                                                )
                                            }
                                        }
                                    } else if (isEndOfListState) {
                                        item(key = "end_of_list_footer") {
                                            Text(
                                                text = if (isAr) "— نهاية سجل المبيعات —" else "— End of sales history —",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textSecondary.copy(alpha = 0.7f),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SalesSummaryHeroCard(
        isArabic: Boolean,
        totalRevenue: String,
        invoicesCount: String,
        showingCount: String
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                colors.goldPrimary.copy(alpha = 0.14f),
                                Color(0xFF10B981).copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // إجمالي الإيرادات
                    Column(modifier = Modifier.weight(1.4f)) {
                        Text(
                            text = if (isArabic) "إجمالي المبيعات" else "Total Revenue",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = totalRevenue,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // فاصل عمودي
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(colors.textSecondary.copy(alpha = 0.2f))
                    )

                    // عدد الفواتير
                    Column(
                        modifier = Modifier.weight(0.8f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isArabic) "الفواتير" else "Invoices",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = invoicesCount,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary
                        )
                    }

                    // فاصل عمودي
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(colors.textSecondary.copy(alpha = 0.2f))
                    )

                    // المعروض حالياً
                    Column(
                        modifier = Modifier.weight(0.8f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isArabic) "المعروض" else "Showing",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = showingCount,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SaleInvoiceCard(
        sale: MiraSaleItem,
        isArabic: Boolean,
        formattedAmount: String,
        formattedDate: String,
        onClick: () -> Unit
    ) {
        val colors = MiraTheme.colors

        val (statusText, statusColor, statusIcon) = when (sale.refundStatus.lowercase(Locale.ROOT)) {
            "refunded", "fully_refunded" -> Triple(
                if (isArabic) "مرتجع" else "Refunded",
                Color(0xFFEF4444),
                Icons.Rounded.Autorenew
            )
            "partially_refunded" -> Triple(
                if (isArabic) "مرتجع جزئياً" else "Partial Refund",
                Color(0xFFF59E0B),
                Icons.Rounded.Autorenew
            )
            else -> Triple(
                if (isArabic) "مكتمل" else "Completed",
                Color(0xFF10B981),
                Icons.Rounded.CheckCircle
            )
        }

        val (payLabel, payColor) = when (sale.paymentMethod.uppercase(Locale.ROOT)) {
            "CARD" -> (if (isArabic) "بطاقة" else "Card") to Color(0xFF3B82F6)
            "TRANSFER" -> (if (isArabic) "تحويل" else "Transfer") to Color(0xFF06B6D4)
            "CREDIT" -> (if (isArabic) "آجل" else "Credit") to Color(0xFFF59E0B)
            else -> (if (isArabic) "نقداً" else "Cash") to Color(0xFF10B981)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.28f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // الصف العلوي: رقم الفاتورة + شارة الدفع + شارة الحالة
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.goldPrimary.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "#${sale.saleId}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = payColor.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = payLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = payColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // حالة الفاتورة
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                    }
                }

                // الصف الأوسط: اسم العميل والتاريخ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = sale.customerName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarToday,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }

                Divider(color = colors.textSecondary.copy(alpha = 0.12f))

                // الصف السفلي: عدد القطع + الإجمالي الذهبي + سهم فتح الإيصال
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Inventory2,
                            contentDescription = null,
                            tint = Color(0xFF06B6D4),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "${sale.itemCount} " + (if (isArabic) "قطع" else "pcs"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formattedAmount,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.goldPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (isArabic) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = colors.goldPrimary.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyOrLoadingStage(
        isLoading: Boolean,
        message: String,
        isAccessDenied: Boolean,
        isArabic: Boolean,
        onRetry: (() -> Unit)?
    ) {
        val colors = MiraTheme.colors

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = colors.goldPrimary,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            } else {
                val badgeColor = if (isAccessDenied) Color(0xFFEF4444) else colors.goldPrimary
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isAccessDenied) Icons.Rounded.Block else Icons.Rounded.ReceiptLong,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                if (onRetry != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.goldPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isArabic) "تحديث القائمة" else "Refresh",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
