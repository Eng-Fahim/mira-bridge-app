package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MiraBranchPickerFragment extends BottomSheetDialogFragment {

    private RecyclerView rvBranches;
    private TextView tvTitle, tvEmpty;
    private BranchAdapter adapter;
    private final List<BranchItem> branchList = new ArrayList<>();
    private OnBranchSelectedListener listener;
    private MiraBranchesManager branchesManager;

    public interface OnBranchSelectedListener {
        void onBranchSelected(int branchId, String branchName, String branchCode);
    }

    public void setOnBranchSelectedListener(OnBranchSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_branch_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        branchesManager = MiraBranchesManager.getInstance(requireContext());

        rvBranches = view.findViewById(R.id.rvBranches);
        tvTitle = view.findViewById(R.id.tvBranchTitle);
        tvEmpty = view.findViewById(R.id.tvBranchEmpty);

        rvBranches.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BranchAdapter(branchList, this::selectBranch);
        rvBranches.setAdapter(adapter);

        // ✅ عرض الفرع النشط حالياً في العنوان
        if (tvTitle != null && branchesManager.hasActiveBranch()) {
            tvTitle.setText("اختيار الفرع (الحالي: " + branchesManager.getActiveBranchDisplayName() + ")");
        }

        loadBranches();
    }

    private void loadBranches() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                // ✅ إرسال branch_id الحالي مع الطلب
                int currentBranchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = client.getBranches();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (response != null && response.isSuccess && response.data != null) {
                            parseBranches(response.data, currentBranchId);
                        } else {
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("لا توجد فروع متاحة");
                        }
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText("خطأ في تحميل الفروع");
                    });
                }
            }
        });
    }

    private void parseBranches(JSONObject data, int currentBranchId) {
        branchList.clear();
        JSONArray branches = data.optJSONArray("branches");

        if (branches != null) {
            for (int i = 0; i < branches.length(); i++) {
                JSONObject b = branches.optJSONObject(i);
                if (b == null) continue;
                
                String status = b.optString("status", "active");
                if ("inactive".equals(status)) continue; // تجاهل الفروع غير النشطة
                
                BranchItem item = new BranchItem();
                item.id = b.optInt("id");
                item.name = b.optString("branch_name", "فرع " + (i + 1));
                item.code = b.optString("branch_code", "");
                item.address = b.optString("address", "");
                item.phone = b.optString("phone", "");
                item.isActive = true;
                item.itemCount = b.optInt("inventory_count", 0);
                item.employeeCount = b.optInt("user_count", 0);
                item.isCurrentBranch = (item.id == currentBranchId); // ✅ تحديد الفرع الحالي
                branchList.add(item);
            }
        }

        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(branchList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void selectBranch(BranchItem branch) {
        if (listener != null) {
            listener.onBranchSelected(branch.id, branch.name, branch.code);
        }
        dismiss();
    }

    static class BranchItem {
        int id, itemCount, employeeCount;
        String name, code, address, phone;
        boolean isActive;
        boolean isCurrentBranch; // ✅ للتمييز البصري
    }

    class BranchAdapter extends RecyclerView.Adapter<BranchAdapter.VH> {
        final List<BranchItem> list;
        final OnBranchClickListener clickListener;

        BranchAdapter(List<BranchItem> list, OnBranchClickListener listener) {
            this.list = list;
            this.clickListener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_branch_picker, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BranchItem b = list.get(pos);
            h.tvName.setText(b.name);
            h.tvCode.setText("🏷️ " + b.code);
            h.tvDetails.setText("📦 " + b.itemCount + " قطعة | 👥 " + b.employeeCount + " موظف");
            h.tvAddress.setText(b.address.isEmpty() ? "" : "📍 " + b.address);
            h.vStatus.setBackgroundResource(b.isActive ? R.drawable.circle_green : R.drawable.circle_red);
            
            // ✅ تمييز الفرع الحالي
            if (b.isCurrentBranch) {
                h.tvCurrentBadge.setVisibility(View.VISIBLE);
                h.tvCurrentBadge.setText("✅ الحالي");
            } else {
                h.tvCurrentBadge.setVisibility(View.GONE);
            }
            
            h.itemView.setOnClickListener(v -> clickListener.onClick(b));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCode, tvDetails, tvAddress, tvCurrentBadge;
            View vStatus;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvBranchName);
                tvCode = v.findViewById(R.id.tvBranchCode);
                tvDetails = v.findViewById(R.id.tvBranchDetails);
                tvAddress = v.findViewById(R.id.tvBranchAddress);
                tvCurrentBadge = v.findViewById(R.id.tvCurrentBadge);
                vStatus = v.findViewById(R.id.vBranchStatus);
            }
        }
    }

    interface OnBranchClickListener {
        void onClick(BranchItem branch);
    }
}
