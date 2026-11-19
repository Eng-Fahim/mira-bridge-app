package com.example.uhf.fragment

import android.content.Context
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
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.dialog.ActivityDetailsBottomSheet
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.tools.RecentActivityCache
import com.example.uhf.tools.SecureSessionManager
import com.example.uhf.ui.dashboard.*
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
import java.util.concurrent.atomic.AtomicBoolean

class MiraDashboardFragment : Fragment() {

    companion object {
        private const val TAG = "MiraDashboardFragment"
        private const val DASHBOARD_CACHE_PREFS = "mira_dashboard_summary_cache_v2"
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

    private var apiClient: MiraApiClient? = null
    private var sessionManager: SecureSessionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var activityCache: RecentActivityCache? = null

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

        initSessionAndBranchState()
        loadOfflineData()
        refreshAllDashboardData(forceNetwork = true)
    }

    override fun onResume() {
        super.onResume()
        (activity as? UHFMainActivity)?.setBottomNavVisible(true)
        activity?.findViewById<View>(R.id.appBarLayout)?.visibility = View.VISIBLE

        isOnlineState = isNetworkAvailable()
        val latestBranch = resolveActiveBranchId()
        val latestUser = readIntProp(sessionManager, "userId", "id")

        if (latestBranch != currentBranchId || latestUser != currentUserId) {
            initSessionAndBranchState()
            recentActivitiesState = emptyList()
            loadOfflineData()
            refreshAllDashboardData(forceNetwork = true)
        } else {
            val cache = activityCache
            val cacheUserKey = if (isManagerState) 0 else currentUserId
            if (cache != null && !cache.isValid(currentBranchId, cacheUserKey)) {
                fetchRecentActivity()
            }
        }
    }

    override fun onDestroyView() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        localExecutor?.let { exec ->
            if (!exec.isShutdown) {
                exec.shutdownNow()
            }
        }
        localExecutor = null
        super.onDestroyView()
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
        isManagerState = resolveIsManagerOrAdmin(sm, userRoleState)

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

