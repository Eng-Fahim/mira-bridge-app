package com.example.uhf.manager;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.navigation.FragmentFactory;
import com.example.uhf.navigation.NavigationManager;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationBarItemView;

/**
 * BottomNavManager
 * مدير الشريط السفلي والزر العائم المركزي (FAB).
 *
 * يتولى:
 * 1. بناء وفلترة تبويبات الشريط السفلي حسب صلاحيات المستخدم.
 * 2. الاستماع لتحديثات الصلاحيات وإعادة رسم التبويبات والـ FAB.
 * 3. إدارة إظهار وإخفاء الشريط مع الحركات وتفاعل لوحة المفاتيح.
 * 4. إدارة شارة التحويلات الواردة وحماية التنقل أثناء الجرد النشط.
 */
public class BottomNavManager {

    private static final String TAG = "BottomNavManager";

    public interface HostCallback {
        @NonNull String getCurrentFragmentTag();
        boolean isSetupCompleted();
        boolean isInventoryActive();
        void stopActiveInventory();
        void navigateToFragment(@NonNull Fragment fragment, @NonNull String tag, boolean direct);
        void showQuickActionsSheet();
    }

    private static final class NavItemCfg {
        final int itemId;
        final int iconRes;
        final int titleArRes;
        final int titleEnRes;
        final String tag;
        final boolean alwaysAllowed;

        NavItemCfg(
                int itemId,
                int iconRes,
                int titleArRes,
                int titleEnRes,
                String tag,
                boolean alwaysAllowed
        ) {
            this.itemId = itemId;
            this.iconRes = iconRes;
            this.titleArRes = titleArRes;
            this.titleEnRes = titleEnRes;
            this.tag = tag;
            this.alwaysAllowed = alwaysAllowed;
        }
    }

    // مصدر الحقيقة الوحيد لعناصر الشريط السفلي (تم إلغاء تبويب المالية)
    private static final NavItemCfg[] NAV_CONFIGS = {
            new NavItemCfg(
                    R.id.nav_home,
                    R.drawable.ic_home,
                    R.string.nav_home,
                    R.string.nav_home,
                    NavigationManager.TAG_DASHBOARD,
                    true
            ),
            new NavItemCfg(
                    R.id.nav_inventory,
                    R.drawable.ic_inventory,
                    R.string.nav_inventory,
                    R.string.nav_inventory,
                    NavigationManager.TAG_INVENTORY,
                    false
            ),
            new NavItemCfg(
                    R.id.nav_sales,
                    R.drawable.ic_sales,
                    R.string.nav_sales,
                    R.string.nav_sales,
                    NavigationManager.TAG_POS,
                    false
            ),
            new NavItemCfg(
                    R.id.nav_scrap,
                    R.drawable.ic_diamond,
                    R.string.nav_scrap,
                    R.string.nav_scrap,
                    NavigationManager.TAG_SCRAP_GOLD_BUY,
                    false
            )
    };

    private final Context context;
    private final PermissionManager permissionManager;
    private final NavigationManager navigationManager;
    private final HostCallback host;

    @Nullable
    private final View bottomNavContainer;

    @Nullable
    private final BottomNavigationView bottomNav;

    @Nullable
    private final View fragmentContainer;

    @Nullable
    private final FloatingActionButton fabActionCenter;

    @Nullable
    private final FloatingActionButton fabReports;

    @Nullable
    private final FloatingActionButton fabProfile;

    private final PermissionManager.OnPermissionsChangedListener permissionsListener;

    private boolean isArabic;
    private boolean isFabMenuOpen = false;
    private boolean navigationLocked = false;

    public BottomNavManager(
            @NonNull Context context,
            @NonNull View rootView,
            @Nullable PermissionManager permissionManager,
            @Nullable NavigationManager navigationManager,
            @NonNull HostCallback host,
            boolean isArabic
    ) {
        this.context = context;
        this.permissionManager = permissionManager;
        this.navigationManager = navigationManager;
        this.host = host;
        this.isArabic = isArabic;

        this.bottomNavContainer = rootView.findViewById(R.id.bottomNavContainer);
        this.bottomNav = rootView.findViewById(R.id.bottomNavigationView);
        this.fragmentContainer = rootView.findViewById(R.id.fragment_container);
        this.fabActionCenter = rootView.findViewById(R.id.fabActionCenter);
        this.fabReports = rootView.findViewById(R.id.fabReports);
        this.fabProfile = rootView.findViewById(R.id.fabProfile);

        this.permissionsListener = () -> {
            if (this.bottomNav != null) {
                this.bottomNav.post(this::applyPermissions);
            }
        };
    }

