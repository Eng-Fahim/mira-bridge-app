package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MiraScrapHistoryFragment extends Fragment {

    private RecyclerView rvHistory;
    private TextView tvEmpty;
    private ScrapHistoryAdapter adapter;
    private final List<JSONObject> historyList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_history, container, false);
        rvHistory = view.findViewById(R.id.rvScrapHistory);
        tvEmpty = view.findViewById(R.id.tvEmptyState);

        rvHistory.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ScrapHistoryAdapter(historyList);
        rvHistory.setAdapter(adapter);

        loadHistory();
        return view;
    }

    private void loadHistory() {
        int branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();
        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.getWithAuth("/scrap/history?branch_id=" + branchId + "&limit=100");
            
            if (response.isSuccess && response.data != null) {
                try {
                    JSONArray data = response.data.optJSONArray("data");
                    if (data != null) {
                        historyList.clear();
                        for (int i = 0; i < data.length(); i++) {
                            historyList.add(data.getJSONObject(i));
                        }
                        requireActivity().runOnUiThread(() -> {
                            adapter.notifyDataSetChanged();
                            tvEmpty.setVisibility(historyList.isEmpty() ? View.VISIBLE : View.GONE);
                        });
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  Adapter
    // ═══════════════════════════════════════════════════════
    private class ScrapHistoryAdapter extends RecyclerView.Adapter<ScrapHistoryAdapter.ViewHolder> {
        private final List<JSONObject> items;

        ScrapHistoryAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scrap_history, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    JSONObject item = items.get(position);
    holder.tvNumber.setText(item.optString("purchase_number"));
    holder.tvCustomer.setText(item.optString("customer_name", "عميل نقدي"));
    
    double weight = item.optDouble("gross_weight_grams", 0);
    String karat = item.optString("gold_karat", "21K");
    holder.tvDetails.setText(String.format(Locale.US, "%s | %.2f g", karat, weight));
    
    double amount = item.optDouble("total_amount", 0);
    holder.tvAmount.setText(String.format(Locale.US, "%,.2f SAR", amount));
    
    // ✅ عرض الإيصال عند الضغط
    holder.itemView.setOnClickListener(v -> {
        int purchaseId = item.optInt("id", 0);
        if (purchaseId > 0) {
            MiraScrapReceiptFragment receipt = 
                MiraScrapReceiptFragment.newInstance(purchaseId, item.toString());
            receipt.show(getParentFragmentManager(), "ScrapReceipt");
        }
    });
}

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvNumber, tvCustomer, tvDetails, tvAmount;
            ViewHolder(View v) {
                super(v);
                tvNumber = v.findViewById(R.id.tvItemNumber);
                tvCustomer = v.findViewById(R.id.tvItemCustomer);
                tvDetails = v.findViewById(R.id.tvItemDetails);
                tvAmount = v.findViewById(R.id.tvItemAmount);
            }
        }
    }
}
