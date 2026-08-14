package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraDashboardFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "MiraDashboardFragment";
    private static final String OFFLINE_PREFS_BASE = "dashboard_cache_branch_";

    private TextView tvGreeting, tvDate, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24, tvKarat21, tvKarat18;
    private View btnGate, btnInventory;
    private SwipeRefreshLayout swipeRefresh;
    private View shimmerLayout;

    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private boolean isArabic;
    private SharedPreferences offlineCache;
    private final StringBuilder debugLog = new StringBuilder();
    private int currentBranchId = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            apiClient = MiraApiClient.getInstance(requireContext());
            sessionManager = new SessionManager(requireContext());
            branchesManager = MiraBranchesManager.getInstance(requireContext());
            orgProfile = MiraOrgProfileManager.getInstance(requireContext());
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            initViews(view);
            setupListeners();
            updateHeader();

            currentBranchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : -1;
            initBranchCache(currentBranchId);

            // إظهار التحميل الشامل في البداية
            showShimmer(true);
            loadOfflineData();
            fetchData();

        } catch (Throwable e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    private void initBranchCache(int branchId) {
        if (getContext() != null) {
            offlineCache = requireContext().getSharedPreferences(OFFLINE_PREFS_BASE + branchId, Context.MODE_PRIVATE);
        }
    }

    private void initViews(View v) {
        try {
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

            shimmerLayout = v.findViewById(R.id.shimmerLayout);
            swipeRefresh = v.findViewById(R.id.swipeRefresh);

            if (swipeRefresh != null) {
                swipeRefresh.setOnRefreshListener(this::fetchData);
            }

            if (tvGreeting != null) {
                tvGreeting.setOnLongClickListener(view -> {
                    showDebugDialog("🔍 تشخيص الداشبورد");
                    return true;
                });
                addDebug("✅ تم تجهيز زر التشخيص - اضغط مطولاً على رسالة الترحيب");
            }

        } catch (Throwable e) {
            Log.e(TAG, "initViews error", e);
        }
    }

    private void setupListeners() {
        if (btnGate != null) btnGate.setOnClickListener(v -> switchTab("SecureGate"));
        if (btnInventory != null) btnInventory.setOnClickListener(v -> switchTab("Inventory Pro"));
    }

    private void showShimmer(boolean show) {
        if (shimmerLayout != null) {
            shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                shimmerLayout.setAlpha(0.6f);
                shimmerLayout.animate().alpha(1.0f).setDuration(600).start();
            }
        }
        if (swipeRefresh != null) {
            swipeRefresh.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void updateHeader() {
        try {
            if (tvDate != null) {
                Locale loc = isArabic ? new Locale("ar") : Locale.US;
                String pattern = isArabic ? "EEEE، d MMMM yyyy" : "EEEE, MMMM d, yyyy";
                tvDate.setText(new SimpleDateFormat(pattern, loc).format(new Date()));
            }
            if (tvGreeting != null && sessionManager != null) {
                String name = sessionManager.getDisplayName();
                if (name == null || name.isEmpty()) name = sessionManager.getUsername();
                if (name == null) name = isArabic ? "المستخدم" : "User";
                tvGreeting.setText(isArabic ? "مرحباً، " + name : "Welcome, " + name);
            }
            if (tvLocation != null && branchesManager != null) {
                tvLocation.setText("📍 " + branchesManager.getActiveBranchDisplayName());
            }
        } catch (Throwable e) {
            Log.e(TAG, "updateHeader error", e);
        }
    }

    private void loadOfflineData() {
        if (offlineCache == null) return;
        try {
            int items = offlineCache.getInt("total_items", 0);
            String weight = offlineCache.getString("total_weight", "0.0 g");
            int sold = offlineCache.getInt("sold_today", 0);
            String sToday = offlineCache.getString("sales_today", "—");
            String sMonth = offlineCache.getString("sales_month", "—");
            String sYear = offlineCache.getString("sales_year", "—");
            int k24 = offlineCache.getInt("karat_24", 0);
            int k21 = offlineCache.getInt("karat_21", 0);
            int k18 = offlineCache.getInt("karat_18", 0);

            setText(tvTotalItems, String.valueOf(items));
            setText(tvTotalWeight, weight);
            setText(tvSoldToday, String.valueOf(sold));
            setText(tvSalesToday, sToday);
            setText(tvSalesMonth, sMonth);
            setText(tvSalesYear, sYear);
            setKarat(tvKarat24, "24K", k24);
            setKarat(tvKarat21, "21K", k21);
            setKarat(tvKarat18, "18K", k18);

            if (tvLiveStatus != null && offlineCache.contains("last_update")) {
                tvLiveStatus.setText(isArabic ? "وضع أوفلاين" : "Offline Cache");
                tvLiveStatus.setTextColor(0xFF6B7280);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadOfflineData error", e);
        }
    }

    private void saveOfflineData(JSONObject data) {
        if (offlineCache == null) return;
        try {
            JSONObject inv = data.optJSONObject("inventory");
            JSONObject sales = data.optJSONObject("sales");
            JSONObject w = inv != null ? inv.optJSONObject("weights") : null;

            double totalWeightVal = 0.0;
            if (inv != null) {
                totalWeightVal = inv.optDouble("total_weight", 0.0);
                if (Double.isNaN(totalWeightVal)) {
                    try {
                        totalWeightVal = Double.parseDouble(inv.optString("total_weight", "0.0"));
                    } catch (Exception ignored) {}
                }
            }

            offlineCache.edit()
                .putInt("total_items", inv != null ? inv.optInt("total_items", 0) : 0)
                .putString("total_weight", String.format(Locale.US, "%.1f g", totalWeightVal))
                .putInt("sold_today", sales != null ? sales.optInt("count_today", 0) : 0)
                .putString("sales_today", sales != null ? cleanAmount(sales.optString("today", "0")) : "—")
                .putString("sales_month", sales != null ? cleanAmount(sales.optString("month", "0")) : "—")
                .putString("sales_year", sales != null ? cleanAmount(sales.optString("year", "0")) : "—")
                .putInt("karat_24", w != null ? w.optInt("k24", 0) : 0)
                .putInt("karat_21", w != null ? w.optInt("k21", 0) : 0)
                .putInt("karat_18", w != null ? w.optInt("k18", 0) : 0)
                .putLong("last_update", System.currentTimeMillis())
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveOfflineData error", e);
        }
    }

    private void fetchData() {
        try {
            addDebug("🔄 بدء جلب البيانات...");

            if (tvLiveStatus != null) {
                tvLiveStatus.setText(isArabic ? "جاري التحديث..." : "Updating...");
                tvLiveStatus.setTextColor(0xFFF59E0B);
            }

            final int activeBranchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : -1;
            addDebug("📋 معرف الفرع النشط: " + activeBranchId);

            executorService.execute(() -> {
                MiraApiClient.ApiResponse response = null;
                try {
                    addDebug("📡 إرسال طلب API...");
                    response = apiClient.getDashboardFull(activeBranchId);
                } catch (Throwable e) {
                    addDebug("❌ خطأ شبكة/اتصال: " + e.getMessage());
                }

                final MiraApiClient.ApiResponse finalResponse = response;

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    try {
                        if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                            addDebug("✅ تم استلام البيانات بنجاح");
                            saveOfflineData(finalResponse.data);
                            processData(finalResponse.data);
                        } else {
                            addDebug("⚠️ فشل الاتصال، الاعتماد على البيانات المخزنة أوفلاين");
                            loadOfflineData();
                        }
                    } catch (Throwable e) {
                        addDebug("❌ استثناء معالجة: " + e.getMessage());
                        loadOfflineData();
                    } finally {
                        showShimmer(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    }
                });
            });
        } catch (Throwable e) {
            addDebug("❌ خطأ fetchData: " + e.getMessage());
            showShimmer(false);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            loadOfflineData();
        }
    }

    private void processData(JSONObject data) {
        try {
            JSONObject inv = data.optJSONObject("inventory");
            if (inv != null) {
                setText(tvTotalItems, String.valueOf(inv.optInt("total_items", 0)));

                double weightVal = inv.optDouble("total_weight", 0.0);
                if (Double.isNaN(weightVal)) {
                    try {
                        weightVal = Double.parseDouble(inv.optString("total_weight", "0.0"));
                    } catch (Exception e) {
                        weightVal = 0.0;
                    }
                }
                setText(tvTotalWeight, String.format(Locale.US, "%.1f g", weightVal));

                JSONObject w = inv.optJSONObject("weights");
                if (w != null) {
                    setKarat(tvKarat24, "24K", w.optInt("k24", 0));
                    setKarat(tvKarat21, "21K", w.optInt("k21", 0));
                    setKarat(tvKarat18, "18K", w.optInt("k18", 0));
                }
            }

            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                setText(tvSoldToday, String.valueOf(sales.optInt("count_today", 0)));
                setText(tvSalesToday, cleanAmount(sales.optString("today", "0")));
                setText(tvSalesMonth, cleanAmount(sales.optString("month", "0")));
                setText(tvSalesYear, cleanAmount(sales.optString("year", "0")));
            }

            if (tvLiveStatus != null) {
                tvLiveStatus.setText(isArabic ? "متصل" : "Online");
                tvLiveStatus.setTextColor(0xFF16A34A);
            }
        } catch (Throwable e) {
            Log.e(TAG, "processData JSON error", e);
        }
    }

    private void setKarat(TextView tv, String label, int count) {
        if (tv == null) return;
        tv.setText(count > 0 ? label + " | " + count + (isArabic ? " قطعة" : " pcs") : label + " | —");
    }

    private String cleanAmount(String amount) {
        if (amount == null || amount.isEmpty() || amount.equals("$0") || amount.equals("0") || amount.equals("null")) return "—";
        try {
            double val = Double.parseDouble(amount.replaceAll("[^0-9.]", "").trim());
            return val == 0 ? "—" : String.format(Locale.US, "%,.0f YER", val);
        } catch (Exception e) {
            return amount;
        }
    }

    private void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    private void switchTab(String tag) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity a = (UHFMainActivity) getActivity();
                if (a.mTabHost != null) a.mTabHost.setCurrentTabByTag(tag);
            }
        } catch (Exception e) {
            Log.e(TAG, "switchTab error", e);
        }
    }

    public void onBranchChanged() {
        if (!isAdded()) return;
        int newBranchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : -1;
        if (newBranchId != currentBranchId) {
            currentBranchId = newBranchId;
            initBranchCache(currentBranchId);
            updateHeader();
            showShimmer(true);
            loadOfflineData();
            fetchData();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (branchesManager != null) {
            branchesManager.registerListener(this);
            int latestBranchId = branchesManager.getActiveBranchId();
            if (latestBranchId != currentBranchId) {
                onBranchChanged();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (branchesManager != null) {
            branchesManager.unregisterListener(this);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("active_branch_id".equals(key) || "active_branch".equals(key)) {
            mainHandler.post(this::onBranchChanged);
        }
    }

    private void addDebug(String message) {
        Log.d("DASHBOARD_DEBUG", message);
        debugLog.append(message).append("\n");
    }

    private void showDebugDialog(String title) {
        if (!isAdded()) return;
        try {
            String rawMessage = debugLog.toString();
            final String message = rawMessage.isEmpty() ? "لا توجد بيانات تشخيص بعد" : rawMessage;

            new android.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("مسح", (dialog, which) -> debugLog.setLength(0))
                .setNegativeButton("نسخ", (dialog, which) -> {
                    if (getContext() != null) {
                        android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip =
                            android.content.ClipData.newPlainText("debug", message);
                        clipboard.setPrimaryClip(clip);
                        android.widget.Toast.makeText(requireContext(),
                            "تم النسخ", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("إغلاق", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing debug", e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
