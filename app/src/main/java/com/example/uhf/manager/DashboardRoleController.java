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

        setVisible(root, R.id.heroBalanceCard, permissions.canViewDashboardHero());
        setVisible(root, R.id.cardGoldPrices, permissions.canViewDashboardGoldPrices());
        setVisible(root, R.id.salesSummaryCard, permissions.canViewDashboardSales());

        boolean isPos = permissions.isPosOperator();
        boolean isGate = permissions.isGateUser();

        setVisible(root, R.id.cardInventoryByKarat, permissions.canViewDashboardInventoryByKarat() && !isPos && !isGate);
        setVisible(root, R.id.cardScrapGold, permissions.canViewDashboardScrap() && !isPos && !isGate);
        setVisible(root, R.id.cardScrapAging, permissions.canViewDashboardScrapAging() && !isPos && !isGate);
        setVisible(root, R.id.cardCharts, permissions.canViewDashboardCharts() && !isPos && !isGate);

        if (!permissions.canViewDashboardAlerts()) setVisible(root, R.id.alertsStrip, false);
    }

    public boolean canFetchFullDashboard() {
        return permissions.isPosOperator() || permissions.isGateUser()
            || permissions.canViewDashboardHero() || permissions.canViewDashboardSales();
    }

    public boolean canFetchCharts() {
        return permissions.isPosOperator() || permissions.canViewDashboardCharts();
    }

    public boolean canFetchScrap() {
        return permissions.canViewDashboardScrap() && !permissions.isPosOperator() && !permissions.isGateUser();
    }

    public boolean canFetchScrapAging() {
        return permissions.canViewDashboardScrapAging() && !permissions.isPosOperator() && !permissions.isGateUser();
    }

    public boolean canFetchGoldPrices() { return permissions.canViewDashboardGoldPrices(); }

    public boolean canFetchRecentActivity() {
        return permissions.isPosOperator() || permissions.isGateUser()
            || permissions.canViewDashboardRecentActivity();
    }

    private void setVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
