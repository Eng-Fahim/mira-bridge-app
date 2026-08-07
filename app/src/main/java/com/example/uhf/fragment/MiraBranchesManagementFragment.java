package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MiraBranchesManagementFragment extends Fragment {

    private RecyclerView rvBranches;
    private TextView tvEmpty, tvTotalBranches, tvActiveBranches, tvTotalItems;
    private Button btnAddBranch;
    private BranchListAdapter adapter;
    private final List<BranchData> branchList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_branches_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvBranches = view.findViewById(R.id.rvBranchesList);
        tvEmpty = view.findViewById(R.id.tvEmptyBranches);
        tvTotalBranches = view.findViewById(R.id.tvTotalBranches);
        tvActiveBranches = view.findViewById(R.id.tvActiveBranches);
        tvTotalItems = view.findViewById(R.id.tvTotalItems);
        btnAddBranch = view.findViewById(R.id.btnAddBranch);

        rvBranches.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BranchListAdapter(branchList, this::showBranchDialog, this::deleteBranch);
        rvBranches.setAdapter(adapter);

        btnAddBranch.setOnClickListener(v -> showBranchDialog(null));
        loadBranches();
    }

    private void loadBranches() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = client.getBranches();

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        parseBranches(response.data);
                    } else {
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(getContext(), "خطأ في التحميل", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void parseBranches(JSONObject data) {
        branchList.clear();
        JSONArray arr = data.optJSONArray("data");
        if (arr == null) arr = data.optJSONArray("branches");
        if (arr == null) { tvEmpty.setVisibility(View.VISIBLE); return; }

        int active = 0, totalItems = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;
            BranchData br = new BranchData();
            br.id = b.optInt("id");
            br.name = b.optString("name", "");
            br.code = b.optString("code", "");
            br.phone = b.optString("phone", "");
            br.address = b.optString("address", "");
            br.city = b.optString("city", "");
            br.managerName = b.optString("manager_name", "");
            br.isActive = b.optString("status", "active").equals("active");
            br.itemCount = b.optInt("item_count", 0);
            if (br.isActive) active++;
            totalItems += br.itemCount;
            branchList.add(br);
        }

        adapter.notifyDataSetChanged();
        tvTotalBranches.setText(String.valueOf(branchList.size()));
        tvActiveBranches.setText(String.valueOf(active));
        tvTotalItems.setText(String.valueOf(totalItems));
        tvEmpty.setVisibility(branchList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showBranchDialog(@Nullable BranchData existing) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_branch_edit, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        EditText etName = dialogView.findViewById(R.id.etBranchName);
        EditText etCode = dialogView.findViewById(R.id.etBranchCode);
        EditText etPhone = dialogView.findViewById(R.id.etBranchPhone);
        EditText etAddress = dialogView.findViewById(R.id.etBranchAddress);
        EditText etCity = dialogView.findViewById(R.id.etBranchCity);
        com.google.android.material.textfield.MaterialAutoCompleteTextView etManager = dialogView.findViewById(R.id.etBranchManager);
        SwitchMaterial swActive = dialogView.findViewById(R.id.swBranchActive);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnSave = dialogView.findViewById(R.id.btnSaveBranch);

        boolean isEdit = existing != null;
        tvTitle.setText(isEdit ? "تعديل فرع" : "إضافة فرع جديد");

        if (isEdit) {
            etName.setText(existing.name);
            etCode.setText(existing.code);
            etPhone.setText(existing.phone);
            etAddress.setText(existing.address);
            etCity.setText(existing.city);
            etManager.setText(existing.managerName);
            swActive.setChecked(existing.isActive);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            if (name.isEmpty() || code.isEmpty()) {
                Toast.makeText(getContext(), "الاسم والكود مطلوبان", Toast.LENGTH_SHORT).show();
                return;
            }

            saveBranch(isEdit ? existing.id : 0, name, code,
                etPhone.getText().toString().trim(),
                etAddress.getText().toString().trim(),
                etCity.getText().toString().trim(),
                etManager.getText().toString().trim(),
                swActive.isChecked(), isEdit);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void saveBranch(int id, String name, String code, String phone, String address,
                            String city, String manager, boolean active, boolean isEdit) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("name", name);
                payload.put("code", code);
                payload.put("phone", phone);
                payload.put("address", address);
                payload.put("city", city);
                payload.put("manager_name", manager);
                payload.put("status", active ? "active" : "inactive");

                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = isEdit
                    ? client.updateBranch(id, payload)
                    : client.createBranch(payload);

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), isEdit ? "تم التعديل" : "تمت الإضافة", Toast.LENGTH_SHORT).show();
                        loadBranches();
                    } else {
                        Toast.makeText(getContext(), "فشل الحفظ", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(getContext(), "خطأ", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void deleteBranch(BranchData branch) {
        new AlertDialog.Builder(requireContext())
            .setTitle("حذف فرع")
            .setMessage("هل أنت متأكد من حذف \"" + branch.name + "\"؟")
            .setPositiveButton("حذف", (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    MiraApiClient.ApiResponse response = MiraApiClient.getInstance(getContext())
                        .updateBranch(branch.id, createStatusPayload("inactive"));
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), "تم الحذف", Toast.LENGTH_SHORT).show();
                            loadBranches();
                        }
                    });
                });
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private JSONObject createStatusPayload(String status) {
        try { return new JSONObject().put("status", status); } catch (Exception e) { return new JSONObject(); }
    }

    static class BranchData {
        int id, itemCount;
        String name, code, phone, address, city, managerName;
        boolean isActive;
    }

    class BranchListAdapter extends RecyclerView.Adapter<BranchListAdapter.VH> {
        final List<BranchData> list;
        final OnBranchAction editAction, deleteAction;

        BranchListAdapter(List<BranchData> list, OnBranchAction edit, OnBranchAction delete) {
            this.list = list; this.editAction = edit; this.deleteAction = delete;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_branch, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BranchData b = list.get(pos);
            h.tvName.setText(b.name);
            h.tvCode.setText("🏷️ " + b.code);
            h.tvDetails.setText("📦 " + b.itemCount + " قطعة");
            h.tvAddress.setText(b.city.isEmpty() ? "" : "📍 " + b.city + (b.address.isEmpty() ? "" : " - " + b.address));
            h.vStatus.setBackgroundResource(b.isActive ? R.drawable.circle_green : R.drawable.circle_red);
            h.itemView.setOnClickListener(v -> editAction.onAction(b));
            h.itemView.setOnLongClickListener(v -> { deleteAction.onAction(b); return true; });
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCode, tvDetails, tvAddress; View vStatus;
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

    interface OnBranchAction { void onAction(BranchData branch); }
}
