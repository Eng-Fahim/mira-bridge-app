package com.example.uhf.manager

import android.content.Context
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.example.uhf.R
import com.example.uhf.fragment.MiraProfileFragment
import com.example.uhf.fragment.MiraReportsProFragment
import com.example.uhf.navigation.FragmentFactory
import com.example.uhf.navigation.NavigationManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * BottomNavManager (Kotlin + Jetpack Compose)
 * شريط تنقّل عائم فاخر بهوية MIRA ID™ (الكحلي الملكي والذهب عيار 24)
 * متوافق 100% مع UHFMainActivity.java دون الحاجة لتعديل ملفات XML.
 */
class BottomNavManager(
    private val context: Context,
    rootView: View,
    private val permissionManager: PermissionManager?,
    private val navigationManager: NavigationManager?,
    private val host: HostCallback,
    isArabic: Boolean
) {

    companion object {
        private const val TAG = "BottomNavManager"

        // لوحة ألوان هوية MIRA ID™ الملكية
        private val NavyObsidian = Color(0xFF071224)
        private val NavyDeep = Color(0xFF0A192F)
        private val NavyElevated = Color(0xFF132644)
        private val NavyPillActive = Color(0xFF1E2D4D)

        private val GoldBright = Color(0xFFF7DF8B)
        private val GoldPrimary = Color(0xFFD4AF37)
        private val GoldBronze = Color(0xFF9C7621)
        private val TextMuted = Color(0xFF94A3B8)
        private val BadgeCrimson = Color(0xFFDC2626)

        private val NAV_CONFIGS = listOf(
            NavItemCfg(
                itemId = R.id.nav_home,
                iconRes = R.drawable.ic_home,
                titleArRes = R.string.nav_home,
                titleEnRes = R.string.nav_home,
                tag = NavigationManager.TAG_DASHBOARD,
                alwaysAllowed = true
            ),
            NavItemCfg(
                itemId = R.id.nav_inventory,
                iconRes = R.drawable.ic_inventory,
                titleArRes = R.string.nav_inventory,
                titleEnRes = R.string.nav_inventory,
                tag = NavigationManager.TAG_INVENTORY,
                alwaysAllowed = false
            ),
            NavItemCfg(
                itemId = R.id.nav_sales,
                iconRes = R.drawable.ic_sales,
                titleArRes = R.string.nav_sales,
                titleEnRes = R.string.nav_sales,
                tag = NavigationManager.TAG_POS,
                alwaysAllowed = false
            ),
            NavItemCfg(
                itemId = R.id.nav_scrap,
                iconRes = R.drawable.ic_diamond,
                titleArRes = R.string.nav_scrap,
                titleEnRes = R.string.nav_scrap,
                tag = NavigationManager.TAG_SCRAP_GOLD_BUY,
                alwaysAllowed = false
            )
        )
    }

    interface HostCallback {
        fun getCurrentFragmentTag(): String
        fun isSetupCompleted(): Boolean
        fun isInventoryActive(): Boolean
        fun stopActiveInventory()
        fun navigateToFragment(fragment: Fragment, tag: String, direct: Boolean)
        fun showQuickActionsSheet()
    }

    data class NavItemCfg(
        val itemId: Int,
        val iconRes: Int,
        val titleArRes: Int,
        val titleEnRes: Int,
        val tag: String,
        val alwaysAllowed: Boolean
    )

    private val bottomNavContainer: ViewGroup? =
        rootView.findViewById(R.id.bottomNavContainer)
    private val legacyBottomNav: View? =
        rootView.findViewById(R.id.bottomNavigationView)
    private val fragmentContainer: View? =
        rootView.findViewById(R.id.fragment_container)
    private val legacyFabCenter: View? =
        rootView.findViewById(R.id.fabActionCenter)
    private val legacyFabReports: View? =
        rootView.findViewById(R.id.fabReports)
    private val legacyFabProfile: View? =
        rootView.findViewById(R.id.fabProfile)

    private var composeView: ComposeView? = null

    // حالات تفاعلية فورية لـ Jetpack Compose
    private val visibleItemsState = mutableStateListOf<NavItemCfg>()
    private var selectedTagState by mutableStateOf(NavigationManager.TAG_DASHBOARD)
    private var isArabicState by mutableStateOf(isArabic)
    private var showCenterHubState by mutableStateOf(true)
    private var isPosOperatorState by mutableStateOf(false)
    private var isFabMenuOpenState by mutableStateOf(false)
    private var isEnabledState by mutableStateOf(true)
    private var navigationLockedState by mutableStateOf(false)
    private var transfersBadgeCountState by mutableIntStateOf(0)

    private val permissionsListener = PermissionManager.OnPermissionsChangedListener {
        bottomNavContainer?.post { applyPermissions() }
    }

    /** تهيئة الشريط الفاخر وحقن ComposeView داخل bottomNavContainer */
    fun setup() {
        try {
            // إخفاء العناصر التقليدية القديمة مع الإبقاء على مراجعها آمنة
            legacyBottomNav?.visibility = View.GONE
            legacyFabCenter?.visibility = View.GONE
            legacyFabReports?.visibility = View.GONE
            legacyFabProfile?.visibility = View.GONE

            mountComposeDock()
            applyPermissions()

            permissionManager?.addListener(permissionsListener)
        } catch (e: Exception) {
            Log.e(TAG, "setup error: ${e.message}", e)
        }
    }

    private fun mountComposeDock() {
        val container = bottomNavContainer ?: return
        if (composeView != null) return

        val cv = ComposeView(context).apply {
            id = View.generateViewId()
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                val layoutDir = if (isArabicState) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    MiraLuxuryNavigationDock(
                        items = visibleItemsState,
                        selectedTag = selectedTagState,
                        isArabic = isArabicState,
                        showCenterHub = showCenterHubState,
                        isPosOperator = isPosOperatorState,
                        isFabMenuOpen = isFabMenuOpenState,
                        isEnabled = isEnabledState && !navigationLockedState,
                        transfersBadgeCount = transfersBadgeCountState,
                        onSelectTab = { cfg ->
                            performHaptic(HapticFeedbackConstants.CLOCK_TICK)
                            handleTabSelection(cfg)
                        },
                        onCenterHubClick = {
                            performHaptic(HapticFeedbackConstants.LONG_PRESS)
                            handleCenterHubClick()
                        },
                        onOpenReports = {
                            performHaptic(HapticFeedbackConstants.CLOCK_TICK)
                            closePosFabMenu()
                            host.navigateToFragment(
                                MiraReportsProFragment(),
                                NavigationManager.TAG_REPORTS,
                                false
                            )
                        },
                        onOpenProfile = {
                            performHaptic(HapticFeedbackConstants.CLOCK_TICK)
                            closePosFabMenu()
                            host.navigateToFragment(
                                MiraProfileFragment(),
                                "Profile",
                                false
                            )
                        }
                    )
                }
            }
        }

        val lp: ViewGroup.LayoutParams = if (container is ConstraintLayout) {
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(cv, lp)
        composeView = cv
    }

    /** تنظيف المستمعات عند إغلاق النشاط */
    fun cleanup() {
        permissionManager?.removeListener(permissionsListener)
        composeView = null
    }

    /** إعادة بناء التبويبات والزر الأوسط بناءً على الصلاحيات */
    fun applyPermissions() {
        try {
            val container = bottomNavContainer ?: return
            val pm = permissionManager ?: return

            val hideFabForSecurity = pm.isGateUser()
            showCenterHubState = !hideFabForSecurity && (
                pm.isSystemAdmin() ||
                    pm.isOrgAdmin() ||
                    pm.getAllowedQuickActions().isNotEmpty()
                )
            isPosOperatorState = pm.isPosOperator()

            if (!showCenterHubState && isFabMenuOpenState) {
                closePosFabMenu()
            }

            val allowed = NAV_CONFIGS.filter { isNavItemAllowedForRole(it) }
            visibleItemsState.clear()
            visibleItemsState.addAll(allowed)

            if (host.isSetupCompleted()) {
                container.visibility = View.VISIBLE
            }

            val currentTag = host.getCurrentFragmentTag()
            selectByTag(currentTag)

            Log.d(TAG, "applyPermissions: visibleTabs=${allowed.size}, currentTag=$currentTag")
        } catch (e: Exception) {
            Log.e(TAG, "applyPermissions error: ${e.message}", e)
        }
    }

    private fun isNavItemAllowedForRole(cfg: NavItemCfg?): Boolean {
        if (cfg == null) return false
        if (cfg.alwaysAllowed) return true

        val pm = permissionManager ?: return false
        if (pm.isSystemAdmin() || pm.isOrgAdmin() || pm.isAdmin()) {
            return true
        }

        return when (cfg.itemId) {
            R.id.nav_inventory ->
                pm.canViewInventory() && pm.canAccessFragment(NavigationManager.TAG_INVENTORY)
            R.id.nav_sales ->
                pm.canUsePos() && pm.canAccessFragment(NavigationManager.TAG_POS)
            R.id.nav_scrap ->
                pm.canAccessFragment(NavigationManager.TAG_SCRAP_GOLD_BUY)
            else -> false
        }
    }

    private fun handleCenterHubClick() {
        if (!isEnabledState || navigationLockedState) return

        if (isPosOperatorState) {
            isFabMenuOpenState = !isFabMenuOpenState
        } else {
            host.showQuickActionsSheet()
        }
    }

    fun closePosFabMenu() {
        isFabMenuOpenState = false
    }

    private fun handleTabSelection(cfg: NavItemCfg) {
        if (!isEnabledState) return
        if (isFabMenuOpenState) {
            closePosFabMenu()
        }

        val tag = cfg.tag
        if (tag.isEmpty()) return

        if (tag == host.getCurrentFragmentTag()) {
            selectedTagState = tag
            return
        }

        if (navigationLockedState || host.isInventoryActive()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(if (isArabicState) "الجرد قيد التشغيل" else "Inventory Active")
                .setMessage(
                    if (isArabicState) {
                        "لا يمكن التنقل أثناء الجرد النشط.\n\nهل تريد إيقاف الجرد والانتقال؟"
                    } else {
                        "Cannot navigate during active inventory.\n\nStop inventory and proceed?"
                    }
                )
                .setPositiveButton(
                    if (isArabicState) "إيقاف والانتقال" else "Stop & Proceed"
                ) { _, _ ->
                    host.stopActiveInventory()
                    setLocked(false)

                    bottomNavContainer?.postDelayed({
                        val f = createFragment(tag)
                        if (f != null) {
                            selectedTagState = tag
                            host.navigateToFragment(f, tag, true)
                        }
                    }, 350L)
                }
                .setNegativeButton(
                    if (isArabicState) "إلغاء" else "Cancel"
                ) { _, _ ->
                    selectByTag(host.getCurrentFragmentTag())
                }
                .setCancelable(false)
                .show()
            return
        }

        val fragment = createFragment(tag)
        if (fragment != null) {
            selectedTagState = tag
            host.navigateToFragment(fragment, tag, true)
        }
    }

    private fun createFragment(tag: String): Fragment? {
        return navigationManager?.createFragmentByTag(tag)
            ?: FragmentFactory.createFragmentByTag(tag)
    }

    fun selectByTag(tag: String?) {
        if (tag.isNullOrEmpty()) return
        if (NAV_CONFIGS.any { it.tag == tag }) {
            selectedTagState = tag
        }
    }

    fun updateLanguage(isArabic: Boolean) {
        this.isArabicState = isArabic
    }

    fun setVisible(visible: Boolean) {
        try {
            val container = bottomNavContainer ?: return

            if (visible) {
                if (container.visibility == View.VISIBLE &&
                    container.alpha == 1f &&
                    container.translationY == 0f
                ) {
                    setEnabled(true)
                    return
                }

                container.animate().cancel()
                container.visibility = View.VISIBLE

                val startY = if (container.translationY > 0f) {
                    container.translationY
                } else {
                    if (container.height > 0) container.height.toFloat() else dp(110f)
                }

                container.translationY = startY
                container.alpha = 0f

                container.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(OvershootInterpolator(0.85f))
                    .withEndAction { setEnabled(true) }
                    .start()
            } else {
                if (container.visibility == View.GONE) return

                if (isFabMenuOpenState) {
                    closePosFabMenu()
                }

                container.animate().cancel()
                val endY = if (container.height > 0) container.height.toFloat() else dp(110f)

                container.animate()
                    .translationY(endY)
                    .alpha(0f)
                    .setDuration(220L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        container.visibility = View.GONE
                        container.translationY = 0f
                    }
                    .start()
            }

            fragmentContainer?.let { fc ->
                val targetPadding = if (visible) dp(92f).toInt() else 0
                if (fc.paddingBottom != targetPadding) {
                    fc.setPadding(
                        fc.paddingLeft,
                        fc.paddingTop,
                        fc.paddingRight,
                        targetPadding
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "setVisible error: ${e.message}", e)
        }
    }

    fun setEnabled(enabled: Boolean) {
        this.isEnabledState = enabled
    }

    fun setLocked(locked: Boolean) {
        this.navigationLockedState = locked
        setEnabled(!locked)
    }

    fun isLocked(): Boolean = navigationLockedState

    fun onKeyboardVisibilityChanged(isKeyboardVisible: Boolean) {
        try {
            bottomNavContainer?.let { container ->
                if (isKeyboardVisible && isFabMenuOpenState) {
                    closePosFabMenu()
                }

                val targetY = if (isKeyboardVisible) container.height.toFloat() else 0f
                if (container.translationY != targetY) {
                    container.animate()
                        .translationY(targetY)
                        .setDuration(220L)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
            }

            fragmentContainer?.let { fc ->
                val targetPadding = if (isKeyboardVisible) dp(8f).toInt() else dp(92f).toInt()
                if (fc.paddingBottom != targetPadding) {
                    fc.setPadding(
                        fc.paddingLeft,
                        fc.paddingTop,
                        fc.paddingRight,
                        targetPadding
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onKeyboardVisibilityChanged error: ${e.message}")
        }
    }

    fun updateTransfersBadge(count: Int) {
        transfersBadgeCountState = count.coerceAtLeast(0)
    }

    fun anchorFabToContainerCenter() {
        // الزر المركزي مدمج تلقائياً في قلب الكبسولة العائمة
    }

    private fun performHaptic(type: Int) {
        try {
            composeView?.performHapticFeedback(type)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Float): Float =
        value * context.resources.displayMetrics.density

    // ═══════════════════════════════════════════════════════════════════
    //  واجهة Jetpack Compose الفاخرة (MIRA ID™ Royal Dock)
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun MiraLuxuryNavigationDock(
        items: List<NavItemCfg>,
        selectedTag: String,
        isArabic: Boolean,
        showCenterHub: Boolean,
        isPosOperator: Boolean,
        isFabMenuOpen: Boolean,
        isEnabled: Boolean,
        transfersBadgeCount: Int,
        onSelectTab: (NavItemCfg) -> Unit,
        onCenterHubClick: () -> Unit,
        onOpenReports: () -> Unit,
        onOpenProfile: () -> Unit
    ) {
        val dockAlpha by animateFloatAsState(
            targetValue = if (isEnabled) 1f else 0.45f,
            animationSpec = tween(220),
            label = "dockAlpha"
        )

        val navyGradient = remember {
            Brush.verticalGradient(
                colors = listOf(NavyElevated, NavyDeep, NavyObsidian)
            )
        }

        val goldBorderBrush = remember {
            Brush.linearGradient(
                colors = listOf(
                    GoldBright.copy(alpha = 0.75f),
                    GoldPrimary.copy(alpha = 0.45f),
                    GoldBronze.copy(alpha = 0.65f)
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .alpha(dockAlpha)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // قوس الاختصارات العائمة لموظفي نقاط البيع (POS) عند فتح الجوهرة المركزية
            AnimatedVisibility(
                visible = showCenterHub && isPosOperator && isFabMenuOpen,
                enter = fadeIn(tween(220)) + scaleIn(
                    initialScale = 0.82f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.85f)
            ) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LuxurySatelliteChip(
                        iconRes = R.drawable.ic_inventory,
                        label = if (isArabic) "التقارير" else "Reports",
                        onClick = onOpenReports
                    )
                    LuxurySatelliteChip(
                        iconRes = R.drawable.ic_home,
                        label = if (isArabic) "حسابي" else "Profile",
                        onClick = onOpenProfile
                    )
                }
            }

            // الحاوية الرئيسية للكبسولة + الزر الذهبي المرتفع
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // الكبسولة الكحلية ذات الإطار الذهبي المتدرج
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape(32.dp),
                            ambientColor = NavyObsidian,
                            spotColor = GoldPrimary.copy(alpha = 0.35f)
                        )
                        .clip(RoundedCornerShape(32.dp))
                        .background(navyGradient)
                        .border(
                            width = 1.2.dp,
                            brush = goldBorderBrush,
                            shape = RoundedCornerShape(32.dp)
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val splitIndex = (items.size + 1) / 2
                    val leadingItems = if (showCenterHub) items.take(splitIndex) else items
                    val trailingItems = if (showCenterHub) items.drop(splitIndex) else emptyList()

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        leadingItems.forEach { cfg ->
                            val badge = if (cfg.itemId == R.id.nav_transfers) transfersBadgeCount else 0
                            MiraExpandablePillTab(
                                cfg = cfg,
                                isSelected = cfg.tag == selectedTag,
                                isArabic = isArabic,
                                badgeCount = badge,
                                onClick = { onSelectTab(cfg) }
                            )
                        }

                        if (showCenterHub) {
                            Spacer(modifier = Modifier.width(66.dp))
                        }

                        trailingItems.forEach { cfg ->
                            val badge = if (cfg.itemId == R.id.nav_transfers) transfersBadgeCount else 0
                            MiraExpandablePillTab(
                                cfg = cfg,
                                isSelected = cfg.tag == selectedTag,
                                isArabic = isArabic,
                                badgeCount = badge,
                                onClick = { onSelectTab(cfg) }
                            )
                        }
                    }
                }

                // جوهرة التحكم الذهبية المركزية (24K Gold Command Hub)
                if (showCenterHub) {
                    MiraGoldCommandOrb(
                        isOpen = isFabMenuOpen,
                        modifier = Modifier.align(Alignment.TopCenter),
                        onClick = onCenterHubClick
                    )
                }
            }
        }
    }

    @Composable
    private fun MiraExpandablePillTab(
        cfg: NavItemCfg,
        isSelected: Boolean,
        isArabic: Boolean,
        badgeCount: Int,
        onClick: () -> Unit
    ) {
        val pillBg by animateColorAsState(
            targetValue = if (isSelected) NavyPillActive else Color.Transparent,
            animationSpec = tween(240),
            label = "pillBg"
        )

        val borderColor by animateColorAsState(
            targetValue = if (isSelected) GoldPrimary.copy(alpha = 0.75f) else Color.Transparent,
            animationSpec = tween(240),
            label = "pillBorder"
        )

        val contentColor by animateColorAsState(
            targetValue = if (isSelected) GoldBright else TextMuted,
            animationSpec = tween(240),
            label = "pillContent"
        )

        val iconScale by animateFloatAsState(
            targetValue = if (isSelected) 1.08f else 0.96f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "iconScale"
        )

        val title = stringResource(id = if (isArabic) cfg.titleArRes else cfg.titleEnRes)

        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(pillBg)
                .border(1.dp, borderColor, RoundedCornerShape(22.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = if (isSelected) 14.dp else 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Image(
                        painter = painterResource(id = cfg.iconRes),
                        contentDescription = title,
                        colorFilter = ColorFilter.tint(contentColor),
                        modifier = Modifier
                            .size(21.dp)
                            .scale(iconScale)
                    )

                    if (badgeCount > 0) {
                        Box(
                            modifier = Modifier
                                .offset(x = 6.dp, y = (-4).dp)
                                .clip(CircleShape)
                                .background(BadgeCrimson)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isSelected,
                    enter = expandHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeIn(tween(200)),
                    exit = shrinkHorizontally(tween(180)) + fadeOut(tween(140))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(7.dp))
                        BasicText(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                color = GoldBright,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(GoldPrimary)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MiraGoldCommandOrb(
        isOpen: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "goldOrbGlow")
        val glowScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "orbPulse"
        )

        val rotation by animateFloatAsState(
            targetValue = if (isOpen) 135f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "orbRotation"
        )

        val goldOrbBrush = remember {
            Brush.linearGradient(
                colors = listOf(GoldBright, GoldPrimary, GoldBronze)
            )
        }

        // حلقة عزل كحلية داكنة تفصل الجوهرة الذهبية بأناقة عن الكبسولة
        Box(
            modifier = modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(NavyObsidian)
                .padding(3.5.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(if (isOpen) 1.04f else glowScale)
                    .shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        ambientColor = GoldPrimary,
                        spotColor = GoldBright
                    )
                    .clip(CircleShape)
                    .background(goldOrbBrush)
                    .border(1.dp, GoldBright.copy(alpha = 0.85f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_diamond),
                    contentDescription = "MIRA Command Hub",
                    colorFilter = ColorFilter.tint(NavyDeep),
                    modifier = Modifier
                        .size(25.dp)
                        .rotate(rotation)
                )
            }
        }
    }

    @Composable
    private fun LuxurySatelliteChip(
        iconRes: Int,
        label: String,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .shadow(10.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(NavyDeep)
                .border(1.dp, GoldPrimary.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                colorFilter = ColorFilter.tint(GoldPrimary),
                modifier = Modifier.size(18.dp)
            )
            BasicText(
                text = label,
                style = TextStyle(
                    color = GoldBright,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
