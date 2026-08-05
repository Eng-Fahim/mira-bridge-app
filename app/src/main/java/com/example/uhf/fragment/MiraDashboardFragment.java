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
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    // ==================== UI Elements ====================
    private TextView tvGreeting, tvDate, tvTime, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24Qty, tvKarat21Qty, tvKarat18Qty;
    private TextView tvKarat24Label, tvKarat21Label, tvKarat18Label;
    private TextView tvSalesTodayLabel, tvSalesMonthLabel, tvSalesYearLabel;
    private TextView tvTotalItemsLabel, tvTotalWeightLabel, tvSoldTodayLabel;
    private View btnPosSale, btnQuickInventory, btnGateSecurity, btnRfidAudit;
    private SwipeRefreshLayout swipeRefresh;

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;

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
        orgProfile = MiraOrgProfileManager.getInstance(requireContext());
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        initViews(view);
        applyLanguage();
        setupUserGreeting();
        setupNavigationListeners();
        setupSwipeToRefresh();
        fetchDashboardData();
    }

    // ==================== Init Views ====================

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

        tvKarat24Qty = v.findViewById(R.id.tvKarat24Qty);
        tvKarat21Qty = v.findViewById(R.id.tvKarat21Qty);
        tvKarat18Qty = v.findViewById(R.id.tvKarat18Qty);

        // Labels for language switching
        tvKarat24Label = v.findViewById(R.id.tvKarat24Label);
        tvKarat21Label = v.findViewById(R.id.tvKarat21Label);
        tvKarat18Label = v.findViewById(R.id.tvKarat18Label);
        tvSalesTodayLabel = v.findViewById(R.id.tvSalesTodayLabel);
        tvSalesMonthLabel = v.findViewById(R.id.tvSalesMonthLabel);
        tvSalesYearLabel = v.findViewById(R.id.tvSalesYearLabel);
        tvTotalItemsLabel = v.findViewById(R.id.tvTotalItemsLabel);
        tvTotalWeightLabel = v.findViewById(R.id.tvTotalWeightLabel);
        tvSoldTodayLabel = v.findViewById(R.id.tvSoldTodayLabel);

        swipeRefresh = v.findViewById(R.id.swipeRefresh);
        btnPosSale = v.findViewById(R.id.btnPosSale);
        btnQuickInventory = v.findViewById(R.id.btnQuickInventory);
        btnGateSecurity = v.findViewById(R.id.btnGateSecurity);
        btnRfidAudit = v.findViewById(R.id.btnRfidAudit);
    }

    // ==================== Language ====================

    private void applyLanguage() {
        if (tvLocation != null) {
            tvLocation.setText(isArabic ? "📍 " + branchesManager.getActiveBranchDisplayName()
                : "📍 " + branchesManager.getActiveBranchDisplayName());
        }

        // Labels
        if (tvKarat24Label != null) tvKarat24Label.setText(isArabic ? "عيار 24" : "24K");
        if (tvKarat21Label != null) tvKarat21Label.setText(isArabic ? "عيار 21" : "21K");
        if (tvKarat18Label != null) tvKarat18Label.setText(isArabic ? "عيار 18" : "18K");

        if (tvSalesTodayLabel != null) tvSalesTodayLabel.setText(isArabic ? "مبيعات اليوم" : "Today Sales");
        if (tvSalesMonthLabel != null) tvSalesMonthLabel.setText(isArabic ? "مبيعات الشهر" : "Month Sales");
        if (tvSalesYearLabel != null) tvSalesYearLabel.setText(isArabic ? "مبيعات السنة" : "Year Sales");

        if (tvTotalItemsLabel != null) tvTotalItemsLabel.setText(isArabic ? "إجمالي القطع" : "Total Items");
        if (tvTotalWeightLabel != null) tvTotalWeightLabel.setText(isArabic ? "الوزن الإجمالي" : "Total Weight");
        if (tvSoldTodayLabel != null) tvSoldTodayLabel.setText(isArabic ? "مباع اليوم" : "Sold Today");

        // Date & Time
        updateDateTime();
    }

    private void updateDateTime() {
        Locale locale = isArabic ? new Locale("ar") : Locale.US;
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE، d MMMM yyyy", locale);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", locale);
        Date now = new Date();
        if (tvDate != null) tvDate.setText(dateFormat.format(now));
        if (tvTime != null) tvTime.setText(timeFormat.format(now));
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

        // 🛒 نقطة البيع
        if (btnPosSale != null) {
            btnPosSale.setOnClickListener(v -> switchTab("POS Mode"));
        }

        // 📊 الجرد السريع
        if (btnQuickInventory != null) {
            btnQuickInventory.setOnClickListener(v -> switchTab("Inventory Pro"));
        }

        // 🚪 البوابات
        if (btnGateSecurity != null) {
            btnGateSecurity.setOnClickListener(v -> switchTab("SecureGate"));
        }

        // 📷 المسح
        if (btnRfidAudit != null) {
            btnRfidAudit.setOnClickListener(v -> switchTab("Scan"));
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

    // ==================== Swipe ====================

    private void setupSwipeToRefresh() {
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(this::fetchDashboardData);
        }
    }

    // ==================== API ====================

    private void fetchDashboardData() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }

        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardFull();
                mainHandler.post(() -> {
                    if (response.isSuccess && response.data != null) {
                        processFullDashboardData(response.data);
                    } else {
                        handleApiError(response.code, response.error);
                    }
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    handleApiError(-1, e.getMessage());
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    private void processFullDashboardData(JSONObject data) {
        try {
            // 🟢 المخزون
            JSONObject inventory = data.optJSONObject("inventory");
            if (inventory != null) {
                int totalItems = inventory.optInt("total_items", 0);
                double totalWeight = inventory.optDouble("total_weight", 0);

                if (tvTotalItems != null) tvTotalItems.setText(formatNumber(totalItems));
                if (tvTotalWeight != null) tvTotalWeight.setText(String.format(Locale.US, "%.1f g", totalWeight));

                // ✅ كميات العيارات بدلاً من الأسعار
                JSONObject weights = inventory.optJSONObject("weights");
                if (weights != null) {
                    int k24 = weights.optInt("k24", 0);
                    int k21 = weights.optInt("k21", 0);
                    int k18 = weights.optInt("k18", 0);

                    if (tvKarat24Qty != null) tvKarat24Qty.setText(k24 + " " + (isArabic ? "قطعة" : "pcs"));
                    if (tvKarat21Qty != null) tvKarat21Qty.setText(k21 + " " + (isArabic ? "قطعة" : "pcs"));
                    if (tvKarat18Qty != null) tvKarat18Qty.setText(k18 + " " + (isArabic ? "قطعة" : "pcs"));
                }
            }

            // 🟢 المبيعات - بالريال اليمني
            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                double today = sales.optDouble("today_amount", 0);
                double month = sales.optDouble("month_amount", 0);
                double year = sales.optDouble("year_amount", 0);
                int soldToday = sales.optInt("count_today", 0);

                String currency = isArabic ? "ريال" : "YER";
                if (tvSalesToday != null) tvSalesToday.setText(formatCurrency(today, currency));
                if (tvSalesMonth != null) tvSalesMonth.setText(formatCurrency(month, currency));
                if (tvSalesYear != null) tvSalesYear.setText(formatCurrency(year, currency));
                if (tvSoldToday != null) tvSoldToday.setText(String.valueOf(soldToday));
            }

            // 🟢 حالة الاتصال
            if (tvLiveStatus != null) {
                tvLiveStatus.setText("● ONLINE");
                tvLiveStatus.setTextColor(0xFF16A34A);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing dashboard data: " + e.getMessage());
        }
    }

    private void handleApiError(int code, String error) {
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

    private String formatCurrency(double amount, String currency) {
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
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }
}
