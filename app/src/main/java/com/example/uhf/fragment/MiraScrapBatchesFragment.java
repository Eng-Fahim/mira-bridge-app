package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MiraScrapBatchesFragment extends Fragment {

    private RecyclerView rvBatches;
    private ExtendedFloatingActionButton fabNewBatch;
    private final List<JSONObject> batches = new ArrayList<>();
    private BatchAdapter adapter;
    private int branchId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_batches, container, false);
        rvBatches = view.findViewById(R.id.rvBatches);
        fabNewBatch = view.findViewById(R.id.fabNewBatch);
        
        branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();

        rvBatches.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BatchAdapter(batches);
        rvBatches.setAdapter(adapter);

        fabNewBatch.setOnClickListener(v -> openNewBatchDialog());
        
        loadBatches();
        return view;
    }

    private void loadBatches() {
        // دالة لجلب الدفعات من السيرفر (نفس نمط MiraTransferFragment)
        // ستحتاج لإضافة دالة getScrapBatches في MiraApiClient أولاً
    }

    private void openNewBatchDialog() {
        // 1. جلب الفواتير المتاحة
        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.getAvailableScrapPurchases(branchId);
            
            if (response.isSuccess && response.data != null) {
                JSONArray purchases = response.data.optJSONArray("data");
                if (purchases == null || purchases.length() == 0) {
                    requireActivity().runOnUiThread(() -> 
                        Toast.makeText(requireContext(), "لا توجد فواتير كسر متاحة للتصفية", Toast.LENGTH_SHORT).show());
                    return;
                }

                requireActivity().runOnUiThread(() -> showSelectionDialog(purchases));
            }
        }).start();
    }

    private void showSelectionDialog(JSONArray purchases) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("اختر الفواتير لضمها للدفعة");

        // تصميم بسيط للاختيار (يفضل استخدام RecyclerView متعدد الاختيارات في الإنتاج)
        String[] items = new String[purchases.length()];
        boolean[] checkedItems = new boolean[purchases.length()];
        
        for (int i = 0; i < purchases.length(); i++) {
            JSONObject p = purchases.optJSONObject(i);
            items[i] = p.optString("purchase_number") + " | " + p.optString("gold_karat") + " | " + p.optDouble("net_pure_weight_24k") + "g";
        }

        builder.setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> checkedItems[which] = isChecked);

        builder.setPositiveButton("إنشاء", (dialog, which) -> {
            JSONArray selectedIds = new JSONArray();
            for (int i = 0; i < checkedItems.length; i++) {
                if (checkedItems[i]) {
                    selectedIds.put(purchases.optJSONObject(i).optInt("id"));
                }
            }
            if (selectedIds.length() > 0) {
                // حوار إضافي لاختيار الوجهة
                showDestinationDialog(selectedIds);
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    private void showDestinationDialog(JSONArray selectedIds) {
        String[] destinations = {"مصفاة (Refinery)", "ورشة تصنيع (Workshop)", "تاجر جملة (Trader)"};
        new AlertDialog.Builder(requireContext())
            .setTitle("إلى أين ستُرسل هذه الدفعة؟")
            .setItems(destinations, (dialog, which) -> {
                String destType = which == 0 ? "refinery" : (which == 1 ? "workshop" : "trader");
                createBatch(selectedIds, destType);
            }).show();
    }

    private void createBatch(JSONArray ids, String destType) {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("branch_id", branchId);
                payload.put("destination_type", destType);
                payload.put("purchase_ids", ids);

                MiraApiClient api = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = api.createScrapBatch(payload);

                requireActivity().runOnUiThread(() -> {
                    if (response.isSuccess) {
                        Toast.makeText(requireContext(), "تم إنشاء الدفعة بنجاح", Toast.LENGTH_SHORT).show();
                        loadBatches();
                    } else {
                        Toast.makeText(requireContext(), "فشل الإنشاء: " + response.error, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    // Adapter بسيط (يمكنك تنسيقه بنفس شياكة item_transfer_row)
    private class BatchAdapter extends RecyclerView.Adapter<BatchAdapter.VH> {
        private final List<JSONObject> items;
        BatchAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            JSONObject b = items.get(position);
            holder.tv1.setText(b.optString("batch_number") + " - " + b.optString("status"));
            holder.tv2.setText(b.optDouble("sent_weight_24k") + "g (24K)");
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tv1, tv2;
            VH(View v) { super(v); tv1 = v.findViewById(android.R.id.text1); tv2 = v.findViewById(android.R.id.text2); }
        }
    }
}
