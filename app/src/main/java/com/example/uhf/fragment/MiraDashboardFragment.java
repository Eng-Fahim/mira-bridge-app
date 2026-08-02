package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient; // ⭐ العميل المركزي

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraDashboardFragment extends Fragment {

    // عناصر الواجهة
    private TextView tvGreeting, tvDate, tvTime;
    private TextView tvTotalItems, tvSoldToday, tvActiveGates;
    private TextView tvSystemStatus, tvLastSync;
    private View indicatorApi, indicatorDb, indicatorGate;
    private TextView tvRecent1, tvRecent2, tvRecent3;
    private View cardScan, cardGate, cardRadar, cardSettings;

    // الخيوط الموازية وإدارة الوقت
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timeHandler = new Handler(Looper.getMainLooper());

    // ⭐ العميل المركزي
    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_dashboard, container, false);

        // ⭐ تهيئة العميل
        apiClient = MiraApiClient.getInstance(requireContext());

        initViews(v);
        setupNavigationListeners();
        startTimeClock();
        fetchDashboardData(); // ⭐ استخدام الدالة الجديدة

        return v;
    }

    private void initViews(View v) {
        tvGreeting = v.findViewById(R.id.tvGreeting);
        tvDate = v.findViewById(R.id.tvDate);
        tvTime = v.findViewById(R.id.tvTime);

        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvSoldToday = v.findViewById(R.id.tvSoldToday);
        tvActiveGates = v.findViewById(R.id.tvActiveGates);

        tvSystemStatus = v.findViewById(R.id.tvSystemStatus);
        tvLastSync = v.findViewById(R.id.tvLastSync);

        indicatorApi = v.findViewById(R.id.indicatorApi);
        indicatorDb = v.findViewById(R.id.indicatorDb);
        indicatorGate = v.findViewById(R.id.indicatorGate);

        tvRecent1 = v.findViewById(R.id.tvRecent1);
        tvRecent2 = v.findViewById(R.id.tvRecent2);
        tvRecent3 = v.findViewById(R.id.tvRecent3);

        cardScan = v.findViewById(R.id.cardScan);
        cardGate = v.findViewById(R.id.cardGate);
        cardRadar = v.findViewById(R.id.cardRadar);
        cardSettings = v.findViewById(R.id.cardSettings);
    }

    private void setupNavigationListeners() {
        if (cardScan != null) cardScan.setOnClickListener(v -> showToast("فتح شاشة الفحص"));
        if (cardGate != null) cardGate.setOnClickListener(v -> showToast("فتح شاشة البوابة"));
        if (cardRadar != null) cardRadar.setOnClickListener(v -> showToast("فتح شاشة الرادار"));
        if (cardSettings != null) cardSettings.setOnClickListener(v -> showToast("فتح شاشة الإعدادات"));
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private void startTimeClock() {
        timeHandler.post(new Runnable() {
            @Override
            public void run() {
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("ar"));
                Date now = new Date();

                if (tvTime != null) tvTime.setText(timeFormat.format(now));
                if (tvDate != null) tvDate.setText(dateFormat.format(now));

                timeHandler.postDelayed(this, 1000);
            }
        });
    }

    // ===================== ⭐ API Calls via MiraApiClient =====================

    /**
     * 📊 جلب بيانات لوحة القيادة من API
     */
    private void fetchDashboardData() {
        executor.execute(() -> {
            try {
                // ⭐ استخدام العميل المركزي - استدعاء واحد فقط
                MiraApiClient.ApiResponse response = apiClient.getDashboardSummary();

                if (response.isSuccess && response.data != null) {
                    JSONObject summary = response.data.optJSONObject("summary");
                    
                    if (summary != null) {
                        int totalItems = summary.optInt("total_items", 0);
                        int soldToday = summary.optInt("sold_today", 0);
                        int activeGates = summary.optInt("active_gates", 0);
                        JSONArray recentLogs = summary.optJSONArray("recent_logs");

                        mainHandler.post(() -> {
                            // تحديث الإحصائيات
                            if (tvTotalItems != null) tvTotalItems.setText(formatNumber(totalItems));
                            if (tvSoldToday != null) tvSoldToday.setText(String.valueOf(soldToday));
                            if (tvActiveGates != null) tvActiveGates.setText(String.valueOf(activeGates));

                            // تحديث المؤشرات
                            if (indicatorApi != null) indicatorApi.setBackgroundColor(0xFF16A34A);
                            if (indicatorDb != null) indicatorDb.setBackgroundColor(0xFF16A34A);
                            if (indicatorGate != null) {
                                indicatorGate.setBackgroundColor(activeGates > 0 ? 0xFF16A34A : 0xFFF59E0B);
                            }

                            // تحديث الأنشطة الأخيرة
                            updateRecentLogs(recentLogs);

                            // تحديث وقت المزامنة
                            if (tvLastSync != null) {
                                tvLastSync.setText("🟢 متصل | تم التحديث: " +
                                    new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                            }
                            if (tvSystemStatus != null) {
                                tvSystemStatus.setText("✅ جميع الأنظمة تعمل");
                            }
                        });
                    }
                } else {
                    // فشل الاستجابة
                    mainHandler.post(() -> handleApiError(response.code, response.error));
                }

            } catch (Exception e) {
                mainHandler.post(() -> handleApiError(-1, e.getMessage()));
            }
        });
    }

    /**
     * تحديث قائمة الأنشطة الأخيرة
     */
    private void updateRecentLogs(JSONArray recentLogs) {
        if (recentLogs == null || recentLogs.length() == 0) {
            if (tvRecent1 != null) tvRecent1.setText("— لا توجد أنشطة حديثة —");
            if (tvRecent2 != null) tvRecent2.setText("");
            if (tvRecent3 != null) tvRecent3.setText("");
            return;
        }

        TextView[] recentViews = {tvRecent1, tvRecent2, tvRecent3};
        for (int i = 0; i < Math.min(recentLogs.length(), 3); i++) {
            JSONObject log = recentLogs.optJSONObject(i);
            if (log != null && recentViews[i] != null) {
                String epc = log.optString("epc", "—");
                String action = log.optString("action_taken", "—");
                String time = log.optString("created_at", "");
                
                // تنسيق الوقت
                String displayTime = "";
                if (!time.isEmpty()) {
                    try {
                        displayTime = time.substring(11, 16); // HH:mm
                    } catch (Exception e) {
                        displayTime = time;
                    }
                }

                String icon = getActionIcon(action);
                recentViews[i].setText(String.format("%s %s | %s | %s", icon, displayTime, action, epc));
            }
        }
    }

    /**
     * أيقونة حسب نوع النشاط
     */
    private String getActionIcon(String action) {
        if (action == null) return "📋";
        switch (action.toUpperCase()) {
            case "AUTHORIZED": return "✅";
            case "DENIED": return "🚨";
            case "INVENTORY_SCAN": return "📊";
            case "POS_SOLD": return "💰";
            case "SPOTLIGHT": return "💡";
            case "PROGRAMMED": return "🔧";
            case "LOCKED": return "🔒";
            case "DEACTIVATED": return "⚡";
            default: return "📋";
        }
    }

    /**
     * معالجة أخطاء API
     */
    private void handleApiError(int code, String error) {
        if (indicatorApi != null) indicatorApi.setBackgroundColor(0xFFDC2626);
        if (indicatorDb != null) indicatorDb.setBackgroundColor(0xFFF59E0B);
        if (indicatorGate != null) indicatorGate.setBackgroundColor(0xFF94A3B8);
        
        if (tvSystemStatus != null) {
            tvSystemStatus.setText("⚠️ خطأ في الاتصال");
        }
        if (tvLastSync != null) {
            String errorMsg = error != null && !error.isEmpty() ? error : "Code: " + code;
            if (errorMsg.length() > 30) errorMsg = errorMsg.substring(0, 30) + "...";
            tvLastSync.setText("🔴 غير متصل: " + errorMsg);
        }
    }

    /**
     * تنسيق الأرقام الكبيرة
     */
    private String formatNumber(int number) {
        if (number >= 1000) {
            return String.format(Locale.US, "%d,%03d", number / 1000, number % 1000);
        }
        return String.valueOf(number);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timeHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdown();
    }
}
