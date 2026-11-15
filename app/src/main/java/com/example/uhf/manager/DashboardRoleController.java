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

        // 1. البطاقات العلوية (Carousel)
        setVisible(root, R.id.heroBalanceCard, permissions.canViewDashboardHero());
        setVisible(root, R.id.cardGoldPrices, permissions.canViewDashboardGoldPrices());
        setVisible(root, R.id.salesSummaryCard, permissions.canViewDashboardSales());
        
        // 2. موظف المبيعات لا يرى المخزون ولا كسر الذهب
        boolean isPos = permissions.isPosOperator();
        
        setVisible(root, R.id.cardInventoryByKarat, permissions.canViewDashboardInventoryByKarat() && !isPos);
        setVisible(root, R.id.cardScrapGold, permissions.canViewDashboardScrap() && !isPos);
        setVisible(root, R.id.cardScrapAging, permissions.canViewDashboardScrapAging() && !isPos);

        // 3. الرسوم البيانية (للإدارة)
        setVisible(root, R.id.cardCharts, permissions.canViewDashboardCharts() && !isPos);

        // 4. التنبيهات
        if (!permissions.canViewDashboardAlerts()) {
            setVisible(root, R.id.alertsStrip, false);
        }
    }

    // Guards: منع أو السماح بطلب البيانات من السيرفر
    public boolean canFetchFullDashboard() {
        return permissions.isPosOperator() || permissions.canViewDashboardHero() || permissions.canViewDashboardSales();
    }
    
    // ✅ السماح للكاشير بجلب الرسوم البيانية (الخاصة به)
    public boolean canFetchCharts() { 
        return permissions.isPosOperator() || permissions.canViewDashboardCharts(); 
    }
    
    public boolean canFetchScrap() { 
        return permissions.canViewDashboardScrap() && !permissions.isPosOperator(); 
    }
    
    public boolean canFetchScrapAging() { 
        return permissions.canViewDashboardScrapAging() && !permissions.isPosOperator(); 
    }
    
    public boolean canFetchGoldPrices() { 
        return permissions.canViewDashboardGoldPrices(); 
    }
    
    // ✅ السماح للكاشير بجلب العمليات الأخيرة
    public boolean canFetchRecentActivity() { 
        return permissions.isPosOperator() || permissions.canViewDashboardRecentActivity(); 
    }

    private void setVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
