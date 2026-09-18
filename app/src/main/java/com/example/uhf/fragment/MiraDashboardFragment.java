package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.ActivityTimelineAdapter;   // ✅ جديد
import com.example.uhf.adapter.QuickActionsAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.ApiProvider;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.RecentActivityCache;

import org.json.JSONArray;                                 // ✅ جديد
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboardFragment";
    private static final String OFFLINE_PREFS_BASE = "dashboard_cache_branch_";
    private static final String PREFS_PRIVACY = "mira_dashboard_privacy";
    private static final String KEY_HIDE_HERO = "hide_hero_value";
    private static final String KEY_HIDE_SALES = "hide_sales_value";
    
    private RecentActivityCache activityCache;

    private static final double OUNCE_TO_GRAM = 31.1035;
    private static final int MAX_QUICK_ACTIONS = 9;
    private static final long CACHE_VALIDITY_MS = 2 * 60 * 1000;
    private static final long GOLD_CACHE_VALIDITY_MS = 12 * 60 * 60 * 1000;
    private static final long PING_VALIDITY_MS = 30 * 1000;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    private static final long SWIPE_REFRESH_TIMEOUT_MS = 15 * 1000;

    // ═══════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════
    private JSONObject lastSalesData = null;
    private boolean isHeroHidden = true;
    private boolean isSalesHidden = true;

    // ═══════════════════════════════════════════════════════
    // UI Elements
    // ═══════════════════════════════════════════════════════
    private ImageButton btnToggleHeroVisibility;
    private ImageButton btnToggleSalesVisibility;

    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24, tvKarat21, tvKarat18;
    private TextView tvOuncePrice, tvPriceSource, tvLastUpdate;
    private TextView tvGoldTrend, tvGoldChange;
    private View btnRefreshGold;
    private SwipeRefreshLayout swipeRefresh;
    private View shimmerLayout, loadingOverlay;
    private ProgressBar pbGoldLoading;
    private CardView cardGoldPrices;
    private RecyclerView rvQuickActions;
    private QuickActionsAdapter quickAdapter;

    private View heroBalanceCard, heroSparkline;
    private TextView tvHeroBalance, tvHeroTrend, tvHeroSubtitle;
    private TextView tvHeroValueYER, tvHeroValueSAR, tvHeroValueUSD;
    private View alertsStrip;
    private TextView tvAlertIcon, tvAlertMessage, tvAlertAction;
    private View liveStatusDot;
    private TextView tvLiveStatusText;

    // ✅ Recent Activity
    private RecyclerView rvRecentActivity;
    private ActivityTimelineAdapter recentActivityAdapter;
    private TextView tvViewAllActivity;

    // ═══════════════════════════════════════════════════════
    // Managers & State
    // ═══════════════════════════════════════════════════════
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;
    private MiraBranchesManager.OnBranchChangedListener branchListener;
    private MiraPriceManager priceManager;
    private MiraQuickAccessSettings quickAccessSettings;
    private PermissionManager permissionManager;

    private static final ExecutorService sharedExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> dataFuture, goldPriceFuture, pingFuture;
    private Runnable swipeTimeoutRunnable;

    private boolean isArabic;
    private boolean isReceiverRegistered = false;

    private android.content.SharedPreferences offlineCache, goldCachePrefs;
    private int currentBranchId = -1;
    private boolean isViewInitialized = false;
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isGoldRefreshing = new AtomicBoolean(false);
    private boolean isDataLoaded = false;
    private long lastDataFetchTime = 0, lastGoldFetchTime = 0, lastPingTime = 0;

    private double currentInventoryValue = 0;
    private int currentItemCount = 0;
    private double currentTotalWeight = 0;

    // ═══════════════════════════════════════════════════════
    // Formatters
    // ═══════════════════════════════════════════════════════
    private SimpleDateFormat getTimeFormatter() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US);
    }

    private NumberFormat getCurrencyFormatter() {
        return NumberFormat.getCurrencyInstance(Locale.US);
    }

    // ═══════════════════════════════════════════════════════
    // Auto Refresh
    // ═══════════════════════════════════════════════════════
    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded() && isViewInitialized) {
                long now = System.currentTimeMillis();
                if (now - lastDataFetchTime >= REFRESH_INTERVAL_MS) {
                    fetchData();
                }
                mainHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };

    // ═══════════════════════════════════════════════════════
    // Gold Price Data
    // ═══════════════════════════════════════════════════════
    private static class GoldPriceData {
        final double price24, price21, price18, ouncePrice;
        final String source;
        GoldPriceData(double price24, double price21, double price18, double ouncePrice, String source) {
            this.price24 = price24;
            this.price21 = price21;
            this.price18 = price18;
            this.ouncePrice = ouncePrice;
            this.source = source;
        }
    }

    // ═══════════════════════════════════════════════════════
    // Broadcast Receiver
    // ═══════════════════════════════════════════════════════
    private final BroadcastReceiver dataRefreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !isAdded() || !isViewInitialized) return;
            String action = intent.getAction();
            Log.d(TAG, "Received broadcast: " + action);

            if ("com.example.uhf.REFRESH_DASHBOARD".equals(action) ||
                "com.example.uhf.REFRESH_INVENTORY".equals(action) ||
                "com.example.uhf.REFRESH_SALES".equals(action)) {

                mainHandler.post(() -> {
                    if (isAdded() && isViewInitialized) {
                        isDataLoaded = false;
                        lastDataFetchTime = 0;
                        isLoading.set(false);
                        if (isResumed()) fetchData();
                    }
                });
            }
        }
    };

    // ═══════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
        } catch (Exception e) {
            logNonFatal("onCreateView", e);
            return null;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            initializeManagers();
            initViews(view);
            setupProfileListener();
            setupBranchListener();
            setupQuickAccessGrid();

            currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            initBranchCache(currentBranchId);

            updateHeader();
            showShimmer(false);

            isViewInitialized = true;

            // ✅ تشغيل MotionLayout بعد تحميل الشاشة
            if (view instanceof MotionLayout) {
                final MotionLayout motionLayout = (MotionLayout) view;
                motionLayout.setProgress(0f);
                motionLayout.post(() -> motionLayout.transitionToEnd());
            }

            syncOrgProfileFromServer(false);
            fetchData();

            // ✅ تحميل آخر العمليات
            fetchRecentActivity();

            startAutoRefresh();
        } catch (Throwable e) {
            logNonFatal("onViewCreated", e);
        }
    }

    private void initializeManagers() {
        try {
            if (!isAdded()) return;
            Context context = requireContext();
            apiClient = MiraApiClient.getInstance(context);
            sessionManager = new SessionManager(context);
            branchesManager = MiraBranchesManager.getInstance(context);
            orgProfile = MiraOrgProfileManager.getInstance(context);
            priceManager = MiraPriceManager.getInstance(context);
            quickAccessSettings = MiraQuickAccessSettings.getInstance(context);
            permissionManager = PermissionManager.getInstance(context);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            goldCachePrefs = context.getSharedPreferences("gold_cache_prefs", Context.MODE_PRIVATE);
            lastGoldFetchTime = goldCachePrefs.getLong("last_gold_fetch_time", 0);
            activityCache = new RecentActivityCache(context);
        } catch (Exception e) {
            logNonFatal("initializeManagers", e);
        }
    }

    private void initViews(View v) {
        try {
            tvTotalItems = v.findViewById(R.id.tvTotalItems);
            tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
            tvSoldToday = v.findViewById(R.id.tvSoldToday);
            tvSalesToday = v.findViewById(R.id.tvSalesToday);
            tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
            tvSalesYear = v.findViewById(R.id.tvSalesYear);
            tvKarat24 = v.findViewById(R.id.tvGoldPrice24);
            tvKarat21 = v.findViewById(R.id.tvGoldPrice21);
            tvKarat18 = v.findViewById(R.id.tvGoldPrice18);

            shimmerLayout = v.findViewById(R.id.shimmerLayout);
            swipeRefresh = v.findViewById(R.id.swipeRefresh);
            tvOuncePrice = v.findViewById(R.id.tvOuncePrice);
            tvPriceSource = v.findViewById(R.id.tvPriceSource);
            tvLastUpdate = v.findViewById(R.id.tvLastUpdate);
            tvGoldTrend = v.findViewById(R.id.tvGoldTrend);
            tvGoldChange = v.findViewById(R.id.tvGoldChange);
            btnRefreshGold = v.findViewById(R.id.btnRefreshGold);
            cardGoldPrices = v.findViewById(R.id.cardGoldPrices);
            loadingOverlay = v.findViewById(R.id.loadingOverlay);
            pbGoldLoading = v.findViewById(R.id.pbGoldLoading);

            heroBalanceCard = v.findViewById(R.id.heroBalanceCard);
            heroSparkline = v.findViewById(R.id.heroSparkline);
            tvHeroBalance = v.findViewById(R.id.tvHeroBalance);
            tvHeroTrend = v.findViewById(R.id.tvHeroTrend);
            tvHeroSubtitle = v.findViewById(R.id.tvHeroSubtitle);
            tvHeroValueYER = v.findViewById(R.id.tvHeroValueYER);
            tvHeroValueSAR = v.findViewById(R.id.tvHeroValueSAR);
            tvHeroValueUSD = v.findViewById(R.id.tvHeroValueUSD);

            alertsStrip = v.findViewById(R.id.alertsStrip);
            tvAlertIcon = v.findViewById(R.id.tvAlertIcon);
            tvAlertMessage = v.findViewById(R.id.tvAlertMessage);
            tvAlertAction = v.findViewById(R.id.tvAlertAction);

            liveStatusDot = v.findViewById(R.id.liveStatusDot);
            tvLiveStatusText = v.findViewById(R.id.tvLiveStatusText);

            btnToggleHeroVisibility = v.findViewById(R.id.btnToggleHeroVisibility);
            btnToggleSalesVisibility = v.findViewById(R.id.btnToggleSalesVisibility);

            // ✅ Recent Activity
            rvRecentActivity = v.findViewById(R.id.rvRecentActivity);
            tvViewAllActivity = v.findViewById(R.id.tvViewAllActivity);

            if (rvRecentActivity != null) {
                rvRecentActivity.setLayoutManager(new LinearLayoutManager(requireContext()));
                recentActivityAdapter = new ActivityTimelineAdapter(
                        isArabic,
                        activity -> handleRecentActivityClick(activity));
                rvRecentActivity.setAdapter(recentActivityAdapter);
                rvRecentActivity.setHasFixedSize(false);
                rvRecentActivity.setNestedScrollingEnabled(false);
            }

            if (tvViewAllActivity != null) {
                tvViewAllActivity.setOnClickListener(v2 ->
                        navigateToFragment(new MiraInventoryProFragment(), "Scan"));
            }

            // ═══════════════════════════════════════
            // Listeners
            // ═══════════════════════════════════════

            if (btnRefreshGold != null) {
                btnRefreshGold.setOnClickListener(v2 -> {
                    Log.d(TAG, "🔘 Refresh Gold button clicked. isGoldRefreshing=" + isGoldRefreshing.get());
                    if (!isGoldRefreshing.get()) {
                        fetchLiveGoldPrices(true);
                    } else {
                        Log.d(TAG, "⏳ Gold refresh already in progress");
                    }
                });
            }

            if (btnToggleHeroVisibility != null) {
                btnToggleHeroVisibility.setOnClickListener(v2 -> {
                    Log.d(TAG, "👁️ Toggle Hero visibility clicked. Before: " + isHeroHidden);
                    toggleHeroVisibility();
                });
            }

            if (btnToggleSalesVisibility != null) {
                btnToggleSalesVisibility.setOnClickListener(v2 -> {
                    Log.d(TAG, "👁️ Toggle Sales visibility clicked. Before: " + isSalesHidden);
                    toggleSalesVisibility();
                });
            }

            if (tvAlertAction != null) {
                tvAlertAction.setOnClickListener(v2 ->
                        navigateToFragment(new MiraUHFReadTagFragment(), "Scan"));
            }

            if (cardGoldPrices != null && permissionManager != null
                    && !permissionManager.canAccessFragment("GoldRates")) {
                cardGoldPrices.setVisibility(View.GONE);
            }

            // Quick Access RecyclerView
            rvQuickActions = v.findViewById(R.id.rvQuickActions);
            if (rvQuickActions != null) {
                rvQuickActions.setLayoutManager(new GridLayoutManager(getContext(), 3));
                rvQuickActions.setHasFixedSize(true);
                quickAdapter = new QuickActionsAdapter(this::handleQuickAction);
                rvQuickActions.setAdapter(quickAdapter);
            }

            if (swipeRefresh != null) {
                swipeRefresh.setOnRefreshListener(this::refreshAllData);
                swipeRefresh.setColorSchemeResources(R.color.colorPrimary, R.color.gray1);
            }

            // تحميل حالة الإخفاء
            android.content.SharedPreferences privacyPrefs =
                    requireContext().getSharedPreferences(PREFS_PRIVACY, Context.MODE_PRIVATE);
            isHeroHidden = privacyPrefs.getBoolean(KEY_HIDE_HERO, true);
            isSalesHidden = privacyPrefs.getBoolean(KEY_HIDE_SALES, true);

            // تطبيق الحالة الأولية
            updateHeroBalance();
            applySalesVisibility();

            // تحديث أيقونات العين
            if (btnToggleHeroVisibility != null) {
                btnToggleHeroVisibility.setImageResource(
                        isHeroHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
            }
            if (btnToggleSalesVisibility != null) {
                btnToggleSalesVisibility.setImageResource(
                        isSalesHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
            }

        } catch (Exception e) {
            logNonFatal("initViews", e);
        }
    }

    private void updateHeader() {
        // Greeting Card محذوف
    }

    // ═══════════════════════════════════════════════════════
    // Hero Balance
    // ═══════════════════════════════════════════════════════

    private void updateHeroBalance() {
        try {
            if (tvHeroBalance == null) return;

            double totalValue = currentInventoryValue;
            int itemCount = currentItemCount;
            double totalWeight = currentTotalWeight;

            // حساب من سعر الذهب إذا القيمة صفر
            if (totalValue <= 0 && priceManager != null && totalWeight > 0) {
                double price24 = priceManager.getGoldPricePerGram(24);
                if (price24 > 0) {
                    totalValue = totalWeight * price24;
                }
            }

            // ✅ عرض القيمة — مع الإخفاء
            if (isHeroHidden) {
                tvHeroBalance.setText("••••••••");
            } else {
                tvHeroBalance.setText(String.format(Locale.US, "$%,.2f", totalValue));
            }

            // Subtitle يظهر دائماً
            if (tvHeroSubtitle != null) {
                tvHeroSubtitle.setText(String.format(
                        "👑 %d قطعة • %.1fg", itemCount, totalWeight));
            }

            // ═══════════════════════════════════════════════════════
            // ✅ عرض القيمة بـ 3 عملات
            // ═══════════════════════════════════════════════════════

            // 1. بالدولار الأمريكي
            String usdText;
            if (isHeroHidden) {
                usdText = "••••••";
            } else {
                usdText = String.format(Locale.US, "%,.2f", totalValue);
            }
            if (tvHeroValueUSD != null) {
                tvHeroValueUSD.setText(usdText);
            }

            // 2. بالريال السعودي (USD × 3.75)
            String sarText;
            if (isHeroHidden) {
                sarText = "••••••";
            } else {
                double sarValue = totalValue * 3.75;
                sarText = String.format(Locale.US, "%,.2f", sarValue);
            }
            if (tvHeroValueSAR != null) {
                tvHeroValueSAR.setText(sarText);
            }

            // 3. بالريال اليمني (USD × 530)
            String yerText;
            if (isHeroHidden) {
                yerText = "••••••";
            } else {
                double yerValue = totalValue * 530.0;
                yerText = String.format(Locale.US, "%,.0f", yerValue);
            }
            if (tvHeroValueYER != null) {
                tvHeroValueYER.setText(yerText);
            }

        } catch (Exception e) {
            logNonFatal("updateHeroBalance", e);
        }
    }

    private void toggleHeroVisibility() {
        if (!isAdded() || tvHeroBalance == null) return;

        isHeroHidden = !isHeroHidden;

        requireContext().getSharedPreferences(PREFS_PRIVACY, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDE_HERO, isHeroHidden).apply();

        if (btnToggleHeroVisibility != null) {
            btnToggleHeroVisibility.setImageResource(
                    isHeroHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
        }

        updateHeroBalance();

        Log.d(TAG, "✅ Hero visibility toggled. isHeroHidden=" + isHeroHidden);
    }

    // ═══════════════════════════════════════════════════════
    // Sales Visibility
    // ═══════════════════════════════════════════════════════

    private void toggleSalesVisibility() {
        if (!isAdded() || tvSalesToday == null) return;

        isSalesHidden = !isSalesHidden;

        requireContext().getSharedPreferences(PREFS_PRIVACY, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDE_SALES, isSalesHidden).apply();

        if (btnToggleSalesVisibility != null) {
            btnToggleSalesVisibility.setImageResource(
                    isSalesHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
        }

        applySalesVisibility();

        Log.d(TAG, "✅ Sales visibility toggled. isSalesHidden=" + isSalesHidden);
    }

    private void applySalesVisibility() {
        if (isSalesHidden) {
            setText(tvSalesToday, "••••••");
            setText(tvSalesMonth, "••••••");
            setText(tvSalesYear, "••••••");
        } else if (lastSalesData != null) {
            applySalesData(lastSalesData);
        }
    }

    private void applySalesData(JSONObject sales) {
        if (sales == null || tvSalesToday == null) return;

        if (isSalesHidden) {
            setText(tvSalesToday, "••••••");
            setText(tvSalesMonth, "••••••");
            setText(tvSalesYear, "••••••");
            return;
        }

        setText(tvSalesToday, formatCurrency(sales.optDouble("today", 0.0)));
        setText(tvSalesMonth, formatCurrency(sales.optDouble("month", 0.0)));
        setText(tvSalesYear, formatCurrency(sales.optDouble("year", 0.0)));
    }

    // ═══════════════════════════════════════════════════════
    // Alerts
    // ═══════════════════════════════════════════════════════
    private void updateAlerts(int count, String message) {
        try {
            if (alertsStrip == null) return;

            if (count > 0) {
                alertsStrip.setVisibility(View.VISIBLE);
                if (tvAlertIcon != null) tvAlertIcon.setText("⚠️");
                if (tvAlertMessage != null) tvAlertMessage.setText(message);
                if (tvAlertAction != null) {
                    tvAlertAction.setText(isArabic
                            ? "عرض (" + count + ") →"
                            : "View (" + count + ") →");
                }
            } else {
                alertsStrip.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            logNonFatal("updateAlerts", e);
        }
    }

    private void computeAndShowAlerts(JSONObject data) {
        try {
            if (data == null) { updateAlerts(0, null); return; }

            int alertsCount = 0;
            StringBuilder message = new StringBuilder();
            JSONObject inv = data.optJSONObject("inventory");

            if (inv != null) {
                int itemsWithoutWeight = inv.optInt("items_without_weight", -1);
                int itemsWithoutPrice = inv.optInt("items_without_price", -1);

                if (itemsWithoutWeight > 0) {
                    alertsCount += itemsWithoutWeight;
                    message.append(isArabic
                            ? itemsWithoutWeight + " قطعة بدون وزن"
                            : itemsWithoutWeight + " items without weight");
                }
                if (itemsWithoutPrice > 0) {
                    if (message.length() > 0) message.append(" • ");
                    alertsCount += itemsWithoutPrice;
                    message.append(isArabic
                            ? itemsWithoutPrice + " قطعة بدون سعر"
                            : itemsWithoutPrice + " items without price");
                }
            }

            if (alertsCount > 0) {
                updateAlerts(alertsCount, message.toString());
            } else {
                updateAlerts(0, null);
            }
        } catch (Exception e) {
            logNonFatal("computeAndShowAlerts", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Live Status
    // ═══════════════════════════════════════════════════════
    private void updateLiveStatusText(boolean isOnline) {
        try {
            if (!isAdded()) return;
            Context context = getContext();
            if (context == null) return;

            if (tvLiveStatusText != null) {
                tvLiveStatusText.setText(isOnline
                        ? (isArabic ? "متصل" : "Online")
                        : (isArabic ? "غير متصل" : "Offline"));
                tvLiveStatusText.setTextColor(isOnline
                        ? ContextCompat.getColor(context, R.color.status_online_green)
                        : ContextCompat.getColor(context, R.color.status_offline_red));
            }

            if (liveStatusDot != null) {
                liveStatusDot.setBackgroundResource(isOnline
                        ? R.drawable.bg_status_dot_green
                        : R.drawable.bg_status_dot_red);
            }
        } catch (Exception e) {
            logNonFatal("updateLiveStatusText", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Listeners
    // ═══════════════════════════════════════════════════════
    private void setupProfileListener() {
        try {
            if (orgProfile == null) return;
            profileListener = () -> mainHandler.post(() -> {
                if (isAdded() && isViewInitialized) updateHeader();
            });
            orgProfile.addListener(profileListener);
        } catch (Exception e) {
            logNonFatal("setupProfileListener", e);
        }
    }

    private void setupBranchListener() {
        try {
            if (branchesManager == null) return;
            branchListener = () -> {
                if (!isAdded()) return;
                mainHandler.post(() -> {
                    if (isAdded() && isViewInitialized) updateHeader();
                });
            };
            branchesManager.addListener(branchListener);
        } catch (Exception e) {
            logNonFatal("setupBranchListener", e);
        }
    }

    private void syncOrgProfileFromServer(boolean force) {
        try {
            if (orgProfile == null || apiClient == null) return;
            orgProfile.syncFromServer(apiClient, force, new MiraOrgProfileManager.SyncCallback() {
                @Override
                public void onSuccess() {
                    mainHandler.post(() -> {
                        if (isAdded() && isViewInitialized) updateHeader();
                    });
                    syncBranchesListFromServer();
                }
                @Override
                public void onError(String error) {
                    Log.w(TAG, "⚠️ Org sync failed: " + error);
                }
            });
        } catch (Exception e) {
            logNonFatal("syncOrgProfileFromServer", e);
        }
    }

    private void syncBranchesListFromServer() {
        try {
            if (branchesManager == null) return;
            branchesManager.syncFromServer(new MiraBranchesManager.SyncCallback() {
                @Override
                public void onSuccess() { Log.i(TAG, "✅ Branches synced"); }
                @Override
                public void onError(String error) { Log.w(TAG, "⚠️ Branches sync: " + error); }
            });
        } catch (Exception e) {
            logNonFatal("syncBranchesListFromServer", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Quick Access
    // ═══════════════════════════════════════════════════════
    private void setupQuickAccessGrid() {
        try {
            if (quickAccessSettings == null || quickAdapter == null) return;

            sharedExecutor.execute(() -> {
                try {
                    if (!isAdded()) return;

                    List<MiraQuickAccessSettings.QuickAction> enabledActions =
                            quickAccessSettings.getEnabledActions();
                    List<MiraQuickAccessSettings.QuickAction> filteredActions = new ArrayList<>();

                    if (enabledActions != null && !enabledActions.isEmpty()) {
                        for (MiraQuickAccessSettings.QuickAction action : enabledActions) {
                            String tag = getFragmentTagForAction(action.id);
                            boolean hasPermission = permissionManager == null
                                    || permissionManager.canAccessFragment(tag);
                            if (hasPermission) filteredActions.add(action);
                        }
                    }

                    if (filteredActions.isEmpty()) {
                        filteredActions = getDefaultQuickActions();
                    }

                    final List<MiraQuickAccessSettings.QuickAction> finalList = filteredActions;
                    mainHandler.post(() -> {
                        if (isAdded() && quickAdapter != null) {
                            quickAdapter.submitList(finalList);
                        }
                    });

                } catch (Exception e) {
                    logNonFatal("setupQuickAccessGrid.inner", e);
                }
            });
        } catch (Exception e) {
            logNonFatal("setupQuickAccessGrid", e);
        }
    }

    private List<MiraQuickAccessSettings.QuickAction> getDefaultQuickActions() {
        List<MiraQuickAccessSettings.QuickAction> defaults = new ArrayList<>();
        MiraQuickAccessSettings settings = MiraQuickAccessSettings.getInstance(requireContext());
        if (settings == null) return defaults;

        List<MiraQuickAccessSettings.QuickAction> allActions = settings.getAllActions();
        if (allActions == null || allActions.isEmpty()) return defaults;

        for (MiraQuickAccessSettings.QuickAction action : allActions) {
            String tag = getFragmentTagForAction(action.id);
            boolean hasPermission = permissionManager == null
                    || permissionManager.canAccessFragment(tag);
            if (hasPermission && defaults.size() < MAX_QUICK_ACTIONS) {
                defaults.add(action);
            }
            if (defaults.size() >= MAX_QUICK_ACTIONS) break;
        }
        return defaults;
    }

    // ═══════════════════════════════════════════════════════
    // Live Status (Ping)
    // ═══════════════════════════════════════════════════════
    private void updateLiveStatus() {
        try {
            if (apiClient == null) return;
            cancelFuture(pingFuture);
            pingFuture = sharedExecutor.submit(() -> {
                boolean isOnline = apiClient.pingApi();
                mainHandler.post(() -> updateLiveStatusText(isOnline));
            });
        } catch (Exception e) {
            logNonFatal("updateLiveStatus", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Gold Prices
    // ═══════════════════════════════════════════════════════
    private void fetchLiveGoldPrices(boolean forceRefresh) {
        try {
            if (apiClient == null) {
                Log.w(TAG, "⚠️ apiClient is null");
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }
            if (isGoldRefreshing.get()) {
                Log.d(TAG, "⏳ Gold refresh in progress, skipping");
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }

            long now = System.currentTimeMillis();
            if (!forceRefresh && (now - lastGoldFetchTime) < GOLD_CACHE_VALIDITY_MS) {
                displayGoldPricesFromCache();
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }

            isGoldRefreshing.set(true);
            showGoldLoadingState(true);
            cancelFuture(goldPriceFuture);

            goldPriceFuture = sharedExecutor.submit(() -> {
                try {
                    List<ApiProvider> providers = apiClient.getActiveProviders();
                    ApiProvider activeProvider = null;

                    if (providers != null && !providers.isEmpty()) {
                        for (ApiProvider provider : providers) {
                            if (provider.isActive() && provider.getApiKey() != null
                                    && !provider.getApiKey().isEmpty()) {
                                activeProvider = provider;
                                break;
                            }
                        }
                        if (activeProvider == null) activeProvider = providers.get(0);
                    }

                    if (activeProvider == null) {
                        mainHandler.post(() -> {
                            isGoldRefreshing.set(false);
                            showGoldLoadingState(false);
                            if (isAdded()) displayGoldPricesFromCache();
                            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        });
                        return;
                    }

                    MiraApiClient.ApiResponse response =
                            apiClient.fetchGoldPriceWithProvider(activeProvider.getId());
                    GoldPriceData priceData = parseGoldPriceData(response);

                    mainHandler.post(() -> {
                        isGoldRefreshing.set(false);
                        showGoldLoadingState(false);
                        if (!isAdded()) return;

                        if (priceData != null) {
                            lastGoldFetchTime = System.currentTimeMillis();
                            displayGoldPrices(priceData);
                            updateHeroBalance();
                            if (goldCachePrefs != null) {
                                goldCachePrefs.edit()
                                        .putLong("last_gold_fetch_time", System.currentTimeMillis())
                                        .apply();
                            }
                            Toast.makeText(getContext(),
                                    isArabic ? "✅ تم تحديث أسعار الذهب" : "✅ Gold prices updated",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            displayGoldPricesFromCache();
                            Toast.makeText(getContext(),
                                    isArabic ? "⚠️ تعذر التحديث - عرض الكاش" : "⚠️ Update failed - showing cache",
                                    Toast.LENGTH_SHORT).show();
                        }
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    logNonFatal("fetchLiveGoldPrices.thread", e);
                    mainHandler.post(() -> {
                        isGoldRefreshing.set(false);
                        showGoldLoadingState(false);
                        if (isAdded()) displayGoldPricesFromCache();
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                }
            });
        } catch (Exception e) {
            logNonFatal("fetchLiveGoldPrices", e);
            isGoldRefreshing.set(false);
            showGoldLoadingState(false);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        }
    }

    private void showGoldLoadingState(boolean loading) {
        try {
            if (btnRefreshGold == null) return;
            btnRefreshGold.setEnabled(!loading);
            btnRefreshGold.setAlpha(loading ? 0.4f : 1.0f);
            if (pbGoldLoading != null) {
                pbGoldLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
            }
        } catch (Exception e) {
            logNonFatal("showGoldLoadingState", e);
        }
    }

    private GoldPriceData parseGoldPriceData(MiraApiClient.ApiResponse response) {
        try {
            if (response == null || !response.isSuccess || response.data == null) return null;
            JSONObject data = response.data.optJSONObject("data");
            if (data == null) data = response.data;
            JSONObject prices = data.optJSONObject("prices");
            if (prices == null) prices = data;

            double price24 = prices.optDouble("24k", 0);
            double price21 = prices.optDouble("21k", 0);
            double price18 = prices.optDouble("18k", 0);
            double ouncePrice = data.optDouble("price_per_ounce",
                    prices.optDouble("ounce_price", price24 * OUNCE_TO_GRAM));
            String source = data.optString("source", "server");

            return new GoldPriceData(price24, price21, price18, ouncePrice, source);
        } catch (Exception e) {
            logNonFatal("parseGoldPriceData", e);
            return null;
        }
    }

    private void displayGoldPrices(GoldPriceData data) {
        try {
            setGoldPrice(tvKarat24, "24K", data.price24);
            setGoldPrice(tvKarat21, "21K", data.price21);
            setGoldPrice(tvKarat18, "18K", data.price18);

            if (tvOuncePrice != null && data.ouncePrice > 0) {
                tvOuncePrice.setText(String.format(Locale.US, "$%,.2f", data.ouncePrice));
            }
            if (tvPriceSource != null) {
                tvPriceSource.setText("📡 " + getSourceDisplayName(data.source));
            }
            if (tvLastUpdate != null) {
                SimpleDateFormat fmt = getTimeFormatter();
                String time = fmt.format(new Date());
                tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
            }

            calculateTrend(data.price24);
        } catch (Exception e) {
            logNonFatal("displayGoldPrices", e);
        }
    }

    private void displayGoldPricesFromCache() {
        try {
            if (priceManager == null) return;
            double price24 = priceManager.getGoldPricePerGram(24);
            double price21 = priceManager.getGoldPricePerGram(21);
            double price18 = priceManager.getGoldPricePerGram(18);

            setGoldPrice(tvKarat24, "24K", price24);
            setGoldPrice(tvKarat21, "21K", price21);
            setGoldPrice(tvKarat18, "18K", price18);

            if (tvOuncePrice != null && price24 > 0) {
                tvOuncePrice.setText(String.format(Locale.US, "$%,.2f", price24 * OUNCE_TO_GRAM));
            }

            if (tvPriceSource != null) {
                long now = System.currentTimeMillis();
                long timeRemaining = GOLD_CACHE_VALIDITY_MS - (now - lastGoldFetchTime);
                if (timeRemaining > 0) {
                    long hours = timeRemaining / (60 * 60 * 1000);
                    long minutes = (timeRemaining % (60 * 60 * 1000)) / (60 * 1000);
                    tvPriceSource.setText(isArabic
                            ? "📡 كاش محلي - التحديث بعد " + hours + " س " + minutes + " د"
                            : "📡 Cache - update in " + hours + "h " + minutes + "m");
                } else {
                    tvPriceSource.setText(isArabic ? "📡 جاهز للتحديث" : "📡 Ready to update");
                }
            }

            if (tvLastUpdate != null && offlineCache != null) {
                long lastUpdate = offlineCache.getLong("last_update", 0);
                if (lastUpdate > 0) {
                    SimpleDateFormat fmt = getTimeFormatter();
                    String time = fmt.format(new Date(lastUpdate));
                    tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
                }
            }

            updateHeroBalance();
        } catch (Exception e) {
            logNonFatal("displayGoldPricesFromCache", e);
        }
    }

    private void calculateTrend(double currentPrice) {
        if (tvGoldTrend == null || tvGoldChange == null || offlineCache == null) return;

        sharedExecutor.execute(() -> {
            try {
                double previousPrice = offlineCache.getFloat("gold_price_24k_prev", 0);

                if (previousPrice > 0 && currentPrice > 0) {
                    double change = currentPrice - previousPrice;
                    double changePercent = (change / previousPrice) * 100;

                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        if (change > 0) {
                            tvGoldTrend.setText("📈");
                            tvGoldChange.setText(String.format(Locale.US, "+%.2f%%", changePercent));
                            tvGoldChange.setTextColor(0xFF16A34A);
                        } else if (change < 0) {
                            tvGoldTrend.setText("📉");
                            tvGoldChange.setText(String.format(Locale.US, "%.2f%%", changePercent));
                            tvGoldChange.setTextColor(0xFFDC2626);
                        } else {
                            tvGoldTrend.setText("➡️");
                            tvGoldChange.setText("0.00%");
                            tvGoldChange.setTextColor(0xFF6B7280);
                        }

                        if (tvHeroTrend != null) {
                            String trendText = String.format(Locale.US, "%.2f%%", changePercent);
                            tvHeroTrend.setText((change > 0 ? "📈 " : change < 0 ? "📉 " : "➡️ ") + trendText);
                        }
                    });
                }

                if (currentPrice > 0) {
                    offlineCache.edit().putFloat("gold_price_24k_prev", (float) currentPrice).apply();
                }
            } catch (Exception e) {
                logNonFatal("calculateTrend", e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // Offline Data
    // ═══════════════════════════════════════════════════════
    private void loadOfflineData() {
        if (offlineCache == null) return;

        sharedExecutor.execute(() -> {
            try {
                final int totalItems = offlineCache.getInt("total_items", 0);
                final float totalWeight = offlineCache.getFloat("total_weight", 0.0f);
                final int soldToday = offlineCache.getInt("sold_today", 0);
                final float salesToday = offlineCache.getFloat("sales_today", 0.0f);
                final float salesMonth = offlineCache.getFloat("sales_month", 0.0f);
                final float salesYear = offlineCache.getFloat("sales_year", 0.0f);
                final float inventoryValue = offlineCache.getFloat("inventory_value", 0.0f);

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    setText(tvTotalItems, String.valueOf(totalItems));
                    setText(tvTotalWeight, formatWeight(totalWeight));
                    setText(tvSoldToday, String.valueOf(soldToday));

                    currentItemCount = totalItems;
                    currentTotalWeight = totalWeight;
                    currentInventoryValue = inventoryValue;
                    updateHeroBalance();

                    if (isSalesHidden) {
                        setText(tvSalesToday, "••••••");
                        setText(tvSalesMonth, "••••••");
                        setText(tvSalesYear, "••••••");
                    } else {
                        setText(tvSalesToday, formatCurrency(salesToday));
                        setText(tvSalesMonth, formatCurrency(salesMonth));
                        setText(tvSalesYear, formatCurrency(salesYear));
                    }

                    if (priceManager != null) displayGoldPricesFromCache();
                });
            } catch (Exception e) {
                logNonFatal("loadOfflineData", e);
            }
        });
    }

    private void saveOfflineData(JSONObject data) {
        try {
            if (offlineCache == null) return;
            sharedExecutor.execute(() -> {
                try {
                    JSONObject inv = data.optJSONObject("inventory");
                    JSONObject sales = data.optJSONObject("sales");

                    offlineCache.edit()
                            .putInt("total_items", inv != null ? inv.optInt("total_items", 0) : 0)
                            .putFloat("total_weight", (float) (inv != null ? inv.optDouble("total_weight", 0.0) : 0.0))
                            .putFloat("inventory_value", (float) (inv != null ? inv.optDouble("total_value", 0.0) : 0.0))
                            .putInt("sold_today", sales != null ? sales.optInt("count_today", 0) : 0)
                            .putFloat("sales_today", (float) (sales != null ? sales.optDouble("today", 0.0) : 0.0))
                            .putFloat("sales_month", (float) (sales != null ? sales.optDouble("month", 0.0) : 0.0))
                            .putFloat("sales_year", (float) (sales != null ? sales.optDouble("year", 0.0) : 0.0))
                            .putLong("last_update", System.currentTimeMillis())
                            .apply();
                } catch (Exception e) {
                    logNonFatal("saveOfflineData.inner", e);
                }
            });
        } catch (Exception e) {
            logNonFatal("saveOfflineData", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Fetch Data
    // ═══════════════════════════════════════════════════════
    private void fetchData() {
        try {
            if (apiClient == null || !isAdded()) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }

            isLoading.set(true);

            boolean isGlobalAdmin = sessionManager != null && sessionManager.isAnyAdmin();
            final int finalBranchId;
            if (isGlobalAdmin) {
                finalBranchId = (currentBranchId > 0) ? currentBranchId : 0;
            } else {
                int fromSession = sessionManager != null ? sessionManager.getBranchId() : 0;
                int fromManager = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                finalBranchId = fromSession > 0 ? fromSession : (fromManager > 0 ? fromManager : 1);
            }

            cancelFuture(dataFuture);

            dataFuture = sharedExecutor.submit(() -> {
                try {
                    MiraApiClient.ApiResponse response = apiClient.getDashboardFull(finalBranchId);

                    mainHandler.post(() -> {
                        isLoading.set(false);
                        lastDataFetchTime = System.currentTimeMillis();
                        if (!isAdded()) {
                            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                            return;
                        }

                        if (response != null && response.isSuccess && response.data != null) {
                            saveOfflineData(response.data);
                            processData(response.data);
                            computeAndShowAlerts(response.data);
                            isDataLoaded = true;
                            updateLiveStatusText(true);
                            showShimmer(false);
                        } else {
                            updateLiveStatusText(false);
                            loadOfflineData();
                            showShimmer(false);
                        }
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    logNonFatal("fetchData.thread", e);
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        loadOfflineData();
                        showShimmer(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                }
            });
        } catch (Exception e) {
            logNonFatal("fetchData", e);
            isLoading.set(false);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        }
    }

    private void processData(JSONObject data) {
        try {
            JSONObject inv = data.optJSONObject("inventory");
            if (inv != null) {
                int itemCount = inv.optInt("total_items", 0);
                double weight = inv.optDouble("total_weight", 0.0);
                double value = inv.optDouble("total_value", 0.0);

                setText(tvTotalItems, String.valueOf(itemCount));
                setText(tvTotalWeight, formatWeight(weight));

                currentItemCount = itemCount;
                currentTotalWeight = weight;
                currentInventoryValue = value;
                updateHeroBalance();
            }

            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                lastSalesData = sales;
                setText(tvSoldToday, String.valueOf(sales.optInt("count_today", 0)));
                applySalesData(sales);
            }
        } catch (Exception e) {
            logNonFatal("processData", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // ✅ Recent Activity (NEW)
    // ═══════════════════════════════════════════════════════

    /**
 * ✅ جلب آخر 10 عمليات — مع Cache
 * 
 * 1. عرض البيانات من الكاش فوراً (لا تأخير)
 * 2. جلب من السيرفر في الخلفية
 * 3. تحديث الواجهة + الكاش
 */
private void fetchRecentActivity() {
    if (apiClient == null || !isAdded()) return;

    // ═══════════════════════════════════════════════════════
    // ✅ 1. عرض البيانات من الكاش فوراً (بدون تأخير)
    // ═══════════════════════════════════════════════════════
    if (activityCache != null) {
        JSONArray cached = activityCache.load();
        if (cached.length() > 0) {
            updateRecentActivityUI(cached);
            Log.d(TAG, "✅ Displayed " + cached.length() + " activities from cache");
        }
    }

    // ═══════════════════════════════════════════════════════
    // ✅ 2. إذا الكاش صالح، لا نُجدد — لكن نُجدد في الخلفية
    // ═══════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════
    // ✅ 3. جلب من السيرفر في الخلفية
    // ═══════════════════════════════════════════════════════
    sharedExecutor.execute(() -> {
        try {
            int branchId = branchesManager != null
                    ? branchesManager.getActiveBranchId() : 0;

            MiraApiClient.ApiResponse response =
                    apiClient.getUnifiedActivity(branchId, 10);

            mainHandler.post(() -> {
                if (!isAdded() || response == null) return;

                if (response.isSuccess && response.data != null) {
                    try {
                        JSONArray activities = response.data.optJSONArray("activities");
                        if (activities != null && activities.length() > 0) {
                            // ✅ حفظ في الكاش
                            if (activityCache != null) {
                                activityCache.save(activities);
                            }
                            // ✅ تحديث الواجهة
                            updateRecentActivityUI(activities);
                        }
                    } catch (Exception e) {
                        logNonFatal("fetchRecentActivity.parse", e);
                    }
                }
            });
        } catch (Exception e) {
            logNonFatal("fetchRecentActivity", e);
        }
    });
}

/**
 * ✅ تحديث واجهة آخر العمليات
 */
private void updateRecentActivityUI(JSONArray activities) {
    if (activities == null || recentActivityAdapter == null) return;
    try {
        List<JSONObject> list = new ArrayList<>();
        int max = Math.min(activities.length(), 10);
        for (int i = 0; i < max; i++) {
            list.add(activities.getJSONObject(i));
        }
        recentActivityAdapter.submitList(list);
    } catch (Exception e) {
        logNonFatal("updateRecentActivityUI", e);
    }
}

    /**
     * ✅ عند النقر على عملية → فتح الشاشة المناسبة
     */
    private void handleRecentActivityClick(JSONObject activity) {
        if (activity == null) return;
        try {
            String type = activity.optString("type", "");

            switch (type) {
                case "sale":
                    navigateToFragment(new MiraSalesHistoryFragment(), "SalesHistory");
                    break;
                case "inventory":
                    navigateToFragment(new MiraInventoryProFragment(), "Scan");
                    break;
                case "transfer":
                    navigateToFragment(new MiraTransferFragment(), "Transfers");
                    break;
                case "rfid":
                    navigateToFragment(new MiraRfidBindingsFragment(), "RfidBindings");
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logNonFatal("handleRecentActivityClick", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Broadcast Receiver Registration
    // ═══════════════════════════════════════════════════════
    private void registerDataRefreshReceiver() {
        try {
            if (getContext() == null || isReceiverRegistered) return;
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.example.uhf.REFRESH_DASHBOARD");
            filter.addAction("com.example.uhf.REFRESH_INVENTORY");
            filter.addAction("com.example.uhf.REFRESH_SALES");
            ContextCompat.registerReceiver(requireContext(), dataRefreshReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            isReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "registerDataRefreshReceiver: " + e.getMessage());
        }
    }

    private void unregisterDataRefreshReceiver() {
        try {
            if (getContext() != null && isReceiverRegistered) {
                requireContext().unregisterReceiver(dataRefreshReceiver);
                isReceiverRegistered = false;
            }
        } catch (Exception e) {
            Log.d(TAG, "unregisterDataRefreshReceiver: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Refresh
    // ═══════════════════════════════════════════════════════
    public void refreshInternalData() {
        try {
            if (!isAdded()) return;
            isDataLoaded = false;
            lastDataFetchTime = 0;
            isLoading.set(false);
            fetchData();
            fetchRecentActivity();

            long now = System.currentTimeMillis();
            if (permissionManager != null
                    && permissionManager.canAccessFragment("GoldRates")) {
                if (lastGoldFetchTime == 0
                        || (now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                    fetchLiveGoldPrices(false);
                } else {
                    displayGoldPricesFromCache();
                }
            }
        } catch (Exception e) {
            logNonFatal("refreshInternalData", e);
        }
    }

    private void refreshAllData() {
        try {
            if (tvLiveStatusText != null) {
                tvLiveStatusText.setText(isArabic ? "⏳ جاري التحديث..." : "⏳ Updating...");
                tvLiveStatusText.setTextColor(0xFFF59E0B);
            }

            if (swipeTimeoutRunnable != null) {
                mainHandler.removeCallbacks(swipeTimeoutRunnable);
            }
            swipeTimeoutRunnable = () -> {
                if (swipeRefresh != null && swipeRefresh.isRefreshing()) {
                    Log.w(TAG, "⏰ SwipeRefresh timeout — forcing stop");
                    swipeRefresh.setRefreshing(false);
                }
            };
            mainHandler.postDelayed(swipeTimeoutRunnable, SWIPE_REFRESH_TIMEOUT_MS);

            fetchData();
            fetchRecentActivity();

            long now = System.currentTimeMillis();
            if (permissionManager != null && permissionManager.canAccessFragment("GoldRates")) {
                if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                    fetchLiveGoldPrices(true);
                } else {
                    displayGoldPricesFromCache();
                    updateLiveStatusText(true);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }
            } else {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        } catch (Exception e) {
            logNonFatal("refreshAllData", e);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════
    private void showShimmer(boolean show) {
        try {
            if (shimmerLayout != null) {
                shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (swipeRefresh != null) {
                swipeRefresh.setVisibility(show ? View.GONE : View.VISIBLE);
            }
        } catch (Exception e) {
            logNonFatal("showShimmer", e);
        }
    }

    private void initBranchCache(int branchId) {
        try {
            if (getContext() != null) {
                offlineCache = requireContext()
                        .getSharedPreferences(OFFLINE_PREFS_BASE + branchId, Context.MODE_PRIVATE);
            }
        } catch (Exception e) {
            logNonFatal("initBranchCache", e);
        }
    }

    private void startAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable);
        mainHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void stopAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable);
    }

    private void navigateToFragment(Fragment fragment, String tag) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(fragment, tag);
            }
        } catch (Exception e) {
            logNonFatal("navigateToFragment", e);
        }
    }

    private String getFragmentTagForAction(String actionId) {
        if (actionId == null) return "";
        switch (actionId) {
            case "identity": return "IdentityStudio";
            case "radar": return "Radar";
            case "gate": return "SecureGate";
            case "reports": return "Reports";
            case "accounting": return "Accounting";
            case "transfers": return "Transfers";
            case "admin_hub": return "AdminHub";
            case "sales_history": return "SalesHistory";
            case "server_config": return "ServerConfig";
            case "gold_rates": return "GoldRates";
            case "users": return "UsersManagement";
            default: return actionId;
        }
    }

    private void handleQuickAction(String actionId) {
        try {
            if (actionId == null || actionId.isEmpty()) return;
            if (!(getActivity() instanceof UHFMainActivity)) return;

            UHFMainActivity activity = (UHFMainActivity) getActivity();
            String tag = getFragmentTagForAction(actionId);

            Fragment fragment = null;
            try {
                fragment = activity.createFragmentByTag(tag);
            } catch (Exception e) {
                Log.e(TAG, "Error creating fragment: " + e.getMessage(), e);
            }

            if (fragment == null) {
                Toast.makeText(getContext(),
                        isArabic ? "⚠️ تعذر فتح هذه الواجهة" : "⚠️ Cannot open this screen",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            activity.loadFragment(fragment, tag);
        } catch (Exception e) {
            logNonFatal("handleQuickAction", e);
            try {
                Toast.makeText(getContext(),
                        isArabic ? "⚠️ خطأ في فتح الواجهة" : "⚠️ Error opening screen",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        }
    }

    private String getSourceDisplayName(String source) {
        if (source == null) return isArabic ? "غير معروف" : "Unknown";
        switch (source.toLowerCase()) {
            case "metalprice": return "MetalPrice";
            case "goldapi": return "GoldAPI";
            case "metalsdev": return "Metals.Dev";
            case "fallback": return isArabic ? "احتياطي" : "Fallback";
            default: return source;
        }
    }

    private String formatCurrency(double amount) {
        try {
            if (amount <= 0) return "—";
            if (priceManager != null) return priceManager.formatPriceDefault(amount);
            return getCurrencyFormatter().format(amount);
        } catch (Exception e) {
            return "—";
        }
    }

    private String formatWeight(double weightInGrams) {
        if (weightInGrams <= 0) return "0.0 g";
        return weightInGrams >= 1000
                ? String.format(Locale.US, "%.2f kg", weightInGrams / 1000)
                : String.format(Locale.US, "%.1f g", weightInGrams);
    }

    private void setGoldPrice(TextView tv, String label, double price) {
        if (tv == null) return;
        tv.setText(price > 0
                ? String.format(Locale.US, "%s: $%,.2f/g", label, price)
                : label + ": —");
    }

    private void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    // ═══════════════════════════════════════════════════════
    // Lifecycle (Resume/Pause/Destroy)
    // ═══════════════════════════════════════════════════════
    public void onBranchChanged() {
        try {
            if (!isAdded()) return;
            int newBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (newBranchId != currentBranchId) {
                currentBranchId = newBranchId;
                initBranchCache(currentBranchId);
                updateHeader();
                syncOrgProfileFromServer(true);
                loadOfflineData();
                showShimmer(true);
                fetchData();
                fetchRecentActivity();
            }
        } catch (Exception e) {
            logNonFatal("onBranchChanged", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            registerDataRefreshReceiver();
            isDataLoaded = false;
            lastDataFetchTime = 0;

            int latestBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (latestBranchId != currentBranchId) {
                onBranchChanged();
            } else if (isViewInitialized) {
                setupQuickAccessGrid();
                updateHeader();
                syncOrgProfileFromServer(false);
                refreshInternalData();
            }
            startAutoRefresh();
        } catch (Exception e) {
            logNonFatal("onResume", e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            unregisterDataRefreshReceiver();
            stopAutoRefresh();
        } catch (Exception e) {
            logNonFatal("onPause", e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            unregisterDataRefreshReceiver();
            stopAutoRefresh();

            if (branchesManager != null && branchListener != null) {
                branchesManager.removeListener(branchListener);
                branchListener = null;
            }
            if (orgProfile != null && profileListener != null) {
                orgProfile.removeListener(profileListener);
            }

            cancelFuture(dataFuture);
            cancelFuture(goldPriceFuture);
            cancelFuture(pingFuture);

            if (swipeTimeoutRunnable != null) {
                mainHandler.removeCallbacks(swipeTimeoutRunnable);
            }
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }

            isViewInitialized = false;
        } catch (Exception e) {
            logNonFatal("onDestroyView", e);
        }
    }

    private void cancelFuture(Future<?> future) {
        try {
            if (future != null && !future.isDone()) future.cancel(true);
        } catch (Exception e) {
            logNonFatal("cancelFuture", e);
        }
    }

    private void logNonFatal(String location, Throwable throwable) {
        try {
            Log.e(TAG, "Error at " + location + ": " + throwable.getMessage(), throwable);
        } catch (Exception e) {
            Log.e(TAG, "logNonFatal error: " + e.getMessage());
        }
    }
    }
