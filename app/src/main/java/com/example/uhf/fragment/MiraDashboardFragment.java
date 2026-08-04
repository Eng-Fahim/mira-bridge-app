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
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboard";

    // View References
    private TextView tvGreeting, tvDate, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvGoldPrice18, tvGoldPrice21, tvGoldPrice24;
    private SwipeRefreshLayout swipeRefresh;
    private View btnGateSecurity, btnRfidAudit;

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        executor = Executors.newSingleThreadExecutor();
        apiClient = MiraApiClient.getInstance(requireContext());

        initViews(view);
        setupUserGreeting();
        setupNavigationListeners();
        setupSwipeToRefresh();
        
        // جلب البيانات الأولية
        fetchDashboardData();
    }

    private void initViews(View v) {
        tvGreeting = v.findViewById(R.id.tvGreeting);
        tvDate = v.findViewById(R.id.tvDate);
        tvLocation = v.findViewById(R.id.tvLocation);
        tvLiveStatus = v.findViewById(R.id.tvLiveStatus);
        
        tvTotalItems = v.findViewById(R.id.tvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
        tvSoldToday = v.findViewById(R.id.tvSoldToday);
        
        tvSalesToday = v.findViewById(R.id.tvSalesToday);
        tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
        tvSalesYear = v.findViewById(R.id.tvSalesYear);

        tvGoldPrice18 = v.findViewById(R.id.tvGoldPrice18);
        tvGoldPrice21 = v.findViewById(R.id.tvGoldPrice21);
        tvGoldPrice24 = v.findViewById(R.id.tvGoldPrice24);
        
        swipeRefresh = v.findViewById(R.id.swipeRefresh);
        btnGateSecurity = v.findViewById(R.id.btnGateSecurity);
        btnRfidAudit = v.findViewById(R.id.btnRfidAudit);
        
        // تعيين التاريخ العربي الهجري/الملادي الحركي
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE، d MMMM yyyy", new Locale("ar"));
        if (tvDate != null) {
            tvDate.setText(dateFormat.format(new Date()));
        }
    }

    /**
     * حل مشكلة اسم المستخدم الثابت: جلب اسم المستخدم من الجلسة وتضمينه
     */
    private void setupUserGreeting() {
        if (tvGreeting != null) {
            // قم باستبدال getUserDisplayName() بالطريقة التي تستخرج بها الاسم من Session/SharedPref
            String userName = getUserDisplayName(); 
            tvGreeting.setText("مرحباً بك، " + userName);
        }
    }

    private String getUserDisplayName() {
        // تفقد بيانات الجلسة المسجلة
        return "المستخدم"; 
    }

    private void setupSwipeToRefresh() {
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(this::fetchDashboardData);
        }
    }

    private void setupNavigationListeners() {
        if (getActivity() == null) return;

        if (btnRfidAudit != null) {
            btnRfidAudit.setOnClickListener(v -> switchTab(getString(R.string.uhf_msg_tab_scan)));
        }
        if (btnGateSecurity != null) {
            btnGateSecurity.setOnClickListener(v -> switchTab("MIRA Secure Gate"));
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

    private void fetchDashboardData() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        
        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardSummary();
                if (response.isSuccess && response.data != null) {
                    JSONObject summary = response.data.optJSONObject("summary");
                    JSONObject prices = response.data.optJSONObject("gold_prices");
                    
                    mainHandler.post(() -> {
                        if (summary != null) processSummaryData(summary);
                        if (prices != null) processGoldPrices(prices);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
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
        double totalWeight = summary.optDouble("total_weight_grams", 0.0);
        int soldToday = summary.optInt("sold_today", 0);

        double salesToday = summary.optDouble("sales_today", 0.0);
        double salesMonth = summary.optDouble("sales_month", 0.0);
        double salesYear = summary.optDouble("sales_year", 0.0);

        // تحديث إحصائيات القطع والوزن
        if (tvTotalItems != null) tvTotalItems.setText(String.valueOf(totalItems));
        if (tvTotalWeight != null) tvTotalWeight.setText(String.format(Locale.US, "%.1f g", totalWeight));
        if (tvSoldToday != null) tvSoldToday.setText(String.valueOf(soldToday));

        // تحديث قيم المبيعات
        if (tvSalesToday != null) tvSalesToday.setText(String.format(Locale.US, "$%.2f", salesToday));
        if (tvSalesMonth != null) tvSalesMonth.setText(String.format(Locale.US, "$%.2f", salesMonth));
        if (tvSalesYear != null) tvSalesYear.setText(String.format(Locale.US, "$%.2f", salesYear));

        if (tvLiveStatus != null) {
            tvLiveStatus.setText("● ONLINE");
            tvLiveStatus.setTextColor(0xFF16A34A); // أخضر
        }
    }

    private void processGoldPrices(JSONObject prices) {
        double price18 = prices.optDouble("price_18k", 0.0);
        double price21 = prices.optDouble("price_21k", 0.0);
        double price24 = prices.optDouble("price_24k", 0.0);

        if (tvGoldPrice18 != null) tvGoldPrice18.setText(String.format(Locale.US, "$%.2f/g", price18));
        if (tvGoldPrice21 != null) tvGoldPrice21.setText(String.format(Locale.US, "$%.2f/g", price21));
        if (tvGoldPrice24 != null) tvGoldPrice24.setText(String.format(Locale.US, "$%.2f/g", price24));
    }

    private void handleApiError(int code, String error) {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        if (tvLiveStatus != null) {
            tvLiveStatus.setText("● OFFLINE");
            tvLiveStatus.setTextColor(0xFFDC2626); // أحمر
        }
        Log.e(TAG, "API Error [" + code + "]: " + error);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
