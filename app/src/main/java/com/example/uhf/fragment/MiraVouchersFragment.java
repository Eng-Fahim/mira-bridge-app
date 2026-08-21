package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraVouchersFragment extends Fragment {

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    private RecyclerView rvVouchers;
    private Button btnNewVoucher;
    private List<VoucherItem> voucherList = new ArrayList<>();
    private VoucherAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_vouchers, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        
        rvVouchers = view.findViewById(R.id.rvVouchers);
        btnNewVoucher = view.findViewById(R.id.btnNewVoucher);
        
        rvVouchers.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new VoucherAdapter(voucherList);
        rvVouchers.setAdapter(adapter);
        
        btnNewVoucher.setOnClickListener(v -> showCreateVoucherDialog());
        
        loadVouchers();
        
        return view;
    }

    private void loadVouchers() {
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getVouchers(String.valueOf(branchId), "");
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        parseVouchers(response.data);
                    }
                });
            }
        });
    }

    private void parseVouchers(JSONObject data) {
        try {
            voucherList.clear();
            JSONArray items = data.optJSONArray("data");
            
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    VoucherItem item = new VoucherItem();
                    item.id = obj.optInt("id", i + 1);
                    item.voucherNumber = obj.optString("voucher_number", "");
                    item.voucherType = obj.optString("voucher_type", "");
                    item.amountMoney = obj.optString("amount_money", "0");
                    item.amountGold = obj.optString("amount_gold_grams", "0");
                    item.date = obj.optString("created_at", "");
                    voucherList.add(item);
                }
            }
            
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showCreateVoucherDialog() {
        // نافذة إنشاء سند جديد
        Toast.makeText(getContext(), "إنشاء سند جديد", Toast.LENGTH_SHORT).show();
    }

    private static class VoucherItem {
        int id;
        String voucherNumber;
        String voucherType;
        String amountMoney;
        String amountGold;
        String date;
    }

    private class VoucherAdapter extends RecyclerView.Adapter<VoucherAdapter.ViewHolder> {
        private List<VoucherItem> items;
        
        VoucherAdapter(List<VoucherItem> items) { this.items = items; }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VoucherItem item = items.get(position);
            holder.tvTitle.setText(item.voucherNumber + " - " + item.voucherType);
            holder.tvSubtitle.setText("نقد: " + item.amountMoney + " | ذهب: " + item.amountGold + "g");
        }
        
        @Override
        public int getItemCount() { return items.size(); }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            android.widget.TextView tvTitle, tvSubtitle;
            ViewHolder(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(android.R.id.text1);
                tvSubtitle = itemView.findViewById(android.R.id.text2);
            }
        }
    }
}
