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

    /**
     * هل الواجهة الأساسية للمستخدم هي واجهة الكاشير (POS) فقط؟
     * (تكون true فقط إذا كان يملك POS ولا يملك صلاحيات إدارية/مخزنية/محاسبية أعلى)
     */
    public boolean isPosOnlyLayout() {
        if (permissions == null) return false;
        return permissions.canViewPos()
                && !permissions.canViewDashboardHero()
                && !permissions.canViewAccounting()
                && !permissions.canViewAdminHub();
    }

    /**
     * هل الواجهة الأساسية للمستخدم هي واجهة البوابة الأمنية (Gate) فقط؟
     */
    public boolean isGateOnlyLayout() {
        if (permissions == null) return false;
        return permissions.canViewGates()
                && !permissions.canViewDashboardHero()
                && !permissions.canViewDashboardSales()
                && !permissions.canViewPos();
    }

    public void applyVisibility(View root) {
        if (root == null || permissions == null) return;

        // إخفاء الكل أولاً لضمان عدم وجود بقايا من جلسة سابقة
        int[] allViews = {
            R.id.layoutPosGoldDashboard, R.id.cardShiftSummary,
            R.id.horizontalScrollCarousel, R.id.dotsIndicator,
            R.id.heroBalanceCard, R.id.cardGoldPrices, R.id.salesSummaryCard,
            R.id.alertsStrip, R.id.cardInventoryByKarat, R.id.cardScrapGold,
            R.id.cardScrapAging, R.id.llRecentActivity, R.id.recentActivityHeader,
            R.id.cardCharts
        };
        for (int id : allViews) {
            setVisible(root, id, false);
        }

        // 1. حالة الكاشير (POS Variant)
        if (isPosOnlyLayout()) {
            setVisible(root, R.id.layoutPosGoldDashboard, true);
            return;
        }

        // 2. حالة البوابة الأمنية (Gate Variant)
        if (isGateOnlyLayout()) {
            setVisible(root, R.id.alertsStrip, true);
            setVisible(root, R.id.recentActivityHeader, true);
            setVisible(root, R.id.llRecentActivity, true);
            return;
        }

        // 3. الحالة القياسية (Admin / Finance / Inventory / Auditor / Branch Manager)
        // كل بطاقة تُعرض حصرياً بناءً على صلاحيتها من السيرفر
        boolean showHero  = permissions.canViewDashboardHero();
        boolean showGold  = permissions.canViewDashboardGoldPrices();
        boolean showSales = permissions.canViewDashboardSales();

        setVisible(root, R.id.heroBalanceCard, showHero);
        setVisible(root, R.id.cardGoldPrices, showGold);
        setVisible(root, R.id.salesSummaryCard, showSales);

        // إظهار الكاروسيل والنقاط فقط إذا كانت هناك بطاقة واحدة على الأقل ظاهرة بداخله
        boolean hasCarouselItems = showHero || showGold || showSales;
        setVisible(root, R.id.horizontalScrollCarousel, hasCarouselItems);
        setVisible(root, R.id.dotsIndicator, hasCarouselItems);

        // البطاقات التفصيلية
        setVisible(root, R.id.cardInventoryByKarat, permissions.canViewDashboardInventoryByKarat());
        setVisible(root, R.id.cardScrapGold, permissions.canViewDashboardScrap());
        setVisible(root, R.id.cardScrapAging, permissions.canViewDashboardScrapAging());
        setVisible(root, R.id.cardCharts, permissions.canViewDashboardCharts());

        // النشاط الأخير
        boolean showActivity = permissions.canViewDashboardRecentActivity();
        setVisible(root, R.id.recentActivityHeader, showActivity);
        setVisible(root, R.id.llRecentActivity, showActivity);

        // شريط التنبيهات (يُظهره computeAndShowAlerts لاحقاً إذا وجدت تنبيهات وكانت الصلاحية متوفرة)
        setVisible(root, R.id.alertsStrip, false);
    }

    public boolean canFetchFullDashboard() {
        if (permissions == null) return false;
        return isPosOnlyLayout()
                || isGateOnlyLayout()
                || permissions.canViewDashboardHero()
                || permissions.canViewDashboardSales()
                || permissions.canViewInventory();
    }

    public boolean canFetchCharts() {
        if (permissions == null) return false;
        return isPosOnlyLayout() || permissions.canViewDashboardCharts();
    }

    public boolean canFetchScrap() {
        if (permissions == null) return false;
        return permissions.canViewDashboardScrap() && !isPosOnlyLayout() && !isGateOnlyLayout();
    }

    public boolean canFetchScrapAging() {
        if (permissions == null) return false;
        return permissions.canViewDashboardScrapAging() && !isPosOnlyLayout() && !isGateOnlyLayout();
    }

    public boolean canFetchGoldPrices() {
        return permissions != null && permissions.canViewDashboardGoldPrices();
    }

    public boolean canFetchRecentActivity() {
        if (permissions == null) return false;
        return isPosOnlyLayout() || isGateOnlyLayout() || permissions.canViewDashboardRecentActivity();
    }

    private void setVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