    /** تهيئة الشريط السفلي والـ FAB وربط مستمع الصلاحيات */
    public void setup() {
        try {
            if (fabReports != null) {
                fabReports.setVisibility(View.GONE);
            }

            if (fabProfile != null) {
                fabProfile.setVisibility(View.GONE);
            }

            applyPermissions();
            setupFabClicks();

            if (bottomNav != null) {
                attachNavListener(bottomNav);
            }

            if (permissionManager != null) {
                permissionManager.addListener(permissionsListener);
            }

        } catch (Exception e) {
            Log.e(TAG, "setup error: " + e.getMessage(), e);
        }
    }

    /** تنظيف المستمعات عند إغلاق النشاط */
    public void cleanup() {
        if (permissionManager != null) {
            permissionManager.removeListener(permissionsListener);
        }
    }

    /** إعادة بناء التبويبات والزر الأوسط بناءً على الصلاحيات */
    public void applyPermissions() {
        try {
            if (bottomNav == null
                    || bottomNavContainer == null
                    || permissionManager == null) {
                return;
            }

            boolean hideFabForSecurity = permissionManager.isGateUser();

            boolean hasQuickActions = !hideFabForSecurity
                    && (permissionManager.isSystemAdmin()
                    || permissionManager.isOrgAdmin()
                    || !permissionManager.getAllowedQuickActions().isEmpty());

            if (fabActionCenter != null) {
                fabActionCenter.setVisibility(
                        hasQuickActions ? View.VISIBLE : View.GONE
                );

                if (!hasQuickActions && isFabMenuOpen) {
                    closePosFabMenu();
                }
            }

            bottomNav.setOnItemSelectedListener(null);
            bottomNav.getMenu().clear();

            int index = 0;
            int added = 0;

            for (NavItemCfg cfg : NAV_CONFIGS) {
                if (!isNavItemAllowedForRole(cfg)) {
                    continue;
                }

                MenuItem item = bottomNav.getMenu().add(
                        Menu.NONE,
                        cfg.itemId,
                        index++,
                        ""
                );

                item.setIcon(cfg.iconRes);
                item.setTitle(
                        context.getString(
                                isArabic ? cfg.titleArRes : cfg.titleEnRes
                        )
                );

                added++;
            }

            if (host.isSetupCompleted()) {
                bottomNav.setVisibility(View.VISIBLE);
                bottomNavContainer.setVisibility(View.VISIBLE);

                if (fabActionCenter != null) {
                    resetFabConstraintsToNav(fabActionCenter);
                }
            }

            String currentTag = host.getCurrentFragmentTag();
            selectByTag(currentTag);

            attachNavListener(bottomNav);

            Log.d(
                    TAG,
                    "applyPermissions: visibleTabs=" + added
                            + ", currentTag=" + currentTag
            );

        } catch (Exception e) {
            Log.e(TAG, "applyPermissions error: " + e.getMessage(), e);
        }
    }

    /** التحقق من صلاحية كل تبويب */
    private boolean isNavItemAllowedForRole(@Nullable NavItemCfg cfg) {
        if (cfg == null) {
            return false;
        }

        if (cfg.alwaysAllowed) {
            return true;
        }

        if (permissionManager == null) {
            return false;
        }

        if (permissionManager.isSystemAdmin()
                || permissionManager.isOrgAdmin()
                || permissionManager.isAdmin()) {
            return true;
        }

        int id = cfg.itemId;

        if (id == R.id.nav_inventory) {
            return permissionManager.canViewInventory()
                    && permissionManager.canAccessFragment(
                    NavigationManager.TAG_INVENTORY
            );
        }

        if (id == R.id.nav_sales) {
            return permissionManager.canUsePos()
                    && permissionManager.canAccessFragment(
                    NavigationManager.TAG_POS
            );
        }

        if (id == R.id.nav_scrap) {
            return permissionManager.canAccessFragment(
                    NavigationManager.TAG_SCRAP_GOLD_BUY
            );
        }

        return false;
    }

