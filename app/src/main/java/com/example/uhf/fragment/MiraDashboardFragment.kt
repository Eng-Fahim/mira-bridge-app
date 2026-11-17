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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
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

    // ═══ نماذج البيانات الداخلية ═══
    data class KaratInventory(val karat: Int, val itemCount: Int, val weight: Double)

    data class GoldPriceData(
        val price24: Double,
        val price21: Double,
        val price18: Double,
        val ouncePrice: Double,
        val source: String
    )

    data class QuickActionItem(
        val titleAr: String,
        val titleEn: String,
        val icon: ImageVector,
        val accentColor: Color,
        val tag: String,
        val fragmentProvider: () -> Fragment
    )

    data class SalesPoint(val label: String, val amount: Double)

    data class KaratSalesSlice(val label: String, val amount: Double)

    // ═══ المدراء والخدمات ═══
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

    // ═══ حالات الخصوصية (تُقفل تلقائياً عند الخروج) ═══
    private var isHeroHiddenState by mutableStateOf(true)
    private var isSalesHiddenState by mutableStateOf(true)
    private var isInventoryHiddenState by mutableStateOf(true)
    private var isScrapHiddenState by mutableStateOf(true)
    private var isPosDashboardHiddenState by mutableStateOf(true)

    // ═══ حالات الواجهة التفاعلية (Compose Reactive States) ═══
    private var isArabicState by mutableStateOf(true)
    private var isOnlineState by mutableStateOf(true)
    private var isRefreshingState by mutableStateOf(false)
    private var orgTitleState by mutableStateOf("MIRA Jewelry")
    private var userGreetingNameState by mutableStateOf("")

    // وضع التخطيط المشتق من صلاحيات السيرفر
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

    // بيانات المخزون والخزنة
    private var currentInventoryValueState by mutableDoubleStateOf(0.0)
    private var currentTotalWeightState by mutableDoubleStateOf(0.0)
    private var currentItemCountState by mutableIntStateOf(0)
    private var inventoryByKaratState by mutableStateOf<List<KaratInventory>>(emptyList())

    // بيانات المبيعات والكاش
    private var salesTodayState by mutableDoubleStateOf(0.0)
    private var salesMonthState by mutableDoubleStateOf(0.0)
    private var salesYearState by mutableDoubleStateOf(0.0)
    private var posCashBalanceState by mutableDoubleStateOf(0.0)

    // بيانات أسعار الذهب
    private var goldPrice24State by mutableDoubleStateOf(0.0)
    private var goldPrice21State by mutableDoubleStateOf(0.0)
    private var goldPrice18State by mutableDoubleStateOf(0.0)
    private var ouncePriceState by mutableDoubleStateOf(0.0)
    private var priceSourceTextState by mutableStateOf("📡 —")
    private var lastUpdateTextState by mutableStateOf("")
    private var goldChangePercentState by mutableDoubleStateOf(0.0)
    private var hasGoldTrendState by mutableStateOf(false)
    private var isGoldLoadingState by mutableStateOf(false)

    // بيانات الكسر وتقادمه
    private var scrapWeightState by mutableDoubleStateOf(0.0)
    private var scrapValueState by mutableDoubleStateOf(0.0)
    private var agingTotalItemsState by mutableIntStateOf(0)
    private var agingTotalWeightState by mutableDoubleStateOf(0.0)
    private var agingOldCountState by mutableIntStateOf(0)
    private var agingOldWeightState by mutableDoubleStateOf(0.0)

    // بيانات البوابة الأمنية (Gate Security Variant)
    private var gateScansTodayState by mutableIntStateOf(0)
    private var gateAllowedTodayState by mutableIntStateOf(0)
    private var gateDeniedTodayState by mutableIntStateOf(0)

    // شريط التنبيهات
    private var alertCountState by mutableIntStateOf(0)
    private var alertMessageState by mutableStateOf("")

    // الرسوم البيانية والإجراءات السريعة والنشاط الأخير
    private var sales7DaysState by mutableStateOf<List<SalesPoint>>(emptyList())
    private var salesByKaratState by mutableStateOf<List<KaratSalesSlice>>(emptyList())
    private var quickActionsState by mutableStateOf<List<QuickActionItem>>(emptyList())
    private var recentActivitiesState by mutableStateOf<List<JSONObject>>(emptyList())

    enum class DashboardLayoutMode { STANDARD, POS_ONLY, GATE_ONLY }

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
                    if (isAdded() && isViewInitialized) {
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

    // ═══════════════════════════════════════════════════════════════════
    //  1. تهيئة المدراء والصلاحيات القادمة من السيرفر
    // ═══════════════════════════════════════════════════════════════════
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
            list.add(
                QuickActionItem("بيع", "POS Sale", Icons.Rounded.PointOfSale, Color(0xFFD4AF37), NavigationManager.TAG_POS) {
                    MiraPosFragment()
                }
            )
        }
        if (pm.canBuyScrapGold()) {
            list.add(
                QuickActionItem("شراء كسر", "Buy Scrap", Icons.Rounded.Recycling, Color(0xFFF59E0B), NavigationManager.TAG_SCRAP_GOLD_BUY) {
                    MiraScrapGoldBuyFragment()
                }
            )
        }
        if (pm.canViewInventory()) {
            list.add(
                QuickActionItem("الجرد", "Inventory", Icons.Rounded.Inventory2, Color(0xFF38BDF8), "InventoryDashboard") {
                    MiraInventoryDashboardFragment()
                }
            )
        }
        if (pm.canUseIdentity()) {
            list.add(
                QuickActionItem("التكويد", "Encoding", Icons.Rounded.QrCode2, Color(0xFF10B981), "IdentityStudio") {
                    MiraIdentityStudioFragment()
                }
            )
        }
        if (pm.canViewTransfers()) {
            list.add(
                QuickActionItem("التحويلات", "Transfers", Icons.Rounded.SwapHoriz, Color(0xFFA855F7), "Transfers") {
                    MiraTransferFragment()
                }
            )
        }
        if (pm.canViewRadar()) {
            list.add(
                QuickActionItem("الرادار", "Radar", Icons.Rounded.Radar, Color(0xFF06B6D4), "Radar") {
                    MiraUHFRadarLocationFragment()
                }
            )
        }
        if (pm.canViewGates()) {
            list.add(
                QuickActionItem("البوابات", "Gates", Icons.Rounded.Shield, Color(0xFFEF4444), "SecureGate") {
                    MiraSecureGateFragment()
                }
            )
        }
        if (pm.canViewAccounting()) {
            list.add(
                QuickActionItem("المحاسبة", "Accounting", Icons.Rounded.AccountBalance, Color(0xFF22C55E), "Accounting") {
                    MiraAccountingFragment()
                }
            )
        }
        if (pm.canViewSalesHistory()) {
            list.add(
                QuickActionItem("سجل المبيعات", "Sales Log", Icons.Rounded.ReceiptLong, Color(0xFFEAB308), "SalesHistory") {
                    MiraSalesHistoryFragment()
                }
            )
        }
        if (pm.canViewReports()) {
            list.add(
                QuickActionItem("التقارير", "Reports", Icons.Rounded.BarChart, Color(0xFF6366F1), NavigationManager.TAG_REPORTS) {
                    MiraReportsProFragment()
                }
            )
        }
        if (pm.canViewGoldRates()) {
            list.add(
                QuickActionItem("أسعار الذهب", "Gold Rates", Icons.Rounded.MonetizationOn, Color(0xFFD4AF37), "GoldRates") {
                    MiraGoldCurrencyFragment()
                }
            )
        }
        if (pm.canViewAdminHub()) {
            list.add(
                QuickActionItem("الإدارة", "Admin Hub", Icons.Rounded.AdminPanelSettings, Color(0xFFF43F5E), "AdminHub") {
                    MiraAdminHubFragment()
                }
            )
        }

        // زر دائم للملف الشخصي
        list.add(
            QuickActionItem("الملف الشخصي", "Profile", Icons.Rounded.AccountCircle, Color(0xFF94A3B8), "Profile") {
                MiraProfileFragment()
            }
        )

        quickActionsState = list
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. جلب البيانات والاتصال بالخادم
    // ═══════════════════════════════════════════════════════════════════
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
                            val rootData = if (response.data.has("data")) {
                                response.data.optJSONObject("data")
                            } else {
                                response.data
                            }
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
            // 1. معالجة خاصة لأمن البوابات
            if (roleController?.isGateOnlyLayout() == true) {
                val gates = data.optJSONObject("gates")
                if (gates != null) {
                    gateScansTodayState = gates.optInt("scans_today", 0)
                    gateAllowedTodayState = gates.optInt("allowed_today", 0)
                    gateDeniedTodayState = gates.optInt("denied_today", 0)
                }
                return
            }

            // 2. المخزون والعيارات
            val inv = data.optJSONObject("inventory") ?: data.optJSONObject("inventory_data")
            if (inv != null) {
                var itemCount = inv.optInt("total_items", 0)
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

            // 3. المبيعات والكاش
            val sales = data.optJSONObject("sales")
            if (sales != null) {
                salesTodayState = sales.optDouble("today", 0.0)
                salesMonthState = sales.optDouble("month", 0.0)
                salesYearState = sales.optDouble("year", 0.0)
            }

            val shiftObj = data.optJSONObject("shift_summary")
            posCashBalanceState = shiftObj?.optDouble("total_cash", 0.0) ?: salesTodayState

            // 4. بيانات الرسم البياني لـ 7 أيام (إذا وردت في الاستجابة الرئيسية)
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
                    val rootData = if (response.data.has("data")) {
                        response.data.optJSONObject("data")
                    } else {
                        response.data
                    }
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

    // ═══════════════════════════════════════════════════════════════════
    //  3. أسعار الذهب الحية والمحفوظة
    // ═══════════════════════════════════════════════════════════════════
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
                    var activeProvider: ApiProvider? = null
                    if (!providers.isNullOrEmpty()) {
                        activeProvider = providers.firstOrNull {
                            it.isActive && !it.apiKey.isNullOrEmpty()
                        } ?: providers[0]
                    }

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
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lastUpdateTextState = if (isArabicState) "آخر تحديث: $timeStr" else "Updated: $timeStr"
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

        val lastUpd = offlineCache?.getLong("last_update", 0L) ?: 0L
        if (lastUpd > 0) {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastUpd))
            lastUpdateTextState = if (isArabicState) "آخر تحديث: $timeStr" else "Updated: $timeStr"
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

    // ═══════════════════════════════════════════════════════════════════
    //  4. النشاط الأخير والتفاصيل التفاعلية
    // ═══════════════════════════════════════════════════════════════════
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
                        if (!fm.isStateSaved) {
                            receiptFragment.show(fm, "MiraReceiptFragment")
                        }
                    } catch (_: Exception) {
                        Toast.makeText(
                            context,
                            if (isArabicState) "تعذر فتح الفاتورة" else "Unable to open receipt",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onOpenRefund(activityData: JSONObject) {
                    if (!isAdded || activity == null) return
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

    // ═══════════════════════════════════════════════════════════════════
    //  5. التخزين المحلي المشفر (Offline Cache)
    // ═══════════════════════════════════════════════════════════════════
    private fun initBranchCache(branchId: Int) {
        try {
            val ctx = context ?: return
            val userId = sessionManager?.userId ?: return
            if (userId <= 0) return
            val prefsName = "$OFFLINE_PREFS_BASE${userId}_b$branchId"
            offlineCache = createSecurePrefs(ctx, prefsName)
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
                                karatItems.add(
                                    KaratInventory(karat, k.optInt("count", 0), k.optDouble("weight", 0.0))
                                )
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

    // ═══════════════════════════════════════════════════════════════════
    //  6. التحديث اليدوي ودورة الحياة (Lifecycle)
    // ═══════════════════════════════════════════════════════════════════
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
            if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                fetchLiveGoldPrices(true)
            } else {
                displayGoldPricesFromCache()
            }
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
            ContextCompat.registerReceiver(
                requireContext(),
                dataRefreshReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
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
    //  7. دوال التنسيق المساعدة
    // ═══════════════════════════════════════════════════════════════════
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

    private fun formatCurrencyShort(amount: Double): String {
        if (amount <= 0) return "0"
        return when {
            amount >= 1_000_000 -> String.format(Locale.US, "%.1fM", amount / 1_000_000)
            amount >= 1_000 -> String.format(Locale.US, "%.1fK", amount / 1_000)
            else -> String.format(Locale.US, "%.0f", amount)
        }
    }

    private fun formatWeight(weightInGrams: Double): String {
        if (weightInGrams <= 0) return "0.0 g"
        return if (weightInGrams >= 1000) {
            String.format(Locale.US, "%.2f kg", weightInGrams / 1000)
        } else {
            String.format(Locale.US, "%.1f g", weightInGrams)
        }
    }

    private fun extractTime(dateStr: String): String {
        if (dateStr.isEmpty()) return ""
        return try {
            val parts = dateStr.split(" ")
            if (parts.size >= 2) {
                val timeParts = parts[1].split(":")
                if (timeParts.size >= 2) "${timeParts[0]}:${timeParts[1]}" else dateStr
            } else dateStr
        } catch (_: Exception) {
            dateStr
        }
    }

    private fun getKaratAccentColor(karat: Int): Color {
        return when (karat) {
            24 -> Color(0xFFD4AF37)
            22 -> Color(0xFFE6B800)
            21 -> Color(0xFFF59E0B)
            18 -> Color(0xFFCD853F)
            else -> Color(0xFFA0522D)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. واجهات العرض الرئيسية (Jetpack Compose UI)
    // ═══════════════════════════════════════════════════════════════════
    @Composable
    private fun MiraDashboardScreen() {
        val colors = MiraTheme.colors

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
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 1. شريط الترويسة الموحد وحالة الاتصال
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

                    // 2. التبديل الذكي بين واجهات الأدوار الثلاث
                    when (layoutModeState) {
                        DashboardLayoutMode.POS_ONLY -> {
                            item(key = "pos_hero") {
                                PosVaultSummaryCard(
                                    isArabic = isArabicState,
                                    isHidden = isPosDashboardHiddenState,
                                    onToggleHidden = { isPosDashboardHiddenState = !isPosDashboardHiddenState },
                                    totalWeight = currentTotalWeightState,
                                    totalValue = currentInventoryValueState,
                                    cashBalance = posCashBalanceState,
                                    formattedCash = formatCurrency(posCashBalanceState)
                                )
                            }

                            if (inventoryByKaratState.isNotEmpty()) {
                                item(key = "pos_karats") {
                                    PosKaratChipsRow(
                                        isArabic = isArabicState,
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
                                        onActionClick = { item ->
                                            navigateToFragment(item.fragmentProvider(), item.tag)
                                        }
                                    )
                                }
                            }

                            if (sales7DaysState.isNotEmpty()) {
                                item(key = "pos_chart") {
                                    SalesTrendChartCard(
                                        isArabic = isArabicState,
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
                                    onOpenGate = {
                                        navigateToFragment(MiraSecureGateFragment(), "SecureGate")
                                    }
                                )
                            }

                            if (quickActionsState.isNotEmpty()) {
                                item(key = "gate_actions") {
                                    QuickActionsSection(
                                        isArabic = isArabicState,
                                        actions = quickActionsState,
                                        onActionClick = { item ->
                                            navigateToFragment(item.fragmentProvider(), item.tag)
                                        }
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
                            // شريط التنبيهات الذكي
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

                            // بطاقات العرض الدوارة (Hero / Gold / Sales) حسب الصلاحيات
                            val carouselTypes = buildList {
                                if (showHeroCardState) add("HERO")
                                if (showGoldCardState) add("GOLD")
                                if (showSalesCardState) add("SALES")
                            }

                            if (carouselTypes.isNotEmpty()) {
                                item(key = "carousel_cards") {
                                    ExecutiveCarouselSection(
                                        cardTypes = carouselTypes,
                                        isArabic = isArabicState
                                    )
                                }
                            }

                            // الإجراءات السريعة
                            if (quickActionsState.isNotEmpty()) {
                                item(key = "std_actions") {
                                    QuickActionsSection(
                                        isArabic = isArabicState,
                                        actions = quickActionsState,
                                        onActionClick = { item ->
                                            navigateToFragment(item.fragmentProvider(), item.tag)
                                        }
                                    )
                                }
                            }

                            // المخزون حسب العيار
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

                            // بطاقة ذهب الكسر
                            if (showScrapCardState) {
                                item(key = "scrap_gold") {
                                    ScrapGoldSummaryCard(
                                        isArabic = isArabicState,
                                        isHidden = isScrapHiddenState,
                                        onToggleHidden = { isScrapHiddenState = !isScrapHiddenState },
                                        weight = scrapWeightState,
                                        formattedValue = formatCurrency(scrapValueState),
                                        onOpenBuy = {
                                            navigateToFragment(MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY)
                                        },
                                        onOpenHistory = {
                                            navigateToFragment(MiraScrapHistoryFragment(), NavigationManager.TAG_SCRAP_HISTORY)
                                        },
                                        onOpenBatches = {
                                            navigateToFragment(MiraScrapBatchesFragment(), NavigationManager.TAG_SCRAP_BATCHES)
                                        }
                                    )
                                }
                            }

                            // بطاقة تقادم الكسر (+60 يوماً)
                            if (showScrapAgingCardState) {
                                item(key = "scrap_aging") {
                                    ScrapAgingAlertCard(
                                        isArabic = isArabicState,
                                        totalItems = agingTotalItemsState,
                                        totalWeight = agingTotalWeightState,
                                        oldCount = agingOldCountState,
                                        oldWeight = agingOldWeightState,
                                        onOpenReport = {
                                            navigateToFragment(MiraScrapAgingFragment(), NavigationManager.TAG_SCRAP_AGING)
                                        }
                                    )
                                }
                            }

                            // الرسوم البيانية التحليلية
                            if (showChartsCardState && (sales7DaysState.isNotEmpty() || salesByKaratState.isNotEmpty())) {
                                item(key = "analytics_charts") {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        if (sales7DaysState.isNotEmpty()) {
                                            SalesTrendChartCard(
                                                isArabic = isArabicState,
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

                            // سجل النشاط الأخير
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

    // ═══════════════════════════════════════════════════════════════════
    //  مكونات الواجهة الفرعية (Sub-Composables)
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun DashboardTopHeader(
        isArabic: Boolean,
        orgTitle: String,
        userName: String,
        isOnline: Boolean,
        isRefreshing: Boolean,
        onRefresh: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val spinTransition = rememberInfiniteTransition(label = "refreshSpin")
        val spinAngle by spinTransition.animateFloat(
            initialValue = 0f,
            targetValue = if (isRefreshing) 360f else 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spinAngle"
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = orgTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (userName.isNotEmpty()) {
                    Text(
                        text = if (isArabic) "مرحباً، $userName" else "Welcome, $userName",
                        fontSize = 12.sp,
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
                // شارة حالة الاتصال الحي
                val statusColor = if (isOnline) Color(0xFF10B981) else Color(0xFFEF4444)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOnline) (if (isArabic) "متصل" else "Online")
                            else (if (isArabic) "غير متصل" else "Offline"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }

                // زر التحديث الفوري
                Surface(
                    shape = CircleShape,
                    color = colors.cardSurface,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isRefreshing) { onRefresh() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = colors.goldPrimary,
                            modifier = Modifier
                                .size(19.dp)
                                .rotate(if (isRefreshing) spinAngle else 0f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AlertsBannerCard(
        isArabic: Boolean,
        count: Int,
        message: String,
        onClick: () -> Unit
    ) {
        val warnColor = Color(0xFFF59E0B)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = warnColor.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, warnColor.copy(alpha = 0.55f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = warnColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = message,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiraTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isArabic) "عرض ($count)" else "View ($count)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = warnColor
                )
            }
        }
    }

    @Composable
    private fun ExecutiveCarouselSection(
        cardTypes: List<String>,
        isArabic: Boolean
    ) {
        val colors = MiraTheme.colors
        val pagerState = rememberPagerState(pageCount = { cardTypes.size })

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = if (cardTypes.size > 1) 8.dp else 0.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                when (cardTypes[page]) {
                    "HERO" -> HeroVaultCard(isArabic = isArabic)
                    "GOLD" -> LiveGoldPricesCard(isArabic = isArabic)
                    "SALES" -> SalesSummaryCard(isArabic = isArabic)
                }
            }

            if (cardTypes.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(cardTypes.size) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (active) 20.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (active) colors.goldPrimary else colors.cardBorder)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HeroVaultCard(isArabic: Boolean) {
        val colors = MiraTheme.colors
        var totalValue = currentInventoryValueState
        if (totalValue <= 0 && priceManager != null && currentTotalWeightState > 0) {
            totalValue = currentTotalWeightState * priceManager!!.getGoldPricePerGram(24)
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.2.dp, colors.goldPrimary.copy(alpha = 0.65f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.goldPrimary.copy(alpha = 0.16f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Diamond,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isArabic) "إجمالي قيمة الخزنة والمخزون" else "Total Vault & Inventory Value",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textSecondary
                            )
                        }

                        IconButton(
                            onClick = { isHeroHiddenState = !isHeroHiddenState },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isHeroHiddenState) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle Visibility",
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isHeroHiddenState) "••••••••" else String.format(Locale.US, "$%,.2f", totalValue),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.goldPrimary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        VaultStatChip(
                            label = if (isArabic) "الوزن الكلي" else "Total Weight",
                            value = if (isHeroHiddenState) "••• g" else String.format(Locale.US, "%.1f g", currentTotalWeightState),
                            icon = Icons.Rounded.Scale,
                            modifier = Modifier.weight(1f)
                        )
                        VaultStatChip(
                            label = if (isArabic) "عدد القطع" else "Total Items",
                            value = if (isHeroHiddenState) "•••" else String.format(Locale.getDefault(), "%d قطعة", currentItemCountState),
                            icon = Icons.Rounded.Category,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun VaultStatChip(
        label: String,
        value: String,
        icon: ImageVector,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.backgroundTop.copy(alpha = 0.5f))
                .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.goldPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(text = label, fontSize = 10.sp, color = colors.textSecondary)
                Text(
                    text = value,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        }
    }

    @Composable
    private fun LiveGoldPricesCard(isArabic: Boolean) {
        val colors = MiraTheme.colors

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.ShowChart,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "أسعار الذهب المباشرة" else "Live Gold Bullion",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasGoldTrendState) {
                            val isUp = goldChangePercentState > 0
                            val isDown = goldChangePercentState < 0
                            val trendColor = when {
                                isUp -> Color(0xFF16A34A)
                                isDown -> Color(0xFFDC2626)
                                else -> colors.textSecondary
                            }
                            Text(
                                text = String.format(Locale.US, "%+.2f%%", goldChangePercentState),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = trendColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        if (canRefreshGoldManuallyState) {
                            if (isGoldLoadingState) {
                                CircularProgressIndicator(
                                    color = colors.goldPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                IconButton(
                                    onClick = { fetchLiveGoldPrices(true) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Sync,
                                        contentDescription = "Update Gold",
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GoldKaratBox("24K", goldPrice24State, Modifier.weight(1f))
                    GoldKaratBox("21K", goldPrice21State, Modifier.weight(1f))
                    GoldKaratBox("18K", goldPrice18State, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (ouncePriceState > 0) {
                            String.format(Locale.US, "OZ: $%,.2f", ouncePriceState)
                        } else "OZ: —",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.goldPrimary
                    )
                    Text(
                        text = priceSourceTextState,
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }

    @Composable
    private fun GoldKaratBox(karatLabel: String, price: Double, modifier: Modifier = Modifier) {
        val colors = MiraTheme.colors
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.backgroundTop.copy(alpha = 0.55f))
                .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                .padding(vertical = 10.dp, horizontal = 6.dp)
        ) {
            Text(
                text = karatLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.goldPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (price > 0) String.format(Locale.US, "$%,.2f", price) else "—",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Text(
                text = "/g",
                fontSize = 10.sp,
                color = colors.textSecondary
            )
        }
    }

    @Composable
    private fun SalesSummaryCard(isArabic: Boolean) {
        val colors = MiraTheme.colors

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.TrendingUp,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "ملخص أداء المبيعات" else "Sales Performance",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }

                    IconButton(
                        onClick = { isSalesHiddenState = !isSalesHiddenState },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isSalesHiddenState) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle Sales",
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SalesMetricBox(
                        label = if (isArabic) "اليوم" else "Today",
                        value = if (isSalesHiddenState) "••••" else formatCurrencyShort(salesTodayState),
                        accent = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                    SalesMetricBox(
                        label = if (isArabic) "الشهر" else "Month",
                        value = if (isSalesHiddenState) "••••" else formatCurrencyShort(salesMonthState),
                        accent = colors.goldPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    SalesMetricBox(
                        label = if (isArabic) "السنة" else "Year",
                        value = if (isSalesHiddenState) "••••" else formatCurrencyShort(salesYearState),
                        accent = Color(0xFF38BDF8),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    @Composable
    private fun SalesMetricBox(
        label: String,
        value: String,
        accent: Color,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.10f))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(vertical = 12.dp, horizontal = 8.dp)
        ) {
            Text(text = label, fontSize = 11.sp, color = colors.textSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent
            )
        }
    }

    @Composable
    private fun QuickActionsSection(
        isArabic: Boolean,
        actions: List<QuickActionItem>,
        onActionClick: (QuickActionItem) -> Unit
    ) {
        val colors = MiraTheme.colors
        val view = LocalView.current

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isArabic) "الوصول السريع" else "Quick Actions",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                val rows = actions.chunked(4)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { rowItems ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowItems.forEach { item ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.backgroundTop.copy(alpha = 0.45f))
                                        .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            onActionClick(item)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(item.accentColor.copy(alpha = 0.16f))
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            tint = item.accentColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (isArabic) item.titleAr else item.titleEn,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            // ملء الفراغ المتبقي في الصف الأخير للمحاذاة المتساوية
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InventoryByKaratCard(
        isArabic: Boolean,
        isHidden: Boolean,
        onToggleHidden: () -> Unit,
        items: List<KaratInventory>
    ) {
        val colors = MiraTheme.colors
        val totalWeight = items.sumOf { it.weight }.coerceAtLeast(0.001)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isArabic) "توزيع المخزون حسب العيار" else "Inventory by Karat",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    IconButton(onClick = onToggleHidden, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (items.isEmpty()) {
                    Text(
                        text = if (isArabic) "لا توجد بيانات مخزون متاحة" else "No inventory data available",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.forEach { inv ->
                            val kColor = getKaratAccentColor(inv.karat)
                            val ratio = (inv.weight / totalWeight).toFloat().coerceIn(0.04f, 1f)

                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = kColor.copy(alpha = 0.18f),
                                            border = BorderStroke(1.dp, kColor.copy(alpha = 0.5f))
                                        ) {
                                            Text(
                                                text = "${inv.karat}K",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = kColor,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isHidden) "•• قطعة" else "${inv.itemCount} قطعة",
                                            fontSize = 12.sp,
                                            color = colors.textSecondary
                                        )
                                    }

                                    Text(
                                        text = if (isHidden) "•• g" else formatWeight(inv.weight),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(5.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(colors.trackBackground)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(ratio)
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .background(kColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScrapGoldSummaryCard(
        isArabic: Boolean,
        isHidden: Boolean,
        onToggleHidden: () -> Unit,
        weight: Double,
        formattedValue: String,
        onOpenBuy: () -> Unit,
        onOpenHistory: () -> Unit,
        onOpenBatches: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val amber = Color(0xFFF59E0B)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, amber.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Recycling,
                            contentDescription = null,
                            tint = amber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "خزنة ذهب الكسر" else "Scrap Gold Vault",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                    IconButton(onClick = onToggleHidden, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = amber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VaultStatChip(
                        label = if (isArabic) "إجمالي الوزن" else "Scrap Weight",
                        value = if (isHidden) "••••••" else String.format(Locale.US, "%.2f g", weight),
                        icon = Icons.Rounded.Scale,
                        modifier = Modifier.weight(1f)
                    )
                    VaultStatChip(
                        label = if (isArabic) "القيمة التقديرية" else "Est. Value",
                        value = if (isHidden) "••••••" else formattedValue,
                        icon = Icons.Rounded.Paid,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onOpenBuy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = amber,
                            contentColor = Color(0xFF060B19)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isArabic) "شراء كسر" else "Buy Scrap",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenHistory,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isArabic) "السجل" else "History",
                            fontSize = 11.sp,
                            color = colors.textPrimary
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenBatches,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isArabic) "الدفعات" else "Batches",
                            fontSize = 11.sp,
                            color = colors.textPrimary
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ScrapAgingAlertCard(
        isArabic: Boolean,
        totalItems: Int,
        totalWeight: Double,
        oldCount: Int,
        oldWeight: Double,
        onOpenReport: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val isWarning = oldCount > 0
        val statusColor = if (isWarning) Color(0xFFEF4444) else Color(0xFF10B981)
        val statusLabel = when {
            isWarning -> if (isArabic) "تحذير تقادم" else "Aging Warning"
            totalItems > 0 -> if (isArabic) "سليم" else "Healthy"
            else -> if (isArabic) "مُصفّى" else "Cleared"
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenReport() }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isArabic) "مؤشر تقادم ذهب الكسر" else "Scrap Aging Monitor",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = statusLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isArabic) {
                        "${String.format(Locale.US, "%.3f g", totalWeight)}  •  $totalItems فاتورة"
                    } else {
                        "${String.format(Locale.US, "%.3f g", totalWeight)}  •  $totalItems invoices"
                    },
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )

                if (isWarning) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(statusColor.copy(alpha = 0.12f))
                            .padding(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) {
                                "$oldCount فاتورة كسر مضى عليها +60 يوماً (${String.format(Locale.US, "%.2fg", oldWeight)})"
                            } else {
                                "$oldCount scrap invoices older than 60 days (${String.format(Locale.US, "%.2fg", oldWeight)})"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  مكونات وضع نقطة البيع (POS Variant)
    // ═══════════════════════════════════════════════════════════════════
    @Composable
    private fun PosVaultSummaryCard(
        isArabic: Boolean,
        isHidden: Boolean,
        onToggleHidden: () -> Unit,
        totalWeight: Double,
        totalValue: Double,
        cashBalance: Double,
        formattedCash: String
    ) {
        val colors = MiraTheme.colors

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.2.dp, colors.goldPrimary.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.PointOfSale,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "ملخص نقطة البيع والصندوق" else "POS & Cash Drawer",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                    IconButton(onClick = onToggleHidden, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isArabic) "رصيد الصندوق / مبيعات اليوم" else "Cash Drawer / Today Sales",
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
                Text(
                    text = if (isHidden) "•••••••" else (if (cashBalance > 0) formattedCash else "0"),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF10B981)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VaultStatChip(
                        label = if (isArabic) "وزن المعروضات" else "Display Weight",
                        value = if (isHidden) "•••••" else String.format(Locale.US, "%,.2f g", totalWeight),
                        icon = Icons.Rounded.Scale,
                        modifier = Modifier.weight(1f)
                    )
                    VaultStatChip(
                        label = if (isArabic) "القيمة التقديرية" else "Est. Value",
                        value = if (isHidden) "•••••••" else String.format(Locale.US, "$%,.2f", totalValue),
                        icon = Icons.Rounded.Diamond,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    @Composable
    private fun PosKaratChipsRow(
        isArabic: Boolean,
        isHidden: Boolean,
        karats: List<KaratInventory>,
        priceManager: MiraPriceManager?
    ) {
        val colors = MiraTheme.colors
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(karats) { inv ->
                val kColor = getKaratAccentColor(inv.karat)
                val gramPrice = priceManager?.getGoldPricePerGram(inv.karat) ?: 0.0
                val estValue = inv.weight * gramPrice
                val formattedVal = if (estValue > 0) {
                    priceManager?.formatPriceDefault(estValue)
                        ?: String.format(Locale.US, "$%,.0f", estValue)
                } else "—"

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                    border = BorderStroke(1.dp, kColor.copy(alpha = 0.5f)),
                    modifier = Modifier.width(142.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${inv.karat}K",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = kColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isHidden) "•••" else String.format(Locale.US, "%,.2f g", inv.weight),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (isHidden) "•••••" else formattedVal,
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  مكون وضع البوابة الأمنية (Gate Security Variant)
    // ═══════════════════════════════════════════════════════════════════
    @Composable
    private fun GateSecurityTelemetryCard(
        isArabic: Boolean,
        scansToday: Int,
        allowedToday: Int,
        deniedToday: Int,
        onOpenGate: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val cyan = Color(0xFF38BDF8)
        val green = Color(0xFF10B981)
        val red = Color(0xFFEF4444)

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.2.dp, cyan.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = cyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "مراقبة البوابات الأمنية اليوم" else "Today's Gate Security Telemetry",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SalesMetricBox(
                        label = if (isArabic) "إجمالي المسح" else "Total Scans",
                        value = scansToday.toString(),
                        accent = cyan,
                        modifier = Modifier.weight(1f)
                    )
                    SalesMetricBox(
                        label = if (isArabic) "مسموح" else "Allowed",
                        value = allowedToday.toString(),
                        accent = green,
                        modifier = Modifier.weight(1f)
                    )
                    SalesMetricBox(
                        label = if (isArabic) "مرفوض" else "Denied",
                        value = deniedToday.toString(),
                        accent = red,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onOpenGate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cyan,
                        contentColor = Color(0xFF060B19)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sensors,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isArabic) "فتح شاشة البوابة الأمنية الحية" else "Open Live Security Gate",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  الرسوم البيانية الأصلية بـ Jetpack Compose Canvas
    // ═══════════════════════════════════════════════════════════════════
    @Composable
    private fun SalesTrendChartCard(
        isArabic: Boolean,
        title: String,
        points: List<SalesPoint>,
        showDualCurve: Boolean
    ) {
        val colors = MiraTheme.colors

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(155.dp)
                ) {
                    if (points.isEmpty()) return@Canvas
                    val maxVal = (points.maxOfOrNull { it.amount } ?: 1.0).coerceAtLeast(1.0)
                    val w = size.width
                    val h = size.height
                    val stepX = if (points.size > 1) w / (points.size - 1) else w / 2f

                    // خطوط شبكة أفقية خفيفة
                    for (g in 1..3) {
                        val gy = h * (g / 4f)
                        drawLine(
                            color = colors.cardBorder.copy(alpha = 0.35f),
                            start = Offset(0f, gy),
                            end = Offset(w, gy),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val primaryCoords = points.mapIndexed { idx, pt ->
                        val x = if (points.size > 1) idx * stepX else w / 2f
                        val y = h - ((pt.amount / maxVal).toFloat() * (h * 0.85f)) - (h * 0.08f)
                        Offset(x, y)
                    }

                    val curvePath = Path().apply {
                        moveTo(primaryCoords.first().x, primaryCoords.first().y)
                        for (i in 0 until primaryCoords.size - 1) {
                            val p0 = primaryCoords[i]
                            val p1 = primaryCoords[i + 1]
                            val midX = (p0.x + p1.x) / 2f
                            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }
                    }

                    val fillPath = Path().apply {
                        addPath(curvePath)
                        lineTo(primaryCoords.last().x, h)
                        lineTo(primaryCoords.first().x, h)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(colors.goldPrimary.copy(alpha = 0.28f), Color.Transparent)
                        )
                    )

                    drawPath(
                        path = curvePath,
                        color = colors.goldPrimary,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    if (showDualCurve && primaryCoords.size > 1) {
                        val estCoords = points.mapIndexed { idx, pt ->
                            val x = idx * stepX
                            val y = h - (((pt.amount * 0.92) / maxVal).toFloat() * (h * 0.85f)) - (h * 0.08f)
                            Offset(x, y)
                        }
                        val estPath = Path().apply {
                            moveTo(estCoords.first().x, estCoords.first().y)
                            for (i in 0 until estCoords.size - 1) {
                                val p0 = estCoords[i]
                                val p1 = estCoords[i + 1]
                                val midX = (p0.x + p1.x) / 2f
                                cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                            }
                        }
                        drawPath(
                            path = estPath,
                            color = Color(0xFF4ADE80),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    primaryCoords.forEach { pt ->
                        drawCircle(color = colors.goldPrimary, radius = 4.dp.toPx(), center = pt)
                        drawCircle(color = colors.cardSurface, radius = 2.dp.toPx(), center = pt)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    points.forEach { pt ->
                        Text(
                            text = pt.label,
                            fontSize = 10.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun KaratDistributionDonutCard(
        isArabic: Boolean,
        slices: List<KaratSalesSlice>
    ) {
        val colors = MiraTheme.colors
        val palette = listOf(
            Color(0xFFD4AF37),
            Color(0xFF10B981),
            Color(0xFF38BDF8),
            Color(0xFFF59E0B),
            Color(0xFFA855F7)
        )
        val total = slices.sumOf { it.amount }.coerceAtLeast(0.001)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isArabic) "نسبة المبيعات حسب العيار" else "Sales Share by Karat",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Canvas(modifier = Modifier.size(118.dp)) {
                        var startAngle = -90f
                        val strokeWidth = 22.dp.toPx()
                        slices.forEachIndexed { index, slice ->
                            val sweep = ((slice.amount / total) * 360.0).toFloat()
                            drawArc(
                                color = palette[index % palette.size],
                                startAngle = startAngle,
                                sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                                useCenter = false,
                                topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                            )
                            startAngle += sweep
                        }
                    }

                    Spacer(modifier = Modifier.width(18.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        slices.forEachIndexed { index, slice ->
                            val pct = (slice.amount / total) * 100.0
                            val sliceColor = palette[index % palette.size]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(sliceColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = slice.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                }
                                Text(
                                    text = String.format(Locale.US, "%.1f%%", pct),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  سجل العمليات الموحد (Recent Activity Timeline)
    // ═══════════════════════════════════════════════════════════════════
    @Composable
    private fun RecentActivitySection(
        isArabic: Boolean,
        activities: List<JSONObject>,
        maskAmounts: Boolean,
        onViewAll: () -> Unit,
        onItemClick: (JSONObject) -> Unit
    ) {
        val colors = MiraTheme.colors

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isArabic) "آخر العمليات والنشاط" else "Recent Activity",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )

                    Text(
                        text = if (isArabic) "عرض الكل ←" else "View All →",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.goldPrimary,
                        modifier = Modifier.clickable { onViewAll() }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (activities.isEmpty()) {
                    Text(
                        text = if (isArabic) "لا توجد عمليات مسجلة حديثاً" else "No recent activities",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        activities.forEach { item ->
                            ActivityTimelineRow(
                                isArabic = isArabic,
                                item = item,
                                maskAmount = maskAmounts,
                                onClick = { onItemClick(item) }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActivityTimelineRow(
        isArabic: Boolean,
        item: JSONObject,
        maskAmount: Boolean,
        onClick: () -> Unit
    ) {
        val colors = MiraTheme.colors
        val iconEmoji = item.optString("icon", "⚙️")
        val title = if (isArabic) {
            item.optString("title_ar", item.optString("title", item.optString("action", "عملية")))
        } else {
            item.optString("title_en", item.optString("title", item.optString("action", "Activity")))
        }
        val details = if (isArabic) item.optString("details_ar", "") else item.optString("details_en", "")
        val user = item.optString("user", "")
        val timestamp = extractTime(item.optString("timestamp", ""))
        val amount = item.optDouble("amount", 0.0)
        val currency = item.optString("currency", "USD")
        val accuracy = item.optDouble("accuracy", -1.0)
        val status = item.optString("status", "neutral")

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.backgroundTop.copy(alpha = 0.45f))
                .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                .clickable { onClick() }
                .padding(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.goldPrimary.copy(alpha = 0.14f))
            ) {
                Text(text = iconEmoji, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (user.isNotEmpty() && user != "—") {
                        Text(
                            text = "👤 $user",
                            fontSize = 10.sp,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (timestamp.isNotEmpty()) {
                        Text(
                            text = timestamp,
                            fontSize = 10.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (amount != 0.0) {
                    val isNegative = amount < 0 || status == "refund"
                    val amtColor = if (isNegative) Color(0xFFEF4444) else Color(0xFF10B981)
                    Text(
                        text = if (maskAmount) "•••••" else String.format(
                            Locale.US,
                            "%s%,.2f %s",
                            if (isNegative) "-" else "",
                            kotlin.math.abs(amount),
                            currency
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (maskAmount) colors.textSecondary else amtColor
                    )
                } else if (accuracy >= 0) {
                    Text(
                        text = String.format(Locale.US, "🎯 %.1f%%", accuracy),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.goldPrimary
                    )
                }

                if (status == "danger" || status == "warning") {
                    Spacer(modifier = Modifier.height(3.dp))
                    val chipColor = if (status == "danger") Color(0xFFEF4444) else Color(0xFFF59E0B)
                    Text(
                        text = if (status == "danger") (if (isArabic) "حرج" else "Critical")
                        else (if (isArabic) "تحذير" else "Warning"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = chipColor
                    )
                }
            }
        }
    }
}
