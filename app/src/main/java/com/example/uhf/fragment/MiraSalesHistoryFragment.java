package com.example.uhf.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SecureSessionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;

public class MiraSalesHistoryFragment extends Fragment {

    private static final String TAG = "MiraSalesHistory";
    private static final int PAGE_SIZE = 50;
    private static final int VISIBLE_THRESHOLD = 10;

    private boolean isReceiverRegistered = false;
    private BroadcastReceiver dataRefreshReceiver;
    private RecyclerView rvSales;
    private View layoutEmptyState;
    private TextView tvEmpty;
    private ProgressBar pbLoading;
    private ProgressBar pbLoadMore;
    private EditText etSearch;
    private View btnRefresh;
    private SwipeRefreshLayout swipeRefresh;
    private MaterialButton btnFilterPayment;
    private MaterialButton btnFilterBranch;

    private View loadingMoreContainer;
    private TextView tvEndOfList;
    private TextView tvTotalRevenue;
    private TextView tvInvoicesCount;
    private TextView tvShowingCount;

    private MiraSalesAdapter adapter;
    private final List<MiraSaleItem> saleList = new ArrayList<>();
    private final List<MiraSaleItem> filteredList = new ArrayList<>();

    private int currentPage = 0;
    private boolean isLoading = false;
    private boolean isLastPage = false;
    private boolean hasMoreData = true;
    private int totalItems = 0;
    private String searchQuery = "";
    private String currentFilter = "ALL";
    private int selectedBranchId = 0;

    private static final String OFFLINE_PREFS = "sales_history_cache";
    private SharedPreferences offlineCache;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;
    private SessionManager sessionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArabic;

