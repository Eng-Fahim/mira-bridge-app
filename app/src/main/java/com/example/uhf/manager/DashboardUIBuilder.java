package com.example.uhf.manager;

import android.graphics.Color;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.fragment.MiraAdminHubFragment;
import com.example.uhf.fragment.MiraAllActivitiesFragment;
import com.example.uhf.fragment.MiraGoldCurrencyFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraInventoryDashboardFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraScrapGoldBuyFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFRadarLocationFragment;
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
    //  1. إخفاء/إظهار الواجهات بناءً على قرار السيرفر الموحد
    // =========================================================
    public void applyLayoutVisibility(View root, DashboardRoleController roleController) {
        if (root == null) return;
        if (roleController != null) {
            roleController.applyVisibility(root);
        }
    }

    // =========================================================
    //  2. أزرار الإجراءات السريعة (مبنية 100% على صلاحيات السيرفر)
    // =========================================================
    public void setupQuickActionButtons(View root, OnNavigateListener listener) {
        GridLayout gridContainer = root.findViewById(R.id.gridPosQuickActions);
        if (gridContainer == null || permissions == null) return;
        gridContainer.removeAllViews();

        // أزرار العمليات الأساسية حسب صلاحيات الوحدات
        if (permissions.canUsePos()) {
            addButton(gridContainer, "بيع", R.drawable.ic_pos, new MiraPosFragment(), NavigationManager.TAG_POS, listener);
        }
        if (permissions.canBuyScrapGold()) {
            addButton(gridContainer, "شراء كسر", R.drawable.ic_gold_scrap, new MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY, listener);
        }
        if (permissions.canViewInventory()) {
            addButton(gridContainer, "الجرد", R.drawable.ic_inventory_items, new MiraInventoryDashboardFragment(), "InventoryDashboard", listener);
        }
        if (permissions.canUseIdentity()) {
            addButton(gridContainer, "التكويد", R.drawable.ic_identity_studio, new MiraIdentityStudioFragment(), "IdentityStudio", listener);
        }
        if (permissions.canViewTransfers()) {
            addButton(gridContainer, "التحويلات", R.drawable.ic_transfer, new MiraTransferFragment(), "Transfers", listener);
        }
        if (permissions.canViewRadar()) {
            addButton(gridContainer, "الرادار", R.drawable.ic_radar, new MiraUHFRadarLocationFragment(), "Radar", listener);
        }
        if (permissions.canViewGates()) {
            addButton(gridContainer, "البوابات", R.drawable.ic_security, new MiraSecureGateFragment(), "SecureGate", listener);
        }
        if (permissions.canViewAccounting()) {
            addButton(gridContainer, "المحاسبة", R.drawable.ic_accounting, new MiraAccountingFragment(), "Accounting", listener);
        }
        if (permissions.canViewSalesHistory()) {
            addButton(gridContainer, "سجل المبيعات", R.drawable.ic_sales_history, new MiraSalesHistoryFragment(), "SalesHistory", listener);
        }
        if (permissions.canViewReports()) {
            addButton(gridContainer, "التقارير", R.drawable.ic_reports, new MiraReportsProFragment(), NavigationManager.TAG_REPORTS, listener);
        }
        if (permissions.canViewGoldRates()) {
            addButton(gridContainer, "أسعار الذهب", R.drawable.ic_reports, new MiraGoldCurrencyFragment(), "GoldRates", listener);
        }
        if (permissions.canViewAdminHub()) {
            addButton(gridContainer, "الإدارة", R.drawable.ic_admin_hub, new MiraAdminHubFragment(), "AdminHub", listener);
        }

        // زر دائم للجميع
        addButton(gridContainer, "الملف الشخصي", R.drawable.ic_user_placeholder, new MiraProfileFragment(), "Profile", listener);
    }

    // =========================================================
    //  3. رسم المخططات
    // =========================================================
    public void renderCharts(LineChart adminChart, LineChart posChart, PieChart pieChart, JSONObject data) {
        if (data == null) return;
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

                if (adminChart != null && adminChart.getVisibility() == View.VISIBLE) {
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

                if (posChart != null && posChart.getVisibility() == View.VISIBLE) {
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
            if (salesByKarat != null && pieChart != null && pieChart.getVisibility() == View.VISIBLE) {
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
        if (!fragment.isAdded() || fragment.getContext() == null) return;
        View view = fragment.getLayoutInflater().inflate(R.layout.item_quick_action_grid, container, false);
        ImageView icon = view.findViewById(R.id.ivQuickIcon);
        TextView text = view.findViewById(R.id.tvQuickText);
        if (icon != null) icon.setImageResource(iconRes);
        if (text != null) text.setText(title);
        view.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            listener.navigateTo(target, tag);
        });
        container.addView(view);
    }
}
