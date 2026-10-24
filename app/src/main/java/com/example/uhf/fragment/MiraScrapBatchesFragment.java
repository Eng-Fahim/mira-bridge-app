package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.navigation.NavigationManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MiraScrapBatchesFragment extends Fragment {

    private RecyclerView rvBatches;
    private TextView tvEmpty;
    private ExtendedFloatingActionButton fabNewBatch;
    private MaterialButton btnViewReport;
    
    private final List<JSONObject> batches = new ArrayList<>();
    private BatchAdapter adapter;
    private int branchId;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_batches, container, false);
        
        isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
        
        rvBatches = view.findViewById(R.id.rvBatches);
        tvEmpty = view.findViewById(R.id.tvEmptyBatches);
        fabNewBatch = view.findViewById(R.id.fabNewBatch);
        btnViewReport = view.findViewById(R.id.btnViewReport);
        
        branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();

        rvBatches.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BatchAdapter(batches);
        rvBatches.setAdapter(adapter);

        // ✅ ربط زر إنشاء الدفعة
        fabNewBatch.setOnClickListener(v -> openNewBatchDialog());
        
        // ✅ ربط زر تقرير الأرباح
        btnViewReport.setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(
                    new MiraScrapProfitReportFragment(), 
                    NavigationManager.TAG_SCRAP_PROFIT_REPORT
                );
            }
        });

        // ✅ [إضافة جديدة] ربط زر تقرير الأعمار (Aging)
        MaterialButton btnAging = view.findViewById(R.id.btnViewAging);
        if (btnAging != null) {
            btnAging.setOnClickListener(v -> {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).loadFragment(
                        new MiraScrapAgingFragment(), NavigationManager.TAG_SCRAP_AGING);
                }
            });
        }
        
        loadBatches();
        return view;
    }

    // ✅ هذه الدالة كانت فارغة، الآن تم تنفيذها
    private void loadBatches() {
        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.getScrapBatches(branchId);
            
            if (getActivity() == null) return;
            
            getActivity().runOnUiThread(() -> {
                batches.clear();
                
                if (response.isSuccess && response.data != null) {
                    JSONArray data = response.data.optJSONArray("data");
                    if (data != null && data.length() > 0) {
                        for (int i = 0; i < data.length(); i++) {
                            batches.add(data.optJSONObject(i));
                        }
                        tvEmpty.setVisibility(View.GONE);
                        rvBatches.setVisibility(View.VISIBLE);
                    } else {
                        tvEmpty.setVisibility(View.VISIBLE);
                        rvBatches.setVisibility(View.GONE);
                    }
                } else {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvBatches.setVisibility(View.GONE);
                }
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void openNewBatchDialog() {
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
                showDestinationDialog(selectedIds);
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    private void showDestinationDialog(JSONArray selectedIds) {
        String[] destinations = {"🏭 مصفاة (Refinery)", "🔨 ورشة تصنيع (Workshop)", "🤝 تاجر جملة (Trader)"};
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

    // ✅ تصميم بطاقة الدفعة بشكل احترافي
    private class BatchAdapter extends RecyclerView.Adapter<BatchAdapter.VH> {
        private final List<JSONObject> items;
        BatchAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull @Override 
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scrap_batch, parent, false));
        }
        
        @Override 
        public void onBindViewHolder(@NonNull VH holder, int position) {
            JSONObject b = items.get(position);
            
            String status = b.optString("status", "draft");
            String statusText = "";
            int statusColor = 0xFF78909C;
            
            switch (status) {
                case "draft": statusText = "📝 مسودة"; statusColor = 0xFF78909C; break;
                case "in_transit": statusText = "🚚 في الطريق"; statusColor = 0xFF1976D2; break;
                case "received": statusText = "📥 مستلمة"; statusColor = 0xFF388E3C; break;
                case "cancelled": statusText = "❌ ملغاة"; statusColor = 0xFFDC2626; break;
            }
            
            holder.tvNumber.setText(b.optString("batch_number"));
            holder.tvStatus.setText(statusText);
            holder.tvStatus.setTextColor(statusColor);
            
            String dest = b.optString("destination_type");
            if (dest.equals("refinery")) dest = "🏭 مصفاة";
            else if (dest.equals("workshop")) dest = "🔨 ورشة";
            else if (dest.equals("trader")) dest = "🤝 تاجر";
            
            holder.tvDestination.setText(dest);
            holder.tvWeight.setText(String.format(Locale.US, "%.3f g (24K)", b.optDouble("sent_weight_24k", 0)));
        }
        
        @Override public int getItemCount() { return items.size(); }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvNumber, tvStatus, tvDestination, tvWeight;
            VH(View v) { 
                super(v); 
                tvNumber = v.findViewById(R.id.tvBatchNumber);
                tvStatus = v.findViewById(R.id.tvBatchStatus);
                tvDestination = v.findViewById(R.id.tvBatchDestination);
                tvWeight = v.findViewById(R.id.tvBatchWeight);
            }
        }
    }
}
