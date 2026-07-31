package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

public class JewelryDashboardFragment extends Fragment {

    // المخزن
    private TextView tvTotalItems, tvTotalWeight;
    // المبيعات
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    // البوابات
    private TextView tvGatesActive, tvGatesOffline, tvGatesAlarms;
    // الجرد
    private TextView tvLastAuditDate, tvAuditAccuracy, tvAuditMissing;
    private BarChart barChartWeights;

    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_jewelry_dashboard, container, false);

        // ربط عناصر المخزن
        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);

        // ربط عناصر المبيعات
        tvSalesToday = v.findViewById(R.id.tvSalesToday);
        tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
        tvSalesYear = v.findViewById(R.id.tvSalesYear);

        // ربط عناصر البوابات
        tvGatesActive = v.findViewById(R.id.tvGatesActive);
        tvGatesOffline = v.findViewById(R.id.tvGatesOffline);
        tvGatesAlarms = v.findViewById(R.id.tvGatesAlarms);

        // ربط عناصر الجرد
        tvLastAuditDate = v.findViewById(R.id.tvLastAuditDate);
        tvAuditAccuracy = v.findViewById(R.id.tvAuditAccuracy);
        tvAuditMissing = v.findViewById(R.id.tvAuditMissing);

        barChartWeights = v.findViewById(R.id.barChartWeights);

        configureChartStyles();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                fetchFullEnterpriseData();
                autoRefreshHandler.postDelayed(this, 15000); // تحديث كل 15 ثانية للإنذارات الحية
            }
        };
        autoRefreshHandler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (refreshRunnable != null) {
            autoRefreshHandler.removeCallbacks(refreshRunnable);
        }
    }

    private void configureChartStyles() {
        barChartWeights.getDescription().setEnabled(false);
        barChartWeights.getLegend().setEnabled(false);
        barChartWeights.getAxisRight().setEnabled(false);
        barChartWeights.getAxisLeft().setTextColor(Color.parseColor("#94A3B8"));
        barChartWeights.getAxisLeft().setGridColor(Color.parseColor("#1E293B"));

        XAxis xAxis = barChartWeights.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.parseColor("#94A3B8"));
        xAxis.setDrawGridLines(false);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(new String[]{"24K", "22K", "21K", "18K"}));
    }

    private void fetchFullEnterpriseData() {
        String apiUrl = "https://your-domain.com/wp-json/mira/v1/enterprise-dashboard";

        new Thread(() -> {
            try {
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    InputStream in = conn.getInputStream();
                    Scanner scanner = new Scanner(in).useDelimiter("\\A");
                    String response = scanner.hasNext() ? scanner.next() : "";

                    JSONObject json = new JSONObject(response);

                    // 1. بيانات المخزن
                    JSONObject inv = json.optJSONObject("inventory");
                    int totalItems = inv != null ? inv.optInt("total_items") : 0;
                    double totalWeight = inv != null ? inv.optDouble("total_weight") : 0.0;

                    // 2. بيانات المبيعات
                    JSONObject sales = json.optJSONObject("sales");
                    String sToday = sales != null ? sales.optString("today", "$0") : "$0";
                    String sMonth = sales != null ? sales.optString("month", "$0") : "$0";
                    String sYear = sales != null ? sales.optString("year", "$0") : "$0";

                    // 3. بيانات البوابات
                    JSONObject gates = json.optJSONObject("gates");
                    int gActive = gates != null ? gates.optInt("active") : 0;
                    int gOffline = gates != null ? gates.optInt("offline") : 0;
                    int gAlarms = gates != null ? gates.optInt("alarms_today") : 0;

                    // 4. بيانات الجرد
                    JSONObject audit = json.optJSONObject("last_audit");
                    String aDate = audit != null ? audit.optString("date") : "--";
                    double aAccuracy = audit != null ? audit.optDouble("accuracy") : 0.0;
                    int aMissing = audit != null ? audit.optInt("missing_items") : 0;

                    // 5. الأوزان
                    JSONObject wObj = inv != null ? inv.optJSONObject("weights") : null;
                    float k24 = (float) (wObj != null ? wObj.optDouble("k24") : 0);
                    float k22 = (float) (wObj != null ? wObj.optDouble("k22") : 0);
                    float k21 = (float) (wObj != null ? wObj.optDouble("k21") : 0);
                    float k18 = (float) (wObj != null ? wObj.optDouble("k18") : 0);

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> updateEnterpriseUI(
                                totalItems, totalWeight,
                                sToday, sMonth, sYear,
                                gActive, gOffline, gAlarms,
                                aDate, aAccuracy, aMissing,
                                k24, k22, k21, k18
                        ));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void updateEnterpriseUI(int items, double weight,
                                    String sToday, String sMonth, String sYear,
                                    int gActive, int gOffline, int gAlarms,
                                    String aDate, double aAccuracy, int aMissing,
                                    float k24, float k22, float k21, float k18) {
        // المخزن
        tvTotalItems.setText(String.format(Locale.US, "%,d", items));
        tvTotalWeight.setText(String.format(Locale.US, "%.1f g", weight));

        // المبيعات
        tvSalesToday.setText(sToday);
        tvSalesMonth.setText(sMonth);
        tvSalesYear.setText(sYear);

        // البوابات
        tvGatesActive.setText(String.valueOf(gActive));
        tvGatesOffline.setText(String.valueOf(gOffline));
        tvGatesAlarms.setText(String.valueOf(gAlarms));

        // الجرد
        tvLastAuditDate.setText(aDate);
        tvAuditAccuracy.setText(String.format(Locale.US, "الدقة: %.1f%%", aAccuracy));
        tvAuditMissing.setText(String.format(Locale.US, "المفقود: %d قطعة", aMissing));

        // الرسم البياني للأوزان
        List<BarEntry> barEntries = new ArrayList<>();
        barEntries.add(new BarEntry(0, k24));
        barEntries.add(new BarEntry(1, k22));
        barEntries.add(new BarEntry(2, k21));
        barEntries.add(new BarEntry(3, k18));

        BarDataSet barDataSet = new BarDataSet(barEntries, "");
        barDataSet.setColors(new int[]{
                Color.parseColor("#10B981"),
                Color.parseColor("#3B82F6"),
                Color.parseColor("#F59E0B"),
                Color.parseColor("#EF4444")
        });
        barDataSet.setValueTextColor(Color.WHITE);
        barDataSet.setValueTextSize(10f);

        barChartWeights.setData(new BarData(barDataSet));
        barChartWeights.invalidate();
    }
}
