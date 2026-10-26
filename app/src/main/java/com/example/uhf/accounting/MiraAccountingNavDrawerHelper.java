package com.example.uhf.accounting;

import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.navigation.NavigationView;

import java.util.List;

/**
 * MIRA Accounting — Navigation Drawer Helper
 *
 * يبني عناصر القائمة الجانبية ديناميكياً من {@link MiraAccountingScreens}.
 * المسؤوليات الوحيدة:
 *   1. تحويل الـ registry إلى MenuItems مصنّفة بالأقسام
 *   2. إبقاء معرّفات MenuItem مرتبطة بالـ Screen.id عبر hash مستقر
 *   3. تسليم حدث "شاشة مختارة" للمتلقي (الـ Fragment)
 *
 * لماذا hash بدل استخدام ordinal؟
 *   لأن ordinal يتغير إذا أضفنا/أعدنا ترتيب شاشات، بينما hash(screenId)
 *   يبقى ثابتاً ما دام المعرف النصي ثابتاً. هذا يمنع كسر الـ checked state
 *   بعد أي تعديل في السجل.
 */
public final class MiraAccountingNavDrawerHelper {

    public interface OnScreenSelectedListener {
        void onScreenSelected(@NonNull MiraAccountingScreens.Screen screen);
    }

    /**
     * كل MenuItem لشاشة يحمل id = BASE + hash(screenId) % RANGE.
     * النطاق 90,000 يترك مساحة آمنة للأيقونات الأخرى، وقاعدة 100,000
     * تفصل menu items عن أي id آخر قد يُستخدم في القوائم.
     */
    private static final int ITEM_ID_BASE = 100_000;
    private static final int ITEM_ID_RANGE = 90_000;

    private MiraAccountingNavDrawerHelper() {}

    public static int itemIdFor(@NonNull String screenId) {
        int h = screenId.hashCode() % ITEM_ID_RANGE;
        return ITEM_ID_BASE + Math.abs(h);
    }

    /**
     * يملأ الـ NavigationView بكل الأقسام والشاشات.
     *
     * @param navView  عرض القائمة
     * @param isArabic لغة العرض الحالية
     * @param listener يُنفَّذ عند اختيار شاشة
     */
    public static void build(@NonNull NavigationView navView,
                             boolean isArabic,
                             @NonNull OnScreenSelectedListener listener) {

        Menu menu = navView.getMenu();
        menu.clear();

        int order = 0;
        for (MiraAccountingScreens.Section section : MiraAccountingScreens.allSections()) {
            List<MiraAccountingScreens.Screen> screens =
                    MiraAccountingScreens.inSection(section);

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
                MenuItem item = menu.add(
                        Menu.NONE,
                        itemIdFor(screen.id),
                        order++,
                        "      " + screen.icon + "  " + screen.title(isArabic)
                );
                item.setCheckable(true);
            }
        }

        // ─── استقبال الاختيار ───
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id < ITEM_ID_BASE) return false;   // عنوان قسم

            for (MiraAccountingScreens.Screen screen : MiraAccountingScreens.all()) {
                if (itemIdFor(screen.id) == id) {
                    listener.onScreenSelected(screen);
                    return true;
                }
            }
            return false;
        });
    }

    /** تحديد الشاشة النشطة في القائمة */
    public static void setChecked(@NonNull NavigationView navView,
                                  @Nullable String screenId) {
        if (screenId == null) {
            navView.setCheckedItem(androidx.appcompat.R.id.home);
            return;
        }
        navView.setCheckedItem(itemIdFor(screenId));
    }
}
