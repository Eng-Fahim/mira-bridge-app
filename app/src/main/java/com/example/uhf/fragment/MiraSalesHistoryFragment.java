package com.example.uhf.fragment;

import android.content.Context;
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
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

public class MiraSalesHistoryFragment extends Fragment {

    private static final String TAG = "MiraSalesHistory";
    private static final int PAGE_SIZE = 50; // عدد العمليات في كل صفحة
    private static final int VISIBLE_THRESHOLD = 10; // تحميل المزيد قبل الوصول للنهاية بـ 10 عناصر

    private RecyclerView rvSales;
    private View layoutEmptyState;
    private TextView tvEmpty;
    private ProgressBar pbLoading;
    private ProgressBar pbLoadMore; // مؤشر تحميل المزيد
    private EditText etSearch;
    private View btnRefresh;
    private SwipeRefreshLayout swipeRefresh;
    
    private View loadingMoreContainer;
    private TextView tvEndOfList;
    private TextView tvTotalRevenue;
    private TextView tvInvoicesCount;
    private TextView tvShowingCount; // عداد العناصر المعروضة

    private MiraSalesAdapter adapter;
    private final List<MiraSaleItem> saleList = new ArrayList<>();
    private final List<MiraSaleItem> filteredList = new ArrayList<>();

    // Pagination variables
    private int currentPage = 0;
    private boolean isLoading = false;
    private boolean isLastPage = false;
    private boolean hasMoreData = true;
    private int totalItems = 0;
    private String searchQuery = "";
    private String currentFilter = "ALL"; // ALL, CASH, CARD, TRANSFER, CREDIT

    private static final String OFFLINE_PREFS = "sales_history_cache";
    private SharedPreferences offlineCache;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraPriceManager priceManager;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArabic;

    private boolean checkIsArabic() {
        return AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar") ||
                getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
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

        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            priceManager = MiraPriceManager.getInstance(getContext());
            permissionManager = PermissionManager.getInstance(getContext());
            offlineCache = requireContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        }

        initViews(view);
        setupRecyclerView();
        setupListeners();
        applyPermissions();

