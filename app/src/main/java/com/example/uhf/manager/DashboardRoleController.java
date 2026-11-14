package com.example.uhf.manager;

import android.view.View;
import androidx.cardview.widget.CardView;
import com.example.uhf.R;

/**
 * DashboardRoleController v4.0 — Enterprise RBAC Enforcement
 * يتحكم في رؤية العناصر ويمنع طلب البيانات غير المصرح بها.
 */
public class DashboardRoleController {

    private final PermissionManager permissions;
    private final boolean arabic;

    public DashboardRoleController(PermissionManager permissions, boolean arabic) {
        this.permissions = permissions;
        this.arabic = arabic;
    }

    public void applyVisibility(View root) {
        if (root == null || permissions == null) return;

        // 1. البطاقات الرئيسية (Carousel)
        setVisible(root, R.id.cardGoldPrices, permissions.canViewDashboardGoldPrices());
        setVisible(root, R.id.salesSummaryCard, permissions.canViewDashboardSales());
        setVisible(root, R.id.cardScrapAging, permissions.canViewDashboardScrapAging());
        
        // 2. المخزون
        boolean showKarat = permissions.canViewDashboardInventoryByKarat();
        setVisible(root, R.id.cardInventoryByKarat, showKarat);

        // 3. كسر الذهب (يتم البحث عنه بالـ ID)
        setVisible(root, R.id.cardScrapGold, permissions.canViewDashboardScrap());

        // 4. الرسوم البيانية
        setVisible(root, R.id.cardCharts, permissions.canViewDashboardCharts());

        // 5. أزرار كشف القيم الحساسة
        boolean canReveal = permissions.canRevealSensitiveValues();
        setVisible(root, R.id.btnToggleHeroVisibility, canReveal);
        setVisible(root, R.id.btnToggleSalesVisibility, canReveal);
        setVisible(root, R.id.btnToggleInventoryVisibility, canReveal);
        setVisible(root, R.id.btnToggleScrapVisibility, canReveal);

        // 6. التنبيهات
        if (!permissions.canViewDashboardAlerts()) {
            setVisible(root, R.id.alertsStrip, false);
        }
    }

    // Guards: منع طلب البيانات من السيرفر
    public boolean canFetchFullDashboard() {
        return permissions.canViewDashboardHero() || permissions.canViewDashboardSales();
    }
    public boolean canFetchCharts() { return permissions.canViewDashboardCharts(); }
    public boolean canFetchScrap() { return permissions.canViewDashboardScrap(); }
    public boolean canFetchScrapAging() { return permissions.canViewDashboardScrapAging(); }
    public boolean canFetchGoldPrices() { return permissions.canViewDashboardGoldPrices(); }
    public boolean canFetchRecentActivity() { return permissions.canViewDashboardRecentActivity(); }

    private void setVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