    /** إعداد أحداث الأزرار العائمة */
    private void setupFabClicks() {
        if (fabActionCenter == null) {
            return;
        }

        fabActionCenter.setOnClickListener(v -> {
            boolean isPos = permissionManager != null
                    && permissionManager.isPosOperator();

            if (isPos && fabReports != null && fabProfile != null) {

                if (!isFabMenuOpen) {
                    fabReports.setVisibility(View.VISIBLE);
                    fabProfile.setVisibility(View.VISIBLE);

                    fabReports.animate()
                            .translationX(-150f)
                            .translationY(-150f)
                            .setDuration(300)
                            .start();

                    fabProfile.animate()
                            .translationX(150f)
                            .translationY(-150f)
                            .setDuration(300)
                            .start();

                    fabActionCenter.animate()
                            .rotation(45f)
                            .setDuration(300)
                            .start();

                    isFabMenuOpen = true;

                } else {
                    closePosFabMenu();
                }

            } else {
                host.showQuickActionsSheet();
            }
        });

        if (fabReports != null) {
            fabReports.setOnClickListener(v -> {
                host.navigateToFragment(
                        new MiraReportsProFragment(),
                        NavigationManager.TAG_REPORTS,
                        false
                );

                closePosFabMenu();
            });
        }

        if (fabProfile != null) {
            fabProfile.setOnClickListener(v -> {
                host.navigateToFragment(
                        new MiraProfileFragment(),
                        "Profile",
                        false
                );

                closePosFabMenu();
            });
        }
    }

    /** إغلاق قائمة الأزرار العائمة */
    public void closePosFabMenu() {
        if (fabActionCenter != null
                && fabReports != null
                && fabProfile != null) {

            fabReports.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(300)
                    .withEndAction(() -> fabReports.setVisibility(View.GONE))
                    .start();

            fabProfile.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(300)
                    .withEndAction(() -> fabProfile.setVisibility(View.GONE))
                    .start();

            fabActionCenter.animate()
                    .rotation(0f)
                    .setDuration(300)
                    .start();

            isFabMenuOpen = false;
        }
    }

    /** ربط مستمع التنقل */
    private void attachNavListener(@NonNull BottomNavigationView nav) {
        nav.setOnItemSelectedListener(item -> {

            String tag = getFragmentTagForNavItem(item.getItemId());

            if (tag.isEmpty()) {
                return false;
            }

            if (tag.equals(host.getCurrentFragmentTag())) {
                return true;
            }

            if (navigationLocked || host.isInventoryActive()) {

                new MaterialAlertDialogBuilder(context)
                        .setTitle(
                                isArabic
                                        ? "الجرد قيد التشغيل"
                                        : "Inventory Active"
                        )
                        .setMessage(
                                isArabic
                                        ? "لا يمكن التنقل أثناء الجرد النشط.\n\nهل تريد إيقاف الجرد والانتقال؟"
                                        : "Cannot navigate during active inventory.\n\nStop inventory and proceed?"
                        )
                        .setPositiveButton(
                                isArabic ? "إيقاف والانتقال" : "Stop & Proceed",
                                (d, w) -> {
                                    host.stopActiveInventory();
                                    setLocked(false);

                                    nav.postDelayed(() -> {
                                        Fragment f = createFragment(tag);

                                        if (f != null) {
                                            host.navigateToFragment(f, tag, true);
                                        }
                                    }, 500);
                                }
                        )
                        .setNegativeButton(
                                isArabic ? "إلغاء" : "Cancel",
                                (d, w) -> selectByTag(host.getCurrentFragmentTag())
                        )
                        .setCancelable(false)
                        .show();

                return false;
            }

            Fragment fragment = createFragment(tag);

            if (fragment != null) {
                host.navigateToFragment(fragment, tag, true);
                return true;
            }

            return false;
        });
    }

    @Nullable
    private Fragment createFragment(@NonNull String tag) {
        return navigationManager != null
                ? navigationManager.createFragmentByTag(tag)
                : FragmentFactory.createFragmentByTag(tag);
    }

    @NonNull
    private String getFragmentTagForNavItem(int itemId) {
        for (NavItemCfg cfg : NAV_CONFIGS) {
            if (cfg.itemId == itemId) {
                return cfg.tag;
            }
        }

        return "";
    }

