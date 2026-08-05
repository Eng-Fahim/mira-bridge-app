package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;
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
 * - كميات العيارات (24K, 21K, 18K) معروضة في خانات أسعار الذهب
 * - المبيعات (يوم/شهر/سنة) بالريال اليمني
 * - الوصول السريع للبوابات والجرد
 * - دعم كامل للغة العربية والإنجليزية
 */
public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    // ==================== UI Elements (مطابقة لـ XML) ====================
    private TextView tvGreeting, tvDate, tvLocation, tvLiveStatus;
    private TextClock tcLiveTime;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvGoldPrice18, tvGoldPrice21, tvGoldPrice24;
    private View btnGateSecurity, btnRfidAudit;
    private SwipeRefreshLayout swipeRefresh;

    // ==================== Managers ====================
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler dateHandler = new Handler(Looper.getMainLooper());
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;

    private boolean isArabic;

    // ==================== Lifecycle ====================

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
        updateDateDisplay();
        fetchDashboardData();
    }

    // ==================== Init Views ====================

    private void initViews(View v) {
        // الهيدر - مطابق للـ XML
        tvGreeting = v.findViewById(R.id.tvGreeting);
        tvDate = v.findViewById(R.id.tvDate);
        tcLiveTime = v.findViewById(R.id.tcLiveTime);
        tvLocation = v.findViewById(R.id.tvLocation);
        tvLiveStatus = v.findViewById(R.id.tvLiveStatus);

        // إحصائيات المخزون - مطابق للـ XML
        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
        tvSoldToday = v.findViewById(R.id.tvSoldToday);

        // المبيعات - مطابق للـ XML
        tvSalesToday = v.findViewById(R.id.tvSalesToday);
        tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
        tvSalesYear = v.findViewById(R.id.tvSalesYear);

        // أسعار/كميات العيارات - نستخدم عناصر gold price الموجودة
        tvGoldPrice18 = v.findViewById(R.id.tvGoldPrice18);
        tvGoldPrice21 = v.findViewById(R.id.tvGoldPrice21);
        tvGoldPrice24 = v.findViewById(R.id.tvGoldPrice24);

        // أزرار العمليات - مطابق للـ XML
        btnGateSecurity = v.findViewById(R.id.btnGateSecurity);
        btnRfidAudit = v.findViewById(R.id.btnRfidAudit);

        // السحب للتحديث
        swipeRefresh = v.findViewById(R.id.swipeRefresh);

        // تحديث اسم الموقع
        if (tvLocation != null) {
            tvLocation.setText("📍 " + branchesManager.getActiveBranchDisplayName());
        }
    }

    // ==================== Date Update ====================

    private void updateDateDisplay() {
        dateHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) return;
                Locale locale = isArabic ? new Locale("ar") : Locale.US;
                SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE، d MMMM yyyy", locale);
                if (tvDate != null) tvDate.setText(dateFmt.format(new Date()));
                dateHandler.postDelayed(this, 60000); // تحديث كل دقيقة
            }
        });
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

        // btnGateSecurity ← البوابات
        if (btnGateSecurity != null) {
            btnGateSecurity.setOnClickListener(v -> switchTab("SecureGate"));
        }

        // btnRfidAudit ← الجرد السريع
        if (btnRfidAudit != null) {
            btnRfidAudit.setOnClickListener(v -> switchTab("Inventory Pro"));
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

                // ✅ كميات العيارات في خانات أسعار الذهب
                JSONObject weights = inventory.optJSONObject("weights");
                if (weights != null) {
                    setKaratText(tvGoldPrice18, "18K", weights.optInt("k18", 0));
                    setKaratText(tvGoldPrice21, "21K", weights.optInt("k21", 0));
                    setKaratText(tvGoldPrice24, "24K", weights.optInt("k24", 0));
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

    /**
     * ✅ عرض كمية القطع لكل عيار في خانة سعر الذهب
     */
    private void setKaratText(TextView tv, String karatLabel, int count) {
        if (tv == null) return;
        if (count > 0) {
            tv.setText(karatLabel + " | " + count + " " + (isArabic ? "قطعة" : "pcs"));
            tv.setTextColor(0xFF1A237E);
        } else {
            tv.setText(karatLabel + " | —");
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
        dateHandler.removeCallbacksAndMessages(null);
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }
}
