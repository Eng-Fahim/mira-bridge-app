package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.ActivityDetailsBottomSheet;
import com.example.uhf.manager.DashboardRoleController;
import com.example.uhf.manager.DashboardUIBuilder;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.ApiProvider;
import com.example.uhf.navigation.NavigationManager;
import com.example.uhf.tools.RecentActivityCache;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.widget.CountdownTimerView;
import com.example.uhf.widget.ShiftSummaryCardView;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.color.MaterialColors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboardFragment";
    private static final String OFFLINE_PREFS_BASE = "dashboard_cache_u";
    private static final String PREFS_CACHE_OWNER = "mira_dashboard_cache_owner";
    private static final String KEY_CACHE_OWNER_USER_ID = "cache_owner_user_id";
    private static final String KEY_PREFIX_LAST_BRANCH = "last_branch_id_";

    private static final double OUNCE_TO_GRAM = 31.1035;
    private static final long GOLD_CACHE_VALIDITY_MS = 12 * 60 * 60 * 1000;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    private static final long SWIPE_REFRESH_TIMEOUT_MS = 15 * 1000;
    private static final long RESUME_REFETCH_THRESHOLD_MS = 2 * 60 * 1000;

    private RecentActivityCache activityCache;
    private JSONObject lastSalesData = null;
    private boolean isHeroHidden = true;
    private boolean isSalesHidden = true;
    private boolean isInventoryHidden = true;
    private boolean isScrapHidden = true;

    private HorizontalScrollView hsvCarousel, hsvQuickActions;
    private View dot1, dot2, dot3, dotsIndicator;
    private LineChart lineChartSales;
    private PieChart pieChartKarat;

    private double currentScrapWeight = 0;
    private double currentScrapValue = 0;
    private TextView tvScrapWeight, tvScrapValue;
    private ImageButton btnToggleScrapVisibility;

    private DashboardRoleController roleController;
    private DashboardUIBuilder uiBuilder; // ✅ إضافة الباني الجديد

    private View cardScrapAging;
    private TextView tvAgingStatusIcon, tvAgingStatusLabel, tvAgingWeight, tvAgingCount;
    private View llAgingAlert;
    private TextView tvAgingAlertText;
    private View btnOpenAgingReport;

    private ImageButton btnToggleHeroVisibility, btnToggleSalesVisibility, btnToggleInventoryVisibility;

    private LinearLayout llInventoryByKarat;
    private TextView tvKaratEmptyState;
    private final List<KaratInventory> currentInventoryByKarat = new ArrayList<>();

    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24, tvKarat21, tvKarat18;
    private TextView tvOuncePrice, tvPriceSource, tvLastUpdate;
    private TextView tvGoldTrend, tvGoldChange;
    private View btnRefreshGold;
    private SwipeRefreshLayout swipeRefresh;
    private View shimmerLayout, loadingOverlay;
    private ProgressBar pbGoldLoading;
    private CardView cardGoldPrices;

    private View heroBalanceCard;
    private TextView tvHeroBalance, tvHeroTrend, tvHeroSubtitle;
    private TextView tvHeroValueYER, tvHeroValueSAR, tvHeroValueUSD;
    private View alertsStrip;
    private TextView tvAlertIcon, tvAlertMessage, tvAlertAction;
    private View liveStatusDot;
    private TextView tvLiveStatusText;
    private TextView tvHeroItemCount;

    private LinearLayout llRecentActivity, llPosRecentActivity;
    private TextView tvViewAllActivity, tvPosViewAllActivity;

    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;
    private MiraBranchesManager.OnBranchChangedListener branchListener;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;

    private ExecutorService localExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> dataFuture, goldPriceFuture;
    private Runnable swipeTimeoutRunnable;

    private boolean isArabic;
    private boolean isReceiverRegistered = false;

    private android.content.SharedPreferences offlineCache, goldCachePrefs;
    private int currentBranchId = -1;
    private boolean isViewInitialized = false;
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isGoldRefreshing = new AtomicBoolean(false);
    private boolean isDataLoaded = false;
    private long lastDataFetchTime = 0, lastGoldFetchTime = 0;

    private double currentInventoryValue = 0;
    private int currentItemCount = 0;
    private double currentTotalWeight = 0;

    // ✅ متغيرات واجهة POS
    private View layoutPosGoldDashboard;
    private TextView tvPosGreeting, tvPosUserName;
    private TextView tvPosTotalWeight, tvPosTotalValue, tvPosCashBalance;
    private HorizontalScrollView hsvPosKarat;
    private ImageView ivKaratLeftArrow, ivKaratRightArrow;
    private LinearLayout llPosKaratContainer;
    private LineChart lineChartPosPriceMovement;
    private ImageButton btnTogglePosGoldVisibility;
    private boolean isPosDashboardHidden = true;
    private ShiftSummaryCardView shiftSummaryCard;
    private boolean isShiftCardAnimated = false;

    private static class KaratInventory {
        final int karat; final int itemCount; final double weight;
        KaratInventory(int karat, int itemCount, double weight) { this.karat = karat; this.itemCount = itemCount; this.weight = weight; }
    }

    private static class GoldPriceData {
        final double price24, price21, price18, ouncePrice; final String source;
        GoldPriceData(double price24, double price21, double price18, double ouncePrice, String source) {
            this.price24 = price24; this.price21 = price21; this.price18 = price18; this.ouncePrice = ouncePrice; this.source = source;
        }
    }

    private SimpleDateFormat getTimeFormatter() { return new SimpleDateFormat("HH:mm:ss", Locale.US); }
    private NumberFormat getCurrencyFormatter() { return NumberFormat.getCurrencyInstance(Locale.US); }

    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded() && isViewInitialized) {
                long now = System.currentTimeMillis();
                if (now - lastDataFetchTime >= REFRESH_INTERVAL_MS) {
                    fetchData();
                    if (currentBranchId > 0) {
                        fetchScrapSummary(currentBranchId);
                        fetchScrapAging(currentBranchId);
                    }
                }
                mainHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };

    private final BroadcastReceiver dataRefreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !isAdded() || !isViewInitialized) return;
            String action = intent.getAction();
            if ("com.example.uhf.REFRESH_DASHBOARD".equals(action) ||
                "com.example.uhf.REFRESH_INVENTORY".equals(action) ||
                "com.example.uhf.REFRESH_SALES".equals(action)) {
                mainHandler.post(() -> {
                    if (isAdded() && isViewInitialized) {
                        isDataLoaded = false; lastDataFetchTime = 0; isLoading.set(false);
                        if (isResumed()) {
                            fetchData();
                            if (currentBranchId > 0) { fetchScrapSummary(currentBranchId); fetchScrapAging(currentBranchId); }
                        }
                    }
                });
            }
        }
    };

    private boolean isSessionValid() { return sessionManager != null && sessionManager.getUserId() > 0; }

    private boolean ensureExecutor() {
        if (localExecutor == null || localExecutor.isShutdown()) {
            if (!isAdded()) return false;
            localExecutor = Executors.newFixedThreadPool(4);
        }
        return true;
    }

    private void shutdownExecutor() {
        if (localExecutor != null) { try { localExecutor.shutdownNow(); } catch (Exception ignored) {} localExecutor = null; }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        try { 
            return inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
        } catch (Exception e) { 
            Log.e(TAG, "Dashboard inflate FAILED", e);
            android.widget.TextView errorView = new android.widget.TextView(container != null ? container.getContext() : requireContext());
            errorView.setText("Error loading UI: " + e.getMessage());
            return errorView;
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
            currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            initBranchCache(currentBranchId);
            showShimmer(false);
            isViewInitialized = true;
            loadOfflineData();
            startAutoRefresh();
            lastDataFetchTime = 0;

            fetchData();
            fetchDashboardCharts();
            fetchRecentActivity();
            if (currentBranchId > 0) { fetchScrapSummary(currentBranchId); fetchScrapAging(currentBranchId); }

        } catch (Throwable e) { Log.e(TAG, "onViewCreated", e); }
    }

    private void initViews(View v) {
        try {
            // --- ربط العناصر ---
            hsvCarousel = v.findViewById(R.id.horizontalScrollCarousel);
            hsvQuickActions = v.findViewById(R.id.hsvQuickActions);
            dot1 = v.findViewById(R.id.dot1);
            dot2 = v.findViewById(R.id.dot2);
            dot3 = v.findViewById(R.id.dot3);
            dotsIndicator = v.findViewById(R.id.dotsIndicator);

            heroBalanceCard = v.findViewById(R.id.heroBalanceCard);
            cardGoldPrices = v.findViewById(R.id.cardGoldPrices);

            tvHeroBalance   = v.findViewById(R.id.tvHeroBalance);
            tvHeroTrend     = v.findViewById(R.id.tvHeroTrend);
            tvHeroSubtitle  = v.findViewById(R.id.tvHeroSubtitle);
            tvHeroValueYER  = v.findViewById(R.id.tvHeroValueYER);
            tvHeroValueSAR  = v.findViewById(R.id.tvHeroValueSAR);
            tvHeroValueUSD  = v.findViewById(R.id.tvHeroValueUSD);
            tvHeroItemCount = v.findViewById(R.id.tvHeroItemCount);
            btnToggleHeroVisibility = v.findViewById(R.id.btnToggleHeroVisibility);

            tvSalesToday = v.findViewById(R.id.tvSalesToday);
            tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
            tvSalesYear  = v.findViewById(R.id.tvSalesYear);
            btnToggleSalesVisibility = v.findViewById(R.id.btnToggleSalesVisibility);

            tvKarat24 = v.findViewById(R.id.tvGoldPrice24);
            tvKarat21 = v.findViewById(R.id.tvGoldPrice21);
            tvKarat18 = v.findViewById(R.id.tvGoldPrice18);
            tvOuncePrice  = v.findViewById(R.id.tvOuncePrice);
            tvPriceSource = v.findViewById(R.id.tvPriceSource);
            tvLastUpdate  = v.findViewById(R.id.tvLastUpdate);
            tvGoldTrend   = v.findViewById(R.id.tvGoldTrend);
            tvGoldChange  = v.findViewById(R.id.tvGoldChange);
            btnRefreshGold = v.findViewById(R.id.btnRefreshGold);
            pbGoldLoading  = v.findViewById(R.id.pbGoldLoading);

            llInventoryByKarat = v.findViewById(R.id.llInventoryByKarat);
            tvKaratEmptyState  = v.findViewById(R.id.tvKaratEmptyState);
            shimmerLayout = v.findViewById(R.id.shimmerLayout);
            swipeRefresh  = v.findViewById(R.id.swipeRefresh);
            loadingOverlay = v.findViewById(R.id.loadingOverlay);
            alertsStrip   = v.findViewById(R.id.alertsStrip);
            tvAlertIcon   = v.findViewById(R.id.tvAlertIcon);
            tvAlertMessage = v.findViewById(R.id.tvAlertMessage);
            tvAlertAction = v.findViewById(R.id.tvAlertAction);
            btnToggleInventoryVisibility = v.findViewById(R.id.btnToggleInventoryVisibility);

            tvScrapWeight = v.findViewById(R.id.tvScrapWeight);
            tvScrapValue = v.findViewById(R.id.tvScrapValue);
            btnToggleScrapVisibility = v.findViewById(R.id.btnToggleScrapVisibility);
            View btnOpenScrapBuy = v.findViewById(R.id.btnOpenScrapBuy);
            View btnOpenScrapHistory = v.findViewById(R.id.btnOpenScrapHistory);
            View btnOpenScrapBatches = v.findViewById(R.id.btnOpenScrapBatches);

            cardScrapAging = v.findViewById(R.id.cardScrapAging);
            tvAgingStatusIcon = v.findViewById(R.id.tvAgingStatusIcon);
            tvAgingStatusLabel = v.findViewById(R.id.tvAgingStatusLabel);
            tvAgingWeight = v.findViewById(R.id.tvAgingWeight);
            tvAgingCount = v.findViewById(R.id.tvAgingCount);
            llAgingAlert = v.findViewById(R.id.llAgingAlert);
            tvAgingAlertText = v.findViewById(R.id.tvAgingAlertText);
            btnOpenAgingReport = v.findViewById(R.id.btnOpenAgingReport);

            llRecentActivity = v.findViewById(R.id.llRecentActivity);
            tvViewAllActivity = v.findViewById(R.id.tvViewAllActivity);
            lineChartSales = v.findViewById(R.id.lineChartSales);
            pieChartKarat = v.findViewById(R.id.pieChartKarat);

            shiftSummaryCard = v.findViewById(R.id.cardShiftSummary);

            layoutPosGoldDashboard = v.findViewById(R.id.layoutPosGoldDashboard);
            tvPosTotalWeight = v.findViewById(R.id.tvPosTotalWeight);
            tvPosTotalValue = v.findViewById(R.id.tvPosTotalValue);
            tvPosCashBalance = v.findViewById(R.id.tvPosCashBalance);
            hsvPosKarat = v.findViewById(R.id.hsvPosKarat);
            ivKaratLeftArrow = v.findViewById(R.id.ivKaratLeftArrow);
            ivKaratRightArrow = v.findViewById(R.id.ivKaratRightArrow);
            llPosKaratContainer = v.findViewById(R.id.llPosKaratContainer);
            lineChartPosPriceMovement = v.findViewById(R.id.lineChartPosPriceMovement);
            btnTogglePosGoldVisibility = v.findViewById(R.id.btnTogglePosGoldVisibility);
            llPosRecentActivity = v.findViewById(R.id.llPosRecentActivity);
            tvPosViewAllActivity = v.findViewById(R.id.tvPosViewAllActivity);

            // --- المستمعين ---
            if (btnTogglePosGoldVisibility != null) {
                btnTogglePosGoldVisibility.setOnClickListener(v2 -> {
                    isPosDashboardHidden = !isPosDashboardHidden;
                    btnTogglePosGoldVisibility.setImageResource(isPosDashboardHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
                    updatePosDashboardUI();
                });
            }
            if (tvPosViewAllActivity != null) tvPosViewAllActivity.setOnClickListener(v2 -> navigateToFragment(new MiraAllActivitiesFragment(), "AllActivities"));
            if (tvViewAllActivity != null) tvViewAllActivity.setOnClickListener(v2 -> navigateToFragment(new MiraAllActivitiesFragment(), "AllActivities"));
            if (btnToggleScrapVisibility != null) btnToggleScrapVisibility.setOnClickListener(v2 -> toggleScrapVisibility());
            if (btnOpenScrapBuy != null) btnOpenScrapBuy.setOnClickListener(v2 -> navigateToFragment(new MiraScrapGoldBuyFragment(), NavigationManager.TAG_SCRAP_GOLD_BUY));
            if (btnOpenScrapHistory != null) btnOpenScrapHistory.setOnClickListener(v2 -> navigateToFragment(new MiraScrapHistoryFragment(), NavigationManager.TAG_SCRAP_HISTORY));
            if (btnOpenScrapBatches != null) btnOpenScrapBatches.setOnClickListener(v2 -> navigateToFragment(new MiraScrapBatchesFragment(), NavigationManager.TAG_SCRAP_BATCHES));
            if (btnOpenAgingReport != null) btnOpenAgingReport.setOnClickListener(v2 -> navigateToFragment(new MiraScrapAgingFragment(), NavigationManager.TAG_SCRAP_AGING));
            if (btnToggleHeroVisibility != null) btnToggleHeroVisibility.setOnClickListener(v2 -> toggleHeroVisibility());
            if (btnToggleSalesVisibility != null) btnToggleSalesVisibility.setOnClickListener(v2 -> toggleSalesVisibility());
            if (btnToggleInventoryVisibility != null) btnToggleInventoryVisibility.setOnClickListener(v2 -> toggleInventoryVisibility());
            if (tvAlertAction != null) tvAlertAction.setOnClickListener(v2 -> navigateToFragment(new MiraUHFReadTagFragment(), "Scan"));
            
            if (btnRefreshGold != null) {
                if (!canManuallyRefreshGold()) btnRefreshGold.setVisibility(View.GONE);
                else btnRefreshGold.setOnClickListener(v2 -> { if (!isGoldRefreshing.get()) fetchLiveGoldPrices(true); });
            }
            if (swipeRefresh != null) {
                swipeRefresh.setOnRefreshListener(this::refreshAllData);
                swipeRefresh.setColorSchemeResources(R.color.colorPrimary, R.color.gray1);
            }

            setupKaratScrollArrows(v);
            if (hsvCarousel != null) {
                final LinearLayout cardsContainer = v.findViewById(R.id.cardsContainer);
                hsvCarousel.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    if (!isAdded() || getContext() == null || cardsContainer == null) return;
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenCenter = screenWidth / 2;
                    int activeCardId = View.NO_ID;
                    float minDistance = Float.MAX_VALUE;
                    for (int i = 0; i < cardsContainer.getChildCount(); i++) {
                        View card = cardsContainer.getChildAt(i);
                        if (card.getVisibility() != View.VISIBLE) continue;
                        int cardCenter = card.getLeft() + (card.getWidth() / 2) - scrollX;
                        int distance = cardCenter - screenCenter;
                        if (Math.abs(distance) < minDistance) { minDistance = Math.abs(distance); activeCardId = card.getId(); }
                        float percent = Math.min(1f, Math.abs((float) distance / (screenWidth / 2.0f)));
                        float rotation = (distance > 0 ? 1 : -1) * (percent * 12f);
                        card.setRotationY(rotation); card.setCameraDistance(8000f);
                        float scale = 1f - (percent * (1f - 0.85f));
                        card.setScaleX(scale); card.setScaleY(scale); card.setAlpha(1f);
                    }
                    if (dot1 != null && dot1.getVisibility() == View.VISIBLE) dot1.setBackgroundResource(activeCardId == R.id.heroBalanceCard ? R.drawable.dot_active : R.drawable.dot_inactive);
                    if (dot2 != null && dot2.getVisibility() == View.VISIBLE) dot2.setBackgroundResource(activeCardId == R.id.cardGoldPrices ? R.drawable.dot_active : R.drawable.dot_inactive);
                    if (dot3 != null && dot3.getVisibility() == View.VISIBLE) dot3.setBackgroundResource(activeCardId == R.id.salesSummaryCard ? R.drawable.dot_active : R.drawable.dot_inactive);
                });
            }

            isHeroHidden = true; isSalesHidden = true; isInventoryHidden = true; isScrapHidden = true;
            if (btnToggleHeroVisibility != null) btnToggleHeroVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleSalesVisibility != null) btnToggleSalesVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleInventoryVisibility != null) btnToggleInventoryVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleScrapVisibility != null) btnToggleScrapVisibility.setImageResource(R.drawable.ic_eye_off);

            applyCarouselLayout();
            applyChartTheme();
            updateHeroBalance();
            applySalesVisibility();
            updateScrapUI();
            
            // ✅ استخدام الباني الجديد بدلاً من الكود القديم
            if (uiBuilder != null) {
                uiBuilder.setupQuickActionButtons(v, (target, tag) -> navigateToFragment(target, tag));
                uiBuilder.applyLayoutVisibility(v, roleController);
            }

        } catch (Exception e) { Log.e(TAG, "initViews", e); }
    }

    private void setupKaratScrollArrows(View root) {
        if (hsvPosKarat == null || ivKaratLeftArrow == null || ivKaratRightArrow == null) return;
        hsvPosKarat.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (hsvPosKarat.canScrollHorizontally(-1)) ivKaratLeftArrow.setVisibility(View.VISIBLE);
            else ivKaratLeftArrow.setVisibility(View.GONE);
            if (hsvPosKarat.canScrollHorizontally(1)) ivKaratRightArrow.setVisibility(View.VISIBLE);
            else ivKaratRightArrow.setVisibility(View.GONE);
        });
        ivKaratLeftArrow.setOnClickListener(v -> hsvPosKarat.smoothScrollBy(-300, 0));
        ivKaratRightArrow.setOnClickListener(v -> hsvPosKarat.smoothScrollBy(300, 0));
    }

    private void updatePosDashboardUI() {
        if (getView() == null) return;
        
        if (isPosDashboardHidden) {
            tvPosTotalWeight.setText("•••••");
            tvPosTotalValue.setText("•••••••");
            tvPosCashBalance.setText("•••••••");
            if (llPosKaratContainer != null) {
                for (int i = 0; i < llPosKaratContainer.getChildCount(); i++) {
                    View card = llPosKaratContainer.getChildAt(i);
                    TextView tvKaratWeight = card.findViewById(R.id.tvChipKaratWeight);
                    TextView tvKaratValue = card.findViewById(R.id.tvChipKaratValue);
                    if (tvKaratWeight != null) tvKaratWeight.setText("•••");
                    if (tvKaratValue != null) tvKaratValue.setText("•••••");
                }
            }
            if (llPosRecentActivity != null) {
                for (int i = 0; i < llPosRecentActivity.getChildCount(); i++) {
                    View row = llPosRecentActivity.getChildAt(i);
                    TextView tvAmount = row.findViewById(R.id.tvPosActAmount);
                    if (tvAmount != null) tvAmount.setText("•••••");
                }
            }
        } else {
            tvPosTotalWeight.setText(String.format(Locale.US, "%,.2f", currentTotalWeight));
            tvPosTotalValue.setText(String.format(Locale.US, "$%,.2f", currentInventoryValue));
            double cash = 0.0;
            if (lastSalesData != null) cash = lastSalesData.optDouble("today", 0.0);
            tvPosCashBalance.setText(String.format(Locale.US, "%,.2f YER", cash));
            if (llPosKaratContainer != null) {
                llPosKaratContainer.removeAllViews();
                for (KaratInventory inv : currentInventoryByKarat) {
                    View card = getLayoutInflater().inflate(R.layout.item_pos_karat_chip, llPosKaratContainer, false);
                    TextView tvKaratTitle = card.findViewById(R.id.tvChipKaratTitle);
                    TextView tvKaratWeight = card.findViewById(R.id.tvChipKaratWeight);
                    TextView tvKaratValue = card.findViewById(R.id.tvChipKaratValue);
                    if (tvKaratTitle != null) tvKaratTitle.setText(inv.karat + "K");
                    if (tvKaratWeight != null) tvKaratWeight.setText(String.format(Locale.US, "%,.2f", inv.weight));
                    if (tvKaratValue != null) {
                        double price = priceManager != null ? priceManager.getGoldPricePerGram(inv.karat) : 0;
                        tvKaratValue.setText(String.format(Locale.US, "%,.0f YER", inv.weight * price * 530.0));
                    }
                    llPosKaratContainer.addView(card);
                }
                setupKaratScrollArrows(getView());
            }
            fetchRecentActivity();
        }
    }

    // ✅ دالة الرسم الآن تستدعي DashboardUIBuilder
    private void renderCharts(JSONObject data) {
        applyChartTheme();
        if (uiBuilder != null) {
            uiBuilder.renderCharts(lineChartSales, lineChartPosPriceMovement, pieChartKarat, data);
        }
    }

    private void fetchDashboardCharts() {
        if (apiClient == null || !isAdded() || !isSessionValid() || !ensureExecutor()) return;
        
        localExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardCharts(currentBranchId);
                if (response != null && response.isSuccess && response.data != null) {
                    Log.d(TAG, "📊 Charts Raw Response: " + response.data.toString());
                    JSONObject rootData = response.data.has("data") ? response.data.optJSONObject("data") : response.data;
                    if (rootData != null) {
                        mainHandler.post(() -> { if (isAdded()) renderCharts(rootData); });
                    }
                } else {
                    Log.e(TAG, "❌ Charts API Failed: " + (response != null ? response.error : "null"));
                }
            } catch (Exception e) { 
                Log.e(TAG, "❌ fetchDashboardCharts Error", e); 
            }
        });
    }

    private void fetchData() {
        try {
            boolean isPos = permissionManager != null && permissionManager.isPosOperator();
            if (!isPos && roleController != null && !roleController.canFetchFullDashboard()) {
                isLoading.set(false);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }
            
            if (apiClient == null || !isAdded() || !isSessionValid() || !ensureExecutor()) { if (swipeRefresh != null) swipeRefresh.setRefreshing(false); return; }
            if (!isLoading.compareAndSet(false, true)) return;

            boolean isGlobalAdmin = sessionManager != null && sessionManager.isAnyAdmin();
            final int finalBranchId;
            if (isGlobalAdmin) finalBranchId = (currentBranchId > 0) ? currentBranchId : 0;
            else {
                int fromSession = sessionManager != null ? sessionManager.getBranchId() : 0;
                int fromManager = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                finalBranchId = fromSession > 0 ? fromSession : (fromManager > 0 ? fromManager : 1);
            }
            if (finalBranchId <= 0 && !isGlobalAdmin) { isLoading.set(false); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); return; }

            cancelFuture(dataFuture);
            dataFuture = localExecutor.submit(() -> {
                try {
                    MiraApiClient.ApiResponse response = apiClient.getDashboardFull(finalBranchId);
                    mainHandler.post(() -> {
                        isLoading.set(false); lastDataFetchTime = System.currentTimeMillis();
                        if (!isAdded()) { if (swipeRefresh != null) swipeRefresh.setRefreshing(false); return; }
                        if (response != null && response.isSuccess && response.data != null) {
                            JSONObject rootData = response.data.has("data") ? response.data.optJSONObject("data") : response.data;
                            if (rootData != null) {
                                saveOfflineData(rootData); 
                                processData(rootData); 
                                computeAndShowAlerts(rootData);
                            }
                            isDataLoaded = true; updateLiveStatusText(true); showShimmer(false);
                        } else {
                            updateLiveStatusText(false); loadOfflineData(); showShimmer(false);
                        }
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "fetchData.thread", e);
                    mainHandler.post(() -> { isLoading.set(false); loadOfflineData(); showShimmer(false); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); });
                }
            });
            fetchDashboardCharts();
        } catch (Exception e) { Log.e(TAG, "fetchData", e); isLoading.set(false); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }
    }
    
    private void processData(JSONObject data) {
    try {
        // ✅ معالجة خاصة لأمن البوابات (يخرج من الدالة مباشرة)
        if (permissionManager != null && permissionManager.isGateUser()) {
            JSONObject gates = data.optJSONObject("gates");
            if (gates != null && alertsStrip != null) {
                int scans = gates.optInt("scans_today", 0);
                int allowed = gates.optInt("allowed_today", 0);
                int denied = gates.optInt("denied_today", 0);
                alertsStrip.setVisibility(View.VISIBLE);
                if (tvAlertIcon != null) tvAlertIcon.setText("🛡️");
                if (tvAlertMessage != null) {
                    tvAlertMessage.setText(String.format(Locale.US,
                        "المسح: %d | مسموح: %d | مرفوض: %d", scans, allowed, denied));
                }
                if (tvAlertAction != null) tvAlertAction.setVisibility(View.GONE);
            }
            return;
        }

        // ═══════════════════════════════════════════════════════
        //  باقي المنطق (مخزون + مبيعات + POS)
        // ═══════════════════════════════════════════════════════
        JSONObject inv = data.optJSONObject("inventory");
        if (inv == null) inv = data.optJSONObject("inventory_data");

        if (inv != null) {
            currentItemCount = inv.optInt("total_items", 0);
            currentTotalWeight = inv.optDouble("total_weight", inv.optDouble("weight", 0.0));
            currentInventoryValue = inv.optDouble("total_value", 0.0);

            JSONArray byKarat = inv.optJSONArray("by_karat");
            if (byKarat == null) byKarat = inv.optJSONArray("karats");

            List<KaratInventory> karatItems = new ArrayList<>();
            double calculatedWeight = 0;
            double calculatedValue = 0;

            if (byKarat != null) {
                for (int i = 0; i < byKarat.length(); i++) {
                    JSONObject k = byKarat.optJSONObject(i);
                    if (k != null) {
                        int karat = k.optInt("karat", 0);
                        double weight = k.optDouble("weight", k.optDouble("total_weight", 0.0));
                        if (karat > 0) {
                            karatItems.add(new KaratInventory(karat, k.optInt("count", 0), weight));
                            calculatedWeight += weight;
                            if (priceManager != null) {
                                double price = priceManager.getGoldPricePerGram(karat);
                                calculatedValue += (weight * price);
                            }
                        }
                    }
                }
            }

            if (currentTotalWeight == 0 && calculatedWeight > 0) currentTotalWeight = calculatedWeight;
            if (currentInventoryValue == 0 && calculatedValue > 0) currentInventoryValue = calculatedValue;

            currentInventoryByKarat.clear();
            currentInventoryByKarat.addAll(karatItems);
            renderInventoryByKarat(currentInventoryByKarat);
        }

        JSONObject sales = data.optJSONObject("sales");
        if (sales != null) {
            lastSalesData = sales;
            applySalesData(sales);
        }

        JSONArray sales7Days = data.optJSONArray("sales_last_7_days");
        if (sales7Days != null && lineChartSales != null) {
            List<Entry> entries = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            for (int i = 0; i < sales7Days.length(); i++) {
                JSONObject day = sales7Days.optJSONObject(i);
                if (day != null) {
                    entries.add(new Entry(i, (float) day.optDouble("amount", 0)));
                    labels.add(day.optString("date", ""));
                }
            }
            if (!entries.isEmpty()) {
                LineDataSet dataSet = new LineDataSet(entries, "المبيعات");
                dataSet.setColor(Color.parseColor("#D4AF37"));
                dataSet.setLineWidth(2.5f);
                dataSet.setCircleRadius(4f);
                dataSet.setDrawValues(false);
                dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
                lineChartSales.setData(new LineData(dataSet));
                XAxis xAxis = lineChartSales.getXAxis();
                xAxis.setValueFormatter(new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(labels));
                xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
                xAxis.setGranularity(1f);
                xAxis.setDrawGridLines(false);
                lineChartSales.getAxisRight().setEnabled(false);
                lineChartSales.getAxisLeft().setDrawGridLines(true);
                lineChartSales.getDescription().setEnabled(false);
                lineChartSales.getLegend().setEnabled(false);
                lineChartSales.invalidate();
            }
        }

        if (permissionManager != null && permissionManager.isPosOperator() && layoutPosGoldDashboard != null) {
            if (isPosDashboardHidden) {
                tvPosTotalWeight.setText("•••••");
                tvPosTotalValue.setText("•••••••");
                tvPosCashBalance.setText("•••••••");
            } else {
                tvPosTotalWeight.setText(String.format(Locale.US, "%,.2f", currentTotalWeight));
                tvPosTotalValue.setText(String.format(Locale.US, "$%,.2f", currentInventoryValue));
                double cash = 0.0;
                if (data.optJSONObject("shift_summary") != null) cash = data.optJSONObject("shift_summary").optDouble("total_cash", 0.0);
                else if (sales != null) cash = sales.optDouble("today", 0.0);
                tvPosCashBalance.setText(String.format(Locale.US, "%,.2f YER", cash));
            }
            renderPosKaratCards(inv);
        }

    } catch (Exception e) {
        Log.e(TAG, "processData error", e);
    }
}

    private void renderPosKaratCards(JSONObject inv) {
        if (llPosKaratContainer == null) return;
        llPosKaratContainer.removeAllViews();
        try {
            JSONArray byKarat = (inv != null) ? inv.optJSONArray("by_karat") : null;
            if (byKarat == null || byKarat.length() == 0) {
                TextView tvEmpty = new TextView(getContext());
                tvEmpty.setText("لا توجد تفاصيل للعيارات");
                tvEmpty.setTextColor(ContextCompat.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
                tvEmpty.setTextSize(12); tvEmpty.setPadding(16, 16, 16, 16);
                llPosKaratContainer.addView(tvEmpty);
                return;
            }
            for (int i = 0; i < byKarat.length(); i++) {
                JSONObject k = byKarat.getJSONObject(i);
                int karat = k.optInt("karat", 0);
                double weight = k.optDouble("weight", k.optDouble("total_weight", 0.0));
                if (karat == 0 || weight == 0) continue; 
                
                View card = getLayoutInflater().inflate(R.layout.item_pos_karat_chip, llPosKaratContainer, false);
                TextView tvKaratTitle = card.findViewById(R.id.tvChipKaratTitle);
                TextView tvKaratWeight = card.findViewById(R.id.tvChipKaratWeight);
                TextView tvKaratValue = card.findViewById(R.id.tvChipKaratValue);
                
                if (tvKaratTitle != null) tvKaratTitle.setText(karat + "K");
                if (isPosDashboardHidden) {
                    if (tvKaratWeight != null) tvKaratWeight.setText("•••"); 
                    if (tvKaratValue != null) tvKaratValue.setText("•••••");
                } else {
                    if (tvKaratWeight != null) tvKaratWeight.setText(String.format(Locale.US, "%,.2f", weight));
                    if (tvKaratValue != null) {
                        double price = priceManager != null ? priceManager.getGoldPricePerGram(karat) : 0;
                        tvKaratValue.setText(String.format(Locale.US, "%,.0f YER", weight * price * 530.0));
                    }
                }
                llPosKaratContainer.addView(card);
            }
            llPosKaratContainer.post(() -> { if (getView() != null) setupKaratScrollArrows(getView()); });
        } catch (Exception e) { Log.e(TAG, "renderPosKaratCards", e); }
    }

    private void applyChartTheme() {
        if (!isAdded() || getContext() == null || getView() == null) return;
        try {
            int onSurface = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurface, isNightMode() ? 0xFFFFFFFF : 0xFF212121);
            int onSurfaceVariant = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant, isNightMode() ? 0xFFBBBBBB : 0xFF666666);
            int chartGrid = isNightMode() ? 0x33FFFFFF : 0x33000000;

            if (lineChartSales != null) {
                XAxis x = lineChartSales.getXAxis();
                x.setTextColor(onSurfaceVariant); x.setAxisLineColor(chartGrid);
                lineChartSales.getAxisLeft().setTextColor(onSurfaceVariant); lineChartSales.getAxisLeft().setGridColor(chartGrid);
                lineChartSales.setNoDataTextColor(onSurfaceVariant);
            }
            if (pieChartKarat != null) {
                pieChartKarat.setEntryLabelColor(onSurface);
                pieChartKarat.setHoleColor(isNightMode() ? 0xFF1E1E1E : 0xFFFFFFFF);
                pieChartKarat.setNoDataTextColor(onSurfaceVariant);
            }
            if (lineChartPosPriceMovement != null) {
                XAxis x = lineChartPosPriceMovement.getXAxis();
                x.setTextColor(onSurfaceVariant); x.setAxisLineColor(chartGrid);
                lineChartPosPriceMovement.getAxisRight().setTextColor(onSurfaceVariant);
                lineChartPosPriceMovement.getAxisRight().setGridColor(chartGrid);
                lineChartPosPriceMovement.getLegend().setTextColor(onSurface);
                lineChartPosPriceMovement.setNoDataTextColor(onSurfaceVariant);
            }
        } catch (Exception e) { Log.e(TAG, "applyChartTheme", e); }
    }

    private void applyCarouselLayout() {
        if (!isAdded() || hsvCarousel == null) return;
        View cardsContainer = hsvCarousel.findViewById(R.id.cardsContainer);
        if (!(cardsContainer instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) cardsContainer;
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cardWidthPx = (int) Math.min(Math.max(screenWidth * 0.82f, 280 * density), 360 * density);
        int sidePadding = Math.max((screenWidth - cardWidthPx) / 2, (int) (24 * density));
        hsvCarousel.setClipToPadding(false); hsvCarousel.setPadding(sidePadding, 0, sidePadding, 0);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp.width != cardWidthPx) { lp.width = cardWidthPx; child.setLayoutParams(lp); }
        }
        hsvCarousel.post(() -> {
            if (!isAdded()) return;
            for (int i = 0; i < container.getChildCount(); i++) {
                View c = container.getChildAt(i);
                if (c.getVisibility() == View.VISIBLE) {
                    int scrollToX = c.getLeft() - (screenWidth / 2) + (c.getWidth() / 2);
                    hsvCarousel.smoothScrollTo(scrollToX, 0); break;
                }
            }
        });
    }

    private boolean isNightMode() {
        try { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; } catch (Exception e) { return false; }
    }

    private void renderInventoryByKarat(List<KaratInventory> items) {
        if (llInventoryByKarat == null) return;
        if (permissionManager != null && !permissionManager.canViewDashboardInventoryByKarat()) {
            llInventoryByKarat.setVisibility(View.GONE); if (tvKaratEmptyState != null) tvKaratEmptyState.setVisibility(View.GONE); return;
        }
        try {
            llInventoryByKarat.removeAllViews();
            if (items == null || items.isEmpty()) {
                if (tvKaratEmptyState != null) tvKaratEmptyState.setVisibility(View.VISIBLE);
                llInventoryByKarat.setVisibility(View.GONE); return;
            }
            if (tvKaratEmptyState != null) tvKaratEmptyState.setVisibility(View.GONE);
            llInventoryByKarat.setVisibility(View.VISIBLE);
            List<KaratInventory> sorted = new ArrayList<>(items);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.karat, a.karat));
            for (KaratInventory inv : sorted) {
                View itemView = getLayoutInflater().inflate(R.layout.item_karat_inventory, llInventoryByKarat, false);
                TextView tvKarat = itemView.findViewById(R.id.tvKaratLabel);
                TextView tvCount = itemView.findViewById(R.id.tvKaratCount);
                TextView tvWeight = itemView.findViewById(R.id.tvKaratWeight);
                if (tvKarat != null) { tvKarat.setText(inv.karat + "K"); tvKarat.setTextColor(getKaratColor(inv.karat)); }
                if (tvCount != null) tvCount.setText(isInventoryHidden ? "••" : String.valueOf(inv.itemCount));
                if (tvWeight != null) tvWeight.setText(isInventoryHidden ? "•• g" : formatWeight(inv.weight));
                llInventoryByKarat.addView(itemView);
            }
        } catch (Exception e) { Log.e(TAG, "renderInventoryByKarat", e); }
    }

    private int getKaratColor(int karat) {
        switch (karat) { case 24: return 0xFFB8860B; case 22: return 0xFFDAA520; case 21: return 0xFFE6B800; case 18: return 0xFFCD853F; default: return 0xFFA0522D; }
    }

    private void updateHeroBalance() {
        try {
            if (tvHeroBalance == null) return;
            double totalValue = currentInventoryValue;
            if (totalValue <= 0 && priceManager != null && currentTotalWeight > 0) totalValue = currentTotalWeight * priceManager.getGoldPricePerGram(24);
            if (isHeroHidden) {
                tvHeroBalance.setText("••••••••");
                if (tvHeroSubtitle != null) tvHeroSubtitle.setText("••• g");
                if (tvHeroItemCount != null) tvHeroItemCount.setText("••• قطعة");
            } else {
                tvHeroBalance.setText(String.format(Locale.US, "$%,.2f", totalValue));
                if (tvHeroSubtitle != null) tvHeroSubtitle.setText(String.format(Locale.US, "%.1f g", currentTotalWeight));
                if (tvHeroItemCount != null) tvHeroItemCount.setText(String.format(Locale.getDefault(), "%d قطعة", currentItemCount));
            }
        } catch (Exception e) { Log.e(TAG, "updateHeroBalance", e); }
    }

    private void toggleHeroVisibility() { if (!isAdded() || tvHeroBalance == null) return; isHeroHidden = !isHeroHidden; if (btnToggleHeroVisibility != null) btnToggleHeroVisibility.setImageResource(isHeroHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on); updateHeroBalance(); }
    private void toggleSalesVisibility() { if (!isAdded() || tvSalesToday == null) return; isSalesHidden = !isSalesHidden; if (btnToggleSalesVisibility != null) btnToggleSalesVisibility.setImageResource(isSalesHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on); applySalesVisibility(); }
    private void toggleInventoryVisibility() { if (!isAdded()) return; isInventoryHidden = !isInventoryHidden; if (btnToggleInventoryVisibility != null) btnToggleInventoryVisibility.setImageResource(isInventoryHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on); renderInventoryByKarat(currentInventoryByKarat); }
    private void toggleScrapVisibility() { if (!isAdded()) return; isScrapHidden = !isScrapHidden; if (btnToggleScrapVisibility != null) btnToggleScrapVisibility.setImageResource(isScrapHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on); updateScrapUI(); }

    private void updateScrapUI() {
        if (tvScrapWeight == null || tvScrapValue == null) return;
        if (isScrapHidden) { tvScrapWeight.setText("••••••"); tvScrapValue.setText("••••••"); }
        else { tvScrapWeight.setText(String.format(Locale.US, "%.2f g", currentScrapWeight)); tvScrapValue.setText(formatCurrency(currentScrapValue)); }
    }

    private void fetchScrapSummary(int branchId) {
        if (roleController != null && !roleController.canFetchScrap()) return;
        if (apiClient == null || !ensureExecutor()) return;
        localExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getScrapSummary(branchId);
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data"); if (data == null) data = response.data;
                    if (data != null) {
                        currentScrapWeight = data.optDouble("total_weight", 0);
                        currentScrapValue = data.optDouble("total_value", 0);
                        mainHandler.post(() -> { if (isAdded()) { isScrapHidden = true; if (btnToggleScrapVisibility != null) btnToggleScrapVisibility.setImageResource(R.drawable.ic_eye_off); updateScrapUI(); } });
                    }
                }
            } catch (Exception e) { Log.e(TAG, "fetchScrapSummary", e); }
        });
    }

    private void fetchScrapAging(int branchId) {
        if (roleController != null && !roleController.canFetchScrapAging()) return;
        if (apiClient == null || !ensureExecutor()) return;
        localExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getScrapAgingSummary(branchId);
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data != null) {
                        final int totalItems = data.optInt("total_items", 0); final double totalWeight = data.optDouble("total_weight", 0);
                        final int oldCount = data.optInt("old_count", 0); final double oldWeight = data.optDouble("old_weight", 0);
                        mainHandler.post(() -> { if (isAdded()) renderAgingData(totalItems, totalWeight, oldCount, oldWeight); });
                    }
                }
            } catch (Exception e) { Log.e(TAG, "fetchScrapAging", e); }
        });
    }

    private void renderAgingData(int totalItems, double totalWeight, int oldCount, double oldWeight) {
        if (tvAgingStatusIcon == null) return;
        if (tvAgingWeight != null) tvAgingWeight.setText(String.format(Locale.US, "%.3f g", totalWeight));
        if (tvAgingCount != null) tvAgingCount.setText(totalItems + " فاتورة");
        if (oldCount > 0) {
            tvAgingStatusIcon.setText("🔴");
            if (tvAgingStatusLabel != null) { tvAgingStatusLabel.setText("تحذير"); tvAgingStatusLabel.setTextColor(0xFFDC2626); }
            if (llAgingAlert != null) { llAgingAlert.setVisibility(View.VISIBLE); if (tvAgingAlertText != null) tvAgingAlertText.setText(String.format(Locale.US, "%d فاتورة كسر مضى عليها +60 يوماً (%.2fg)", oldCount, oldWeight)); }
        } else if (totalItems > 0) {
            tvAgingStatusIcon.setText("🟢");
            if (tvAgingStatusLabel != null) { tvAgingStatusLabel.setText("سليم"); tvAgingStatusLabel.setTextColor(0xFF16A34A); }
            if (llAgingAlert != null) llAgingAlert.setVisibility(View.GONE);
        } else {
            tvAgingStatusIcon.setText("✅");
            if (tvAgingStatusLabel != null) { tvAgingStatusLabel.setText("مُصفّى"); tvAgingStatusLabel.setTextColor(0xFF16A34A); }
            if (llAgingAlert != null) llAgingAlert.setVisibility(View.GONE);
        }
    }

    private void applySalesVisibility() { if (isSalesHidden) { setText(tvSalesToday, "••••"); setText(tvSalesMonth, "••••"); setText(tvSalesYear, "••••"); } else if (lastSalesData != null) applySalesData(lastSalesData); }
    private void applySalesData(JSONObject sales) {
        if (sales == null || tvSalesToday == null) return;
        if (isSalesHidden) { setText(tvSalesToday, "••••"); setText(tvSalesMonth, "••••"); setText(tvSalesYear, "••••"); return; }
        setText(tvSalesToday, formatCurrencyShort(sales.optDouble("today", 0.0)));
        setText(tvSalesMonth, formatCurrencyShort(sales.optDouble("month", 0.0)));
        setText(tvSalesYear, formatCurrencyShort(sales.optDouble("year", 0.0)));
    }

    private void updateAlerts(int count, String message) {
        try {
            if (alertsStrip == null || (permissionManager != null && !permissionManager.canViewDashboardAlerts())) { if(alertsStrip != null) alertsStrip.setVisibility(View.GONE); return; }
            if (count > 0) {
                alertsStrip.setVisibility(View.VISIBLE);
                if (tvAlertIcon != null) tvAlertIcon.setText("⚠️");
                if (tvAlertMessage != null) tvAlertMessage.setText(message);
                if (tvAlertAction != null) tvAlertAction.setText(isArabic ? "عرض (" + count + ") →" : "View (" + count + ") →");
            } else { alertsStrip.setVisibility(View.GONE); }
        } catch (Exception e) { Log.e(TAG, "updateAlerts", e); }
    }
