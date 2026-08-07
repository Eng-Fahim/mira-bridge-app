package com.example.uhf.fragment;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
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

        rvBranches = view.findViewById(R.id.rvBranches);
        tvTitle = view.findViewById(R.id.tvBranchTitle);
        tvEmpty = view.findViewById(R.id.tvBranchEmpty);

        rvBranches.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BranchAdapter(branchList, this::selectBranch);
        rvBranches.setAdapter(adapter);

        loadBranches();
    }

    private void loadBranches() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = client.getBranches();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (response != null && response.isSuccess && response.data != null) {
                            parseBranches(response.data);
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

    private void parseBranches(JSONObject data) {
        branchList.clear();
        JSONArray branches = data.optJSONArray("data");
        if (branches == null) branches = data.optJSONArray("branches");

        if (branches != null) {
            for (int i = 0; i < branches.length(); i++) {
                JSONObject b = branches.optJSONObject(i);
                if (b == null) continue;
                BranchItem item = new BranchItem();
                item.id = b.optInt("id");
                item.name = b.optString("name", "فرع " + (i + 1));
                item.code = b.optString("code", "");
                item.address = b.optString("address", "");
                item.phone = b.optString("phone", "");
                item.isActive = b.optString("status", "active").equals("active");
                item.itemCount = b.optInt("item_count", 0);
                item.employeeCount = b.optInt("employee_count", 0);
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
    }

    class BranchAdapter extends RecyclerView.Adapter<BranchAdapter.VH> {
        final List<BranchItem> list;
        final OnBranchClickListener clickListener;

        BranchAdapter(List<BranchItem> list, OnBranchClickListener listener) {
            this.list = list; this.clickListener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_branch, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BranchItem b = list.get(pos);
            h.tvName.setText(b.name);
            h.tvCode.setText("🏷️ " + b.code);
            h.tvDetails.setText("📦 " + b.itemCount + " قطعة | 👥 " + b.employeeCount + " موظف");
            h.tvAddress.setText(b.address.isEmpty() ? "" : "📍 " + b.address);
            h.vStatus.setBackgroundResource(b.isActive ? R.drawable.circle_green : R.drawable.circle_red);
            h.itemView.setOnClickListener(v -> clickListener.onClick(b));
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCode, tvDetails, tvAddress;
            View vStatus;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvBranchName);
                tvCode = v.findViewById(R.id.tvBranchCode);
                tvDetails = v.findViewById(R.id.tvBranchDetails);
                tvAddress = v.findViewById(R.id.tvBranchAddress);
                vStatus = v.findViewById(R.id.vBranchStatus);
            }
        }
    }

    interface OnBranchClickListener { void onClick(BranchItem branch); }
}
