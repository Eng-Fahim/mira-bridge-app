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
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JewelryDashboardFragment extends Fragment {

    private TextView tvTotalItems, tvTotalWeight, tvGoldPrice;
    private BarChart barChartWeights;
    private PieChart pieChartTarget;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String API_URL = "https://your-domain.com/wp-json/mira-gate/v1/dashboard/summary";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_jewelry_dashboard, container, false);

        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
        tvGoldPrice = v.findViewById(R.id.tvGoldPrice);
        barChartWeights = v.findViewById(R.id.barChartWeights);
        pieChartTarget = v.findViewById(R.id.pieChartTarget);

        setupCharts();
        fetchDashboardData();

        return v;
    }

    private void setupCharts() {
        // إعداد الرسم البياني المفتوح (Bar Chart) للأوزان
        List<BarEntry> entries = new ArrayList<>();
        entries.add(new BarEntry(0, 30f)); // عيار 24
        entries.add(new BarEntry(1, 45f)); // عيار 22
        entries.add(new BarEntry(2, 180f)); // عيار 21
        entries.add(new BarEntry(3, 65f)); // عيار 18

        BarDataSet dataSet = new BarDataSet(entries, "الأوزان");
        dataSet.setColors(new int[]{Color.parseColor("#10B981"), Color.parseColor("#3B82F6"), Color.parseColor("#F59E0B"), Color.parseColor("#EF4444")});
        BarData barData = new BarData(dataSet);
        barChartWeights.setData(barData);
        barChartWeights.invalidate();

        // إعداد الرسم الدائري (Pie Chart) للمستهدف
        List<PieEntry> pieEntries = new ArrayList<>();
        pieEntries.add(new PieEntry(12f, "المنجز اليوم"));
        pieEntries.add(new PieEntry(38f, "المتبقي"));

        PieDataSet pieDataSet = new PieDataSet(pieEntries, "");
        pieDataSet.setColors(new int[]{Color.parseColor("#10B981"), Color.parseColor("#1E293B")});
        PieData pieData = new PieData(pieDataSet);
        pieChartTarget.setData(pieData);
        pieChartTarget.setHoleRadius(75f);
        pieChartTarget.setHoleColor(Color.parseColor("#020617"));
        pieChartTarget.invalidate();
    }

    private void fetchDashboardData() {
        executor.execute(() -> {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) response.append(line);
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    if (json.optBoolean("success")) {
                        JSONObject summary = json.getJSONObject("summary");
                        int totalItems = summary.optInt("total_items", 0);

                        mainHandler.post(() -> {
                            if (tvTotalItems != null) tvTotalItems.setText(String.valueOf(totalItems));
                        });
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
