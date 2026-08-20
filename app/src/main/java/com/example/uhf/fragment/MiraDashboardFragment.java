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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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
    private TextView tvOuncePrice, tvPriceSource, tvLastUpdate;
    private TextView tvGoldTrend, tvGoldChange;
    private View btnGate, btnInventory, btnRefreshGold;
    private SwipeRefreshLayout swipeRefresh;
    private View shimmerLayout;
    private CardView cardGoldPrices;
    private LinearLayout containerQuickActions;

    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private MiraQuickAccessSettings quickAccessSettings;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private boolean isArabic;
    private SharedPreferences offlineCache;
    private SharedPreferences branchPrefs;
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
        // 1️⃣ تهيئة المديرين والخدمات
        apiClient = MiraApiClient.getInstance(requireContext());
        sessionManager = new SessionManager(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        orgProfile = MiraOrgProfileManager.getInstance(requireContext());
        priceManager = MiraPriceManager.getInstance(requireContext());
        quickAccessSettings = MiraQuickAccessSettings.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        branchPrefs = requireContext().getSharedPreferences("mira_branches_prefs", Context.MODE_PRIVATE);

        // 2️⃣ تهيئة الواجهات
        initViews(view);
        
        // 3️⃣ الترحيب والتاريخ أولاً (أعلى أولوية)
        updateHeader();
        
        // 4️⃣ تحديث حالة الاتصال
        updateLiveStatus();
        
        // 5️⃣ تحميل البيانات المحلية فوراً (سريعة)
        currentBranchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : -1;
        initBranchCache(currentBranchId);
        loadOfflineData();
        
        // 6️⃣ إعداد أزرار الوصول السريع
        setupQuickAccessGrid();
        
        // 7️⃣ إعداد المستمعين
        setupListeners();
        
        // 8️⃣ عرض shimmer أثناء جلب البيانات
        showShimmer(true);
        
        // 9️⃣ جلب البيانات من السيرفر (خلفية)
        fetchData();
        
        // 🔟 جلب أسعار الذهب (خلفية)
        fetchLiveGoldPrices();

    } catch (Throwable e) {
        Log.e(TAG, "onViewCreated error", e);
    }
}

/**
 * ✅ تحديث حالة الاتصال
 */
private void updateLiveStatus() {
    if (tvLiveStatus == null) return;
    
    // التحقق من الاتصال
    boolean isOnline = apiClient != null && apiClient.pingApi();
    
    if (isOnline) {
        tvLiveStatus.setText(isArabic ? "🟢 متصل" : "🟢 Online");
        tvLiveStatus.setTextColor(0xFF16A34A);
    } else {
        tvLiveStatus.setText(isArabic ? "🔴 غير متصل" : "🔴 Offline");
        tvLiveStatus.setTextColor(0xFFDC2626);
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

            tvOuncePrice = v.findViewById(R.id.tvOuncePrice);
            tvPriceSource = v.findViewById(R.id.tvPriceSource);
            tvLastUpdate = v.findViewById(R.id.tvLastUpdate);
            tvGoldTrend = v.findViewById(R.id.tvGoldTrend);
            tvGoldChange = v.findViewById(R.id.tvGoldChange);
            btnRefreshGold = v.findViewById(R.id.btnRefreshGold);
            cardGoldPrices = v.findViewById(R.id.cardGoldPrices);
            containerQuickActions = v.findViewById(R.id.containerQuickActions);

            if (swipeRefresh != null) {
                swipeRefresh.setOnRefreshListener(() -> {
                    fetchData();
                    fetchLiveGoldPrices();
                });
            }

            if (btnRefreshGold != null) {
                btnRefreshGold.setOnClickListener(v2 -> fetchLiveGoldPrices());
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
        if (btnGate != null) btnGate.setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(new MiraSecureGateFragment(), "SecureGate");
            }
        });

        if (btnInventory != null) btnInventory.setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(new MiraInventoryProFragment(), "Scan");
            }
        });
    }

    // ============================================
    // ✅ إعداد شبكة الأزرار السريعة الاحترافية
    // ============================================
    private void setupQuickAccessGrid() {
        if (containerQuickActions == null) return;

        containerQuickActions.removeAllViews();

        List<MiraQuickAccessSettings.QuickAction> enabledActions = quickAccessSettings.getEnabledActions();

        if (enabledActions.isEmpty()) {
            showEmptyQuickAccess();
            return;
        }

        LinearLayout currentRow = null;
        int countInRow = 0;

        for (MiraQuickAccessSettings.QuickAction action : enabledActions) {
            if (currentRow == null || countInRow == 0) {
                currentRow = new LinearLayout(getContext());
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                containerQuickActions.addView(currentRow);
            }

            CardView card = createQuickActionCard(action);
            if (card != null) {
                currentRow.addView(card);
                countInRow++;
            }

            if (countInRow >= 3) {
                countInRow = 0;
                currentRow = null;
            }
        }
    }

    // في createQuickActionCard()
