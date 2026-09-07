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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.QuickActionsAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.ApiProvider;
import com.example.uhf.tools.SessionManager;

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
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraDashboardFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "MiraDashboardFragment";
    private static final String OFFLINE_PREFS_BASE = "dashboard_cache_branch_";
    private static final double OUNCE_TO_GRAM = 31.1035;
    private static final int MAX_QUICK_ACTIONS = 9;
    private static final long CACHE_VALIDITY_MS = 2 * 60 * 1000;
    private static final long GOLD_CACHE_VALIDITY_MS = 12 * 60 * 60 * 1000;
    private static final long PING_VALIDITY_MS = 30 * 1000;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    // UI Elements
    private TextView tvOrgName, tvGreeting, tvDate, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24, tvKarat21, tvKarat18;
    private TextView tvOuncePrice, tvPriceSource, tvLastUpdate;
    private TextView tvGoldTrend, tvGoldChange;
    private View btnGate, btnInventory, btnRefreshGold;
    private SwipeRefreshLayout swipeRefresh;
    private View shimmerLayout, loadingOverlay;
    private ProgressBar pbGoldLoading;
    private CardView cardGoldPrices;
    private RecyclerView rvQuickActions;
    private QuickActionsAdapter quickAdapter;

    // Managers & State
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;
    private MiraPriceManager priceManager;
    private MiraQuickAccessSettings quickAccessSettings;
    private PermissionManager permissionManager;

    private static final ExecutorService sharedExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> dataFuture, goldPriceFuture, pingFuture;

    private boolean isArabic;
    private boolean isReceiverRegistered = false;

    private SharedPreferences offlineCache, branchPrefs, goldCachePrefs;
    private int currentBranchId = -1;
    private boolean isViewInitialized = false;
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isGoldRefreshing = new AtomicBoolean(false);
    private boolean isDataLoaded = false;
    private long lastDataFetchTime = 0, lastGoldFetchTime = 0, lastPingTime = 0;
    private int consecutiveFailures = 0;

    private SimpleDateFormat getDateFormatter() {
        return isArabic ? new SimpleDateFormat("EEEE، d MMMM yyyy", new Locale("ar")) : new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US);
    }

    private SimpleDateFormat getTimeFormatter() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US);
    }

    private NumberFormat getCurrencyFormatter() {
        return NumberFormat.getCurrencyInstance(Locale.US);
    }

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
                        Log.d(TAG, "Refreshing dashboard from broadcast");
                        isDataLoaded = false;
                        lastDataFetchTime = 0;
                        isLoading.set(false);
                        
                        if (isResumed()) {
                            fetchData();
                        }
                    }
                });
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
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
            setupQuickAccessGrid();
            setupListeners();

            currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            initBranchCache(currentBranchId);

            loadOfflineData();
            updateHeader();
            showShimmer(false);
            
            isViewInitialized = true;
            loadDataIfNeeded();
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
            branchPrefs = context.getSharedPreferences("mira_branches_prefs", Context.MODE_PRIVATE);
            goldCachePrefs = context.getSharedPreferences("gold_cache_prefs", Context.MODE_PRIVATE);
            lastGoldFetchTime = goldCachePrefs.getLong("last_gold_fetch_time", 0);
            
            boolean isLoggedIn = sessionManager != null && sessionManager.isLoggedIn();
            Log.d(TAG, "Dashboard Initialized. Logged in: " + isLoggedIn);
        } catch (Exception e) {
            logNonFatal("MiraDashboardFragment.initializeManagers", e);
        }
    }

    private void initViews(View v) {
        try {
            tvOrgName = v.findViewById(R.id.tvOrgName);
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
            tvOuncePrice = v.findViewById(R.id.tvOuncePrice);
            tvPriceSource = v.findViewById(R.id.tvPriceSource);
            tvLastUpdate = v.findViewById(R.id.tvLastUpdate);
            tvGoldTrend = v.findViewById(R.id.tvGoldTrend);
            tvGoldChange = v.findViewById(R.id.tvGoldChange);
            btnRefreshGold = v.findViewById(R.id.btnRefreshGold);
            cardGoldPrices = v.findViewById(R.id.cardGoldPrices);
            loadingOverlay = v.findViewById(R.id.loadingOverlay);
            pbGoldLoading = v.findViewById(R.id.pbGoldLoading);

            if (btnRefreshGold != null) {
                btnRefreshGold.setOnClickListener(v2 -> {
                    if (!isGoldRefreshing.get()) {
                        fetchLiveGoldPrices(true);
                    }
                });
            }

            if (cardGoldPrices != null && permissionManager != null && !permissionManager.canAccessFragment("GoldRates")) {
                cardGoldPrices.setVisibility(View.GONE);
            }

            rvQuickActions = v.findViewById(R.id.rvQuickActions);
            if (rvQuickActions != null) {
                rvQuickActions.setLayoutManager(new GridLayoutManager(getContext(), 3));
                rvQuickActions.setHasFixedSize(true);
                quickAdapter = new QuickActionsAdapter(this::handleQuickAction);
                rvQuickActions.setAdapter(quickAdapter);
            }

            if (swipeRefresh != null) {
                swipeRefresh.setOnRefreshListener(this::refreshAllData);
            }
        } catch (Exception e) {
            logNonFatal("initViews", e);
        }
    }

    private void updateHeader() {
        try {
            if (tvDate != null) {
                SimpleDateFormat formatter = getDateFormatter();
                tvDate.setText(formatter.format(new Date()));
            }
            if (tvOrgName != null && orgProfile != null) {
                String orgName = orgProfile.getDisplayName();
                tvOrgName.setText((orgName == null || orgName.isEmpty()) ? "MIRA" : orgName);
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
        } catch (Exception e) {
            logNonFatal("updateHeader", e);
        }
    }
    
    private void setupQuickAccessGrid() {
        try {
            if (quickAccessSettings == null || quickAdapter == null) return;
            
            sharedExecutor.execute(() -> {
                try {
                    if (!isAdded()) return;
                    
                    List<MiraQuickAccessSettings.QuickAction> enabledActions = quickAccessSettings.getEnabledActions();
                    List<MiraQuickAccessSettings.QuickAction> filteredActions = new ArrayList<>();
                    
                    if (enabledActions != null && !enabledActions.isEmpty()) {
                        for (MiraQuickAccessSettings.QuickAction action : enabledActions) {
                            String fragmentTag = getFragmentTagForAction(action.id);
                            boolean hasPermission = permissionManager == null || permissionManager.canAccessFragment(fragmentTag);
                            
                            if (hasPermission) {
                                filteredActions.add(action);
                            }
                        }
                    }
                    
                    mainHandler.post(() -> {
                        if (isAdded() && quickAdapter != null) {
                            quickAdapter.submitList(filteredActions);
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

    private void loadDataIfNeeded() {
        try {
            long now = System.currentTimeMillis();
            if (!isDataLoaded || (now - lastDataFetchTime) > CACHE_VALIDITY_MS) {
                fetchData();
            }

            if (permissionManager != null && permissionManager.canAccessFragment("GoldRates")) {
                if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                    fetchLiveGoldPrices(false);
                } else {
                    displayGoldPricesFromCache();
                }
            }

            if ((now - lastPingTime) > PING_VALIDITY_MS) {
                updateLiveStatus();
                lastPingTime = now;
            }
        } catch (Exception e) {
            logNonFatal("loadDataIfNeeded", e);
        }
    }

    private void updateLiveStatus() {
        try {
            if (tvLiveStatus == null || apiClient == null) return;

            cancelFuture(pingFuture);
            pingFuture = sharedExecutor.submit(() -> {
                boolean isOnline = apiClient.pingApi();
                mainHandler.post(() -> updateLiveStatusText(isOnline));
            });
        } catch (Exception e) {
            logNonFatal("updateLiveStatus", e);
        }
    }

    private void updateLiveStatusText(boolean isOnline) {
        try {
            if (!isAdded() || tvLiveStatus == null) return;
            Context context = getContext();
            if (context == null) return;

            if (isOnline) {
                tvLiveStatus.setText(isArabic ? "🟢 متصل" : "🟢 Online");
                tvLiveStatus.setTextColor(ContextCompat.getColor(context, R.color.status_online_green));
            } else {
                tvLiveStatus.setText(isArabic ? "🔴 غير متصل" : "🔴 Offline");
                tvLiveStatus.setTextColor(ContextCompat.getColor(context, R.color.status_offline_red));
            }
        } catch (Exception e) {
            logNonFatal("updateLiveStatusText", e);
        }
    }

    private void fetchLiveGoldPrices(boolean forceRefresh) {
        try {
            if (apiClient == null || isGoldRefreshing.get()) return;

            long now = System.currentTimeMillis();
            if (!forceRefresh && (now - lastGoldFetchTime) < GOLD_CACHE_VALIDITY_MS) {
                displayGoldPricesFromCache();
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
                            if (provider.isActive() && provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
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
                        });
                        return;
                    }

                    MiraApiClient.ApiResponse response = apiClient.fetchGoldPriceWithProvider(activeProvider.getId());
                    GoldPriceData priceData = parseGoldPriceData(response);

                    mainHandler.post(() -> {
                        isGoldRefreshing.set(false);
                        showGoldLoadingState(false);
                        if (!isAdded()) return;

                        if (priceData != null) {
                            lastGoldFetchTime = System.currentTimeMillis();
                            displayGoldPrices(priceData);
                            Toast.makeText(getContext(), isArabic ? "✅ تم تحديث أسعار الذهب" : "✅ Gold prices updated", Toast.LENGTH_SHORT).show();
                        } else {
                            displayGoldPricesFromCache();
                            Toast.makeText(getContext(), isArabic ? "⚠️ تعذر التحديث - عرض الكاش" : "⚠️ Update failed - showing cache", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    logNonFatal("fetchLiveGoldPrices.thread", e);
                    mainHandler.post(() -> {
                        isGoldRefreshing.set(false);
                        showGoldLoadingState(false);
                        if (isAdded()) displayGoldPricesFromCache();
                    });
                }
            });
        } catch (Exception e) {
            logNonFatal("fetchLiveGoldPrices", e);
            isGoldRefreshing.set(false);
            showGoldLoadingState(false);
        }
    }

    private void showGoldLoadingState(boolean isLoading) {
        try {
            if (btnRefreshGold == null) return;
            btnRefreshGold.setEnabled(!isLoading);
            btnRefreshGold.setAlpha(isLoading ? 0.4f : 1.0f);
            if (pbGoldLoading != null) {
                pbGoldLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
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
            double ouncePrice = data.optDouble("price_per_ounce", prices.optDouble("ounce_price", price24 * OUNCE_TO_GRAM));
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
                tvOuncePrice.setText(String.format(Locale.US, "💰 $%,.2f", data.ouncePrice));
            }
            if (tvPriceSource != null) {
                tvPriceSource.setText("📡 " + getSourceDisplayName(data.source));
            }
            if (tvLastUpdate != null) {
                SimpleDateFormat timeFmt = getTimeFormatter();
                String time = timeFmt.format(new Date());
                tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
            }

            calculateTrend(data.price24);

            if (goldCachePrefs != null) {
                goldCachePrefs.edit().putLong("last_gold_fetch_time", System.currentTimeMillis()).apply();
            }
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
                tvOuncePrice.setText(String.format(Locale.US, "💰 $%,.2f", price24 * OUNCE_TO_GRAM));
            }

            if (tvPriceSource != null) {
                long now = System.currentTimeMillis();
                long timeRemaining = GOLD_CACHE_VALIDITY_MS - (now - lastGoldFetchTime);
                if (timeRemaining > 0) {
                    long hours = timeRemaining / (60 * 60 * 1000);
                    long minutes = (timeRemaining % (60 * 60 * 1000)) / (60 * 1000);
                    tvPriceSource.setText(isArabic ? "📡 كاش محلي - التحديث بعد " + hours + " س " + minutes + " د" : "📡 Cache - update in " + hours + "h " + minutes + "m");
                } else {
                    tvPriceSource.setText(isArabic ? "📡 جاهز للتحديث" : "📡 Ready to update");
                }
            }

            if (tvLastUpdate != null && offlineCache != null) {
                long lastUpdate = offlineCache.getLong("last_update", 0);
                if (lastUpdate > 0) {
                    SimpleDateFormat timeFmt = getTimeFormatter();
                    String time = timeFmt.format(new Date(lastUpdate));
                    tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
                }
            }
        } catch (Exception e) {
            logNonFatal("displayGoldPricesFromCache", e);
        }
    }

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
                
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    setText(tvTotalItems, String.valueOf(totalItems));
                    setText(tvTotalWeight, formatWeight(totalWeight));
                    setText(tvSoldToday, String.valueOf(soldToday));
                    setText(tvSalesToday, formatCurrency(salesToday));
                    setText(tvSalesMonth, formatCurrency(salesMonth));
                    setText(tvSalesYear, formatCurrency(salesYear));
                    
                    if (priceManager != null) {
                        displayGoldPricesFromCache();
                    }
                });
            } catch (Exception e) {
                logNonFatal("loadOfflineData", e);
            }
        });
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

    private void fetchData() {
        try {
            if (isLoading.get() || apiClient == null || !isAdded()) return;

            if (sessionManager == null || !sessionManager.isLoggedIn() || !sessionManager.hasValidToken()) {
                Log.w(TAG, "Token invalid, redirecting to login");
                redirectToLogin();
                return;
            }

            isLoading.set(true);

            if (tvLiveStatus != null) {
                tvLiveStatus.setText(isArabic ? "⏳ جاري التحديث..." : "⏳ Updating...");
                tvLiveStatus.setTextColor(0xFFF59E0B);
            }

            cancelFuture(dataFuture);
            final int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;

            dataFuture = sharedExecutor.submit(() -> {
                try {
                    MiraApiClient.ApiResponse response = apiClient.getDashboardFull(activeBranchId);
                    
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        lastDataFetchTime = System.currentTimeMillis();
                        if (!isAdded()) return;

                        if (response != null && response.code == 401) {
                            handleTokenExpired();
                            return;
                        }

                        if (response != null && response.isSuccess && response.data != null) {
                            saveOfflineData(response.data);
                            processData(response.data);
                            isDataLoaded = true;
                            consecutiveFailures = 0;
                            updateLiveStatusText(true);
                            
                            showShimmer(false);
                            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        } else {
                            if (response == null || response.code != 401) {
                                logNonFatal("fetchData.response", new Exception("Dashboard API Failed - code=" + (response != null ? response.code : -1)));
                            }
                            
                            updateLiveStatusText(false);
                            fetchInventoryDataDirectly(activeBranchId);
                        }

                        showShimmer(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    logNonFatal("fetchData.thread", e);
                    
                    mainHandler.post(() -> {
                        isLoading.set(false);
                        if (!isAdded()) return;

                        consecutiveFailures++;
                        updateLiveStatusText(false);
                        loadOfflineData();
                        showShimmer(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);

                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            Toast.makeText(getContext(), isArabic ? "تعذر تحديث البيانات من السيرفر" : "Could not update data from server", Toast.LENGTH_SHORT).show();
                            consecutiveFailures = 0;
                        }
                    });
                }
            });
        } catch (Exception e) {
            logNonFatal("fetchData", e);
            isLoading.set(false);
        }
    }

    private void handleTokenExpired() {
        try {
            Log.w(TAG, "401 received - clearing session and redirecting to login");
            
            if (sessionManager != null) {
                sessionManager.clearAuthToken();
                sessionManager.logoutUser();
            }
            
            isLoading.set(false);
            showShimmer(false);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), 
                    isArabic ? "انتهت صلاحية الجلسة - سجل الدخول مجدداً" : "Session expired - Please sign in again", 
                    Toast.LENGTH_LONG).show();
            }
            
            redirectToLogin();
        } catch (Exception e) {
            Log.e(TAG, "handleTokenExpired error: " + e.getMessage());
        }
    }

    private void redirectToLogin() {
        try {
            if (isAdded() && getActivity() != null) {
                Intent intent = new Intent(getActivity(), com.example.uhf.activity.LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.putExtra("session_expired", true);
                startActivity(intent);
                getActivity().finish();
            }
        } catch (Exception e) {
            Log.e(TAG, "redirectToLogin error: " + e.getMessage());
        }
    }

    private void fetchInventoryDataDirectly(int branchId) {
        try {
            if (apiClient == null || branchId <= 0) {
                loadOfflineData();
                updateLiveStatusText(false);
                return;
            }

            sharedExecutor.execute(() -> {
                try {
                    MiraApiClient.ApiResponse response = apiClient.getBranchInventory(branchId);
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        if (response != null && response.isSuccess && response.data != null) {
                            int totalItems = response.data.optInt("total", 0);
                            double totalWeight = 0;

                            org.json.JSONArray items = response.data.optJSONArray("items");
                            if (items != null) {
                                for (int i = 0; i < items.length(); i++) {
                                    JSONObject item = items.optJSONObject(i);
                                    if (item != null) {
                                        totalWeight += item.optDouble("gross_weight_grams", 0);
                                    }
                                }
                            }

                            setText(tvTotalItems, String.valueOf(totalItems));
                            setText(tvTotalWeight, formatWeight(totalWeight));

                            if (offlineCache != null) {
                                offlineCache.edit()
                                        .putInt("total_items", totalItems)
                                        .putFloat("total_weight", (float) totalWeight)
                                        .putLong("last_update", System.currentTimeMillis())
                                        .apply();
                            }

                            updateLiveStatusText(true);
                        } else {
                            updateLiveStatusText(false);
                            loadOfflineData();
                        }
                        showShimmer(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                } catch (Exception e) {
                    logNonFatal("fetchInventoryDataDirectly.thread", e);
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        loadOfflineData();
                        updateLiveStatusText(false);
                        showShimmer(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    });
                }
            });
        } catch (Exception e) {
            logNonFatal("fetchInventoryDataDirectly", e);
            loadOfflineData();
            updateLiveStatusText(false);
            showShimmer(false);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        }
    }

    private void logNonFatal(String location, Throwable throwable) {
        try {
            Log.e(TAG, "Error at " + location + ": " + throwable.getMessage(), throwable);
        } catch (Exception e) {
            Log.e(TAG, "logNonFatal error: " + e.getMessage());
        }
    }

    private void processData(JSONObject data) {
        try {
            JSONObject inv = data.optJSONObject("inventory");
            if (inv != null) {
                setText(tvTotalItems, String.valueOf(inv.optInt("total_items", 0)));
                setText(tvTotalWeight, formatWeight(inv.optDouble("total_weight", 0.0)));
            }

            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                setText(tvSoldToday, String.valueOf(sales.optInt("count_today", 0)));
                setText(tvSalesToday, formatCurrency(sales.optDouble("today", 0.0)));
                setText(tvSalesMonth, formatCurrency(sales.optDouble("month", 0.0)));
                setText(tvSalesYear, formatCurrency(sales.optDouble("year", 0.0)));
            }
        } catch (Exception e) {
            logNonFatal("processData", e);
        }
    }

    private void setupProfileListener() {
        try {
            if (orgProfile == null) return;
            profileListener = () -> mainHandler.post(this::updateHeader);
            orgProfile.addListener(profileListener);
        } catch (Exception e) {
            logNonFatal("setupProfileListener", e);
        }
    }

    private void setupListeners() {
        try {
            if (btnGate != null && permissionManager != null) {
                boolean canAccess = permissionManager.canAccessFragment("SecureGate");
                btnGate.setVisibility(canAccess ? View.VISIBLE : View.GONE);
                if (canAccess) {
                    btnGate.setOnClickListener(v -> navigateToFragment(new MiraSecureGateFragment(), "SecureGate"));
                }
            }

            if (btnInventory != null && permissionManager != null) {
                boolean canAccess = permissionManager.canAccessFragment("Scan");
                btnInventory.setVisibility(canAccess ? View.VISIBLE : View.GONE);
                if (canAccess) {
                    btnInventory.setOnClickListener(v -> navigateToFragment(new MiraUHFReadTagFragment(), "Scan"));
                }
            }
        } catch (Exception e) {
            logNonFatal("setupListeners", e);
        }
    }

    private void registerDataRefreshReceiver() {
        try {
            if (getContext() == null || isReceiverRegistered) return;
            
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.example.uhf.REFRESH_DASHBOARD");
            filter.addAction("com.example.uhf.REFRESH_INVENTORY");
            filter.addAction("com.example.uhf.REFRESH_SALES");
            
            ContextCompat.registerReceiver(requireContext(), dataRefreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isReceiverRegistered = true;
            Log.d(TAG, "DataRefreshReceiver registered");
        } catch (Exception e) {
            Log.e(TAG, "registerDataRefreshReceiver error: " + e.getMessage());
        }
    }

    private void unregisterDataRefreshReceiver() {
        try {
            if (getContext() != null && isReceiverRegistered) {
                requireContext().unregisterReceiver(dataRefreshReceiver);
                isReceiverRegistered = false;
                Log.d(TAG, "DataRefreshReceiver unregistered");
            }
        } catch (Exception e) {
            Log.d(TAG, "unregisterDataRefreshReceiver: " + e.getMessage());
        }
    }

    public void refreshInternalData() {
        try {
            if (!isAdded()) return;
            isDataLoaded = false;
            lastDataFetchTime = 0;
            isLoading.set(false);
            fetchData();
            displayGoldPricesFromCache();
        } catch (Exception e) {
            logNonFatal("refreshInternalData", e);
        }
    }

    private void refreshAllData() {
        try {
            if (tvLiveStatus != null) {
                tvLiveStatus.setText(isArabic ? "⏳ جاري التحديث..." : "⏳ Updating...");
                tvLiveStatus.setTextColor(0xFFF59E0B);
            }

            fetchData();

            long now = System.currentTimeMillis();
            if (permissionManager != null && permissionManager.canAccessFragment("GoldRates")) {
                if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                    fetchLiveGoldPrices(true);
                } else {
                    displayGoldPricesFromCache();
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    updateLiveStatusText(true);
                }
            } else {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        } catch (Exception e) {
            logNonFatal("refreshAllData", e);
        }
    }

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
                offlineCache = requireContext().getSharedPreferences(OFFLINE_PREFS_BASE + branchId, Context.MODE_PRIVATE);
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
            if (getActivity() == null || !(getActivity() instanceof UHFMainActivity)) return;
            
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
            NumberFormat fmt = getCurrencyFormatter();
            return fmt.format(amount);
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
        tv.setText(price > 0 ? String.format(Locale.US, "%s: $%,.2f/g", label, price) : label + ": —");
    }

    private void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    public void onBranchChanged() {
        try {
            if (!isAdded()) return;
            int newBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (newBranchId != currentBranchId) {
                currentBranchId = newBranchId;
                initBranchCache(currentBranchId);
                updateHeader();
                loadOfflineData();
                showShimmer(true);
                fetchData();
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
            
            if (branchPrefs != null) {
                branchPrefs.registerOnSharedPreferenceChangeListener(this);
            }

            isDataLoaded = false;
            lastDataFetchTime = 0;
            
            int latestBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (latestBranchId != currentBranchId) {
                onBranchChanged();
            } else if (isViewInitialized) {
                setupQuickAccessGrid();
                updateHeader();
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
            if (branchPrefs != null) {
                branchPrefs.unregisterOnSharedPreferenceChangeListener(this);
            }
        } catch (Exception e) {
            logNonFatal("onPause", e);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        try {
            if ("active_branch_id".equals(key) || "active_branch".equals(key)) {
                mainHandler.post(this::onBranchChanged);
            }
        } catch (Exception e) {
            logNonFatal("onSharedPreferenceChanged", e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            unregisterDataRefreshReceiver();
            stopAutoRefresh();
            if (branchPrefs != null) {
                branchPrefs.unregisterOnSharedPreferenceChangeListener(this);
            }
            if (orgProfile != null && profileListener != null) {
                orgProfile.removeListener(profileListener);
            }

            cancelFuture(dataFuture);
            cancelFuture(goldPriceFuture);
            cancelFuture(pingFuture);

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
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        } catch (Exception e) {
            logNonFatal("cancelFuture", e);
        }
    }
}
