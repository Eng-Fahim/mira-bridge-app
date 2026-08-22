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
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.QuickActionsAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
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

public class MiraDashboardFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "MiraDashboardFragment";
    private static final String OFFLINE_PREFS_BASE = "dashboard_cache_branch_";
    private static final double OUNCE_TO_GRAM = 31.1035;
    private static final int MAX_QUICK_ACTIONS = 9;
    private static final long CACHE_VALIDITY_MS = 5 * 60 * 1000;
    private static final long PING_VALIDITY_MS = 30 * 1000;

    // UI Elements
    private TextView tvGreeting, tvDate, tvLocation, tvLiveStatus;
    private TextView tvTotalItems, tvTotalWeight, tvSoldToday;
    private TextView tvSalesToday, tvSalesMonth, tvSalesYear;
    private TextView tvKarat24, tvKarat21, tvKarat18;
    private TextView tvOuncePrice, tvPriceSource, tvLastUpdate;
    private TextView tvGoldTrend, tvGoldChange;
    private View btnGate, btnInventory, btnRefreshGold;
    private SwipeRefreshLayout swipeRefresh;
    private View shimmerLayout;
    private CardView cardGoldPrices;
    
    private RecyclerView rvQuickActions;
    private QuickActionsAdapter quickAdapter;

    // Managers
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;
    private MiraPriceManager priceManager;
    private MiraQuickAccessSettings quickAccessSettings;
    private PermissionManager permissionManager;
    
    // Threading
    private static final ExecutorService sharedExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> dataFuture;
    private Future<?> goldPriceFuture;
    private Future<?> pingFuture;
    
    // State & Cache
    private boolean isArabic;
    private SharedPreferences offlineCache;
    private SharedPreferences branchPrefs;
    private int currentBranchId = -1;
    private boolean isViewInitialized = false;
    private boolean isLoading = false;
    private boolean isDataLoaded = false;
    private long lastDataFetchTime = 0;
    private long lastGoldFetchTime = 0;
    private long lastPingTime = 0;

    // ThreadLocal Formatters
    private static final ThreadLocal<SimpleDateFormat> dateFormatsAr = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("EEEE، d MMMM yyyy", new Locale("ar")));

    private static final ThreadLocal<SimpleDateFormat> dateFormatsEn = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US));

    private static final ThreadLocal<SimpleDateFormat> timeFormatter = 
        ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss", Locale.US));
    
    private static final ThreadLocal<NumberFormat> currencyFormatter = 
        ThreadLocal.withInitial(() -> NumberFormat.getCurrencyInstance(Locale.US));

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
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
            loadDataIfNeeded();
            
            isViewInitialized = true;
        } catch (Throwable e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    private void initializeManagers() {
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
        shimmerLayout = v.findViewById(R.id.shimmerLayout);
        swipeRefresh = v.findViewById(R.id.swipeRefresh);
        tvOuncePrice = v.findViewById(R.id.tvOuncePrice);
        tvPriceSource = v.findViewById(R.id.tvPriceSource);
        tvLastUpdate = v.findViewById(R.id.tvLastUpdate);
        tvGoldTrend = v.findViewById(R.id.tvGoldTrend);
        tvGoldChange = v.findViewById(R.id.tvGoldChange);
        btnRefreshGold = v.findViewById(R.id.btnRefreshGold);
        cardGoldPrices = v.findViewById(R.id.cardGoldPrices);

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

        if (btnRefreshGold != null) {
            btnRefreshGold.setOnClickListener(v2 -> fetchLiveGoldPrices(true));
        }
    }

    private void updateHeader() {
        if (tvDate != null) {
            SimpleDateFormat formatter = isArabic ? dateFormatsAr.get() : dateFormatsEn.get();
            if (formatter != null) {
                tvDate.setText(formatter.format(new Date()));
            }
        }
        
        if (tvGreeting != null && sessionManager != null) {
            String name = sessionManager.getDisplayName();
            if (name == null || name.isEmpty()) name = sessionManager.getUsername();
            if (name == null) name = isArabic ? "المستخدم" : "User";
            
            String orgName = orgProfile != null ? orgProfile.getDisplayName() : "MIRA";
            tvGreeting.setText(isArabic ? orgName + " - مرحباً، " + name : orgName + " - Welcome, " + name);
        }
        
        if (tvLocation != null && branchesManager != null) {
            tvLocation.setText("📍 " + branchesManager.getActiveBranchDisplayName());
        }
    }

    private void setupQuickAccessGrid() {
        if (quickAccessSettings == null || quickAdapter == null) return;
        
        sharedExecutor.execute(() -> {
            if (!isAdded()) return;
            
            List<MiraQuickAccessSettings.QuickAction> enabledActions = quickAccessSettings.getEnabledActions();
            List<MiraQuickAccessSettings.QuickAction> filteredActions = new ArrayList<>();
            
            for (MiraQuickAccessSettings.QuickAction action : enabledActions) {
                if (filteredActions.size() >= MAX_QUICK_ACTIONS) break;
                if (permissionManager != null && permissionManager.canAccessFragment(getFragmentTagForAction(action.id))) {
                    filteredActions.add(action);
                }
            }
            
            mainHandler.post(() -> {
                if (isAdded() && quickAdapter != null) {
                    quickAdapter.submitList(filteredActions);
                }
            });
        });
    }

    private void loadDataIfNeeded() {
        long now = System.currentTimeMillis();

        if (!isDataLoaded || (now - lastDataFetchTime) > CACHE_VALIDITY_MS) {
            fetchData();
        }

        if (permissionManager != null && permissionManager.canAccessFragment("GoldRates") 
                && (now - lastGoldFetchTime) > CACHE_VALIDITY_MS) {
            fetchLiveGoldPrices(false);
        }

        if ((now - lastPingTime) > PING_VALIDITY_MS) {
            updateLiveStatus();
            lastPingTime = now;
        }
    }

    private void updateLiveStatus() {
        if (tvLiveStatus == null || apiClient == null) return;

        cancelFuture(pingFuture);
        pingFuture = sharedExecutor.submit(() -> {
            try {
                boolean isOnline = apiClient.pingApi();
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    tvLiveStatus.setText(isOnline ? (isArabic ? "🟢 متصل" : "🟢 Online") : (isArabic ? "🔴 غير متصل" : "🔴 Offline"));
                    tvLiveStatus.setTextColor(isOnline ? 0xFF16A34A : 0xFFDC2626);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (isAdded()) {
                        tvLiveStatus.setText(isArabic ? "🔴 غير متصل" : "🔴 Offline");
                        tvLiveStatus.setTextColor(0xFFDC2626);
                    }
                });
            }
        });
    }

    private void fetchLiveGoldPrices(boolean forceRefresh) {
    if (apiClient == null) return;

    long now = System.currentTimeMillis();
    if (!forceRefresh && (now - lastGoldFetchTime) < CACHE_VALIDITY_MS) return;

    cancelFuture(goldPriceFuture);
    long startTime = System.currentTimeMillis();

    goldPriceFuture = sharedExecutor.submit(() -> {
        try {
            // ✅ استخدام الدوال الموجودة
            List<ApiProvider> providers = apiClient.getActiveProviders();
            ApiProvider activeProvider = null;
            
            for (ApiProvider provider : providers) {
                if (provider.isActive() && !provider.getApiKey().isEmpty()) {
                    activeProvider = provider;
                    break;
                }
            }
            
            if (activeProvider == null && !providers.isEmpty()) {
                activeProvider = providers.get(0);
            }
            
            if (activeProvider == null) {
                mainHandler.post(() -> {
                    if (isAdded()) displayGoldPricesFromCache();
                });
                return;
            }
            
            MiraApiClient.ApiResponse response = apiClient.fetchGoldPriceWithProvider(activeProvider.getId());
            GoldPriceData priceData = parseGoldPriceData(response);
            
            mainHandler.post(() -> {
                lastGoldFetchTime = System.currentTimeMillis();
                logPerformance("fetchLiveGoldPrices", System.currentTimeMillis() - startTime);
                if (!isAdded()) return;
                
                if (priceData != null) {
                    displayGoldPrices(priceData);
                } else {
                    displayGoldPricesFromCache();
                }
            });
        } catch (Exception e) {
            mainHandler.post(() -> {
                if (isAdded()) displayGoldPricesFromCache();
            });
        }
    });
}

    private GoldPriceData parseGoldPriceData(MiraApiClient.ApiResponse response) {
        if (response == null || !response.isSuccess || response.data == null) {
            return null;
        }
        
        try {
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
            return null;
        }
    }

    private void displayGoldPrices(GoldPriceData data) {
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
            SimpleDateFormat timeFmt = timeFormatter.get();
            String time = timeFmt != null ? timeFmt.format(new Date()) : "";
            tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
        }
        
        calculateTrend(data.price24);
    }

    private void displayGoldPricesFromCache() {
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
            tvPriceSource.setText(isArabic ? "📡 كاش محلي" : "📡 Local cache");
        }

        if (tvLastUpdate != null) {
            tvLastUpdate.setText(isArabic ? "⏰ وضع أوفلاين" : "⏰ Offline mode");
        }
    }

    private void calculateTrend(double currentPrice) {
        if (tvGoldTrend == null || tvGoldChange == null || offlineCache == null) return;

        double previousPrice = offlineCache.getFloat("gold_price_24k_prev", 0);

        if (previousPrice > 0 && currentPrice > 0) {
            double change = currentPrice - previousPrice;
            double changePercent = (change / previousPrice) * 100;

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
        }

        if (currentPrice > 0) {
            offlineCache.edit().putFloat("gold_price_24k_prev", (float) currentPrice).apply();
        }
    }

    private void loadOfflineData() {
        if (offlineCache == null) return;
        
        int items = offlineCache.getInt("total_items", 0);
        double weightVal = offlineCache.getFloat("total_weight", 0.0f);
        int sold = offlineCache.getInt("sold_today", 0);
        double sToday = offlineCache.getFloat("sales_today", 0.0f);
        double sMonth = offlineCache.getFloat("sales_month", 0.0f);
        double sYear = offlineCache.getFloat("sales_year", 0.0f);

        setText(tvTotalItems, String.valueOf(items));
        setText(tvTotalWeight, formatWeight(weightVal));
        setText(tvSoldToday, String.valueOf(sold));
        setText(tvSalesToday, formatCurrency(sToday));
        setText(tvSalesMonth, formatCurrency(sMonth));
        setText(tvSalesYear, formatCurrency(sYear));
        
        if (priceManager != null) {
            displayGoldPricesFromCache();
        }
    }

    private void saveOfflineData(JSONObject data) {
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
                Log.e(TAG, "saveOfflineData error", e);
            }
        });
    }

    private void fetchData() {
        if (isLoading || apiClient == null) return;
        isLoading = true;

        if (tvLiveStatus != null) {
            tvLiveStatus.setText(isArabic ? "جاري التحديث..." : "Updating...");
            tvLiveStatus.setTextColor(0xFFF59E0B);
        }

        cancelFuture(dataFuture);
        final int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
        long startTime = System.currentTimeMillis();

        dataFuture = sharedExecutor.submit(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardFull(activeBranchId);
                mainHandler.post(() -> {
                    isLoading = false;
                    lastDataFetchTime = System.currentTimeMillis();
                    logPerformance("fetchData", System.currentTimeMillis() - startTime);
                    if (!isAdded()) return;

                    if (response != null && response.isSuccess && response.data != null) {
                        saveOfflineData(response.data);
                        processData(response.data);
                        isDataLoaded = true;
                    } else {
                        loadOfflineData();
                    }
                    showShimmer(false);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isLoading = false;
                    if (!isAdded()) return;
                    loadOfflineData();
                    showShimmer(false);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    private void processData(JSONObject data) {
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
    }

    private void setupProfileListener() {
        if (orgProfile == null) return;
        profileListener = () -> mainHandler.post(this::updateHeader);
        orgProfile.addListener(profileListener);
    }

    private void setupListeners() {
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
                btnInventory.setOnClickListener(v -> navigateToFragment(new MiraInventoryProFragment(), "Scan"));
            }
        }
    }

    private void refreshAllData() {
        showShimmer(true);
        fetchData();
        if (permissionManager != null && permissionManager.canAccessFragment("GoldRates")) {
            fetchLiveGoldPrices(true);
        }
    }

    private void showShimmer(boolean show) {
        if (shimmerLayout != null) {
            shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (swipeRefresh != null) {
            swipeRefresh.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void initBranchCache(int branchId) {
        if (getContext() != null) {
            offlineCache = requireContext().getSharedPreferences(OFFLINE_PREFS_BASE + branchId, Context.MODE_PRIVATE);
        }
    }

    private void navigateToFragment(Fragment fragment, String tag) {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).loadFragment(fragment, tag);
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
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            Fragment fragment = createFragmentForAction(actionId);
            if (fragment != null) {
                activity.loadFragment(fragment, getFragmentTagForAction(actionId));
            }
        }
    }

    private Fragment createFragmentForAction(String actionId) {
        switch (actionId) {
            case "identity": return new MiraIdentityStudioFragment();
            case "radar": return new MiraUHFRadarLocationFragment();
            case "gate": return new MiraSecureGateFragment();
            case "reports": return new MiraReportsProFragment();
            case "accounting": return new MiraAccountingFragment();
            case "transfers": return new MiraTransferFragment();
            case "admin_hub": return new MiraAdminHubFragment();
            case "sales_history": return new MiraSalesHistoryFragment();
            case "server_config": return new MiraServerConfigFragment();
            case "gold_rates": return new MiraGoldCurrencyFragment();
            case "users": return new UsersManagementFragment();
            default: return null;
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
        if (amount <= 0) return "—";
        if (priceManager != null) return priceManager.formatPriceDefault(amount);
        NumberFormat fmt = currencyFormatter.get();
        return fmt != null ? fmt.format(amount) : String.valueOf(amount);
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

    private void logPerformance(String operation, long durationMs) {
        if (durationMs > 1000) {
            Log.w(TAG, "Slow operation detected: " + operation + " took " + durationMs + "ms");
        }
    }

    public void onBranchChanged() {
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
    }

    @Override
    public void onResume() {
        super.onResume();
        if (branchPrefs != null) {
            branchPrefs.registerOnSharedPreferenceChangeListener(this);
        }

        int latestBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
        if (latestBranchId != currentBranchId) {
            onBranchChanged();
        } else if (isViewInitialized) {
            setupQuickAccessGrid();
            updateHeader();
            loadDataIfNeeded();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (branchPrefs != null) {
            branchPrefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("active_branch_id".equals(key) || "active_branch".equals(key)) {
            mainHandler.post(this::onBranchChanged);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (rvQuickActions != null) {
            rvQuickActions.setItemViewCacheSize(0);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (orgProfile != null && profileListener != null) {
            orgProfile.removeListener(profileListener);
        }

        cancelFuture(dataFuture);
        cancelFuture(goldPriceFuture);
        cancelFuture(pingFuture);

        mainHandler.removeCallbacksAndMessages(null);
        isViewInitialized = false;
    }

    private void cancelFuture(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }
}