private CardView createQuickActionCard(MiraQuickAccessSettings.QuickAction action) {
    Context context = getContext();
    if (context == null) return null;

    MaterialCardView card = new MaterialCardView(context);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    params.setMargins(6, 6, 6, 6);
    card.setLayoutParams(params);
    card.setRadius(12 * context.getResources().getDisplayMetrics().density);
    card.setCardElevation(2f);
    card.setStrokeWidth(1);
    
    // ✅ لون الحدود
    try {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true);
        card.setStrokeColor(typedValue.data);
    } catch (Exception ignored) {}
    
    // ✅ لون خلفية البطاقة من الثيم
    try {
        android.util.TypedValue bgTypedValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, bgTypedValue, true);
        card.setCardBackgroundColor(bgTypedValue.data);
    } catch (Exception ignored) {}

    card.setClickable(true);
    card.setFocusable(true);
    card.setRippleColor(0x22000000); // تأثير الضغط

    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(8, 14, 8, 14);
    layout.setGravity(android.view.Gravity.CENTER);

    // ✅ تحميل خط Cairo مرة واحدة
    android.graphics.Typeface cairoBold = null;
    try {
        cairoBold = android.graphics.Typeface.createFromAsset(
            context.getAssets(), "fonts/cairo_bold.ttf");
    } catch (Exception e) {
        Log.e(TAG, "Cairo font load error: " + e.getMessage());
    }

    // ✅ الأيقونة
    TextView tvIcon = new TextView(context);
    tvIcon.setText(getActionIcon(action.id));
    tvIcon.setTextSize(22);
    tvIcon.setGravity(android.view.Gravity.CENTER);
    layout.addView(tvIcon);

    // ✅ العنوان مع خط Cairo
    TextView tvTitle = new TextView(context);
    tvTitle.setText(isArabic ? action.titleAr : action.titleEn);
    tvTitle.setTextSize(11);
    tvTitle.setGravity(android.view.Gravity.CENTER);
    tvTitle.setPadding(0, 6, 0, 0);
    
    // ✅ استخدام خط Cairo دائماً
    if (cairoBold != null) {
        tvTitle.setTypeface(cairoBold);
    } else {
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }
    
    // ✅ لون النص من الثيم
    try {
        android.util.TypedValue textColorTypedValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, textColorTypedValue, true);
        tvTitle.setTextColor(textColorTypedValue.data);
    } catch (Exception ignored) {}
    
    layout.addView(tvTitle);

    card.addView(layout);
    card.setOnClickListener(v -> handleQuickAction(action.id));

    return card;
}
    private void handleQuickAction(String actionId) {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();

            switch (actionId) {
                case "identity":
                    activity.loadFragment(new MiraIdentityStudioFragment(), "IdentityStudio");
                    break;
                case "radar":
                    activity.loadFragment(new MiraUHFRadarLocationFragment(), "Radar");
                    break;
                case "gate":
                    activity.loadFragment(new MiraSecureGateFragment(), "SecureGate");
                    break;
                case "reports":
                    activity.loadFragment(new MiraReportsProFragment(), "Reports");
                    break;
                case "accounting":
                    activity.loadFragment(new MiraAccountingFragment(), "Accounting");
                    break;
                case "transfers":
                    activity.loadFragment(new MiraTransferFragment(), "Transfers");
                    break;
                case "admin_hub":
                    activity.loadFragment(new MiraAdminHubFragment(), "AdminHub");
                    break;
                case "sales_history":
                    activity.loadFragment(new MiraSalesHistoryFragment(), "SalesHistory");
                    break;
                case "server_config":
                    activity.loadFragment(new MiraServerConfigFragment(), "ServerConfig");
                    break;
                case "gold_rates":
                    activity.loadFragment(new MiraGoldCurrencyFragment(), "GoldRates");
                    break;
                case "users":
                    activity.loadFragment(new UsersManagementFragment(), "UsersManagement");
                    break;
                default:
                    Toast.makeText(getContext(),
                            isArabic ? "غير معروف: " + actionId : "Unknown: " + actionId,
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    }

    private void showEmptyQuickAccess() {
        if (containerQuickActions == null || getContext() == null) return;

        TextView tvEmpty = new TextView(getContext());
        tvEmpty.setText(isArabic ?
                "لا توجد أزرار مفعّلة" :
                "No enabled actions");
        tvEmpty.setTextSize(13);
        tvEmpty.setGravity(android.view.Gravity.CENTER);
        tvEmpty.setPadding(16, 16, 16, 16);
        tvEmpty.setTextColor(0xFF6B7280);

        containerQuickActions.addView(tvEmpty);
    }

    private String getActionIcon(String actionId) {
        switch (actionId) {
            case "identity": return "🏷️";
            case "radar": return "📡";
            case "gate": return "🚪";
            case "reports": return "📊";
            case "accounting": return "💰";
            case "transfers": return "🔄";
            case "admin_hub": return "⚙️";
            case "sales_history": return "📋";
            case "server_config": return "🌐";
            case "gold_rates": return "🥇";
            case "users": return "👥";
            case "branches": return "🏢";
            default: return "📱";
        }
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

    private void fetchLiveGoldPrices() {
        if (apiClient == null) return;

        addDebug("🔄 جلب أسعار الذهب العالمية...");

        executorService.execute(() -> {
            try {
                String provider = "metalprice";
                String apiKey = "";

                MiraApiClient.ApiResponse response = apiClient.fetchGoldPrice(provider, apiKey);

                mainHandler.post(() -> {
                    if (!isAdded()) return;

                    if (response != null && response.isSuccess && response.data != null) {
                        try {
                            JSONObject data = response.data.optJSONObject("data");
                            if (data == null) data = response.data;

                            displayGoldPrices(data);
                            addDebug("✅ تم تحديث أسعار الذهب");
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing gold prices", e);
                            displayGoldPricesFromCache();
                        }
                    } else {
                        addDebug("⚠️ فشل جلب الأسعار، استخدام الكاش");
                        displayGoldPricesFromCache();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching gold prices", e);
                mainHandler.post(() -> {
                    if (isAdded()) {
                        displayGoldPricesFromCache();
                    }
                });
            }
        });
    }

    private void displayGoldPrices(JSONObject data) {
        try {
            JSONObject prices = data.optJSONObject("prices");
            if (prices == null) prices = data;

            double price24 = prices.optDouble("24k", 0);
            double price21 = prices.optDouble("21k", 0);
            double price18 = prices.optDouble("18k", 0);
            double ouncePrice = data.optDouble("price_per_ounce",
                    prices.optDouble("ounce_price", price24 * 31.1035));
            String source = data.optString("source", "server");

            setGoldPrice(tvKarat24, "24K", price24);
            setGoldPrice(tvKarat21, "21K", price21);
            setGoldPrice(tvKarat18, "18K", price18);

            if (tvOuncePrice != null && ouncePrice > 0) {
                tvOuncePrice.setText(String.format(Locale.US, "💰 $%,.2f", ouncePrice));
            }

            if (tvPriceSource != null) {
                String sourceText = getSourceDisplayName(source);
                tvPriceSource.setText("📡 " + sourceText);
            }

            if (tvLastUpdate != null) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                tvLastUpdate.setText(isArabic ? "⏰ آخر تحديث: " + time : "⏰ Last update: " + time);
            }

            calculateTrend(price24);

        } catch (Exception e) {
            Log.e(TAG, "displayGoldPrices error", e);
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
                double ouncePrice = price24 * 31.1035;
                tvOuncePrice.setText(String.format(Locale.US, "💰 $%,.2f", ouncePrice));
            }

            if (tvPriceSource != null) {
                tvPriceSource.setText(isArabic ? "📡 كاش محلي" : "📡 Local cache");
            }

            if (tvLastUpdate != null) {
                tvLastUpdate.setText(isArabic ? "⏰ وضع أوفلاين" : "⏰ Offline mode");
            }

        } catch (Exception e) {
            Log.e(TAG, "displayGoldPricesFromCache error", e);
        }
    }

    private void calculateTrend(double currentPrice) {
        if (tvGoldTrend == null || tvGoldChange == null) return;

        try {
            if (offlineCache == null) return;

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
            } else {
                tvGoldTrend.setText("📊");
                tvGoldChange.setText("—");
                tvGoldChange.setTextColor(0xFF6B7280);
            }

            if (offlineCache != null && currentPrice > 0) {
                offlineCache.edit().putFloat("gold_price_24k_prev", (float) currentPrice).apply();
            }

        } catch (Exception e) {
            Log.e(TAG, "calculateTrend error", e);
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

    private void loadOfflineData() {
        if (offlineCache == null) return;
        try {
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

            double totalWeightVal = 0.0;
            if (inv != null) {
                totalWeightVal = inv.optDouble("total_weight", 0.0);
            }

            offlineCache.edit()
                    .putInt("total_items", inv != null ? inv.optInt("total_items", 0) : 0)
                    .putFloat("total_weight", (float) totalWeightVal)
                    .putInt("sold_today", sales != null ? sales.optInt("count_today", 0) : 0)
                    .putFloat("sales_today", (float) (sales != null ? sales.optDouble("today", 0.0) : 0.0))
                    .putFloat("sales_month", (float) (sales != null ? sales.optDouble("month", 0.0) : 0.0))
                    .putFloat("sales_year", (float) (sales != null ? sales.optDouble("year", 0.0) : 0.0))
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

            executorService.execute(() -> {
                MiraApiClient.ApiResponse response = null;
                try {
                    response = apiClient.getDashboardFull(activeBranchId);
                } catch (Throwable e) {
                    addDebug("❌ خطأ شبكة/اتصال: " + e.getMessage());
                }

                final MiraApiClient.ApiResponse finalResponse = response;

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    try {
                        if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                            saveOfflineData(finalResponse.data);
                            processData(finalResponse.data);
                        } else {
                            loadOfflineData();
                        }
                    } catch (Throwable e) {
                        loadOfflineData();
                    } finally {
                        showShimmer(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    }
                });
            });
        } catch (Throwable e) {
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
                setText(tvTotalWeight, formatWeight(weightVal));
            }

            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                setText(tvSoldToday, String.valueOf(sales.optInt("count_today", 0)));
                setText(tvSalesToday, formatCurrency(sales.optDouble("today", 0.0)));
                setText(tvSalesMonth, formatCurrency(sales.optDouble("month", 0.0)));
                setText(tvSalesYear, formatCurrency(sales.optDouble("year", 0.0)));
            }

            if (tvLiveStatus != null) {
                tvLiveStatus.setText(isArabic ? "متصل" : "Online");
                tvLiveStatus.setTextColor(0xFF16A34A);
            }
        } catch (Throwable e) {
            Log.e(TAG, "processData error", e);
        }
    }

    private String formatCurrency(double amount) {
        if (amount <= 0) return "—";
        if (priceManager != null) {
            return priceManager.formatPriceDefault(amount);
        }
        return String.format(Locale.US, "%,.0f", amount);
    }

    private String formatWeight(double weightInGrams) {
        if (weightInGrams <= 0) return "0.0 g";
        if (weightInGrams >= 1000) {
            return String.format(Locale.US, "%.2f kg", weightInGrams / 1000);
        }
        return String.format(Locale.US, "%.1f g", weightInGrams);
    }

    private void setGoldPrice(TextView tv, String label, double price) {
        if (tv == null) return;
        if (price > 0) {
            tv.setText(String.format(Locale.US, "%s: $%,.2f/g", label, price));
        } else {
            tv.setText(label + ": —");
        }
    }

    private void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
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
        if (branchPrefs != null) {
            branchPrefs.registerOnSharedPreferenceChangeListener(this);
        }
        int latestBranchId = (branchesManager != null) ? branchesManager.getActiveBranchId() : -1;
        if (latestBranchId != currentBranchId) {
            onBranchChanged();
        }

        setupQuickAccessGrid();
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
        if ("active_branch_id".equals(key) || "active_branch".equals(key) || "active_branch_json".equals(key)) {
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
                            Toast.makeText(requireContext(),
                                    "تم النسخ", Toast.LENGTH_SHORT).show();
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
