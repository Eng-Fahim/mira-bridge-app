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
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "Dashboard";

    private TextView tvGreeting, tvDate, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24, tvKarat21, tvKarat18;
    private View btnGate, btnInventory;
    private SwipeRefreshLayout swipeRefresh;

    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

        apiClient = MiraApiClient.getInstance(requireContext());
        sessionManager = new SessionManager(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        orgProfile = MiraOrgProfileManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        updateHeader();
        fetchData();
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

        tvKarat24 = v.findViewById(R.id.tvGoldPrice24);
        tvKarat21 = v.findViewById(R.id.tvGoldPrice21);
        tvKarat18 = v.findViewById(R.id.tvGoldPrice18);

        btnGate = v.findViewById(R.id.btnGateSecurity);
        btnInventory = v.findViewById(R.id.btnRfidAudit);

        swipeRefresh = v.findViewById(R.id.swipeRefresh);
        if (swipeRefresh != null) swipeRefresh.setOnRefreshListener(this::fetchData);
    }

    private void setupListeners() {
        if (btnGate != null) btnGate.setOnClickListener(v -> switchTab("SecureGate"));
        if (btnInventory != null) btnInventory.setOnClickListener(v -> switchTab("Inventory Pro"));
    }

    private void updateHeader() {
        if (tvDate != null) {
            Locale loc = isArabic ? new Locale("ar") : Locale.US;
            tvDate.setText(new SimpleDateFormat("EEEE، d MMMM yyyy", loc).format(new Date()));
        }

        if (tvGreeting != null) {
            String name = sessionManager.getDisplayName();
            if (name == null || name.isEmpty()) name = sessionManager.getUsername();
            if (name == null) name = isArabic ? "المستخدم" : "User";
            tvGreeting.setText(isArabic ? "مرحباً، " + name : "Welcome, " + name);
        }

        if (tvLocation != null) {
            tvLocation.setText("📍 " + branchesManager.getActiveBranchDisplayName());
        }

        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity a = (UHFMainActivity) getActivity();
            if (a.getSupportActionBar() != null) {
                String orgName = orgProfile.getOrgName();
                String userName = sessionManager.getDisplayName();
                if (userName == null || userName.isEmpty()) userName = sessionManager.getUsername();
                a.getSupportActionBar().setTitle(orgName + " - " + userName);
            }
        }
    }

    private void fetchData() {
        if (tvLiveStatus != null) {
            tvLiveStatus.setText(isArabic ? "● جاري التحديث..." : "● Updating...");
            tvLiveStatus.setTextColor(0xFFF59E0B);
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardFull();
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        processData(response.data);
                    } else {
                        showOffline();
                    }
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    showOffline();
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    private void processData(JSONObject data) {
        try {
            JSONObject inv = data.optJSONObject("inventory");
            if (inv != null) {
                setText(tvTotalItems, String.valueOf(inv.optInt("total_items", 0)));
                setText(tvTotalWeight, String.format(Locale.US, "%.1f g", inv.optDouble("total_weight", 0)));

                JSONObject w = inv.optJSONObject("weights");
                if (w != null) {
                    setKarat(tvKarat24, "24K", w.optInt("k24", 0));
                    setKarat(tvKarat21, "21K", w.optInt("k21", 0));
                    setKarat(tvKarat18, "18K", w.optInt("k18", 0));
                }
            }

            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                int countToday = sales.optInt("count_today", 0);
                setText(tvSoldToday, String.valueOf(countToday));

                String todayStr = sales.optString("today", "0");
                String monthStr = sales.optString("month", "0");
                String yearStr = sales.optString("year", "0");

                setText(tvSalesToday, cleanAmount(todayStr));
                setText(tvSalesMonth, cleanAmount(monthStr));
                setText(tvSalesYear, cleanAmount(yearStr));
            }

            if (tvLiveStatus != null) {
                tvLiveStatus.setText("● ONLINE");
                tvLiveStatus.setTextColor(0xFF16A34A);
            }

        } catch (Exception e) {
            Log.e(TAG, "Process error: " + e.getMessage());
        }
    }

    private void setKarat(TextView tv, String label, int count) {
        if (tv == null) return;
        if (count > 0) {
            tv.setText(label + " | " + count + " " + (isArabic ? "قطعة" : "pcs"));
            tv.setTextColor(0xFF1A237E);
        } else {
            tv.setText(label + " | —");
            tv.setTextColor(0xFF9CA3AF);
        }
    }

    private String cleanAmount(String amount) {
        if (amount == null || amount.isEmpty() || amount.equals("$0") || amount.equals("0")) return "—";
        String cleaned = amount.replace("$", "").replace(",", "").trim();
        try {
            double val = Double.parseDouble(cleaned);
            if (val == 0) return "—";
            return String.format(Locale.US, "%,.0f YER", val);
        } catch (Exception e) {
            return amount;
        }
    }

    private void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    private void showOffline() {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        if (tvLiveStatus != null) {
            tvLiveStatus.setText("● OFFLINE");
            tvLiveStatus.setTextColor(0xFFDC2626);
        }
    }

    private void switchTab(String tag) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity a = (UHFMainActivity) getActivity();
                if (a.mTabHost != null) a.mTabHost.setCurrentTabByTag(tag);
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), isArabic ? "جاري الفتح..." : "Opening...", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
