package com.example.uhf.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ActivityDetailsBottomSheet
import com.example.uhf.manager.DashboardRoleController
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.model.ApiProvider
import com.example.uhf.navigation.NavigationManager
import com.example.uhf.tools.RecentActivityCache
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.dashboard.*
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.example.uhf.fragment.MiraInventoryDashboardFragment

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class MiraDashboardFragment : Fragment() {
    companion object {
        private const val TAG = "MiraDashboardFragment"
        private const val OFFLINE_PREFS_BASE = "dashboard_cache_u"
        private const val OUNCE_TO_GRAM = 31.1035
        private const val GOLD_CACHE_VALIDITY_MS = 12 * 60 * 60 * 1000L
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        private const val RESUME_REFETCH_THRESHOLD_MS = 2 * 60 * 1000L
    }

    private var apiClient: MiraApiClient? = null
    private var sessionManager: SessionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var orgProfile: MiraOrgProfileManager? = null
    private var priceManager: MiraPriceManager? = null
    private var permissionManager: PermissionManager? = null
    private var roleController: DashboardRoleController? = null
    private var activityCache: RecentActivityCache? = null

    private var profileListener: MiraOrgProfileManager.OnProfileChangedListener? = null
    private var branchListener: MiraBranchesManager.OnBranchChangedListener? = null

    private var localExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dataFuture: Future<*>? = null
    private var goldPriceFuture: Future<*>? = null

    private var offlineCache: SharedPreferences? = null
    private var goldCachePrefs: SharedPreferences? = null

    private var currentBranchId = -1
    private var isViewInitialized = false
    private var isReceiverRegistered = false
    private val isLoading = AtomicBoolean(false)
    private val isGoldRefreshing = AtomicBoolean(false)
    private var lastDataFetchTime = 0L
    private var lastGoldFetchTime = 0L

    // حالات الخصوصية
    private var isHeroHiddenState by mutableStateOf(true)
    private var isSalesHiddenState by mutableStateOf(true)
    private var isInventoryHiddenState by mutableStateOf(true)
    private var isScrapHiddenState by mutableStateOf(true)
    private var isPosDashboardHiddenState by mutableStateOf(true)

    // حالات الواجهة التفاعلية
    private var isArabicState by mutableStateOf(true)
    private var isOnlineState by mutableStateOf(true)
    private var isRefreshingState by mutableStateOf(false)
    private var orgTitleState by mutableStateOf("MIRA Jewelry")
    private var userGreetingNameState by mutableStateOf("")

    private var layoutModeState by mutableStateOf(DashboardLayoutMode.STANDARD)
    private var showHeroCardState by mutableStateOf(false)
    private var showGoldCardState by mutableStateOf(false)
    private var showSalesCardState by mutableStateOf(false)
    private var showInventoryCardState by mutableStateOf(false)
    private var showScrapCardState by mutableStateOf(false)
    private var showScrapAgingCardState by mutableStateOf(false)
    private var showChartsCardState by mutableStateOf(false)
    private var showRecentActivityState by mutableStateOf(false)
    private var canRefreshGoldManuallyState by mutableStateOf(false)

    private var currentInventoryValueState by mutableDoubleStateOf(0.0)
    private var currentTotalWeightState by mutableDoubleStateOf(0.0)
    private var currentItemCountState by mutableIntStateOf(0)
    private var inventoryByKaratState by mutableStateOf<List<KaratInventory>>(emptyList())

    private var salesTodayState by mutableDoubleStateOf(0.0)
    private var salesMonthState by mutableDoubleStateOf(0.0)
    private var salesYearState by mutableDoubleStateOf(0.0)
    private var posCashBalanceState by mutableDoubleStateOf(0.0)

    private var goldPrice24State by mutableDoubleStateOf(0.0)
    private var goldPrice21State by mutableDoubleStateOf(0.0)
    private var goldPrice18State by mutableDoubleStateOf(0.0)
    private var ouncePriceState by mutableDoubleStateOf(0.0)
    private var priceSourceTextState by mutableStateOf("📡 —")
    private var goldChangePercentState by mutableDoubleStateOf(0.0)
    private var hasGoldTrendState by mutableStateOf(false)
    private var isGoldLoadingState by mutableStateOf(false)

    private var scrapWeightState by mutableDoubleStateOf(0.0)
    private var scrapValueState by mutableDoubleStateOf(0.0)
    private var agingTotalItemsState by mutableIntStateOf(0)
    private var agingTotalWeightState by mutableDoubleStateOf(0.0)
    private var agingOldCountState by mutableIntStateOf(0)
    private var agingOldWeightState by mutableDoubleStateOf(0.0)

    private var gateScansTodayState by mutableIntStateOf(0)
    private var gateAllowedTodayState by mutableIntStateOf(0)
    private var gateDeniedTodayState by mutableIntStateOf(0)

    private var alertCountState by mutableIntStateOf(0)
    private var alertMessageState by mutableStateOf("")

    private var sales7DaysState by mutableStateOf<List<SalesPoint>>(emptyList())
    private var salesByKaratState by mutableStateOf<List<KaratSalesSlice>>(emptyList())
    private var quickActionsState by mutableStateOf<List<QuickActionItem>>(emptyList())
    private var recentActivitiesState by mutableStateOf<List<JSONObject>>(emptyList())

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (isAdded && isViewInitialized) {
                val now = System.currentTimeMillis()
                if (now - lastDataFetchTime >= REFRESH_INTERVAL_MS) {
                    fetchData()
                    if (currentBranchId > 0) {
                        fetchScrapSummary(currentBranchId)
                        fetchScrapAging(currentBranchId)
                    }
                }
                mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    private val dataRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !isAdded || !isViewInitialized) return
            val action = intent.action
            if ("com.example.uhf.REFRESH_DASHBOARD" == action ||
                "com.example.uhf.REFRESH_INVENTORY" == action ||
                "com.example.uhf.REFRESH_SALES" == action
            ) {
                mainHandler.post {
                    if (isAdded && isViewInitialized) {
                        lastDataFetchTime = 0L
                        isLoading.set(false)
                        if (isResumed) {
                            fetchData()
                            fetchRecentActivity()
                            if (currentBranchId > 0) {
                                fetchScrapSummary(currentBranchId)
                                fetchScrapAging(currentBranchId)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val layoutDirection = if (isArabicState) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    MiraJewelryTheme {
                        MiraDashboardScreen()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            initializeManagers()
            refreshRoleAndPermissionsState()
            setupProfileListener()
            setupBranchListener()

            currentBranchId = if (branchesManager != null && branchesManager!!.activeBranchId > 0) {
                branchesManager!!.activeBranchId
            } else {
                sessionManager?.branchId ?: 1
            }

            initBranchCache(currentBranchId)
            isViewInitialized = true
            loadOfflineData()
            startAutoRefresh()
            lastDataFetchTime = 0L

            fetchData()
            fetchDashboardCharts()
            fetchRecentActivity()
            if (currentBranchId > 0) {
                fetchScrapSummary(currentBranchId)
                fetchScrapAging(currentBranchId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onViewCreated error", t)
        }
    }

    private fun initializeManagers() {
        if (!isAdded) return
        val context = requireContext()
        ensureExecutor()

        apiClient = MiraApiClient.getInstance(context)
        sessionManager = SessionManager(context)
        branchesManager = MiraBranchesManager.getInstance(context)
        orgProfile = MiraOrgProfileManager.getInstance(context)
        priceManager = MiraPriceManager.getInstance(context)
        permissionManager = PermissionManager.getInstance(context)

        isArabicState = Locale.getDefault().language == "ar"
        roleController = DashboardRoleController(permissionManager, isArabicState)

        goldCachePrefs = context.getSharedPreferences("gold_cache_prefs", Context.MODE_PRIVATE)
        lastGoldFetchTime = goldCachePrefs?.getLong("last_gold_fetch_time", 0L) ?: 0L
        activityCache = RecentActivityCache(context)

        updateHeaderIdentity()
    }

    private fun updateHeaderIdentity() {
        val orgName = if (isArabicState) orgProfile?.orgNameAr else orgProfile?.orgNameEn
        orgTitleState = if (!orgName.isNullOrEmpty()) orgName else "MIRA Jewelry"
        val display = sessionManager?.displayName
        val user = sessionManager?.username
        userGreetingNameState = when {
            !display.isNullOrEmpty() -> display
            !user.isNullOrEmpty() -> user
            else -> ""
        }
    }

    private fun refreshRoleAndPermissionsState() {
        val pm = permissionManager ?: return
        val rc = DashboardRoleController(pm, isArabicState)
        roleController = rc

        layoutModeState = when {
            rc.isPosOnlyLayout() -> DashboardLayoutMode.POS_ONLY
            rc.isGateOnlyLayout() -> DashboardLayoutMode.GATE_ONLY
            else -> DashboardLayoutMode.STANDARD
        }

        showHeroCardState = pm.canViewDashboardHero()
        showGoldCardState = pm.canViewDashboardGoldPrices()
        showSalesCardState = pm.canViewDashboardSales()
        showInventoryCardState = pm.canViewDashboardInventoryByKarat()
        showScrapCardState = pm.canViewDashboardScrap()
        showScrapAgingCardState = pm.canViewDashboardScrapAging()
        showChartsCardState = pm.canViewDashboardCharts()
        showRecentActivityState = pm.canViewDashboardRecentActivity()
        canRefreshGoldManuallyState = pm.canRefreshGoldManually()

        buildQuickActionsList(pm)
    }

    private fun buildQuickActionsList(pm: PermissionManager) {
        val list = mutableListOf<QuickActionItem>()

        if (pm.canUsePos()) {
            list.add(QuickActionItem("بيع", "POS Sale", Icons.Rounded.PointOfSale, Color(0xFFD4AF37), NavigationManager.TAG_POS) { MiraPosFragment() })
        }
        if (pm.canBuyScrapGold()) {
            list.add(QuickActionItem("شراء كسر", "Buy Scrap", Icons.Rounded.Recycling, Color(0xFFF59E0B), NavigationManager.TAG_SCRAP_GOLD_BUY) { MiraScrapGoldBuyFragment() })
        }
        if (pm.canViewInventory()) {
            list.add(QuickActionItem("الجرد", "Inventory", Icons.Rounded.Inventory2, Color(0xFF38BDF8), "InventoryDashboard") { MiraInventoryDashboardFragment() })
        }
        
        if (pm.canUseIdentity()) {
            list.add(QuickActionItem("التكويد", "Encoding", Icons.Rounded.QrCode2, Color(0xFF10B981), "IdentityStudio") { MiraIdentityStudioFragment() })
        }
        if (pm.canViewTransfers()) {
            list.add(QuickActionItem("التحويلات", "Transfers", Icons.Rounded.SwapHoriz, Color(0xFFA855F7), "Transfers") { MiraTransferFragment() })
        }
        if (pm.canViewRadar()) {
            list.add(QuickActionItem("الرادار", "Radar", Icons.Rounded.Radar, Color(0xFF06B6D4), "Radar") { MiraUHFRadarLocationFragment() })
        }
        if (pm.canViewGates()) {
            list.add(QuickActionItem("البوابات", "Gates", Icons.Rounded.Shield, Color(0xFFEF4444), "SecureGate") { MiraSecureGateFragment() })
        }
        if (pm.canViewAccounting()) {
            list.add(QuickActionItem("المحاسبة", "Accounting", Icons.Rounded.AccountBalance, Color(0xFF22C55E), "Accounting") { MiraAccountingFragment() })
        }
        if (pm.canViewSalesHistory()) {
            list.add(QuickActionItem("سجل المبيعات", "Sales Log", Icons.Rounded.ReceiptLong, Color(0xFFEAB308), "SalesHistory") { MiraSalesHistoryFragment() })
        }
        if (pm.canViewReports()) {
            list.add(QuickActionItem("التقارير", "Reports", Icons.Rounded.BarChart, Color(0xFF6366F1), NavigationManager.TAG_REPORTS) { MiraReportsProFragment() })
        }
        if (pm.canViewGoldRates()) {
            list.add(QuickActionItem("أسعار الذهب", "Gold Rates", Icons.Rounded.MonetizationOn, Color(0xFFD4AF37), "GoldRates") { MiraGoldCurrencyFragment() })
        }
        if (pm.canViewAdminHub()) {
            list.add(QuickActionItem("الإدارة", "Admin Hub", Icons.Rounded.AdminPanelSettings, Color(0xFFF43F5E), "AdminHub") { MiraAdminHubFragment() })
        }
        list.add(QuickActionItem("الملف الشخصي", "Profile", Icons.Rounded.AccountCircle, Color(0xFF94A3B8), "Profile") { MiraProfileFragment() })

        quickActionsState = list
    }

    private fun isSessionValid(): Boolean = sessionManager != null && sessionManager!!.userId > 0

    private fun ensureExecutor(): Boolean {
        if (localExecutor == null || localExecutor!!.isShutdown) {
            if (!isAdded) return false
            localExecutor = Executors.newFixedThreadPool(4)
        }
        return true
    }

    private fun shutdownExecutor() {
        try {
            localExecutor?.shutdownNow()
        } catch (_: Exception) {
        }
        localExecutor = null
    }

    private fun cancelFuture(future: Future<*>?) {
        try {
            if (future != null && !future.isDone) future.cancel(true)
        } catch (_: Exception) {
        }
    }

    private fun fetchData() {
        try {
            if (roleController != null && !roleController!!.canFetchFullDashboard()) {
                isLoading.set(false)
                isRefreshingState = false
                return
            }
            if (apiClient == null || !isAdded || !isSessionValid() || !ensureExecutor()) {
                isRefreshingState = false
                return
            }
            if (!isLoading.compareAndSet(false, true)) return

            val isGlobalAdmin = sessionManager?.isAnyAdmin == true
            val finalBranchId = if (isGlobalAdmin) {
                if (currentBranchId > 0) currentBranchId else 0
            } else {
                val fromSession = sessionManager?.branchId ?: 0
                val fromManager = branchesManager?.activeBranchId ?: 0
                when {
                    fromSession > 0 -> fromSession
                    fromManager > 0 -> fromManager
                    else -> 1
                }
            }

            if (finalBranchId <= 0 && !isGlobalAdmin) {
                isLoading.set(false)
                isRefreshingState = false
                return
            }

            cancelFuture(dataFuture)
            dataFuture = localExecutor!!.submit {
                try {
                    val response = apiClient?.getDashboardFull(finalBranchId)
                    mainHandler.post {
                        isLoading.set(false)
                        isRefreshingState = false
                        lastDataFetchTime = System.currentTimeMillis()
                        if (!isAdded) return@post

                        if (response != null && response.isSuccess && response.data != null) {
                            val rootData = if (response.data.has("data")) response.data.optJSONObject("data") else response.data
                            if (rootData != null) {
                                saveOfflineData(rootData)
                                processData(rootData)
                                computeAndShowAlerts(rootData)
                            }
                            isOnlineState = true
                        } else {
                            isOnlineState = false
                            loadOfflineData()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchData.thread error", e)
                    mainHandler.post {
                        isLoading.set(false)
                        isRefreshingState = false
                        isOnlineState = false
                        loadOfflineData()
                    }
                }
            }
            fetchDashboardCharts()
        } catch (e: Exception) {
            Log.e(TAG, "fetchData error", e)
            isLoading.set(false)
            isRefreshingState = false
        }
    }

    private fun processData(data: JSONObject) {
        try {
            if (roleController?.isGateOnlyLayout() == true) {
                val gates = data.optJSONObject("gates")
                if (gates != null) {
                    gateScansTodayState = gates.optInt("scans_today", 0)
                    gateAllowedTodayState = gates.optInt("allowed_today", 0)
                    gateDeniedTodayState = gates.optInt("denied_today", 0)
                }
                return
            }

            val inv = data.optJSONObject("inventory") ?: data.optJSONObject("inventory_data")
            if (inv != null) {
                val itemCount = inv.optInt("total_items", 0)
                var totalWeight = inv.optDouble("total_weight", inv.optDouble("weight", 0.0))
                var totalValue = inv.optDouble("total_value", 0.0)

                val byKarat = inv.optJSONArray("by_karat") ?: inv.optJSONArray("karats")
                val karatItems = mutableListOf<KaratInventory>()
                var calcWeight = 0.0
                var calcValue = 0.0

                if (byKarat != null) {
                    for (i in 0 until byKarat.length()) {
                        val k = byKarat.optJSONObject(i) ?: continue
                        val karat = k.optInt("karat", 0)
                        val weight = k.optDouble("weight", k.optDouble("total_weight", 0.0))
                        val count = k.optInt("count", 0)
                        if (karat > 0) {
                            karatItems.add(KaratInventory(karat, count, weight))
                            calcWeight += weight
                            val price = priceManager?.getGoldPricePerGram(karat) ?: 0.0
                            calcValue += (weight * price)
                        }
                    }
                }

                if (totalWeight == 0.0 && calcWeight > 0.0) totalWeight = calcWeight
                if (totalValue == 0.0 && calcValue > 0.0) totalValue = calcValue

                currentItemCountState = itemCount
                currentTotalWeightState = totalWeight
                currentInventoryValueState = totalValue
                inventoryByKaratState = karatItems.sortedByDescending { it.karat }
            }

            val sales = data.optJSONObject("sales")
            if (sales != null) {
                salesTodayState = sales.optDouble("today", 0.0)
                salesMonthState = sales.optDouble("month", 0.0)
                salesYearState = sales.optDouble("year", 0.0)
            }

            val shiftObj = data.optJSONObject("shift_summary")
            posCashBalanceState = shiftObj?.optDouble("total_cash", 0.0) ?: salesTodayState

            val sales7Days = data.optJSONArray("my_sales_last_7_days")
                ?: data.optJSONArray("sales_last_7_days")
            if (sales7Days != null && sales7Days.length() > 0) {
                sales7DaysState = parseSalesPoints(sales7Days)
            }
        } catch (e: Exception) {
            Log.e(TAG, "processData error", e)
        }
    }

    private fun parseSalesPoints(array: JSONArray): List<SalesPoint> {
        val points = mutableListOf<SalesPoint>()
        for (i in 0 until array.length()) {
            val day = array.optJSONObject(i) ?: continue
            val amount = day.optDouble("amount", 0.0)
            var dateStr = day.optString("date", "")
            if (dateStr.length > 5) dateStr = dateStr.substring(5)
            points.add(SalesPoint(dateStr, amount))
        }
        return points
    }

    private fun fetchDashboardCharts() {
        if (roleController != null && !roleController!!.canFetchCharts()) return
        if (apiClient == null || !isAdded || !isSessionValid() || !ensureExecutor()) return

        localExecutor!!.execute {
            try {
                val response = apiClient?.getDashboardCharts(currentBranchId)
                if (response != null && response.isSuccess && response.data != null) {
                    val rootData = if (response.data.has("data")) response.data.optJSONObject("data") else response.data
                    if (rootData != null) {
                        val salesArr = rootData.optJSONArray("my_sales_last_7_days")
                            ?: rootData.optJSONArray("sales_last_7_days")
                            ?: rootData.optJSONArray("sales")
                        val parsedPoints = if (salesArr != null) parseSalesPoints(salesArr) else emptyList()

                        val karatArr = rootData.optJSONArray("sales_by_karat")
                        val parsedSlices = mutableListOf<KaratSalesSlice>()
                        if (karatArr != null) {
                            for (i in 0 until karatArr.length()) {
                                val k = karatArr.optJSONObject(i) ?: continue
                                val amt = k.optDouble("amount", 0.0)
                                val label = k.optString("karat", "")
                                if (amt > 0) parsedSlices.add(KaratSalesSlice(label, amt))
                            }
                        }

                        mainHandler.post {
                            if (!isAdded) return@post
                            if (parsedPoints.isNotEmpty()) sales7DaysState = parsedPoints
                            if (parsedSlices.isNotEmpty()) salesByKaratState = parsedSlices
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchDashboardCharts error", e)
            }
        }
    }

    private fun fetchScrapSummary(branchId: Int) {
        if (roleController != null && !roleController!!.canFetchScrap()) return
        if (apiClient == null || !ensureExecutor()) return

        localExecutor!!.execute {
            try {
                val response = apiClient?.getScrapSummary(branchId)
                if (response != null && response.isSuccess && response.data != null) {
                    val data = response.data.optJSONObject("data") ?: response.data
                    val weight = data.optDouble("total_weight", 0.0)
                    val value = data.optDouble("total_value", 0.0)
                    mainHandler.post {
                        if (isAdded) {
                            scrapWeightState = weight
                            scrapValueState = value
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchScrapSummary error", e)
            }
        }
    }

    private fun fetchScrapAging(branchId: Int) {
        if (roleController != null && !roleController!!.canFetchScrapAging()) return
        if (apiClient == null || !ensureExecutor()) return

        localExecutor!!.execute {
            try {
                val response = apiClient?.getScrapAgingSummary(branchId)
                if (response != null && response.isSuccess && response.data != null) {
                    val data = response.data.optJSONObject("data") ?: return@execute
                    val totalItems = data.optInt("total_items", 0)
                    val totalWeight = data.optDouble("total_weight", 0.0)
                    val oldCount = data.optInt("old_count", 0)
                    val oldWeight = data.optDouble("old_weight", 0.0)
                    mainHandler.post {
                        if (isAdded) {
                            agingTotalItemsState = totalItems
                            agingTotalWeightState = totalWeight
                            agingOldCountState = oldCount
                            agingOldWeightState = oldWeight
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchScrapAging error", e)
            }
        }
    }

    private fun computeAndShowAlerts(data: JSONObject?) {
        if (roleController?.isGateOnlyLayout() == true) return
        if (permissionManager != null && !permissionManager!!.canViewDashboardAlerts()) {
            alertCountState = 0
            return
        }
        if (data == null) {
            alertCountState = 0
            return
        }
        var count = 0
        val sb = StringBuilder()
        val inv = data.optJSONObject("inventory")
        if (inv != null) {
            val noWeight = inv.optInt("items_without_weight", -1)
            val noPrice = inv.optInt("items_without_price", -1)
            if (noWeight > 0) {
                count += noWeight
                sb.append(if (isArabicState) "$noWeight قطعة بدون وزن" else "$noWeight items without weight")
            }
            if (noPrice > 0) {
                if (sb.isNotEmpty()) sb.append(" • ")
                count += noPrice
                sb.append(if (isArabicState) "$noPrice قطعة بدون سعر" else "$noPrice items without price")
            }
        }
        alertCountState = count
        alertMessageState = sb.toString()
    }

    private fun fetchLiveGoldPrices(forceRefresh: Boolean) {
        try {
            if (!canRefreshGoldManuallyState ||
                permissionManager == null ||
                !permissionManager!!.canAccessFragment("GoldRates") ||
                apiClient == null ||
                isGoldRefreshing.get() ||
                !ensureExecutor()
            ) {
                isRefreshingState = false
                return
            }

            val now = System.currentTimeMillis()
            if (!forceRefresh && (now - lastGoldFetchTime) < GOLD_CACHE_VALIDITY_MS) {
                displayGoldPricesFromCache()
                isRefreshingState = false
                return
            }

            isGoldRefreshing.set(true)
            isGoldLoadingState = true
            cancelFuture(goldPriceFuture)
            val appCtx = requireContext().applicationContext

            goldPriceFuture = localExecutor!!.submit {
                try {
                    val providers: List<ApiProvider>? = apiClient?.activeProviders
                    val activeProvider = providers?.firstOrNull {
                        it.isActive && !it.apiKey.isNullOrEmpty()
                    } ?: providers?.firstOrNull()

                    if (activeProvider == null) {
                        mainHandler.post {
                            isGoldRefreshing.set(false)
                            isGoldLoadingState = false
                            isRefreshingState = false
                            if (isAdded) displayGoldPricesFromCache()
                        }
                        return@submit
                    }

                    val response = apiClient?.fetchGoldPriceWithProvider(activeProvider.id)
                    val priceData = parseGoldPriceData(response)

                    mainHandler.post {
                        isGoldRefreshing.set(false)
                        isGoldLoadingState = false
                        isRefreshingState = false
                        if (!isAdded) return@post

                        if (priceData != null) {
                            lastGoldFetchTime = System.currentTimeMillis()
                            displayGoldPrices(priceData)
                            goldCachePrefs?.edit()?.putLong("last_gold_fetch_time", lastGoldFetchTime)?.apply()
                            if (forceRefresh) {
                                Toast.makeText(
                                    appCtx,
                                    if (isArabicState) "✅ تم تحديث أسعار الذهب" else "✅ Gold prices updated",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            displayGoldPricesFromCache()
                        }
                    }
                } catch (_: Exception) {
                    mainHandler.post {
                        isGoldRefreshing.set(false)
                        isGoldLoadingState = false
                        isRefreshingState = false
                        if (isAdded) displayGoldPricesFromCache()
                    }
                }
            }
        } catch (_: Exception) {
            isGoldRefreshing.set(false)
            isGoldLoadingState = false
            isRefreshingState = false
        }
    }

    private fun parseGoldPriceData(response: MiraApiClient.ApiResponse?): GoldPriceData? {
        return try {
            if (response == null || !response.isSuccess || response.data == null) return null
            val data = response.data.optJSONObject("data") ?: response.data
            val prices = data.optJSONObject("prices") ?: data
            val p24 = prices.optDouble("24k", 0.0)
            val p21 = prices.optDouble("21k", 0.0)
            val p18 = prices.optDouble("18k", 0.0)
            if (p24 <= 0.0 || p24 > 5000.0) return null
            val ounce = data.optDouble("price_per_ounce", p24 * OUNCE_TO_GRAM)
            GoldPriceData(p24, p21, p18, ounce, data.optString("source", "server"))
        } catch (_: Exception) {
            null
        }
    }

    private fun displayGoldPrices(data: GoldPriceData) {
        goldPrice24State = data.price24
        goldPrice21State = data.price21
        goldPrice18State = data.price18
        ouncePriceState = data.ouncePrice
        priceSourceTextState = "📡 " + getSourceDisplayName(data.source)
        calculateTrend(data.price24)
    }

    private fun displayGoldPricesFromCache() {
        val pm = priceManager ?: return
        val p24 = pm.getGoldPricePerGram(24)
        val p21 = pm.getGoldPricePerGram(21)
        val p18 = pm.getGoldPricePerGram(18)

        goldPrice24State = p24
        goldPrice21State = p21
        goldPrice18State = p18
        if (p24 > 0) ouncePriceState = p24 * OUNCE_TO_GRAM

        val now = System.currentTimeMillis()
        val remaining = GOLD_CACHE_VALIDITY_MS - (now - lastGoldFetchTime)
        priceSourceTextState = if (remaining > 0) {
            val hrs = remaining / 3600000
            val mins = (remaining % 3600000) / 60000
            if (isArabicState) "📡 كاش محلي ($hrs س $mins د)" else "📡 Cached ($hrs h $mins m)"
        } else {
            if (isArabicState) "📡 جاهز للتحديث" else "📡 Ready to update"
        }
    }

    private fun fetchApprovedGoldPricesFromServer() {
        if (roleController != null && !roleController!!.canFetchGoldPrices()) return
        if (apiClient == null || !isAdded || !isSessionValid() || !ensureExecutor()) return

        localExecutor!!.execute {
            try {
                val response = apiClient?.dashboardGoldPrices
                if (response != null && response.isSuccess && response.data != null) {
                    val prices = response.data.optJSONObject("prices")
                    if (prices != null) {
                        val p24 = prices.optDouble("24k", 0.0)
                        val p21 = prices.optDouble("21k", 0.0)
                        val p18 = prices.optDouble("18k", 0.0)
                        if (p24 > 0) {
                            val pd = GoldPriceData(
                                p24, p21, p18,
                                p24 * OUNCE_TO_GRAM,
                                response.data.optString("source", "server")
                            )
                            mainHandler.post { if (isAdded) displayGoldPrices(pd) }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun calculateTrend(currentPrice: Double) {
        val cache = offlineCache ?: return
        if (!ensureExecutor()) return
        localExecutor!!.execute {
            try {
                val prevPrice = cache.getFloat("gold_price_24k_prev", 0f).toDouble()
                if (prevPrice > 0 && currentPrice > 0) {
                    val changePercent = ((currentPrice - prevPrice) / prevPrice) * 100.0
                    mainHandler.post {
                        if (isAdded) {
                            goldChangePercentState = changePercent
                            hasGoldTrendState = true
                        }
                    }
                }
                if (currentPrice > 0) {
                    cache.edit().putFloat("gold_price_24k_prev", currentPrice.toFloat()).apply()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchRecentActivity() {
        if (roleController != null && !roleController!!.canFetchRecentActivity()) return
        if (apiClient == null || !isAdded || !isSessionValid() || !ensureExecutor()) return

        localExecutor!!.execute {
            try {
                val branchId = if (currentBranchId > 0) currentBranchId else (sessionManager?.branchId ?: 1)
                val response = apiClient?.getUnifiedActivity(branchId, 15)
                if (response != null && response.isSuccess && response.data != null) {
                    val activities = extractActivitiesArray(response.data)
                    if (activities != null) {
                        val itemsList = mutableListOf<JSONObject>()
                        for (i in 0 until activities.length()) {
                            activities.optJSONObject(i)?.let { itemsList.add(it) }
                        }
                        mainHandler.post {
                            if (isAdded) recentActivitiesState = itemsList
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchRecentActivity error", e)
            }
        }
    }

    private fun extractActivitiesArray(top: JSONObject): JSONArray? {
        top.optJSONArray("activities")?.let { return it }
        top.optJSONArray("data")?.let { return it }
        top.optJSONArray("items")?.let { return it }
        top.optJSONArray("logs")?.let { return it }
        top.optJSONArray("result")?.let { return it }
        val nested = top.optJSONObject("data")
        if (nested != null) {
            nested.optJSONArray("activities")?.let { return it }
            nested.optJSONArray("items")?.let { return it }
            nested.optJSONArray("logs")?.let { return it }
        }
        return null
    }

    private fun handleRecentActivityClick(activity: JSONObject) {
        if (!isAdded) return
        try {
            val sheet = ActivityDetailsBottomSheet.newInstance(activity, isArabicState)
            sheet.setOnActionClickListener(object : ActivityDetailsBottomSheet.OnActionClickListener {
                override fun onPrintInvoice(activityData: JSONObject) {
                    if (!isAdded || context == null) return
                    try {
                        val receiptFragment = MiraReceiptFragment.newInstance(activityData.toString())
                        val fm = parentFragmentManager
                        if (!fm.isStateSaved) receiptFragment.show(fm, "MiraReceiptFragment")
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            if (isArabicState) "تعذر فتح الفاتورة" else "Unable to open receipt",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onOpenRefund(activityData: JSONObject) {
                    if (!isAdded) return
                    try {
                        val salesFragment = MiraPosFragment().apply {
                            arguments = Bundle().apply {
                                putString("refund_data_json", activityData.toString())
                                putBoolean("auto_open_refund", true)
                            }
                        }
                        (getActivity() as? UHFMainActivity)?.loadFragment(salesFragment, "Sales")
                    } catch (_: Exception) {
                    }
                }
            })
            sheet.show(parentFragmentManager, "ActivityDetails")
        } catch (_: Exception) {
        }
    }

    private fun initBranchCache(branchId: Int) {
        try {
            val ctx = context ?: return
            val userId = sessionManager?.userId ?: return
            if (userId <= 0) return
            offlineCache = createSecurePrefs(ctx, "$OFFLINE_PREFS_BASE${userId}_b$branchId")
        } catch (_: Exception) {
        }
    }

    private fun createSecurePrefs(ctx: Context, name: String): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    private fun loadOfflineData() {
        val cache = offlineCache ?: return
        if (!isSessionValid() || !ensureExecutor()) return

        localExecutor!!.execute {
            try {
                val totalItems = cache.getInt("total_items", 0)
                val totalWeight = cache.getFloat("total_weight", 0f).toDouble()
                val invValue = cache.getFloat("inventory_value", 0f).toDouble()
                val sToday = cache.getFloat("sales_today", 0f).toDouble()
                val sMonth = cache.getFloat("sales_month", 0f).toDouble()
                val sYear = cache.getFloat("sales_year", 0f).toDouble()
                val karatJson = cache.getString("inventory_by_karat", null)

                val karatItems = mutableListOf<KaratInventory>()
                if (karatJson != null) {
                    try {
                        val arr = JSONArray(karatJson)
                        for (i in 0 until arr.length()) {
                            val k = arr.optJSONObject(i) ?: continue
                            val karat = k.optInt("karat", 0)
                            if (karat > 0) {
                                karatItems.add(KaratInventory(karat, k.optInt("count", 0), k.optDouble("weight", 0.0)))
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                mainHandler.post {
                    if (!isAdded) return@post
                    currentItemCountState = totalItems
                    currentTotalWeightState = totalWeight
                    currentInventoryValueState = invValue
                    inventoryByKaratState = karatItems.sortedByDescending { it.karat }
                    salesTodayState = sToday
                    salesMonthState = sMonth
                    salesYearState = sYear
                    posCashBalanceState = sToday
                    if (priceManager != null) displayGoldPricesFromCache()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveOfflineData(data: JSONObject) {
        val cache = offlineCache ?: return
        if (!isSessionValid() || !ensureExecutor()) return

        localExecutor!!.execute {
            try {
                val inv = data.optJSONObject("inventory")
                val sales = data.optJSONObject("sales")
                val editor = cache.edit()
                    .putInt("total_items", inv?.optInt("total_items", 0) ?: 0)
                    .putFloat("total_weight", (inv?.optDouble("total_weight", 0.0) ?: 0.0).toFloat())
                    .putFloat("inventory_value", (inv?.optDouble("total_value", 0.0) ?: 0.0).toFloat())
                    .putInt("sold_today", sales?.optInt("count_today", 0) ?: 0)
                    .putFloat("sales_today", (sales?.optDouble("today", 0.0) ?: 0.0).toFloat())
                    .putFloat("sales_month", (sales?.optDouble("month", 0.0) ?: 0.0).toFloat())
                    .putFloat("sales_year", (sales?.optDouble("year", 0.0) ?: 0.0).toFloat())
                inv?.optJSONArray("by_karat")?.let { editor.putString("inventory_by_karat", it.toString()) }
                editor.putLong("last_update", System.currentTimeMillis()).apply()
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshAllData() {
        isRefreshingState = true
        refreshRoleAndPermissionsState()
        fetchData()
        fetchRecentActivity()
        if (currentBranchId > 0) {
            fetchScrapSummary(currentBranchId)
            fetchScrapAging(currentBranchId)
        }
        fetchDashboardCharts()

        val now = System.currentTimeMillis()
        if (canRefreshGoldManuallyState) {
            if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) fetchLiveGoldPrices(true)
            else displayGoldPricesFromCache()
        } else {
            displayGoldPricesFromCache()
            fetchApprovedGoldPricesFromServer()
        }
    }

    fun onBranchChanged() {
        try {
            if (!isAdded) return
            val newBranchId = branchesManager?.activeBranchId ?: -1
            if (newBranchId != currentBranchId) {
                currentBranchId = newBranchId
                initBranchCache(currentBranchId)
                refreshRoleAndPermissionsState()
                syncOrgProfileFromServer(true)
                loadOfflineData()
                fetchData()
                fetchRecentActivity()
                if (currentBranchId > 0) {
                    fetchScrapSummary(currentBranchId)
                    fetchScrapAging(currentBranchId)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun setupProfileListener() {
        try {
            profileListener = MiraOrgProfileManager.OnProfileChangedListener {
                mainHandler.post { if (isAdded) updateHeaderIdentity() }
            }
            orgProfile?.addListener(profileListener)
        } catch (_: Exception) {
        }
    }

    private fun setupBranchListener() {
        try {
            branchListener = MiraBranchesManager.OnBranchChangedListener {
                mainHandler.post { if (isAdded) onBranchChanged() }
            }
            branchesManager?.addListener(branchListener)
        } catch (_: Exception) {
        }
    }

    private fun syncOrgProfileFromServer(force: Boolean) {
        val client = apiClient ?: return
        orgProfile?.syncFromServer(client, force, object : MiraOrgProfileManager.SyncCallback {
            override fun onSuccess() {
                mainHandler.post {
                    if (isAdded) updateHeaderIdentity()
                    branchesManager?.syncFromServer(object : MiraBranchesManager.SyncCallback {
                        override fun onSuccess() {}
                        override fun onError(error: String?) {}
                    })
                }
            }

            override fun onError(error: String?) {}
        })
    }

    private fun registerDataRefreshReceiver() {
        try {
            if (context == null || isReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction("com.example.uhf.REFRESH_DASHBOARD")
                addAction("com.example.uhf.REFRESH_INVENTORY")
                addAction("com.example.uhf.REFRESH_SALES")
            }
            ContextCompat.registerReceiver(requireContext(), dataRefreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterDataRefreshReceiver() {
        try {
            if (context != null && isReceiverRegistered) {
                requireContext().unregisterReceiver(dataRefreshReceiver)
                isReceiverRegistered = false
            }
        } catch (_: Exception) {
        }
    }

    private fun hideSensitiveValuesOnExit() {
        isHeroHiddenState = true
        isSalesHiddenState = true
        isInventoryHiddenState = true
        isScrapHiddenState = true
        isPosDashboardHiddenState = true
    }

    private fun startAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable)
        mainHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun stopAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable)
    }

    private fun navigateToFragment(fragment: Fragment, tag: String) {
        try {
            (activity as? UHFMainActivity)?.loadFragment(fragment, tag)
        } catch (_: Exception) {
        }
    }

    private fun getSourceDisplayName(source: String?): String {
        if (source == null) return if (isArabicState) "غير معروف" else "Unknown"
        return when (source.lowercase(Locale.US)) {
            "metalprice" -> "MetalPrice"
            "goldapi" -> "GoldAPI"
            "metalsdev" -> "Metals.Dev"
            "fallback" -> if (isArabicState) "احتياطي" else "Fallback"
            else -> source
        }
    }

    private fun formatCurrency(amount: Double): String {
        if (amount <= 0) return "—"
        return try {
            priceManager?.formatPriceDefault(amount)
                ?: NumberFormat.getCurrencyInstance(Locale.US).format(amount)
        } catch (_: Exception) {
            String.format(Locale.US, "$%,.2f", amount)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSensitiveValuesOnExit()
        registerDataRefreshReceiver()

        val latestBranchId = branchesManager?.activeBranchId ?: -1
        if (latestBranchId != currentBranchId) {
            onBranchChanged()
        } else if (isViewInitialized) {
            syncOrgProfileFromServer(false)
            refreshRoleAndPermissionsState()

            val now = System.currentTimeMillis()
            if ((now - lastDataFetchTime) > RESUME_REFETCH_THRESHOLD_MS) {
                fetchData()
                if (currentBranchId > 0) {
                    fetchScrapSummary(currentBranchId)
                    fetchScrapAging(currentBranchId)
                }
            }
            if (activityCache == null || !activityCache!!.isValid) {
                mainHandler.postDelayed({ if (isAdded && isResumed) fetchRecentActivity() }, 600)
            }
            if ((now - lastGoldFetchTime) > 30 * 60 * 1000L && canRefreshGoldManuallyState) {
                mainHandler.postDelayed({ if (isAdded && isResumed) fetchLiveGoldPrices(false) }, 1200)
            } else {
                displayGoldPricesFromCache()
                fetchApprovedGoldPricesFromServer()
            }
        }
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        unregisterDataRefreshReceiver()
        stopAutoRefresh()
        hideSensitiveValuesOnExit()
    }

    override fun onStop() {
        super.onStop()
        hideSensitiveValuesOnExit()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isArabicState = newConfig.locales[0].language == "ar"
        refreshRoleAndPermissionsState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterDataRefreshReceiver()
        stopAutoRefresh()
        profileListener?.let { orgProfile?.removeListener(it) }
        branchListener?.let { branchesManager?.removeListener(it) }
        cancelFuture(dataFuture)
        cancelFuture(goldPriceFuture)
        mainHandler.removeCallbacksAndMessages(null)
        shutdownExecutor()
        isViewInitialized = false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تجميع الشاشة حسب وضع الصلاحيات (UI Orchestrator)
    // ═══════════════════════════════════════════════════════════════════
    @Composable
    private fun MiraDashboardScreen() {
        val colors = MiraTheme.colors
        val computedTotalValue = if (currentInventoryValueState <= 0 && priceManager != null && currentTotalWeightState > 0) {
            currentTotalWeightState * priceManager!!.getGoldPricePerGram(24)
        } else {
            currentInventoryValueState
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
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "header") {
                        DashboardTopHeader(
                            isArabic = isArabicState,
                            orgTitle = orgTitleState,
                            userName = userGreetingNameState,
                            isOnline = isOnlineState,
                            isRefreshing = isRefreshingState,
                            onRefresh = { refreshAllData() }
                        )
                    }

                    when (layoutModeState) {
                        DashboardLayoutMode.POS_ONLY -> {
                            item(key = "pos_hero") {
                                PosVaultSummaryCard(
                                    isArabic = isArabicState,
                                    isHidden = isPosDashboardHiddenState,
                                    onToggleHidden = { isPosDashboardHiddenState = !isPosDashboardHiddenState },
                                    totalWeight = currentTotalWeightState,
                                    totalValue = computedTotalValue,
                                    cashBalance = posCashBalanceState,
                                    formattedCash = formatCurrency(posCashBalanceState)
                                )
                            }
                            if (inventoryByKaratState.isNotEmpty()) {
                                item(key = "pos_karats") {
                                    PosKaratChipsRow(
                                        isHidden = isPosDashboardHiddenState,
                                        karats = inventoryByKaratState,
                                        priceManager = priceManager
                                    )
                                }
                            }
                            if (quickActionsState.isNotEmpty()) {
                                item(key = "pos_actions") {
                                    QuickActionsSection(
                                        isArabic = isArabicState,
                                        actions = quickActionsState,
                                        onActionClick = { navigateToFragment(it.fragmentProvider(), it.tag) }
                                    )
                                }
                            }
                            if (sales7DaysState.isNotEmpty()) {
                                item(key = "pos_chart") {
                                    SalesTrendChartCard(
                                        title = if (isArabicState) "حركة المبيعات والتقدير (7 أيام)" else "Sales & Estimate Trend (7D)",
                                        points = sales7DaysState,
                                        showDualCurve = true
                                    )
                                }
                            }
                            item(key = "pos_activity") {
                                RecentActivitySection(
                                    isArabic = isArabicState,
                                    activities = recentActivitiesState.take(5),
                                    maskAmounts = isPosDashboardHiddenState,
                                    onViewAll = { navigateToFragment(MiraAllActivitiesFragment(), "AllActivities") },
                                    onItemClick = { handleRecentActivityClick(it) }
                                )
                            }
                        }

                        DashboardLayoutMode.GATE_ONLY -> {
                            item(key = "gate_telemetry") {
                                GateSecurityTelemetryCard(
                                    isArabic = isArabicState,
                                    scansToday = gateScansTodayState,
                                    allowedToday = gateAllowedTodayState,
                                    deniedToday = gateDeniedTodayState,
                                    onOpenGate = { navigateToFragment(MiraSecureGateFragment(), "SecureGate") }
                                )
                            }
                            if (quickActionsState.isNotEmpty()) {
                                item(key = "gate_actions") {
                                    QuickActionsSection(
                                        isArabic = isArabicState,
                                        actions = quickActionsState,
                                        onActionClick = { navigateToFragment(it.fragmentProvider(), it.tag) }
                                    )
                                }
                            }
                            item(key = "gate_activity") {
                                RecentActivitySection(
                                    isArabic = isArabicState,
                                    activities = recentActivitiesState.take(10),
                                    maskAmounts = false,
                                    onViewAll = { navigateToFragment(MiraAllActivitiesFragment(), "AllActivities") },
                                    onItemClick = { handleRecentActivityClick(it) }
                                )
                            }
                        }

                        DashboardLayoutMode.STANDARD -> {
                            if (alertCountState > 0) {
                                item(key = "alerts_strip") {
                                    AlertsBannerCard(
                                        isArabic = isArabicState,
                                        count = alertCountState,
                                        message = alertMessageState,
                                        onClick = { navigateToFragment(MiraUHFReadTagFragment(), "Scan") }
                                    )
                                }
                            }

                            val carouselTypes = buildList {
                                if (showHeroCardState) add("HERO")
                                if (showGoldCardState) add("GOLD")
                                if (showSalesCardState) add("SALES")
                            }

                            if (carouselTypes.isNotEmpty()) {
                                item(key = "carousel_cards") {
                                    ExecutiveCarouselSection(
                                        cardTypes = carouselTypes,
                                        isArabic = isArabicState,
                                        isHeroHidden = isHeroHiddenState,
                                        onToggleHeroHidden = { isHeroHiddenState = !isHeroHiddenState },
                                        totalInventoryValue = computedTotalValue,
                                        totalWeight = currentTotalWeightState,
                                        totalItemCount = currentItemCountState,
                                        goldPrice24 = goldPrice24State,
                                        goldPrice21 = goldPrice21State,
                                        goldPrice18 = goldPrice18State,
                                        ouncePrice = ouncePriceState,
                                        priceSourceText = priceSourceTextState,
                                        goldChangePercent = goldChangePercentState,
                                        hasGoldTrend = hasGoldTrendState,
                                        canRefreshGoldManually = canRefreshGoldManuallyState,
                                        isGoldLoading = isGoldLoadingState,
                                        onRefreshGoldClick = { fetchLiveGoldPrices(true) },
                                        isSalesHidden = isSalesHiddenState,
                                        onToggleSalesHidden = { isSalesHiddenState = !isSalesHiddenState },
                                        salesToday = salesTodayState,
                                        salesMonth = salesMonthState,
                                        salesYear = salesYearState
                                    )
                                }
                            }

                            if (quickActionsState.isNotEmpty()) {
                                item(key = "std_actions") {
                                    QuickActionsSection(
                                        isArabic = isArabicState,
                                        actions = quickActionsState,
                                        onActionClick = { navigateToFragment(it.fragmentProvider(), it.tag) }
                                    )
                                }
                            }

                            if (showInventoryCardState) {
                                item(key = "inventory_karat") {
                                    InventoryByKaratCard(
                                        isArabic = isArabicState,
                                        isHidden = isInventoryHiddenState,
                                        onToggleHidden = { isInventoryHiddenState = !isInventoryHiddenState },
                                        items = inventoryByKaratState
                                    )
                                }
                            }

                            if (showScrapCardState) {
                                item(key = "scrap_gold") {
                                    ScrapGoldSummaryCard(
                                        isArabic = isArabicState,
                                        isHidden = isScrapHiddenState,
                                        onToggleHidden = { isScrapHiddenState = !isScrapHiddenState },
                                        weight = scrapWeightState,
                                        formattedValue = formatCurrency(scrapValueState),
                                        onOpenBuy = { navigateToFragment(MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY) },
                                        onOpenHistory = { navigateToFragment(MiraScrapHistoryFragment(), NavigationManager.TAG_SCRAP_HISTORY) },
                                        onOpenBatches = { navigateToFragment(MiraScrapBatchesFragment(), NavigationManager.TAG_SCRAP_BATCHES) }
                                    )
                                }
                            }

                            if (showScrapAgingCardState) {
                                item(key = "scrap_aging") {
                                    ScrapAgingAlertCard(
                                        isArabic = isArabicState,
                                        totalItems = agingTotalItemsState,
                                        totalWeight = agingTotalWeightState,
                                        oldCount = agingOldCountState,
                                        oldWeight = agingOldWeightState,
                                        onOpenReport = { navigateToFragment(MiraScrapAgingFragment(), NavigationManager.TAG_SCRAP_AGING) }
                                    )
                                }
                            }

                            if (showChartsCardState && (sales7DaysState.isNotEmpty() || salesByKaratState.isNotEmpty())) {
                                item(key = "analytics_charts") {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        if (sales7DaysState.isNotEmpty()) {
                                            SalesTrendChartCard(
                                                title = if (isArabicState) "منحنى المبيعات (آخر 7 أيام)" else "Sales Curve (Last 7 Days)",
                                                points = sales7DaysState,
                                                showDualCurve = false
                                            )
                                        }
                                        if (salesByKaratState.isNotEmpty()) {
                                            KaratDistributionDonutCard(
                                                isArabic = isArabicState,
                                                slices = salesByKaratState
                                            )
                                        }
                                    }
                                }
                            }

                            if (showRecentActivityState) {
                                item(key = "recent_activity") {
                                    RecentActivitySection(
                                        isArabic = isArabicState,
                                        activities = recentActivitiesState.take(10),
                                        maskAmounts = false,
                                        onViewAll = { navigateToFragment(MiraAllActivitiesFragment(), "AllActivities") },
                                        onItemClick = { handleRecentActivityClick(it) }
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
