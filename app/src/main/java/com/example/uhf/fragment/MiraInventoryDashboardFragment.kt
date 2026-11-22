package com.example.uhf.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.api.MiraApiClient
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraPriceManager
import com.example.uhf.manager.PermissionManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MiraInventoryDashboardFragment : Fragment() {

    companion object {
        private const val TAG = "InvDashboardFragment"
    }

    private data class BranchOption(
        val id: Int,
        val name: String
    )

    private data class DashboardSnapshot(
        val totalValueUsd: Double = 0.0,
        val totalItems: Int = 0,
        val totalWeightGrams: Double = 0.0,
        val matchedItems: Int = 0,
        val missingItems: Int = 0,
        val extraItems: Int = 0,
        val pendingSessions: Int = 0,
        val hasRecordedAudit: Boolean = false,
        val lastAuditCode: String = "",
        val lastAuditDate: String = "",
        val matchRatePercent: Float? = null
    )

    private var apiClient: MiraApiClient? = null
    private var sessionManager: SessionManager? = null
    private var permissionManager: PermissionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var priceManager: MiraPriceManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fetchGeneration = AtomicInteger(0)
    private val networkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "InvDashboard-Net").apply { isDaemon = true }
    }

    private var isReceiverRegistered = false
    private var refreshReceiver: BroadcastReceiver? = null
    private var isArabic: Boolean = true

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose Reactive States
    // ═══════════════════════════════════════════════════════════════════

    private var isLoadingState by mutableStateOf(false)
    private var errorMessageState by mutableStateOf<String?>(null)

    private var canViewAllBranchesState by mutableStateOf(false)
    private var selectedBranchIdState by mutableIntStateOf(0)
    private var selectedBranchNameState by mutableStateOf("")
    private var branchOptionsState by mutableStateOf<List<BranchOption>>(emptyList())

    private var totalValueUsdState by mutableDoubleStateOf(0.0)
    private var totalItemsState by mutableIntStateOf(0)
    private var totalWeightState by mutableDoubleStateOf(0.0)

    private var matchedItemsState by mutableIntStateOf(0)
    private var missingItemsState by mutableIntStateOf(0)
    private var extraItemsState by mutableIntStateOf(0)
    private var pendingSessionsState by mutableIntStateOf(0)

    private var hasRecordedAuditState by mutableStateOf(false)
    private var lastAuditCodeState by mutableStateOf("")
    private var lastAuditDateState by mutableStateOf("")
    private var matchRatePercentState by mutableStateOf<Float?>(null)

    private fun checkIsArabic(): Boolean {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    InventoryDashboardScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isArabic = checkIsArabic()

        context?.let { ctx ->
            apiClient = MiraApiClient.getInstance(ctx)
            sessionManager = SessionManager(ctx)
            permissionManager = PermissionManager.getInstance(ctx)
            branchesManager = MiraBranchesManager.getInstance(ctx)
            priceManager = MiraPriceManager.getInstance(ctx)
        }

        setupBranchFilter()
        fetchInventoryDashboardData()
    }

    override fun onResume() {
        super.onResume()
        registerRefreshReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterRefreshReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterRefreshReceiver()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!networkExecutor.isShutdown) {
            networkExecutor.shutdownNow()
        }
    }

    private fun registerRefreshReceiver() {
        try {
            val ctx = context ?: return
            if (isReceiverRegistered) return

            refreshReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null || !isAdded) return
                    val action = intent.action
                    if ("com.example.uhf.REFRESH_INVENTORY" == action ||
                        "com.example.uhf.REFRESH_DASHBOARD" == action
                    ) {
                        mainHandler.post {
                            if (isAdded && isResumed && !isDetached) {
                                fetchInventoryDashboardData()
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction("com.example.uhf.REFRESH_INVENTORY")
                addAction("com.example.uhf.REFRESH_DASHBOARD")
            }

            ContextCompat.registerReceiver(
                ctx,
                refreshReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerRefreshReceiver error: ${e.message}")
        }
    }

    private fun unregisterRefreshReceiver() {
        try {
            val ctx = context
            if (ctx != null && isReceiverRegistered && refreshReceiver != null) {
                ctx.unregisterReceiver(refreshReceiver)
                isReceiverRegistered = false
            }
        } catch (_: Exception) {
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Branch & Network Logic
    // ═══════════════════════════════════════════════════════════════════

    private fun setupBranchFilter() {
        val bm = branchesManager
        val sessionBranch = sessionManager?.branchId ?: 0
        val activeBranch = bm?.activeBranchId?.takeIf { it > 0 } ?: sessionBranch
        val canViewAll = permissionManager?.canViewAllBranches() == true

        if (canViewAll) {
            canViewAllBranchesState = true
            selectedBranchIdState = activeBranch
            val options = ArrayList<BranchOption>()
            options.add(BranchOption(0, if (isArabic) "جميع الفروع" else "All Branches"))
            bm?.allBranches?.forEach { b ->
                options.add(BranchOption(b.id, b.name ?: "#${b.id}"))
            }
            branchOptionsState = options
            selectedBranchNameState = options.firstOrNull { it.id == activeBranch }?.name
                ?: (if (isArabic) "جميع الفروع" else "All Branches")
        } else {
            canViewAllBranchesState = false
            selectedBranchIdState = activeBranch
            selectedBranchNameState = bm?.activeBranchDisplayName
                ?: (if (isArabic) "الفرع الحالي" else "Current Branch")
            branchOptionsState = emptyList()
        }
    }

    private fun onBranchSelected(option: BranchOption) {
        selectedBranchIdState = option.id
        selectedBranchNameState = option.name
        if (option.id > 0) {
            runCatching {
                val bm = branchesManager ?: return@runCatching
                val singleArgMethod = bm.javaClass.methods.firstOrNull {
                    it.name == "setActiveBranch" && it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Integer::class.java)
                }
                singleArgMethod?.invoke(bm, option.id)
            }
        }
        fetchInventoryDashboardData()
    }

    private fun fetchInventoryDashboardData() {
        if (!isAdded || isDetached || networkExecutor.isShutdown) return

        val requestId = fetchGeneration.incrementAndGet()
        val branchId = selectedBranchIdState
        isLoadingState = true
        errorMessageState = null

        networkExecutor.execute {
            try {
                val client = apiClient ?: context?.let { MiraApiClient.getInstance(it) }
                if (client == null) {
                    postErrorIfCurrent(
                        requestId = requestId,
                        message = if (isArabic) "خطأ في تهيئة الاتصال" else "Client initialization error"
                    )
                    return@execute
                }

                val response = client.getBranchInventoryStats(branchId)

                if (response == null || !response.isSuccess || response.data == null) {
                    Log.e(TAG, "Failed to fetch inventory stats: ${response?.message}")
                    postErrorIfCurrent(
                        requestId = requestId,
                        message = response?.message?.takeIf { it.isNotBlank() }
                            ?: (if (isArabic) "تعذر جلب إحصائيات الجرد من السيرفر" else "Failed to load inventory stats")
                    )
                    return@execute
                }

                val root = response.data!!
                val data = root.optJSONObject("data") ?: root

                val totalValue = firstPositiveDouble(
                    data, null,
                    "total_value", "total_value_usd", "inventory_value", "estimated_value"
                )
                val totalItems = firstNonNegativeInt(
                    data,
                    "total_items", "items_count", "catalog_count", "expected_count"
                )
                val totalWeight = firstPositiveDouble(
                    data, null,
                    "total_weight", "total_net_weight", "total_weight_grams", "net_weight"
                )

                val lastAuditObj = data.optJSONObject("last_audit")
                    ?: data.optJSONObject("latest_session")
                    ?: root.optJSONObject("last_audit")

                val rawMissing = firstNullableInt(
                    lastAuditObj, data,
                    listOf("missing_items", "missing_count")
                )
                val rawMatched = firstNullableInt(
                    lastAuditObj, data,
                    listOf("matched_items", "matched_count", "found_count")
                )
                val rawExtra = firstNullableInt(
                    lastAuditObj, data,
                    listOf("extra_items", "uncatalogued_count", "extra_count")
                )
                val pending = firstNonNegativeInt(
                    data,
                    "pending_sessions", "open_sessions", "draft_sessions"
                )

                val auditCode = firstNonBlank(
                    lastAuditObj, data,
                    listOf("session_code", "audit_code", "report_number", "session_id")
                )
                val auditDate = firstNonBlank(
                    lastAuditObj, data,
                    listOf("audited_at", "completed_at", "last_audit_date", "created_at", "date")
                )

                val hasExplicitAudit = lastAuditObj != null ||
                    rawMatched != null ||
                    rawMissing != null ||
                    rawExtra != null ||
                    auditCode.isNotBlank() ||
                    auditDate.isNotBlank()

                val matched = rawMatched ?: 0
                val missing = rawMissing ?: 0
                val extra = rawExtra ?: 0

                val serverRate = firstPositiveDouble(
                    lastAuditObj, data,
                    "match_rate", "match_rate_percent", "accuracy_percent"
                )

                val computedRate: Float? = when {
                    serverRate > 0.0 -> serverRate.toFloat().coerceIn(0f, 100f)
                    hasExplicitAudit && (matched + missing) > 0 ->
                        ((matched.toFloat() / (matched + missing).toFloat()) * 100f).coerceIn(0f, 100f)
                    hasExplicitAudit && matched > 0 && missing == 0 -> 100f
                    else -> null
                }

                val snapshot = DashboardSnapshot(
                    totalValueUsd = totalValue,
                    totalItems = totalItems,
                    totalWeightGrams = totalWeight,
                    matchedItems = matched,
                    missingItems = missing,
                    extraItems = extra,
                    pendingSessions = pending,
                    hasRecordedAudit = hasExplicitAudit,
                    lastAuditCode = auditCode,
                    lastAuditDate = auditDate,
                    matchRatePercent = computedRate
                )

                mainHandler.post {
                    if (!isAdded || isDetached || requestId != fetchGeneration.get()) return@post
                    isLoadingState = false
                    errorMessageState = null

                    totalValueUsdState = snapshot.totalValueUsd
                    totalItemsState = snapshot.totalItems
                    totalWeightState = snapshot.totalWeightGrams

                    matchedItemsState = snapshot.matchedItems
                    missingItemsState = snapshot.missingItems
                    extraItemsState = snapshot.extraItems
                    pendingSessionsState = snapshot.pendingSessions

                    hasRecordedAuditState = snapshot.hasRecordedAudit
                    lastAuditCodeState = snapshot.lastAuditCode
                    lastAuditDateState = snapshot.lastAuditDate
                    matchRatePercentState = snapshot.matchRatePercent
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching inventory data", e)
                postErrorIfCurrent(
                    requestId = requestId,
                    message = if (isArabic) "خطأ في الاتصال بالشبكة" else "Network error"
                )
            }
        }
    }

    private fun postErrorIfCurrent(requestId: Int, message: String) {
        mainHandler.post {
            if (!isAdded || isDetached || requestId != fetchGeneration.get()) return@post
            isLoadingState = false
            errorMessageState = message
        }
    }

    private fun firstPositiveDouble(primary: JSONObject?, secondary: JSONObject?, vararg keys: String): Double {
        for (obj in listOf(primary, secondary)) {
            if (obj == null) continue
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    val v = obj.optDouble(k, 0.0)
                    if (!v.isNaN() && !v.isInfinite() && v > 0.0) return v
                }
            }
        }
        return 0.0
    }

    private fun firstNonNegativeInt(obj: JSONObject?, vararg keys: String): Int {
        if (obj == null) return 0
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.optInt(k, -1)
                if (v >= 0) return v
            }
        }
        return 0
    }

    private fun firstNullableInt(primary: JSONObject?, secondary: JSONObject?, keys: List<String>): Int? {
        for (obj in listOf(primary, secondary)) {
            if (obj == null) continue
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    val v = obj.optInt(k, -1)
                    if (v >= 0) return v
                }
            }
        }
        return null
    }

    private fun firstNonBlank(primary: JSONObject?, secondary: JSONObject?, keys: List<String>): String {
        for (obj in listOf(primary, secondary)) {
            if (obj == null) continue
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    val v = obj.optString(k, "").trim()
                    if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) {
                        return v
                    }
                }
            }
        }
        return ""
    }

    private fun startNewInventorySession() {
        if (!isAdded || isDetached) return
        try {
            val act = activity
            if (act is UHFMainActivity) {
                act.loadFragment(MiraInventoryProFragment(), "Inventory")
            } else {
                parentFragmentManager.beginTransaction()
                    .replace(android.R.id.content, MiraInventoryProFragment(), "Inventory")
                    .addToBackStack("Inventory")
                    .commitAllowingStateLoss()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening MiraInventoryProFragment", e)
            context?.let {
                Toast.makeText(
                    it,
                    if (isArabic) "تعذر فتح شاشة الجرد" else "Unable to open inventory scanner",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun formatInventoryValue(usdAmount: Double): Pair<String, String> {
        val defaultFormatted = try {
            priceManager?.formatPriceDefault(usdAmount)
        } catch (_: Exception) {
            null
        }
        val usdFormatted = String.format(Locale.US, "$%,.2f", usdAmount)

        return if (!defaultFormatted.isNullOrBlank() && !defaultFormatted.contains("$")) {
            defaultFormatted to usdFormatted
        } else {
            val rate = try {
                priceManager?.getExchangeRate("YER")?.takeIf { it > 1.0 } ?: 530.0
            } catch (_: Exception) {
                530.0
            }
            String.format(Locale.US, "%,.0f YER", usdAmount * rate) to usdFormatted
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Jetpack Compose UI
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun InventoryDashboardScreen() {
        val colors = MiraTheme.colors
        val isAr = isArabic
        var branchMenuExpanded by remember { mutableStateOf(false) }

        val (primaryValueStr, secondaryUsdStr) = formatInventoryValue(totalValueUsdState)

        Scaffold(
            containerColor = colors.backgroundTop,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { startNewInventorySession() },
                    containerColor = colors.goldPrimary,
                    contentColor = Color(0xFF0D1B2A),
                    shape = RoundedCornerShape(16.dp),
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    text = {
                        Text(
                            text = if (isAr) "بدء جرد RFID جديد" else "Start New RFID Audit",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.backgroundTop, colors.backgroundMid, colors.backgroundTop)
                        )
                    )
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. الشريط العلوي (Header + Branch Selector + Refresh)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Inventory,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isAr) "لوحة تحكم الجرد" else "Inventory Dashboard",
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
                            if (canViewAllBranchesState) {
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
                                                    onBranchSelected(branch)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            val infiniteTransition = rememberInfiniteTransition(label = "inv_spin")
                            val spinAngle by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(900, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "inv_spin_angle"
                            )

                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable(enabled = !isLoadingState) {
                                        fetchInventoryDashboardData()
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
                                            .rotate(if (isLoadingState) spinAngle else 0f)
                                    )
                                }
                            }
                        }
                    }

                    // تنبيه خطأ الشبكة إن وجد
                    errorMessageState?.let { err ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFCA5A5),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { fetchInventoryDashboardData() }) {
                                    Text(
                                        text = if (isAr) "إعادة المحاولة" else "Retry",
                                        color = colors.goldPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 2. البطاقة الرئيسية لمخزون الفرع المسجّل (قيمة + عدد + وزن)
                    InventoryHeroValueCard(
                        isArabic = isAr,
                        primaryValue = primaryValueStr,
                        secondaryUsd = secondaryUsdStr,
                        totalItems = totalItemsState,
                        totalWeight = totalWeightState,
                        isLoading = isLoadingState
                    )

                    // 3. بطاقة ملخص آخر جلسة جرد معتمدة + تنبيه المفقودات
                    LastAuditSummaryBanner(
                        isArabic = isAr,
                        hasRecordedAudit = hasRecordedAuditState,
                        auditCode = lastAuditCodeState,
                        auditDate = lastAuditDateState,
                        missingCount = missingItemsState,
                        pendingCount = pendingSessionsState
                    )

                    // 4. شبكة مؤشرات المطابقة (2x2 Grid)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AuditStatTile(
                            modifier = Modifier.weight(1f),
                            title = if (isAr) "قطع مطابقة" else "Matched Items",
                            count = matchedItemsState,
                            accentColor = Color(0xFF10B981),
                            icon = Icons.Rounded.CheckCircle
                        )
                        AuditStatTile(
                            modifier = Modifier.weight(1f),
                            title = if (isAr) "قطع مفقودة" else "Missing Items",
                            count = missingItemsState,
                            accentColor = Color(0xFFEF4444),
                            icon = Icons.Rounded.WarningAmber
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AuditStatTile(
                            modifier = Modifier.weight(1f),
                            title = if (isAr) "قطع زائدة / مخالفة" else "Extra / Uncatalogued",
                            count = extraItemsState,
                            accentColor = Color(0xFF3B82F6),
                            icon = Icons.Rounded.AddCircleOutline
                        )
                        AuditStatTile(
                            modifier = Modifier.weight(1f),
                            title = if (isAr) "جلسات معلقة" else "Pending Audits",
                            count = pendingSessionsState,
                            accentColor = Color(0xFFF59E0B),
                            icon = Icons.Rounded.PendingActions
                        )
                    }

                    // 5. الرسم البياني الدائري لتوزيع حالة آخر جرد
                    InventoryAuditDonutCard(
                        isArabic = isAr,
                        hasRecordedAudit = hasRecordedAuditState,
                        matched = matchedItemsState,
                        missing = missingItemsState,
                        extra = extraItemsState,
                        matchRatePercent = matchRatePercentState
                    )
                }
            }
        }
    }

    @Composable
    private fun InventoryHeroValueCard(
        isArabic: Boolean,
        primaryValue: String,
        secondaryUsd: String,
        totalItems: Int,
        totalWeight: Double,
        isLoading: Boolean
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.45f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.goldPrimary.copy(alpha = 0.18f),
                                Color(0xFF06B6D4).copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isArabic) "القيمة التقديرية لمخزون الفرع" else "Estimated Branch Stock Value",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isLoading && totalItems == 0) "..." else primaryValue,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.goldPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = secondaryUsd,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF10B981)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.goldPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Diamond,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.15f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Inventory2,
                                contentDescription = null,
                                tint = Color(0xFF06B6D4),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isArabic) "القطع المسجلة بالمخزون" else "Cataloged Pieces",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = String.format(Locale.US, "%,d", totalItems),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(colors.textSecondary.copy(alpha = 0.2f))
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Scale,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isArabic) "إجمالي الوزن الصافي" else "Total Net Weight",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                                Text(
                                    text = String.format(Locale.US, "%,.3f g", totalWeight),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LastAuditSummaryBanner(
        isArabic: Boolean,
        hasRecordedAudit: Boolean,
        auditCode: String,
        auditDate: String,
        missingCount: Int,
        pendingCount: Int
    ) {
        val colors = MiraTheme.colors
        val alertColor = when {
            missingCount > 0 -> Color(0xFFEF4444)
            pendingCount > 0 -> Color(0xFFF59E0B)
            hasRecordedAudit -> Color(0xFF10B981)
            else -> colors.goldPrimary
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = alertColor.copy(alpha = 0.11f)),
            border = BorderStroke(1.dp, alertColor.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (missingCount > 0) Icons.Rounded.WarningAmber else Icons.Rounded.FactCheck,
                    contentDescription = null,
                    tint = alertColor,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    val headerText = if (hasRecordedAudit) {
                        val codePart = if (auditCode.isNotBlank()) " • #$auditCode" else ""
                        val datePart = if (auditDate.isNotBlank()) " ($auditDate)" else ""
                        if (isArabic) "نتائج آخر جلسة جرد$codePart$datePart"
                        else "Latest Audit Session$codePart$datePart"
                    } else {
                        if (isArabic) "حالة الجرد: لا توجد جلسة جرد معتمدة بعد"
                        else "Audit Status: No committed audit session yet"
                    }

                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val detailText = when {
                        missingCount > 0 -> if (isArabic)
                            "تنبيه: يوجد $missingCount قطعة مفقودة تتطلب مراجعة أو تتبعاً بالرادار"
                        else
                            "Alert: $missingCount missing item(s) require review or radar lookup"
                        pendingCount > 0 -> if (isArabic)
                            "يوجد $pendingCount جلسة جرد معلقة بانتظار الإكمال أو الاعتماد"
                        else
                            "$pendingCount pending audit session(s) awaiting completion"
                        hasRecordedAudit -> if (isArabic)
                            "جميع القطع المشمولة في آخر جرد مطابقة دون نقص"
                        else
                            "All items in the latest audit match with zero missing pieces"
                        else -> if (isArabic)
                            "ابدأ جلسة جرد RFID لتسجيل المطابقة والمفقودات والوزن الفعلي"
                        else
                            "Start an RFID audit session to record matches, missing items, and weight"
                    }

                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }

    @Composable
    private fun AuditStatTile(
        modifier: Modifier = Modifier,
        title: String,
        count: Int,
        accentColor: Color,
        icon: ImageVector
    ) {
        val colors = MiraTheme.colors

        Card(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.32f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = String.format(Locale.US, "%,d", count),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun InventoryAuditDonutCard(
        isArabic: Boolean,
        hasRecordedAudit: Boolean,
        matched: Int,
        missing: Int,
        extra: Int,
        matchRatePercent: Float?
    ) {
        val colors = MiraTheme.colors
        val total = (matched + missing + extra).coerceAtLeast(0)

        val animProgress by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            label = "donut_anim"
        )

        val matchedColor = Color(0xFF10B981)
        val missingColor = Color(0xFFEF4444)
        val extraColor = Color(0xFF3B82F6)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PieChart,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isArabic) "تحليل حالة مطابقة آخر جرد" else "Latest Audit Status Breakdown",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                if (!hasRecordedAudit && total <= 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isArabic)
                                "📭 لا توجد بيانات جرد مسجلة بعد — ابدأ جلسة جرد جديدة لتوليد التحليل"
                            else
                                "📭 No recorded audit data yet — start a new audit session to view breakdown",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    val rateText = matchRatePercent?.let {
                        String.format(Locale.US, "%.1f%%", it)
                    } ?: "—"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Box(
                            modifier = Modifier.size(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 22.dp.toPx()
                                val diameter = size.minDimension - strokeWidth
                                val topLeft = Offset(
                                    (size.width - diameter) / 2f,
                                    (size.height - diameter) / 2f
                                )
                                val arcSize = Size(diameter, diameter)

                                if (total <= 0) {
                                    drawArc(
                                        color = colors.textSecondary.copy(alpha = 0.18f),
                                        startAngle = 0f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                    )
                                } else {
                                    val segments = listOf(
                                        matched to matchedColor,
                                        missing to missingColor,
                                        extra to extraColor
                                    ).filter { it.first > 0 }

                                    var startAngle = -90f
                                    val gap = if (segments.size > 1) 3f else 0f

                                    segments.forEach { (value, segColor) ->
                                        val sweep = ((value.toFloat() / total.toFloat()) * 360f * animProgress) - gap
                                        if (sweep > 0f) {
                                            drawArc(
                                                color = segColor,
                                                startAngle = startAngle + (gap / 2f),
                                                sweepAngle = sweep,
                                                useCenter = false,
                                                topLeft = topLeft,
                                                size = arcSize,
                                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                            )
                                        }
                                        startAngle += (value.toFloat() / total.toFloat()) * 360f * animProgress
                                    }
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = rateText,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (missing > 0) Color(0xFFF59E0B) else matchedColor
                                )
                                Text(
                                    text = if (isArabic) "نسبة المطابقة" else "Match Rate",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            LegendItemRow(
                                label = if (isArabic) "مطابق" else "Matched",
                                count = matched,
                                total = total,
                                color = matchedColor
                            )
                            LegendItemRow(
                                label = if (isArabic) "مفقود" else "Missing",
                                count = missing,
                                total = total,
                                color = missingColor
                            )
                            LegendItemRow(
                                label = if (isArabic) "زائد / مخالف" else "Extra",
                                count = extra,
                                total = total,
                                color = extraColor
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LegendItemRow(
        label: String,
        count: Int,
        total: Int,
        color: Color
    ) {
        val colors = MiraTheme.colors
        val pct = if (total > 0) (count * 100f) / total else 0f

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Text(
                    text = String.format(Locale.US, "%,d (%.1f%%)", count, pct),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}