        loadSalesOffline();
        refreshSales(true);
    }

    private void applyPermissions() {
        boolean canView = permissionManager.canView("sales_history") || 
                         permissionManager.isPosOperator() || 
                         permissionManager.isManager();
        
        if (!canView) {
            showEmptyState(isArabic ? "⛔ غير مصرح لك بالوصول لسجل المبيعات" : "⛔ Access Denied");
            if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            return;
        }
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
    
    // العناصر الجديدة
    pbLoadMore = view.findViewById(R.id.pbLoadMore);
    tvShowingCount = view.findViewById(R.id.tvShowingCount);
    
    // يمكنك إضافة مراجع للعناصر الجديدة
    View loadingMoreContainer = view.findViewById(R.id.loadingMoreContainer);
    TextView tvEndOfList = view.findViewById(R.id.tvEndOfList);
    
    // تخزين المراجع كمتغيرات عامة إذا كنت بحاجة إليها
    this.loadingMoreContainer = loadingMoreContainer;
    this.tvEndOfList = tvEndOfList;
}

    private void setupRecyclerView() {
        if (rvSales != null) {
            LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
            rvSales.setLayoutManager(layoutManager);
            
            // تحسين الأداء
            rvSales.setHasFixedSize(true);
            rvSales.setItemViewCacheSize(20);
            rvSales.setDrawingCacheEnabled(true);
            rvSales.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
            
            adapter = new MiraSalesAdapter(filteredList, this::openReceipt, isArabic, priceManager);
            rvSales.setAdapter(adapter);
            
            // إضافة مستمع للتمرير للتحميل التدريجي
            rvSales.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    
                    if (dy > 0) { // التمرير للأسفل
                        int totalItemCount = layoutManager.getItemCount();
                        int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                        
                        if (!isLoading && hasMoreData && 
                            totalItemCount <= (lastVisibleItem + VISIBLE_THRESHOLD)) {
                            loadMoreSales();
                        }
                    }
                }
            });
        }
    }

    private void setupListeners() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> refreshSales(true));
        }

        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(() -> refreshSales(false));
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchQuery = s.toString();
                    // تأخير البحث لتجنب البحث مع كل حرف
                    mainHandler.removeCallbacks(searchRunnable);
                    mainHandler.postDelayed(searchRunnable, 300);
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }
    
    private final Runnable searchRunnable = () -> {
        filterSales(searchQuery);
    };

    private void saveSalesOffline(JSONArray sales, int page) {
        try {
            if (offlineCache == null) return;
            
            SharedPreferences.Editor editor = offlineCache.edit();
            
            if (page == 0) {
                // أول صفحة - حذف البيانات القديمة
                editor.putString("sales_page_0", sales.toString());
                editor.putInt("last_page", 0);
                editor.putInt("total_items", sales.length());
            } else {
                // صفحات إضافية - إضافة للبيانات الموجودة
                editor.putString("sales_page_" + page, sales.toString());
                editor.putInt("last_page", page);
                
                // تحديث العدد الإجمالي
                int currentTotal = offlineCache.getInt("total_items", 0);
                editor.putInt("total_items", currentTotal + sales.length());
            }
            
            editor.putLong("last_update", System.currentTimeMillis());
            editor.apply();
        } catch (Exception ignored) {}
    }

    private void loadSalesOffline() {
        try {
            if (offlineCache == null) return;
            
            int lastPage = offlineCache.getInt("last_page", -1);
            if (lastPage < 0) return;
            
            List<MiraSaleItem> offlineItems = new ArrayList<>();
            
            for (int i = 0; i <= lastPage; i++) {
                String json = offlineCache.getString("sales_page_" + i, "");
                if (!json.isEmpty()) {
                    JSONArray sales = new JSONArray(json);
                    offlineItems.addAll(parseSalesJson(sales));
                }
            }
            
            if (!offlineItems.isEmpty()) {
                saleList.clear();
                saleList.addAll(offlineItems);
                totalItems = offlineCache.getInt("total_items", offlineItems.size());
                
                String currentQuery = etSearch != null ? etSearch.getText().toString() : "";
                filterSales(currentQuery);
            }
            
        } catch (Exception ignored) {}
    }

    private void refreshSales(boolean showProgress) {
        if (!isAdded()) return;
        
        if (!permissionManager.canView("sales_history") && 
            !permissionManager.isPosOperator() && 
            !permissionManager.isManager()) {
            return;
        }

        // إعادة تعيين متغيرات الترحيل
        currentPage = 0;
        isLastPage = false;
        hasMoreData = true;
        isLoading = false;
        
        if (showProgress) {
            showLoadingState();
        }

        loadSalesPage(0, true);
    }
    
    private void loadMoreSales() {
    if (isLoading || !hasMoreData || isLastPage) {
        // إظهار نهاية القائمة إذا كانت آخر صفحة
        if (isLastPage && tvEndOfList != null) {
            tvEndOfList.setVisibility(View.VISIBLE);
        }
        return;
    }
    
    isLoading = true;
    if (loadingMoreContainer != null) {
        loadingMoreContainer.setVisibility(View.VISIBLE);
    }
    if (tvEndOfList != null) {
        tvEndOfList.setVisibility(View.GONE);
    }
    
    loadSalesPage(currentPage + 1, false);
}
    
    private void loadSalesPage(int page, boolean isRefresh) {
    Executors.newSingleThreadExecutor().execute(() -> {
        try {
            if (apiClient == null && getContext() != null) {
                apiClient = MiraApiClient.getInstance(getContext());
            }

            if (apiClient == null) {
                mainHandler.post(() -> {
                    hideLoadingState();
                    if (saleList.isEmpty()) {
                        showEmptyState(isArabic ? "⚠️ خطأ في تهيئة النظام" : "⚠️ System initialization error");
                    }
                });
                return;
            }

            // استخدام الدالة الجديدة مع البحث والفلترة
            MiraApiClient.ApiResponse response;
            
            if (!searchQuery.isEmpty() || !currentFilter.equals("ALL")) {
                // استخدام البحث المتقدم
                response = apiClient.getSalesHistoryAdvanced(
                    searchQuery, 
                    currentFilter, 
                    null, // fromDate
                    null, // toDate
                    page, 
                    PAGE_SIZE
                );
            } else {
                // تحميل عادي مع pagination
                response = apiClient.getSalesHistoryPaginated(page, PAGE_SIZE);
            }

            if (response != null && response.isSuccess && response.data != null) {
                JSONArray sales = null;
                int totalCount = 0;
                boolean hasMore = false;

                // استخراج البيانات
                if (response.data.has("sales")) {
                    sales = response.data.optJSONArray("sales");
                } else if (response.data.has("data")) {
                    Object dataObj = response.data.opt("data");
                    if (dataObj instanceof JSONArray) {
                        sales = (JSONArray) dataObj;
                    } else if (dataObj instanceof JSONObject) {
                        JSONObject dataJson = (JSONObject) dataObj;
                        sales = dataJson.optJSONArray("sales");
                        if (sales == null) {
                            sales = dataJson.optJSONArray("items");
                        }
                        totalCount = dataJson.optInt("total", dataJson.optInt("total_count", 0));
                        hasMore = dataJson.optBoolean("has_more", false);
                    }
                }

                // استخراج العدد الإجمالي
                if (totalCount == 0) {
                    totalCount = response.data.optInt("total", response.data.optInt("total_count", 0));
                }
                
                // استخراج has_more
                if (!hasMore) {
                    hasMore = response.data.optBoolean("has_more", false);
                }

                final List<MiraSaleItem> items = (sales != null) ? parseSalesJson(sales) : new ArrayList<>();
                final int finalTotalCount = totalCount;
                final boolean finalHasMore = hasMore;
                
                // حفظ في الكاش
                if (sales != null && sales.length() > 0) {
                    saveSalesOffline(sales, page);
                }
                
                // تحديد إذا كانت هذه آخر صفحة
                boolean isLast = items.size() < PAGE_SIZE || 
                               (finalTotalCount > 0 && (page + 1) * PAGE_SIZE >= finalTotalCount) ||
                               !finalHasMore;

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    if (isRefresh) {
                        hideLoadingState();
                        saleList.clear();
                        saleList.addAll(items);
                        
                        if (finalTotalCount > 0) {
                            totalItems = finalTotalCount;
                        } else {
                            totalItems = items.size();
                        }
                        
                        currentPage = 0;
                        hasMoreData = !isLast && items.size() == PAGE_SIZE;
                        isLastPage = isLast;
                        
                    } else {
                        // إضافة عناصر جديدة
                        saleList.addAll(items);
                        
                        currentPage = page;
                        hasMoreData = !isLast && items.size() == PAGE_SIZE;
                        isLastPage = isLast;
                    }
                    
                    // تحديث الواجهة
                    String currentQuery = etSearch != null ? etSearch.getText().toString() : "";
                    filterSales(currentQuery);
                    
                    if (saleList.isEmpty()) {
                        showEmptyState(isArabic ? "📭 لا توجد مبيعات مسجلة" : "📭 No sales recorded");
                    }
                    
                    // إخفاء مؤشر التحميل هنا - المكان الصحيح
                    isLoading = false;
                    if (pbLoadMore != null) {
                        pbLoadMore.setVisibility(View.GONE);
                    }
                    
                    // إخفاء حاوية التحميل الإضافي
                    if (loadingMoreContainer != null) {
                        loadingMoreContainer.setVisibility(View.GONE);
                    }
                    
                    // إظهار نهاية القائمة إذا كانت آخر صفحة
                    if (isLastPage && tvEndOfList != null) {
                        tvEndOfList.setVisibility(View.VISIBLE);
                    } else if (tvEndOfList != null) {
                        tvEndOfList.setVisibility(View.GONE);
                    }
                });
            } else {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    hideLoadingState();
                    isLoading = false;
                    if (pbLoadMore != null) {
                        pbLoadMore.setVisibility(View.GONE);
                    }
                    
                    if (loadingMoreContainer != null) {
                        loadingMoreContainer.setVisibility(View.GONE);
                    }
                    
                    if (saleList.isEmpty()) {
                        showEmptyState(isArabic ? "📭 لا توجد مبيعات مسجلة" : "📭 No sales recorded");
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception loading sales", e);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                hideLoadingState();
                isLoading = false;
                if (pbLoadMore != null) {
                    pbLoadMore.setVisibility(View.GONE);
                }
                
                // إخفاء حاوية التحميل الإضافي في حالة الخطأ
                if (loadingMoreContainer != null) {
                    loadingMoreContainer.setVisibility(View.GONE);
                }
                
                if (saleList.isEmpty()) {
                    showEmptyState(isArabic ? "⚠️ خطأ في تحميل البيانات" : "⚠️ Error loading data");
                }
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
            item.amount = sale.optDouble("final_amount", sale.optDouble("total_amount", sale.optDouble("amount", 0)));
            item.itemCount = sale.optInt("item_count", sale.optInt("total_items", sale.optInt("count", 0)));
            item.paymentMethod = sale.optString("payment_method", sale.optString("pay_type", "CASH"));
            item.date = sale.optString("created_at", sale.optString("date", ""));
            item.rawJson = sale.toString();
            items.add(item);
        }
        return items;
    }

    private void filterSales(String query) {
        List<MiraSaleItem> newFiltered = new ArrayList<>();
        
        if ((query == null || query.trim().isEmpty()) && currentFilter.equals("ALL")) {
            newFiltered.addAll(saleList);
        } else {
            String q = (query != null) ? query.toLowerCase(Locale.ROOT) : "";
            
            for (MiraSaleItem item : saleList) {
                // فلترة حسب نوع الدفع
                if (!currentFilter.equals("ALL")) {
                    String paymentMethod = item.paymentMethod != null ? item.paymentMethod.toUpperCase(Locale.ROOT) : "";
                    if (!paymentMethod.equals(currentFilter)) {
                        continue;
                    }
                }
                
                // فلترة حسب البحث
                if (!q.isEmpty()) {
                    boolean matchesCustomer = item.customerName != null && 
                        item.customerName.toLowerCase(Locale.ROOT).contains(q);
                    boolean matchesSaleId = item.saleId != null && 
                        item.saleId.toLowerCase(Locale.ROOT).contains(q);
                    
                    if (!matchesCustomer && !matchesSaleId) {
                        continue;
                    }
                }
                
                newFiltered.add(item);
            }
        }

        if (adapter != null) {
            adapter.updateData(newFiltered);
        }

        updateSummaryCards(newFiltered);

        if (newFiltered.isEmpty()) {
            if (!saleList.isEmpty()) {
                showEmptyState(isArabic ? "📭 لا توجد نتائج مطابقة" : "📭 No matching results");
            } else {
                showEmptyState(isArabic ? "📭 لا توجد مبيعات مسجلة" : "📭 No sales recorded");
            }
        } else {
            hideEmptyState();
        }
    }

    private void updateSummaryCards(List<MiraSaleItem> list) {
        double totalRevenue = 0;
        for (MiraSaleItem item : list) {
            totalRevenue += item.amount;
        }

        if (tvTotalRevenue != null) {
            tvTotalRevenue.setText(priceManager.formatPriceDefault(totalRevenue));
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
                Toast.makeText(getContext(), 
                    isArabic ? "خطأ في فتح الإيصال" : "Error opening receipt", 
                    Toast.LENGTH_SHORT).show();
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
            try {
                MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(receiptJson);
                getParentFragmentManager().beginTransaction()
                        .replace(android.R.id.content, receipt)
                        .addToBackStack("mira_receipt")
                        .commit();
            } catch (Exception ex) {
                Log.e(TAG, "Fallback also failed", ex);
            }
        }
    }

    static class MiraSaleItem {
        String saleId, customerName, paymentMethod, date, rawJson;
        double amount;
        int itemCount;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MiraSaleItem item = (MiraSaleItem) o;
            return Double.compare(item.amount, amount) == 0 &&
                    itemCount == item.itemCount &&
                    Objects.equals(saleId, item.saleId) &&
                    Objects.equals(customerName, item.customerName) &&
                    Objects.equals(paymentMethod, item.paymentMethod) &&
                    Objects.equals(date, item.date);
        }

        @Override
        public int hashCode() {
            return Objects.hash(saleId, customerName, paymentMethod, date, amount, itemCount);
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
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return Objects.equals(items.get(oldItemPosition).saleId, newItems.get(newItemPosition).saleId);
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    return items.get(oldItemPosition).equals(newItems.get(newItemPosition));
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
            
            if (h.tvAmount != null) h.tvAmount.setText(priceManager.formatPriceDefault(s.amount));
            
            if (h.tvItems != null) h.tvItems.setText(s.itemCount + " " + (isArabic ? "قطع" : "pcs"));

            if (h.tvPayment != null) {
                String paymentText;
                switch (s.paymentMethod != null ? s.paymentMethod.toUpperCase(Locale.ROOT) : "") {
                    case "CASH": paymentText = isArabic ? "نقداً" : "Cash"; break;
                    case "CARD": paymentText = isArabic ? "بطاقة" : "Card"; break;
                    case "TRANSFER": paymentText = isArabic ? "تحويل" : "Transfer"; break;
                    case "BANK_TRANSFER": paymentText = isArabic ? "تحويل بنكي" : "Bank Transfer"; break;
                    case "CREDIT": paymentText = isArabic ? "آجل" : "Credit"; break;
                    default: paymentText = s.paymentMethod != null ? s.paymentMethod : "—";
                }
                h.tvPayment.setText(paymentText);
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
            TextView tvSaleId, tvCustomer, tvAmount, tvItems, tvPayment, tvDate;

            VH(View v) {
                super(v);
                tvSaleId = v.findViewById(R.id.tvMiraSaleId);
                tvCustomer = v.findViewById(R.id.tvMiraSaleCustomer);
                tvAmount = v.findViewById(R.id.tvMiraSaleAmount);
                tvItems = v.findViewById(R.id.tvMiraSaleItems);
                tvPayment = v.findViewById(R.id.tvMiraSalePayment);
                tvDate = v.findViewById(R.id.tvMiraSaleDate);
            }
        }
    }

    private interface OnMiraSaleClickListener {
        void onClick(MiraSaleItem sale);
    }
}
