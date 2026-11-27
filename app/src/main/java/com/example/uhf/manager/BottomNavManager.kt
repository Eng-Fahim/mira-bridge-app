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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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

        private val GoldBright = Color(0xFFE8C868)
        private val GoldDeep = Color(0xFFB8891E)
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

    fun setup() {
        try {
            // إزالة الأزرار التقليدية تماماً من الحاوية لمنع أي تراكب بالارتفاع (Z-Order)
            listOf(legacyBottomNav, legacyFabCenter, legacyFabReports, legacyFabProfile).forEach { v ->
                v?.visibility = View.GONE
                if (v != null && v.parent is ViewGroup) {
                    (v.parent as ViewGroup).removeView(v)
                }
            }

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
            elevation = dp(12f)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                val layoutDir = if (isArabicState) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    MiraJewelryTheme {
                        MiraThemeAwareNavigationDock(
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

    fun cleanup() {
        permissionManager?.removeListener(permissionsListener)
        composeView = null
    }

    fun applyPermissions() {
        try {
            val container = bottomNavContainer ?: return
            val pm = permissionManager ?: return

            val hideFabForSecurity = pm.isGateUser()
            showCenterHubState = !hideFabForSecurity && (
                pm.isSystemAdmin() ||
                    pm.isOrgAdmin() ||
                    pm.isPosOperator() ||
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
                    if (container.height > 0) container.height.toFloat() else dp(96f)
                }

                container.translationY = startY
                container.alpha = 0f

                container.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(250L)
                    .setInterpolator(OvershootInterpolator(0.75f))
                    .withEndAction { setEnabled(true) }
                    .start()
            } else {
                if (container.visibility == View.GONE) return

                if (isFabMenuOpenState) {
                    closePosFabMenu()
                }

                container.animate().cancel()
                val endY = if (container.height > 0) container.height.toFloat() else dp(96f)

                container.animate()
                    .translationY(endY)
                    .alpha(0f)
                    .setDuration(200L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        container.visibility = View.GONE
                        container.translationY = 0f
                    }
                    .start()
            }

            fragmentContainer?.let { fc ->
                val targetPadding = if (visible) dp(88f).toInt() else 0
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
                        .setDuration(200L)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
            }

            fragmentContainer?.let { fc ->
                val targetPadding = if (isKeyboardVisible) dp(8f).toInt() else dp(88f).toInt()
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
        // مدمج تلقائياً في المنتصف
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
    //  واجهة Jetpack Compose المتناغمة مع الثيم النهاري والليلي
    // ═══════════════════════════════════════════════════════════════════

    @Composable
    private fun MiraThemeAwareNavigationDock(
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
        val colors = MiraTheme.colors
        val isDark = colors.backgroundTop.luminance() < 0.45f

        val dockAlpha by animateFloatAsState(
            targetValue = if (isEnabled) 1f else 0.45f,
            animationSpec = tween(200),
            label = "dockAlpha"
        )

        val dockSurfaceColor = if (isDark) colors.cardSurface else Color(0xFFFFFFFF)
        val dockBorderColor = if (isDark) {
            colors.goldPrimary.copy(alpha = 0.45f)
        } else {
            colors.goldPrimary.copy(alpha = 0.38f)
        }
        val cutoutRingColor = if (isDark) colors.backgroundTop else colors.backgroundMid

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .alpha(dockAlpha)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // اختصارات موظف نقطة البيع عند فتح الزر الأوسط
            AnimatedVisibility(
                visible = showCenterHub && isPosOperator && isFabMenuOpen,
                enter = fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.85f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.88f)
            ) {
                Row(
                    modifier = Modifier.padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemeSatelliteChip(
                        iconRes = R.drawable.ic_inventory,
                        label = if (isArabic) "التقارير" else "Reports",
                        surfaceColor = dockSurfaceColor,
                        borderColor = dockBorderColor,
                        textColor = colors.textPrimary,
                        iconTint = colors.goldPrimary,
                        onClick = onOpenReports
                    )
                    ThemeSatelliteChip(
                        iconRes = R.drawable.ic_home,
                        label = if (isArabic) "حسابي" else "Profile",
                        surfaceColor = dockSurfaceColor,
                        borderColor = dockBorderColor,
                        textColor = colors.textPrimary,
                        iconTint = colors.goldPrimary,
                        onClick = onOpenProfile
                    )
                }
            }

            // الحاوية المتوازنة للشريط + الزر الدائري الذهبي في المنتصف
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = dockSurfaceColor,
                    border = BorderStroke(1.dp, dockBorderColor),
                    shadowElevation = if (isDark) 12.dp else 8.dp
                ) {
                    val splitIndex = (items.size + 1) / 2
                    val leadingItems = if (showCenterHub) items.take(splitIndex) else items
                    val trailingItems = if (showCenterHub) items.drop(splitIndex) else emptyList()

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // النصف الأيمن (في العربية)
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            leadingItems.forEach { cfg ->
                                val badge = if (cfg.itemId == R.id.nav_transfers) transfersBadgeCount else 0
                                SymmetricalNavTab(
                                    cfg = cfg,
                                    isSelected = cfg.tag == selectedTag,
                                    isArabic = isArabic,
                                    isDark = isDark,
                                    badgeCount = badge,
                                    onClick = { onSelectTab(cfg) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // مساحة محجوزة ثابتة للزر الذهبي الأوسط تمنع أي تراكب مع النصوص
                        if (showCenterHub) {
                            Spacer(modifier = Modifier.width(66.dp))
                        }

                        // النصف الأيسر (في العربية)
                        if (trailingItems.isNotEmpty()) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                trailingItems.forEach { cfg ->
                                    val badge = if (cfg.itemId == R.id.nav_transfers) transfersBadgeCount else 0
                                    SymmetricalNavTab(
                                        cfg = cfg,
                                        isSelected = cfg.tag == selectedTag,
                                        isArabic = isArabic,
                                        isDark = isDark,
                                        badgeCount = badge,
                                        onClick = { onSelectTab(cfg) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                // الزر الدائري الذهبي الأوسط (CircleShape حقيقي)
                if (showCenterHub) {
                    CenterGoldCircleButton(
                        isOpen = isFabMenuOpen,
                        ringColor = cutoutRingColor,
                        modifier = Modifier.align(Alignment.TopCenter),
                        onClick = onCenterHubClick
                    )
                }
            }
        }
    }

    @Composable
    private fun SymmetricalNavTab(
        cfg: NavItemCfg,
        isSelected: Boolean,
        isArabic: Boolean,
        isDark: Boolean,
        badgeCount: Int,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val colors = MiraTheme.colors

        val activePillColor = if (isDark) {
            colors.goldPrimary.copy(alpha = 0.18f)
        } else {
            colors.goldPrimary.copy(alpha = 0.14f)
        }

        val activeTextAndIcon = if (isDark) Color(0xFFF7DF8B) else Color(0xFF8C6200)
        val inactiveTextAndIcon = colors.textSecondary

        val pillBg by animateColorAsState(
            targetValue = if (isSelected) activePillColor else Color.Transparent,
            animationSpec = tween(200),
            label = "pillBg"
        )
        val tintColor by animateColorAsState(
            targetValue = if (isSelected) activeTextAndIcon else inactiveTextAndIcon,
            animationSpec = tween(200),
            label = "tintColor"
        )
        val iconScale by animateFloatAsState(
            targetValue = if (isSelected) 1.08f else 1.0f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "iconScale"
        )

        val title = stringResource(id = if (isArabic) cfg.titleArRes else cfg.titleEnRes)

        Column(
            modifier = modifier
                .padding(horizontal = 3.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(pillBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(vertical = 5.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Image(
                    painter = painterResource(id = cfg.iconRes),
                    contentDescription = title,
                    colorFilter = ColorFilter.tint(tintColor),
                    modifier = Modifier
                        .size(20.dp)
                        .scale(iconScale)
                )

                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier
                            .offset(x = 7.dp, y = (-3).dp)
                            .clip(CircleShape)
                            .background(BadgeCrimson)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                            color = Color.White,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = title,
                color = tintColor,
                fontSize = 10.5.sp,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun CenterGoldCircleButton(
        isOpen: Boolean,
        ringColor: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val rotation by animateFloatAsState(
            targetValue = if (isOpen) 135f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "orbRotation"
        )

        val goldBrush = remember {
            Brush.linearGradient(
                colors = listOf(GoldBright, Color(0xFFD4AF37), GoldDeep)
            )
        }

        // حلقة خارجية بلون خلفية الشاشة تعزل الدائرة الذهبية بنظافة تامة
        Box(
            modifier = modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(ringColor)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(goldBrush)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_diamond),
                    contentDescription = "Quick Actions",
                    colorFilter = ColorFilter.tint(Color(0xFF1A1405)),
                    modifier = Modifier
                        .size(23.dp)
                        .rotate(rotation)
                )
            }
        }
    }

    @Composable
    private fun ThemeSatelliteChip(
        iconRes: Int,
        label: String,
        surfaceColor: Color,
        borderColor: Color,
        textColor: Color,
        iconTint: Color,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(iconTint),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