    /**
     * تحديد ما إذا كان المستخدم مديراً/مشرفاً (يرى عمليات الفرع بالكامل)
     * أو موظف مبيعات/كاشير (يرى عملياته الشخصية فقط)
     */
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
            val setter = branchesManager?.javaClass?.methods?.firstOrNull {
                it.name in setOf("setActiveBranchId", "selectBranch") && it.parameterCount == 1
            }
            setter?.invoke(branchesManager, branchId)
        } catch (_: Exception) {
        }

        recentActivitiesState = emptyList()
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

            val cacheUserKey = if (isManagerState) 0 else currentUserId
            val cachedActs = activityCache?.load(currentBranchId, cacheUserKey)
            if (cachedActs != null && cachedActs.length() > 0 && recentActivitiesState.isEmpty()) {
                val cachedList = mutableListOf<JSONObject>()
                for (i in 0 until cachedActs.length()) {
                    cachedActs.optJSONObject(i)?.let { cachedList.add(it) }
                }
                recentActivitiesState = cachedList
                recomputeTodaySalesFromOperations(cachedList, forceOverride = !isManagerState)
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

        isRefreshingState = true
        val branchSnapshot = currentBranchId
        val managerSnapshot = isManagerState
        val userSnapshot = currentUserId

        fetchRecentActivity()

        localExecutor?.execute {
            try {
                val client = apiClient
                val summaryResp = client?.getDashboardSummarySmart(branchSnapshot)
                if (summaryResp != null && summaryResp.isSuccess && summaryResp.data != null) {
                    val dataJson = summaryResp.data!!
                    context?.getSharedPreferences(DASHBOARD_CACHE_PREFS, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(summaryCacheKey(branchSnapshot), dataJson.toString())
                        ?.apply()

                    mainHandler.post {
                        if (!isDestroyed && isAdded && currentBranchId == branchSnapshot) {
                            applyDashboardSummaryJson(dataJson)
                            if (!managerSnapshot && currentUserId == userSnapshot) {
                                recomputeTodaySalesFromOperations(recentActivitiesState, forceOverride = true)
                            }
                        }
                    }
                }

                val pricesResp = client?.getGoldPricesSmart(branchSnapshot)
                if (pricesResp != null && pricesResp.isSuccess && pricesResp.data != null) {
                    val pJson = pricesResp.data!!
                    context?.getSharedPreferences(DASHBOARD_CACHE_PREFS, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString("gold_prices_json", pJson.toString())
                        ?.apply()

                    mainHandler.post {
                        if (!isDestroyed && isAdded) {
                            applyGoldPricesJson(pJson)
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
                    10,
                    null,
                    "all"
                )
                if (response != null && response.isSuccess && response.data != null) {
                    val activities = extractActivitiesArray(response.data)
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
                                    forceOverride = !managerSnapshot
                                )
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

        // للمدراء: تُعرض إجماليات الفرع كاملة
        // للموظفين: نبحث أولاً عن الحقول الشخصية (my_today_sales) وإلا تُحسب من مبيعات الموظف
        if (isManagerState) {
            val salesObj = data.optJSONObject("today_sales") ?: data.optJSONObject("sales") ?: data
            val sLocal = salesObj.optDouble(
                "today_total_local",
                salesObj.optDouble("total_local", salesObj.optDouble("today_sales_local", 0.0))
            )
            val sUsd = salesObj.optDouble(
                "today_total_usd",
                salesObj.optDouble("total_usd", salesObj.optDouble("today_sales_usd", 0.0))
            )
            val sCount = salesObj.optInt(
                "today_count",
                salesObj.optInt("invoices_count", salesObj.optInt("sales_count", 0))
            )
            val sWeight = salesObj.optDouble(
                "today_weight",
                salesObj.optDouble("sold_weight", 0.0)
            )

            if (sLocal > 0.0) todaySalesLocalState = sLocal
            if (sUsd > 0.0) todaySalesUsdState = sUsd
            if (sCount > 0) todayInvoicesCountState = sCount
            if (sWeight > 0.0) todaySoldWeightState = sWeight
        } else {
            val mySalesObj = data.optJSONObject("my_today_sales") ?: data.optJSONObject("user_sales")
            if (mySalesObj != null) {
                todaySalesLocalState = mySalesObj.optDouble("total_local", 0.0)
                todaySalesUsdState = mySalesObj.optDouble("total_usd", 0.0)
                todayInvoicesCountState = mySalesObj.optInt("count", 0)
                todaySoldWeightState = mySalesObj.optDouble("weight", 0.0)
            } else {
                recomputeTodaySalesFromOperations(recentActivitiesState, forceOverride = true)
            }
        }

        // الخزينة والمخزون
        val vaultObj = data.optJSONObject("vault") ?: data.optJSONObject("inventory") ?: data
        val pieces = vaultObj.optInt(
            "total_pieces",
            vaultObj.optInt("in_stock_count", vaultObj.optInt("items_count", 0))
        )
        val weight = vaultObj.optDouble(
            "total_weight",
            vaultObj.optDouble("gross_weight", 0.0)
        )
        val pure24 = vaultObj.optDouble(
            "pure_24k_weight",
            vaultObj.optDouble("equivalent_24k", 0.0)
        )
        if (pieces > 0) totalVaultPiecesState = pieces
        if (weight > 0.0) totalVaultWeightState = weight
        if (pure24 > 0.0) pure24kWeightState = pure24

        // التنبيهات (للمدراء)
        if (isManagerState) {
            val alertsObj = data.optJSONObject("alerts")
            val aCount = alertsObj?.optInt("count", 0) ?: data.optInt("alerts_count", 0)
            alertsCountState = aCount
            if (aCount > 0) {
                alertsMessageState = alertsObj?.optString("latest_message", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: (if (isArabic) "يوجد $aCount تنبيهات تتطلب مراجعتك" else "$aCount alerts require attention")
            }
        } else {
            alertsCountState = 0
        }

        if (data.has("gold_prices") || data.has("prices")) {
            applyGoldPricesJson(data)
        }
    }

    private fun applyGoldPricesJson(root: JSONObject) {
        val data = root.optJSONObject("data") ?: root
        val prices = data.optJSONObject("gold_prices") ?: data.optJSONObject("prices") ?: data

        val rate = prices.optDouble("exchange_rate", data.optDouble("exchange_rate", 0.0))
        if (rate > 0.0) exchangeRateState = rate

        val curr = prices.optString("currency", data.optString("currency", "YER")).ifBlank { "YER" }
        localCurrencyState = curr.uppercase(Locale.US)

        val p24 = prices.optDouble("karat_24", prices.optDouble("24k", prices.optDouble("price_24k", 0.0)))
        val p21 = prices.optDouble("karat_21", prices.optDouble("21k", prices.optDouble("price_21k", 0.0)))
        val p18 = prices.optDouble("karat_18", prices.optDouble("18k", prices.optDouble("price_18k", 0.0)))
        val p24Usd = prices.optDouble("karat_24_usd", prices.optDouble("24k_usd", 0.0))

        if (p24 > 0.0) price24kLocalState = p24
        if (p21 > 0.0) price21kLocalState = p21
        if (p18 > 0.0) price18kLocalState = p18
        if (p24Usd > 0.0) {
            price24kUsdState = p24Usd
        } else if (p24 > 0.0 && exchangeRateState > 0.0) {
            price24kUsdState = p24 / exchangeRateState
        }

        pricesLastUpdatedState = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
    }

        private fun recomputeTodaySalesFromOperations(ops: List<JSONObject>, forceOverride: Boolean) {
        if (!forceOverride && todayInvoicesCountState > 0 && todaySoldWeightState > 0.0) return
        val todayPrefix = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var count = 0
        var sumLocal = 0.0
        var sumUsd = 0.0
        var sumWeight = 0.0

        for (op in ops) {
            val type = op.optString("type", "")
            val ts = op.optString("created_at", op.optString("timestamp", ""))
            if (type == "sale" && ts.startsWith(todayPrefix)) {
                count++
                sumLocal += op.optDouble("final_amount", op.optDouble("total_amount", 0.0))
                sumUsd += op.optDouble("final_amount_usd", op.optDouble("total_amount_usd", 0.0))

                var opWeight = op.optDouble("total_weight", op.optDouble("weight", 0.0))
                if (opWeight <= 0.0) {
                    val items = op.optJSONArray("items") ?: op.optJSONArray("sold_items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val it = items.optJSONObject(i) ?: continue
                            opWeight += it.optDouble("weight", it.optDouble("gross_weight_grams", 0.0))
                        }
                    }
                }
                sumWeight += opWeight
            }
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

    private fun createFragmentByClassName(className: String, fallback: () -> Fragment): Fragment {
        return try {
            val clazz = Class.forName(className)
            (clazz.getDeclaredConstructor().newInstance() as? Fragment) ?: fallback()
        } catch (_: Exception) {
            fallback()
        }
    }

    private fun handleQuickAction(action: QuickActionItem) {
        if (action.tag == "sync_rates") {
            refreshAllDashboardData(forceNetwork = true)
            Toast.makeText(
                context,
                if (isArabic) "جاري تحديث الأسعار والبيانات..." else "Syncing rates & data...",
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

        val quickActions = remember(isAr) {
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
                    icon = Icons.Rounded.ReceiptLong,
                    accentColor = Color(0xFFD4AF37),
                    fragmentProvider = { MiraSalesHistoryFragment() }
                ),
                QuickActionItem(
                    tag = "Scan",
                    titleAr = "الجرد الذكي",
                    titleEn = "Inventory",
                    icon = Icons.Rounded.Inventory2,
                    accentColor = Color(0xFF06B6D4),
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
                        createFragmentByClassName("com.example.uhf.fragment.MiraScrapFragment") {
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
                    fragmentProvider = {
                        createFragmentByClassName("com.example.uhf.fragment.MiraEncodeFragment") {
                            MiraInventoryProFragment()
                        }
                    }
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
            )
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

                    // شارة توضيح نطاق الصلاحية (للمدير: جميع عمليات الفرع | للموظف: عملياتي فقط)
                    RoleVisibilityScopeBadge(
                        isArabic = isAr,
                        isManager = isManagerState
                    )

                    // مبدّل الفروع للمدراء فقط
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
                            onClick = { navigateToAllActivities() }
                        )
                    }

                    LiveGoldRatesCard(isArabic = isAr)

                    ExecutiveSummaryAndVaultCard(
                        isArabic = isAr,
                        isManager = isManagerState
                    )

                    QuickActionsSection(
                        isArabic = isAr,
                        actions = quickActions,
                        onActionClick = { handleQuickAction(it) }
                    )

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
                            imageVector = Icons.Rounded.TrendingUp,
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
                        subText = null,
                        accent = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                    KaratRateChip(
                        karatLabel = if (isArabic) "عيار 18" else "18K",
                        priceText = if (price18kLocalState > 0.0) "${formatCompactNumber(price18kLocalState)} $curr" else "—",
                        subText = null,
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

                // رصيد الخزينة الكلي يظهر للمدراء
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
