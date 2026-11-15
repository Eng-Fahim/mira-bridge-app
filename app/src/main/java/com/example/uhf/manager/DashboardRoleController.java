package com.example.uhf.manager;

import android.view.View;
import com.example.uhf.R;

public class DashboardRoleController {

    private final PermissionManager permissions;
    private final boolean arabic;

    public DashboardRoleController(PermissionManager permissions, boolean arabic) {
        this.permissions = permissions;
        this.arabic = arabic;
    }

    public void applyVisibility(View root) {
        if (root == null || permissions == null) return;

        // ✅ إخفاء/إظهار البطاقات العلوية
        setVisible(root, R.id.cardGoldPrices, permissions.canViewDashboardGoldPrices());
        setVisible(root, R.id.salesSummaryCard, permissions.canViewDashboardSales());
        
        // ✅ موظف المبيعات لا يرى المخزون ولا كسر الذهب
        boolean isPos = permissions.isPosOperator();
        
        setVisible(root, R.id.cardInventoryByKarat, permissions.canViewDashboardInventoryByKarat() && !isPos);
        setVisible(root, R.id.cardScrapGold, permissions.canViewDashboardScrap() && !isPos);
        setVisible(root, R.id.cardScrapAging, permissions.canViewDashboardScrapAging() && !isPos);

        // ✅ الرسوم البيانية تظهر للمدير والمحاسب
        setVisible(root, R.id.cardCharts, permissions.canViewDashboardCharts());

        // ✅ أزرار كشف القيم
        boolean canReveal = permissions.canRevealSensitiveValues();
        setVisible(root, R.id.btnToggleHeroVisibility, canReveal);
        setVisible(root, R.id.btnToggleSalesVisibility, canReveal);
        setVisible(root, R.id.btnToggleInventoryVisibility, canReveal);
        setVisible(root, R.id.btnToggleScrapVisibility, canReveal);

        // ✅ التنبيهات
        if (!permissions.canViewDashboardAlerts()) {
            setVisible(root, R.id.alertsStrip, false);
        }
    }

    public boolean canFetchFullDashboard() {
        return permissions.canViewDashboardHero() || permissions.canViewDashboardSales();
    }
    public boolean canFetchCharts() { return permissions.canViewDashboardCharts(); }
    public boolean canFetchScrap() { return permissions.canViewDashboardScrap() && !permissions.isPosOperator(); }
    public boolean canFetchScrapAging() { return permissions.canViewDashboardScrapAging() && !permissions.isPosOperator(); }
    public boolean canFetchGoldPrices() { return permissions.canViewDashboardGoldPrices(); }
    public boolean canFetchRecentActivity() { return permissions.canViewDashboardRecentActivity(); }

    private void setVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
