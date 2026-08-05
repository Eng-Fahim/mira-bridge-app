package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

/**
 * MIRA Sales History Fragment
 * 
 * يعرض جميع عمليات البيع للفرع النشط
 * - مدير الجهة يرى جميع الفروع
 * - مدير الفرع يرى فرعه فقط
 * - إمكانية فتح إيصال أي عملية ومشاركتها أو طباعتها
 * - دعم عربي/إنجليزي كامل
 */
public class MiraSalesHistoryFragment extends Fragment {

    // ==================== UI ====================
    private RecyclerView rvSales;
    private TextView tvEmpty;
    private MiraSalesAdapter adapter;
    private final List<MiraSaleItem> saleList = new ArrayList<>();

    // ==================== Managers ====================
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isArabic;

    // ==================== Interface ====================
    private interface OnMiraSaleClickListener {
        void onClick(MiraSaleItem sale);
    }

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_sales_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        rvSales = view.findViewById(R.id.rvMiraSalesHistory);
        tvEmpty = view.findViewById(R.id.tvMiraEmptySales);

        rvSales.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new MiraSalesAdapter(saleList, this::openReceipt, isArabic);
        rvSales.setAdapter(adapter);

        loadSalesHistory();
    }

    // ==================== Load Data ====================

    private void loadSalesHistory() {
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int branchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = apiClient.getSalesHistory();

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray sales = response.data.optJSONArray("sales");
                    List<MiraSaleItem> items = new ArrayList<>();

                    if (sales != null) {
                        for (int i = 0; i < sales.length(); i++) {
                            JSONObject sale = sales.optJSONObject(i);
                            if (sale == null) continue;

                            MiraSaleItem item = new MiraSaleItem();
                            item.saleId = sale.optString("sale_id", sale.optString("id", "—"));
                            item.customerName = sale.optString("customer_name", isArabic ? "عميل" : "Customer");
                            item.amount = sale.optDouble("final_amount", 0);
                            item.itemCount = sale.optInt("item_count", sale.optInt("total_items", 0));
                            item.paymentMethod = sale.optString("payment_method", "");
                            item.date = sale.optString("created_at", "");
                            item.rawJson = sale.toString();
                            items.add(item);
                        }
                    }

                    mainHandler.post(() -> {
                        saleList.clear();
                        saleList.addAll(items);
                        adapter.notifyDataSetChanged();

                        if (tvEmpty != null) {
                            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                            if (items.isEmpty()) {
                                tvEmpty.setText(isArabic ? "📭 لا توجد عمليات بيع حتى الآن" : "📭 No sales yet");
                            }
                        }
                    });
                } else {
                    mainHandler.post(() -> {
                        if (tvEmpty != null) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText(isArabic ? "⚠️ تعذر تحميل المبيعات" : "⚠️ Failed to load sales");
                        }
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (tvEmpty != null) {
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText(isArabic ? "⚠️ خطأ في التحميل" : "⚠️ Load error");
                    }
                });
            }
        });
    }

    // ==================== Open Receipt ====================

    private void openReceipt(MiraSaleItem sale) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).openReceiptFragment(sale.rawJson);
            } else {
                MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(sale.rawJson);
                getParentFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, receipt)
                    .addToBackStack("mira_receipt")
                    .commit();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), isArabic ? "خطأ في فتح الإيصال" : "Error opening receipt", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Data Class ====================

    static class MiraSaleItem {
        String saleId, customerName, paymentMethod, date, rawJson;
        double amount;
        int itemCount;
    }

    // ==================== Adapter ====================

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
}
