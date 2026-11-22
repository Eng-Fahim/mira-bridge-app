package com.example.uhf.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ActivityDetailsBottomSheet
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.tools.RecentActivityCache
import com.example.uhf.tools.SecureSessionManager
import com.example.uhf.ui.dashboard.*
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MiraDashboardFragment : Fragment() {

    companion object {
        private const val TAG = "MiraDashboardFragment"
        private const val DASHBOARD_CACHE_PREFS = "mira_dashboard_summary_cache_v2"
        private const val PREFS_RESOLVED_ALERTS = "mira_resolved_alerts_v1"
    }

    private data class BranchChipOption(
        val id: Int,
        val name: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var localExecutor: ExecutorService? = null
    private val isSyncingAll = AtomicBoolean(false)

    @Volatile private var isDestroyed = false
    private var isArabic = true
    private var currentBranchId = 1
    private var currentUserId = 0
    private var lastFullRefreshMs = 0L
    private var isReceiverRegistered = false
    private var dashboardBroadcastReceiver: BroadcastReceiver? = null

    private var apiClient: MiraApiClient? = null
    private var sessionManager: SecureSessionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var activityCache: RecentActivityCache? = null
    private var permissionManager: PermissionManager? = null

    // حالات واجهة المستخدم التفاعلية
    private var orgTitleState by mutableStateOf("MIRA JEWELRY")
    private var userNameState by mutableStateOf("")
    private var userRoleState by mutableStateOf("")
    private var isManagerState by mutableStateOf(false)
    private var isOnlineState by mutableStateOf(true)
    private var isRefreshingState by mutableStateOf(false)
    private var maskAmountsState by mutableStateOf(false)

    // الفروع (تُعرض للمدراء فقط)
    private var branchesListState by mutableStateOf<List<BranchChipOption>>(emptyList())
    private var selectedBranchIdState by mutableIntStateOf(1)

    // التنبيهات
    private var alertsCountState by mutableIntStateOf(0)
    private var alertsMessageState by mutableStateOf("")
    private var rawAlertsJsonState by mutableStateOf<String?>(null)

    // أسعار الذهب
    private var price24kLocalState by mutableDoubleStateOf(0.0)
    private var price21kLocalState by mutableDoubleStateOf(0.0)
    private var price18kLocalState by mutableDoubleStateOf(0.0)
    private var price24kUsdState by mutableDoubleStateOf(0.0)
    private var exchangeRateState by mutableDoubleStateOf(0.0)
    private var localCurrencyState by mutableStateOf("YER")
    private var pricesLastUpdatedState by mutableStateOf("")

    // مؤشرات المبيعات اليومية والخزينة
    private var todaySalesLocalState by mutableDoubleStateOf(0.0)
    private var todaySalesUsdState by mutableDoubleStateOf(0.0)
    private var todayInvoicesCountState by mutableIntStateOf(0)
    private var todaySoldWeightState by mutableDoubleStateOf(0.0)

    private var totalVaultPiecesState by mutableIntStateOf(0)
    private var totalVaultWeightState by mutableDoubleStateOf(0.0)
    private var pure24kWeightState by mutableDoubleStateOf(0.0)

    // حالات الرسوم البيانية (للبائع والمدير)
    private var selectedChartDaysState by mutableIntStateOf(7)
    private var chartPointsState by mutableStateOf<List<ChartPeriodPoint>>(emptyList())
    private var staffTargetAmountState by mutableDoubleStateOf(0.0)
    private var karatDistributionState by mutableStateOf<List<KaratDistributionItem>>(emptyList())
    private var operationsBreakdownState by mutableStateOf<List<OperationSummaryMetric>>(emptyList())

    // السجل الموحد لآخر العمليات
    private var recentActivitiesState by mutableStateOf<List<JSONObject>>(emptyList())

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
                        DashboardScreenContent()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isDestroyed = false

        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            Locale.getDefault().language == "ar"
        view.layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val ctx = requireContext()
        apiClient = MiraApiClient.getInstance(ctx)
        sessionManager = SecureSessionManager.getInstance(ctx)
        branchesManager = MiraBranchesManager.getInstance(ctx)
        activityCache = RecentActivityCache(ctx)
        permissionManager = PermissionManager.getInstance(ctx)

        initSessionAndBranchState()
        loadOfflineData()
        refreshAllDashboardData(forceNetwork = true)
    }

    override fun onResume() {
        super.onResume()
        registerDashboardReceiver()
        (activity as? UHFMainActivity)?.setBottomNavVisible(true)
        activity?.findViewById<View>(R.id.appBarLayout)?.visibility = View.VISIBLE

        isOnlineState = isNetworkAvailable()
        val latestBranch = resolveActiveBranchId()
        val latestUser = readIntProp(sessionManager, "userId", "id")

        recalculateUnresolvedAlertsBadge()

        if (latestBranch != currentBranchId || latestUser != currentUserId) {
            initSessionAndBranchState()
            recentActivitiesState = emptyList()
            operationsBreakdownState = emptyList()
            loadOfflineData()
            refreshAllDashboardData(forceNetwork = true)
        } else if (System.currentTimeMillis() - lastFullRefreshMs > 1500L) {
            // تحديث كامل للخزينة والتنبيهات والمبيعات والرسوم عند العودة من الجرد أو نقطة البيع
            refreshAllDashboardData(forceNetwork = true)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterDashboardReceiver()
    }

    override fun onDestroyView() {
        isDestroyed = true
        unregisterDashboardReceiver()
        mainHandler.removeCallbacksAndMessages(null)
        localExecutor?.let { exec ->
            if (!exec.isShutdown) {
                exec.shutdownNow()
            }
        }
        localExecutor = null
        super.onDestroyView()
    }

    private fun registerDashboardReceiver() {
        try {
            val ctx = context ?: return
            if (isReceiverRegistered) return

            dashboardBroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || isDestroyed || !isAdded) return
                    val action = intent.action
                    if (action == "com.example.uhf.REFRESH_DASHBOARD" ||
                        action == "com.example.uhf.REFRESH_INVENTORY" ||
                        action == "com.example.uhf.REFRESH_SALES"
                    ) {
                        mainHandler.post {
                            if (!isDestroyed && isAdded) {
                                refreshAllDashboardData(forceNetwork = true)
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction("com.example.uhf.REFRESH_DASHBOARD")
                addAction("com.example.uhf.REFRESH_INVENTORY")
                addAction("com.example.uhf.REFRESH_SALES")
            }

            ContextCompat.registerReceiver(
                ctx,
                dashboardBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerDashboardReceiver error: ${e.message}")
        }
    }

    private fun unregisterDashboardReceiver() {
        try {
            val ctx = context
            if (ctx != null && isReceiverRegistered && dashboardBroadcastReceiver != null) {
                ctx.unregisterReceiver(dashboardBroadcastReceiver)
                isReceiverRegistered = false
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureExecutor(): Boolean {
        if (isDestroyed || !isAdded) return false
        val current = localExecutor
        if (current == null || current.isShutdown || current.isTerminated) {
            localExecutor = Executors.newFixedThreadPool(3)
        }
        return true
    }

    private fun isSessionValid(): Boolean {
        val sm = sessionManager ?: return false
        return try {
            sm.isLoggedIn() || readIntProp(sm, "userId") > 0
        } catch (_: Exception) {
            true
        }
    }

    private fun initSessionAndBranchState() {
        val sm = sessionManager
        val bm = branchesManager

        currentUserId = readIntProp(sm, "userId", "id")
        userNameState = readStringProp(sm, "username", "fullName", "name")
        userRoleState = readStringProp(sm, "role", "userRole", "position").lowercase(Locale.ROOT)
        isManagerState = resolveIsManagerOrAdmin(sm, userRoleState) ||
            (permissionManager?.isManager == true) ||
            (permissionManager?.canViewAllBranches() == true)

        val orgName = readStringProp(sm, "orgName", "organizationName", "companyName")
            .ifBlank { if (isArabic) "ميرا للمجوهرات" else "MIRA JEWELRY" }
        orgTitleState = orgName

        currentBranchId = resolveActiveBranchId()
        selectedBranchIdState = currentBranchId

        if (isManagerState) {
            loadBranchesList(bm)
        } else {
            branchesListState = emptyList()
        }
    }

    private fun resolveIsManagerOrAdmin(sm: SecureSessionManager?, roleRaw: String): Boolean {
        val managerKeywords = setOf(
            "admin", "super_admin", "superadmin", "owner", "manager",
            "branch_manager", "general_manager", "supervisor", "director",
            "accountant", "auditor", "مدير", "مشرف", "مالك", "محاسب"
        )
        if (roleRaw.isNotBlank() && managerKeywords.any { roleRaw.contains(it) }) {
            return true
        }
        if (sm != null) {
            val boolFlags = listOf("isAdmin", "isManager", "isOwner", "isSupervisor", "canViewAllSales")
            for (flag in boolFlags) {
                try {
                    val m = sm.javaClass.methods.firstOrNull {
                        it.name.equals(flag, ignoreCase = true) && it.parameterCount == 0
                    }
                    val res = m?.invoke(sm)
                    if (res is Boolean && res) return true
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun resolveActiveBranchId(): Int {
        val bmActive = try {
            branchesManager?.activeBranchId ?: 0
        } catch (_: Exception) {
            0
        }
        if (bmActive > 0) return bmActive

        val smBranch = readIntProp(sessionManager, "branchId")
        return if (smBranch > 0) smBranch else 1
    }

    private fun loadBranchesList(bm: MiraBranchesManager?) {
        if (bm == null) return
        try {
            val method = bm.javaClass.methods.firstOrNull {
                it.name in setOf("getBranches", "getAllBranches", "getCachedBranches") && it.parameterCount == 0
            }
            val raw = method?.invoke(bm) ?: return
            val parsed = mutableListOf<BranchChipOption>()
            when (raw) {
                is JSONArray -> {
                    for (i in 0 until raw.length()) {
                        val obj = raw.optJSONObject(i) ?: continue
                        val id = obj.optInt("id", 0)
                        val name = if (isArabic) {
                            obj.optString("name_ar", obj.optString("name", "فرع #$id"))
                        } else {
                            obj.optString("name_en", obj.optString("name", "Branch #$id"))
                        }
                        if (id > 0) parsed.add(BranchChipOption(id, name))
                    }
                }
                is List<*> -> {
                    for (entry in raw) {
                        if (entry is JSONObject) {
                            val id = entry.optInt("id", 0)
                            val name = entry.optString("name", "Branch #$id")
                            if (id > 0) parsed.add(BranchChipOption(id, name))
                        } else if (entry != null) {
                            val id = readIntProp(entry, "id", "branchId")
                            val name = readStringProp(entry, "name", "branchName", "title")
                            if (id > 0 && name.isNotBlank()) parsed.add(BranchChipOption(id, name))
                        }
                    }
                }
            }
            if (parsed.isNotEmpty()) {
                branchesListState = parsed
            }
        } catch (e: Exception) {
            Log.d(TAG, "loadBranchesList optional skip: ${e.message}")
        }
    }

    private fun switchBranch(branchId: Int) {
        if (!isManagerState) return
        if (branchId <= 0 || branchId == currentBranchId) return
        currentBranchId = branchId
        selectedBranchIdState = branchId
        try {
            val setter = branchesManager?.javaClass.methods.firstOrNull {
                it.name in setOf("setActiveBranchId", "selectBranch") && it.parameterCount == 1
            }
            setter?.invoke(branchesManager, branchId)
        } catch (_: Exception) {
        }

        recentActivitiesState = emptyList()
        operationsBreakdownState = emptyList()
        loadOfflineData()
        refreshAllDashboardData(forceNetwork = true)
    }

    private fun summaryCacheKey(branchId: Int): String {
        return if (isManagerState) "summary_b${branchId}_all" else "summary_b${branchId}_u${currentUserId}"
    }

    private fun loadOfflineData() {
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(DASHBOARD_CACHE_PREFS, Context.MODE_PRIVATE)
            val savedSummary = prefs.getString(summaryCacheKey(currentBranchId), null)
            if (!savedSummary.isNullOrBlank()) {
                applyDashboardSummaryJson(JSONObject(savedSummary))
            }
            val savedPrices = prefs.getString("gold_prices_json", null)
            if (!savedPrices.isNullOrBlank()) {
                applyGoldPricesJson(JSONObject(savedPrices))
            }
            val savedCharts = prefs.getString("charts_b${currentBranchId}_d${selectedChartDaysState}", null)
            if (!savedCharts.isNullOrBlank()) {
                applyChartsJson(JSONObject(savedCharts))
            }

            val cacheUserKey = if (isManagerState) 0 else currentUserId
            val cachedActs = activityCache?.load(currentBranchId, cacheUserKey)
            if (cachedActs != null && cachedActs.length() > 0 && recentActivitiesState.isEmpty()) {
                val cachedList = mutableListOf<JSONObject>()
                for (i in 0 until cachedActs.length()) {
                    cachedActs.optJSONObject(i)?.let { cachedList.add(it) }
                }
                recentActivitiesState = cachedList
                recomputeTodaySalesFromOperations(cachedList, forceOverride = !isManagerState || todaySalesLocalState <= 0.0)
                if (chartPointsState.isEmpty()) {
                    buildFallbackChartsFromOperations(cachedList, selectedChartDaysState)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadOfflineData error: ${e.message}")
        }
    }

    private fun refreshAllDashboardData(forceNetwork: Boolean) {
        isOnlineState = isNetworkAvailable()
        if (!isOnlineState && !forceNetwork) return
        if (!ensureExecutor() || !isSessionValid()) return
        if (!isSyncingAll.compareAndSet(false, true)) return

        lastFullRefreshMs = System.currentTimeMillis()
        isRefreshingState = true
        val branchSnapshot = currentBranchId
        val managerSnapshot = isManagerState
        val userSnapshot = currentUserId
        val daysSnapshot = selectedChartDaysState

        fetchRecentActivity()
        fetchDashboardCharts(daysSnapshot)

        localExecutor?.execute {
            try {
                val client = apiClient
                val summaryResp = client?.getDashboardSummarySmart(branchSnapshot)
                val summaryData: JSONObject? = summaryResp?.data
                if (summaryResp != null && summaryResp.isSuccess && summaryData != null) {
                    context?.getSharedPreferences(DASHBOARD_CACHE_PREFS, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(summaryCacheKey(branchSnapshot), summaryData.toString())
                        ?.apply()

                    mainHandler.post {
                        if (!isDestroyed && isAdded && currentBranchId == branchSnapshot) {
                            applyDashboardSummaryJson(summaryData)
                            if (!managerSnapshot && currentUserId == userSnapshot) {
                                recomputeTodaySalesFromOperations(recentActivitiesState, forceOverride = true)
                            }
                        }
                    }
                }

                val pricesResp = client?.getGoldPricesSmart(branchSnapshot)
                    ?: invokeApiMethod("getCurrentGoldRates")
                    ?: invokeApiMethod("getGoldRates")
                val pricesData: JSONObject? = pricesResp?.data

                if (pricesResp != null && pricesResp.isSuccess && pricesData != null) {
                    context?.getSharedPreferences(DASHBOARD_CACHE_PREFS, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString("gold_prices_json", pricesData.toString())
                        ?.apply()

                    mainHandler.post {
                        if (!isDestroyed && isAdded) {
                            applyGoldPricesJson(pricesData)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshAllDashboardData error: ${e.message}")
            } finally {
                isSyncingAll.set(false)
                mainHandler.post {
                    if (!isDestroyed && isAdded) {
                        isRefreshingState = false
                    }
                }
            }
        }
    }

    private fun fetchDashboardCharts(days: Int) {
        if (apiClient == null || !isAdded || isDestroyed || !isSessionValid() || !ensureExecutor()) return

        val branchSnapshot = currentBranchId
        val managerSnapshot = isManagerState
        val userSnapshot = currentUserId

        localExecutor?.execute {
            try {
                val endpoint = if (managerSnapshot) {
                    "dashboard/app-charts?branch_id=$branchSnapshot&days=$days"
                } else {
                    "dashboard/app-charts?branch_id=$branchSnapshot&user_id=$userSnapshot&days=$days"
                }
                val resp = invokeApiMethod("get", endpoint)
                    ?: invokeApiMethod("getDashboardCharts", branchSnapshot, days)
                    ?: invokeApiMethod("getAccounting", "reports/sales/daily?branch_id=$branchSnapshot&days=$days")

                val chartJson: JSONObject? = resp?.data
                if (resp != null && resp.isSuccess && chartJson != null) {
                    context?.getSharedPreferences(DASHBOARD_CACHE_PREFS, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString("charts_b${branchSnapshot}_d$days", chartJson.toString())
                        ?.apply()

                    mainHandler.post {
                        if (!isDestroyed && isAdded && currentBranchId == branchSnapshot) {
                            applyChartsJson(chartJson)
                        }
                    }
                } else {
                    mainHandler.post {
                        if (!isDestroyed && isAdded) {
                            buildFallbackChartsFromOperations(recentActivitiesState, days)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchDashboardCharts fallback to local ops: ${e.message}")
                mainHandler.post {
                    if (!isDestroyed && isAdded) {
                        buildFallbackChartsFromOperations(recentActivitiesState, days)
                    }
                }
            }
        }
    }

    private fun resolveEffectiveExchangeRate(currency: String = localCurrencyState): Double {
        if (currency.equals("USD", ignoreCase = true)) return 1.0
        if (exchangeRateState > 0.0) return exchangeRateState

        try {
            val ctx = context
            if (ctx != null) {
                val pm = MiraPriceManager.getInstance(ctx)
                val methodWithArg = pm.javaClass.methods.firstOrNull {
                    it.name in setOf("getExchangeRate", "getRateForCurrency") && it.parameterCount == 1
                }
                val r1 = methodWithArg?.invoke(pm, currency)
                if (r1 is Number && r1.toDouble() > 0.0) return r1.toDouble()

                val noArgMethod = pm.javaClass.methods.firstOrNull {
                    it.name in setOf("getExchangeRate", "getUsdRate", "getUsdToYerRate") && it.parameterCount == 0
                }
                val r2 = noArgMethod?.invoke(pm)
                if (r2 is Number && r2.toDouble() > 0.0) return r2.toDouble()
            }
        } catch (_: Exception) {
        }

        return if (currency.equals("YER", ignoreCase = true)) 530.0 else 1.0
    }

    private fun convertUsdToActiveCurrency(amountUsd: Double): Double {
        if (amountUsd <= 0.0) return 0.0
        if (localCurrencyState.equals("USD", ignoreCase = true)) return amountUsd
        return amountUsd * resolveEffectiveExchangeRate(localCurrencyState)
    }

    private fun applyChartsJson(root: JSONObject) {
        val data = root.optJSONObject("data") ?: root

        val seriesArr = data.optJSONArray("series")
            ?: data.optJSONArray("daily")
            ?: data.optJSONArray("chart_data")
            ?: data.optJSONArray("sales_trend")

        val parsedPoints = mutableListOf<ChartPeriodPoint>()
        if (seriesArr != null) {
            for (i in 0 until seriesArr.length()) {
                val item = seriesArr.optJSONObject(i) ?: continue
                val label = item.optString("label", item.optString("date", item.optString("day", "#${i + 1}")))
                var sales = parseFlexibleDouble(item, "sales")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(item, "total_local")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(item, "amount")
                val salesUsd = parseFlexibleDouble(item, "sales_usd")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(item, "total_usd")
                if (sales <= 0.0 && salesUsd > 0.0) {
                    sales = convertUsdToActiveCurrency(salesUsd)
                }

                var scrap = parseFlexibleDouble(item, "scrap")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(item, "scrap_total")
                val scrapUsd = parseFlexibleDouble(item, "scrap_usd")
                if (scrap <= 0.0 && scrapUsd > 0.0) {
                    scrap = convertUsdToActiveCurrency(scrapUsd)
                }

                val refund = parseFlexibleDouble(item, "refunds")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(item, "returns")
                val count = item.optInt("count", item.optInt("invoices", 0))
                val weight = parseFlexibleDouble(item, "weight")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(item, "sold_weight")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(item, "total_weight")
                parsedPoints.add(ChartPeriodPoint(label, sales, scrap, refund, count, weight))
            }
        }

        if (parsedPoints.isNotEmpty() && parsedPoints.sumOf { it.salesAmount } > 0.0) {
            chartPointsState = parsedPoints
        } else {
            buildFallbackChartsFromOperations(recentActivitiesState, selectedChartDaysState)
        }

        val target = parseFlexibleDouble(data, "staff_target")
            .takeIf { it > 0.0 } ?: parseFlexibleDouble(data, "target_amount")
        if (target > 0.0) staffTargetAmountState = target

        val karatArr = data.optJSONArray("by_karat") ?: data.optJSONArray("karat_distribution")
        if (karatArr != null && karatArr.length() > 0) {
            val karats = mutableListOf<KaratDistributionItem>()
            val palette = listOf(Color(0xFFD4AF37), Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFFA855F7))
            for (i in 0 until karatArr.length()) {
                val kObj = karatArr.optJSONObject(i) ?: continue
                val kName = kObj.optString("karat", kObj.optString("gold_karat", "21K"))
                val w = parseFlexibleDouble(kObj, "weight").takeIf { it > 0.0 } ?: parseFlexibleDouble(kObj, "total_weight")
                val amt = parseFlexibleDouble(kObj, "amount").takeIf { it > 0.0 } ?: parseFlexibleDouble(kObj, "total_local")
                if (w > 0.0 || amt > 0.0) {
                    karats.add(KaratDistributionItem(kName, w, amt, palette[i % palette.size]))
                }
            }
            if (karats.isNotEmpty()) karatDistributionState = karats
        }

        val opsObj = data.optJSONObject("operations_summary") ?: data.optJSONObject("counts")
        if (opsObj != null) {
            operationsBreakdownState = listOf(
                OperationSummaryMetric("المبيعات (POS)", "POS Sales", opsObj.optInt("sales", todayInvoicesCountState), Color(0xFF10B981)),
                OperationSummaryMetric("شراء كسر الذهب", "Scrap Gold", opsObj.optInt("scrap", 0), Color(0xFFF59E0B)),
                OperationSummaryMetric("التحويلات المخزنية", "Transfers", opsObj.optInt("transfers", 0), Color(0xFFA855F7)),
                OperationSummaryMetric("جلسات الجرد", "Inventory Scans", opsObj.optInt("inventory", 0), Color(0xFF06B6D4))
            )
        }
    }

    private fun buildFallbackChartsFromOperations(ops: List<JSONObject>, days: Int) {
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfLabel = SimpleDateFormat("MM/dd", Locale.US)
        val cal = Calendar.getInstance()

        val buckets = linkedMapOf<String, MutableList<JSONObject>>()
        val labelsMap = mutableMapOf<String, String>()

        val displayDays = days.coerceIn(7, 30)
        cal.add(Calendar.DAY_OF_YEAR, -(displayDays - 1))
        for (i in 0 until displayDays) {
            val d = cal.time
            val key = sdfKey.format(d)
            buckets[key] = mutableListOf()
            labelsMap[key] = sdfLabel.format(d)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val todayKey = sdfKey.format(Date())
        val karatWeights = mutableMapOf("24K" to 0.0, "21K" to 0.0, "18K" to 0.0)
        var salesCnt = 0
        var scrapCnt = 0
        var transferCnt = 0
        var invCnt = 0

        for (op in ops) {
            val type = op.optString("type", op.optString("operation_type", "")).lowercase(Locale.ROOT)
            val parsedDate = parseOperationDate(op)
            val dayKey = when {
                parsedDate != null -> sdfKey.format(parsedDate)
                isOperationToday(op, sdfKey, todayKey) -> todayKey
                else -> todayKey
            }

            if (buckets.containsKey(dayKey)) {
                buckets[dayKey]?.add(op)
            }

            when {
                isSaleOperation(op) -> salesCnt++
                type.contains("scrap") -> scrapCnt++
                type.contains("transfer") -> transferCnt++
                type.contains("inv") || type.contains("scan") || type.contains("audit") || type.contains("stock") -> invCnt++
            }

            val k = op.optString("karat", op.optString("gold_karat", "")).uppercase(Locale.US)
            val w = extractOperationWeight(op)
            if (w > 0.0) {
                when {
                    k.contains("24") -> karatWeights["24K"] = (karatWeights["24K"] ?: 0.0) + w
                    k.contains("21") -> karatWeights["21K"] = (karatWeights["21K"] ?: 0.0) + w
                    k.contains("18") -> karatWeights["18K"] = (karatWeights["18K"] ?: 0.0) + w
                }
            }
        }

        val points = buckets.map { (key, dayOps) ->
            var sAmt = 0.0
            var scAmt = 0.0
            var rfAmt = 0.0
            var sCount = 0
            var sWeight = 0.0

            for (op in dayOps) {
                val t = op.optString("type", op.optString("operation_type", "")).lowercase(Locale.ROOT)
                val amt = extractOperationAmountLocal(op)
                val w = extractOperationWeight(op)
                when {
                    isSaleOperation(op) -> {
                        sAmt += amt
                        sCount++
                        sWeight += w
                    }
                    t.contains("scrap") -> scAmt += amt
                    t.contains("refund") || t.contains("return") -> rfAmt += amt
                }
            }

            if (key == todayKey) {
                if (sCount == 0 && todayInvoicesCountState > 0) {
                    sCount = todayInvoicesCountState
                }
                if (sAmt <= 0.0) {
                    sAmt = when {
                        todaySalesLocalState > 0.0 -> todaySalesLocalState
                        todaySalesUsdState > 0.0 -> convertUsdToActiveCurrency(todaySalesUsdState)
                        else -> 0.0
                    }
                }
                if (sWeight <= 0.0 && todaySoldWeightState > 0.0) {
                    sWeight = todaySoldWeightState
                }
            }

            ChartPeriodPoint(
                label = labelsMap[key] ?: key,
                salesAmount = sAmt,
                scrapAmount = scAmt,
                refundAmount = rfAmt,
                invoicesCount = sCount,
                weightGrams = sWeight
            )
        }

        chartPointsState = points

        if (isManagerState) {
            if (karatDistributionState.isEmpty() && karatWeights.values.sum() > 0.0) {
                karatDistributionState = listOf(
                    KaratDistributionItem("24K", karatWeights["24K"] ?: 0.0, 0.0, Color(0xFFD4AF37)),
                    KaratDistributionItem("21K", karatWeights["21K"] ?: 0.0, 0.0, Color(0xFF10B981)),
                    KaratDistributionItem("18K", karatWeights["18K"] ?: 0.0, 0.0, Color(0xFF06B6D4))
                ).filter { it.weightGrams > 0.0 }
            }
            if (ops.isNotEmpty() || operationsBreakdownState.isEmpty()) {
                operationsBreakdownState = listOf(
                    OperationSummaryMetric("المبيعات (POS)", "POS Sales", salesCnt.coerceAtLeast(todayInvoicesCountState), Color(0xFF10B981)),
                    OperationSummaryMetric("شراء كسر الذهب", "Scrap Gold", scrapCnt, Color(0xFFF59E0B)),
                    OperationSummaryMetric("التحويلات المخزنية", "Transfers", transferCnt, Color(0xFFA855F7)),
                    OperationSummaryMetric("جلسات الجرد", "Inventory Scans", invCnt, Color(0xFF06B6D4))
                )
            }
        }
    }

    private fun parseFlexibleDouble(obj: JSONObject?, key: String): Double {
        if (obj == null || !obj.has(key)) return 0.0
        val raw = obj.opt(key) ?: return 0.0
        if (raw is Number) {
            val d = raw.toDouble()
            return if (!d.isNaN() && d > 0.0) d else 0.0
        }
        val str = raw.toString().trim()
        if (str.isEmpty() || str == "null") return 0.0
        val cleaned = str.replace(",", "").replace(Regex("[^0-9.\\-]"), "")
        return cleaned.toDoubleOrNull()?.takeIf { !it.isNaN() && it > 0.0 } ?: 0.0
    }

    private fun extractOperationAmountUsd(op: JSONObject): Double {
        val nested = op.optJSONObject("data")
            ?: op.optJSONObject("sale")
            ?: op.optJSONObject("details")
            ?: op.optJSONObject("summary")

        val candidates = arrayOf("final_amount_usd", "total_amount_usd", "total_usd", "amount_usd", "subtotal_usd")
        for (k in candidates) {
            val v = parseFlexibleDouble(op, k)
            if (v > 0.0) return v
            val nv = parseFlexibleDouble(nested, k)
            if (nv > 0.0) return nv
        }
        return 0.0
    }

    private fun extractOperationAmountLocal(op: JSONObject): Double {
        val nested = op.optJSONObject("data")
            ?: op.optJSONObject("sale")
            ?: op.optJSONObject("details")
            ?: op.optJSONObject("summary")

        val activeCurrency = localCurrencyState.ifBlank { "YER" }
        val yerRate = resolveEffectiveExchangeRate("YER")

        val localKeys = arrayOf(
            "total_yer", "amount_yer", "final_amount_yer", "local_amount",
            "final_amount", "total_amount", "amount", "total", "price"
        )
        val usdKeys = arrayOf(
            "total_usd", "amount_usd", "final_amount_usd", "total_amount_usd",
            "subtotal_usd", "price_usd"
        )

        fun readFirstPositive(obj: JSONObject?, keys: Array<String>): Double {
            if (obj == null) return 0.0
            for (k in keys) {
                val v = parseFlexibleDouble(obj, k)
                if (v > 0.0) return v
            }
            return 0.0
        }

        val usdVal = readFirstPositive(op, usdKeys).takeIf { it > 0.0 }
            ?: readFirstPositive(nested, usdKeys)

        val rawLocalVal = readFirstPositive(op, localKeys).takeIf { it > 0.0 }
            ?: readFirstPositive(nested, localKeys)

        val opCurrency = op.optString("currency", op.optString("currency_code", "")).uppercase(Locale.US)
        val isRawInUsd = opCurrency == "USD" || (usdVal > 0.0 && kotlin.math.abs(rawLocalVal - usdVal) < 0.01)

        if (activeCurrency.equals("USD", ignoreCase = true)) {
            if (usdVal > 0.0) return usdVal
            if (rawLocalVal > 0.0) return if (isRawInUsd) rawLocalVal else (rawLocalVal / yerRate)
            return 0.0
        }

        val targetRate = resolveEffectiveExchangeRate(activeCurrency)
        if (rawLocalVal > 0.0 && !isRawInUsd) return rawLocalVal
        if (usdVal > 0.0) return usdVal * targetRate
        if (rawLocalVal > 0.0) return rawLocalVal * targetRate
        return 0.0
    }

    private fun extractOperationWeight(op: JSONObject): Double {
        val nested = op.optJSONObject("data")
            ?: op.optJSONObject("sale")
            ?: op.optJSONObject("details")
            ?: op.optJSONObject("summary")
            ?: op.optJSONObject("meta")

        val weightKeys = arrayOf(
            "weight", "total_weight", "total_weight_grams", "sold_weight",
            "gross_weight", "gross_weight_grams", "net_weight", "net_weight_grams",
            "weight_grams", "gold_weight", "gold_weight_grams", "grams", "total_grams", "item_weight"
        )

        for (k in weightKeys) {
            val v = parseFlexibleDouble(op, k)
            if (v > 0.0) return v
            val nv = parseFlexibleDouble(nested, k)
            if (nv > 0.0) return nv
        }

        val items = op.optJSONArray("items") ?: nested?.optJSONArray("items")
        if (items != null && items.length() > 0) {
            var sum = 0.0
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                for (k in weightKeys) {
                    val w = parseFlexibleDouble(it, k)
                    if (w > 0.0) {
                        sum += w
                        break
                    }
                }
            }
            if (sum > 0.0) return sum
        }

        val textFields = listOf(
            op.optString("subtitle", ""),
            op.optString("description", ""),
            op.optString("details_text", ""),
            op.optString("summary_text", ""),
            nested?.optString("subtitle", "") ?: "",
            nested?.optString("description", "") ?: ""
        )
        val weightRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:g|gm|gram|grams|جم|جرام)(?:\\b|$)", RegexOption.IGNORE_CASE)
        for (txt in textFields) {
            if (txt.isBlank()) continue
            val match = weightRegex.find(txt)
            val parsed = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (parsed != null && parsed > 0.0 && parsed < 100_000.0) {
                return parsed
            }
        }

        return 0.0
    }

    private fun parseOperationDate(op: JSONObject): Date? {
        val raw = op.optString("created_at", "")
            .ifBlank { op.optString("timestamp", "") }
            .ifBlank { op.optString("date", "") }
            .ifBlank { op.optString("sold_at", "") }
            .trim()
        if (raw.isEmpty() || raw == "null") return null

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.US).apply {
                    isLenient = false
                    if (p.endsWith("'Z'")) {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }
                val parsed = sdf.parse(raw)
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun isOperationToday(op: JSONObject, sdfKey: SimpleDateFormat, todayKey: String): Boolean {
        if (op.optBoolean("is_today", false)) return true
        val parsedDate = parseOperationDate(op)
        if (parsedDate != null) {
            return sdfKey.format(parsedDate) == todayKey
        }
        val rawTime = (
            op.optString("created_at", "") + " " +
                op.optString("time_ago", "") + " " +
                op.optString("date_label", "")
            ).lowercase(Locale.ROOT)
        return rawTime.contains(todayKey.lowercase(Locale.ROOT)) ||
            rawTime.contains("today") ||
            rawTime.contains("اليوم") ||
            rawTime.contains("الآن") ||
            rawTime.contains("دقيقة") ||
            rawTime.contains("دقائق") ||
            rawTime.contains("ساعة") ||
            rawTime.contains("ساعات")
    }

    private fun isSaleOperation(op: JSONObject): Boolean {
        val type = op.optString("type", op.optString("operation_type", "")).lowercase(Locale.ROOT)
        val invoiceNo = op.optString("invoice_number", op.optString("reference", "")).uppercase(Locale.US)
        if (type.contains("refund") || type.contains("return") || type.contains("scrap") || type.contains("transfer")) {
            return false
        }
        return type == "sale" || type == "pos_sale" || type == "invoice" ||
            type.contains("sale") || invoiceNo.startsWith("INV")
    }

    private fun fetchRecentActivity() {
        val client = apiClient ?: return
        if (!isAdded || isDestroyed || !isSessionValid() || !ensureExecutor()) return

        val branchId = if (currentBranchId > 0) currentBranchId else resolveActiveBranchId()
        val managerSnapshot = isManagerState
        val userIdSnapshot = currentUserId
        val usernameSnapshot = userNameState
        val cacheUserKey = if (managerSnapshot) 0 else userIdSnapshot

        localExecutor?.execute {
            try {
                val response = client.getUnifiedOperations(
                    branchId,
                    userIdSnapshot,
                    usernameSnapshot,
                    managerSnapshot,
                    1,
                    30,
                    null,
                    "all"
                )
                val respData: JSONObject? = response?.data
                if (response != null && response.isSuccess && respData != null) {
                    val activities = extractActivitiesArray(respData)
                    if (activities != null) {
                        activityCache?.save(branchId, cacheUserKey, activities)
                        val itemsList = mutableListOf<JSONObject>()
                        for (i in 0 until activities.length()) {
                            activities.optJSONObject(i)?.let { itemsList.add(it) }
                        }
                        mainHandler.post {
                            if (!isDestroyed && isAdded && currentBranchId == branchId) {
                                recentActivitiesState = itemsList
                                recomputeTodaySalesFromOperations(
                                    itemsList,
                                    forceOverride = !managerSnapshot || todaySalesLocalState <= 0.0
                                )
                                buildFallbackChartsFromOperations(itemsList, selectedChartDaysState)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchRecentActivity error", e)
            }
        }
    }

    private fun applyDashboardSummaryJson(root: JSONObject) {
        val data = root.optJSONObject("data") ?: root

        if (isManagerState) {
            val salesObj = data.optJSONObject("today_sales") ?: data.optJSONObject("sales") ?: data
            var sLocal = parseFlexibleDouble(salesObj, "today_total_local")
                .takeIf { it > 0.0 } ?: parseFlexibleDouble(salesObj, "total_local")
                .takeIf { it > 0.0 } ?: parseFlexibleDouble(salesObj, "today_sales_local")
            val sUsd = parseFlexibleDouble(salesObj, "today_total_usd")
                .takeIf { it > 0.0 } ?: parseFlexibleDouble(salesObj, "total_usd")
                .takeIf { it > 0.0 } ?: parseFlexibleDouble(salesObj, "today_sales_usd")
            val sCount = salesObj.optInt(
                "today_count",
                salesObj.optInt("invoices_count", salesObj.optInt("sales_count", 0))
            )
            var sWeight = parseFlexibleDouble(salesObj, "today_weight")
                .takeIf { it > 0.0 } ?: parseFlexibleDouble(salesObj, "sold_weight")
                .takeIf { it > 0.0 } ?: parseFlexibleDouble(salesObj, "total_weight")
                .takeIf { it > 0.0 } ?: parseFlexibleDouble(salesObj, "weight")

            if (sLocal <= 0.0 && sUsd > 0.0) {
                sLocal = convertUsdToActiveCurrency(sUsd)
            }

            if (sLocal > 0.0) todaySalesLocalState = sLocal
            if (sUsd > 0.0) todaySalesUsdState = sUsd
            if (sCount > 0) todayInvoicesCountState = sCount
            if (sWeight > 0.0) todaySoldWeightState = sWeight

            if (todaySoldWeightState <= 0.0 && recentActivitiesState.isNotEmpty()) {
                recomputeTodaySalesFromOperations(recentActivitiesState, forceOverride = false)
            }
        } else {
            val mySalesObj = data.optJSONObject("my_today_sales") ?: data.optJSONObject("user_sales")
            if (mySalesObj != null) {
                var mLocal = parseFlexibleDouble(mySalesObj, "total_local")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(mySalesObj, "today_total_local")
                val mUsd = parseFlexibleDouble(mySalesObj, "total_usd")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(mySalesObj, "today_total_usd")
                val mCount = mySalesObj.optInt("count", mySalesObj.optInt("invoices_count", 0))
                val mWeight = parseFlexibleDouble(mySalesObj, "weight")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(mySalesObj, "sold_weight")
                    .takeIf { it > 0.0 } ?: parseFlexibleDouble(mySalesObj, "total_weight")

                if (mLocal <= 0.0 && mUsd > 0.0) {
                    mLocal = convertUsdToActiveCurrency(mUsd)
                }

                if (mLocal > 0.0 || mUsd > 0.0 || mCount > 0) {
                    if (mLocal > 0.0) todaySalesLocalState = mLocal
                    if (mUsd > 0.0) todaySalesUsdState = mUsd
                    if (mCount > 0) todayInvoicesCountState = mCount
                    if (mWeight > 0.0) todaySoldWeightState = mWeight
                }
            }
            if (recentActivitiesState.isNotEmpty()) {
                recomputeTodaySalesFromOperations(
                    recentActivitiesState,
                    forceOverride = todaySalesLocalState <= 0.0 && todaySalesUsdState <= 0.0
                )
            }
        }

        val vaultObj = data.optJSONObject("vault") ?: data.optJSONObject("inventory") ?: data
        val pieces = vaultObj.optInt(
            "total_pieces",
            vaultObj.optInt("in_stock_count", vaultObj.optInt("items_count", 0))
        )
        val weight = parseFlexibleDouble(vaultObj, "total_weight")
            .takeIf { it > 0.0 } ?: parseFlexibleDouble(vaultObj, "gross_weight")
        val pure24 = parseFlexibleDouble(vaultObj, "pure_24k_weight")
            .takeIf { it > 0.0 } ?: parseFlexibleDouble(vaultObj, "equivalent_24k")
        if (pieces >= 0 && (vaultObj.has("total_pieces") || vaultObj.has("in_stock_count") || vaultObj.has("items_count"))) {
            totalVaultPiecesState = pieces
        }
        if (weight > 0.0) totalVaultWeightState = weight
        if (pure24 > 0.0) pure24kWeightState = pure24

        if (isManagerState) {
            val alertsObj = data.optJSONObject("alerts")
            val itemsArr = alertsObj?.optJSONArray("items") ?: data.optJSONArray("alerts_list")
            if (itemsArr != null) {
                rawAlertsJsonState = itemsArr.toString()
            }
            val aCount = alertsObj?.optInt("count", 0) ?: data.optInt("alerts_count", 0)
            val resolvedPrefs = context?.getSharedPreferences(PREFS_RESOLVED_ALERTS, Context.MODE_PRIVATE)
            val summaryId = "summary_alert_${currentBranchId}_$aCount"

            if (aCount > 0 && resolvedPrefs?.contains(summaryId) != true) {
                alertsCountState = aCount
                alertsMessageState = alertsObj?.optString("latest_message", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: (if (isArabic) "يوجد $aCount تنبيهات تتطلب مراجعتك" else "$aCount alerts require attention")
            } else {
                alertsCountState = 0
            }
        } else {
            alertsCountState = 0
        }

        if (data.has("gold_prices") || data.has("prices") || data.has("rates")) {
            applyGoldPricesJson(data)
        }
    }

    private fun applyGoldPricesJson(root: JSONObject) {
        val data = root.optJSONObject("data") ?: root
        val prices = data.optJSONObject("gold_prices")
            ?: data.optJSONObject("prices")
            ?: data.optJSONObject("rate")
            ?: data.optJSONObject("current")
            ?: data

        val rate = findDoubleCaseInsensitive(
            prices,
            "exchange_rate", "usd_rate", "rate"
        ).takeIf { it > 0.0 } ?: findDoubleCaseInsensitive(data, "exchange_rate", "usd_rate")
        if (rate > 0.0) exchangeRateState = rate

        val rawCurr = findStringCaseInsensitive(
            prices,
            "local_currency", "currency_code", "currency"
        ).ifBlank {
            findStringCaseInsensitive(data, "local_currency", "currency_code", "currency")
        }.ifBlank { "YER" }
        val curr = rawCurr.uppercase(Locale.US)
        localCurrencyState = curr

        var p24Local = findDoubleCaseInsensitive(
            prices,
            "price_24k_local", "karat_24_local", "sell_24k_local", "local_24k",
            "karat_24", "24k", "24K", "price_24k", "sell_24k", "gram_24k"
        )
        var p21Local = findDoubleCaseInsensitive(
            prices,
            "price_21k_local", "karat_21_local", "sell_21k_local", "local_21k",
            "karat_21", "21k", "21K", "price_21k", "sell_21k", "gram_21k"
        )
        var p18Local = findDoubleCaseInsensitive(
            prices,
            "price_18k_local", "karat_18_local", "sell_18k_local", "local_18k",
            "karat_18", "18k", "18K", "price_18k", "sell_18k", "gram_18k"
        )
        var p24Usd = findDoubleCaseInsensitive(
            prices,
            "price_24k_usd", "karat_24_usd", "24k_usd", "24K_usd", "usd_24k"
        )

        val ratesArr = prices.optJSONArray("rates") ?: data.optJSONArray("rates")
        if (ratesArr != null) {
            for (i in 0 until ratesArr.length()) {
                val rObj = ratesArr.optJSONObject(i) ?: continue
                val kLabel = rObj.optString("karat", rObj.optString("name", "")).uppercase(Locale.US)
                val localVal = findDoubleCaseInsensitive(
                    rObj,
                    "sell_price_local", "price_local", "local_price", "sell_price", "price", "rate"
                )
                val usdVal = findDoubleCaseInsensitive(rObj, "price_usd", "sell_price_usd", "usd_price")
                when {
                    kLabel.contains("24") -> {
                        if (localVal > 0.0) p24Local = localVal
                        if (usdVal > 0.0) p24Usd = usdVal
                    }
                    kLabel.contains("21") -> if (localVal > 0.0) p21Local = localVal
                    kLabel.contains("18") -> if (localVal > 0.0) p18Local = localVal
                }
            }
        }

        if (curr != "USD" && exchangeRateState > 10.0 && p24Local > 0.0 && p24Local < 500.0) {
            if (p24Usd <= 0.0) p24Usd = p24Local
            p24Local *= exchangeRateState
            if (p21Local in 1.0..500.0) p21Local *= exchangeRateState
            if (p18Local in 1.0..500.0) p18Local *= exchangeRateState
        }

        if (p24Local > 0.0) {
            if (p21Local <= 0.0) p21Local = p24Local * (21.0 / 24.0)
            if (p18Local <= 0.0) p18Local = p24Local * (18.0 / 24.0)
        }

        if (p24Local > 0.0) price24kLocalState = p24Local
        if (p21Local > 0.0) price21kLocalState = p21Local
        if (p18Local > 0.0) price18kLocalState = p18Local

        if (p24Usd > 0.0) {
            price24kUsdState = p24Usd
        } else if (p24Local > 0.0 && curr == "USD") {
            price24kUsdState = p24Local
        } else if (p24Local > 0.0 && exchangeRateState > 0.0) {
            price24kUsdState = p24Local / exchangeRateState
        }

        pricesLastUpdatedState = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
    }

    private fun findDoubleCaseInsensitive(obj: JSONObject?, vararg keys: String): Double {
        if (obj == null) return 0.0
        for (k in keys) {
            if (obj.has(k)) {
                val v = obj.optDouble(k, 0.0)
                if (!v.isNaN() && v > 0.0) return v
            }
            val it = obj.keys()
            while (it.hasNext()) {
                val actualKey = it.next()
                if (actualKey.equals(k, ignoreCase = true)) {
                    val v = obj.optDouble(actualKey, 0.0)
                    if (!v.isNaN() && v > 0.0) return v
                }
            }
        }
        return 0.0
    }

    private fun findStringCaseInsensitive(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        for (k in keys) {
            if (obj.has(k)) {
                val v = obj.optString(k, "").trim()
                if (v.isNotEmpty() && v != "null") return v
            }
            val it = obj.keys()
            while (it.hasNext()) {
                val actualKey = it.next()
                if (actualKey.equals(k, ignoreCase = true)) {
                    val v = obj.optString(actualKey, "").trim()
                    if (v.isNotEmpty() && v != "null") return v
                }
            }
        }
        return ""
    }

    private fun recalculateUnresolvedAlertsBadge() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_RESOLVED_ALERTS, Context.MODE_PRIVATE)
        val summaryId = "summary_alert_${currentBranchId}_$alertsCountState"
        if (alertsCountState > 0 && prefs.contains(summaryId)) {
            alertsCountState = 0
        }
    }

    private fun recomputeTodaySalesFromOperations(ops: List<JSONObject>, forceOverride: Boolean) {
        val needWeightOnly = !forceOverride && todayInvoicesCountState > 0 &&
            (todaySalesLocalState > 0.0 || todaySalesUsdState > 0.0) &&
            todaySoldWeightState <= 0.0

        if (!forceOverride && !needWeightOnly && todayInvoicesCountState > 0 &&
            (todaySalesLocalState > 0.0 || todaySalesUsdState > 0.0)
        ) {
            return
        }

        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayKey = sdfKey.format(Date())

        var count = 0
        var sumLocal = 0.0
        var sumUsd = 0.0
        var sumWeight = 0.0

        for (op in ops) {
            if (!isSaleOperation(op)) continue
            if (isOperationToday(op, sdfKey, todayKey)) {
                count++
                sumLocal += extractOperationAmountLocal(op)
                sumUsd += extractOperationAmountUsd(op)
                sumWeight += extractOperationWeight(op)
            }
        }

        if (needWeightOnly) {
            if (sumWeight > 0.0) {
                todaySoldWeightState = sumWeight
            }
            return
        }

        if (forceOverride || count > 0) {
            if (count > 0 || forceOverride) todayInvoicesCountState = count
            if (sumLocal > 0.0 || forceOverride) todaySalesLocalState = sumLocal
            if (sumUsd > 0.0 || forceOverride) todaySalesUsdState = sumUsd
            if (sumWeight > 0.0 || forceOverride) todaySoldWeightState = sumWeight
        }
    }

    private fun extractActivitiesArray(root: JSONObject?): JSONArray? {
        if (root == null) return null
        root.optJSONArray("activities")?.let { return it }
        root.optJSONArray("data")?.let { return it }
        root.optJSONArray("sales")?.let { return it }
        val nested = root.optJSONObject("data")
        if (nested != null) {
            nested.optJSONArray("activities")?.let { return it }
            nested.optJSONArray("data")?.let { return it }
            nested.optJSONArray("sales")?.let { return it }
        }
        return null
    }

    private fun invokeApiMethod(methodName: String, vararg args: Any): MiraApiClient.ApiResponse? {
        val client = apiClient ?: return null
        return try {
            val target = client.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == args.size
            } ?: return null
            target.invoke(client, *args) as? MiraApiClient.ApiResponse
        } catch (_: Exception) {
            null
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun openActivityDetails(activityItem: JSONObject) {
        if (!isAdded) return
        try {
            val sheet = ActivityDetailsBottomSheet.newInstance(activityItem, isArabic)
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
                            if (isArabic) "تعذر فتح الفاتورة" else "Unable to open receipt",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onOpenRefund(activityData: JSONObject) {
                    if (!isAdded) return
                    if (permissionManager?.canRefund() == false) {
                        Toast.makeText(
                            context,
                            if (isArabic) "ليس لديك صلاحية تنفيذ عمليات الإرجاع" else "You do not have refund permission",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    val salesFragment = MiraPosFragment().apply {
                        arguments = Bundle().apply {
                            putString("refund_data_json", activityData.toString())
                            putBoolean("auto_open_refund", true)
                        }
                    }
                    navigateToFragment(salesFragment, "Sales")
                }
            })
            sheet.show(parentFragmentManager, "ActivityDetails")
        } catch (e: Exception) {
            Log.e(TAG, "openActivityDetails error", e)
        }
    }

    private fun navigateToAllActivities() {
        navigateToFragment(MiraAllActivitiesFragment(), "AllActivities")
    }

    private fun navigateToAlertsCenter() {
        val alertsFragment = MiraAlertsCenterFragment.newInstance(rawAlertsJsonState, isArabic)
        navigateToFragment(alertsFragment, "AlertsCenter")
    }

    private fun navigateToFragment(fragment: Fragment, tag: String) {
        if (!isAdded) return
        try {
            val host = activity as? UHFMainActivity
            if (host != null) {
                host.loadFragment(fragment, tag)
            } else {
                val fm = parentFragmentManager
                if (!fm.isStateSaved) {
                    fm.beginTransaction()
                        .replace(R.id.fragment_container, fragment, tag)
                        .addToBackStack(tag)
                        .commit()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "navigateToFragment($tag) error: ${e.message}")
        }
    }

    private fun createFragmentByClassName(vararg classNames: String, fallback: () -> Fragment): Fragment {
        for (name in classNames) {
            try {
                val clazz = Class.forName(name)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is Fragment) return instance
            } catch (_: Exception) {
            }
        }
        return fallback()
    }

    /** التحقق من صلاحية كل زر في شبكة الوصول السريع بالداشبورد */
    private fun isQuickActionAllowed(tag: String): Boolean {
        val pm = permissionManager ?: return false
        return when (tag) {
            "Sales" -> pm.canViewPos()
            "SalesHistory" -> pm.canViewSalesHistory()
            "InventoryDashboard" -> pm.canViewInventory()
            "Scan" -> pm.canViewInventory()
            "Transfers" -> pm.canViewTransfers()
            "Scrap" -> pm.canViewScrapGold()
            "Encode" -> pm.canUseIdentity()
            "AllActivities", "sync_rates" -> true
            else -> pm.canAccessFragment(tag)
        }
    }

    private fun handleQuickAction(action: QuickActionItem) {
        if (!isQuickActionAllowed(action.tag)) {
            Toast.makeText(
                context,
                if (isArabic) "ليس لديك صلاحية للوصول إلى هذه الخدمة" else "You do not have permission to access this feature",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (action.tag == "sync_rates") {
            refreshAllDashboardData(forceNetwork = true)
            Toast.makeText(
                context,
                if (isArabic) "جاري تحديث الأسعار والرسوم البيانية..." else "Syncing rates & charts...",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        navigateToFragment(action.fragmentProvider(), action.tag)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  واجهة لوحة القيادة التنفيذية (Jetpack Compose)
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun DashboardScreenContent() {
        val colors = MiraTheme.colors
        val scrollState = rememberScrollState()
        val isAr = isArabic

        // الاستماع اللحظي لتحديثات الصلاحيات أثناء عرض الداشبورد
        val pm = permissionManager
        var permTick by remember { mutableIntStateOf(0) }
        DisposableEffect(pm) {
            val listener = PermissionManager.OnPermissionsChangedListener {
                mainHandler.post {
                    isManagerState = resolveIsManagerOrAdmin(sessionManager, userRoleState) ||
                        (permissionManager?.isManager == true) ||
                        (permissionManager?.canViewAllBranches() == true)
                    permTick++
                }
            }
            pm?.addListener(listener)
            onDispose {
                pm?.removeListener(listener)
            }
        }

        LaunchedEffect(recentActivitiesState, isManagerState, selectedChartDaysState) {
            if (recentActivitiesState.isNotEmpty()) {
                recomputeTodaySalesFromOperations(
                    recentActivitiesState,
                    forceOverride = !isManagerState || todaySalesLocalState <= 0.0
                )
                buildFallbackChartsFromOperations(recentActivitiesState, selectedChartDaysState)
            }
        }

        val quickActions = remember(isAr, permTick) {
            listOf(
                QuickActionItem(
                    tag = "Sales",
                    titleAr = "نقطة البيع",
                    titleEn = "POS Sale",
                    icon = Icons.Rounded.PointOfSale,
                    accentColor = Color(0xFF10B981),
                    fragmentProvider = { MiraPosFragment() }
                ),
                QuickActionItem(
                    tag = "SalesHistory",
                    titleAr = "سجل المبيعات",
                    titleEn = "Invoices",
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    accentColor = Color(0xFFD4AF37),
                    fragmentProvider = { MiraSalesHistoryFragment() }
                ),
                QuickActionItem(
                    tag = "InventoryDashboard",
                    titleAr = "لوحة الجرد",
                    titleEn = "Inventory Hub",
                    icon = Icons.Rounded.Inventory2,
                    accentColor = Color(0xFF06B6D4),
                    fragmentProvider = { MiraInventoryDashboardFragment() }
                ),
                QuickActionItem(
                    tag = "Scan",
                    titleAr = "جرد فوري",
                    titleEn = "Quick Scan",
                    icon = Icons.Rounded.QrCodeScanner,
                    accentColor = Color(0xFF38BDF8),
                    fragmentProvider = { MiraInventoryProFragment() }
                ),
                QuickActionItem(
                    tag = "Transfers",
                    titleAr = "التحويلات",
                    titleEn = "Transfers",
                    icon = Icons.Rounded.SwapHoriz,
                    accentColor = Color(0xFFA855F7),
                    fragmentProvider = { MiraTransferFragment() }
                ),
                QuickActionItem(
                    tag = "Scrap",
                    titleAr = "شراء كسر",
                    titleEn = "Scrap Gold",
                    icon = Icons.Rounded.Scale,
                    accentColor = Color(0xFFF59E0B),
                    fragmentProvider = {
                        createFragmentByClassName(
                            "com.example.uhf.fragment.MiraScrapGoldBuyFragment",
                            "com.example.uhf.fragment.MiraScrapFragment"
                        ) {
                            MiraPosFragment().apply {
                                arguments = Bundle().apply { putBoolean("open_scrap_mode", true) }
                            }
                        }
                    }
                ),
                QuickActionItem(
                    tag = "Encode",
                    titleAr = "تشفير RFID",
                    titleEn = "Encode Tag",
                    icon = Icons.Rounded.Contactless,
                    accentColor = Color(0xFF3B82F6),
                    fragmentProvider = { MiraIdentityStudioFragment() }
                ),
                QuickActionItem(
                    tag = "AllActivities",
                    titleAr = "كل العمليات",
                    titleEn = "All Logs",
                    icon = Icons.Rounded.History,
                    accentColor = Color(0xFFEC4899),
                    fragmentProvider = { MiraAllActivitiesFragment() }
                ),
                QuickActionItem(
                    tag = "sync_rates",
                    titleAr = "تحديث فوري",
                    titleEn = "Sync Now",
                    icon = Icons.Rounded.Sync,
                    accentColor = Color(0xFF14B8A6),
                    fragmentProvider = { MiraDashboardFragment() }
                )
            ).filter { isQuickActionAllowed(it.tag) }
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
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DashboardTopHeader(
                        isArabic = isAr,
                        orgTitle = orgTitleState,
                        userName = userNameState,
                        isOnline = isOnlineState,
                        isRefreshing = isRefreshingState,
                        onRefresh = { refreshAllDashboardData(forceNetwork = true) }
                    )

                    RoleVisibilityScopeBadge(
                        isArabic = isAr,
                        isManager = isManagerState
                    )

                    if (isManagerState && branchesListState.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(branchesListState) { branch ->
                                val selected = branch.id == selectedBranchIdState
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (selected) colors.goldPrimary.copy(alpha = 0.20f)
                                    else colors.cardSurface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) colors.goldPrimary else colors.cardBorder
                                    ),
                                    modifier = Modifier.clickable { switchBranch(branch.id) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Store,
                                            contentDescription = null,
                                            tint = if (selected) colors.goldPrimary else colors.textSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = branch.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (selected) colors.goldPrimary else colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (alertsCountState > 0) {
                        AlertsBannerCard(
                            isArabic = isAr,
                            count = alertsCountState,
                            message = alertsMessageState,
                            onClick = { navigateToAlertsCenter() }
                        )
                    }

                    LiveGoldRatesCard(isArabic = isAr)

                    ExecutiveSummaryAndVaultCard(
                        isArabic = isAr,
                        isManager = isManagerState
                    )

                    if (isManagerState) {
                        ManagerAnalyticsChartsSection(
                            isArabic = isAr,
                            currency = localCurrencyState,
                            points = chartPointsState,
                            karatItems = karatDistributionState,
                            operationsBreakdown = operationsBreakdownState,
                            selectedDays = selectedChartDaysState,
                            maskAmounts = maskAmountsState,
                            onPeriodChange = { newDays ->
                                selectedChartDaysState = newDays
                                fetchDashboardCharts(newDays)
                            }
                        )
                    } else {
                        StaffAchievementChartCard(
                            isArabic = isAr,
                            currency = localCurrencyState,
                            points = chartPointsState,
                            selectedDays = selectedChartDaysState,
                            targetAmount = staffTargetAmountState,
                            maskAmounts = maskAmountsState,
                            onPeriodChange = { newDays ->
                                selectedChartDaysState = newDays
                                fetchDashboardCharts(newDays)
                            }
                        )
                    }

                    if (quickActions.isNotEmpty()) {
                        QuickActionsSection(
                            isArabic = isAr,
                            actions = quickActions,
                            onActionClick = { handleQuickAction(it) }
                        )
                    }

                    RecentActivitySection(
                        isArabic = isAr,
                        activities = recentActivitiesState.take(10),
                        maskAmounts = maskAmountsState,
                        onViewAll = { navigateToAllActivities() },
                        onItemClick = { openActivityDetails(it) }
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    @Composable
    private fun RoleVisibilityScopeBadge(
        isArabic: Boolean,
        isManager: Boolean
    ) {
        val accent = if (isManager) Color(0xFFD4AF37) else Color(0xFF06B6D4)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Icon(
                    imageVector = if (isManager) Icons.Rounded.AdminPanelSettings else Icons.Rounded.PersonPin,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isManager) {
                        if (isArabic) "صلاحية الإدارة: عرض جميع عمليات ومبيعات الفرع"
                        else "Manager View: Showing all branch operations & sales"
                    } else {
                        if (isArabic) "نطاق الموظف: عرض مبيعاتك وعملياتك الشخصية فقط"
                        else "Staff View: Showing your personal sales & operations only"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
        }
    }

    @Composable
    private fun LiveGoldRatesCard(isArabic: Boolean) {
        val colors = MiraTheme.colors
        val curr = localCurrencyState

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.40f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                colors.goldPrimary.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (isArabic) "أسعار الذهب اليوم (للجرام)" else "Live Gold Rates (/g)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.textPrimary
                        )
                    }

                    if (exchangeRateState > 0.0 && curr != "USD") {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF10B981).copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = "1$ = ${formatCompactNumber(exchangeRateState)} $curr",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KaratRateChip(
                        karatLabel = if (isArabic) "عيار 24" else "24K",
                        priceText = if (price24kLocalState > 0.0) "${formatCompactNumber(price24kLocalState)} $curr" else "—",
                        subText = if (price24kUsdState > 0.0) String.format(Locale.US, "$%.2f", price24kUsdState) else null,
                        accent = Color(0xFFD4AF37),
                        modifier = Modifier.weight(1f)
                    )
                    KaratRateChip(
                        karatLabel = if (isArabic) "عيار 21" else "21K",
                        priceText = if (price21kLocalState > 0.0) "${formatCompactNumber(price21kLocalState)} $curr" else "—",
                        subText = if (price21kLocalState > 0.0 && exchangeRateState > 0.0 && curr != "USD")
                            String.format(Locale.US, "$%.2f", price21kLocalState / exchangeRateState) else null,
                        accent = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                    KaratRateChip(
                        karatLabel = if (isArabic) "عيار 18" else "18K",
                        priceText = if (price18kLocalState > 0.0) "${formatCompactNumber(price18kLocalState)} $curr" else "—",
                        subText = if (price18kLocalState > 0.0 && exchangeRateState > 0.0 && curr != "USD")
                            String.format(Locale.US, "$%.2f", price18kLocalState / exchangeRateState) else null,
                        accent = Color(0xFF06B6D4),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    @Composable
    private fun KaratRateChip(
        karatLabel: String,
        priceText: String,
        subText: String?,
        accent: Color,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.backgroundTop.copy(alpha = 0.55f))
                .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                .padding(vertical = 8.dp, horizontal = 6.dp)
        ) {
            Text(
                text = karatLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = priceText,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subText != null) {
                Text(
                    text = subText,
                    fontSize = 10.sp,
                    color = colors.textSecondary
                )
            }
        }
    }

    @Composable
    private fun ExecutiveSummaryAndVaultCard(
        isArabic: Boolean,
        isManager: Boolean
    ) {
        val colors = MiraTheme.colors
        val curr = localCurrencyState
        val masked = maskAmountsState

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isManager) {
                            if (isArabic) "ملخص الفرع والخزينة اليوم" else "Branch Today & Vault"
                        } else {
                            if (isArabic) "ملخص مبيعاتك اليوم" else "Your Sales Today"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary
                    )

                    IconButton(
                        onClick = { maskAmountsState = !maskAmountsState },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (masked) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle Privacy",
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val salesMainDisplay = when {
                        masked -> "•••••"
                        todaySalesLocalState > 0.0 -> "${formatCompactNumber(todaySalesLocalState)} $curr"
                        todaySalesUsdState > 0.0 -> String.format(Locale.US, "$%,.2f", todaySalesUsdState)
                        else -> "0 $curr"
                    }

                    SalesMetricBox(
                        label = if (isManager) {
                            if (isArabic) "مبيعات اليوم" else "Today Sales"
                        } else {
                            if (isArabic) "مبيعاتي اليوم" else "My Sales"
                        },
                        value = salesMainDisplay,
                        accent = Color(0xFF10B981),
                        modifier = Modifier.weight(1.35f)
                    )
                    SalesMetricBox(
                        label = if (isArabic) "الفواتير" else "Invoices",
                        value = if (masked) "••" else todayInvoicesCountState.toString(),
                        accent = Color(0xFFD4AF37),
                        modifier = Modifier.weight(0.8f)
                    )
                    SalesMetricBox(
                        label = if (isArabic) "الوزن المباع" else "Sold Weight",
                        value = if (masked) "•••" else String.format(Locale.US, "%.1fg", todaySoldWeightState),
                        accent = Color(0xFF06B6D4),
                        modifier = Modifier.weight(0.95f)
                    )
                }

                if (isManager && (totalVaultPiecesState > 0 || totalVaultWeightState > 0.0)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VaultStatChip(
                            label = if (isArabic) "قطع المخزون" else "Stock Pieces",
                            value = if (masked) "•••" else "$totalVaultPiecesState",
                            icon = Icons.Rounded.Diamond,
                            modifier = Modifier.weight(1f)
                        )
                        VaultStatChip(
                            label = if (isArabic) "إجمالي الوزن" else "Vault Weight",
                            value = if (masked) "•••" else String.format(Locale.US, "%.1f g", totalVaultWeightState),
                            icon = Icons.Rounded.Scale,
                            modifier = Modifier.weight(1f)
                        )
                        if (pure24kWeightState > 0.0) {
                            VaultStatChip(
                                label = if (isArabic) "صافي 24K" else "Pure 24K",
                                value = if (masked) "•••" else String.format(Locale.US, "%.1f g", pure24kWeightState),
                                icon = Icons.Rounded.Verified,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun formatCompactNumber(value: Double): String {
        return try {
            NumberFormat.getNumberInstance(Locale.US).apply {
                maximumFractionDigits = 0
                minimumFractionDigits = 0
            }.format(value)
        } catch (_: Exception) {
            String.format(Locale.US, "%.0f", value)
        }
    }

    private fun readStringProp(target: Any?, vararg names: String): String {
        if (target == null) return ""
        for (name in names) {
            try {
                val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
                val m = target.javaClass.methods.firstOrNull {
                    (it.name == getter || it.name == name) && it.parameterCount == 0
                }
                val v = m?.invoke(target)?.toString()?.trim()
                if (!v.isNullOrEmpty() && v != "null") return v
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun readIntProp(target: Any?, vararg names: String): Int {
        if (target == null) return 0
        for (name in names) {
            try {
                val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
                val m = target.javaClass.methods.firstOrNull {
                    (it.name == getter || it.name == name) && it.parameterCount == 0
                }
                val v = m?.invoke(target)
                if (v is Number) return v.toInt()
            } catch (_: Exception) {
            }
        }
        return 0
    }
}
