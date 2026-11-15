package com.example.uhf.manager;

import android.graphics.Color;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraInventoryDashboardFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.fragment.MiraAllActivitiesFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraScrapGoldBuyFragment;
import com.example.uhf.navigation.NavigationManager;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DashboardUIBuilder {

    private final Fragment fragment;
    private final PermissionManager permissions;

    public interface OnNavigateListener {
        void navigateTo(Fragment target, String tag);
    }

    public DashboardUIBuilder(Fragment fragment, PermissionManager permissions) {
        this.fragment = fragment;
        this.permissions = permissions;
    }

    // =========================================================
    //  1. إخفاء/إظهار الواجهات بناءً على الدور (عشرة أدوار)
    // =========================================================
    public void applyLayoutVisibility(View root, DashboardRoleController roleController) {
        if (root == null || permissions == null) return;

        hideAll(root);

        if (permissions.isSystemAdmin() || permissions.isOrgAdmin() || permissions.isBranchManager()) {
            showFullAdmin(root, roleController);
        } else if (permissions.isFinanceManager() || permissions.isAccountant()) {
            showFinanceDashboard(root, roleController);
        } else if (permissions.isInventoryManager()) {
            showInventoryDashboard(root, roleController);
        } else if (permissions.isAuditor()) {
            showAuditorDashboard(root, roleController);
        } else if (permissions.isPosOperator()) {
            showPosDashboard(root);
        } else if (permissions.isGateUser()) {
            showGateDashboard(root);
        } else {
            showFullAdmin(root, roleController);
        }
    }

    private void hideAll(View root) {
        int[] all = {
            R.id.layoutPosGoldDashboard, R.id.cardShiftSummary,
            R.id.horizontalScrollCarousel, R.id.dotsIndicator,
            R.id.heroBalanceCard, R.id.cardGoldPrices, R.id.salesSummaryCard,
            R.id.alertsStrip, R.id.cardInventoryByKarat, R.id.cardScrapGold,
            R.id.cardScrapAging, R.id.llRecentActivity, R.id.recentActivityHeader,
            R.id.cardCharts
        };
        for (int id : all) setVisible(root, id, false);
    }

    private void showFullAdmin(View root, DashboardRoleController roleController) {
        setVisible(root, R.id.horizontalScrollCarousel, true);
        setVisible(root, R.id.dotsIndicator, true);
        setVisible(root, R.id.cardInventoryByKarat, true);
        setVisible(root, R.id.cardScrapGold, true);
        setVisible(root, R.id.cardScrapAging, true);
        setVisible(root, R.id.cardCharts, true);
        setVisible(root, R.id.llRecentActivity, true);
        if (roleController != null) roleController.applyVisibility(root);
    }

    private void showFinanceDashboard(View root, DashboardRoleController roleController) {
        setVisible(root, R.id.horizontalScrollCarousel, true);
        setVisible(root, R.id.dotsIndicator, true);
        setVisible(root, R.id.cardCharts, true);
        setVisible(root, R.id.llRecentActivity, true);
        if (roleController != null) roleController.applyVisibility(root);
    }

    private void showInventoryDashboard(View root, DashboardRoleController roleController) {
        setVisible(root, R.id.horizontalScrollCarousel, true);
        setVisible(root, R.id.dotsIndicator, true);
        setVisible(root, R.id.cardInventoryByKarat, true);
        setVisible(root, R.id.cardScrapGold, true);
        setVisible(root, R.id.cardScrapAging, true);
        setVisible(root, R.id.llRecentActivity, true);
        if (roleController != null) roleController.applyVisibility(root);
    }

    private void showAuditorDashboard(View root, DashboardRoleController roleController) {
        setVisible(root, R.id.horizontalScrollCarousel, true);
        setVisible(root, R.id.dotsIndicator, true);
        setVisible(root, R.id.cardInventoryByKarat, true);
        setVisible(root, R.id.cardCharts, true);
        setVisible(root, R.id.llRecentActivity, true);
        if (roleController != null) roleController.applyVisibility(root);
    }

    private void showPosDashboard(View root) {
        setVisible(root, R.id.layoutPosGoldDashboard, true);
    }

    private void showGateDashboard(View root) {
        setVisible(root, R.id.alertsStrip, true);
        setVisible(root, R.id.llRecentActivity, true);
    }

    // =========================================================
    //  2. أزرار الإجراءات السريعة (ديناميكية)
    // =========================================================
    public void setupQuickActionButtons(View root, OnNavigateListener listener) {
        GridLayout gridContainer = root.findViewById(R.id.gridPosQuickActions);
        if (gridContainer == null) return;
        gridContainer.removeAllViews();

        boolean isPos = permissions.isPosOperator();
        boolean isGate = permissions.isGateUser();

        if (isPos) {
            addButton(gridContainer, "بيع", R.drawable.ic_pos, new MiraPosFragment(), NavigationManager.TAG_POS, listener);
            addButton(gridContainer, "مرتجع", R.drawable.ic_reports, new MiraPosFragment(), "Sales", listener);
            addButton(gridContainer, "شراء كسر", R.drawable.ic_gold_scrap, new MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY, listener);
            addButton(gridContainer, "التقارير", R.drawable.ic_reports, new MiraReportsProFragment(), NavigationManager.TAG_REPORTS, listener);
            addButton(gridContainer, "سجل المبيعات", R.drawable.ic_reports, new MiraSalesHistoryFragment(), "SalesHistory", listener);
            addButton(gridContainer, "الملف الشخصي", R.drawable.ic_user_placeholder, new MiraProfileFragment(), "Profile", listener);

        } else if (isGate) {
            if (permissions.canViewRadar()) addButton(gridContainer, "الرادار", R.drawable.ic_radar, new MiraUHFReadTagFragment(), "Radar", listener);
            if (permissions.canViewGates()) addButton(gridContainer, "بوابة الأمن", R.drawable.ic_gate, new MiraUHFReadTagFragment(), "SecureGate", listener);
            addButton(gridContainer, "سجل العمليات", R.drawable.ic_reports, new MiraAllActivitiesFragment(), "AllActivities", listener);
            addButton(gridContainer, "الملف الشخصي", R.drawable.ic_user_placeholder, new MiraProfileFragment(), "Profile", listener);

        } else {
            if (permissions.canUsePos()) addButton(gridContainer, "بيع", R.drawable.ic_pos, new MiraPosFragment(), NavigationManager.TAG_POS, listener);
            if (permissions.canBuyScrapGold()) addButton(gridContainer, "شراء كسر", R.drawable.ic_gold_scrap, new MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY, listener);
            if (permissions.canViewInventory()) addButton(gridContainer, "الجرد", R.drawable.ic_inventory_items, new MiraInventoryDashboardFragment(), "InventoryDashboard", listener);
            if (permissions.canViewSalesHistory()) addButton(gridContainer, "سجل المبيعات", R.drawable.ic_reports, new MiraSalesHistoryFragment(), "SalesHistory", listener);
            if (permissions.canViewReports()) addButton(gridContainer, "التقارير", R.drawable.ic_reports, new MiraReportsProFragment(), NavigationManager.TAG_REPORTS, listener);
            addButton(gridContainer, "الملف الشخصي", R.drawable.ic_user_placeholder, new MiraProfileFragment(), "Profile", listener);
        }
    }

    // =========================================================
    //  3. رسم المخططات
    // =========================================================
    public void renderCharts(LineChart adminChart, LineChart posChart, PieChart pieChart, JSONObject data) {
        try {
            JSONArray sales7Days = data.optJSONArray("my_sales_last_7_days");
            if (sales7Days == null) sales7Days = data.optJSONArray("sales_last_7_days");
            if (sales7Days == null) sales7Days = data.optJSONArray("sales");

            if (sales7Days != null && sales7Days.length() > 0) {
                List<Entry> entries = new ArrayList<>();
                List<Entry> sellEntries = new ArrayList<>();
                List<Entry> buyEntries = new ArrayList<>();
                final List<String> labels = new ArrayList<>();

                for (int i = 0; i < sales7Days.length(); i++) {
                    JSONObject day = sales7Days.getJSONObject(i);
                    double amount = day.optDouble("amount", 0);
                    entries.add(new Entry(i, (float) amount));
                    sellEntries.add(new Entry(i, (float) amount));
                    buyEntries.add(new Entry(i, (float) (amount * 0.95)));
                    String dateStr = day.optString("date", "");
                    if (dateStr.length() > 5) dateStr = dateStr.substring(5);
                    labels.add(dateStr);
                }

                if (adminChart != null) {
                    LineDataSet dataSet = new LineDataSet(entries, "المبيعات");
                    dataSet.setColor(Color.parseColor("#D4AF37"));
                    dataSet.setCircleColor(Color.parseColor("#D4AF37"));
                    dataSet.setLineWidth(2.5f);
                    dataSet.setCircleRadius(4f);
                    dataSet.setDrawValues(false);
                    dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                    adminChart.setData(new LineData(dataSet));
                    XAxis xAxis = adminChart.getXAxis();
                    xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
                    xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
                    xAxis.setGranularity(1f);
                    xAxis.setDrawGridLines(false);
                    adminChart.getAxisRight().setEnabled(false);
                    adminChart.getDescription().setEnabled(false);
                    adminChart.getLegend().setEnabled(false);
                    adminChart.animateX(1000);
                    adminChart.invalidate();
                }

                if (posChart != null) {
                    LineDataSet sellDataSet = new LineDataSet(sellEntries, "مبيعات");
                    sellDataSet.setColor(Color.parseColor("#D4AF37"));
                    sellDataSet.setLineWidth(3f);
                    sellDataSet.setDrawCircles(false);
                    sellDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                    sellDataSet.setDrawFilled(true);
                    sellDataSet.setFillColor(Color.parseColor("#33D4AF37"));

                    LineDataSet buyDataSet = new LineDataSet(buyEntries, "تقديري");
                    buyDataSet.setColor(Color.parseColor("#4ADE80"));
                    buyDataSet.setLineWidth(3f);
                    buyDataSet.setDrawCircles(false);
                    buyDataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                    buyDataSet.setDrawFilled(true);
                    buyDataSet.setFillColor(Color.parseColor("#334ADE80"));

                    posChart.setData(new LineData(sellDataSet, buyDataSet));
                    XAxis xAxis = posChart.getXAxis();
                    xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
                    xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
                    xAxis.setDrawGridLines(false);
                    xAxis.setDrawAxisLine(false);
                    posChart.getAxisRight().setEnabled(true);
                    posChart.getAxisLeft().setEnabled(false);
                    posChart.getDescription().setEnabled(false);
                    posChart.getLegend().setEnabled(false);
                    posChart.animateX(1000);
                    posChart.invalidate();
                }
            }

            JSONArray salesByKarat = data.optJSONArray("sales_by_karat");
            if (salesByKarat != null && pieChart != null) {
                List<PieEntry> entriesPie = new ArrayList<>();
                for (int i = 0; i < salesByKarat.length(); i++) {
                    JSONObject k = salesByKarat.getJSONObject(i);
                    entriesPie.add(new PieEntry((float) k.optDouble("amount", 0), k.optString("karat", "")));
                }
                if (!entriesPie.isEmpty()) {
                    PieDataSet dataSet = new PieDataSet(entriesPie, "");
                    dataSet.setColors(new int[]{
                        Color.parseColor("#D4AF37"), Color.parseColor("#E6B800"),
                        Color.parseColor("#CD853F"), Color.parseColor("#A0522D")
                    });
                    pieChart.setData(new PieData(dataSet));
                    pieChart.getDescription().setEnabled(false);
                    pieChart.setUsePercentValues(true);
                    pieChart.setHoleRadius(40f);
                    pieChart.animateY(1000);
                    pieChart.invalidate();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addButton(GridLayout container, String title, int iconRes, Fragment target, String tag, OnNavigateListener listener) {
        View view = fragment.getLayoutInflater().inflate(R.layout.item_quick_action_grid, container, false);
        ImageView icon = view.findViewById(R.id.ivQuickIcon);
        TextView text = view.findViewById(R.id.tvQuickText);
        icon.setImageResource(iconRes);
        text.setText(title);
        view.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            listener.navigateTo(target, tag);
        });
        container.addView(view);
    }

    private void setVisible(View root, int id, boolean visible) {
        View v = root.findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