private void computeAndShowAlerts(JSONObject data) {
    // ✅ تخطي هذا لأمن البوابات (شريطهم مخصص للإحصائيات)
    if (permissionManager != null && permissionManager.isGateUser()) return;
        try {
            if (data == null) { updateAlerts(0, null); return; }
            int alertsCount = 0; StringBuilder message = new StringBuilder();
            JSONObject inv = data.optJSONObject("inventory");
            if (inv != null) {
                int itemsWithoutWeight = inv.optInt("items_without_weight", -1);
                int itemsWithoutPrice = inv.optInt("items_without_price", -1);
                if (itemsWithoutWeight > 0) { alertsCount += itemsWithoutWeight; message.append(isArabic ? itemsWithoutWeight + " قطعة بدون وزن" : itemsWithoutWeight + " items without weight"); }
                if (itemsWithoutPrice > 0) { if (message.length() > 0) message.append(" • "); alertsCount += itemsWithoutPrice; message.append(isArabic ? itemsWithoutPrice + " قطعة بدون سعر" : itemsWithoutPrice + " items without price"); }
            }
            if (alertsCount > 0) updateAlerts(alertsCount, message.toString()); else updateAlerts(0, null);
        } catch (Exception e) { Log.e(TAG, "computeAndShowAlerts", e); }
    }

    private void updateLiveStatusText(boolean isOnline) {
        try {
            if (!isAdded()) return;
            Context context = getContext(); if (context == null) return;
            if (tvLiveStatusText != null) {
                tvLiveStatusText.setText(isOnline ? (isArabic ? "متصل" : "Online") : (isArabic ? "غير متصل" : "Offline"));
                tvLiveStatusText.setTextColor(isOnline ? ContextCompat.getColor(context, R.color.status_online_green) : ContextCompat.getColor(context, R.color.status_offline_red));
            }
            if (liveStatusDot != null) liveStatusDot.setBackgroundResource(isOnline ? R.drawable.bg_status_dot_green : R.drawable.bg_status_dot_red);
        } catch (Exception e) { Log.e(TAG, "updateLiveStatusText", e); }
    }

    private void setupProfileListener() {
        try { if (orgProfile != null) { profileListener = () -> mainHandler.post(() -> {}); orgProfile.addListener(profileListener); } } catch (Exception e) {}
    }

    private void setupBranchListener() {
        try { if (branchesManager != null) { branchListener = () -> mainHandler.post(() -> {}); branchesManager.addListener(branchListener); } } catch (Exception e) {}
    }

    private void syncOrgProfileFromServer(boolean force) {
        try {
            if (orgProfile == null || apiClient == null) return;
            orgProfile.syncFromServer(apiClient, force, new MiraOrgProfileManager.SyncCallback() {
                @Override public void onSuccess() { mainHandler.post(() -> syncBranchesListFromServer()); }
                @Override public void onError(String error) {}
            });
        } catch (Exception e) {}
    }

    private void syncBranchesListFromServer() {
        try { if (branchesManager != null) branchesManager.syncFromServer(new MiraBranchesManager.SyncCallback() { @Override public void onSuccess() {} @Override public void onError(String error) {} }); } catch (Exception e) {}
    }

    private void fetchLiveGoldPrices(boolean forceRefresh) {
        try {
            if (!canManuallyRefreshGold() || permissionManager == null || !permissionManager.canAccessFragment("GoldRates") || apiClient == null || isGoldRefreshing.get() || !ensureExecutor()) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false); return;
            }
            long now = System.currentTimeMillis();
            if (!forceRefresh && (now - lastGoldFetchTime) < GOLD_CACHE_VALIDITY_MS) { displayGoldPricesFromCache(); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); return; }

            isGoldRefreshing.set(true); showGoldLoadingState(true); cancelFuture(goldPriceFuture);
            final Context appCtx = requireContext().getApplicationContext();

            goldPriceFuture = localExecutor.submit(() -> {
                try {
                    List<ApiProvider> providers = apiClient.getActiveProviders();
                    ApiProvider activeProvider = null;
                    if (providers != null && !providers.isEmpty()) {
                        for (ApiProvider provider : providers) { if (provider.isActive() && provider.getApiKey() != null && !provider.getApiKey().isEmpty()) { activeProvider = provider; break; } }
                        if (activeProvider == null) activeProvider = providers.get(0);
                    }
                    if (activeProvider == null) {
                        mainHandler.post(() -> { isGoldRefreshing.set(false); showGoldLoadingState(false); if (isAdded()) displayGoldPricesFromCache(); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }); return;
                    }
                    MiraApiClient.ApiResponse response = apiClient.fetchGoldPriceWithProvider(activeProvider.getId());
                    GoldPriceData priceData = parseGoldPriceData(response);
                    mainHandler.post(() -> {
                        isGoldRefreshing.set(false); showGoldLoadingState(false);
                        if (!isAdded()) return;
                        if (priceData != null) {
                            lastGoldFetchTime = System.currentTimeMillis(); displayGoldPrices(priceData); updateHeroBalance();
                            if (goldCachePrefs != null) goldCachePrefs.edit().putLong("last_gold_fetch_time", System.currentTimeMillis()).apply();
                            if (forceRefresh) Toast.makeText(appCtx, "✅ تم تحديث أسعار الذهب", Toast.LENGTH_SHORT).show();
                        } else { displayGoldPricesFromCache(); }
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> { isGoldRefreshing.set(false); showGoldLoadingState(false); if (isAdded()) displayGoldPricesFromCache(); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); });
                }
            });
        } catch (Exception e) { isGoldRefreshing.set(false); showGoldLoadingState(false); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }
    }

    private void showGoldLoadingState(boolean loading) {
        try { if (btnRefreshGold == null) return; btnRefreshGold.setEnabled(!loading); btnRefreshGold.setAlpha(loading ? 0.4f : 1.0f); if (pbGoldLoading != null) pbGoldLoading.setVisibility(loading ? View.VISIBLE : View.GONE); } catch (Exception e) {}
    }

    private GoldPriceData parseGoldPriceData(MiraApiClient.ApiResponse response) {
        try {
            if (response == null || !response.isSuccess || response.data == null) return null;
            JSONObject data = response.data.optJSONObject("data"); if (data == null) data = response.data;
            JSONObject prices = data.optJSONObject("prices"); if (prices == null) prices = data;
            double price24 = prices.optDouble("24k", 0); double price21 = prices.optDouble("21k", 0); double price18 = prices.optDouble("18k", 0);
            if (price24 <= 0 || price24 > 5000) return null;
            double ouncePrice = data.optDouble("price_per_ounce", price24 * OUNCE_TO_GRAM);
            return new GoldPriceData(price24, price21, price18, ouncePrice, data.optString("source", "server"));
        } catch (Exception e) { return null; }
    }

    private void displayGoldPrices(GoldPriceData data) {
        try {
            setGoldPrice(tvKarat24, "24K", data.price24); setGoldPrice(tvKarat21, "21K", data.price21); setGoldPrice(tvKarat18, "18K", data.price18);
            if (tvOuncePrice != null && data.ouncePrice > 0) tvOuncePrice.setText(String.format(Locale.US, "$%,.2f", data.ouncePrice));
            if (tvPriceSource != null) tvPriceSource.setText("📡 " + getSourceDisplayName(data.source));
            if (tvLastUpdate != null) tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + getTimeFormatter().format(new Date()) : "⏰ Last update: " + getTimeFormatter().format(new Date()));
            calculateTrend(data.price24);
        } catch (Exception e) {}
    }

    private void displayGoldPricesFromCache() {
        try {
            if (priceManager == null) return;
            double price24 = priceManager.getGoldPricePerGram(24); double price21 = priceManager.getGoldPricePerGram(21); double price18 = priceManager.getGoldPricePerGram(18);
            setGoldPrice(tvKarat24, "24K", price24); setGoldPrice(tvKarat21, "21K", price21); setGoldPrice(tvKarat18, "18K", price18);
            if (tvOuncePrice != null && price24 > 0) tvOuncePrice.setText(String.format(Locale.US, "$%,.2f", price24 * OUNCE_TO_GRAM));
            if (tvPriceSource != null) {
                long now = System.currentTimeMillis(); long timeRemaining = GOLD_CACHE_VALIDITY_MS - (now - lastGoldFetchTime);
                if (timeRemaining > 0) tvPriceSource.setText(isArabic ? "📡 كاش محلي - التحديث بعد " + (timeRemaining / 3600000) + " س " + ((timeRemaining % 3600000) / 60000) + " د" : "📡 Cache");
                else tvPriceSource.setText(isArabic ? "📡 جاهز للتحديث" : "📡 Ready to update");
            }
            if (tvLastUpdate != null && offlineCache != null && offlineCache.getLong("last_update", 0) > 0) tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + getTimeFormatter().format(new Date(offlineCache.getLong("last_update", 0))) : "⏰ Last update");
            updateHeroBalance();
        } catch (Exception e) {}
    }

    private void fetchApprovedGoldPricesFromServer() {
        if (roleController != null && !roleController.canFetchGoldPrices()) return;
        if (apiClient == null || !isAdded() || !isSessionValid() || !ensureExecutor()) return;
        localExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardGoldPrices();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject prices = response.data.optJSONObject("prices");
                    if (prices != null) {
                        double price24 = prices.optDouble("24k", 0); double price21 = prices.optDouble("21k", 0); double price18 = prices.optDouble("18k", 0);
                        if (price24 > 0) { GoldPriceData pd = new GoldPriceData(price24, price21, price18, price24 * OUNCE_TO_GRAM, response.data.optString("source", "server")); mainHandler.post(() -> { if (isAdded()) displayGoldPrices(pd); }); }
                    }
                }
            } catch (Exception e) {}
        });
    }

    private void calculateTrend(double currentPrice) {
        if (tvGoldTrend == null || tvGoldChange == null || offlineCache == null || !ensureExecutor()) return;
        localExecutor.execute(() -> {
            try {
                double previousPrice = offlineCache.getFloat("gold_price_24k_prev", 0);
                if (previousPrice > 0 && currentPrice > 0) {
                    double change = currentPrice - previousPrice; double changePercent = (change / previousPrice) * 100;
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        if (change > 0) { tvGoldTrend.setText("📈"); tvGoldChange.setText(String.format(Locale.US, "+%.2f%%", changePercent)); tvGoldChange.setTextColor(0xFF16A34A); }
                        else if (change < 0) { tvGoldTrend.setText("📉"); tvGoldChange.setText(String.format(Locale.US, "%.2f%%", changePercent)); tvGoldChange.setTextColor(0xFFDC2626); }
                        else { tvGoldTrend.setText("➡️"); tvGoldChange.setText("0.00%"); tvGoldChange.setTextColor(0xFF6B7280); }
                        if (tvHeroTrend != null) tvHeroTrend.setText((change > 0 ? "📈 " : change < 0 ? "📉 " : "➡️ ") + String.format(Locale.US, "%.2f%%", changePercent));
                    });
                }
                if (currentPrice > 0) offlineCache.edit().putFloat("gold_price_24k_prev", (float) currentPrice).apply();
            } catch (Exception e) {}
        });
    }

    private void loadOfflineData() {
        if (offlineCache == null || !isSessionValid() || !ensureExecutor()) return;
        localExecutor.execute(() -> {
            try {
                final int totalItems = offlineCache.getInt("total_items", 0);
                final float totalWeight = offlineCache.getFloat("total_weight", 0.0f);
                final float salesToday = offlineCache.getFloat("sales_today", 0.0f);
                final float salesMonth = offlineCache.getFloat("sales_month", 0.0f);
                final float salesYear  = offlineCache.getFloat("sales_year", 0.0f);
                final float inventoryValue = offlineCache.getFloat("inventory_value", 0.0f);
                final String karatJson = offlineCache.getString("inventory_by_karat", null);
                final List<KaratInventory> karatItems = new ArrayList<>();
                if (karatJson != null) {
                    try {
                        JSONArray arr = new JSONArray(karatJson);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject k = arr.optJSONObject(i);
                            if (k != null) { int karat = k.optInt("karat", 0); if (karat > 0) karatItems.add(new KaratInventory(karat, k.optInt("count", 0), k.optDouble("weight", 0.0))); }
                        }
                    } catch (Exception ignored) {}
                }
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    currentItemCount = totalItems; currentTotalWeight = totalWeight; currentInventoryValue = inventoryValue;
                    updateHeroBalance();
                    currentInventoryByKarat.clear(); currentInventoryByKarat.addAll(karatItems); renderInventoryByKarat(currentInventoryByKarat);
                    if (isSalesHidden) { setText(tvSalesToday, "••••"); setText(tvSalesMonth, "••••"); setText(tvSalesYear, "••••"); }
                    else { setText(tvSalesToday, formatCurrency(salesToday)); setText(tvSalesMonth, formatCurrency(salesMonth)); setText(tvSalesYear, formatCurrency(salesYear)); }
                    if (priceManager != null) displayGoldPricesFromCache();
                });
            } catch (Exception e) {}
        });
    }

    private void saveOfflineData(JSONObject data) {
        try {
            if (offlineCache == null || !isSessionValid() || !ensureExecutor()) return;
            localExecutor.execute(() -> {
                try {
                    JSONObject inv = data.optJSONObject("inventory"); JSONObject sales = data.optJSONObject("sales");
                    android.content.SharedPreferences.Editor editor = offlineCache.edit()
                            .putInt("total_items", inv != null ? inv.optInt("total_items", 0) : 0)
                            .putFloat("total_weight", (float) (inv != null ? inv.optDouble("total_weight", 0.0) : 0.0))
                            .putFloat("inventory_value", (float) (inv != null ? inv.optDouble("total_value", 0.0) : 0.0))
                            .putInt("sold_today", sales != null ? sales.optInt("count_today", 0) : 0)
                            .putFloat("sales_today", (float) (sales != null ? sales.optDouble("today", 0.0) : 0.0))
                            .putFloat("sales_month", (float) (sales != null ? sales.optDouble("month", 0.0) : 0.0))
                            .putFloat("sales_year", (float) (sales != null ? sales.optDouble("year", 0.0) : 0.0));
                    if (inv != null && inv.optJSONArray("by_karat") != null) editor.putString("inventory_by_karat", inv.optJSONArray("by_karat").toString());
                    editor.putLong("last_update", System.currentTimeMillis()).apply();
                } catch (Exception e) {}
            });
        } catch (Exception e) {}
    }

    private void fetchRecentActivity() {
        if (apiClient == null || !isAdded() || !isSessionValid() || !ensureExecutor()) return;
        
        localExecutor.execute(() -> {
            try {
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                MiraApiClient.ApiResponse response = apiClient.getUnifiedActivity(branchId, 15);
                
                if (response != null && response.isSuccess && response.data != null) {
                    Log.d(TAG, "📋 Activity Raw Response: " + response.data.toString());
                    JSONArray activities = extractActivitiesArray(response.data);
                    
                    if (activities != null && activities.length() > 0) {
                        mainHandler.post(() -> {
                            if (isAdded()) {
                                updateRecentActivityUI(activities);
                                updatePosRecentActivityUI(activities);
                            }
                        });
                    } else {
                        Log.w(TAG, "⚠️ Activity array is empty or not found.");
                    }
                } else {
                    Log.e(TAG, "❌ Activity API Failed: " + (response != null ? response.error : "null"));
                }
            } catch (Exception e) { 
                Log.e(TAG, "❌ fetchRecentActivity Error", e); 
            }
        });
    }

    private void updateRecentActivityUI(JSONArray activities) {
        if (activities == null || llRecentActivity == null) return;
        try {
            llRecentActivity.removeAllViews();
            int max = Math.min(activities.length(), 10);
            for (int i = 0; i < max; i++) {
                JSONObject item = activities.getJSONObject(i);
                View itemView = getLayoutInflater().inflate(R.layout.item_activity_timeline, llRecentActivity, false);
                bindActivityToView(itemView, item);
                itemView.setOnClickListener(v -> handleRecentActivityClick(item));
                llRecentActivity.addView(itemView);
            }
        } catch (Exception e) {}
    }
    
    @Nullable
    private JSONArray extractActivitiesArray(@NonNull JSONObject top) {
        JSONArray arr = top.optJSONArray("activities");
        if (arr != null) return arr;
        arr = top.optJSONArray("data");
        if (arr != null) return arr;
        arr = top.optJSONArray("items");
        if (arr != null) return arr;
        arr = top.optJSONArray("logs");
        if (arr != null) return arr;
        arr = top.optJSONArray("result");
        if (arr != null) return arr;
        JSONObject nested = top.optJSONObject("data");
        if (nested != null) {
            arr = nested.optJSONArray("activities");
            if (arr != null) return arr;
            arr = nested.optJSONArray("items");
            if (arr != null) return arr;
            arr = nested.optJSONArray("logs");
            if (arr != null) return arr;
        }
        return null;
    }

    private void updatePosRecentActivityUI(JSONArray activities) {
        if (activities == null || llPosRecentActivity == null) return;
        try {
            llPosRecentActivity.removeAllViews();
            int max = Math.min(activities.length(), 5); 
            for (int i = 0; i < max; i++) {
                JSONObject item = activities.getJSONObject(i);
                View itemView = getLayoutInflater().inflate(R.layout.item_pos_activity, llPosRecentActivity, false);
                
                TextView tvIcon = itemView.findViewById(R.id.tvPosActIcon);
                TextView tvTitle = itemView.findViewById(R.id.tvPosActTitle);
                TextView tvTime = itemView.findViewById(R.id.tvPosActTime);
                TextView tvAmount = itemView.findViewById(R.id.tvPosActAmount);

                String icon = item.optString("icon", "🛒");
                String title = isArabic ? item.optString("title_ar", "") : item.optString("title_en", "");
                String timestamp = item.optString("timestamp", "");
                double amount = item.optDouble("amount", 0);
                String currency = item.optString("currency", "YER");
                String status = item.optString("status", "success");

                if (tvIcon != null) tvIcon.setText(icon); 
                if (tvTitle != null) tvTitle.setText(title);
                if (tvTime != null) tvTime.setText(extractTime(timestamp));
                
                if (tvAmount != null) {
                    if (isPosDashboardHidden) {
                        tvAmount.setText("•••••");
                        tvAmount.setTextColor(ContextCompat.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
                    } else {
                        int redColor = isNightMode() ? Color.parseColor("#F87171") : Color.parseColor("#DC2626");
                        int greenColor = isNightMode() ? Color.parseColor("#4ADE80") : Color.parseColor("#16A34A");
                        
                        if (amount < 0 || "refund".equals(status)) {
                            tvAmount.setText(String.format(Locale.US, "- %,.0f %s", Math.abs(amount), currency));
                            tvAmount.setTextColor(redColor);
                        } else if (amount > 0) {
                            tvAmount.setText(String.format(Locale.US, "+ %,.0f %s", amount, currency));
                            tvAmount.setTextColor(greenColor);
                        } else {
                            tvAmount.setText("—");
                            tvAmount.setTextColor(ContextCompat.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
                        }
                    }
                }
                itemView.setOnClickListener(v -> handleRecentActivityClick(item));
                llPosRecentActivity.addView(itemView);
            }
        } catch (Exception e) { Log.e(TAG, "updatePosRecentActivityUI", e); }
    }

    private void bindActivityToView(View v, JSONObject item) {
        if (v == null || item == null) return;
        try {
            TextView tvIcon = v.findViewById(R.id.tvActivityIcon); TextView tvTitle = v.findViewById(R.id.tvActivityTitle);
            TextView tvDetails = v.findViewById(R.id.tvActivityDetails); TextView tvUser = v.findViewById(R.id.tvActivityUser);
            TextView tvTime = v.findViewById(R.id.tvActivityTime); TextView tvAmount = v.findViewById(R.id.tvActivityAmount);
            TextView tvStatusChip = v.findViewById(R.id.tvActivityStatusChip);
            String icon = item.optString("icon", "⚙️");
            String title = isArabic ? item.optString("title_ar", "") : item.optString("title_en", "");
            String details = isArabic ? item.optString("details_ar", "") : item.optString("details_en", "");
            String user = item.optString("user", "—"); String timestamp = item.optString("timestamp", "");
            double amount = item.optDouble("amount", 0); double accuracy = item.optDouble("accuracy", -1);
            if (tvIcon != null) tvIcon.setText(icon); if (tvTitle != null) tvTitle.setText(title);
            if (tvDetails != null) tvDetails.setText(details); if (tvUser != null) tvUser.setText("👤 " + user);
            if (tvTime != null) tvTime.setText(extractTime(timestamp));
            if (tvAmount != null) {
                if (amount > 0) { tvAmount.setVisibility(View.VISIBLE); tvAmount.setText(String.format(Locale.US, "%,.2f %s", amount, item.optString("currency", "USD"))); }
                else if (accuracy >= 0) { tvAmount.setVisibility(View.VISIBLE); tvAmount.setText(String.format(Locale.US, "🎯 %.1f%%", accuracy)); }
                else tvAmount.setVisibility(View.GONE);
            }
            if (tvStatusChip != null) {
                String status = item.optString("status", "neutral");
                if ("danger".equals(status)) { tvStatusChip.setVisibility(View.VISIBLE); tvStatusChip.setText(isArabic ? "⚠️ حرج" : "⚠️ Critical"); tvStatusChip.setTextColor(0xFFDC2626); }
                else if ("warning".equals(status)) { tvStatusChip.setVisibility(View.VISIBLE); tvStatusChip.setText(isArabic ? "⚠️ تحذير" : "⚠️ Warning"); tvStatusChip.setTextColor(0xFFF59E0B); }
                else tvStatusChip.setVisibility(View.GONE);
            }
            CountdownTimerView countdown = v.findViewById(R.id.countdownTimer);
            if (countdown != null) {
                String status = item.optString("status", "");
                long expiresAt = item.optLong("expires_at", 0);
                long createdAt = item.optLong("created_at", 0);
                if ("pending".equals(status) && expiresAt > 0) {
                    countdown.setVisibility(View.VISIBLE);
                    countdown.start(createdAt, expiresAt, () -> fetchData());
                } else {
                    countdown.cancel(); countdown.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {}
    }

    private String extractTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return "";
        try { String[] parts = dateStr.split(" "); if (parts.length >= 2) { String[] timeParts = parts[1].split(":"); if (timeParts.length >= 2) return timeParts[0] + ":" + timeParts[1]; } } catch (Exception ignored) {}
        return dateStr;
    }

    private void handleRecentActivityClick(JSONObject activity) {
        if (activity == null || !isAdded()) return;
        try {
            ActivityDetailsBottomSheet sheet = ActivityDetailsBottomSheet.newInstance(activity, isArabic);
            sheet.setOnActionClickListener(new ActivityDetailsBottomSheet.OnActionClickListener() {
                @Override public void onPrintInvoice(JSONObject activityData) {
                    if (!isAdded() || getContext() == null) return;
                    try {
                        com.example.uhf.fragment.MiraReceiptFragment receiptFragment = com.example.uhf.fragment.MiraReceiptFragment.newInstance(activityData.toString());
                        androidx.fragment.app.FragmentManager fm = getParentFragmentManager();
                        if (fm != null && !fm.isStateSaved()) receiptFragment.show(fm, "MiraReceiptFragment");
                    } catch (Exception e) { Toast.makeText(getContext(), "تعذر فتح الفاتورة", Toast.LENGTH_SHORT).show(); }
                }
                @Override public void onOpenRefund(JSONObject activityData) {
                    if (!isAdded() || getActivity() == null) return;
                    try {
                        Fragment salesFragment = new MiraPosFragment();
                        Bundle bundle = new Bundle(); bundle.putString("refund_data_json", activityData.toString()); bundle.putBoolean("auto_open_refund", true);
                        salesFragment.setArguments(bundle);
                        if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).loadFragment(salesFragment, "Sales");
                    } catch (Exception e) {}
                }
            });
            sheet.show(getParentFragmentManager(), "ActivityDetails");
        } catch (Exception e) {}
    }

    private void registerDataRefreshReceiver() {
        try {
            if (getContext() == null || isReceiverRegistered) return;
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.example.uhf.REFRESH_DASHBOARD"); filter.addAction("com.example.uhf.REFRESH_INVENTORY"); filter.addAction("com.example.uhf.REFRESH_SALES");
            ContextCompat.registerReceiver(requireContext(), dataRefreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isReceiverRegistered = true;
        } catch (Exception e) {}
    }

    private void unregisterDataRefreshReceiver() {
        try { if (getContext() != null && isReceiverRegistered) { requireContext().unregisterReceiver(dataRefreshReceiver); isReceiverRegistered = false; } } catch (Exception e) {}
    }

    private void refreshAllData() {
        try {
            if (tvLiveStatusText != null) { tvLiveStatusText.setText(isArabic ? "⏳ جاري التحديث..." : "⏳ Updating..."); tvLiveStatusText.setTextColor(0xFFF59E0B); }
            if (swipeTimeoutRunnable != null) mainHandler.removeCallbacks(swipeTimeoutRunnable);
            swipeTimeoutRunnable = () -> { if (swipeRefresh != null && swipeRefresh.isRefreshing()) swipeRefresh.setRefreshing(false); };
            mainHandler.postDelayed(swipeTimeoutRunnable, SWIPE_REFRESH_TIMEOUT_MS);

            if (getView() != null) {
                uiBuilder.setupQuickActionButtons(getView(), (target, tag) -> navigateToFragment(target, tag));
            }

            fetchData(); fetchRecentActivity();
            if (currentBranchId > 0) { fetchScrapSummary(currentBranchId); fetchScrapAging(currentBranchId); }
            fetchDashboardCharts();

            long now = System.currentTimeMillis();
            if (canManuallyRefreshGold()) {
                if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) fetchLiveGoldPrices(true);
                else { displayGoldPricesFromCache(); updateLiveStatusText(true); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }
            } else { displayGoldPricesFromCache(); fetchApprovedGoldPricesFromServer(); updateLiveStatusText(true); if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }
        } catch (Exception e) { if (swipeRefresh != null) swipeRefresh.setRefreshing(false); }
    }

    private void showShimmer(boolean show) {
        try { if (shimmerLayout != null) shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE); if (loadingOverlay != null) loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE); if (swipeRefresh != null) swipeRefresh.setVisibility(show ? View.GONE : View.VISIBLE); } catch (Exception e) {}
    }

    private void initBranchCache(int branchId) {
        try {
            if (getContext() == null || sessionManager == null) return;
            int userId = sessionManager.getUserId(); if (userId <= 0) return;
            String prefsName = OFFLINE_PREFS_BASE + userId + "_b" + branchId; offlineCache = createSecurePrefs(requireContext(), prefsName);
        } catch (Exception e) {}
    }

    private android.content.SharedPreferences createSecurePrefs(Context ctx, String name) {
        android.content.SharedPreferences encrypted = tryCreateEncryptedPrefs(ctx, name);
        if (encrypted != null) return encrypted;
        return ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private android.content.SharedPreferences tryCreateEncryptedPrefs(Context ctx, String name) {
        try {
            Class<?> masterKeyClass = Class.forName("androidx.security.crypto.MasterKey");
            Class<?> builderClass = Class.forName("androidx.security.crypto.MasterKey$Builder");
            Class<?> keySchemeClass = Class.forName("androidx.security.crypto.MasterKey$KeyScheme");
            Object aes256Gcm = Enum.valueOf((Class<Enum>) keySchemeClass, "AES256_GCM");
            Object builder = builderClass.getConstructor(Context.class).newInstance(ctx);
            builderClass.getMethod("setKeyScheme", keySchemeClass).invoke(builder, aes256Gcm);
            Object masterKey = builderClass.getMethod("build").invoke(builder);
            Class<?> espClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences");
            Class<?> keyEncClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences$PrefKeyEncryptionScheme");
            Class<?> valEncClass = Class.forName("androidx.security.crypto.EncryptedSharedPreferences$PrefValueEncryptionScheme");
            Object keyEnc = Enum.valueOf((Class<Enum>) keyEncClass, "AES256_SIV");
            Object valEnc = Enum.valueOf((Class<Enum>) valEncClass, "AES256_GCM");
            Method create = espClass.getMethod("create", Context.class, String.class, masterKeyClass, keyEncClass, valEncClass);
            return (android.content.SharedPreferences) create.invoke(null, ctx, name, masterKey, keyEnc, valEnc);
        } catch (Throwable t) { return null; }
    }

    private void startAutoRefresh() { mainHandler.removeCallbacks(autoRefreshRunnable); mainHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS); }
    private void stopAutoRefresh() { mainHandler.removeCallbacks(autoRefreshRunnable); }

    private void navigateToFragment(Fragment fragment, String tag) { try { if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).loadFragment(fragment, tag); } catch (Exception e) {} }

    private String getSourceDisplayName(String source) {
        if (source == null) return isArabic ? "غير معروف" : "Unknown";
        switch (source.toLowerCase()) { case "metalprice": return "MetalPrice"; case "goldapi": return "GoldAPI"; case "metalsdev": return "Metals.Dev"; case "fallback": return isArabic ? "احتياطي" : "Fallback"; default: return source; }
    }

    private String formatCurrency(double amount) { try { if (amount <= 0) return "—"; if (priceManager != null) return priceManager.formatPriceDefault(amount); return getCurrencyFormatter().format(amount); } catch (Exception e) { return "—"; } }
    private String formatCurrencyShort(double amount) { if (amount <= 0) return "0"; if (amount >= 1000000) return String.format(Locale.US, "%.1fM", amount / 1000000); if (amount >= 1000) return String.format(Locale.US, "%.1fK", amount / 1000); return String.format(Locale.US, "%.0f", amount); }
    private String formatWeight(double weightInGrams) { if (weightInGrams <= 0) return "0.0 g"; return weightInGrams >= 1000 ? String.format(Locale.US, "%.2f kg", weightInGrams / 1000) : String.format(Locale.US, "%.1f g", weightInGrams); }
    private void setGoldPrice(TextView tv, String label, double price) { if (tv == null) return; tv.setText(price > 0 ? String.format(Locale.US, "%s: $%,.2f/g", label, price) : label + ": —"); }
    private void setText(TextView tv, String text) { if (tv != null) tv.setText(text); }

    private void hideSensitiveValuesOnExit() {
        try {
            if (!isHeroHidden) { isHeroHidden = true; if (btnToggleHeroVisibility != null) btnToggleHeroVisibility.setImageResource(R.drawable.ic_eye_off); }
            if (!isSalesHidden) { isSalesHidden = true; if (btnToggleSalesVisibility != null) btnToggleSalesVisibility.setImageResource(R.drawable.ic_eye_off); }
            if (!isInventoryHidden) { isInventoryHidden = true; if (btnToggleInventoryVisibility != null) btnToggleInventoryVisibility.setImageResource(R.drawable.ic_eye_off); renderInventoryByKarat(currentInventoryByKarat); }
            if (!isScrapHidden) { isScrapHidden = true; if (btnToggleScrapVisibility != null) btnToggleScrapVisibility.setImageResource(R.drawable.ic_eye_off); updateScrapUI(); }
        } catch (Exception e) {}
    }

    public void onBranchChanged() {
        try {
            if (!isAdded()) return;
            int newBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (newBranchId != currentBranchId) {
                currentBranchId = newBranchId; initBranchCache(currentBranchId);
                if (roleController != null && getView() != null) roleController.applyVisibility(getView());
                if (getView() != null) { 
                    uiBuilder.setupQuickActionButtons(getView(), (target, tag) -> navigateToFragment(target, tag)); 
                    uiBuilder.applyLayoutVisibility(getView(), roleController); 
                }
                syncOrgProfileFromServer(true); loadOfflineData();
                if (offlineCache == null || offlineCache.getInt("total_items", 0) == 0) showShimmer(true);
                fetchData(); fetchRecentActivity();
                if (currentBranchId > 0) { fetchScrapSummary(currentBranchId); fetchScrapAging(currentBranchId); }
            }
        } catch (Exception e) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            isHeroHidden = true; isSalesHidden = true; isInventoryHidden = true; isScrapHidden = true;
            if (btnToggleHeroVisibility != null) btnToggleHeroVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleSalesVisibility != null) btnToggleSalesVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleInventoryVisibility != null) btnToggleInventoryVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleScrapVisibility != null) btnToggleScrapVisibility.setImageResource(R.drawable.ic_eye_off);
            updateScrapUI();
            registerDataRefreshReceiver();

            int latestBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (latestBranchId != currentBranchId) onBranchChanged();
            else if (isViewInitialized) {
                syncOrgProfileFromServer(false);
                if (roleController != null && getView() != null) roleController.applyVisibility(getView());
                if (getView() != null) { 
                    uiBuilder.setupQuickActionButtons(getView(), (target, tag) -> navigateToFragment(target, tag)); 
                    uiBuilder.applyLayoutVisibility(getView(), roleController); 
                }

                long now = System.currentTimeMillis();
                long timeSinceLastFetch = now - lastDataFetchTime; long timeSinceLastGoldFetch = now - lastGoldFetchTime;
                boolean isCacheValid = activityCache != null && activityCache.isValid();
                if (timeSinceLastFetch > RESUME_REFETCH_THRESHOLD_MS) { fetchData(); if (currentBranchId > 0) { fetchScrapSummary(currentBranchId); fetchScrapAging(currentBranchId); } }
                if (!isCacheValid) mainHandler.postDelayed(() -> { if (isAdded() && isResumed()) fetchRecentActivity(); }, 800);
                if (timeSinceLastGoldFetch > 30 * 60 * 1000 && canManuallyRefreshGold()) mainHandler.postDelayed(() -> { if (isAdded() && isResumed()) fetchLiveGoldPrices(false); }, 1500);
                else { displayGoldPricesFromCache(); fetchApprovedGoldPricesFromServer(); }
            }
            startAutoRefresh();
        } catch (Exception e) {}
    }

    @Override
    public void onPause() {
        super.onPause();
        try { 
            unregisterDataRefreshReceiver(); stopAutoRefresh(); hideSensitiveValuesOnExit(); 
            if (shiftSummaryCard != null) shiftSummaryCard.hideSensitiveValues();
            isShiftCardAnimated = false;
        } catch (Exception e) {}
    }

    @Override
    public void onStop() { super.onStop(); hideSensitiveValuesOnExit(); }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            isArabic = newConfig.getLocales().get(0).getLanguage().equals("ar");
            mainHandler.post(() -> {
                if (isAdded() && isViewInitialized) { applyCarouselLayout(); applyChartTheme(); if (lineChartSales != null) lineChartSales.invalidate(); if (pieChartKarat != null) pieChartKarat.invalidate(); if (lineChartPosPriceMovement != null) lineChartPosPriceMovement.invalidate(); }
            });
        } catch (Exception e) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            unregisterDataRefreshReceiver(); stopAutoRefresh();
            if (branchesManager != null && branchListener != null) { branchesManager.removeListener(branchListener); branchListener = null; }
            if (orgProfile != null && profileListener != null) { orgProfile.removeListener(profileListener); profileListener = null; }
            cancelFuture(dataFuture); cancelFuture(goldPriceFuture);
            mainHandler.removeCallbacks(autoRefreshRunnable); if (swipeTimeoutRunnable != null) mainHandler.removeCallbacks(swipeTimeoutRunnable);
            shutdownExecutor(); isViewInitialized = false;
        } catch (Exception e) {}
    }

    private void cancelFuture(Future<?> future) { try { if (future != null && !future.isDone()) future.cancel(true); } catch (Exception e) {} }

    private void initializeManagers() {
        try {
            if (!isAdded()) return;
            Context context = requireContext();
            if (localExecutor == null || localExecutor.isShutdown()) localExecutor = Executors.newFixedThreadPool(4);
            apiClient = MiraApiClient.getInstance(context); sessionManager = new SessionManager(context); branchesManager = MiraBranchesManager.getInstance(context);
            orgProfile = MiraOrgProfileManager.getInstance(context); priceManager = MiraPriceManager.getInstance(context); permissionManager = PermissionManager.getInstance(context);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            roleController = new DashboardRoleController(permissionManager, isArabic);
            
            // ✅ تهيئة الباني الجديد
            uiBuilder = new DashboardUIBuilder(this, permissionManager);
            
            goldCachePrefs = context.getSharedPreferences("gold_cache_prefs", Context.MODE_PRIVATE);
            lastGoldFetchTime = goldCachePrefs.getLong("last_gold_fetch_time", 0);
            activityCache = new RecentActivityCache(context);
        } catch (Exception e) {}
    }

    private boolean canManuallyRefreshGold() { return permissionManager != null && permissionManager.canRefreshGoldManually(); }
}
