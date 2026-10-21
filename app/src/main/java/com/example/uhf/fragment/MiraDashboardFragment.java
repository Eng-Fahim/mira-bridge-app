package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.ApiProvider;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.RecentActivityCache;
import com.example.uhf.dialog.ActivityDetailsBottomSheet;

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

/**
 * MiraDashboardFragment v2.3 — FULL SECURITY & PRIVACY HARDENED
 *
 * ما تم في v2.3:
 *   ✅ إضافة زر إخفاء/إظهار لكرت المخزون حسب العيار (الخصوصية).
 *   ✅ إخفاء أرقام المخزون تلقائياً عند الخروج من التطبيق.
 *   ✅ تنسيق أيقونة العيار (الألماسة) في التصميم.
 */
public class MiraDashboardFragment extends Fragment {

    private static final String TAG = "MiraDashboardFragment";

    // ═══════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════
    private static final String OFFLINE_PREFS_BASE = "dashboard_cache_u";
    private static final String PREFS_CACHE_OWNER = "mira_dashboard_cache_owner";
    private static final String KEY_CACHE_OWNER_USER_ID = "cache_owner_user_id";
    private static final String KEY_PREFIX_LAST_BRANCH = "last_branch_id_";

    private static final double OUNCE_TO_GRAM = 31.1035;
    private static final int MAX_QUICK_ACTIONS = 9;
    private static final long CACHE_VALIDITY_MS = 2 * 60 * 1000;
    private static final long GOLD_CACHE_VALIDITY_MS = 12 * 60 * 60 * 1000;
    private static final long PING_VALIDITY_MS = 30 * 1000;
    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    private static final long SWIPE_REFRESH_TIMEOUT_MS = 15 * 1000;
    private static final long RESUME_REFETCH_THRESHOLD_MS = 2 * 60 * 1000;

    // ═══════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════
    private RecentActivityCache activityCache;
    private JSONObject lastSalesData = null;
    private boolean isHeroHidden = true;
    private boolean isSalesHidden = true;
    private boolean isInventoryHidden = true; // ✅ جديد: إخفاء المخزون افتراضياً

    // ───── UI Elements ─────
    private ImageButton btnToggleHeroVisibility;
    private ImageButton btnToggleSalesVisibility;
    private ImageButton btnToggleInventoryVisibility; // ✅ جديد

    // ✅ كرت المخزون حسب العيار
    private android.widget.LinearLayout llInventoryByKarat;
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

    private View heroBalanceCard, heroSparkline;
    private TextView tvHeroBalance, tvHeroTrend, tvHeroSubtitle;
    private TextView tvHeroValueYER, tvHeroValueSAR, tvHeroValueUSD;
    private View alertsStrip;
    private TextView tvAlertIcon, tvAlertMessage, tvAlertAction;
    private View liveStatusDot;
    private TextView tvLiveStatusText;
    private TextView tvHeroItemCount;

    private android.widget.LinearLayout llRecentActivity;
    private TextView tvViewAllActivity;

    // ───── Managers & State ─────
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
    // Inner classes
    // ═══════════════════════════════════════════════════════
    private static class KaratInventory {
        final int karat;
        final int itemCount;
        final double weight;

        KaratInventory(int karat, int itemCount, double weight) {
            this.karat = karat;
            this.itemCount = itemCount;
            this.weight = weight;
        }
    }

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
    // Security helpers
    // ═══════════════════════════════════════════════════════
    private boolean isSessionValid() {
        return sessionManager != null && sessionManager.getUserId() > 0;
    }

    private boolean ensureExecutor() {
        if (localExecutor == null || localExecutor.isShutdown()) {
            if (!isAdded()) return false;
            localExecutor = Executors.newFixedThreadPool(4);
        }
        return true;
    }

    private void shutdownExecutor() {
        if (localExecutor != null) {
            try { localExecutor.shutdownNow(); } catch (Exception ignored) {}
            localExecutor = null;
        }
    }

    private void ensureCacheOwnerIsCurrentUser() {
        try {
            if (sessionManager == null) return;
            Context ctx = getSafeContext();
            if (ctx == null) return;

            int currentUserId = sessionManager.getUserId();
            if (currentUserId <= 0) return; 

            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(PREFS_CACHE_OWNER, Context.MODE_PRIVATE);
            int ownerUserId = prefs.getInt(KEY_CACHE_OWNER_USER_ID, 0);

            if (ownerUserId > 0 && ownerUserId != currentUserId) {
                if (activityCache != null) activityCache.clear();
                if (goldCachePrefs != null) goldCachePrefs.edit().clear().apply();
                // في دالة ensureCacheOwnerIsCurrentUser
                if (goldCachePrefs != null) goldCachePrefs.edit().clear().apply();
                if (priceManager != null) priceManager.clearCache(); // (إذا كانت هذه الدالة موجودة في MiraPriceManager)

                int oldBranch = prefs.getInt(KEY_PREFIX_LAST_BRANCH + ownerUserId, -1);
                if (oldBranch > 0) {
                    try {
                        String oldName = OFFLINE_PREFS_BASE + ownerUserId + "_b" + oldBranch;
                        createSecurePrefs(ctx, oldName).edit().clear().apply();
                    } catch (Exception ignored) {}
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "Caches cleared after user switch");
            }

            prefs.edit().putInt(KEY_CACHE_OWNER_USER_ID, currentUserId).apply();
        } catch (Exception e) {
            logNonFatal("ensureCacheOwnerIsCurrentUser", e);
        }
    }