    private boolean checkIsArabic() {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
                getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private void registerDataRefreshReceiver() {
        try {
            if (getContext() == null || isReceiverRegistered) return;

            dataRefreshReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !isAdded()) return;
                    String action = intent.getAction();
                    if ("com.example.uhf.REFRESH_SALES".equals(action) ||
                        "com.example.uhf.REFRESH_DASHBOARD".equals(action) ||
                        "com.example.uhf.REFRESH_INVENTORY".equals(action)) {
                        mainHandler.post(() -> {
                            if (isAdded() && isResumed()) {
                                refreshSales(true);
                            }
                        });
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction("com.example.uhf.REFRESH_SALES");
            filter.addAction("com.example.uhf.REFRESH_DASHBOARD");
            filter.addAction("com.example.uhf.REFRESH_INVENTORY");

            ContextCompat.registerReceiver(requireContext(), dataRefreshReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            isReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "registerDataRefreshReceiver error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        registerDataRefreshReceiver();
        if (isAdded() && saleList.isEmpty()) {
            refreshSales(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterDataRefreshReceiver();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unregisterDataRefreshReceiver();
    }

    private void unregisterDataRefreshReceiver() {
        try {
            if (getContext() != null && isReceiverRegistered && dataRefreshReceiver != null) {
                requireContext().unregisterReceiver(dataRefreshReceiver);
                isReceiverRegistered = false;
            }
        } catch (Exception e) {
            Log.d(TAG, "unregisterDataRefreshReceiver: " + e.getMessage());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_sales_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isArabic = checkIsArabic();
        view.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            priceManager = MiraPriceManager.getInstance(getContext());
            permissionManager = PermissionManager.getInstance(getContext());
            sessionManager = new SessionManager(getContext());
            offlineCache = requireContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        }

        initViews(view);
        setupRecyclerView();
        setupListeners();
        applyPermissions();
        applyLanguage();
        setupBranchFilter();

        loadSalesOffline();
        refreshSales(true);
    }

    private void loadSalesOffline() {
        try {
            if (offlineCache == null) return;
            String cachedJson = offlineCache.getString("sales_cache", "");
            if (cachedJson.isEmpty()) return;

            JSONArray sales = new JSONArray(cachedJson);
            saleList.clear();
            saleList.addAll(parseSalesJson(sales));
            filterSales(searchQuery);
        } catch (Exception e) {
            Log.e(TAG, "Error loading offline sales: " + e.getMessage());
        }
    }

    private void applyPermissions() {
        boolean canView = permissionManager.canView("sales_history") ||
                         permissionManager.isPosOperator() ||
                         permissionManager.isManager();

        if (!canView) {
            showEmptyState(isArabic ? "⛔ غير مصرح لك بالوصول لسجل المبيعات" : "⛔ Access Denied");
            if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            if (etSearch != null) etSearch.setEnabled(false);
            if (btnRefresh != null) btnRefresh.setEnabled(false);
            if (swipeRefresh != null) swipeRefresh.setEnabled(false);
            if (rvSales != null) rvSales.setVisibility(View.GONE);
            if (btnFilterPayment != null) btnFilterPayment.setEnabled(false);
            if (btnFilterBranch != null) btnFilterBranch.setEnabled(false);
        }
    }

    private void setupBranchFilter() {
        SecureSessionManager.Role userRole = sessionManager.getUserRole();
        int userBranchId = branchesManager.getActiveBranchId();

        boolean canViewAllBranches = userRole == SecureSessionManager.Role.SYSTEM_ADMIN ||
                                     userRole == SecureSessionManager.Role.ORG_ADMIN;

        if (canViewAllBranches) {
            selectedBranchId = 0;
            if (btnFilterBranch != null) {
                btnFilterBranch.setVisibility(View.VISIBLE);
                btnFilterBranch.setText(isArabic ? "🏢 جميع الفروع" : "🏢 All Branches");
                btnFilterBranch.setOnClickListener(v -> showBranchSelectionDialog());
            }
        } else {
            selectedBranchId = userBranchId;
            if (btnFilterBranch != null) {
                btnFilterBranch.setVisibility(View.GONE);
            }
        }
    }

    private void showBranchSelectionDialog() {
        List<BranchOption> branches = new ArrayList<>();
        branches.add(new BranchOption(0, isArabic ? "🏢 جميع الفروع" : "🏢 All Branches"));

        List<MiraBranchesManager.BranchItem> branchList = branchesManager.getAllBranches();
        for (MiraBranchesManager.BranchItem branch : branchList) {
            branches.add(new BranchOption(branch.id, branch.name));
        }

        String[] branchNames = new String[branches.size()];
        for (int i = 0; i < branches.size(); i++) {
            branchNames[i] = branches.get(i).name;
        }

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "🏢 اختر الفرع" : "🏢 Select Branch")
            .setItems(branchNames, (dialog, which) -> {
                BranchOption selected = branches.get(which);
                selectedBranchId = selected.id;
                if (btnFilterBranch != null) {
                    btnFilterBranch.setText(selected.name);
                }
                refreshSales(true);
            })
            .show();
    }

    private void showPaymentFilterDialog() {
        String[] filterNames = isArabic ?
            new String[]{"💰 الكل", "💵 نقداً", "💳 بطاقة", "🏦 تحويل", "📝 آجل"} :
            new String[]{"💰 All", "💵 Cash", "💳 Card", "🏦 Transfer", "📝 Credit"};

        String[] filterValues = {"ALL", "CASH", "CARD", "TRANSFER", "CREDIT"};

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "💰 فلترة حسب طريقة الدفع" : "💰 Filter by Payment Method")
            .setItems(filterNames, (dialog, which) -> {
                currentFilter = filterValues[which];
                if (btnFilterPayment != null) {
                    btnFilterPayment.setText(filterNames[which]);
                }
                refreshSales(true);
            })
            .show();
    }

    private void initViews(View view) {
        rvSales = view.findViewById(R.id.rvMiraSalesHistory);
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState);
        tvEmpty = view.findViewById(R.id.tvMiraEmptySales);
        pbLoading = view.findViewById(R.id.pbSalesLoading);
        etSearch = view.findViewById(R.id.etMiraSearchSales);
        btnRefresh = view.findViewById(R.id.btnMiraRefreshSales);
        swipeRefresh = view.findViewById(R.id.swipeRefreshSales);
        tvTotalRevenue = view.findViewById(R.id.tvTotalRevenue);
        tvInvoicesCount = view.findViewById(R.id.tvInvoicesCount);
        pbLoadMore = view.findViewById(R.id.pbLoadMore);
        tvShowingCount = view.findViewById(R.id.tvShowingCount);
        loadingMoreContainer = view.findViewById(R.id.loadingMoreContainer);
        tvEndOfList = view.findViewById(R.id.tvEndOfList);
        btnFilterPayment = view.findViewById(R.id.btnFilterPayment);
        btnFilterBranch = view.findViewById(R.id.btnFilterBranch);
    }

    private void applyLanguage() {
        if (etSearch != null) {
            etSearch.setHint(isArabic ? "بحث في المبيعات..." : "Search sales...");
        }
        if (tvEmpty != null) {
            tvEmpty.setText(isArabic ? "📭 لا توجد مبيعات مسجلة" : "📭 No sales recorded");
        }
        if (btnFilterPayment != null) {
            btnFilterPayment.setText(isArabic ? "💰 الكل" : "💰 All");
        }
    }

    private void setupRecyclerView() {
        if (rvSales != null) {
            LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
            rvSales.setLayoutManager(layoutManager);
            rvSales.setHasFixedSize(true);
            rvSales.setItemViewCacheSize(20);

            adapter = new MiraSalesAdapter(filteredList, this::openReceipt, isArabic, priceManager);
            rvSales.setAdapter(adapter);

            rvSales.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    if (dy > 0) {
                        int totalItemCount = layoutManager.getItemCount();
                        int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                        if (!isLoading && hasMoreData && totalItemCount <= (lastVisibleItem + VISIBLE_THRESHOLD)) {
                            loadMoreSales();
                        }
                    }
                }
            });
        }
    }

    private void setupListeners() {
        if (btnRefresh != null) btnRefresh.setOnClickListener(v -> refreshSales(true));
        if (swipeRefresh != null) swipeRefresh.setOnRefreshListener(() -> refreshSales(false));

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchQuery = s.toString();
                    mainHandler.removeCallbacks(searchRunnable);
                    mainHandler.postDelayed(searchRunnable, 300);
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        if (btnFilterPayment != null) btnFilterPayment.setOnClickListener(v -> showPaymentFilterDialog());
        if (btnFilterBranch != null) btnFilterBranch.setOnClickListener(v -> showBranchSelectionDialog());
    }

    private final Runnable searchRunnable = () -> filterSales(searchQuery);

    private void refreshSales(boolean showProgress) {
        if (!isAdded()) return;
        if (!permissionManager.canView("sales_history") &&
            !permissionManager.isPosOperator() &&
            !permissionManager.isManager()) {
            return;
        }

        currentPage = 0;
        isLastPage = false;
        hasMoreData = true;
        isLoading = false;

        if (showProgress) showLoadingState();
        loadSalesPage(0, true);
    }

    private void loadMoreSales() {
        if (isLoading || !hasMoreData || isLastPage) {
            if (isLastPage && tvEndOfList != null) tvEndOfList.setVisibility(View.VISIBLE);
            return;
        }

        isLoading = true;
        if (loadingMoreContainer != null) loadingMoreContainer.setVisibility(View.VISIBLE);
        if (tvEndOfList != null) tvEndOfList.setVisibility(View.GONE);

        loadSalesPage(currentPage + 1, false);
    }

    private void loadSalesPage(int page, boolean isRefresh) {
        if (isLoading && !isRefresh) return;
        isLoading = true;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (apiClient == null && getContext() != null) {
                    apiClient = MiraApiClient.getInstance(getContext());
                }

                if (apiClient == null) {
                    mainHandler.post(() -> {
                        hideLoadingState();
                        isLoading = false;
                        if (saleList.isEmpty()) {
                            showEmptyState(isArabic ? "⚠️ خطأ في تهيئة النظام" : "⚠️ System initialization error");
                        }
                    });
                    return;
                }

                MiraApiClient.ApiResponse response;
                if (!searchQuery.isEmpty() || !currentFilter.equals("ALL") || selectedBranchId > 0) {
                    response = apiClient.getSalesHistoryAdvanced(searchQuery, currentFilter, null, null, page, PAGE_SIZE, selectedBranchId);
                } else {
                    response = (selectedBranchId > 0) ?
                        apiClient.getSalesHistoryAdvanced("", "ALL", null, null, page, PAGE_SIZE, selectedBranchId) :
                        apiClient.getSalesHistoryPaginated(page, PAGE_SIZE);
                }

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray sales = null;
                    int totalCount = 0;
                    boolean hasMore = false;

                    if (response.data.has("sales")) {
                        sales = response.data.optJSONArray("sales");
                    } else if (response.data.has("data")) {
                        Object dataObj = response.data.opt("data");
                        if (dataObj instanceof JSONArray) {
                            sales = (JSONArray) dataObj;
                        } else if (dataObj instanceof JSONObject) {
                            JSONObject dataJson = (JSONObject) dataObj;
                            sales = dataJson.optJSONArray("sales");
                            if (sales == null) sales = dataJson.optJSONArray("items");
                            totalCount = dataJson.optInt("total", dataJson.optInt("total_count", 0));
                            hasMore = dataJson.optBoolean("has_more", false);
                        }
                    }

                    if (totalCount == 0) totalCount = response.data.optInt("total", response.data.optInt("total_count", 0));
                    if (!hasMore) hasMore = response.data.optBoolean("has_more", false);

                    List<MiraSaleItem> items = (sales != null) ? parseSalesJson(sales) : new ArrayList<>();

                    if (selectedBranchId > 0) {
                        List<MiraSaleItem> filteredByBranch = new ArrayList<>();
                        for (MiraSaleItem item : items) {
                            if (item.branchId == selectedBranchId) filteredByBranch.add(item);
                        }
                        items = filteredByBranch;
                    }

                    final List<MiraSaleItem> finalItems = items;
                    final int finalTotalCount = totalCount;
                    final boolean finalHasMore = hasMore;

                    if (sales != null && sales.length() > 0) saveSalesOffline(sales, page);

                    boolean isLast = finalItems.size() < PAGE_SIZE ||
                                   (finalTotalCount > 0 && (page + 1) * PAGE_SIZE >= finalTotalCount) ||
                                   !finalHasMore;

                    mainHandler.post(() -> {
                        if (!isAdded()) return;

                        isLoading = false;
                        hideLoadingState();
                        if (pbLoadMore != null) pbLoadMore.setVisibility(View.GONE);
                        if (loadingMoreContainer != null) loadingMoreContainer.setVisibility(View.GONE);

                        if (isRefresh) {
                            saleList.clear();
                            saleList.addAll(finalItems);
                            totalItems = finalTotalCount > 0 ? finalTotalCount : finalItems.size();
                            currentPage = 0;
                            hasMoreData = !isLast && finalItems.size() == PAGE_SIZE;
                            isLastPage = isLast;
                        } else {
                            saleList.addAll(finalItems);
                            currentPage = page;
                            hasMoreData = !isLast && finalItems.size() == PAGE_SIZE;
                            isLastPage = isLast;
                        }

                        filterSales(searchQuery);
                        if (saleList.isEmpty()) showEmptyState(isArabic ? "📭 لا توجد مبيعات مسجلة" : "📭 No sales recorded");
                        if (isLastPage && tvEndOfList != null) tvEndOfList.setVisibility(View.VISIBLE);
                    });
                } else {
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        hideLoadingState();
                        isLoading = false;
                        if (saleList.isEmpty()) showEmptyState(isArabic ? "📭 لا توجد مبيعات مسجلة" : "📭 No sales recorded");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception loading sales", e);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    hideLoadingState();
                    isLoading = false;
                    if (saleList.isEmpty()) showEmptyState(isArabic ? "⚠️ خطأ في تحميل البيانات" : "⚠️ Error loading data");
                });
            }
        });
    }

    private List<MiraSaleItem> parseSalesJson(JSONArray sales) {
    List<MiraSaleItem> items = new ArrayList<>();
    for (int i = 0; i < sales.length(); i++) {
        JSONObject sale = sales.optJSONObject(i);
        if (sale == null) continue;

        MiraSaleItem item = new MiraSaleItem();
        item.saleId = sale.optString("sale_id", sale.optString("id", sale.optString("invoice_no", "—")));
        item.customerName = sale.optString("customer_name", sale.optString("client_name", isArabic ? "عميل افتراضي" : "Customer"));
        
        // ✅ اقرأ العملة
        item.currency = sale.optString("currency", sale.optString("currency_code", "USD"));
        
        // ✅ اقرأ السعر بالدولار أولاً
        double amountUsd = sale.optDouble("final_amount_usd", 
                            sale.optDouble("total_amount_usd", 0));
        
        // ✅ اقرأ السعر المحلي
        double amountLocal = sale.optDouble("final_amount", 
                              sale.optDouble("total_amount", 
                                sale.optDouble("amount", 0)));
        
        // ✅ إذا كان USD موجوداً، استخدمه
        if (amountUsd > 0) {
            item.amountUSD = amountUsd;
            item.amount = amountUsd;  // للتوافق
        } else {
            // ✅ حوّل من المحلي إلى USD
            String currency = item.currency != null ? item.currency.toUpperCase() : "USD";
            if ("USD".equals(currency)) {
                item.amountUSD = amountLocal;
            } else if ("YER".equals(currency)) {
                double rate = (priceManager != null) ? priceManager.getExchangeRate("YER") : 530.0;
                if (rate <= 0) rate = 530.0;
                item.amountUSD = amountLocal / rate;
            } else if ("SAR".equals(currency)) {
                double rate = (priceManager != null) ? priceManager.getExchangeRate("SAR") : 3.75;
                if (rate <= 0) rate = 3.75;
                item.amountUSD = amountLocal / rate;
            } else {
                item.amountUSD = amountLocal;
            }
            item.amount = item.amountUSD;
        }
        
        item.itemCount = sale.optInt("item_count", sale.optInt("total_items", sale.optInt("count", 0)));
        item.paymentMethod = sale.optString("payment_method", sale.optString("pay_type", "CASH"));
        item.date = sale.optString("created_at", sale.optString("date", ""));
        item.branchId = sale.optInt("branch_id", 0);
        item.refundStatus = sale.optString("refund_status", sale.optString("status", "completed"));
        item.refundReason = sale.optString("refund_reason", "");
        item.refundedAt = sale.optString("refunded_at", "");
        item.rawJson = sale.toString();
        items.add(item);
    }
    return items;
}

    private void saveSalesOffline(JSONArray sales, int page) {
        try {
            if (offlineCache == null) return;
            offlineCache.edit().putString("sales_cache", sales.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void filterSales(String query) {
        List<MiraSaleItem> newFiltered = new ArrayList<>();

        if ((query == null || query.trim().isEmpty()) && currentFilter.equals("ALL") && selectedBranchId == 0) {
            newFiltered.addAll(saleList);
        } else {
            String q = (query != null) ? query.toLowerCase(Locale.ROOT) : "";

            for (MiraSaleItem item : saleList) {
                if (selectedBranchId > 0 && item.branchId != selectedBranchId) continue;
                if (!currentFilter.equals("ALL")) {
                    String paymentMethod = item.paymentMethod != null ? item.paymentMethod.toUpperCase(Locale.ROOT) : "";
                    if (!paymentMethod.equals(currentFilter)) continue;
                }
                if (!q.isEmpty()) {
                    boolean matchesCustomer = item.customerName != null && item.customerName.toLowerCase(Locale.ROOT).contains(q);
                    boolean matchesSaleId = item.saleId != null && item.saleId.toLowerCase(Locale.ROOT).contains(q);
                    if (!matchesCustomer && !matchesSaleId) continue;
                }
                newFiltered.add(item);
            }
        }

        if (adapter != null) adapter.updateData(newFiltered);
        updateSummaryCards(newFiltered);

        if (newFiltered.isEmpty()) {
            showEmptyState(isArabic ? "📭 لا توجد نتائج مطابقة" : "📭 No matching results");
        } else {
            hideEmptyState();
        }
    }

    private void updateSummaryCards(List<MiraSaleItem> list) {
    double totalRevenueUSD = 0;
    for (MiraSaleItem item : list) {
        // ✅ استخدم القيمة بالدولار
        totalRevenueUSD += item.amountUSD;
    }

    if (tvTotalRevenue != null) {
        // ✅ formatPriceDefault يتوقع USD ويحوّل → نعرض بالسعر الحالي
        tvTotalRevenue.setText(priceManager.formatPriceDefault(totalRevenueUSD));
    }
    if (tvInvoicesCount != null) {
        tvInvoicesCount.setText(String.format(Locale.US, "%,d", totalItems > 0 ? totalItems : list.size()));
    }
    if (tvShowingCount != null) {
        tvShowingCount.setText(String.format(Locale.US, "%,d", list.size()));
    }
}

    private void showLoadingState() {
        if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.VISIBLE);
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(isArabic ? "جاري تحميل المبيعات..." : "Loading sales...");
        }
    }

    private void hideLoadingState() {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
    }

    private void showEmptyState(String message) {
        if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.VISIBLE);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(message);
        }
    }

    private void hideEmptyState() {
        if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.GONE);
    }

    private void openReceipt(MiraSaleItem sale) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).openReceiptFragment(sale.rawJson);
            } else if (isAdded()) {
                openReceiptWithAdd(sale.rawJson);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening receipt", e);
            if (getContext() != null) {
                Toast.makeText(getContext(), isArabic ? "خطأ في فتح الإيصال" : "Error opening receipt", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openReceiptWithAdd(String receiptJson) {
        try {
            MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(receiptJson);
            FragmentManager fm = getParentFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();
            ft.hide(this);
            ft.add(android.R.id.content, receipt, "mira_receipt");
            ft.addToBackStack("mira_receipt");
            ft.commit();

            fm.addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
                @Override
                public void onBackStackChanged() {
                    if (fm.getBackStackEntryCount() == 0) {
                        FragmentTransaction showFt = fm.beginTransaction();
                        showFt.show(MiraSalesHistoryFragment.this);
                        showFt.commit();
                        fm.removeOnBackStackChangedListener(this);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in openReceiptWithAdd", e);
        }
    }

    static class MiraSaleItem {
        String saleId, customerName, paymentMethod, date, rawJson;
        double amount;
        double amountUSD;        // ✅ جديد: القيمة بالدولار
        String currency;         // ✅ جديد: عملة الفاتورة
        int itemCount;
        int branchId;
        String refundStatus;
        String refundReason;
        String refundedAt;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MiraSaleItem item = (MiraSaleItem) o;
            return Double.compare(item.amount, amount) == 0 &&
                    itemCount == item.itemCount &&
                    branchId == item.branchId &&
                    Objects.equals(saleId, item.saleId) &&
                    Objects.equals(customerName, item.customerName) &&
                    Objects.equals(paymentMethod, item.paymentMethod) &&
                    Objects.equals(date, item.date) &&
                    Objects.equals(refundStatus, item.refundStatus);
        }

        @Override
        public int hashCode() {
            return Objects.hash(saleId, customerName, paymentMethod, date, amount, itemCount, branchId, refundStatus);
        }
    }

    static class BranchOption {
        int id;
        String name;
        BranchOption(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static class MiraSalesAdapter extends RecyclerView.Adapter<MiraSalesAdapter.VH> {
        private final List<MiraSaleItem> items;
        private final OnMiraSaleClickListener listener;
        private final boolean isArabic;
        private final MiraPriceManager priceManager;

        MiraSalesAdapter(List<MiraSaleItem> items, OnMiraSaleClickListener listener, boolean isArabic, MiraPriceManager priceManager) {
            this.items = new ArrayList<>(items);
            this.listener = listener;
            this.isArabic = isArabic;
            this.priceManager = priceManager;
        }

        public void updateData(List<MiraSaleItem> newItems) {
            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() { return items.size(); }
                @Override
                public int getNewListSize() { return newItems.size(); }
                @Override
                public boolean areItemsTheSame(int oldPos, int newPos) {
                    return Objects.equals(items.get(oldPos).saleId, newItems.get(newPos).saleId);
                }
                @Override
                public boolean areContentsTheSame(int oldPos, int newPos) {
                    return items.get(oldPos).equals(newItems.get(newPos));
                }
            });

            items.clear();
            items.addAll(newItems);
            diffResult.dispatchUpdatesTo(this);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_mira_sale, p, false));
        }
@Override
public void onBindViewHolder(@NonNull VH h, int pos) {
    MiraSaleItem s = items.get(pos);

    if (h.tvSaleId != null) h.tvSaleId.setText("#" + (s.saleId != null ? s.saleId : "—"));
    if (h.tvCustomer != null) h.tvCustomer.setText(s.customerName);
    
    // ✅ استخدم amountUSD (بالدولار) — CartAdapter يحوّل عند العرض
    if (h.tvAmount != null) {
        double amountToShow = s.amountUSD > 0 ? s.amountUSD : s.amount;
        h.tvAmount.setText(priceManager.formatPriceDefault(amountToShow));
    }
    
    if (h.tvItems != null) h.tvItems.setText(s.itemCount + " " + (isArabic ? "قطع" : "pcs"));

            if (h.tvStatus != null) {
                String status = s.refundStatus != null ? s.refundStatus : "completed";
                switch (status) {
                    case "refunded":
                    case "fully_refunded":
                        h.tvStatus.setText(isArabic ? "🔄 مرتجع" : "🔄 Refunded");
                        h.tvStatus.setTextColor(0xFFEF4444);
                        break;
                    case "partially_refunded":
                        h.tvStatus.setText(isArabic ? "🔄 مرتجع جزئياً" : "🔄 Partially Refunded");
                        h.tvStatus.setTextColor(0xFFF59E0B);
                        break;
                    case "completed":
                    default:
                        h.tvStatus.setText(isArabic ? "✅ مكتمل" : "✅ Completed");
                        h.tvStatus.setTextColor(0xFF22C55E);
                        break;
                }
                h.tvStatus.setVisibility(View.VISIBLE);
            }

            if (h.tvDate != null) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    Date d = sdf.parse(s.date);
                    SimpleDateFormat display = new SimpleDateFormat("dd/MM/yyyy hh:mm a", isArabic ? new Locale("ar") : Locale.US);
                    h.tvDate.setText(d != null ? display.format(d) : s.date);
                } catch (Exception e) {
                    h.tvDate.setText(s.date != null ? s.date : "—");
                }
            }

            h.itemView.setOnClickListener(v -> listener.onClick(s));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvSaleId, tvCustomer, tvAmount, tvItems, tvDate, tvStatus;

            VH(View v) {
                super(v);
                tvSaleId = v.findViewById(R.id.tvMiraSaleId);
                tvCustomer = v.findViewById(R.id.tvMiraSaleCustomer);
                tvAmount = v.findViewById(R.id.tvMiraSaleAmount);
                tvItems = v.findViewById(R.id.tvMiraSaleItems);
                tvDate = v.findViewById(R.id.tvMiraSaleDate);
                tvStatus = v.findViewById(R.id.tvMiraSaleStatus);
            }
        }
    }

    private interface OnMiraSaleClickListener {
        void onClick(MiraSaleItem sale);
    }
}
