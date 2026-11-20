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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.uhf.activity.UHFMainActivity
import com.example.uhf.license.LicenseStatusActivity
import com.example.uhf.manager.MiraQuickAccessSettings
import com.example.uhf.manager.PermissionManager
import com.example.uhf.navigation.FragmentFactory
import com.example.uhf.navigation.NavigationManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.Locale

class QuickActionsBottomSheetFragment : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "QuickActionsSheet"
        private const val NAV_DELAY_MS = 180L
        private const val GRID_COLUMNS = 4
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
        val category: ActionCategory,
        val alwaysAllowed: Boolean = false
    )

    private var isArabic: Boolean = true
    private var permissionManager: PermissionManager? = null
    private var quickAccessSettings: MiraQuickAccessSettings? = null
    private val navHandler = Handler(Looper.getMainLooper())

    private val menuConfigs = listOf(
        // العمليات والحركة
        QuickMenuItemConfig("transfers", NavigationManager.TAG_TRANSFERS, "التحويلات", "Transfers", Icons.Rounded.SwapHoriz, Color(0xFF06B6D4), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("gold_conversion", NavigationManager.TAG_GOLD_CONVERSION, "أمر صياغة", "Crafting", Icons.Rounded.PrecisionManufacturing, Color(0xFFD4AF37), ActionCategory.OPERATIONS, alwaysAllowed = true),
        QuickMenuItemConfig("purchase_invoice", NavigationManager.TAG_PURCHASE_INVOICE, "فاتورة شراء", "Purchase Inv.", Icons.Rounded.ReceiptLong, Color(0xFF10B981), ActionCategory.OPERATIONS, alwaysAllowed = true),
        QuickMenuItemConfig("sales_history", NavigationManager.TAG_SALES_HISTORY, "المبيعات", "Sales Log", Icons.Rounded.PointOfSale, Color(0xFF38BDF8), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("reports", NavigationManager.TAG_REPORTS, "التقارير", "Reports", Icons.Rounded.Analytics, Color(0xFF818CF8), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("accounting", NavigationManager.TAG_ACCOUNTING, "المحاسبة", "Accounting", Icons.Rounded.AccountBalanceWallet, Color(0xFF10B981), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("vouchers", NavigationManager.TAG_VOUCHERS, "السندات", "Vouchers", Icons.Rounded.RequestQuote, Color(0xFFF59E0B), ActionCategory.OPERATIONS),
        QuickMenuItemConfig("cash_reconciliation", NavigationManager.TAG_CASH_RECONCILIATION, "الصندوق", "Cash Recon.", Icons.Rounded.FactCheck, Color(0xFF06B6D4), ActionCategory.OPERATIONS),

        // الذهب الكسر والأسعار
        QuickMenuItemConfig("scrap_buy", NavigationManager.TAG_SCRAP_GOLD_BUY, "شراء كسر", "Scrap Buy", Icons.Rounded.Scale, Color(0xFFF59E0B), ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("scrap_batches", NavigationManager.TAG_SCRAP_BATCHES, "دفعات الكسر", "Scrap Batches", Icons.Rounded.Layers, Color(0xFF10B981), ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("scrap_aging", NavigationManager.TAG_SCRAP_AGING, "أعمار الكسر", "Scrap Aging", Icons.Rounded.HourglassBottom, Color(0xFFD4AF37), ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("scrap_history", NavigationManager.TAG_SCRAP_HISTORY, "سجل الكسر", "Scrap Log", Icons.Rounded.History, Color(0xFF38BDF8), ActionCategory.SCRAP_AND_PURCHASES),
        QuickMenuItemConfig("gold_rates", NavigationManager.TAG_GOLD_RATES, "الذهب والعملات", "Gold Rates", Icons.Rounded.TrendingUp, Color(0xFFD4AF37), ActionCategory.SCRAP_AND_PURCHASES),

        // الإدارة والأجهزة والمراقبة
        QuickMenuItemConfig("parties", NavigationManager.TAG_PARTIES, "العملاء والموردين", "Parties", Icons.Rounded.Groups, Color(0xFF10B981), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("radar", NavigationManager.TAG_RADAR, "الرادار", "RFID Radar", Icons.Rounded.Radar, Color(0xFFEF4444), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("gate", NavigationManager.TAG_GATES, "البوابة", "Smart Gate", Icons.Rounded.SensorDoor, Color(0xFF06B6D4), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("rfid_write", NavigationManager.TAG_RFID_WRITE, "كتابة شريحة", "Write Tag", Icons.Rounded.Nfc, Color(0xFFD4AF37), ActionCategory.MANAGEMENT),
        QuickMenuItemConfig("reader_setup", NavigationManager.TAG_READER_SETUP, "إعداد القارئ", "Reader Setup", Icons.Rounded.SettingsRemote, Color(0xFF38BDF8), ActionCategory.MANAGEMENT),
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
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
            Locale.getDefault().language == "ar"
        permissionManager = PermissionManager.getInstance(ctx)
        quickAccessSettings = MiraQuickAccessSettings.getInstance(ctx)

        return ComposeView(ctx).apply {
            layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MiraJewelryTheme {
                    QuickActionsSheetContent()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                bottomSheet.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.maxHeight = (resources.displayMetrics.heightPixels * 0.90).toInt()
                bottomSheet.requestLayout()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStart error", e)
        }
    }

    override fun onDestroyView() {
        navHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun isItemAllowed(item: QuickMenuItemConfig): Boolean {
        if (item.alwaysAllowed) return true
        val pm = permissionManager ?: return true
        val hasPermission = pm.canAccessFragment(item.navTag)
        val qaAllowed = quickAccessSettings?.isActionEnabled(item.actionKey) ?: true
        return hasPermission && qaAllowed
    }

    private fun isStudioAllowed(): Boolean {
        val pm = permissionManager ?: return true
        val hasPermission = pm.canAccessFragment(NavigationManager.TAG_IDENTITY_STUDIO)
        val qaAllowed = quickAccessSettings?.isActionEnabled("identity") ?: true
        return hasPermission && qaAllowed
    }

    private fun canSeeLicenseCard(): Boolean {
        val pm = permissionManager ?: return true
        return pm.isAdmin || pm.isManager
    }

    private fun navigateToTag(navTag: String, fallback: Fragment? = null) {
        performHaptic()
        val mainActivity = activity as? UHFMainActivity ?: run {
            dismiss()
            return
        }
        val targetFragment = FragmentFactory.createFragmentByTag(navTag) ?: fallback ?: run {
            dismiss()
            return
        }
        dismiss()
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
        performHaptic()
        try {
            startActivity(Intent(requireContext(), LicenseStatusActivity::class.java))
            dismiss()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open LicenseStatusActivity", e)
        }
    }

    private fun performHaptic() {
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
        }
    }

    @Composable
    private fun QuickActionsSheetContent() {
        val colors = MiraTheme.colors
        var searchQuery by remember { mutableStateOf("") }
        var showSearchBar by remember { mutableStateOf(false) }

        val allowedItems = remember { menuConfigs.filter { isItemAllowed(it) } }
        val filteredItems = remember(allowedItems, searchQuery) {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) allowedItems
            else allowedItems.filter {
                it.titleAr.lowercase(Locale.ROOT).contains(q) ||
                    it.titleEn.lowercase(Locale.ROOT).contains(q) ||
                    it.actionKey.lowercase(Locale.ROOT).contains(q)
            }
        }

        val opsItems = remember(filteredItems) { filteredItems.filter { it.category == ActionCategory.OPERATIONS } }
        val scrapItems = remember(filteredItems) { filteredItems.filter { it.category == ActionCategory.SCRAP_AND_PURCHASES } }
        val mgmtItems = remember(filteredItems) { filteredItems.filter { it.category == ActionCategory.MANAGEMENT } }

        val showStudio = remember(searchQuery) {
            if (!isStudioAllowed()) false
            else if (searchQuery.isBlank()) true
            else {
                val q = searchQuery.trim().lowercase(Locale.ROOT)
                "استوديو التكويد".contains(q) || "encoding studio".contains(q) || "identity".contains(q)
            }
        }
        val showLicense = remember { canSeeLicenseCard() }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.cardSurface,
            border = BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        colors.goldPrimary.copy(alpha = 0.55f),
                        Color(0xFF06B6D4).copy(alpha = 0.35f),
                        colors.goldPrimary.copy(alpha = 0.55f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                colors.backgroundTop.copy(alpha = 0.99f),
                                colors.backgroundMid.copy(alpha = 0.96f),
                                colors.cardSurface
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.goldPrimary.copy(alpha = 0.45f))
                )

                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.goldPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Widgets,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isArabic) "القائمة السريعة" else "Quick Actions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isArabic) "وصول فوري للعمليات والأنظمة" else "Instant access to modules & tools",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    performHaptic()
                                    showSearchBar = !showSearchBar
                                    if (!showSearchBar) searchQuery = ""
                                },
                            shape = CircleShape,
                            color = if (showSearchBar) Color(0xFF06B6D4).copy(alpha = 0.2f) else colors.backgroundMid,
                            border = BorderStroke(
                                1.dp,
                                if (showSearchBar) Color(0xFF06B6D4) else colors.textSecondary.copy(alpha = 0.25f)
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = if (showSearchBar) Color(0xFF06B6D4) else colors.goldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    performHaptic()
                                    dismiss()
                                },
                            shape = CircleShape,
                            color = colors.backgroundMid,
                            border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.25f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Expandable Quick Search
                AnimatedVisibility(
                    visible = showSearchBar,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = if (isArabic) "ابحث عن خدمة أو عملية..." else "Search action or module...",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF06B6D4))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = null, tint = colors.textSecondary)
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.backgroundMid,
                            unfocusedContainerColor = colors.backgroundMid,
                            focusedBorderColor = Color(0xFF06B6D4),
                            unfocusedBorderColor = colors.goldPrimary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Hero CTA Card: Encoding Studio
                if (showStudio) {
                    val studioInteraction = remember { MutableInteractionSource() }
                    val studioPressed by studioInteraction.collectIsPressedAsState()
                    val studioScale by animateFloatAsState(
                        targetValue = if (studioPressed) 0.985f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "studioScale"
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(studioScale)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = studioInteraction,
                                indication = null
                            ) {
                                navigateToTag(
                                    NavigationManager.TAG_IDENTITY_STUDIO,
                                    MiraIdentityStudioFragment()
                                )
                            },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.4.dp,
                            Brush.horizontalGradient(
                                listOf(colors.goldPrimary, Color(0xFF06B6D4).copy(alpha = 0.7f))
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            colors.goldPrimary.copy(alpha = 0.24f),
                                            Color(0xFF06B6D4).copy(alpha = 0.10f),
                                            colors.backgroundMid
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
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.goldPrimary.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.QrCode2,
                                        contentDescription = null,
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isArabic) "استوديو التكويد" else "Encoding Studio",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = colors.textPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = colors.goldPrimary.copy(alpha = 0.20f)
                                        ) {
                                            Text(
                                                text = "RFID / GS1",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = colors.goldPrimary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isArabic) "تكويد وربط شريحة قطعة جديدة بسهولة" else "Encode and link a new item smart tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(colors.goldPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isArabic) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
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
                        accent = Color(0xFF06B6D4),
                        items = opsItems,
                        isArabic = isArabic,
                        onClick = { navigateToTag(it.navTag) }
                    )
                }

                if (scrapItems.isNotEmpty()) {
                    SectionBlock(
                        title = if (isArabic) "الذهب الكسر والأسعار" else "Scrap & Gold Rates",
                        badgeCount = scrapItems.size,
                        accent = Color(0xFFF59E0B),
                        items = scrapItems,
                        isArabic = isArabic,
                        onClick = { navigateToTag(it.navTag) }
                    )
                }

                if (mgmtItems.isNotEmpty()) {
                    SectionBlock(
                        title = if (isArabic) "الإدارة والأجهزة والمراقبة" else "Management & Devices",
                        badgeCount = mgmtItems.size,
                        accent = colors.goldPrimary,
                        items = mgmtItems,
                        isArabic = isArabic,
                        onClick = { navigateToTag(it.navTag) }
                    )
                }

                if (!showStudio && opsItems.isEmpty() && scrapItems.isEmpty() && mgmtItems.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.backgroundMid,
                        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SearchOff,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = if (isArabic) {
                                    "لا توجد نتائج مطابقة أو أزرار مفعّلة متاحة."
                                } else {
                                    "No matching or enabled actions found."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // License Status Card
                if (showLicense) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { openLicenseStatus() },
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFF172554).copy(alpha = 0.78f),
                        border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.6f))
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
                                        .background(Color(0xFF3B82F6).copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VerifiedUser,
                                        contentDescription = null,
                                        tint = Color(0xFF93C5FD),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (isArabic) "حالة الترخيص" else "License Status",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = if (isArabic) "عرض تفاصيل ترخيص MIRA ID" else "View MIRA ID license details",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF93C5FD)
                                    )
                                }
                            }

                            Icon(
                                imageVector = if (isArabic) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = Color(0xFF93C5FD)
                            )
                        }
                    }
                }
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
        val colors = MiraTheme.colors
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
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = 0.14f)
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
        val colors = MiraTheme.colors
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()

        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.95f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "tileScale"
        )
        val borderColor by animateColorAsState(
            targetValue = if (isPressed) item.accent else item.accent.copy(alpha = 0.28f),
            label = "tileBorder"
        )

        Surface(
            modifier = modifier
                .height(80.dp)
                .scale(scale)
                .clip(RoundedCornerShape(15.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = RoundedCornerShape(15.dp),
            color = colors.backgroundMid,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    item.accent.copy(alpha = 0.22f),
                                    item.accent.copy(alpha = 0.08f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = item.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isArabic) item.titleAr else item.titleEn,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
