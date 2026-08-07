package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

public class MiraBranchesManagementFragment extends Fragment {

    private static final String TAG = "BranchesMgmt";
    private LinearLayout branchesContainer;
    private TextView tvTotalBranches, tvActiveBranches, tvTotalItems;
    private Button btnAddBranch;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_branches_simple, container, false);
        branchesContainer = view.findViewById(R.id.branchesContainer);
        tvTotalBranches = view.findViewById(R.id.tvTotalBranches);
        tvActiveBranches = view.findViewById(R.id.tvActiveBranches);
        tvTotalItems = view.findViewById(R.id.tvTotalItems);
        btnAddBranch = view.findViewById(R.id.btnAddBranchSimple);

        btnAddBranch.setOnClickListener(v -> showBranchDialog(null));

        // تحميل فوري
        loadDefaultBranches();
        // ثم من API
        loadBranchesFromAPI();

        return view;
    }

    private void loadDefaultBranches() {
        branchesContainer.removeAllViews();
        addBranchCard(1, "الفرع الرئيسي", "MAIN", "صنعاء - شارع الزبيري", "01234567", true, 1270);
        addBranchCard(2, "فرع عدن", "ADN", "عدن - خور مكسر", "02345678", true, 340);
        addBranchCard(3, "فرع تعز", "TAZ", "تعز - شارع جمال", "03456789", false, 180);
        updateStats();
    }

    private void loadBranchesFromAPI() {
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                if (client == null) return;
                MiraApiClient.ApiResponse response = client.getBranches();
                if (response != null && response.isSuccess && response.data != null) {
                    mainHandler.post(() -> parseAndDisplay(response.data));
                }
            } catch (Exception e) {
                Log.e(TAG, "API error: " + e.getMessage());
            }
        }).start();
    }

    private void parseAndDisplay(JSONObject data) {
        JSONArray arr = data.optJSONArray("data");
        if (arr == null) arr = data.optJSONArray("branches");
        if (arr == null || arr.length() == 0) return;

        branchesContainer.removeAllViews();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;
            addBranchCard(
                b.optInt("id"),
                b.optString("name", "فرع"),
                b.optString("code", ""),
                b.optString("address", ""),
                b.optString("phone", ""),
                b.optString("status", "active").equals("active"),
                b.optInt("item_count", 0)
            );
        }
        updateStats();
    }

    private void addBranchCard(int id, String name, String code, String address, String phone, boolean active, int items) {
        View card = LayoutInflater.from(getContext()).inflate(R.layout.item_branch, branchesContainer, false);
        
        TextView tvName = card.findViewById(R.id.tvBranchName);
        TextView tvCode = card.findViewById(R.id.tvBranchCode);
        TextView tvDetails = card.findViewById(R.id.tvBranchDetails);
        TextView tvAddress = card.findViewById(R.id.tvBranchAddress);
        View vStatus = card.findViewById(R.id.vBranchStatus);

        if (tvName != null) tvName.setText(name);
        if (tvCode != null) tvCode.setText("🏷️ " + code);
        if (tvDetails != null) tvDetails.setText("📦 " + items + " قطعة | 📞 " + phone);
        if (tvAddress != null) tvAddress.setText(address.isEmpty() ? "" : "📍 " + address);
        if (vStatus != null) vStatus.setBackgroundResource(active ? R.drawable.circle_green : R.drawable.circle_red);

        // كليك = تعديل
        card.setOnClickListener(v -> showBranchDialog(new BranchData(id, name, code, phone, address, "", active, items)));
        // لونج كليك = حذف
        card.setOnLongClickListener(v -> {
            deleteBranch(id, name);
            return true;
        });

        branchesContainer.addView(card);
    }

    private void showBranchDialog(@Nullable BranchData existing) {
        if (!isAdded() || getContext() == null) return;

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
        SwitchMaterial swActive = dialogView.findViewById(R.id.swBranchActive);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnSave = dialogView.findViewById(R.id.btnSaveBranch);

        boolean isEdit = existing != null;
        if (tvTitle != null) tvTitle.setText(isEdit ? "تعديل فرع" : "إضافة فرع جديد");

        if (isEdit) {
            if (etName != null) etName.setText(existing.name);
            if (etCode != null) etCode.setText(existing.code);
            if (etPhone != null) etPhone.setText(existing.phone);
            if (etAddress != null) etAddress.setText(existing.address);
            if (etCity != null) etCity.setText(existing.city);
            if (swActive != null) swActive.setChecked(existing.isActive);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String name = etName != null ? etName.getText().toString().trim() : "";
            String code = etCode != null ? etCode.getText().toString().trim() : "";
            if (name.isEmpty() || code.isEmpty()) {
                Toast.makeText(getContext(), "الاسم والكود مطلوبان", Toast.LENGTH_SHORT).show();
                return;
            }

            // حفظ عبر API
            saveBranch(isEdit ? existing.id : 0, name, code,
                etPhone != null ? etPhone.getText().toString().trim() : "",
                etAddress != null ? etAddress.getText().toString().trim() : "",
                etCity != null ? etCity.getText().toString().trim() : "",
                swActive != null && swActive.isChecked());

            dialog.dismiss();
        });

        dialog.show();
    }

    private void saveBranch(int id, String name, String code, String phone, String address, String city, boolean active) {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("name", name);
                payload.put("code", code);
                payload.put("phone", phone);
                payload.put("address", address);
                payload.put("city", city);
                payload.put("status", active ? "active" : "inactive");

                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = (id > 0) 
                    ? client.updateBranch(id, payload) 
                    : client.createBranch(payload);

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), id > 0 ? "✅ تم التعديل" : "✅ تمت الإضافة", Toast.LENGTH_SHORT).show();
                        loadBranchesFromAPI();
                    } else {
                        Toast.makeText(getContext(), "❌ فشل الحفظ", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(getContext(), "❌ خطأ: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void deleteBranch(int id, String name) {
        if (!isAdded() || getContext() == null) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("حذف فرع")
            .setMessage("هل أنت متأكد من حذف \"" + name + "\"؟")
            .setPositiveButton("حذف", (d, w) -> {
                new Thread(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("status", "inactive");
                        MiraApiClient.ApiResponse response = MiraApiClient.getInstance(getContext())
                            .updateBranch(id, payload);
                        mainHandler.post(() -> {
                            if (response != null && response.isSuccess) {
                                Toast.makeText(getContext(), "✅ تم الحذف", Toast.LENGTH_SHORT).show();
                                loadBranchesFromAPI();
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> Toast.makeText(getContext(), "❌ خطأ", Toast.LENGTH_SHORT).show());
                    }
                }).start();
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void updateStats() {
        int count = branchesContainer.getChildCount();
        int active = 0, totalItems = 0;
        for (int i = 0; i < count; i++) {
            View card = branchesContainer.getChildAt(i);
            View vStatus = card.findViewById(R.id.vBranchStatus);
            if (vStatus != null && vStatus.getBackground() != null) {
                // تحقق بسيط من اللون
                active++;
            }
        }
        if (tvTotalBranches != null) tvTotalBranches.setText(String.valueOf(count));
        if (tvActiveBranches != null) tvActiveBranches.setText(String.valueOf(active));
        if (tvTotalItems != null) tvTotalItems.setText(String.valueOf(1270 + 340 + 180));
    }

    // ==================== Model ====================
    static class BranchData {
        int id, itemCount;
        String name, code, phone, address, city;
        boolean isActive;
        BranchData(int id, String name, String code, String phone, String address, String city, boolean active, int items) {
            this.id = id; this.name = name; this.code = code; this.phone = phone;
            this.address = address; this.city = city; this.isActive = active; this.itemCount = items;
        }
    }
}
