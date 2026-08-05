package com.example.uhf.fragment;

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
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MIRA Dashboard Fragment
 * 
 * يعرض إحصائيات الفرع النشط:
 * - إجمالي القطع والوزن والمباع اليوم
 * - كميات العيارات (24K, 21K, 18K)
 * - المبيعات (يوم/شهر/سنة) بالريال اليمني
 * - الوصول السريع لنقطة البيع والجرد والبوابات
 * - دعم كامل للغة العربية والإنجليزية
 */
public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    // ==================== UI Elements ====================
    private TextView tvGreeting, tvDate, tvTime, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24Qty, tvKarat21Qty, tvKarat18Qty;
    private View cardScan, cardGate, cardRadar, cardSettings;
    private SwipeRefreshLayout swipeRefresh;

    // ==================== Managers ====================
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;

    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        executor = Executors.newSingleThreadExecutor();
        apiClient = MiraApiClient.getInstance(requireContext());
        sessionManager = new SessionManager(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        initViews(view);
        setupUserGreeting();
        setupNavigationListeners();
        setupSwipeToRefresh();
        startClock();
        fetchDashboardData();
    }

    // ==================== Init Views ====================

    private void initViews(View v) {
        // الهيدر
        tvGreeting = v.findViewById(R.id.tvGreeting);
        tvDate = v.findViewById(R.id.tvDate);
        tvTime = v.findViewById(R.id.tvTime);
        tvLocation = v.findViewById(R.id.tvLocation);
        tvLiveStatus = v.findViewById(R.id.tvLiveStatus);

        // إحصائيات المخزون
        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
        tvSoldToday = v.findViewById(R.id.tvSoldToday);

        // المبيعات
        tvSalesToday = v.findViewById(R.id.tvSalesToday);
        tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
        tvSalesYear = v.findViewById(R.id.tvSalesYear);

        // كميات العيارات - نستخدم نفس عناصر gold price إن وجدت
        tvKarat24Qty = v.findViewById(R.id.tvKarat24Qty);
        tvKarat21Qty = v.findViewById(R.id.tvKarat21Qty);
        tvKarat18Qty = v.findViewById(R.id.tvKarat18Qty);

        // إذا لم تكن موجودة، نستخدم عناصر gold price الموجودة
        if (tvKarat24Qty == null) tvKarat24Qty = v.findViewById(R.id.tvGoldPrice24);
        if (tvKarat21Qty == null) tvKarat21Qty = v.findViewById(R.id.tvGoldPrice21);
        if (tvKarat18Qty == null) tvKarat18Qty = v.findViewById(R.id.tvGoldPrice18);

        // أزرار الاختصارات
        cardScan = v.findViewById(R.id.cardScan);
        cardGate = v.findViewById(R.id.cardGate);
        cardRadar = v.findViewById(R.id.cardRadar);
        cardSettings = v.findViewById(R.id.cardSettings);

        // السحب للتحديث
        swipeRefresh = v.findViewById(R.id.swipeRefresh);

        // تحديث التاريخ والموقع
        updateLocationLabel();
    }

    // ==================== Clock ====================

    private void startClock() {
        clockHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) return;
                Locale locale = isArabic ? new Locale("ar") : Locale.US;
                SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", locale);
                SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE، d MMMM yyyy", locale);
                Date now = new Date();
                if (tvTime != null) tvTime.setText(timeFmt.format(now));
                if (tvDate != null) tvDate.setText(dateFmt.format(now));
                clockHandler.postDelayed(this, 1000);
            }
        });
    }

    private void updateLocationLabel() {
        if (tvLocation != null) {
            String branchName = branchesManager.getActiveBranchDisplayName();
            tvLocation.setText("📍 " + branchName);
        }
    }

    // ==================== Greeting ====================

    private void setupUserGreeting() {
        if (tvGreeting == null) return;
        String userName = getUserDisplayName();
        tvGreeting.setText(isArabic ? "مرحباً، " + userName : "Welcome, " + userName);
    }

    private String getUserDisplayName() {
        if (sessionManager != null) {
            String name = sessionManager.getDisplayName();
            if (name == null || name.isEmpty()) name = sessionManager.getUsername();
            if (name != null && !name.isEmpty()) return name;
        }
        return isArabic ? "المستخدم" : "User";
    }

    // ==================== Navigation ====================

    private void setupNavigationListeners() {
        if (getActivity() == null) return;

        // cardScan ← الجرد والمسح (Inventory Pro)
        if (cardScan != null) {
            cardScan.setOnClickListener(v -> switchTab("Inventory Pro"));
        }

        // cardGate ← البوابات (SecureGate)
        if (cardGate != null) {
            cardGate.setOnClickListener(v -> switchTab("SecureGate"));
        }

        // cardRadar ← الرادار (RadarLocation)
        if (cardRadar != null) {
            cardRadar.setOnClickListener(v -> switchTab("RadarLocation"));
        }

        // cardSettings ← الإعدادات (Settings) - فقط للمدراء
        if (cardSettings != null) {
            cardSettings.setOnClickListener(v -> switchTab("Settings"));
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
            Toast.makeText(getContext(), isArabic ? "جاري الفتح..." : "Opening...", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Swipe to Refresh ====================

    private void setupSwipeToRefresh() {
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(this::fetchDashboardData);
        }
    }

    // ==================== API Call ====================

    private void fetchDashboardData() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        if (tvLiveStatus != null) {
            tvLiveStatus.setText("● " + (isArabic ? "جاري التحديث..." : "UPDATING..."));
            tvLiveStatus.setTextColor(0xFFF59E0B);
        }

        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardFull();
                mainHandler.post(() -> {
                    if (response.isSuccess && response.data != null) {
                        processFullDashboardData(response.data);
                    } else {
                        handleApiError();
                    }
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    handleApiError();
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    private void processFullDashboardData(JSONObject data) {
        try {
            // ========== المخزون ==========
            JSONObject inventory = data.optJSONObject("inventory");
            if (inventory != null) {
                int totalItems = inventory.optInt("total_items", 0);
                double totalWeight = inventory.optDouble("total_weight", 0);

                if (tvTotalItems != null) tvTotalItems.setText(formatNumber(totalItems));
                if (tvTotalWeight != null) tvTotalWeight.setText(String.format(Locale.US, "%.1f g", totalWeight));

                // كميات العيارات
                JSONObject weights = inventory.optJSONObject("weights");
                if (weights != null) {
                    setKaratText(tvKarat24Qty, weights.optInt("k24", 0));
                    setKaratText(tvKarat21Qty, weights.optInt("k21", 0));
                    setKaratText(tvKarat18Qty, weights.optInt("k18", 0));
                }
            }

            // ========== المبيعات ==========
            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                int soldToday = sales.optInt("count_today", 0);
                double todayAmount = sales.optDouble("today_amount", 0);
                double monthAmount = sales.optDouble("month_amount", 0);
                double yearAmount = sales.optDouble("year_amount", 0);

                if (tvSoldToday != null) tvSoldToday.setText(String.valueOf(soldToday));
                if (tvSalesToday != null) tvSalesToday.setText(formatCurrency(todayAmount));
                if (tvSalesMonth != null) tvSalesMonth.setText(formatCurrency(monthAmount));
                if (tvSalesYear != null) tvSalesYear.setText(formatCurrency(yearAmount));
            }

            // ========== حالة الاتصال ==========
            if (tvLiveStatus != null) {
                tvLiveStatus.setText("● ONLINE");
                tvLiveStatus.setTextColor(0xFF16A34A);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing dashboard: " + e.getMessage());
        }
    }

    private void setKaratText(TextView tv, int count) {
        if (tv == null) return;
        if (count > 0) {
            tv.setText(count + " " + (isArabic ? "قطعة" : "pcs"));
            tv.setTextColor(0xFF1A237E);
        } else {
            tv.setText("—");
            tv.setTextColor(0xFF9CA3AF);
        }
    }

    private void handleApiError() {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        if (tvLiveStatus != null) {
            tvLiveStatus.setText("● OFFLINE");
            tvLiveStatus.setTextColor(0xFFDC2626);
        }
    }

    // ==================== Formatters ====================

    private String formatNumber(int number) {
        if (number >= 1000) {
            return String.format(Locale.US, "%d,%03d", number / 1000, number % 1000);
        }
        return String.valueOf(number);
    }

    private String formatCurrency(double amount) {
        String currency = isArabic ? "ريال" : "YER";
        if (amount >= 1000) {
            return String.format(Locale.US, "%,.0f %s", amount, currency);
        }
        return String.format(Locale.US, "%.0f %s", amount, currency);
    }

    // ==================== Lifecycle ====================

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
        clockHandler.removeCallbacksAndMessages(null);
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }
}
