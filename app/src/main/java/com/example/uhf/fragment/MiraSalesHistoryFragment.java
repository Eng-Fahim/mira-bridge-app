package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MiraSalesHistoryFragment extends Fragment {

    private RecyclerView rvSales;
    private MiraSalesAdapter adapter;
    private List<MiraSaleItem> saleList = new ArrayList<>();
    private MiraApiClient apiClient;
    private TextView tvEmpty;

    // ⭐ الـ interface هنا - خارج الـ Adapter
    private interface OnMiraSaleClickListener { 
        void onClick(MiraSaleItem sale); 
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_sales_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvSales = view.findViewById(R.id.rvMiraSalesHistory);
        tvEmpty = view.findViewById(R.id.tvMiraEmptySales);
        apiClient = MiraApiClient.getInstance(requireContext());

        rvSales.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new MiraSalesAdapter(saleList, this::openReceipt);
        rvSales.setAdapter(adapter);
        loadSalesHistory();
    }

    private void loadSalesHistory() {
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getSalesHistory();
                if (response.isSuccess && response.data != null) {
                    JSONArray sales = response.data.optJSONArray("sales");
                    List<MiraSaleItem> items = new ArrayList<>();
                    if (sales != null) {
                        for (int i = 0; i < sales.length(); i++) {
                            JSONObject sale = sales.optJSONObject(i);
                            if (sale != null) {
                                items.add(new MiraSaleItem(
                                    sale.optString("sale_id", ""),
                                    sale.optString("customer_name", "—"),
                                    sale.optDouble("final_amount", 0),
                                    sale.optInt("item_count", 0),
                                    sale.optString("payment_method", ""),
                                    sale.optString("created_at", ""),
                                    sale.toString()
                                ));
                            }
                        }
                    }
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            saleList.clear();
                            saleList.addAll(items);
                            adapter.notifyDataSetChanged();
                            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                        });
                    }
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "خطأ في تحميل سجل المبيعات", Toast.LENGTH_SHORT).show()
                    );
                }
            }
        }).start();
    }

    private void openReceipt(MiraSaleItem sale) {
        MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(sale.rawJson);
        getParentFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, receipt)
                .addToBackStack("mira_receipt")
                .commit();
    }

    static class MiraSaleItem {
        String saleId, customerName, paymentMethod, date, rawJson;
        double amount;
        int itemCount;

        MiraSaleItem(String saleId, String customerName, double amount, int itemCount,
                     String paymentMethod, String date, String rawJson) {
            this.saleId = saleId;
            this.customerName = customerName;
            this.amount = amount;
            this.itemCount = itemCount;
            this.paymentMethod = paymentMethod;
            this.date = date;
            this.rawJson = rawJson;
        }
    }

    class MiraSalesAdapter extends RecyclerView.Adapter<MiraSalesAdapter.VH> {
        List<MiraSaleItem> items;
        OnMiraSaleClickListener listener; // ⭐ يستخدم الـ interface من الأعلى

        MiraSalesAdapter(List<MiraSaleItem> items, OnMiraSaleClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_mira_sale, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            MiraSaleItem s = items.get(pos);
            h.tvCustomer.setText(s.customerName);
            h.tvAmount.setText(String.format(Locale.US, "$%,.2f", s.amount));
            h.tvItems.setText(s.itemCount + " قطعة");
            h.tvPayment.setText(s.paymentMethod);
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                Date d = sdf.parse(s.date);
                SimpleDateFormat display = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("ar"));
                h.tvDate.setText(d != null ? display.format(d) : s.date);
            } catch (Exception e) { h.tvDate.setText(s.date); }
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
