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

    // ==================== عناصر الواجهة ====================
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

    // إدارة الوقت والخيوط
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = null;
        try {
            v = inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
            Log.d(TAG, "✅ Layout inflated successfully");

            apiClient = MiraApiClient.getInstance(requireContext());

            initViews(v);
            setupNavigationListeners();
            startTimeClock();
            fetchDashboardData();

        } catch (Exception e) {
            Log.e(TAG, "❌ Fatal error in onCreateView: " + e.getMessage(), e);
            if (getContext() != null) {
                Toast.makeText(getContext(), "خطأ في تحميل لوحة التحكم: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        return v;
    }

    // ==================== تهيئة جميع عناصر الواجهة (مع حماية null) ====================
    private void initViews(View v) {
        if (v == null) {
            Log.e(TAG, "❌ View is null, cannot init views");
            return;
        }

        try {
            // الهيدر والتحية
            tvGreeting = safeFindTextView(v, R.id.tvGreeting, "tvGreeting");
            tvDate = safeFindTextView(v, R.id.tvDate, "tvDate");
            tvTime = safeFindTextView(v, R.id.tvTime, "tvTime");
            tvLocation = safeFindTextView(v, R.id.tvLocation, "tvLocation");
            tvLiveStatus = safeFindTextView(v, R.id.tvLiveStatus, "tvLiveStatus");

            // إحصائيات المخزون
            tvTotalItems = safeFindTextView(v, R.id.tvTotalItems, "tvTotalItems");
            tvTotalWeight = safeFindTextView(v, R.id.tvTotalWeight, "tvTotalWeight");
            tvSoldToday = safeFindTextView(v, R.id.tvSoldToday, "tvSoldToday");

            // المبيعات التفصيلية
            tvSalesToday = safeFindTextView(v, R.id.tvSalesToday, "tvSalesToday");
            tvSalesMonth = safeFindTextView(v, R.id.tvSalesMonth, "tvSalesMonth");
            tvSalesYear = safeFindTextView(v, R.id.tvSalesYear, "tvSalesYear");

            // بوابات RFID
            tvGatesActive = safeFindTextView(v, R.id.tvGatesActive, "tvGatesActive");
            tvGatesOffline = safeFindTextView(v, R.id.tvGatesOffline, "tvGatesOffline");
            tvGatesAlarms = safeFindTextView(v, R.id.tvGatesAlarms, "tvGatesAlarms");

            // مؤشرات النظام
            tvLastSync = safeFindTextView(v, R.id.tvLastSync, "tvLastSync");
            indicatorApi = v.findViewById(R.id.indicatorApi);
            indicatorDb = v.findViewById(R.id.indicatorDb);
            indicatorGate = v.findViewById(R.id.indicatorGate);

            // سجل الجرد
            tvLastAuditDate = safeFindTextView(v, R.id.tvLastAuditDate, "tvLastAuditDate");
            tvAuditAccuracy = safeFindTextView(v, R.id.tvAuditAccuracy, "tvAuditAccuracy");
            tvAuditMissing = safeFindTextView(v, R.id.tvAuditMissing, "tvAuditMissing");

            // السجل المباشر
            tvRecent1 = safeFindTextView(v, R.id.tvRecent1, "tvRecent1");
            tvRecent2 = safeFindTextView(v, R.id.tvRecent2, "tvRecent2");
            tvRecent3 = safeFindTextView(v, R.id.tvRecent3, "tvRecent3");

            // اختصارات سريعة
            cardScan = v.findViewById(R.id.cardScan);
            cardGate = v.findViewById(R.id.cardGate);
            cardRadar = v.findViewById(R.id.cardRadar);
            cardSettings = v.findViewById(R.id.cardSettings);

            // الرسم البياني
            barChartWeights = v.findViewById(R.id.barChartWeights);

            Log.d(TAG, "✅ All views initialized");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing views: " + e.getMessage(), e);
        }
    }

    /**
     * دالة مساعدة للعثور على TextView بأمان مع تسجيل الأخطاء
     */
    private TextView safeFindTextView(View v, int id, String name) {
        TextView tv = v.findViewById(id);
        if (tv == null) {
            Log.w(TAG, "⚠️ TextView not found: " + name + " (ID: " + id + ")");
        }
        return tv;
    }

    // ==================== مستمعي أزرار الاختصارات السريعة ====================
    private void setupNavigationListeners() {
        try {
            if (cardScan != null) {
                cardScan.setOnClickListener(v -> safeShowToast("🔍 جاري فتح شاشة الجرد والمسح..."));
            }
            if (cardGate != null) {
                cardGate.setOnClickListener(v -> safeShowToast("🚪 جاري فتح شاشة رقابة البوابات..."));
            }
            if (cardRadar != null) {
                cardRadar.setOnClickListener(v -> safeShowToast("📡 جاري فتح شاشة الرادار والبحث..."));
            }
            if (cardSettings != null) {
                cardSettings.setOnClickListener(v -> safeShowToast("⚙️ جاري فتح شاشة الإعدادات..."));
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error setting up listeners: " + e.getMessage(), e);
        }
    }

    private void safeShowToast(String message) {
        try {
            if (getContext() != null) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing toast: " + e.getMessage(), e);
        }
    }

    // ==================== الساعة والتاريخ المباشر ====================
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
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error updating clock: " + e.getMessage(), e);
                }
            }
        });
    }

    // ==================== جلب بيانات لوحة التحكم من API ====================
    private void fetchDashboardData() {
        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardSummary();

                if (response.isSuccess && response.data != null) {
                    JSONObject summary = response.data.optJSONObject("summary");
                    
                    if (summary != null) {
                        int totalItems = summary.optInt("total_items", 0);
                        double totalWeight = summary.optDouble("total_weight_grams", 0);
                        int soldToday = summary.optInt("sold_today", 0);
                        
                        double salesToday = summary.optDouble("sales_today_amount", 0);
                        double salesMonth = summary.optDouble("sales_month_amount", 0);
                        double salesYear = summary.optDouble("sales_year_amount", 0);
                        
                        int gatesActive = summary.optInt("gates_active", 0);
                        int gatesOffline = summary.optInt("gates_offline", 0);
                        int gatesAlarms = summary.optInt("gates_alarms_today", 0);
                        
                        String lastAuditDate = summary.optString("last_audit_date", "");
                        double auditAccuracy = summary.optDouble("audit_accuracy_percent", 0);
                        int auditMissing = summary.optInt("audit_missing_items", 0);
                        
                        JSONArray recentLogs = summary.optJSONArray("recent_logs");
                        JSONArray weightDistribution = summary.optJSONArray("weight_by_karat");

                        mainHandler.post(() -> {
                            try {
                                updateUI(totalItems, totalWeight, soldToday,
                                        salesToday, salesMonth, salesYear,
                                        gatesActive, gatesOffline, gatesAlarms,
                                        lastAuditDate, auditAccuracy, auditMissing,
                                        recentLogs, weightDistribution);
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Error updating UI: " + e.getMessage(), e);
                            }
                        });
                    }
                } else {
                    mainHandler.post(() -> handleApiError(response.code, response.error));
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ Error fetching data: " + e.getMessage(), e);
                mainHandler.post(() -> handleApiError(-1, e.getMessage()));
            }
        });
    }

    private void updateUI(int totalItems, double totalWeight, int soldToday,
                          double salesToday, double salesMonth, double salesYear,
                          int gatesActive, int gatesOffline, int gatesAlarms,
                          String lastAuditDate, double auditAccuracy, int auditMissing,
                          JSONArray recentLogs, JSONArray weightDistribution) {
        try {
            // تحديث الإحصائيات
            safeSetText(tvTotalItems, formatNumber(totalItems));
            safeSetText(tvTotalWeight, String.format(Locale.US, "%.1f g", totalWeight));
            safeSetText(tvSoldToday, String.valueOf(soldToday));

            // تحديث المبيعات
            safeSetText(tvSalesToday, formatCurrency(salesToday));
            safeSetText(tvSalesMonth, formatCurrency(salesMonth));
            safeSetText(tvSalesYear, formatCurrency(salesYear));

            // تحديث البوابات
            safeSetText(tvGatesActive, String.valueOf(gatesActive));
            safeSetText(tvGatesOffline, String.valueOf(gatesOffline));
            safeSetText(tvGatesAlarms, String.valueOf(gatesAlarms));

            // تحديث مؤشرات النظام
            updateSystemIndicators(gatesActive > 0);

            // تحديث سجل الجرد
            safeSetText(tvLastAuditDate, lastAuditDate.isEmpty() ? "—" : lastAuditDate);
            safeSetText(tvAuditAccuracy, String.format(Locale.US, "الدقة: %.1f%%", auditAccuracy));
            safeSetText(tvAuditMissing, String.format("المفقود: %d قطعة", auditMissing));

            // تحديث الأنشطة الأخيرة
            updateRecentLogs(recentLogs);

            // تحديث الرسم البياني
            if (weightDistribution != null && weightDistribution.length() > 0) {
                updateBarChart(weightDistribution);
            }

            // تحديث حالة الاتصال
            String syncTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            safeSetText(tvLastSync, "آخر مزامنة: " + syncTime);
            safeSetText(tvLiveStatus, "🟢 ONLINE");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in updateUI: " + e.getMessage(), e);
        }
    }

    private void safeSetText(TextView tv, String text) {
        if (tv != null) {
            tv.setText(text);
        }
    }

    // ==================== تحديث مؤشرات النظام ====================
    private void updateSystemIndicators(boolean gatesActive) {
        try {
            if (indicatorApi != null) 
                indicatorApi.setBackgroundColor(0xFF16A34A);
            if (indicatorDb != null) 
                indicatorDb.setBackgroundColor(0xFF16A34A);
            if (indicatorGate != null) {
                indicatorGate.setBackgroundColor(gatesActive ? 0xFF16A34A : 0xFFF59E0B);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating indicators: " + e.getMessage(), e);
        }
    }

    // ==================== تحديث السجل المباشر للعمليات ====================
    private void updateRecentLogs(JSONArray recentLogs) {
        try {
            if (recentLogs == null || recentLogs.length() == 0) {
                safeSetText(tvRecent1, "• لا توجد أنشطة حديثة");
                safeSetText(tvRecent2, "");
                safeSetText(tvRecent3, "");
                return;
            }

            TextView[] recentViews = {tvRecent1, tvRecent2, tvRecent3};
            for (int i = 0; i < Math.min(recentLogs.length(), 3); i++) {
                JSONObject log = recentLogs.optJSONObject(i);
                if (log != null && recentViews[i] != null) {
                    String epc = log.optString("epc", "—");
                    String action = log.optString("action_taken", "—");
                    String time = log.optString("created_at", "");
                    
                    String displayTime = "";
                    if (!time.isEmpty()) {
                        try {
                            displayTime = time.substring(11, 16);
                        } catch (Exception e) {
                            displayTime = time;
                        }
                    }

                    String icon = getActionIcon(action);
                    String actionAr = getActionArabicName(action);
                    recentViews[i].setText(String.format("• %s %s | %s | %s", 
                        icon, displayTime, actionAr, epc));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating recent logs: " + e.getMessage(), e);
        }
    }

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

    private String getActionArabicName(String action) {
        if (action == null) return "نشاط";
        switch (action.toUpperCase()) {
            case "AUTHORIZED": return "مصرح";
            case "DENIED": return "مرفوض";
            case "INVENTORY_SCAN": return "جرد";
            case "POS_SOLD": return "مباع";
            case "SPOTLIGHT": return "تسليط";
            case "PROGRAMMED": return "مبرمج";
            case "LOCKED": return "مقفل";
            case "DEACTIVATED": return "معطل";
            default: return action;
        }
    }

    // ==================== تحديث الرسم البياني ====================
    private void updateBarChart(JSONArray weightDistribution) {
        try {
            if (barChartWeights == null) return;

            List<BarEntry> entries = new ArrayList<>();
            List<String> labels = new ArrayList<>();

            for (int i = 0; i < weightDistribution.length(); i++) {
                JSONObject item = weightDistribution.optJSONObject(i);
                if (item != null) {
                    String karat = item.optString("karat", "عيار " + (i + 1));
                    float weight = (float) item.optDouble("weight_grams", 0);
                    
                    entries.add(new BarEntry(i, weight));
                    labels.add(karat);
                }
            }

            if (entries.isEmpty()) return;

            BarDataSet dataSet = new BarDataSet(entries, "الوزن بالجرام");
            dataSet.setColors(ColorTemplate.MATERIAL_COLORS);
            dataSet.setValueTextColor(Color.WHITE);
            dataSet.setValueTextSize(10f);

            BarData barData = new BarData(dataSet);
            barChartWeights.setData(barData);

            XAxis xAxis = barChartWeights.getXAxis();
            xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setGranularity(1f);
            xAxis.setLabelCount(labels.size());
            xAxis.setTextColor(Color.parseColor("#94A3B8"));
            xAxis.setTextSize(10f);

            barChartWeights.getAxisLeft().setTextColor(Color.parseColor("#94A3B8"));
            barChartWeights.getAxisRight().setEnabled(false);
            barChartWeights.getLegend().setTextColor(Color.parseColor("#F8FAFC"));
            barChartWeights.setBackgroundColor(Color.parseColor("#1E293B"));
            barChartWeights.getDescription().setEnabled(false);
            barChartWeights.animateY(800);
            barChartWeights.invalidate();

        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating bar chart: " + e.getMessage(), e);
        }
    }

    // ==================== معالجة أخطاء API ====================
    private void handleApiError(int code, String error) {
        try {
            if (indicatorApi != null) indicatorApi.setBackgroundColor(0xFFDC2626);
            if (indicatorDb != null) indicatorDb.setBackgroundColor(0xFFF59E0B);
            if (indicatorGate != null) indicatorGate.setBackgroundColor(0xFF94A3B8);
            
            if (tvLastSync != null) {
                String errorMsg = error != null && !error.isEmpty() ? error : "Code: " + code;
                if (errorMsg.length() > 30) errorMsg = errorMsg.substring(0, 30) + "...";
                tvLastSync.setText("🔴 غير متصل: " + errorMsg);
            }
            if (tvLiveStatus != null) {
                tvLiveStatus.setText("🔴 OFFLINE");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error handling API error: " + e.getMessage(), e);
        }
    }

    // ==================== دوال مساعدة للتنسيق ====================
    private String formatNumber(int number) {
        if (number >= 1000) {
            return String.format(Locale.US, "%d,%03d", number / 1000, number % 1000);
        }
        return String.valueOf(number);
    }

    private String formatCurrency(double amount) {
        if (amount >= 1000) {
            return String.format(Locale.US, "%,.0f $", amount);
        }
        return String.format(Locale.US, "%.0f $", amount);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timeHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdown();
    }
}
