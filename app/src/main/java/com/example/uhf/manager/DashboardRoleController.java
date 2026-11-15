package com.example.uhf.manager;

import android.view.View;
import androidx.cardview.widget.CardView;

import com.example.uhf.R;

/**
 * ═══════════════════════════════════════════════════════════════
 *  DashboardRoleController — Enterprise Dashboard Renderer
 * ═══════════════════════════════════════════════════════════════
 *
 *  يتحكم في ظهور/إخفاء عناصر الداشبورد بناءً على صلاحيات المستخدم.
 *
 *  الاستخدام من MiraDashboardFragment (3 أسطر فقط):
 *      private DashboardRoleController roleController;
 *      roleController = new DashboardRoleController(permissionManager, isArabic);
 *      roleController.applyVisibility(getView());
 *
 *  وتُستعمل canFetchXxx() لتقليل نداءات الشبكة غير المسموح بها.
 */
public class DashboardRoleController {

    private final PermissionManager permissions;
    private final boolean arabic;

    public DashboardRoleController(PermissionManager permissions, boolean arabic) {
        this.permissions = permissions;
        this.arabic = arabic;
    }

    // ═══════════════════════════════════════════════════════
    //  Public API — Called by Fragment
    // ═══════════════════════════════════════════════════════

    /** تطبيق كل قواعد الرؤية على الشجرة. آمن للاستدعاء في أي وقت. */
    public void applyVisibility(View root) {
        if (root == null || permissions == null) return;

        // ─── 1. البطاقات الرئيسية ───
        setVisible(root, R.id.heroBalanceCard,    permissions.canViewDashboardHero());
        setVisible(root, R.id.cardGoldPrices,     permissions.canViewDashboardGoldPrices());
        setVisible(root, R.id.salesSummaryCard,   permissions.canViewDashboardSales());
        setVisible(root, R.id.cardScrapAging,     permissions.canViewDashboardScrapAging());

        // ─── 2. المخزون حسب العيار ───
        boolean showKarat = permissions.canViewDashboardInventoryByKarat();
        setVisible(root, R.id.llInventoryByKarat, showKarat);
        if (!showKarat) setVisible(root, R.id.tvKaratEmptyState, false);

        // ─── 3. بطاقة الكسر (تجربتها عبر معرف البطاقة، أو الجد الأب للعناصر) ───
        applyScrapCardVisibility(root, permissions.canViewDashboardScrap());

        // ─── 4. الرسوم البيانية ───
        boolean showCharts = permissions.canViewDashboardCharts();
        setVisible(root, R.id.lineChartSales, showCharts);
        setVisible(root, R.id.pieChartKarat,  showCharts);

        // ─── 5. أزرار العين (كشف القيم الحساسة) ───
        boolean canReveal = permissions.canRevealSensitiveValues();
        setVisible(root, R.id.btnToggleHeroVisibility,      canReveal);
        setVisible(root, R.id.btnToggleSalesVisibility,     canReveal);
        setVisible(root, R.id.btnToggleInventoryVisibility, canReveal);
        setVisible(root, R.id.btnToggleScrapVisibility,     canReveal);

        // ─── 6. زر التحديث اليدوي للذهب ───
        setVisible(root, R.id.btnRefreshGold, permissions.canRefreshGoldManually());

        // ─── 7. التنبيهات ───
        if (!permissions.canViewDashboardAlerts()) {
            setVisible(root, R.id.alertsStrip, false);
        } else {
            // نخفي زر "عرض" فقط إن لم يكن مسموحاً فتح Inventory
            setVisible(root, R.id.tvAlertAction, permissions.canViewInventory());
        }

        // ─── 8. النقاط (Dots) الخاصة بالكاروسيل ───
        applyDotsVisibility(root);
    }

    // ═══════════════════════════════════════════════════════
    //  Fetch Guards — لتقليل نداءات الشبكة
    // ═══════════════════════════════════════════════════════

    /** هل يجب جلب /getDashboardFull ؟ */
    public boolean canFetchFullDashboard() {
        return permissions.canViewDashboardHero()
            || permissions.canViewDashboardSales()
            || permissions.canViewDashboardInventoryByKarat();
    }

    public boolean canFetchCharts()      { return permissions.canViewDashboardCharts(); }
    public boolean canFetchScrap()       { return permissions.canViewDashboardScrap(); }
    public boolean canFetchScrapAging()  { return permissions.canViewDashboardScrapAging(); }
    public boolean canFetchGoldPrices()  { return permissions.canViewDashboardGoldPrices(); }
    public boolean canFetchRecentActivity() { return permissions.canViewDashboardRecentActivity(); }
    public boolean canRevealSensitive()  { return permissions.canRevealSensitiveValues(); }

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════

    private void setVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * إخفاء بطاقة الكسر — نبحث عنها بثلاث طرق متتالية لأن الـ ID قد يختلف
     * بين نسخ الـ layout. إذا فشل كل ذلك نخفي العناصر الداخلية.
     */
    private void applyScrapCardVisibility(View root, boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;

        // 1) المعرف المباشر إن وُجد
        View card = root.findViewById(R.id.cardScrapGold);

        // 2) الصعود من tvScrapWeight حتى CardView
        if (card == null) {
            View anchor = root.findViewById(R.id.tvScrapWeight);
            while (anchor != null) {
                if (anchor instanceof CardView) { card = anchor; break; }
                if (anchor.getParent() instanceof View) anchor = (View) anchor.getParent();
                else break;
            }
        }

        if (card != null) {
            card.setVisibility(visibility);
            return;
        }

        // 3) Fallback: إخفاء المحتوى فقط
        setVisible(root, R.id.tvScrapWeight, visible);
        setVisible(root, R.id.tvScrapValue, visible);
        setVisible(root, R.id.btnToggleScrapVisibility, visible);
        setVisible(root, R.id.btnOpenScrapBuy, visible);
        setVisible(root, R.id.btnOpenScrapHistory, visible);
        setVisible(root, R.id.btnOpenScrapBatches, visible);
    }

    private void applyDotsVisibility(View root) {
        setVisible(root, R.id.dot1, permissions.canViewDashboardHero());
        setVisible(root, R.id.dot2, permissions.canViewDashboardGoldPrices());
        setVisible(root, R.id.dot3, permissions.canViewDashboardSales());
    }
}
