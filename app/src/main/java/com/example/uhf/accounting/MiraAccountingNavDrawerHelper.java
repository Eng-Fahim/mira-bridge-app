package com.example.uhf.accounting;

import android.view.Menu;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.navigation.NavigationView;
import java.util.List;

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
     * بناء القائمة الجانبية مع فحص الصلاحيات والأيقونات الحقيقية
     */
    public static void build(@NonNull NavigationView navView,
                             boolean isArabic,
                             @NonNull PermissionManager permissionManager, // ✅ مضاف
                             @NonNull OnScreenSelectedListener listener) {

        Menu menu = navView.getMenu();
        menu.clear();

        int order = 0;
        
        // المرور على الأقسام
        for (MiraAccountingScreens.Section section : MiraAccountingScreens.allSections()) {
            List<MiraAccountingScreens.Screen> screens = MiraAccountingScreens.inSection(section);
            if (screens.isEmpty()) continue;

            // إنشاء قسم فرعي قابل للطي (SubMenu)
            // ملاحظة: يجب أن يدعم الـ XML الخاص بك الـ SubMenu، أو يمكنك تركها كعنوان غير قابل للنقر
            MenuItem header = menu.add(Menu.NONE, Menu.NONE, order++, section.icon + " " + section.title(isArabic));
            header.setCheckable(false);
            header.setEnabled(false);

            // المرور على شاشات القسم
            for (MiraAccountingScreens.Screen screen : screens) {
                
                // ⚡ فلترة الصلاحيات: هل هذا المستخدم يملك حق الوصول لهذه الشاشة؟
                if (!hasPermission(permissionManager, screen)) {
                    continue; // تخطي هذه الشاشة، لا تضفها للقائمة
                }

                MenuItem item = menu.add(
                        Menu.NONE,
                        itemIdFor(screen.id),
                        order++,
                        screen.title(isArabic)
                );
                item.setCheckable(true);
                
                // ⚡ استخدام الأيقونة الحقيقية إذا كانت موجودة
                if (screen.iconResId != 0) {
                    item.setIcon(screen.iconResId);
                }
                
                // ⚡ إضافة شارات الإشعارات (اختياري)
                // if (screen.id.equals(MiraAccountingScreens.ID_JOURNAL)) {
                //     item.setActionView(...); // يمكنك إضافة Badge هنا
                // }
            }
        }

        // استقبال الاختيار
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id < ITEM_ID_BASE) return false; // عنوان قسم

            for (MiraAccountingScreens.Screen screen : MiraAccountingScreens.all()) {
                if (itemIdFor(screen.id) == id) {
                    listener.onScreenSelected(screen);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * فحص ما إذا كان المستخدم يملك صلاحية فتح هذه الشاشة
     */
    private static boolean hasPermission(PermissionManager pm, MiraAccountingScreens.Screen screen) {
        if (pm == null) return true;
        
        // المدير والمحاسب المالي يملكان كل شيء
        if (pm.isSystemAdmin() || pm.isOrgAdmin() || pm.isFinanceManager() || pm.isAccountant()) {
            return true;
        }
        
        // باقي الأدوار (مثل المدقق) يفحصون الصلاحية الفردية للشاشة
        return pm.canView(screen.permissionKey);
    }

    public static void setChecked(@NonNull NavigationView navView, @Nullable String screenId) {
        if (screenId == null) {
            navView.setCheckedItem(androidx.appcompat.R.id.home);
            return;
        }
        navView.setCheckedItem(itemIdFor(screenId));
    }
}
