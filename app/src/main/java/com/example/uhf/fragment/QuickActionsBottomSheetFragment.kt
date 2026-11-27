package com.example.uhf.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.license.LicenseStatusActivity
import com.example.uhf.manager.MiraQuickAccessSettings
import com.example.uhf.manager.PermissionManager
import com.example.uhf.navigation.FragmentFactory
import com.example.uhf.navigation.NavigationManager
import com.example.uhf.tools.MiraLanguageHelper
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class QuickActionsBottomSheetFragment : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "QuickActionsSheet"
        private const val NAV_DELAY_MS = 160L
        private const val GRID_COLUMNS = 4

        // لوحة ألوان هوية MIRA ID™ الملكية الموحدة
        private val NavyObsidian = Color(0xFF071224)
        private val NavyDeep = Color(0xFF0A192F)
        private val NavyElevated = Color(0xFF11223D)
        private val NavyTile = Color(0xFF162B4D)

        private val GoldBright = Color(0xFFF7DF8B)
        private val GoldPrimary = Color(0xFFD4AF37)
        private val GoldBronze = Color(0xFF9C7621)
        private val CyanAccent = Color(0xFF38BDF8)
    }

    private enum class ActionCategory {
        OPERATIONS,
        SCRAP_AND_PURCHASES,
        MANAGEMENT
    }

    private data class QuickMenuItemConfig(
        val actionKey: String,
        val navTag: String,
        val titleAr: String,
        val titleEn: String,
        val icon: ImageVector,
        val accent: Color,
        val category: ActionCategory
    )

    private var isArabic: Boolean = true
    private var permissionManager: PermissionManager? = null
    private var quickAccessSettings: MiraQuickAccessSettings? = null
    private val navHandler = Handler(Looper.getMainLooper())
    private var isNavigating: Boolean = false
    private var permissionsRevision by mutableIntStateOf(0)

    private val permissionsListener = PermissionManager.OnPermissionsChangedListener {
        navHandler.post {
            if (isAdded) {
                permissionsRevision++
            }
        }
    }

    // مصدر الحقيقة الموحّد للعمليات الميدانية والإدارية (دون استثناءات تجاوز الصلاحيات)
    private val menuConfigs = listOf(
        // 1. العمليات والحركة
        QuickMenuItemConfig("transfers", NavigationManager.TAG_TRANSFERS, "التحويلات", "Transfers", Icons.Rounded.SwapHoriz, Color(0xFF06B6D4), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("gold_conversion", NavigationManager.TAG_GOLD_CONVERSION, "أمر صياغة", "Crafting", Icons.Rounded.PrecisionManufacturing, GoldPrimary, ActionCategory.OPERATIONS),
        QuickMenuItemConfig("purchase_invoice", NavigationManager.TAG_PURCHASE_INVOICE, "فاتورة شراء", "Purchase Inv.", Icons.Rounded.ReceiptLong, Color(0xFF10B981), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("sales_history", NavigationManager.TAG_SALES_HISTORY, "المبيعات", "Sales Log", Icons.Rounded.PointOfSale, CyanAccent, ActionCategory.OPERATIONS),
        QuickMenuItemConfig("reports", NavigationManager.TAG_REPORTS, "التقارير", "Reports", Icons.Rounded.Analytics, Color(0xFF818CF8), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("vouchers", NavigationManager.TAG_VOUCHERS, "السندات", "Vouchers", Icons.Rounded.RequestQuote, Color(0xFFF59E0B), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("cash_reconciliation", NavigationManager.TAG_CASH_RECONCILIATION, "الصندوق", "Cash Recon.", Icons.Rounded.FactCheck, Color(0xFF06B6D4), ActionCategory.OPERATIONS),

        // 2. الذهب الكسر والأسعار
        QuickMenuItemConfig("scrap_buy", NavigationManager.TAG_SCRAP_GOLD_BUY, "شراء كسر", "Scrap Buy", Icons.Rounded.Scale, Color(0xFFF59E0B), ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("scrap_batches", NavigationManager.TAG_SCRAP_BATCHES, "دفعات الكسر", "Scrap Batches", Icons.Rounded.Layers, Color(0xFF10B981), ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("scrap_aging", NavigationManager.TAG_SCRAP_AGING, "أعمار الكسر", "Scrap Aging", Icons.Rounded.HourglassBottom, GoldPrimary, ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("scrap_history", NavigationManager.TAG_SCRAP_HISTORY, "سجل الكسر", "Scrap Log", Icons.Rounded.History, CyanAccent, ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("gold_rates", NavigationManager.TAG_GOLD_RATES, "الذهب والعملات", "Gold Rates", Icons.Rounded.TrendingUp, GoldPrimary, ActionCategory.SCRAP_AND_PURCHASES),

        // 3. الإدارة والأجهزة والمراقبة
        QuickMenuItemConfig("parties", NavigationManager.TAG_PARTIES, "العملاء والموردين", "Parties", Icons.Rounded.Groups, Color(0xFF10B981), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("radar", NavigationManager.TAG_RADAR, "الرادار", "RFID Radar", Icons.Rounded.Radar, Color(0xFFEF4444), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("gate", NavigationManager.TAG_GATES, "البوابة", "Smart Gate", Icons.Rounded.SensorDoor, Color(0xFF06B6D4), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("rfid_write", NavigationManager.TAG_RFID_WRITE, "كتابة شريحة", "Write Tag", Icons.Rounded.Nfc, GoldPrimary, ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("reader_setup", NavigationManager.TAG_READER_SETUP, "إعداد القارئ", "Reader Setup", Icons.Rounded.SettingsRemote, CyanAccent, ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("admin_hub", NavigationManager.TAG_ADMIN_HUB, "مركز الإدارة", "Admin Hub", Icons.Rounded.AdminPanelSettings, Color(0xFFF59E0B), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("quick_access", NavigationManager.TAG_QUICK_ACCESS, "الوصول السريع", "Quick Access", Icons.Rounded.Tune, Color(0xFF818CF8), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("audit_logs", NavigationManager.TAG_AUDIT_LOGS, "سجل التدقيق", "Audit Logs", Icons.Rounded.Security, Color(0xFF94A3B8), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("server_config", NavigationManager.TAG_SERVER_CONFIG, "السيرفر", "Server Config", Icons.Rounded.Dns, Color(0xFF06B6D4), ActionCategory.MANAGEMENT)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val savedLang = MiraLanguageHelper.getSavedLanguage(ctx)
        isArabic = savedLang.equals("ar", ignoreCase = true) ||
            AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            Locale.getDefault().language == "ar"

        permissionManager = PermissionManager.getInstance(ctx).also {
            it.addListener(permissionsListener)
        }
        quickAccessSettings = MiraQuickAccessSettings.getInstance(ctx)
        isNavigating = false

        return ComposeView(ctx).apply {
            layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val composeDir = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides composeDir) {
                    MiraJewelryTheme {
                        QuickActionsSheetContent()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            dialog?.window?.navigationBarColor = 0xFF071224.toInt()
            val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                bottomSheet.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.maxHeight = (resources.displayMetrics.heightPixels * 0.92).toInt()
                bottomSheet.requestLayout()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStart error", e)
        }
    }

    override fun onDestroyView() {
        permissionManager?.removeListener(permissionsListener)
        navHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun isItemAllowed(item: QuickMenuItemConfig): Boolean {
        val pm = permissionManager ?: return false
        if (pm.isGateUser && item.navTag != NavigationManager.TAG_GATES) {
            return false
        }
        val hasPermission = pm.canAccessFragment(item.navTag)
        val qaAllowed = quickAccessSettings?.isActionEnabled(item.actionKey) ?: true
        return hasPermission && qaAllowed
    }

    private fun isStudioAllowed(): Boolean {
        val pm = permissionManager ?: return false
        if (pm.isGateUser) return false
        val hasPermission = pm.canAccessFragment(NavigationManager.TAG_IDENTITY_STUDIO)
        val qaAllowed = quickAccessSettings?.isActionEnabled("identity") ?: true
        return hasPermission && qaAllowed
    }

    private fun canSeeLicenseCard(): Boolean {
        val pm = permissionManager ?: return false
        return pm.isSystemAdmin || pm.isOrgAdmin || pm.isAdmin || pm.isManager
    }

    private fun canCustomizeQuickAccess(): Boolean {
        val pm = permissionManager ?: return false
        return pm.canManageQuickAccess() || pm.isAdmin || pm.isManager
    }

    private fun navigateToTag(navTag: String, fallback: Fragment? = null) {
        if (isNavigating) return
        performHaptic()

        val mainActivity = activity as? UHFMainActivity ?: run {
            dismissSafely()
            return
        }

        if (mainActivity.isNavigationLocked) {
            Toast.makeText(
                mainActivity,
                if (isArabic) "⚠️ التنقل مقفل حالياً" else "⚠️ Navigation is currently locked",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (mainActivity.isInventoryActive) {
            MaterialAlertDialogBuilder(mainActivity)
                .setTitle(if (isArabic) "⚠️ الجرد قيد التشغيل" else "⚠️ Inventory Active")
                .setMessage(
                    if (isArabic) {
                        "لا يمكن الانتقال أثناء عمل ماسح الجرد.\n\nهل تريد إيقاف الجرد والانتقال الآن؟"
                    } else {
                        "Cannot switch screens during active inventory scan.\n\nStop scan and proceed?"
                    }
                )
                .setPositiveButton(if (isArabic) "إيقاف والانتقال" else "Stop & Proceed") { _, _ ->
                    stopActiveScannerSafely(mainActivity)
                    executeNavigation(mainActivity, navTag, fallback)
                }
                .setNegativeButton(if (isArabic) "إلغاء" else "Cancel", null)
                .show()
            return
        }

        executeNavigation(mainActivity, navTag, fallback)
    }

    private fun stopActiveScannerSafely(mainActivity: UHFMainActivity) {
        try {
            val current = mainActivity.navigationManager?.currentFragment
            if (current is MiraInventoryProFragment) {
                current.stopScanning()
            } else if (current is MiraUHFReadTagFragment) {
                current.stopScanning()
            }
            mainActivity.stopUHFScan()
        } catch (e: Exception) {
            Log.w(TAG, "stopActiveScannerSafely error: ${e.message}")
        }
    }

    private fun executeNavigation(
        mainActivity: UHFMainActivity,
        navTag: String,
        fallback: Fragment?
    ) {
        val targetFragment = mainActivity.navigationManager?.createFragmentByTag(navTag)
            ?: FragmentFactory.createFragmentByTag(navTag)
            ?: fallback
            ?: run {
                dismissSafely()
                return
            }

        isNavigating = true
        dismissSafely()

        navHandler.postDelayed({
            if (!mainActivity.isFinishing && !mainActivity.isDestroyed) {
                try {
                    mainActivity.loadFragmentDirectly(targetFragment, navTag)
                } catch (e: Throwable) {
                    Log.e(TAG, "loadFragmentDirectly error for $navTag", e)
                }
            }
        }, NAV_DELAY_MS)
    }

    private fun openLicenseStatus() {
        if (isNavigating) return
        isNavigating = true
        performHaptic()
        try {
            startActivity(Intent(requireContext(), LicenseStatusActivity::class.java))
            dismissSafely()
        } catch (e: Exception) {
            isNavigating = false
            Log.e(TAG, "Failed to open LicenseStatusActivity", e)
        }
    }

    private fun dismissSafely() {
        try {
            if (isAdded) {
                dismissAllowingStateLoss()
            }
        } catch (_: Exception) {
        }
    }

    private fun performHaptic() {
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  واجهة Jetpack Compose الملكية (MIRA ID™ Royal Command Sheet)
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun QuickActionsSheetContent() {
        val colors = MiraTheme.colors
        val rev = permissionsRevision
        var searchQuery by remember { mutableStateOf("") }
        var showSearchBar by remember { mutableStateOf(false) }

        val allowedItems = remember(rev) { menuConfigs.filter { isItemAllowed(it) } }
        val filteredItems = remember(allowedItems, searchQuery) {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) allowedItems
            else allowedItems.filter {
                it.titleAr.lowercase(Locale.ROOT).contains(q) ||
                    it.titleEn.lowercase(Locale.ROOT).contains(q) ||
                    it.actionKey.lowercase(Locale.ROOT).contains(q)
            }
        }

        val opsItems = remember(filteredItems) {
            filteredItems.filter { it.category == ActionCategory.OPERATIONS }
        }
        val scrapItems = remember(filteredItems) {
            filteredItems.filter { it.category == ActionCategory.SCRAP_AND_PURCHASES }
        }
        val mgmtItems = remember(filteredItems) {
            filteredItems.filter { it.category == ActionCategory.MANAGEMENT }
        }

        val showStudio = remember(rev, searchQuery) {
            if (!isStudioAllowed()) false
            else if (searchQuery.isBlank()) true
            else {
                val q = searchQuery.trim().lowercase(Locale.ROOT)
                "استوديو التكويد".contains(q) ||
                    "encoding studio".contains(q) ||
                    "identity".contains(q) ||
                    "rfid".contains(q) ||
                    "gs1".contains(q)
            }
        }

        val showLicense = remember(rev) { canSeeLicenseCard() }
        val showCustomizeBtn = remember(rev) { canCustomizeQuickAccess() }

        val royalTopBorder = remember {
            Brush.horizontalGradient(
                listOf(
                    GoldBronze.copy(alpha = 0.45f),
                    GoldBright.copy(alpha = 0.85f),
                    GoldPrimary,
                    GoldBright.copy(alpha = 0.85f),
                    GoldBronze.copy(alpha = 0.45f)
                )
            )
        }

        val royalSheetBg = remember {
            Brush.verticalGradient(
                listOf(
                    NavyElevated,
                    NavyDeep,
                    NavyObsidian
                )
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)),
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            color = NavyObsidian,
            border = BorderStroke(1.2.dp, royalTopBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(royalSheetBg)
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // مقبض السحب الذهبي
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(48.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(GoldBronze, GoldBright, GoldBronze)
                            )
                        )
                )

                // شريط العنوان الملكي
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(GoldBright, GoldPrimary, GoldBronze)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Diamond,
                                contentDescription = null,
                                tint = NavyObsidian,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isArabic) "مركز القيادة السريع" else "MIRA Command Hub",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFF8FAFC)
                            )
                            Text(
                                text = if (isArabic) "وصول فوري للعمليات والأنظمة الذكية" else "Instant access to enterprise modules",
                                style = MaterialTheme.typography.labelSmall,
                                color = GoldBright.copy(alpha = 0.85f)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // زر البحث السريع
                        RoyalHeaderCircleButton(
                            icon = Icons.Rounded.Search,
                            active = showSearchBar,
                            tint = if (showSearchBar) CyanAccent else GoldBright,
                            onClick = {
                                performHaptic()
                                showSearchBar = !showSearchBar
                                if (!showSearchBar) searchQuery = ""
                            }
                        )

                        // زر تخصيص الأزرار السريعة للمديرين
                        if (showCustomizeBtn) {
                            RoyalHeaderCircleButton(
                                icon = Icons.Rounded.Tune,
                                active = false,
                                tint = GoldPrimary,
                                onClick = {
                                    navigateToTag(
                                        NavigationManager.TAG_QUICK_ACCESS,
                                        MiraQuickAccessSettingsFragment()
                                    )
                                }
                            )
                        }

                        // زر الإغلاق
                        RoyalHeaderCircleButton(
                            icon = Icons.Rounded.Close,
                            active = false,
                            tint = Color(0xFF94A3B8),
                            onClick = {
                                performHaptic()
                                dismissSafely()
                            }
                        )
                    }
                }

                // شريط البحث الفوري القابل للطي
                AnimatedVisibility(
                    visible = showSearchBar,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Color(0xFFF8FAFC)
                        ),
                        placeholder = {
                            Text(
                                text = if (isArabic) "ابحث عن خدمة أو وحدة..." else "Search action or module...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = GoldPrimary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = null, tint = Color(0xFF94A3B8))
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = NavyTile,
                            unfocusedContainerColor = NavyElevated,
                            focusedBorderColor = GoldPrimary,
                            unfocusedBorderColor = GoldPrimary.copy(alpha = 0.35f),
                            cursorColor = GoldBright
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // البطاقة الرئيسية المميزة: استوديو التكويد
                if (showStudio) {
                    val studioInteraction = remember { MutableInteractionSource() }
                    val studioPressed by studioInteraction.collectIsPressedAsState()
                    val studioScale by animateFloatAsState(
                        targetValue = if (studioPressed) 0.98f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "studioScale"
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(studioScale)
                            .shadow(
                                elevation = 12.dp,
                                shape = RoundedCornerShape(22.dp),
                                ambientColor = NavyObsidian,
                                spotColor = GoldPrimary.copy(alpha = 0.35f)
                            )
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(
                                interactionSource = studioInteraction,
                                indication = null
                            ) {
                                navigateToTag(
                                    NavigationManager.TAG_IDENTITY_STUDIO,
                                    MiraIdentityStudioFragment()
                                )
                            },
                        shape = RoundedCornerShape(22.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.4.dp,
                            Brush.horizontalGradient(
                                listOf(GoldBright, GoldPrimary, CyanAccent.copy(alpha = 0.75f))
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            GoldPrimary.copy(alpha = 0.25f),
                                            NavyTile,
                                            NavyElevated
                                        )
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(GoldBright, GoldPrimary)
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.QrCode2,
                                        contentDescription = null,
                                        tint = NavyObsidian,
                                        modifier = Modifier.size(27.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isArabic) "استوديو التكويد" else "Encoding Studio",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFFF8FAFC)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = GoldPrimary.copy(alpha = 0.22f),
                                            border = BorderStroke(0.8.dp, GoldPrimary.copy(alpha = 0.6f))
                                        ) {
                                            Text(
                                                text = "RFID / GS1",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = GoldBright,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (isArabic) "تكويد وربط شريحة قطعة جديدة بسهولة" else "Encode and link a new item smart tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFCBD5E1),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(GoldPrimary.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isArabic) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = GoldBright,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (opsItems.isNotEmpty()) {
                    SectionBlock(
                        title = if (isArabic) "العمليات والحركة" else "Operations",
                        badgeCount = opsItems.size,
                        accent = CyanAccent,
                        items = opsItems,
                        isArabic = isArabic,
                        onClick = { navigateToTag(it.navTag) }
                    )
                }

                if (scrapItems.isNotEmpty()) {
                    SectionBlock(
                        title = if (isArabic) "الذهب الكسر والأسعار" else "Scrap & Gold Rates",
                        badgeCount = scrapItems.size,
                        accent = GoldPrimary,
                        items = scrapItems,
                        isArabic = isArabic,
                        onClick = { navigateToTag(it.navTag) }
                    )
                }

                if (mgmtItems.isNotEmpty()) {
                    SectionBlock(
                        title = if (isArabic) "الإدارة والأجهزة والمراقبة" else "Management & Devices",
                        badgeCount = mgmtItems.size,
                        accent = Color(0xFF10B981),
                        items = mgmtItems,
                        isArabic = isArabic,
                        onClick = { navigateToTag(it.navTag) }
                    )
                }

                if (!showStudio && opsItems.isEmpty() && scrapItems.isEmpty() && mgmtItems.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = NavyElevated,
                        border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SearchOff,
                                contentDescription = null,
                                tint = GoldPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = if (isArabic) {
                                    "لا توجد نتائج مطابقة أو خدمات مفعّلة لصلاحياتك."
                                } else {
                                    "No matching or enabled actions found."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // بطاقة حالة الترخيص الملكية
                if (showLicense) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { openLicenseStatus() },
                        shape = RoundedCornerShape(18.dp),
                        color = NavyTile,
                        border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
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
                                        .background(GoldPrimary.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VerifiedUser,
                                        contentDescription = null,
                                        tint = GoldBright,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (isArabic) "حالة الترخيص" else "License Status",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFF8FAFC)
                                    )
                                    Text(
                                        text = if (isArabic) "عرض تفاصيل ترخيص MIRA ID™ المعتمد" else "View verified MIRA ID™ license details",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GoldBright.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            Icon(
                                imageVector = if (isArabic) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = GoldBright
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RoyalHeaderCircleButton(
        icon: ImageVector,
        active: Boolean,
        tint: Color,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = if (active) tint.copy(alpha = 0.2f) else NavyTile,
            border = BorderStroke(
                1.dp,
                if (active) tint else GoldPrimary.copy(alpha = 0.30f)
            )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    @Composable
    private fun SectionBlock(
        title: String,
        badgeCount: Int,
        accent: Color,
        items: List<QuickMenuItemConfig>,
        isArabic: Boolean,
        onClick: (QuickMenuItemConfig) -> Unit
    ) {
        val rows = items.chunked(GRID_COLUMNS)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(14.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = GoldBright
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.16f),
                    border = BorderStroke(0.8.dp, accent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "$badgeCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { item ->
                        QuickActionTile(
                            item = item,
                            isArabic = isArabic,
                            onClick = { onClick(item) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(GRID_COLUMNS - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickActionTile(
        item: QuickMenuItemConfig,
        isArabic: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()

        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.94f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "tileScale"
        )
        val borderColor by animateColorAsState(
            targetValue = if (isPressed) GoldBright else item.accent.copy(alpha = 0.32f),
            label = "tileBorder"
        )

        Surface(
            modifier = modifier
                .height(82.dp)
                .scale(scale)
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = RoundedCornerShape(16.dp),
            color = NavyTile,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                item.accent.copy(alpha = 0.10f),
                                NavyElevated
                            )
                        )
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(35.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    item.accent.copy(alpha = 0.25f),
                                    item.accent.copy(alpha = 0.10f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = item.accent,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isArabic) item.titleAr else item.titleEn,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
