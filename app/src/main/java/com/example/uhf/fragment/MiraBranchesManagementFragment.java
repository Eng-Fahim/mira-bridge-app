package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MiraBranchesManagementFragment extends Fragment {

    private static final String TAG = "BranchesMgmt";
    private static final String OFFLINE_PREFS = "branches_cache";

    private LinearLayout branchesContainer;
    private TextView tvTotalBranches, tvActiveBranches, tvTotalItems;
    private Button btnAddBranch;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraBranchesManager branchesManager;
    private MiraApiClient apiClient;
    private SharedPreferences offlineCache;
    private final List<BranchData> branchList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_branches_simple, container, false);
        
        Context ctx = getContext();
        if (ctx != null) {
            branchesManager = MiraBranchesManager.getInstance(ctx);
            apiClient = MiraApiClient.getInstance(ctx);
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        }

        branchesContainer = view.findViewById(R.id.branchesContainer);
        tvTotalBranches = view.findViewById(R.id.tvTotalBranches);
        tvActiveBranches = view.findViewById(R.id.tvActiveBranches);
        tvTotalItems = view.findViewById(R.id.tvTotalItems);
        btnAddBranch = view.findViewById(R.id.btnAddBranchSimple);

        btnAddBranch.setOnClickListener(v -> showBranchDialog(null));
        
        // ✅ تحميل محلي أولاً
        loadOfflineBranches();
        // ثم من API
        loadBranchesFromAPI();

        return view;
    }

    // ==================== OFFLINE ====================

    private void saveBranchesOffline(JSONArray branches) {
        try {
            if (offlineCache == null || branches == null) return;
            offlineCache.edit()
                .putString("branches", branches.toString())
                .putLong("last_update", System.currentTimeMillis())
                .apply();
        } catch (Exception ignored) {}
    }

    private void loadOfflineBranches() {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("branches", "");
            if (json.isEmpty()) {
                loadDefaultBranches();
                return;
            }
            JSONArray arr = new JSONArray(json);
            parseAndDisplay(arr);
        } catch (Exception e) {
            loadDefaultBranches();
        }
    }

    private void loadDefaultBranches() {
        branchList.clear();
        branchList.add(new BranchData(1, "الفرع الرئيسي", "MAIN", "01234567", "صنعاء - شارع الزبيري", "صنعاء", true, 0));
        branchList.add(new BranchData(2, "فرع عدن", "ADN", "02345678", "عدن - خور مكسر", "عدن", true, 0));
        branchList.add(new BranchData(3, "فرع تعز", "TAZ", "03456789", "تعز - شارع جمال", "تعز", false, 0));
        displayBranches();
    }

    private void loadBranchesFromAPI() {
        new Thread(() -> {
            try {
                if (apiClient == null) return;
                MiraApiClient.ApiResponse response = apiClient.getBranches();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("branches");
                    if (arr != null && arr.length() > 0) {
                        saveBranchesOffline(arr);
                        mainHandler.post(() -> parseAndDisplay(arr));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "API error: " + e.getMessage());
            }
        }).start();
    }

    private void parseAndDisplay(JSONArray arr) {
        branchList.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;
            branchList.add(new BranchData(
                b.optInt("id"),
                b.optString("branch_name", b.optString("branch_name_ar", "فرع")),
                b.optString("branch_code", ""),
                b.optString("phone", ""),
                b.optString("address", ""),
                b.optString("city", ""),
                b.optString("status", "active").equals("active"),
                b.optInt("inventory_count", 0)
            ));
        }
        displayBranches();
    }

    private void displayBranches() {
        branchesContainer.removeAllViews();
        int activeBranchId = getActiveBranchId();

        for (BranchData b : branchList) {
            View card = LayoutInflater.from(getContext()).inflate(R.layout.item_branch, branchesContainer, false);
            
            TextView tvName = card.findViewById(R.id.tvBranchName);
            TextView tvCode = card.findViewById(R.id.tvBranchCode);
            TextView tvDetails = card.findViewById(R.id.tvBranchDetails);
            TextView tvAddress = card.findViewById(R.id.tvBranchAddress);
            View vStatus = card.findViewById(R.id.vBranchStatus);

            String prefix = (b.id == activeBranchId) ? "✅ " : "";
            if (tvName != null) tvName.setText(prefix + b.name);
            if (tvCode != null) tvCode.setText("🏷️ " + b.code);
            if (tvDetails != null) tvDetails.setText("📦 " + b.itemCount + " قطعة | 📞 " + b.phone);
            if (tvAddress != null) tvAddress.setText(b.address.isEmpty() ? "" : "📍 " + b.address + (b.city.isEmpty() ? "" : " - " + b.city));
            if (vStatus != null) vStatus.setBackgroundResource(b.isActive ? R.drawable.circle_green : R.drawable.circle_red);

            final BranchData fb = b;
            card.setOnClickListener(v -> showBranchDialog(fb));
            card.setOnLongClickListener(v -> {
                showBranchActions(fb);
                return true;
            });

            branchesContainer.addView(card);
        }
        updateStats();
    }

    private int getActiveBranchId() {
        try {
            return branchesManager != null ? branchesManager.getActiveBranchId() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private void updateStats() {
        int total = branchList.size();
        int active = 0;
        int items = 0;
        for (BranchData b : branchList) {
            if (b.isActive) active++;
            items += b.itemCount;
        }
        if (tvTotalBranches != null) tvTotalBranches.setText(String.valueOf(total));
        if (tvActiveBranches != null) tvActiveBranches.setText(String.valueOf(active));
        if (tvTotalItems != null) tvTotalItems.setText(String.valueOf(items));
    }

    // ==================== ACTIONS ====================

    private void showBranchActions(BranchData b) {
        if (!isAdded() || getContext() == null) return;
        
        String[] actions = {
            "✅ تعيين كفرع نشط",
            "✏️ تعديل",
            "👥 إدارة المستخدمين",
            "🗑️ حذف"
        };
        
        new AlertDialog.Builder(requireContext())
            .setTitle(b.name)
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0:
                        branchesManager.setActiveBranch(b.id, b.name, b.code, "", "");
                        Toast.makeText(getContext(), "✅ تم تعيين " + b.name + " كفرع نشط", Toast.LENGTH_SHORT).show();
                        displayBranches();
                        break;
                    case 1:
                        showBranchDialog(b);
                        break;
                    case 2:
                        showBranchUsers(b);
                        break;
                    case 3:
                        deleteBranch(b);
                        break;
                }
            })
            .show();
    }

    // ✅ إدارة مستخدمي الفرع
    private void showBranchUsers(BranchData b) {
        if (!isAdded() || getContext() == null) return;
        
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getBranchUsers(b.id);
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray users = response.data.optJSONArray("users");
                        List<String> userList = new ArrayList<>();
                        if (users != null) {
                            for (int i = 0; i < users.length(); i++) {
                                JSONObject u = users.optJSONObject(i);
                                if (u != null) {
                                    userList.add(u.optString("display_name", u.optString("username", "مستخدم")) + 
                                        " (" + u.optString("role", "") + ")");
                                }
                            }
                        }
                        if (userList.isEmpty()) {
                            userList.add("لا يوجد مستخدمين في هذا الفرع");
                        }
                        new AlertDialog.Builder(requireContext())
                            .setTitle("👥 مستخدمي " + b.name)
                            .setItems(userList.toArray(new String[0]), null)
                            .setPositiveButton("إغلاق", null)
                            .show();
                    } else {
                        Toast.makeText(getContext(), "تعذر جلب المستخدمين", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Users error: " + e.getMessage());
            }
        }).start();
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
                payload.put("branch_name", name);
                payload.put("branch_code", code);
                payload.put("branch_name_ar", name);
                payload.put("phone", phone);
                payload.put("address", address);
                payload.put("city", city);
                payload.put("status", active ? "active" : "inactive");

                MiraApiClient.ApiResponse response = (id > 0) 
                    ? apiClient.updateBranch(id, payload) 
                    : apiClient.createBranch(payload);

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), id > 0 ? "✅ تم التعديل" : "✅ تمت الإضافة", Toast.LENGTH_SHORT).show();
                        loadBranchesFromAPI();
                    } else {
                        Toast.makeText(getContext(), "❌ فشل", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(getContext(), "❌ خطأ: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void deleteBranch(BranchData b) {
        if (!isAdded() || getContext() == null) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("حذف فرع")
            .setMessage("هل أنت متأكد من حذف \"" + b.name + "\"؟")
            .setPositiveButton("حذف", (d, w) -> {
                new Thread(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("status", "inactive");
                        MiraApiClient.ApiResponse response = apiClient.updateBranch(b.id, payload);
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
