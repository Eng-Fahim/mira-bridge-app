package com.example.uhf.accounting;

import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.manager.PermissionManager;
import com.google.android.material.navigation.NavigationView;

import java.util.List;

/**
 * MIRA Accounting — Navigation Drawer Helper v2.0
 * 
 * التحديثات:
 * - دعم فلترة الصلاحيات (لا يعرض الشاشات التي لا يملك المستخدم صلاحيتها).
 * - دعم الأيقونات الحقيقية (Vector Drawables).
 */
public final class MiraAccountingNavDrawerHelper {

    public interface OnScreenSelectedListener {
        void onScreenSelected(@NonNull MiraAccountingScreens.Screen screen);
    }

    private static final int ITEM_ID_BASE = 100_000;
    private static final int ITEM_ID_RANGE = 90_000;

    private MiraAccountingNavDrawerHelper() {}

    public static int itemIdFor(@NonNull String screenId) {
        int h = screenId.hashCode() % ITEM_ID_RANGE;
        return ITEM_ID_BASE + Math.abs(h);
    }

    /**
     * يملأ الـ NavigationView بكل الأقسام والشاشات المتاحة للمستخدم الحالي.
     */
    public static void build(@NonNull NavigationView navView,
                             boolean isArabic,
                             @NonNull PermissionManager permissionManager, 
                             @NonNull OnScreenSelectedListener listener) {

        Menu menu = navView.getMenu();
        menu.clear();

        int order = 0;

        for (MiraAccountingScreens.Section section : MiraAccountingScreens.allSections()) {
            List<MiraAccountingScreens.Screen> screens = MiraAccountingScreens.inSection(section);
            if (screens.isEmpty()) continue;

            // ─── عنوان قسم (غير قابل للنقر) ───
            MenuItem header = menu.add(
                    Menu.NONE,
                    Menu.NONE,
                    order++,
                    section.icon + "  " + section.title(isArabic)
            );
            header.setCheckable(false);
            header.setEnabled(false);

            // ─── شاشات القسم ───
            for (MiraAccountingScreens.Screen screen : screens) {

                // ⚡ فلترة الصلاحيات
                if (!hasPermission(permissionManager, screen)) {
                    continue;
                }

                MenuItem item = menu.add(
                        Menu.NONE,
                        itemIdFor(screen.id),
                        order++,
                        "      " + screen.title(isArabic)
                );
                item.setCheckable(true);
                
                // ✅ تم التصحيح: استخدام iconRes بدلاً من iconResId
                if (screen.iconRes != 0) {
                    item.setIcon(screen.iconRes);
                }
            }
        }

        // ─── استقبال الاختيار ───
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id < ITEM_ID_BASE) return false;

            for (MiraAccountingScreens.Screen screen : MiraAccountingScreens.all()) {
                if (itemIdFor(screen.id) == id) {
                    listener.onScreenSelected(screen);
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean hasPermission(PermissionManager pm, MiraAccountingScreens.Screen screen) {
        if (pm == null) return true;
        if (pm.isSystemAdmin() || pm.isOrgAdmin() || pm.isFinanceManager() || pm.isAccountant()) {
            return true;
        }
        return pm.canView(screen.permissionKey);
    }

    public static void setChecked(@NonNull NavigationView navView,
                                  @Nullable String screenId) {
        if (screenId == null) {
            navView.setCheckedItem(androidx.appcompat.R.id.home);
            return;
        }
        navView.setCheckedItem(itemIdFor(screenId));
    }
}