    /** تحديد التبويب النشط حسب Tag */
    public void selectByTag(@Nullable String tag) {
        try {
            if (bottomNav == null || tag == null) {
                return;
            }

            for (NavItemCfg cfg : NAV_CONFIGS) {
                if (cfg.tag.equals(tag)) {
                    MenuItem mi = bottomNav.getMenu().findItem(cfg.itemId);

                    if (mi != null) {
                        mi.setChecked(true);
                    }

                    return;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "selectByTag error: " + e.getMessage());
        }
    }

    /** تحديث لغة التبويبات */
    public void updateLanguage(boolean isArabic) {
        this.isArabic = isArabic;

        try {
            if (bottomNav == null || bottomNav.getMenu() == null) {
                return;
            }

            for (NavItemCfg cfg : NAV_CONFIGS) {
                MenuItem item = bottomNav.getMenu().findItem(cfg.itemId);

                if (item != null) {
                    item.setTitle(
                            context.getString(
                                    isArabic ? cfg.titleArRes : cfg.titleEnRes
                            )
                    );
                }
            }

            bottomNav.refreshDrawableState();

        } catch (Exception e) {
            Log.e(TAG, "updateLanguage error: " + e.getMessage());
        }
    }

    /** إظهار أو إخفاء الشريط السفلي */
    public void setVisible(boolean visible) {
        try {
            if (bottomNavContainer == null) {
                return;
            }

            if (visible) {

                if (bottomNavContainer.getVisibility() == View.VISIBLE
                        && bottomNavContainer.getAlpha() == 1f
                        && bottomNavContainer.getTranslationY() == 0f) {
                    setEnabled(true);
                    return;
                }

                bottomNavContainer.animate().cancel();
                bottomNavContainer.setVisibility(View.VISIBLE);

                float startY = bottomNavContainer.getTranslationY() > 0
                        ? bottomNavContainer.getTranslationY()
                        : (bottomNavContainer.getHeight() > 0
                        ? bottomNavContainer.getHeight()
                        : 200f);

                bottomNavContainer.setTranslationY(startY);
                bottomNavContainer.setAlpha(0f);

                bottomNavContainer.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> setEnabled(true))
                        .start();

            } else {

                if (bottomNavContainer.getVisibility() == View.GONE) {
                    return;
                }

                bottomNavContainer.animate().cancel();

                float endY = bottomNavContainer.getHeight() > 0
                        ? bottomNavContainer.getHeight()
                        : 200f;

                bottomNavContainer.animate()
                        .translationY(endY)
                        .alpha(0f)
                        .setDuration(250)
                        .setInterpolator(new AccelerateInterpolator())
                        .withEndAction(() -> {
                            bottomNavContainer.setVisibility(View.GONE);
                            bottomNavContainer.setTranslationY(0f);
                        })
                        .start();
            }

            if (fragmentContainer != null) {
                int targetPadding = visible ? 90 : 0;

                if (fragmentContainer.getPaddingBottom() != targetPadding) {
                    fragmentContainer.setPadding(
                            fragmentContainer.getPaddingLeft(),
                            fragmentContainer.getPaddingTop(),
                            fragmentContainer.getPaddingRight(),
                            targetPadding
                    );
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "setVisible error: " + e.getMessage(), e);
        }
    }

    /** تفعيل أو تعطيل الشريط والأزرار */
    public void setEnabled(boolean enabled) {
        try {
            if (bottomNav != null) {
                bottomNav.setEnabled(enabled);
                bottomNav.setAlpha(enabled ? 1.0f : 0.4f);
            }

            if (fabActionCenter != null) {
                fabActionCenter.setEnabled(enabled);
                fabActionCenter.setAlpha(enabled ? 1.0f : 0.4f);
            }

        } catch (Exception e) {
            Log.e(TAG, "setEnabled error: " + e.getMessage());
        }
    }

    /** قفل أو فتح التنقل */
    public void setLocked(boolean locked) {
        this.navigationLocked = locked;

        setEnabled(!locked);

        if (bottomNav != null && bottomNav.getMenu() != null) {
            Menu menu = bottomNav.getMenu();

            for (int i = 0; i < menu.size(); i++) {
                menu.getItem(i).setEnabled(!locked);
            }
        }
    }

    public boolean isLocked() {
        return navigationLocked;
    }

    /** التعامل مع ظهور واختفاء لوحة المفاتيح */
    public void onKeyboardVisibilityChanged(boolean isKeyboardVisible) {
        try {
            if (bottomNavContainer != null) {

                float targetY = isKeyboardVisible
                        ? bottomNavContainer.getHeight()
                        : 0f;

                if (bottomNavContainer.getTranslationY() != targetY) {
                    bottomNavContainer.animate()
                            .translationY(targetY)
                            .setDuration(250)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
            }

            if (fragmentContainer != null) {
                int targetPadding = isKeyboardVisible ? 8 : 90;

                if (fragmentContainer.getPaddingBottom() != targetPadding) {
                    fragmentContainer.setPadding(
                            fragmentContainer.getPaddingLeft(),
                            fragmentContainer.getPaddingTop(),
                            fragmentContainer.getPaddingRight(),
                            targetPadding
                    );
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "onKeyboardVisibilityChanged error: " + e.getMessage());
        }
    }

    /** تحديث شارة التحويلات الواردة */
    public void updateTransfersBadge(int count) {
        try {
            if (bottomNav == null) {
                return;
            }

            MenuItem transfersItem = bottomNav.getMenu().findItem(R.id.nav_transfers);

            if (transfersItem == null) {
                return;
            }

            BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.nav_transfers);

            if (badge == null) {
                return;
            }

            if (count > 0) {
                badge.setVisible(true);
                badge.setNumber(count);

                badge.setBackgroundColor(
                        ContextCompat.getColor(
                                context,
                                android.R.color.holo_red_dark
                        )
                );

                badge.setBadgeTextColor(
                        ContextCompat.getColor(
                                context,
                                android.R.color.white
                        )
                );

                pulseNavItem(R.id.nav_transfers);

            } else {
                badge.setVisible(false);
                badge.clearNumber();
            }

        } catch (Exception e) {
            Log.e(TAG, "updateTransfersBadge error: " + e.getMessage());
        }
    }

    /** تحريك تبويب التحويلات عند وصول إشعار */
    private void pulseNavItem(int itemId) {
        if (bottomNav == null) {
            return;
        }

        try {
            View itemView = null;

            for (int i = 0; i < bottomNav.getChildCount(); i++) {
                View child = bottomNav.getChildAt(i);

                if (child instanceof NavigationBarItemView) {
                    NavigationBarItemView navItem = (NavigationBarItemView) child;

                    if (navItem.getItemData() != null
                            && navItem.getItemData().getItemId() == itemId) {
                        itemView = navItem;
                        break;
                    }
                }
            }

            if (itemView == null) {
                return;
            }

            ObjectAnimator scaleX = ObjectAnimator.ofFloat(
                    itemView,
                    "scaleX",
                    1f,
                    1.15f,
                    1f
            );

            ObjectAnimator scaleY = ObjectAnimator.ofFloat(
                    itemView,
                    "scaleY",
                    1f,
                    1.15f,
                    1f
            );

            scaleX.setDuration(400);
            scaleY.setDuration(400);

            scaleX.setRepeatCount(1);
            scaleY.setRepeatCount(1);

            scaleX.start();
            scaleY.start();

        } catch (Exception ignored) {
        }
    }

    /** إعادة ربط FAB بالشريط السفلي */
    private void resetFabConstraintsToNav(FloatingActionButton fab) {
        ViewGroup.LayoutParams lp = fab.getLayoutParams();

        if (!(lp instanceof ConstraintLayout.LayoutParams)) {
            return;
        }

        ConstraintLayout.LayoutParams clp =
                (ConstraintLayout.LayoutParams) lp;

        clp.topToTop = R.id.bottomNavigationView;
        clp.bottomToTop = R.id.bottomNavigationView;
        clp.startToStart = R.id.bottomNavigationView;
        clp.endToEnd = R.id.bottomNavigationView;

        clp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
        clp.bottomMargin = 0;

        fab.setLayoutParams(clp);
    }

    /** تثبيت FAB في مركز الحاوية */
    public void anchorFabToContainerCenter() {
        if (fabActionCenter == null) {
            return;
        }

        ViewGroup.LayoutParams lp = fabActionCenter.getLayoutParams();

        if (!(lp instanceof ConstraintLayout.LayoutParams)) {
            return;
        }

        ConstraintLayout.LayoutParams clp =
                (ConstraintLayout.LayoutParams) lp;

        clp.topToTop = ConstraintLayout.LayoutParams.UNSET;
        clp.bottomToTop = ConstraintLayout.LayoutParams.UNSET;

        clp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        clp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        clp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;

        float density = context.getResources()
                .getDisplayMetrics().density;

        clp.bottomMargin = (int) (32 * density);

        fabActionCenter.setLayoutParams(clp);
    }
}
