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
import android.widget.Toast;

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
    private static final long CACHE_VALIDITY_MS = 0; // Always refresh
    private static final long GOLD_CACHE_VALIDITY_MS = 12 * 60 * 60 * 1000;
    private static final long PING_VALIDITY_MS = 30 * 1000;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes auto-refresh
    
    private SharedPreferences goldCachePrefs;

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
    private static final ExecutorService sharedExecutor = Executors.newFixedThreadPool(3);
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
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private boolean isDataLoaded = false;
    private long lastDataFetchTime = 0;
    private long lastGoldFetchTime = 0;
    private long lastPingTime = 0;
    private long lastResumeTime = 0;

    // ✅ Runnable للتحديث التلقائي
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
        try {
            return inflater.inflate(R.layout.fragment_mira_dashboard, container, false);
        } catch (Exception e) {
            Log.e(TAG, "onCreateView error: " + e.getMessage());
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
            loadDataIfNeeded();
            
            isViewInitialized = true;
            startAutoRefresh();
        } catch (Throwable e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
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
        } catch (Exception e) {
            Log.e(TAG, "initializeManagers error: " + e.getMessage());
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
        } catch (Exception e) {
            Log.e(TAG, "initViews error: " + e.getMessage());
        }
    }

    private void updateHeader() {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "updateHeader error: " + e.getMessage());
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
                } catch (Exception e) {
                    Log.e(TAG, "setupQuickAccessGrid inner error: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "setupQuickAccessGrid error: " + e.getMessage());
        }
    }

    private void loadDataIfNeeded() {
        try {
            long now = System.currentTimeMillis();

            // ✅ Always fetch data on first load or when cache is invalid
            if (!isDataLoaded || (now - lastDataFetchTime) > CACHE_VALIDITY_MS || CACHE_VALIDITY_MS == 0) {
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
            Log.e(TAG, "loadDataIfNeeded error: " + e.getMessage());
        }
    }

    private void updateLiveStatus() {
        try {
            if (tvLiveStatus == null || apiClient == null) return;
            
            cancelFuture(pingFuture);
            pingFuture = sharedExecutor.submit(() -> {
                try {
                    boolean isOnline = apiClient.pingApi();
                    mainHandler.post(() -> {
                        try {
                            if (!isAdded()) return;
                            tvLiveStatus.setText(isOnline ? (isArabic ? "🟢 متصل" : "🟢 Online") : (isArabic ? "🔴 غير متصل" : "🔴 Offline"));
                            tvLiveStatus.setTextColor(isOnline ? 0xFF16A34A : 0xFFDC2626);
                        } catch (Exception e) {}
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        try {
                            if (isAdded()) {
                                tvLiveStatus.setText(isArabic ? "🔴 غير متصل" : "🔴 Offline");
                                tvLiveStatus.setTextColor(0xFFDC2626);
                            }
                        } catch (Exception e2) {}
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "updateLiveStatus error: " + e.getMessage());
        }
    }

    private void fetchLiveGoldPrices(boolean forceRefresh) {
        try {
            if (apiClient == null) return;
            
            long now = System.currentTimeMillis();
            if (!forceRefresh && (now - lastGoldFetchTime) < GOLD_CACHE_VALIDITY_MS) {
                displayGoldPricesFromCache();
                return;
            }
            
            cancelFuture(goldPriceFuture);
            
            goldPriceFuture = sharedExecutor.submit(() -> {
                try {
                    List<ApiProvider> providers = apiClient.getActiveProviders();
                    ApiProvider activeProvider = null;
                    
                    if (providers != null) {
                        for (ApiProvider provider : providers) {
                            if (provider.isActive() && provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
                                activeProvider = provider;
                                break;
                            }
                        }
                        if (activeProvider == null && !providers.isEmpty()) {
                            activeProvider = providers.get(0);
                        }
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
                        try {
                            lastGoldFetchTime = System.currentTimeMillis();
                            if (!isAdded()) return;
                            if (priceData != null) {
                                displayGoldPrices(priceData);
                            } else {
                                displayGoldPricesFromCache();
                            }
                        } catch (Exception e) {}
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        try {
                            if (isAdded()) displayGoldPricesFromCache();
                        } catch (Exception e2) {}
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchLiveGoldPrices error: " + e.getMessage());
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
                SimpleDateFormat timeFmt = timeFormatter.get();
                String time = timeFmt != null ? timeFmt.format(new Date()) : "";
                tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
            }
            
            calculateTrend(data.price24);
            
            if (goldCachePrefs != null) {
                goldCachePrefs.edit().putLong("last_gold_fetch_time", System.currentTimeMillis()).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "displayGoldPrices error: " + e.getMessage());
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
                long timeSinceLastFetch = now - lastGoldFetchTime;
                long timeRemaining = GOLD_CACHE_VALIDITY_MS - timeSinceLastFetch;
                
                if (timeRemaining > 0) {
                    long hoursRemaining = timeRemaining / (60 * 60 * 1000);
                    long minutesRemaining = (timeRemaining % (60 * 60 * 1000)) / (60 * 1000);
                    tvPriceSource.setText(isArabic ? 
                        "📡 كاش محلي - التحديث بعد " + hoursRemaining + " ساعة " + minutesRemaining + " دقيقة" : 
                        "📡 Cache - update in " + hoursRemaining + "h " + minutesRemaining + "m");
                } else {
                    tvPriceSource.setText(isArabic ? "📡 جاهز للتحديث" : "📡 Ready to update");
                }
            }
            
            if (tvLastUpdate != null && offlineCache != null) {
                long lastUpdate = offlineCache.getLong("last_update", 0);
                if (lastUpdate > 0) {
                    SimpleDateFormat timeFmt = timeFormatter.get();
                    String time = timeFmt != null ? timeFmt.format(new Date(lastUpdate)) : "";
                    tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "displayGoldPricesFromCache error: " + e.getMessage());
        }
    }

    private void calculateTrend(double currentPrice) {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "calculateTrend error: " + e.getMessage());
        }
    }

    private void loadOfflineData() {
        try {
            if (offlineCache == null) return;
            
            int items = offlineCache.getInt("total_items", 0);
            float weightVal = offlineCache.getFloat("total_weight", 0.0f);
            int sold = offlineCache.getInt("sold_today", 0);
            float sToday = offlineCache.getFloat("sales_today", 0.0f);
            float sMonth = offlineCache.getFloat("sales_month", 0.0f);
            float sYear = offlineCache.getFloat("sales_year", 0.0f);
            
            setText(tvTotalItems, String.valueOf(items));
            setText(tvTotalWeight, formatWeight(weightVal));
            setText(tvSoldToday, String.valueOf(sold));
            setText(tvSalesToday, formatCurrency(sToday));
            setText(tvSalesMonth, formatCurrency(sMonth));
            setText(tvSalesYear, formatCurrency(sYear));
            
            if (priceManager != null) {
                displayGoldPricesFromCache();
            }
        } catch (Exception e) {
            Log.e(TAG, "loadOfflineData error: " + e.getMessage());
        }
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
                    Log.e(TAG, "saveOfflineData inner error: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "saveOfflineData error: " + e.getMessage());
        }
    }

    /**
     * ✅ جلب البيانات مع حماية من التحميل المتكرر
     */
    private void fetchData() {
        try {
            if (isLoading.get() || apiClient == null || !isAdded()) return;
            
            // ✅ منع التحميل المتكرر خلال 2 ثانية
            long now = System.currentTimeMillis();
            if (now - lastDataFetchTime < 2000 && isDataLoaded) {
                return;
            }
            
            isLoading.set(true);
            
            if (tvLiveStatus != null) {
                tvLiveStatus.setText(isArabic ? "جاري التحديث..." : "Updating...");
                tvLiveStatus.setTextColor(0xFFF59E0B);
            }
            
            cancelFuture(dataFuture);
            final int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            
            dataFuture = sharedExecutor.submit(() -> {
                try {
                    MiraApiClient.ApiResponse response = apiClient.getDashboardFull(activeBranchId);
                    mainHandler.post(() -> {
                        try {
                            isLoading.set(false);
                            lastDataFetchTime = System.currentTimeMillis();
                            if (!isAdded()) return;
                            
                            if (response != null && response.isSuccess && response.data != null) {
                                saveOfflineData(response.data);
                                processData(response.data);
                                isDataLoaded = true;
                            } else {
                                // ✅ Fallback: try fetching inventory directly
                                fetchInventoryDataDirectly(activeBranchId);
                            }
                            showShimmer(false);
                            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        } catch (Exception e) {}
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        try {
                            isLoading.set(false);
                            if (!isAdded()) return;
                            loadOfflineData();
                            showShimmer(false);
                            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        } catch (Exception e2) {}
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchData error: " + e.getMessage());
            isLoading.set(false);
        }
    }

    /**
     * ✅ جلب بيانات المخزون مباشرة إذا فشل الداشبورد الكامل
     */
    private void fetchInventoryDataDirectly(int branchId) {
        try {
            if (apiClient == null || branchId <= 0) {
                loadOfflineData();
                return;
            }
            
            sharedExecutor.execute(() -> {
                try {
                    MiraApiClient.ApiResponse response = apiClient.getBranchInventory(branchId);
                    mainHandler.post(() -> {
                        try {
                            if (response != null && response.isSuccess && response.data != null) {
                                int totalItems = response.data.optInt("total", 0);
                                double totalWeight = 0;
                                
                                if (response.data.has("items")) {
                                    org.json.JSONArray items = response.data.optJSONArray("items");
                                    if (items != null) {
                                        for (int i = 0; i < items.length(); i++) {
                                            JSONObject item = items.optJSONObject(i);
                                            if (item != null) {
                                                totalWeight += item.optDouble("gross_weight_grams", 0);
                                            }
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
                            } else {
                                loadOfflineData();
                            }
                        } catch (Exception e) {
                            loadOfflineData();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> loadOfflineData());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "fetchInventoryDataDirectly error: " + e.getMessage());
            loadOfflineData();
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
            Log.e(TAG, "processData error: " + e.getMessage());
        }
    }

    private void setupProfileListener() {
        try {
            if (orgProfile == null) return;
            profileListener = () -> mainHandler.post(this::updateHeader);
            orgProfile.addListener(profileListener);
        } catch (Exception e) {
            Log.e(TAG, "setupProfileListener error: " + e.getMessage());
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
            Log.e(TAG, "setupListeners error: " + e.getMessage());
        }
    }

    /**
     * ✅ التحديث الداخلي - يستدعى من الفрагمنتات الأخرى
     */
    public void refreshInternalData() {
        try {
            if (!isAdded()) return;
            
            // ✅ إعادة تعيين حالة التحميل
            isDataLoaded = false;
            lastDataFetchTime = 0;
            
            fetchData();
            displayGoldPricesFromCache();
        } catch (Exception e) {
            Log.e(TAG, "refreshInternalData error: " + e.getMessage());
        }
    }

    private void refreshAllData() {
        try {
            showShimmer(true);
            fetchData();
            
            long now = System.currentTimeMillis();
            if (permissionManager != null && permissionManager.canAccessFragment("GoldRates")) {
                if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                    fetchLiveGoldPrices(true);
                } else {
                    displayGoldPricesFromCache();
                    showShimmer(false);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "refreshAllData error: " + e.getMessage());
        }
    }

    private void showShimmer(boolean show) {
        try {
            if (shimmerLayout != null) shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (swipeRefresh != null) swipeRefresh.setVisibility(show ? View.GONE : View.VISIBLE);
        } catch (Exception e) {}
    }

    private void initBranchCache(int branchId) {
        try {
            if (getContext() != null) {
                offlineCache = requireContext().getSharedPreferences(OFFLINE_PREFS_BASE + branchId, Context.MODE_PRIVATE);
            }
        } catch (Exception e) {
            Log.e(TAG, "initBranchCache error: " + e.getMessage());
        }
    }

    /**
     * ✅ بدء التحديث التلقائي
     */
    private void startAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable);
        mainHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS);
    }

    /**
     * ✅ إيقاف التحديث التلقائي
     */
    private void stopAutoRefresh() {
        mainHandler.removeCallbacks(autoRefreshRunnable);
    }

    private void navigateToFragment(Fragment fragment, String tag) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(fragment, tag);
            }
        } catch (Exception e) {
            Log.e(TAG, "navigateToFragment error: " + e.getMessage());
        }
    }

    private String getFragmentTagForAction(String actionId) {
        try {
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
        } catch (Exception e) {
            return "";
        }
    }

    private void handleQuickAction(String actionId) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                Fragment fragment = createFragmentForAction(actionId);
                if (fragment != null) {
                    activity.loadFragment(fragment, getFragmentTagForAction(actionId));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "handleQuickAction error: " + e.getMessage());
        }
    }

    private Fragment createFragmentForAction(String actionId) {
        try {
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
        } catch (Exception e) {
            return null;
        }
    }

    private String getSourceDisplayName(String source) {
        try {
            if (source == null) return isArabic ? "غير معروف" : "Unknown";
            switch (source.toLowerCase()) {
                case "metalprice": return "MetalPrice";
                case "goldapi": return "GoldAPI";
                case "metalsdev": return "Metals.Dev";
                case "fallback": return isArabic ? "احتياطي" : "Fallback";
                default: return source;
            }
        } catch (Exception e) {
            return source;
        }
    }

    private String formatCurrency(double amount) {
        try {
            if (amount <= 0) return "—";
            if (priceManager != null) return priceManager.formatPriceDefault(amount);
            NumberFormat fmt = currencyFormatter.get();
            return fmt != null ? fmt.format(amount) : String.valueOf(amount);
        } catch (Exception e) {
            return "—";
        }
    }

    private String formatWeight(double weightInGrams) {
        try {
            if (weightInGrams <= 0) return "0.0 g";
            return weightInGrams >= 1000 
                ? String.format(Locale.US, "%.2f kg", weightInGrams / 1000) 
                : String.format(Locale.US, "%.1f g", weightInGrams);
        } catch (Exception e) {
            return "0.0 g";
        }
    }

    private void setGoldPrice(TextView tv, String label, double price) {
        try {
            if (tv == null) return;
            tv.setText(price > 0 ? String.format(Locale.US, "%s: $%,.2f/g", label, price) : label + ": —");
        } catch (Exception e) {}
    }

    private void setText(TextView tv, String text) {
        try {
            if (tv != null) tv.setText(text);
        } catch (Exception e) {}
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
            Log.e(TAG, "onBranchChanged error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            lastResumeTime = System.currentTimeMillis();
            
            if (branchPrefs != null) {
                branchPrefs.registerOnSharedPreferenceChangeListener(this);
            }
            
            int latestBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (latestBranchId != currentBranchId) {
                onBranchChanged();
            } else if (isViewInitialized) {
                setupQuickAccessGrid();
                updateHeader();
                
                // ✅ Always refresh data on resume
                refreshInternalData();
                
                long now = System.currentTimeMillis();
                if (permissionManager != null && permissionManager.canAccessFragment("GoldRates") 
                        && (now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                    fetchLiveGoldPrices(false);
                } else {
                    displayGoldPricesFromCache();
                }
            }
            
            startAutoRefresh();
        } catch (Exception e) {
            Log.e(TAG, "onResume error: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            stopAutoRefresh();
            
            if (branchPrefs != null) {
                branchPrefs.unregisterOnSharedPreferenceChangeListener(this);
            }
        } catch (Exception e) {}
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        try {
            if ("active_branch_id".equals(key) || "active_branch".equals(key)) {
                mainHandler.post(this::onBranchChanged);
            }
        } catch (Exception e) {}
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        try {
            if (rvQuickActions != null) {
                rvQuickActions.setItemViewCacheSize(0);
            }
        } catch (Exception e) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            stopAutoRefresh();
            
            if (orgProfile != null && profileListener != null) {
                orgProfile.removeListener(profileListener);
            }
            
            cancelFuture(dataFuture);
            cancelFuture(goldPriceFuture);
            cancelFuture(pingFuture);
            
            mainHandler.removeCallbacksAndMessages(null);
            isViewInitialized = false;
        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error: " + e.getMessage());
        }
    }

    private void cancelFuture(Future<?> future) {
        try {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        } catch (Exception e) {}
    }
}
