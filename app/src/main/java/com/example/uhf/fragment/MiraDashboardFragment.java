package com.example.uhf.fragment;

import android.graphics.Color;
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

    // ==================== عناصر الواجهة ====================
    
    // الهيدر والتحية
    private TextView tvGreeting, tvDate, tvTime, tvLocation, tvLiveStatus;
    
    // إحصائيات المخزون (الصف العلوي - 3 بطاقات)
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    
    // المبيعات التفصيلية
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    
    // بوابات RFID
    private TextView tvGatesActive, tvGatesOffline, tvGatesAlarms;
    
    // مؤشرات حالة النظام
    private TextView tvLastSync;
    private View indicatorApi, indicatorDb, indicatorGate;
    
    // سجل الجرد والتتبع
    private TextView tvLastAuditDate, tvAuditAccuracy, tvAuditMissing;
    
    // السجل المباشر للعمليات
    private TextView tvRecent1, tvRecent2, tvRecent3;
    
    // اختصارات الوصول السريع
    private View cardScan, cardGate, cardRadar, cardSettings;
    
    // الرسم البياني لتوزيع الأوزان
    private BarChart barChartWeights;

    // إدارة الوقت والخيوط
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timeHandler = new Handler(Looper.getMainLooper());

    // العميل المركزي للـ API
    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_dashboard, container, false);

        apiClient = MiraApiClient.getInstance(requireContext());

        initViews(v);
        setupNavigationListeners();
        startTimeClock();
        fetchDashboardData();

        return v;
    }

    // ==================== تهيئة جميع عناصر الواجهة ====================
    private void initViews(View v) {
        // الهيدر والتحية
        tvGreeting = v.findViewById(R.id.tvGreeting);
        tvDate = v.findViewById(R.id.tvDate);
        tvTime = v.findViewById(R.id.tvTime);
        tvLocation = v.findViewById(R.id.tvLocation);
        tvLiveStatus = v.findViewById(R.id.tvLiveStatus);

        // إحصائيات المخزون
        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
        tvSoldToday = v.findViewById(R.id.tvSoldToday);

        // المبيعات التفصيلية
        tvSalesToday = v.findViewById(R.id.tvSalesToday);
        tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
        tvSalesYear = v.findViewById(R.id.tvSalesYear);

        // بوابات RFID
        tvGatesActive = v.findViewById(R.id.tvGatesActive);
        tvGatesOffline = v.findViewById(R.id.tvGatesOffline);
        tvGatesAlarms = v.findViewById(R.id.tvGatesAlarms);

        // مؤشرات النظام
        tvLastSync = v.findViewById(R.id.tvLastSync);
        indicatorApi = v.findViewById(R.id.indicatorApi);
        indicatorDb = v.findViewById(R.id.indicatorDb);
        indicatorGate = v.findViewById(R.id.indicatorGate);

        // سجل الجرد
        tvLastAuditDate = v.findViewById(R.id.tvLastAuditDate);
        tvAuditAccuracy = v.findViewById(R.id.tvAuditAccuracy);
        tvAuditMissing = v.findViewById(R.id.tvAuditMissing);

        // السجل المباشر
        tvRecent1 = v.findViewById(R.id.tvRecent1);
        tvRecent2 = v.findViewById(R.id.tvRecent2);
        tvRecent3 = v.findViewById(R.id.tvRecent3);

        // اختصارات سريعة
        cardScan = v.findViewById(R.id.cardScan);
        cardGate = v.findViewById(R.id.cardGate);
        cardRadar = v.findViewById(R.id.cardRadar);
        cardSettings = v.findViewById(R.id.cardSettings);

        // الرسم البياني
        barChartWeights = v.findViewById(R.id.barChartWeights);
    }

    // ==================== مستمعي أزرار الاختصارات السريعة ====================
    private void setupNavigationListeners() {
        if (cardScan != null) {
            cardScan.setOnClickListener(v -> {
                showToast("🔍 جاري فتح شاشة الجرد والمسح...");
                // TODO: إضافة الانتقال الفعلي للصفحة
            });
        }

        if (cardGate != null) {
            cardGate.setOnClickListener(v -> {
                showToast("🚪 جاري فتح شاشة رقابة البوابات...");
                // TODO: إضافة الانتقال الفعلي للصفحة
            });
        }

        if (cardRadar != null) {
            cardRadar.setOnClickListener(v -> {
                showToast("📡 جاري فتح شاشة الرادار والبحث...");
                // TODO: إضافة الانتقال الفعلي للصفحة
            });
        }

        if (cardSettings != null) {
            cardSettings.setOnClickListener(v -> {
                showToast("⚙️ جاري فتح شاشة الإعدادات...");
                // TODO: إضافة الانتقال الفعلي للصفحة
            });
        }
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== الساعة والتاريخ المباشر ====================
    private void startTimeClock() {
        timeHandler.post(new Runnable() {
            @Override
            public void run() {
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE، d MMMM yyyy", new Locale("ar"));
                Date now = new Date();

                if (tvTime != null) tvTime.setText(timeFormat.format(now));
                if (tvDate != null) tvDate.setText(dateFormat.format(now));

                timeHandler.postDelayed(this, 1000);
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
                        // قراءة جميع البيانات
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
                            // تحديث الإحصائيات الرئيسية
                            if (tvTotalItems != null) 
                                tvTotalItems.setText(formatNumber(totalItems));
                            if (tvTotalWeight != null) 
                                tvTotalWeight.setText(String.format(Locale.US, "%.1f g", totalWeight));
                            if (tvSoldToday != null) 
                                tvSoldToday.setText(String.valueOf(soldToday));

                            // تحديث المبيعات
                            if (tvSalesToday != null) 
                                tvSalesToday.setText(formatCurrency(salesToday));
                            if (tvSalesMonth != null) 
                                tvSalesMonth.setText(formatCurrency(salesMonth));
                            if (tvSalesYear != null) 
                                tvSalesYear.setText(formatCurrency(salesYear));

                            // تحديث البوابات
                            if (tvGatesActive != null) 
                                tvGatesActive.setText(String.valueOf(gatesActive));
                            if (tvGatesOffline != null) 
                                tvGatesOffline.setText(String.valueOf(gatesOffline));
                            if (tvGatesAlarms != null) 
                                tvGatesAlarms.setText(String.valueOf(gatesAlarms));

                            // تحديث مؤشرات النظام
                            updateSystemIndicators(gatesActive > 0);

                            // تحديث سجل الجرد
                            if (tvLastAuditDate != null) 
                                tvLastAuditDate.setText(lastAuditDate.isEmpty() ? "—" : lastAuditDate);
                            if (tvAuditAccuracy != null) 
                                tvAuditAccuracy.setText(String.format(Locale.US, "الدقة: %.1f%%", auditAccuracy));
                            if (tvAuditMissing != null) 
                                tvAuditMissing.setText(String.format("المفقود: %d قطعة", auditMissing));

                            // تحديث الأنشطة الأخيرة
                            updateRecentLogs(recentLogs);

                            // تحديث الرسم البياني
                            if (weightDistribution != null && weightDistribution.length() > 0) {
                                updateBarChart(weightDistribution);
                            }

                            // تحديث حالة الاتصال
                            String syncTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(new Date());
                            if (tvLastSync != null) tvLastSync.setText("آخر مزامنة: " + syncTime);
                            if (tvLiveStatus != null) tvLiveStatus.setText("🟢 ONLINE");
                        });
                    }
                } else {
                    mainHandler.post(() -> handleApiError(response.code, response.error));
                }

            } catch (Exception e) {
                mainHandler.post(() -> handleApiError(-1, e.getMessage()));
            }
        });
    }

    // ==================== تحديث مؤشرات النظام ====================
    private void updateSystemIndicators(boolean gatesActive) {
        if (indicatorApi != null) 
            indicatorApi.setBackgroundColor(0xFF16A34A);
        if (indicatorDb != null) 
            indicatorDb.setBackgroundColor(0xFF16A34A);
        if (indicatorGate != null) {
            indicatorGate.setBackgroundColor(gatesActive ? 0xFF16A34A : 0xFFF59E0B);
        }
    }

    // ==================== تحديث السجل المباشر للعمليات ====================
    private void updateRecentLogs(JSONArray recentLogs) {
        if (recentLogs == null || recentLogs.length() == 0) {
            if (tvRecent1 != null) tvRecent1.setText("• لا توجد أنشطة حديثة");
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

    // ==================== تحديث الرسم البياني لتوزيع الأوزان ====================
    private void updateBarChart(JSONArray weightDistribution) {
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
    }

    // ==================== معالجة أخطاء API ====================
    private void handleApiError(int code, String error) {
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
