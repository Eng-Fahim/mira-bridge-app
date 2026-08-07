package com.example.uhf.fragment;

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraSalesHistoryFragment extends Fragment {

    private static final String TAG = "MiraSalesHistory";

    private RecyclerView rvSales;
    private TextView tvEmpty;
    private EditText etSearch;
    private ImageView btnRefresh;

    private MiraSalesAdapter adapter;
    private final List<MiraSaleItem> saleList = new ArrayList<>();
    private final List<MiraSaleItem> filteredList = new ArrayList<>();

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_sales_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        
        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
        }

        rvSales = view.findViewById(R.id.rvMiraSalesHistory);
        tvEmpty = view.findViewById(R.id.tvMiraEmptySales);
        etSearch = view.findViewById(R.id.etMiraSearchSales);
        btnRefresh = view.findViewById(R.id.btnMiraRefreshSales);

        if (rvSales != null) {
            rvSales.setLayoutManager(new LinearLayoutManager(getContext()));
            adapter = new MiraSalesAdapter(filteredList, this::openReceipt, isArabic);
            rvSales.setAdapter(adapter);
        }

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadSalesHistory());
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterSales(s.toString()); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        loadSalesHistory();
    }

    private void loadSalesHistory() {
        if (!isAdded()) return;

        if (tvEmpty != null) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(isArabic ? "جاري تحميل المبيعات..." : "Loading sales...");
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (apiClient == null && getContext() != null) {
                    apiClient = MiraApiClient.getInstance(getContext());
                }

                if (apiClient == null) {
                    mainHandler.post(() -> showEmptyState(isArabic ? "⚠️ خطأ في تهيئة النظام" : "⚠️ System initialization error"));
                    return;
                }

                MiraApiClient.ApiResponse response = apiClient.getSalesHistory();

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray sales = null;

                    // مرونة في قراءة مصفوفة المبيعات بجميع احتمالات رد الـ REST API
                    if (response.data.has("sales")) {
                        sales = response.data.optJSONArray("sales");
                    } else if (response.data.has("data")) {
                        Object dataObj = response.data.opt("data");
                        if (dataObj instanceof JSONArray) {
                            sales = (JSONArray) dataObj;
                        } else if (dataObj instanceof JSONObject) {
                            sales = ((JSONObject) dataObj).optJSONArray("sales");
                        }
                    }

                    List<MiraSaleItem> items = new ArrayList<>();

                    if (sales != null && sales.length() > 0) {
                        for (int i = 0; i < sales.length(); i++) {
                            JSONObject sale = sales.optJSONObject(i);
                            if (sale == null) continue;

                            MiraSaleItem item = new MiraSaleItem();
                            item.saleId = sale.optString("sale_id", sale.optString("id", sale.optString("invoice_no", "—")));
                            item.customerName = sale.optString("customer_name", sale.optString("client_name", isArabic ? "عميل" : "Customer"));
                            item.amount = sale.optDouble("final_amount", sale.optDouble("total_amount", sale.optDouble("amount", 0)));
                            item.itemCount = sale.optInt("item_count", sale.optInt("total_items", sale.optInt("count", 0)));
                            item.paymentMethod = sale.optString("payment_method", sale.optString("pay_type", "CASH"));
                            item.date = sale.optString("created_at", sale.optString("date", ""));
                            item.rawJson = sale.toString();
                            items.add(item);
                        }
                    }

                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        saleList.clear();
                        saleList.addAll(items);
                        
                        String currentQuery = etSearch != null ? etSearch.getText().toString() : "";
                        filterSales(currentQuery);

                        if (saleList.isEmpty()) {
                            showEmptyState(isArabic ? "📭 لا توجد مبيعات مسجلة" : "📭 No sales recorded");
                        }
                    });
                } else {
                    String errorMsg = (response != null && response.error != null) ? response.error : "";
                    Log.e(TAG, "Sales load failed code: " + (response != null ? response.code : -1) + ", error: " + errorMsg);
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        showEmptyState(isArabic ? "⚠️ تعذر تحميل المبيعات (" + (response != null ? response.code : "Error") + ")" : "⚠️ Failed to load sales");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception loading sales", e);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showEmptyState(isArabic ? "⚠️ خطأ في الاتصال بالخادم" : "⚠️ Connection error");
                });
            }
        });
    }

    private void filterSales(String query) {
        filteredList.clear();
        if (query == null || query.trim().isEmpty()) {
            filteredList.addAll(saleList);
        } else {
            String q = query.toLowerCase(Locale.ROOT);
            for (MiraSaleItem item : saleList) {
                if ((item.customerName != null && item.customerName.toLowerCase(Locale.ROOT).contains(q)) || 
                    (item.saleId != null && item.saleId.toLowerCase(Locale.ROOT).contains(q))) {
                    filteredList.add(item);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (tvEmpty != null) {
            tvEmpty.setVisibility(filteredList.isEmpty() ? View.VISIBLE : View.GONE);
            if (filteredList.isEmpty() && !saleList.isEmpty()) {
                tvEmpty.setText(isArabic ? "📭 لا توجد نتائج مطابقة" : "📭 No matching results");
            }
        }
    }

    private void showEmptyState(String message) {
        if (tvEmpty != null) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(message);
        }
    }

    private void openReceipt(MiraSaleItem sale) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).openReceiptFragment(sale.rawJson);
            } else if (isAdded()) {
                MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(sale.rawJson);
                getParentFragmentManager().beginTransaction()
                    .replace(android.R.id.content, receipt)
                    .addToBackStack("mira_receipt")
                    .commit();
            }
        } catch (Exception e) {
            if (getContext() != null) {
                Toast.makeText(getContext(), isArabic ? "خطأ في فتح الإيصال" : "Error opening receipt", Toast.LENGTH_SHORT).show();
            }
        }
    }

    static class MiraSaleItem {
        String saleId, customerName, paymentMethod, date, rawJson;
        double amount;
        int itemCount;
    }

    class MiraSalesAdapter extends RecyclerView.Adapter<MiraSalesAdapter.VH> {
        final List<MiraSaleItem> items;
        final OnMiraSaleClickListener listener;
        final boolean isArabic;

        MiraSalesAdapter(List<MiraSaleItem> items, OnMiraSaleClickListener listener, boolean isArabic) {
            this.items = items;
            this.listener = listener;
            this.isArabic = isArabic;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_mira_sale, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MiraSaleItem s = items.get(pos);

            if (h.tvCustomer != null) h.tvCustomer.setText(s.customerName);
            if (h.tvAmount != null) h.tvAmount.setText(String.format(Locale.US, "%,.0f YER", s.amount));
            if (h.tvItems != null) h.tvItems.setText(s.itemCount + " " + (isArabic ? "قطعة" : "pcs"));
            if (h.tvPayment != null) {
                String paymentAr;
                switch (s.paymentMethod != null ? s.paymentMethod.toUpperCase() : "") {
                    case "CASH": paymentAr = isArabic ? "نقداً" : "Cash"; break;
                    case "CARD": paymentAr = isArabic ? "بطاقة" : "Card"; break;
                    case "TRANSFER": paymentAr = isArabic ? "تحويل" : "Transfer"; break;
                    default: paymentAr = s.paymentMethod != null ? s.paymentMethod : "—";
                }
                h.tvPayment.setText(paymentAr);
            }
            if (h.tvDate != null) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                    Date d = sdf.parse(s.date);
                    SimpleDateFormat display = new SimpleDateFormat("dd/MM/yyyy HH:mm", isArabic ? new Locale("ar") : Locale.US);
                    h.tvDate.setText(d != null ? display.format(d) : s.date);
                } catch (Exception e) {
                    h.tvDate.setText(s.date != null ? s.date : "—");
                }
            }
            h.itemView.setOnClickListener(v -> listener.onClick(s));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCustomer, tvAmount, tvItems, tvPayment, tvDate;
            VH(View v) {
                super(v);
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
