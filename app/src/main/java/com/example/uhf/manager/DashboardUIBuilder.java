package com.example.uhf.manager;

import android.graphics.Color;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.fragment.*;
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
    //  1. إخفاء وإظهار الكروت الأساسية بناءً على الدور
    // =========================================================
    public void applyLayoutVisibility(View root, DashboardRoleController roleController) {
        if (root == null || permissions == null) return;

        if (permissions.isPosOperator()) {
            // واجهة الكاشير (POS)
            setVisible(root, R.id.layoutPosGoldDashboard, true);
            setVisible(root, R.id.cardShiftSummary, false);
            setVisible(root, R.id.horizontalScrollCarousel, false);
            setVisible(root, R.id.dotsIndicator, false);
            
            int[] idsToHide = {
                R.id.alertsStrip, R.id.cardInventoryByKarat, R.id.cardScrapGold,
                R.id.cardScrapAging, R.id.recentActivityHeader, R.id.llRecentActivity, R.id.cardCharts
            };
            for (int id : idsToHide) setVisible(root, id, false);
            setVisible(root, R.id.hsvQuickActions, true);

        } else {
            // واجهة المدير (Admin / Manager)
            setVisible(root, R.id.layoutPosGoldDashboard, false);
            setVisible(root, R.id.cardShiftSummary, false);
            setVisible(root, R.id.horizontalScrollCarousel, true);
            setVisible(root, R.id.heroBalanceCard, true);
            setVisible(root, R.id.dot1, true);
            setVisible(root, R.id.dotsIndicator, true);
            setVisible(root, R.id.cardCharts, true);
            setVisible(root, R.id.hsvQuickActions, true);
            
            if (roleController != null) roleController.applyVisibility(root);
        }
    }

    // =========================================================
    //  2. بناء أزرار الإجراءات السريعة بناءً على الدور
    // =========================================================
    public void setupQuickActionButtons(View root, OnNavigateListener listener) {
        GridLayout gridContainer = root.findViewById(R.id.gridPosQuickActions);
        if (gridContainer == null) return;
        gridContainer.removeAllViews();

        if (permissions.isPosOperator()) {
            // أزرار الكاشير
            addButton(gridContainer, "بيع", R.drawable.ic_pos, new MiraPosFragment(), NavigationManager.TAG_POS, listener);
            addButton(gridContainer, "مرتجع", R.drawable.ic_reports, new MiraPosFragment(), "Sales", listener);
            addButton(gridContainer, "شراء كسر", R.drawable.ic_gold_scrap, new MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY, listener);
            addButton(gridContainer, "التقارير", R.drawable.ic_reports, new MiraReportsProFragment(), NavigationManager.TAG_REPORTS, listener);
            addButton(gridContainer, "سجل المبيعات", R.drawable.ic_reports, new MiraSalesHistoryFragment(), "SalesHistory", listener);
            addButton(gridContainer, "الملف الشخصي", R.drawable.ic_user_placeholder, new MiraProfileFragment(), "Profile", listener);
        } else {
            // أزرار الإدارة والجرد
            if (permissions.canViewInventory()) {
                addButton(gridContainer, "الجرد", R.drawable.ic_inventory_items, new MiraInventoryDashboardFragment(), "InventoryDashboard", listener);
            }
            if (permissions.canBuyScrapGold()) {
                addButton(gridContainer, "شراء كسر", R.drawable.ic_gold_scrap, new MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY, listener);
            }
            if (permissions.canViewSalesHistory()) {
                addButton(gridContainer, "سجل المبيعات", R.drawable.ic_reports, new MiraSalesHistoryFragment(), "SalesHistory", listener);
            }
            if (permissions.canViewReports()) {
                addButton(gridContainer, "التقارير", R.drawable.ic_reports, new MiraReportsProFragment(), NavigationManager.TAG_REPORTS, listener);
            }
            addButton(gridContainer, "الملف الشخصي", R.drawable.ic_user_placeholder, new MiraProfileFragment(), "Profile", listener);
        }
    }

    // =========================================================
    //  3. رسم المخططات البيانية (الرسوم)
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

                // 1. مخطط المدير
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

                // 2. مخطط الكاشير
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

            // 3. مخطط النسب (PieChart)
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

    // Helpers
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
