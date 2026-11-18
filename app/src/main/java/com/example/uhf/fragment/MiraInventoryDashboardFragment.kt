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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import java.util.Locale
import java.util.concurrent.Executors

class MiraInventoryDashboardFragment : Fragment() {

    companion object {
        private const val TAG = "InvDashboardFragment"
    }

    private data class BranchOption(
        val id: Int,
        val name: String
    )

    private var apiClient: MiraApiClient? = null
    private var sessionManager: SessionManager? = null
    private var permissionManager: PermissionManager? = null
    private var branchesManager: MiraBranchesManager? = null
    private var priceManager: MiraPriceManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())
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
                            if (isAdded && isResumed) {
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

    private fun fetchInventoryDashboardData() {
        if (!isAdded || networkExecutor.isShutdown) return

        val branchId = selectedBranchIdState
        isLoadingState = true
        errorMessageState = null

        networkExecutor.execute {
            try {
                val client = apiClient ?: context?.let { MiraApiClient.getInstance(it) }
                if (client == null) {
                    mainHandler.post {
                        if (!isAdded) return@post
                        isLoadingState = false
                        errorMessageState = if (isArabic) "خطأ في تهيئة الاتصال" else "Client initialization error"
                    }
                    return@execute
                }

                val response = client.getBranchInventoryStats(branchId)

                if (response != null && response.isSuccess && response.data != null) {
                    val data = response.data!!.optJSONObject("data") ?: response.data!!

                    val totalValue = data.optDouble("total_value", data.optDouble("total_value_usd", 0.0))
                    val totalItems = data.optInt("total_items", data.optInt("items_count", 0))
                    val totalWeight = data.optDouble("total_weight", data.optDouble("total_net_weight", 0.0))

                    val missing = data.optInt("missing_items", 0)
                    val matched = data.optInt("matched_items", totalItems)
                    val extra = data.optInt("extra_items", 0)
                    val pending = data.optInt("pending_sessions", 0)

                    mainHandler.post {
                        if (!isAdded) return@post
                        isLoadingState = false
                        errorMessageState = null

                        totalValueUsdState = totalValue
                        totalItemsState = totalItems
                        totalWeightState = totalWeight

                        matchedItemsState = matched
                        missingItemsState = missing
                        extraItemsState = extra
                        pendingSessionsState = pending
                    }
                } else {
                    Log.e(TAG, "Failed to fetch inventory stats: ${response?.message}")
                    mainHandler.post {
                        if (!isAdded) return@post
                        isLoadingState = false
                        errorMessageState = response?.message?.takeIf { it.isNotBlank() }
                            ?: (if (isArabic) "تعذر جلب إحصائيات الجرد من السيرفر" else "Failed to load inventory stats")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching inventory data", e)
                mainHandler.post {
                    if (!isAdded) return@post
                    isLoadingState = false
                    errorMessageState = if (isArabic) "خطأ في الاتصال بالشبكة" else "Network error"
                }
            }
        }
    }

    private fun startNewInventorySession() {
        try {
            val act = activity
            if (act is UHFMainActivity) {
                act.loadFragment(MiraInventoryProFragment(), "Inventory")
            } else if (isAdded) {
                parentFragmentManager.beginTransaction()
                    .replace(android.R.id.content, MiraInventoryProFragment(), "Inventory")
                    .addToBackStack("Inventory")
                    .commit()
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
                                                    selectedBranchIdState = branch.id
                                                    selectedBranchNameState = branch.name
                                                    fetchInventoryDashboardData()
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

                    // تنبيه خطأ إن وجد
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

                    // 2. البطاقة الرئيسية لقيمة وأوزان المخزون (بدون أي Drawable خارجي يسبب InflateException)
                    InventoryHeroValueCard(
                        isArabic = isAr,
                        primaryValue = primaryValueStr,
                        secondaryUsd = secondaryUsdStr,
                        totalItems = totalItemsState,
                        totalWeight = totalWeightState,
                        isLoading = isLoadingState
                    )

                    // 3. شبكة مؤشرات المطابقة (2x2 Grid)
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
                            title = if (isAr) "قطع زائدة" else "Extra Items",
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

                    // 4. الرسم البياني الدائري لتوزيع حالة الجرد (Donut Chart)
                    InventoryAuditDonutCard(
                        isArabic = isAr,
                        matched = matchedItemsState,
                        missing = missingItemsState,
                        extra = extraItemsState
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
                                text = if (isArabic) "القيمة التقديرية للمخزون" else "Estimated Stock Value",
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

                    Divider(color = colors.textSecondary.copy(alpha = 0.15f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // إجمالي القطع
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
                                    text = if (isArabic) "إجمالي القطع" else "Total Pieces",
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

                        // إجمالي الوزن
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
        matched: Int,
        missing: Int,
        extra: Int
    ) {
        val colors = MiraTheme.colors
        val total = matched + missing + extra
        val matchPercent = if (total > 0) (matched * 100f) / total else 0f

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
                        text = if (isArabic) "تحليل حالة مطابقة الجرد" else "Inventory Audit Status Breakdown",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                if (total <= 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isArabic) "📭 لا توجد بيانات جرد مسجلة بعد" else "📭 No previous audit data available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Donut Chart
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

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format(Locale.US, "%.0f%%", matchPercent),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = matchedColor
                                )
                                Text(
                                    text = if (isArabic) "نسبة المطابقة" else "Matched",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        // Legend
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
                                label = if (isArabic) "زائد" else "Extra",
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
