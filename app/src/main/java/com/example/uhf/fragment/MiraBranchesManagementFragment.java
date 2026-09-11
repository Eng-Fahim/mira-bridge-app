package com.example.uhf.fragment;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.view.ContextThemeWrapper;
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
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class MiraBranchesManagementFragment extends Fragment {

    private static final String TAG = "BranchesMgmt";
    private static final String OFFLINE_PREFS = "branches_cache";

    private LinearLayout branchesContainer;
    private TextView tvTotalBranches, tvActiveBranches, tvTotalItems;
    private Button btnAddBranch;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraBranchesManager branchesManager;
    private MiraApiClient apiClient;
    private PermissionManager permissionManager;
    private SharedPreferences offlineCache;
    private final List<BranchData> branchList = new ArrayList<>();
    
    // ✅ إضافة ExecutorService آمن
    private ExecutorService executor;
    private static final Object EXECUTOR_LOCK = new Object();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_branches_simple, container, false);
        
        Context ctx = getContext();
        if (ctx != null) {
            branchesManager = MiraBranchesManager.getInstance(ctx);
            apiClient = MiraApiClient.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        }

        branchesContainer = view.findViewById(R.id.branchesContainer);
        tvTotalBranches = view.findViewById(R.id.tvTotalBranches);
        tvActiveBranches = view.findViewById(R.id.tvActiveBranches);
        tvTotalItems = view.findViewById(R.id.tvTotalItems);
        btnAddBranch = view.findViewById(R.id.btnAddBranchSimple);

        applyPermissions();
        
        if (btnAddBranch != null) {
            btnAddBranch.setOnClickListener(v -> {
                if (canManageBranches()) {
                    showBranchDialog(null);
                } else {
                    showToast(isArabic() ? "⛔ غير مصرح لك بإضافة فروع" : "⛔ Access Denied");
                }
            });
        }
        
        loadOfflineBranches();
        loadBranchesFromAPI();

        return view;
    }

    // ✅ دالة آمنة للحصول على executor
    private ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown() || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor();
            }
            return executor;
        }
    }

    // ✅ دالة آمنة لتنفيذ المهام
    private void executeTask(Runnable task) {
        try {
            ExecutorService exec = getExecutor();
            if (!exec.isShutdown() && !exec.isTerminated()) {
                exec.execute(task);
            }
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Task rejected: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Execute error: " + e.getMessage());
        }
    }

    // ✅ دالة آمنة لعرض Toast
    private void showToast(String message) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // ✅ دالة آمنة للحصول على Context
    private Context getContextSafely() {
        return isAdded() ? getContext() : null;
    }

    private void applyPermissions() {
        if (btnAddBranch != null) {
            btnAddBranch.setVisibility(canManageBranches() ? View.VISIBLE : View.GONE);
        }
    }

    private boolean canManageBranches() {
        return permissionManager != null && (permissionManager.isSystemAdmin() || permissionManager.isOrgAdmin());
    }

    private boolean canViewBranches() {
        return permissionManager != null && (permissionManager.isAdmin() || 
               permissionManager.isBranchManager() || 
               permissionManager.isInventoryManager() || 
               permissionManager.isAuditor());
    }

    private boolean isArabic() {
        return java.util.Locale.getDefault().getLanguage().equals("ar");
    }

    private void saveBranchesOffline(JSONArray branches) {
        try {
            if (offlineCache == null || branches == null) return;
            offlineCache.edit()
                .putString("branches", branches.toString())
                .putLong("last_update", System.currentTimeMillis())
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveBranchesOffline error: " + e.getMessage());
        }
    }

    private void loadOfflineBranches() {
        try {
            if (offlineCache == null) {
                loadDefaultBranches();
                return;
            }
            String json = offlineCache.getString("branches", "");
            if (json.isEmpty()) {
                loadDefaultBranches();
                return;
            }
            JSONArray arr = new JSONArray(json);
            parseAndDisplay(arr);
        } catch (Exception e) {
            Log.e(TAG, "loadOfflineBranches error: " + e.getMessage());
            loadDefaultBranches();
        }
    }

    private void loadDefaultBranches() {
        branchList.clear();
        branchList.add(new BranchData(1, "الفرع الرئيسي", "MAIN", "01234567", "صنعاء - شارع الزبيري", "صنعاء", true, 0));
        branchList.add(new BranchData(2, "فرع عدن", "ADN", "02345678", "عدن - خور مكسر", "عدن", true, 0));
        branchList.add(new BranchData(3, "فرع تعز", "TAZ", "03456789", "تعز - شارع جمال", "تعز", false, 0));
        
        // ✅ استخدام mainHandler للتأكد من التنفيذ على الخيط الرئيسي
        mainHandler.post(this::displayBranches);
    }

    private void loadBranchesFromAPI() {
        if (!canViewBranches()) {
            showAccessDenied();
            return;
        }
        
        // ✅ استخدام executeTask بدلاً من new Thread
        executeTask(() -> {
            try {
                if (apiClient == null) return;
                MiraApiClient.ApiResponse response = apiClient.getBranches();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("branches");
                    if (arr != null && arr.length() > 0) {
                        saveBranchesOffline(arr);
                        mainHandler.post(() -> {
                            if (isAdded()) {
                                parseAndDisplay(arr);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "API error: " + e.getMessage());
            }
        });
    }

    private void showAccessDenied() {
        mainHandler.post(() -> {
            if (!isAdded()) return;
            
            branchList.clear();
            displayBranches();
            
            // ✅ عرض رسالة الوصول المرفوض
            if (branchesContainer != null) {
                TextView tvEmpty = new TextView(getContextSafely());
                if (tvEmpty != null) {
                    tvEmpty.setText(isArabic() ? "⛔ غير مصرح لك بالوصول" : "⛔ Access Denied");
                    tvEmpty.setGravity(android.view.Gravity.CENTER);
                    tvEmpty.setPadding(20, 40, 20, 40);
                    tvEmpty.setTextSize(16);
                    branchesContainer.addView(tvEmpty);
                }
            }
        });
    }

    private void parseAndDisplay(JSONArray arr) {
        // ✅ فحص مزدوج للتأكد من أن الـ Fragment ما زال متصلاً
        if (!isAdded() || getContext() == null) {
            Log.w(TAG, "Fragment not attached, skipping parseAndDisplay");
            return;
        }
        
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
        // ✅ التحقق من أننا على الخيط الرئيسي
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::displayBranches);
            return;
        }
        
        // ✅ فحص شامل قبل استخدام context
        Context context = getContextSafely();
        if (context == null || branchesContainer == null) {
            Log.w(TAG, "Context or container is null, skipping displayBranches");
            return;
        }
        
        branchesContainer.removeAllViews();
        int activeBranchId = getActiveBranchId();

        for (BranchData b : branchList) {
            try {
                View card = LayoutInflater.from(context).inflate(R.layout.item_branch, branchesContainer, false);
                
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
                card.setOnClickListener(v -> {
                    if (canManageBranches()) {
                        showBranchDialog(fb);
                    } else {
                        showToast(isArabic() ? "⛔ غير مصرح بالتعديل" : "⛔ Access Denied");
                    }
                });
                card.setOnLongClickListener(v -> {
                    if (canManageBranches()) {
                        showBranchActions(fb);
                    } else {
                        showToast(isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied");
                    }
                    return true;
                });

                branchesContainer.addView(card);
            } catch (Exception e) {
                Log.e(TAG, "Error inflating branch card: " + e.getMessage());
            }
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
        if (!isAdded()) return;
        
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

    private void showBranchActions(BranchData b) {
        Context context = getContextSafely();
        if (context == null) return;
        if (!canManageBranches()) return;
        
        String[] actions = {
            "✅ تعيين كفرع نشط",
            "✏️ تعديل",
            "👥 إدارة المستخدمين",
            "🗑️ حذف"
        };
        
        new MaterialAlertDialogBuilder(new ContextThemeWrapper(context, R.style.MiraDialogTheme))
    .setTitle(b.name)
    .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0:
                        if (branchesManager != null) {
                            branchesManager.setActiveBranch(b.id, b.name, b.code, "", "");
                            showToast("✅ تم تعيين " + b.name + " كفرع نشط");
                            mainHandler.post(this::displayBranches);
                        }
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

    private void showBranchUsers(BranchData b) {
        Context context = getContextSafely();
        if (context == null || !canManageBranches()) return;
        
        executeTask(() -> {
            try {
                if (apiClient == null) return;
                MiraApiClient.ApiResponse response = apiClient.getBranchUsers(b.id);
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    
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
                        new MaterialAlertDialogBuilder(new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
    .setTitle("👥 مستخدمي " + b.name)
    .setItems(userList.toArray(new String[0]), null)
    .setPositiveButton("إغلاق", null)
    .show();
                    } else {
                        showToast("تعذر جلب المستخدمين");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Users error: " + e.getMessage());
            }
        });
    }

    private void showBranchDialog(@Nullable BranchData existing) {
        Context context = getContextSafely();
        if (context == null || !canManageBranches()) return;

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_branch_edit, null);

// ✅ استخدام MaterialAlertDialogBuilder مع ContextThemeWrapper
Context themedContext = new ContextThemeWrapper(context, R.style.MiraDialogTheme);
AlertDialog dialog = new MaterialAlertDialogBuilder(themedContext)
        .setView(dialogView)
        .setCancelable(true)
        .create();

// ✅ خلفية ديناميكية حسب الوضع
if (dialog.getWindow() != null) {
    boolean isDarkMode = (getResources().getConfiguration().uiMode 
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK) 
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    
    if (isDarkMode) {
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_mira_dialog_dark);
    } else {
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_mira_dialog);
    }
}

        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        EditText etName = dialogView.findViewById(R.id.etBranchName);
        EditText etCode = dialogView.findViewById(R.id.etBranchCode);
        EditText etPhone = dialogView.findViewById(R.id.etBranchPhone);
        EditText etAddress = dialogView.findViewById(R.id.etBranchAddress);
        EditText etCity = dialogView.findViewById(R.id.etBranchCity);
        SwitchMaterial swActive = dialogView.findViewById(R.id.swBranchActive);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);
        Button btnSave = dialogView.findViewById(R.id.btnSaveBranch);
        
        com.google.android.material.textfield.MaterialAutoCompleteTextView etBranchManager = 
        dialogView.findViewById(R.id.etBranchManager);

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

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String name = etName != null ? etName.getText().toString().trim() : "";
                String code = etCode != null ? etCode.getText().toString().trim() : "";
                String managerName = etBranchManager != null ? etBranchManager.getText().toString().trim() : "";
                if (name.isEmpty() || code.isEmpty()) {
                    showToast("الاسم والكود مطلوبان");
                    return;
                }
                saveBranch(isEdit ? existing.id : 0, name, code,
                    etPhone != null ? etPhone.getText().toString().trim() : "",
                    etAddress != null ? etAddress.getText().toString().trim() : "",
                    etCity != null ? etCity.getText().toString().trim() : "",
                    swActive != null && swActive.isChecked());
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    private void saveBranch(int id, String name, String code, String phone, String address, String city, boolean active) {
        if (!canManageBranches()) return;
        
        executeTask(() -> {
            try {
                if (apiClient == null) return;
                
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
                    if (!isAdded()) return;
                    
                    if (response != null && response.isSuccess) {
                        showToast(id > 0 ? "✅ تم التعديل" : "✅ تمت الإضافة");
                        loadBranchesFromAPI();
                    } else {
                        showToast("❌ فشل");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (isAdded()) {
                        showToast("❌ خطأ: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void deleteBranch(BranchData b) {
        Context context = getContextSafely();
        if (context == null || !canManageBranches()) return;
        
        new MaterialAlertDialogBuilder(new ContextThemeWrapper(context, R.style.MiraDialogTheme))
    .setTitle("حذف فرع")
    .setMessage("هل أنت متأكد من حذف \"" + b.name + "\"؟")
    .setPositiveButton("حذف", (d, w) -> {
                executeTask(() -> {
                    try {
                        if (apiClient == null) return;
                        
                        JSONObject payload = new JSONObject();
                        payload.put("status", "inactive");
                        MiraApiClient.ApiResponse response = apiClient.updateBranch(b.id, payload);
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            
                            if (response != null && response.isSuccess) {
                                showToast("✅ تم الحذف");
                                loadBranchesFromAPI();
                            } else {
                                showToast("❌ فشل الحذف");
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (isAdded()) {
                                showToast("❌ خطأ: " + e.getMessage());
                            }
                        });
                    }
                });
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // ✅ إزالة أي callbacks معلقة
        mainHandler.removeCallbacksAndMessages(null);
        
        // ✅ إغلاق آمن للـ executor
        shutdownExecutorSafely();
        
        // ✅ تنظيف المراجع
        branchesContainer = null;
        tvTotalBranches = null;
        tvActiveBranches = null;
        tvTotalItems = null;
        btnAddBranch = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shutdownExecutorSafely();
    }

    private void shutdownExecutorSafely() {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                } finally {
                    executor = null;
                }
            }
        }
    }

    static class BranchData {
        int id, itemCount;
        String name, code, phone, address, city;
        boolean isActive;
        BranchData(int id, String name, String code, String phone, String address, String city, boolean active, int items) {
            this.id = id; 
            this.name = name; 
            this.code = code; 
            this.phone = phone;
            this.address = address; 
            this.city = city; 
            this.isActive = active; 
            this.itemCount = items;
        }
    }
}
