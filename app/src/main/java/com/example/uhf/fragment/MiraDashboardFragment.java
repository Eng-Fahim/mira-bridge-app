package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    private TextView tvGreeting, tvDate, tvTime, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvGatesActive, tvGatesOffline, tvGatesAlarms;
    private TextView tvLastSync;
    private View indicatorApi, indicatorDb, indicatorGate;
    private TextView tvLastAuditDate, tvAuditAccuracy, tvAuditMissing;
    private TextView tvRecent1, tvRecent2, tvRecent3;
    private View cardScan, cardGate, cardRadar, cardSettings;
    private BarChart barChartWeights;

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        executor = Executors.newSingleThreadExecutor();
        apiClient = MiraApiClient.getInstance(requireContext());

        initViews(view);
        setupNavigationListeners();
        startTimeClock();
        fetchDashboardData();
    }

    private void initViews(View v) {
        tvGreeting = v.findViewById(R.id.tvGreeting);
        tvDate = v.findViewById(R.id.tvDate);
        tvTime = v.findViewById(R.id.tvTime);
        tvLocation = v.findViewById(R.id.tvLocation);
        tvLiveStatus = v.findViewById(R.id.tvLiveStatus);
        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
        tvSoldToday = v.findViewById(R.id.tvSoldToday);
        tvSalesToday = v.findViewById(R.id.tvSalesToday);
        tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
        tvSalesYear = v.findViewById(R.id.tvSalesYear);
        tvGatesActive = v.findViewById(R.id.tvGatesActive);
        tvGatesOffline = v.findViewById(R.id.tvGatesOffline);
        tvGatesAlarms = v.findViewById(R.id.tvGatesAlarms);
        tvLastSync = v.findViewById(R.id.tvLastSync);
        indicatorApi = v.findViewById(R.id.indicatorApi);
        indicatorDb = v.findViewById(R.id.indicatorDb);
        indicatorGate = v.findViewById(R.id.indicatorGate);
        tvLastAuditDate = v.findViewById(R.id.tvLastAuditDate);
        tvAuditAccuracy = v.findViewById(R.id.tvAuditAccuracy);
        tvAuditMissing = v.findViewById(R.id.tvAuditMissing);
        tvRecent1 = v.findViewById(R.id.tvRecent1);
        tvRecent2 = v.findViewById(R.id.tvRecent2);
        tvRecent3 = v.findViewById(R.id.tvRecent3);
        cardScan = v.findViewById(R.id.cardScan);
        cardGate = v.findViewById(R.id.cardGate);
        cardRadar = v.findViewById(R.id.cardRadar);
        cardSettings = v.findViewById(R.id.cardSettings);
        barChartWeights = v.findViewById(R.id.barChartWeights);
    }

    private void setupNavigationListeners() {
        if (getActivity() == null) return;

        if (cardScan != null) {
            cardScan.setOnClickListener(v -> switchTab(getString(R.string.uhf_msg_tab_scan)));
        }
        if (cardGate != null) {
            cardGate.setOnClickListener(v -> switchTab("MIRA Secure Gate"));
        }
        if (cardRadar != null) {
            cardRadar.setOnClickListener(v -> switchTab(getString(R.string.uhf_radar_loaction)));
        }
        if (cardSettings != null) {
            cardSettings.setOnClickListener(v -> switchTab(getString(R.string.uhf_msg_tab_set)));
        }
    }

    private void switchTab(String tag) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                if (activity.mTabHost != null) {
                    activity.mTabHost.setCurrentTabByTag(tag);
                }
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "جاري الفتح...", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error switching tab: " + e.getMessage());
        }
    }

    private void startTimeClock() {
        timeHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE، d MMMM yyyy", new Locale("ar"));
                    Date now = new Date();
                    if (tvTime != null) tvTime.setText(timeFormat.format(now));
                    if (tvDate != null) tvDate.setText(dateFormat.format(now));
                    timeHandler.postDelayed(this, 1000);
                } catch (Exception ignored) {}
            }
        });
    }

    private void fetchDashboardData() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        
        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardSummary();
                if (response.isSuccess && response.data != null) {
                    JSONObject summary = response.data.optJSONObject("summary");
                    if (summary != null) {
                        processSummaryData(summary);
                    }
                } else {
                    mainHandler.post(() -> handleApiError(response.code, response.error));
                }
            } catch (Exception e) {
                mainHandler.post(() -> handleApiError(-1, e.getMessage()));
            }
        });
    }

    private void processSummaryData(JSONObject summary) {
        int totalItems = summary.optInt("total_items", 0);
        double totalWeight = summary.optDouble("total_weight_grams", 0);
        int soldToday = summary.optInt("sold_today", 0);
        int gatesActive = summary.optInt("gates_active", 0);
        int gatesOffline = summary.optInt("gates_offline", 0);
        int gatesAlarms = summary.optInt("gates_alarms_today", 0);
        String lastAuditDate = summary.optString("last_audit_date", "");
        double auditAccuracy = summary.optDouble("audit_accuracy_percent", 0);
        int auditMissing = summary.optInt("audit_missing_items", 0);

        mainHandler.post(() -> {
            if (tvTotalItems != null) tvTotalItems.setText(String.valueOf(totalItems));
            if (tvTotalWeight != null) tvTotalWeight.setText(String.format(Locale.US, "%.1f g", totalWeight));
            if (tvSoldToday != null) tvSoldToday.setText(String.valueOf(soldToday));
            if (tvGatesActive != null) tvGatesActive.setText(String.valueOf(gatesActive));
            if (tvGatesOffline != null) tvGatesOffline.setText(String.valueOf(gatesOffline));
            if (tvGatesAlarms != null) tvGatesAlarms.setText(String.valueOf(gatesAlarms));
            if (tvLastAuditDate != null) tvLastAuditDate.setText(lastAuditDate.isEmpty() ? "—" : lastAuditDate);
            if (tvAuditAccuracy != null) tvAuditAccuracy.setText(String.format(Locale.US, "الدقة: %.1f%%", auditAccuracy));
            if (tvAuditMissing != null) tvAuditMissing.setText(String.format("المفقود: %d قطعة", auditMissing));
            
            updateIndicators(gatesActive > 0);
            
            String syncTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            if (tvLastSync != null) tvLastSync.setText("آخر مزامنة: " + syncTime);
            if (tvLiveStatus != null) tvLiveStatus.setText("🟢 ONLINE");
        });
    }

    private void updateIndicators(boolean gatesActive) {
        if (indicatorApi != null) indicatorApi.setBackgroundColor(0xFF16A34A);
        if (indicatorDb != null) indicatorDb.setBackgroundColor(0xFF16A34A);
        if (indicatorGate != null) indicatorGate.setBackgroundColor(gatesActive ? 0xFF16A34A : 0xFFF59E0B);
    }

    private void handleApiError(int code, String error) {
        if (indicatorApi != null) indicatorApi.setBackgroundColor(0xFFDC2626);
        if (indicatorDb != null) indicatorDb.setBackgroundColor(0xFFF59E0B);
        if (indicatorGate != null) indicatorGate.setBackgroundColor(0xFF94A3B8);
        if (tvLastSync != null) tvLastSync.setText("🔴 غير متصل");
        if (tvLiveStatus != null) tvLiveStatus.setText("🔴 OFFLINE");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timeHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