    @Nullable
    private Context getSafeContext() {
        try { return getContext(); } catch (Exception e) { return null; }
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
            Class<?> keyEncClass = Class.forName(
                    "androidx.security.crypto.EncryptedSharedPreferences$PrefKeyEncryptionScheme");
            Class<?> valEncClass = Class.forName(
                    "androidx.security.crypto.EncryptedSharedPreferences$PrefValueEncryptionScheme");
            Object keyEnc = Enum.valueOf((Class<Enum>) keyEncClass, "AES256_SIV");
            Object valEnc = Enum.valueOf((Class<Enum>) valEncClass, "AES256_GCM");

            Method create = espClass.getMethod("create",
                    Context.class, String.class, masterKeyClass, keyEncClass, valEncClass);

            return (android.content.SharedPreferences) create.invoke(
                    null, ctx, name, masterKey, keyEnc, valEnc);
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "EncryptedSharedPreferences unavailable: "
                        + t.getClass().getSimpleName() + " — using plain prefs");
            }
            return null;
        }
    }

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

            currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            initBranchCache(currentBranchId);

            updateHeader();
            showShimmer(false);

            isViewInitialized = true;

            View heroCard = view.findViewById(R.id.heroBalanceCard);
            if (heroCard != null) {
                heroCard.setAlpha(0f);
                heroCard.setTranslationY(30f);
                heroCard.animate().alpha(1f).translationY(0f).setDuration(400).start();
            }

            loadOfflineData();

            if (isAdded() && llRecentActivity != null && activityCache != null) {
                try {
                    JSONArray cached = activityCache.load();
                    if (cached != null && cached.length() > 0) {
                        updateRecentActivityUI(cached);
                        Log.d(TAG, "Restored activities from cache: " + cached.length());
                    }
                } catch (Exception e) {
                    logNonFatal("onViewCreated.restoreActivities", e);
                }
            }

            mainHandler.postDelayed(() -> {
                if (isAdded() && isResumed()) updateLiveStatus();
            }, 2000);

            syncOrgProfileFromServer(false);
            startAutoRefresh();

            lastDataFetchTime = 0;

        } catch (Throwable e) {
            logNonFatal("onViewCreated", e);
        }
    }

    private void initViews(View v) {
        try {
            llInventoryByKarat = v.findViewById(R.id.llInventoryByKarat);
            tvKaratEmptyState  = v.findViewById(R.id.tvKaratEmptyState);

            tvSalesToday = v.findViewById(R.id.tvSalesToday);
            tvSalesMonth = v.findViewById(R.id.tvSalesMonth);
            tvSalesYear  = v.findViewById(R.id.tvSalesYear);
            tvKarat24 = v.findViewById(R.id.tvGoldPrice24);
            tvKarat21 = v.findViewById(R.id.tvGoldPrice21);
            tvKarat18 = v.findViewById(R.id.tvGoldPrice18);

            shimmerLayout = v.findViewById(R.id.shimmerLayout);
            swipeRefresh  = v.findViewById(R.id.swipeRefresh);
            tvOuncePrice  = v.findViewById(R.id.tvOuncePrice);
            tvPriceSource = v.findViewById(R.id.tvPriceSource);
            tvLastUpdate  = v.findViewById(R.id.tvLastUpdate);
            tvGoldTrend   = v.findViewById(R.id.tvGoldTrend);
            tvGoldChange  = v.findViewById(R.id.tvGoldChange);
            btnRefreshGold = v.findViewById(R.id.btnRefreshGold);
            cardGoldPrices = v.findViewById(R.id.cardGoldPrices);
            loadingOverlay = v.findViewById(R.id.loadingOverlay);
            pbGoldLoading  = v.findViewById(R.id.pbGoldLoading);

            heroBalanceCard = v.findViewById(R.id.heroBalanceCard);
            heroSparkline   = v.findViewById(R.id.heroSparkline);
            tvHeroBalance   = v.findViewById(R.id.tvHeroBalance);
            tvHeroTrend     = v.findViewById(R.id.tvHeroTrend);
            tvHeroSubtitle  = v.findViewById(R.id.tvHeroSubtitle);
            tvHeroValueYER  = v.findViewById(R.id.tvHeroValueYER);
            tvHeroValueSAR  = v.findViewById(R.id.tvHeroValueSAR);
            tvHeroValueUSD  = v.findViewById(R.id.tvHeroValueUSD);
            tvHeroItemCount = v.findViewById(R.id.tvHeroItemCount);

            alertsStrip   = v.findViewById(R.id.alertsStrip);
            tvAlertIcon   = v.findViewById(R.id.tvAlertIcon);
            tvAlertMessage = v.findViewById(R.id.tvAlertMessage);
            tvAlertAction = v.findViewById(R.id.tvAlertAction);

            btnToggleHeroVisibility  = v.findViewById(R.id.btnToggleHeroVisibility);
            btnToggleSalesVisibility = v.findViewById(R.id.btnToggleSalesVisibility);
            btnToggleInventoryVisibility = v.findViewById(R.id.btnToggleInventoryVisibility); // ✅ جديد

            llRecentActivity = v.findViewById(R.id.llRecentActivity);
            tvViewAllActivity = v.findViewById(R.id.tvViewAllActivity);
            if (tvViewAllActivity != null) {
                tvViewAllActivity.setOnClickListener(v2 ->
                        navigateToFragment(new MiraAllActivitiesFragment(), "AllActivities"));
            }

            if (btnRefreshGold != null) {
                if (!canManuallyRefreshGold()) {
                    btnRefreshGold.setVisibility(View.GONE);
                    Log.d(TAG, "Gold refresh button hidden (no permission)");
                } else {
                    btnRefreshGold.setVisibility(View.VISIBLE);
                    btnRefreshGold.setOnClickListener(v2 -> {
                        if (!isGoldRefreshing.get()) fetchLiveGoldPrices(true);
                    });
                }
            }

            if (btnToggleHeroVisibility != null)
                btnToggleHeroVisibility.setOnClickListener(v2 -> toggleHeroVisibility());
            if (btnToggleSalesVisibility != null)
                btnToggleSalesVisibility.setOnClickListener(v2 -> toggleSalesVisibility());
            
            // ✅ ربط زر إخفاء المخزون
            if (btnToggleInventoryVisibility != null)
                btnToggleInventoryVisibility.setOnClickListener(v2 -> toggleInventoryVisibility());

            if (tvAlertAction != null)
                tvAlertAction.setOnClickListener(v2 ->
                        navigateToFragment(new MiraUHFReadTagFragment(), "Scan"));

            if (cardGoldPrices != null && permissionManager != null
                    && !permissionManager.canAccessFragment("GoldRates")) {
                cardGoldPrices.setVisibility(View.GONE);
            }

            if (swipeRefresh != null) {
                swipeRefresh.setOnRefreshListener(this::refreshAllData);
                swipeRefresh.setColorSchemeResources(R.color.colorPrimary, R.color.gray1);
            }

            isHeroHidden = true;
            isSalesHidden = true;
            isInventoryHidden = true; // ✅ مخفي افتراضياً

            if (btnToggleHeroVisibility != null)
                btnToggleHeroVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleSalesVisibility != null)
                btnToggleSalesVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleInventoryVisibility != null)
                btnToggleInventoryVisibility.setImageResource(R.drawable.ic_eye_off);

            updateHeroBalance();
            applySalesVisibility();

        } catch (Exception e) {
            logNonFatal("initViews", e);
        }
    }

    private void updateHeader() {
        // Greeting Card محذوف — يمكن إضافته لاحقاً
    }

    // ═══════════════════════════════════════════════════════
    // Inventory by Karat
    // ═══════════════════════════════════════════════════════
    private void renderInventoryByKarat(List<KaratInventory> items) {
        if (llInventoryByKarat == null) return;
        try {
            llInventoryByKarat.removeAllViews();

            if (items == null || items.isEmpty()) {
                if (tvKaratEmptyState != null)
                    tvKaratEmptyState.setVisibility(View.VISIBLE);
                llInventoryByKarat.setVisibility(View.GONE);
                return;
            }

            if (tvKaratEmptyState != null) tvKaratEmptyState.setVisibility(View.GONE);
            llInventoryByKarat.setVisibility(View.VISIBLE);

            // ترتيب تنازلي (24K أولاً)
            List<KaratInventory> sorted = new ArrayList<>(items);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.karat, a.karat));

            for (KaratInventory inv : sorted) {
                View itemView = getLayoutInflater().inflate(
                        R.layout.item_karat_inventory, llInventoryByKarat, false);

                TextView tvKarat  = itemView.findViewById(R.id.tvKaratLabel);
                TextView tvCount  = itemView.findViewById(R.id.tvKaratCount);
                TextView tvWeight = itemView.findViewById(R.id.tvKaratWeight);

                if (tvKarat != null) {
                    tvKarat.setText(inv.karat + "K");
                    try {
                        tvKarat.setTextColor(getKaratColor(inv.karat));
                    } catch (Exception ignored) {}
                }

                // ✅ تطبيق الخصوصية: إخفاء الأرقام
                if (tvCount != null) {
                    tvCount.setText(isInventoryHidden ? "••" : String.valueOf(inv.itemCount));
                }
                if (tvWeight != null) {
                    tvWeight.setText(isInventoryHidden ? "•• g" : formatWeight(inv.weight));
                }

                llInventoryByKarat.addView(itemView);
            }
        } catch (Exception e) {
            logNonFatal("renderInventoryByKarat", e);
        }
    }

    private int getKaratColor(int karat) {
        switch (karat) {
            case 24: return 0xFFB8860B;
            case 22: return 0xFFDAA520;
            case 21: return 0xFFE6B800;
            case 18: return 0xFFCD853F;
            default: return 0xFFA0522D;
        }
    }

    // ═══════════════════════════════════════════════════════
    // Hero Balance & Toggles
    // ═══════════════════════════════════════════════════════
    private void updateHeroBalance() {
    try {
        if (tvHeroBalance == null) return;

        double totalValue = currentInventoryValue;
        int itemCount = currentItemCount;
        double totalWeight = currentTotalWeight;

        if (totalValue <= 0 && priceManager != null && totalWeight > 0) {
            double price24 = priceManager.getGoldPricePerGram(24);
            if (price24 > 0) totalValue = totalWeight * price24;
        }

        if (isHeroHidden) {
            tvHeroBalance.setText("••••••••");
            // ✅ إخفاء الوزن وعدد القطع لمنع حساب القيمة
            if (tvHeroSubtitle != null) tvHeroSubtitle.setText("••• g");
            if (tvHeroItemCount != null) tvHeroItemCount.setText("••• قطعة");
        } else {
            tvHeroBalance.setText(String.format(Locale.US, "$%,.2f", totalValue));
            // ✅ إظهار الوزن وعدد القطع فقط عند إظهار القيمة
            if (tvHeroSubtitle != null)
                tvHeroSubtitle.setText(String.format(Locale.US, "%.1f g", totalWeight));
            if (tvHeroItemCount != null)
                tvHeroItemCount.setText(String.format(Locale.getDefault(), "%d قطعة", itemCount));
        }

        String usdText = isHeroHidden ? "••••••" : String.format(Locale.US, "%,.2f", totalValue);
        if (tvHeroValueUSD != null) tvHeroValueUSD.setText(usdText);

        String sarText = isHeroHidden ? "••••••" : String.format(Locale.US, "%,.2f", totalValue * 3.75);
        if (tvHeroValueSAR != null) tvHeroValueSAR.setText(sarText);

        String yerText = isHeroHidden ? "••••••" : String.format(Locale.US, "%,.0f", totalValue * 530.0);
        if (tvHeroValueYER != null) tvHeroValueYER.setText(yerText);

    } catch (Exception e) {
        logNonFatal("updateHeroBalance", e);
    }
}
    private void toggleHeroVisibility() {
        if (!isAdded() || tvHeroBalance == null) return;
        isHeroHidden = !isHeroHidden;
        if (btnToggleHeroVisibility != null) {
            btnToggleHeroVisibility.setImageResource(
                    isHeroHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
        }
        updateHeroBalance();
    }

    private void toggleSalesVisibility() {
        if (!isAdded() || tvSalesToday == null) return;
        isSalesHidden = !isSalesHidden;
        if (btnToggleSalesVisibility != null) {
            btnToggleSalesVisibility.setImageResource(
                    isSalesHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
        }
        applySalesVisibility();
    }

    // ✅ دالة تبديل إخفاء/إظهار المخزون حسب العيار
    private void toggleInventoryVisibility() {
        if (!isAdded()) return;
        isInventoryHidden = !isInventoryHidden;
        if (btnToggleInventoryVisibility != null) {
            btnToggleInventoryVisibility.setImageResource(
                    isInventoryHidden ? R.drawable.ic_eye_off : R.drawable.ic_eye_on);
        }
        renderInventoryByKarat(currentInventoryByKarat);
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
        setText(tvSalesYear,  formatCurrency(sales.optDouble("year",  0.0)));
    }

    // ═══════════════════════════════════════════════════════
    // Alerts / Status
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
                            ? "عرض (" + count + ") →" : "View (" + count + ") →");
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
                int itemsWithoutPrice  = inv.optInt("items_without_price", -1);

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

            if (alertsCount > 0) updateAlerts(alertsCount, message.toString());
            else updateAlerts(0, null);
        } catch (Exception e) {
            logNonFatal("computeAndShowAlerts", e);
        }
    }

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

    // ═══════════════════════════════════════════════════════
    // Sync
    // ═══════════════════════════════════════════════════════
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
                    Log.w(TAG, "Org sync failed: " + error);
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
                @Override public void onSuccess() { Log.i(TAG, "Branches synced"); }
                @Override public void onError(String error) { Log.w(TAG, "Branches sync: " + error); }
            });
        } catch (Exception e) {
            logNonFatal("syncBranchesListFromServer", e);
        }
    }

    private void updateLiveStatus() {
        try {
            if (apiClient == null) return;
            if (!ensureExecutor()) return;
            cancelFuture(pingFuture);
            pingFuture = localExecutor.submit(() -> {
                boolean isOnline = apiClient.pingApi();
                mainHandler.post(() -> updateLiveStatusText(isOnline));
            });
        } catch (Exception e) {
            logNonFatal("updateLiveStatus", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Gold prices
    // ═══════════════════════════════════════════════════════
   
    private void fetchLiveGoldPrices(boolean forceRefresh) {
        try {
            if (!canManuallyRefreshGold()) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
            // ✅ التحقق من الصلاحية قبل أي شيء
        if (permissionManager == null || !permissionManager.canAccessFragment("GoldRates")) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
        
        if (apiClient == null) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
            if (isGoldRefreshing.get()) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }
            if (!ensureExecutor()) {
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

            final Context appCtx = requireContext().getApplicationContext();

            goldPriceFuture = localExecutor.submit(() -> {
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
                            if (forceRefresh) {
                                Toast.makeText(appCtx,
                                        "✅ تم تحديث أسعار الذهب", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            displayGoldPricesFromCache();
                            Toast.makeText(appCtx,
                                    isArabic ? "⚠️ تعذر التحديث - عرض الكاش"
                                            : "⚠️ Update failed - showing cache",
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
            if (pbGoldLoading != null)
                pbGoldLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
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
        
        // ✅ التحقق من صحة الأسعار (لا تقبل الصفر أو القيم السالبة)
        if (price24 <= 0 || price24 > 5000) return null; 
        
        double ouncePrice = data.optDouble("price_per_ounce", price24 * OUNCE_TO_GRAM);
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
                String time = getTimeFormatter().format(new Date());
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
                    long hours   = timeRemaining / (60 * 60 * 1000);
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
                    String time = getTimeFormatter().format(new Date(lastUpdate));
                    tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
                }
            }

            updateHeroBalance();
        } catch (Exception e) {
            logNonFatal("displayGoldPricesFromCache", e);
        }
    }
    
    // ✅ دالة جديدة: تجلب الأسعار المعتمدة من سيرفرك (بدون استهلاك الـ API الخارجي)
private void fetchApprovedGoldPricesFromServer() {
    if (apiClient == null || !isAdded() || !isSessionValid()) return;
    if (!ensureExecutor()) return;

    localExecutor.execute(() -> {
        try {
            MiraApiClient.ApiResponse response = apiClient.getDashboardGoldPrices();
            
            if (response != null && response.isSuccess && response.data != null) {
                JSONObject prices = response.data.optJSONObject("prices");
                if (prices != null) {
                    double price24 = prices.optDouble("24k", 0);
                    double price21 = prices.optDouble("21k", 0);
                    double price18 = prices.optDouble("18k", 0);
                    String source = response.data.optString("source", "server");
                    
                    if (price24 > 0) {
                        GoldPriceData priceData = new GoldPriceData(price24, price21, price18, price24 * OUNCE_TO_GRAM, source);
                        
                        mainHandler.post(() -> {
                            if (isAdded()) {
                                displayGoldPrices(priceData);
                                // 💡 اختياري: إذا كان MiraPriceManager يدعم الحفظ المحلي، احفظها هنا
                                // priceManager.saveLocalGoldPrices(price24, price21, price18);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            logNonFatal("fetchApprovedGoldPricesFromServer", e);
        }
    });
}

    private void calculateTrend(double currentPrice) {
        if (tvGoldTrend == null || tvGoldChange == null || offlineCache == null) return;
        if (!ensureExecutor()) return;

        localExecutor.execute(() -> {
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
    // Offline cache
    // ═══════════════════════════════════════════════════════
    private void loadOfflineData() {
        if (offlineCache == null) return;
        if (!isSessionValid()) return;
        if (!ensureExecutor()) return;

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
                            if (k == null) continue;
                            int karat = k.optInt("karat", 0);
                            if (karat > 0) {
                                karatItems.add(new KaratInventory(
                                        karat,
                                        k.optInt("count", 0),
                                        k.optDouble("weight", 0.0)));
                            }
                        }
                    } catch (Exception ignored) {}
                }

                mainHandler.post(() -> {
                    if (!isAdded()) return;

                    currentItemCount = totalItems;
                    currentTotalWeight = totalWeight;
                    currentInventoryValue = inventoryValue;
                    updateHeroBalance();

                    currentInventoryByKarat.clear();
                    currentInventoryByKarat.addAll(karatItems);
                    renderInventoryByKarat(currentInventoryByKarat);

                    if (isSalesHidden) {
                        setText(tvSalesToday, "••••••");
                        setText(tvSalesMonth, "••••••");
                        setText(tvSalesYear,  "••••••");
                    } else {
                        setText(tvSalesToday, formatCurrency(salesToday));
                        setText(tvSalesMonth, formatCurrency(salesMonth));
                        setText(tvSalesYear,  formatCurrency(salesYear));
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
            if (!isSessionValid()) return;
            if (!ensureExecutor()) return;

            localExecutor.execute(() -> {
                try {
                    JSONObject inv   = data.optJSONObject("inventory");
                    JSONObject sales = data.optJSONObject("sales");

                    android.content.SharedPreferences.Editor editor = offlineCache.edit()
                            .putInt("total_items", inv != null ? inv.optInt("total_items", 0) : 0)
                            .putFloat("total_weight", (float) (inv != null ? inv.optDouble("total_weight", 0.0) : 0.0))
                            .putFloat("inventory_value", (float) (inv != null ? inv.optDouble("total_value", 0.0) : 0.0))
                            .putInt("sold_today", sales != null ? sales.optInt("count_today", 0) : 0)
                            .putFloat("sales_today", (float) (sales != null ? sales.optDouble("today", 0.0) : 0.0))
                            .putFloat("sales_month", (float) (sales != null ? sales.optDouble("month", 0.0) : 0.0))
                            .putFloat("sales_year",  (float) (sales != null ? sales.optDouble("year",  0.0) : 0.0));

                    if (inv != null && inv.optJSONArray("by_karat") != null) {
                        editor.putString("inventory_by_karat",
                                inv.optJSONArray("by_karat").toString());
                    }

                    editor.putLong("last_update", System.currentTimeMillis()).apply();
                } catch (Exception e) {
                    logNonFatal("saveOfflineData.inner", e);
                }
            });
        } catch (Exception e) {
            logNonFatal("saveOfflineData", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // fetchData — race-safe
    // ═══════════════════════════════════════════════════════
    private void fetchData() {
        try {
            if (apiClient == null || !isAdded() || !isSessionValid()) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }
            if (!ensureExecutor()) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }

            if (!isLoading.compareAndSet(false, true)) {
                Log.d(TAG, "fetchData skipped — already loading");
                return;
            }

            boolean isGlobalAdmin = sessionManager != null && sessionManager.isAnyAdmin();
            final int finalBranchId;
            if (isGlobalAdmin) {
                finalBranchId = (currentBranchId > 0) ? currentBranchId : 0;
            } else {
                int fromSession = sessionManager != null ? sessionManager.getBranchId() : 0;
                int fromManager = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                finalBranchId = fromSession > 0 ? fromSession : (fromManager > 0 ? fromManager : 1);
            }

            if (finalBranchId <= 0 && !isGlobalAdmin) {
                Log.w(TAG, "Skipping fetch — no valid branch");
                isLoading.set(false);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                return;
            }

            cancelFuture(dataFuture);

            dataFuture = localExecutor.submit(() -> {
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
                double value  = inv.optDouble("total_value", 0.0);

                currentItemCount = itemCount;
                currentTotalWeight = weight;
                currentInventoryValue = value;
                updateHeroBalance();

                JSONArray byKarat = inv.optJSONArray("by_karat");
                List<KaratInventory> karatItems = new ArrayList<>();
                if (byKarat != null) {
                    for (int i = 0; i < byKarat.length(); i++) {
                        JSONObject k = byKarat.optJSONObject(i);
                        if (k == null) continue;
                        int karat = k.optInt("karat", 0);
                        if (karat > 0) {
                            karatItems.add(new KaratInventory(
                                    karat,
                                    k.optInt("count", 0),
                                    k.optDouble("weight", 0.0)));
                        }
                    }
                }
                currentInventoryByKarat.clear();
                currentInventoryByKarat.addAll(karatItems);
                renderInventoryByKarat(currentInventoryByKarat);
            }

            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                lastSalesData = sales;
                applySalesData(sales);
            }
        } catch (Exception e) {
            logNonFatal("processData", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Recent Activity
    // ═══════════════════════════════════════════════════════
    private void fetchRecentActivity() {
        if (apiClient == null || !isAdded() || !isSessionValid()) return;
        if (!ensureExecutor()) return;

        if (activityCache != null) {
            JSONArray cached = activityCache.load();
            if (cached != null && cached.length() > 0) {
                updateRecentActivityUI(cached);
                Log.d(TAG, "Displayed activities from cache: " + cached.length());
            }
        }

        if (activityCache != null && activityCache.isValid()) {
            Log.d(TAG, "Activity cache valid, skipping network");
            return;
        }

        localExecutor.execute(() -> {
            try {
                int branchId = branchesManager != null
                        ? branchesManager.getActiveBranchId() : 0;

                MiraApiClient.ApiResponse response =
                        apiClient.getUnifiedActivity(branchId, 15);

                mainHandler.post(() -> {
                    if (!isAdded() || response == null) return;

                    if (response.isSuccess && response.data != null) {
                        try {
                            JSONArray activities = response.data.optJSONArray("activities");
                            if (activities != null && activities.length() > 0) {
                                if (activityCache != null) activityCache.save(activities);
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

    private void updateRecentActivityUI(JSONArray activities) {
        if (activities == null || llRecentActivity == null) return;

        try {
            llRecentActivity.removeAllViews();

            int max = Math.min(activities.length(), 10);
            Log.d(TAG, "Rendering activities: " + max);

            for (int i = 0; i < max; i++) {
                JSONObject item = activities.getJSONObject(i);

                View itemView = getLayoutInflater().inflate(
                        R.layout.item_activity_timeline, llRecentActivity, false);

                bindActivityToView(itemView, item);
                itemView.setOnClickListener(v -> handleRecentActivityClick(item));
                llRecentActivity.addView(itemView);
            }
        } catch (Exception e) {
            logNonFatal("updateRecentActivityUI", e);
        }
    }

    private void bindActivityToView(View v, JSONObject item) {
        if (v == null || item == null) return;

        try {
            TextView tvIcon       = v.findViewById(R.id.tvActivityIcon);
            TextView tvTitle      = v.findViewById(R.id.tvActivityTitle);
            TextView tvDetails    = v.findViewById(R.id.tvActivityDetails);
            TextView tvUser       = v.findViewById(R.id.tvActivityUser);
            TextView tvTime       = v.findViewById(R.id.tvActivityTime);
            TextView tvAmount     = v.findViewById(R.id.tvActivityAmount);
            TextView tvStatusChip = v.findViewById(R.id.tvActivityStatusChip);

            String icon  = item.optString("icon", "⚙️");
            String title = isArabic
                    ? item.optString("title_ar", "") : item.optString("title_en", "");
            if (title.isEmpty()) {
                title = item.optString("title_ar", item.optString("title_en", ""));
            }

            String details = isArabic
                    ? item.optString("details_ar", "") : item.optString("details_en", "");
            if (details.isEmpty()) {
                details = item.optString("details_ar", item.optString("details_en", ""));
            }

            String user      = item.optString("user", "—");
            String timestamp = item.optString("timestamp", "");
            double amount    = item.optDouble("amount", 0);
            String currency  = item.optString("currency", "USD");
            double accuracy  = item.optDouble("accuracy", -1);

            if (tvIcon != null) tvIcon.setText(icon);
            if (tvTitle != null) tvTitle.setText(title);
            if (tvDetails != null) tvDetails.setText(details);
            if (tvUser != null) tvUser.setText("👤 " + user);
            if (tvTime != null) tvTime.setText(extractTime(timestamp));

            if (tvAmount != null) {
                if (amount > 0) {
                    tvAmount.setVisibility(View.VISIBLE);
                    tvAmount.setText(String.format(Locale.US, "%,.2f %s", amount, currency));
                } else if (accuracy >= 0) {
                    tvAmount.setVisibility(View.VISIBLE);
                    tvAmount.setText(String.format(Locale.US, "🎯 %.1f%%", accuracy));
                } else {
                    tvAmount.setVisibility(View.GONE);
                }
            }

            if (tvStatusChip != null) {
                String status = item.optString("status", "neutral");
                if ("danger".equals(status)) {
                    tvStatusChip.setVisibility(View.VISIBLE);
                    tvStatusChip.setText(isArabic ? "⚠️ حرج" : "⚠️ Critical");
                    tvStatusChip.setTextColor(0xFFDC2626);
                } else if ("warning".equals(status)) {
                    tvStatusChip.setVisibility(View.VISIBLE);
                    tvStatusChip.setText(isArabic ? "⚠️ تحذير" : "⚠️ Warning");
                    tvStatusChip.setTextColor(0xFFF59E0B);
                } else {
                    tvStatusChip.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "bindActivityToView error", e);
        }
    }

    private String extractTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return "";
        try {
            String[] parts = dateStr.split(" ");
            if (parts.length >= 2) {
                String[] timeParts = parts[1].split(":");
                if (timeParts.length >= 2) return timeParts[0] + ":" + timeParts[1];
            }
        } catch (Exception ignored) {}
        return dateStr;
    }

    private void handleRecentActivityClick(JSONObject activity) {
    if (activity == null || !isAdded()) return;
    try {
        ActivityDetailsBottomSheet sheet =
                ActivityDetailsBottomSheet.newInstance(activity, isArabic);

        // ✅ ربط الأزرار بالأفعال
        sheet.setOnActionClickListener(new ActivityDetailsBottomSheet.OnActionClickListener() {
            
            @Override
            public void onPrintInvoice(JSONObject activityData) {
    if (!isAdded() || getContext() == null) return;
    
    try {
        // 💡 نمرر بيانات العملية كـ JSON String إلى واجهة الفاتورة
        String receiptJson = activityData.toString();
        
        // استدعاء واجهة الفاتورة (نفس الواجهة المستخدمة في شاشة المبيعات)
        com.example.uhf.fragment.MiraReceiptFragment receiptFragment = 
                com.example.uhf.fragment.MiraReceiptFragment.newInstance(receiptJson);
        
        androidx.fragment.app.FragmentManager fm = getParentFragmentManager();
        if (fm != null && !fm.isStateSaved()) {
            receiptFragment.show(fm, "MiraReceiptFragment");
        }
    } catch (Exception e) {
        Log.e(TAG, "onPrintInvoice error", e);
        Toast.makeText(getContext(), "تعذر فتح الفاتورة", Toast.LENGTH_SHORT).show();
    }
}

            @Override
            public void onOpenRefund(JSONObject activityData) {
                if (!isAdded() || getActivity() == null) return;
                
                try {
                    // إنشاء واجهة المبيعات
                    Fragment salesFragment = new MiraPosFragment(); 
                    
                    // تجهيز البيانات لفتح ديالوج الاسترجاع
                    Bundle bundle = new Bundle();
                    bundle.putString("refund_data_json", activityData.toString());
                    bundle.putBoolean("auto_open_refund", true);
                    salesFragment.setArguments(bundle);

                    // الانتقال للشاشة
                    if (getActivity() instanceof UHFMainActivity) {
                        ((UHFMainActivity) getActivity()).loadFragment(salesFragment, "Sales"); // تأكد من التاج
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error opening refund", e);
                }
            }
        });

        sheet.show(getParentFragmentManager(), "ActivityDetails");
    } catch (Exception e) {
        logNonFatal("handleRecentActivityClick", e);
    }
}
    // ═══════════════════════════════════════════════════════
    // Receiver
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
    // Refresh entry points
    // ═══════════════════════════════════════════════════════
    public void refreshInternalData() {
    try {
        if (!isAdded() || !isSessionValid()) return;
        isDataLoaded = false;
        lastDataFetchTime = 0;
        isLoading.set(false);
        fetchData();
        fetchRecentActivity();

        long now = System.currentTimeMillis();
        
        // ✅ الأفضل هنا استخدام canManuallyRefreshGold ليكون المنطق واضحاً
        if (canManuallyRefreshGold()) {
            if (lastGoldFetchTime == 0 || (now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                fetchLiveGoldPrices(false);
            } else {
                displayGoldPricesFromCache();
            }
        } else {
            // المستخدم العادي: يعرض الكاش فوراً
            displayGoldPricesFromCache();
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
                    Log.w(TAG, "SwipeRefresh timeout — forcing stop");
                    swipeRefresh.setRefreshing(false);
                }
            };
            mainHandler.postDelayed(swipeTimeoutRunnable, SWIPE_REFRESH_TIMEOUT_MS);

            fetchData();
            fetchRecentActivity();

            long now = System.currentTimeMillis();

            if (canManuallyRefreshGold()) {
                if ((now - lastGoldFetchTime) > GOLD_CACHE_VALIDITY_MS) {
                    fetchLiveGoldPrices(true);
                } else {
    // ✅ الكاشير: يعرض الكاش ثم يجلب المعتمد من سيرفرك
    displayGoldPricesFromCache();
    fetchApprovedGoldPricesFromServer();
    updateLiveStatusText(true);
    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
}
            } else {
                displayGoldPricesFromCache();
                updateLiveStatusText(true);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        } catch (Exception e) {
            logNonFatal("refreshAllData", e);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        }
    }

    private void showShimmer(boolean show) {
        try {
            if (shimmerLayout != null)
                shimmerLayout.setVisibility(show ? View.VISIBLE : View.GONE);
            if (loadingOverlay != null)
                loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            if (swipeRefresh != null)
                swipeRefresh.setVisibility(show ? View.GONE : View.VISIBLE);
        } catch (Exception e) {
            logNonFatal("showShimmer", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Cache init
    // ═══════════════════════════════════════════════════════
    private void initBranchCache(int branchId) {
        try {
            if (getContext() == null || sessionManager == null) return;
            int userId = sessionManager.getUserId();
            if (userId <= 0) return;

            String prefsName = OFFLINE_PREFS_BASE + userId + "_b" + branchId;
            offlineCache = createSecurePrefs(requireContext(), prefsName);

            try {
                requireContext()
                        .getSharedPreferences(PREFS_CACHE_OWNER, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_PREFIX_LAST_BRANCH + userId, branchId)
                        .apply();
            } catch (Exception ignored) {}
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

    // ═══════════════════════════════════════════════════════
    // Navigation
    // ═══════════════════════════════════════════════════════
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
            case "identity":       return "IdentityStudio";
            case "radar":          return "Radar";
            case "gate":           return "SecureGate";
            case "reports":        return "Reports";
            case "accounting":     return "Accounting";
            case "transfers":      return "Transfers";
            case "admin_hub":      return "AdminHub";
            case "sales_history":  return "SalesHistory";
            case "server_config":  return "ServerConfig";
            case "gold_rates":     return "GoldRates";
            case "users":          return "UsersManagement";
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
            try { fragment = activity.createFragmentByTag(tag); }
            catch (Exception e) { Log.e(TAG, "Error creating fragment: " + e.getMessage(), e); }

            if (fragment == null) {
                Toast.makeText(getContext(),
                        isArabic ? "⚠️ تعذر فتح هذه الواجهة" : "⚠️ Cannot open this screen",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            activity.loadFragment(fragment, tag);
        } catch (Exception e) {
            logNonFatal("handleQuickAction", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Formatting
    // ═══════════════════════════════════════════════════════
    private String getSourceDisplayName(String source) {
        if (source == null) return isArabic ? "غير معروف" : "Unknown";
        switch (source.toLowerCase()) {
            case "metalprice": return "MetalPrice";
            case "goldapi":    return "GoldAPI";
            case "metalsdev":  return "Metals.Dev";
            case "fallback":   return isArabic ? "احتياطي" : "Fallback";
            default: return source;
        }
    }

    private String formatCurrency(double amount) {
        try {
            if (amount <= 0) return "—";
            if (priceManager != null) return priceManager.formatPriceDefault(amount);
            return getCurrencyFormatter().format(amount);
        } catch (Exception e) { return "—"; }
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
    // Branch change
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

                if (offlineCache == null || offlineCache.getInt("total_items", 0) == 0) {
                    showShimmer(true);
                }

                fetchData();
                fetchRecentActivity();
            }
        } catch (Exception e) {
            logNonFatal("onBranchChanged", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Lifecycle — Resume / Pause / Destroy
    // ═══════════════════════════════════════════════════════
    @Override
    public void onResume() {
        super.onResume();
        try {
            ensureCacheOwnerIsCurrentUser();

            isHeroHidden = true;
            isSalesHidden = true;
            isInventoryHidden = true; // ✅ إعادة الإخفاء عند العودة
            
            if (btnToggleHeroVisibility != null)
                btnToggleHeroVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleSalesVisibility != null)
                btnToggleSalesVisibility.setImageResource(R.drawable.ic_eye_off);
            if (btnToggleInventoryVisibility != null)
                btnToggleInventoryVisibility.setImageResource(R.drawable.ic_eye_off);

            registerDataRefreshReceiver();

            int latestBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
            if (latestBranchId != currentBranchId) {
                onBranchChanged();
            } else if (isViewInitialized) {
                updateHeader();
                syncOrgProfileFromServer(false);

                long now = System.currentTimeMillis();
                long timeSinceLastFetch = now - lastDataFetchTime;
                long timeSinceLastGoldFetch = now - lastGoldFetchTime;
                boolean isCacheValid = activityCache != null && activityCache.isValid();

                if (timeSinceLastFetch > RESUME_REFETCH_THRESHOLD_MS) {
                    fetchData();
                }

                if (!isCacheValid) {
                    mainHandler.postDelayed(() -> {
                        if (isAdded() && isResumed()) fetchRecentActivity();
                    }, 800);
                } else {
                    if (isAdded() && llRecentActivity != null
                            && llRecentActivity.getChildCount() == 0
                            && activityCache != null) {
                        try {
                            JSONArray cached = activityCache.load();
                            if (cached != null && cached.length() > 0) {
                                updateRecentActivityUI(cached);
                            }
                        } catch (Exception e) {
                            logNonFatal("onResume.restoreActivities", e);
                        }
                    }
                }

                // ✅ التحديث التلقائي من الـ API مسموح فقط للمدراء
if (timeSinceLastGoldFetch > 30 * 60 * 1000 && canManuallyRefreshGold()) {
    mainHandler.postDelayed(() -> {
        if (isAdded() && isResumed()) {
            fetchLiveGoldPrices(false); // المدير يجلب من الـ API
        }
    }, 1500);
} else {
    // ✅ الكاشير: يعرض الكاش فوراً، ثم يحدثه من سيرفرك في الخلفية
    displayGoldPricesFromCache();
    fetchApprovedGoldPricesFromServer(); 
}
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
            hideSensitiveValuesOnExit();
        } catch (Exception e) {
            logNonFatal("onPause", e);
        }
    }

    private void hideSensitiveValuesOnExit() {
        try {
            if (!isHeroHidden) {
                isHeroHidden = true;
                if (btnToggleHeroVisibility != null)
                    btnToggleHeroVisibility.setImageResource(R.drawable.ic_eye_off);
            }
            if (!isSalesHidden) {
                isSalesHidden = true;
                if (btnToggleSalesVisibility != null)
                    btnToggleSalesVisibility.setImageResource(R.drawable.ic_eye_off);
            }
            // ✅ إخفاء المخزون فوراً عند الخروج
            if (!isInventoryHidden) {
                isInventoryHidden = true;
                if (btnToggleInventoryVisibility != null)
                    btnToggleInventoryVisibility.setImageResource(R.drawable.ic_eye_off);
                renderInventoryByKarat(currentInventoryByKarat);
            }
        } catch (Exception e) {
            logNonFatal("hideSensitiveValuesOnExit", e);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        hideSensitiveValuesOnExit();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            unregisterDataRefreshReceiver();
            stopAutoRefresh();

            llRecentActivity = null;
            llInventoryByKarat = null;
            tvKaratEmptyState = null;

            if (branchesManager != null && branchListener != null) {
                branchesManager.removeListener(branchListener);
                branchListener = null;
            }
            if (orgProfile != null && profileListener != null) {
                orgProfile.removeListener(profileListener);
                profileListener = null;
            }

            cancelFuture(dataFuture);
            cancelFuture(goldPriceFuture);
            cancelFuture(pingFuture);

            if (mainHandler != null) {
                mainHandler.removeCallbacks(autoRefreshRunnable);
                if (swipeTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(swipeTimeoutRunnable);
                }
            }

            shutdownExecutor();
            isViewInitialized = false;
        } catch (Exception e) {
            logNonFatal("onDestroyView", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════
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

    private void initializeManagers() {
        try {
            if (!isAdded()) return;
            Context context = requireContext();

            if (localExecutor == null || localExecutor.isShutdown()) {
                localExecutor = Executors.newFixedThreadPool(4);
            }

            apiClient = MiraApiClient.getInstance(context);
            sessionManager = new SessionManager(context);
            branchesManager = MiraBranchesManager.getInstance(context);
            orgProfile = MiraOrgProfileManager.getInstance(context);
            priceManager = MiraPriceManager.getInstance(context);
            permissionManager = PermissionManager.getInstance(context);
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            goldCachePrefs = context.getSharedPreferences("gold_cache_prefs", Context.MODE_PRIVATE);
            lastGoldFetchTime = goldCachePrefs.getLong("last_gold_fetch_time", 0);
            activityCache = new RecentActivityCache(context);

            ensureCacheOwnerIsCurrentUser();

            try {
                android.content.SharedPreferences prefs =
                        context.getSharedPreferences("mira_dashboard", Context.MODE_PRIVATE);
                int currentVersion = 2;
                int savedVersion = prefs.getInt("activity_cache_version", 0);

                if (savedVersion < currentVersion) {
                    if (activityCache != null) activityCache.clear();
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Old cache cleared (v"
                                + savedVersion + " -> v" + currentVersion + ")");
                    }
                    prefs.edit().putInt("activity_cache_version", currentVersion).apply();
                }
            } catch (Exception e) {
                logNonFatal("initializeManagers.cacheVersion", e);
            }
        } catch (Exception e) {
            logNonFatal("initializeManagers", e);
        }
    }

    private boolean canManuallyRefreshGold() {
        if (permissionManager == null) return false;
        return permissionManager.isSystemAdmin()
            || permissionManager.isOrgAdmin()
            || permissionManager.isFinanceManager();
    }
}
