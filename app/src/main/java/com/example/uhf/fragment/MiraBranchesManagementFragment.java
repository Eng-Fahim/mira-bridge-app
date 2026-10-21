package com.example.uhf.fragment;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class MiraBranchesManagementFragment extends Fragment {

    private static final String TAG = "BranchesMgmt";
    private static final String OFFLINE_PREFS = "branches_cache";

    private LinearLayout branchesContainer;
    private TextView tvTotalBranches, tvActiveBranches, tvTotalItems;
    private TextView tvOfflineWarning;
    private Button btnAddBranch;
    private SwipeRefreshLayout swipeRefreshBranches;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraBranchesManager branchesManager;
    private MiraApiClient apiClient;
    private PermissionManager permissionManager;
    private SharedPreferences offlineCache;
    private final List<BranchData> branchList = new ArrayList<>();
    
    private ExecutorService executor;
    private static final Object EXECUTOR_LOCK = new Object();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_branches_simple, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = requireContext();
        branchesManager = MiraBranchesManager.getInstance(ctx);
        apiClient = MiraApiClient.getInstance(ctx);
        permissionManager = PermissionManager.getInstance(ctx);
        offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);

        branchesContainer    = view.findViewById(R.id.branchesContainer);
        tvTotalBranches      = view.findViewById(R.id.tvTotalBranches);
        tvActiveBranches     = view.findViewById(R.id.tvActiveBranches);
        tvTotalItems         = view.findViewById(R.id.tvTotalItems);
        tvOfflineWarning     = view.findViewById(R.id.tvOfflineWarning);
        btnAddBranch         = view.findViewById(R.id.btnAddBranchSimple);
        swipeRefreshBranches = view.findViewById(R.id.swipeRefreshBranches);

        applyPermissions();
        setupListeners();
        
        loadOfflineBranches();
        loadBranchesFromAPI(true);
    }

    private void setupListeners() {
        if (btnAddBranch != null) {
            btnAddBranch.setOnClickListener(v -> {
                if (canManageBranches()) {
                    showBranchDialog(null);
                } else {
                    showToast(isArabic() ? "⛔ غير مصرح لك بإضافة فروع" : "⛔ Access Denied");
                }
            });
        }
        
        if (swipeRefreshBranches != null) {
            swipeRefreshBranches.setOnRefreshListener(() -> loadBranchesFromAPI(false));
        }
    }

    private ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown() || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor();
            }
            return executor;
        }
    }

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

    private void showToast(String message) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

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
        return permissionManager != null && (permissionManager.isAdmin() 
               || permissionManager.isBranchManager() 
               || permissionManager.isInventoryManager() 
               || permissionManager.isAuditor());
    }

    /** ✅ تحقق إضافي: هل يمكن للمستخدم إدارة مستخدمي هذا الفرع تحديداً؟ */
    private boolean canManageBranchUsers(int branchId) {
        if (permissionManager == null) return false;
        // السوبر أدمن والجهة: أي فرع
        if (permissionManager.isSystemAdmin() || permissionManager.isOrgAdmin()) return true;
        // مدير الفرع: فقط فرعه النشط
        if (permissionManager.isBranchManager()) {
            int activeId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
            return branchId > 0 && branchId == activeId;
        }
        return false;
    }

    private boolean isArabic() {
        return Locale.getDefault().getLanguage().equals("ar");
    }

    // ═══════════════════════════════════════════════════════
    //  Offline Cache
    // ═══════════════════════════════════════════════════════

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
                showOfflineWarning();
                return;
            }
            String json = offlineCache.getString("branches", "");
            if (json.isEmpty()) {
                // ✅ لا نضيف بيانات وهمية
                showOfflineWarning();
                return;
            }
            JSONArray arr = new JSONArray(json);
            parseAndDisplay(arr);
            showOfflineWarning();
        } catch (Exception e) {
            Log.e(TAG, "loadOfflineBranches error: " + e.getMessage());
            showOfflineWarning();
        }
    }

    private void showOfflineWarning() {
        if (tvOfflineWarning != null) {
            tvOfflineWarning.setVisibility(View.VISIBLE);
            tvOfflineWarning.setText(isArabic()
                ? "⚠️ تعرض بيانات محفوظة، اسحب للتحديث"
                : "⚠️ Showing cached data, pull to refresh");
        }
    }

    private void hideOfflineWarning() {
        if (tvOfflineWarning != null) {
            tvOfflineWarning.setVisibility(View.GONE);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  API Load
    // ═══════════════════════════════════════════════════════

    private void loadBranchesFromAPI(boolean showProgress) {
        if (!canViewBranches()) {
            showAccessDenied();
            if (swipeRefreshBranches != null) swipeRefreshBranches.setRefreshing(false);
            return;
        }

        executeTask(() -> {
            MiraApiClient.ApiResponse response = null;
            String errorMsg = null;
            try {
                if (apiClient == null) return;
                response = apiClient.getBranches();
            } catch (Exception e) {
                errorMsg = e.getMessage();
            }

            final MiraApiClient.ApiResponse fResponse = response;
            final String fError = errorMsg;

            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (swipeRefreshBranches != null) swipeRefreshBranches.setRefreshing(false);

                if (fResponse != null && fResponse.isSuccess && fResponse.data != null) {
                    JSONArray arr = fResponse.data.optJSONArray("branches");
                    if (arr != null && arr.length() > 0) {
                        saveBranchesOffline(arr);
                        parseAndDisplay(arr);
                        hideOfflineWarning();
                    } else {
                        // ✅ نجح الاتصال لكن لا توجد فروع
                        branchList.clear();
                        displayBranches();
                        hideOfflineWarning();
                        if (tvOfflineWarning != null) {
                            tvOfflineWarning.setVisibility(View.VISIBLE);
                            tvOfflineWarning.setText(isArabic()
                                ? "ℹ️ لا توجد فروع مسجلة"
                                : "ℹ️ No branches registered");
                        }
                    }
                } else {
                    // ✅ فشل الاتصال - أظهر رسالة واضحة
                    showOfflineWarning();
                    String msg = fError != null
                        ? (isArabic() ? "❌ خطأ: " + fError : "❌ Error: " + fError)
                        : (fResponse != null && fResponse.error != null
                            ? "❌ " + fResponse.error
                            : (isArabic() ? "❌ تعذر تحميل الفروع" : "❌ Failed to load branches"));
                    showToast(msg);
                }
            });
        });
    }

    private void showAccessDenied() {
        mainHandler.post(() -> {
            if (!isAdded()) return;
            branchList.clear();
            displayBranches();
            if (branchesContainer != null && getContextSafely() != null) {
                TextView tvEmpty = new TextView(getContextSafely());
                tvEmpty.setText(isArabic() ? "⛔ غير مصرح لك بالوصول" : "⛔ Access Denied");
                tvEmpty.setGravity(android.view.Gravity.CENTER);
                tvEmpty.setPadding(20, 40, 20, 40);
                tvEmpty.setTextSize(16);
                branchesContainer.addView(tvEmpty);
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    //  Parse & Display
    // ═══════════════════════════════════════════════════════

    private void parseAndDisplay(JSONArray arr) {
        if (!isAdded() || getContext() == null) return;

        branchList.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;

            // ✅ قراءة manager_id و manager_name
            int managerId = b.optInt("manager_id", 0);
            String managerName = b.optString("manager_name",
                    b.optString("branch_manager", ""));

            branchList.add(new BranchData(
                b.optInt("id"),
                b.optString("branch_name", b.optString("branch_name_ar", "فرع")),
                b.optString("branch_code", ""),
                b.optString("phone", ""),
                b.optString("address", ""),
                b.optString("city", ""),
                b.optString("status", "active").equals("active"),
                b.optInt("inventory_count", 0),
                managerId,
                managerName
            ));
        }
        displayBranches();
    }

    private void displayBranches() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::displayBranches);
            return;
        }
        
        Context context = getContextSafely();
        if (context == null || branchesContainer == null) return;
        
        branchesContainer.removeAllViews();
        int activeBranchId = getActiveBranchId();

        for (BranchData b : branchList) {
            try {
                View card = LayoutInflater.from(context)
                    .inflate(R.layout.item_branch, branchesContainer, false);
                
                TextView tvName    = card.findViewById(R.id.tvBranchName);
                TextView tvCode    = card.findViewById(R.id.tvBranchCode);
                TextView tvDetails = card.findViewById(R.id.tvBranchDetails);
                TextView tvAddress = card.findViewById(R.id.tvBranchAddress);
                View vStatus       = card.findViewById(R.id.vBranchStatus);

                String prefix = (b.id == activeBranchId) ? "✅ " : "";
                if (tvName != null) tvName.setText(prefix + b.name);
                if (tvCode != null) tvCode.setText("🏷️ " + b.code);
                if (tvDetails != null) {
                    String mgr = b.managerName.isEmpty() ? "" : " | 👤 " + b.managerName;
                    tvDetails.setText("📦 " + b.itemCount + " قطعة | 📞 " + b.phone + mgr);
                }
                if (tvAddress != null) {
                    tvAddress.setText(b.address.isEmpty()
                        ? ""
                        : "📍 " + b.address + (b.city.isEmpty() ? "" : " - " + b.city));
                }
                if (vStatus != null) {
                    vStatus.setBackgroundResource(b.isActive
                        ? R.drawable.circle_green
                        : R.drawable.circle_red);
                }

                final BranchData fb = b;
                card.setOnClickListener(v -> {
                    if (canManageBranches()) showBranchDialog(fb);
                    else showToast(isArabic() ? "⛔ غير مصرح بالتعديل" : "⛔ Access Denied");
                });
                card.setOnLongClickListener(v -> {
                    if (canManageBranches()) showBranchActions(fb);
                    else showToast(isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied");
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
        if (tvTotalBranches  != null) tvTotalBranches.setText(String.valueOf(total));
        if (tvActiveBranches != null) tvActiveBranches.setText(String.valueOf(active));
        if (tvTotalItems    != null) tvTotalItems.setText(String.valueOf(items));
    }

    // ═══════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════

    private void showBranchActions(BranchData b) {
        Context context = getContextSafely();
        if (context == null || !canManageBranches()) return;
        
        String[] actions = {
            isArabic() ? "✅ تعيين كفرع نشط" : "✅ Set as Active",
            isArabic() ? "✏️ تعديل"         : "✏️ Edit",
            isArabic() ? "👥 إدارة المستخدمين" : "👥 Manage Users",
            isArabic() ? "🗑️ حذف"           : "🗑️ Delete"
        };
        
        new MaterialAlertDialogBuilder(new ContextThemeWrapper(context, R.style.MiraDialogTheme))
            .setTitle(b.name)
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0:
                        if (branchesManager != null) {
                            branchesManager.setActiveBranch(b.id, b.name, b.code, "", "");
                            showToast(isArabic()
                                ? "✅ تم تعيين " + b.name + " كفرع نشط"
                                : "✅ " + b.name + " set as active");
                            displayBranches();
                        }
                        break;
                    case 1: showBranchDialog(b); break;
                    case 2: showBranchUsers(b); break;
                    case 3: deleteBranch(b); break;
                }
            })
            .show();
    }

    // ✅ إصلاح ثغرة: التحقق من صلاحية إدارة مستخدمي الفرع
    private void showBranchUsers(BranchData b) {
    Context context = getContextSafely();
    if (context == null) return;
    
    if (!canManageBranchUsers(b.id)) {
        showToast(isArabic()
            ? "⛔ غير مصرح بإدارة مستخدمي هذا الفرع"
            : "⛔ Not authorized to manage users of this branch");
        return;
    }
    
    executeTask(() -> {
        MiraApiClient.ApiResponse response = null;
        String errorMsg = null;
        try {
            // ✅ [FIX] استخدام endpoint /auth/users الموجود والمضمون
            // ثم الفلترة على العميل بـ branch_id
            response = apiClient.getUsers();
        } catch (Exception e) {
            errorMsg = e.getMessage();
        }

        final MiraApiClient.ApiResponse fResponse = response;
        final String fError = errorMsg;

        mainHandler.post(() -> {
            if (!isAdded() || getContext() == null) return;

            if (fResponse != null && fResponse.isSuccess && fResponse.data != null) {
                JSONArray allUsers = fResponse.data.optJSONArray("users");
                List<String> userList = new ArrayList<>();
                int matchCount = 0;

                if (allUsers != null) {
                    for (int i = 0; i < allUsers.length(); i++) {
                        JSONObject u = allUsers.optJSONObject(i);
                        if (u == null) continue;
                        
                        // ✅ فلترة بـ branch_id
                        int userBranchId = u.optInt("branch_id", 0);
                        if (userBranchId != b.id) continue;
                        
                        matchCount++;
                        String displayName = u.optString("display_name",
                                u.optString("username", "مستخدم"));
                        String role = u.optString("role", "");
                        String status = u.optString("status", "active");
                        String statusIcon = "active".equals(status) ? "🟢" : "🔴";
                        
                        userList.add(statusIcon + " " + displayName + " (" + role + ")");
                    }
                }

                if (userList.isEmpty()) {
                    userList.add(isArabic()
                        ? "لا يوجد مستخدمون في هذا الفرع"
                        : "No users in this branch");
                }

                new MaterialAlertDialogBuilder(
                        new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
                    .setTitle("👥 " + b.name + " (" + matchCount + ")")
                    .setItems(userList.toArray(new String[0]), null)
                    .setPositiveButton(isArabic() ? "إغلاق" : "Close", null)
                    .show();
                    
            } else {
                // ✅ رسالة خطأ واضحة مع السبب الحقيقي
                String err = fError != null
                    ? fError
                    : (fResponse != null && fResponse.error != null
                        ? fResponse.error
                        : (isArabic() ? "تعذر جلب المستخدمين" : "Failed to fetch users"));
                showToast("❌ " + err);
                Log.e(TAG, "showBranchUsers failed: " + err);
            }
        });
    });
}

    // ✅ إصلاح: حفظ manager_id + manager_name بشكل صحيح
    private void showBranchDialog(@Nullable BranchData existing) {
        Context context = getContextSafely();
        if (context == null || !canManageBranches()) return;

        View dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_branch_edit, null);

        Context themedContext = new ContextThemeWrapper(context, R.style.MiraDialogTheme);
        AlertDialog dialog = new MaterialAlertDialogBuilder(themedContext)
            .setView(dialogView)
            .setCancelable(true)
            .create();

        if (dialog.getWindow() != null) {
            boolean isDarkMode = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            dialog.getWindow().setBackgroundDrawableResource(
                isDarkMode ? R.drawable.bg_mira_dialog_dark : R.drawable.bg_mira_dialog);
        }

        TextView tvTitle    = dialogView.findViewById(R.id.tvDialogTitle);
        EditText etName     = dialogView.findViewById(R.id.etBranchName);
        EditText etCode     = dialogView.findViewById(R.id.etBranchCode);
        EditText etPhone    = dialogView.findViewById(R.id.etBranchPhone);
        EditText etAddress  = dialogView.findViewById(R.id.etBranchAddress);
        EditText etCity     = dialogView.findViewById(R.id.etBranchCity);
        SwitchMaterial swActive = dialogView.findViewById(R.id.swBranchActive);
        Button btnCancel    = dialogView.findViewById(R.id.btnCancel);
        Button btnSave      = dialogView.findViewById(R.id.btnSaveBranch);
        
        MaterialAutoCompleteTextView etBranchManager = 
            dialogView.findViewById(R.id.etBranchManager);

        boolean isEdit = existing != null;
        if (tvTitle != null) {
            tvTitle.setText(isEdit
                ? (isArabic() ? "تعديل فرع" : "Edit Branch")
                : (isArabic() ? "إضافة فرع جديد" : "Add New Branch"));
        }

        // ✅ حفظ manager_id في متغير
        final int[] selectedManagerId = { isEdit ? existing.managerId : 0 };
        final String[] selectedManagerName = { isEdit ? existing.managerName : "" };

        // ✅ تحميل قائمة المدراء المحتملين
        loadManagersForSpinner(etBranchManager, selectedManagerId, selectedManagerName);

        if (isEdit) {
            if (etName    != null) etName.setText(existing.name);
            if (etCode    != null) etCode.setText(existing.code);
            if (etPhone   != null) etPhone.setText(existing.phone);
            if (etAddress != null) etAddress.setText(existing.address);
            if (etCity    != null) etCity.setText(existing.city);
            if (swActive  != null) swActive.setChecked(existing.isActive);
            if (etBranchManager != null && !existing.managerName.isEmpty()) {
                etBranchManager.setText(existing.managerName, false);
            }
        }

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String name    = etName != null ? etName.getText().toString().trim() : "";
                String code    = etCode != null ? etCode.getText().toString().trim() : "";
                String phone   = etPhone != null ? etPhone.getText().toString().trim() : "";
                String address = etAddress != null ? etAddress.getText().toString().trim() : "";
                String city    = etCity != null ? etCity.getText().toString().trim() : "";

                // ✅ Validation أفضل
                if (name.isEmpty() || code.isEmpty()) {
                    showToast(isArabic()
                        ? "⚠️ الاسم والكود مطلوبان"
                        : "⚠️ Name and code are required");
                    return;
                }
                if (phone.isEmpty()) {
                    showToast(isArabic()
                        ? "⚠️ رقم الهاتف مطلوب"
                        : "⚠️ Phone is required");
                    return;
                }

                saveBranch(
                    isEdit ? existing.id : 0,
                    name, code, phone, address, city,
                    swActive != null && swActive.isChecked(),
                    selectedManagerId[0]
                );
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    /**
     * ✅ تحميل قائمة المدراء في Spinner
     * (يعتمد على API - إذا لم يكن متوفراً يستخدم fallback)
     */
    private void loadManagersForSpinner(MaterialAutoCompleteTextView autoComplete,
                                        int[] selectedId, String[] selectedName) {
        if (autoComplete == null) return;

        executeTask(() -> {
            List<String> managerNames = new ArrayList<>();
            List<Integer> managerIds = new ArrayList<>();

            try {
                // محاولة جلب المدراء من السيرفر (endpoint افتراضي)
                if (apiClient != null) {
                    MiraApiClient.ApiResponse response = apiClient.getUsers();
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray users = response.data.optJSONArray("users");
                        if (users != null) {
                            for (int i = 0; i < users.length(); i++) {
                                JSONObject u = users.optJSONObject(i);
                                if (u == null) continue;
                                String role = u.optString("role", "");
                                // مديرو الفرع والجهة فقط
                                if ("BRANCH_MANAGER".equals(role) || "ORG_ADMIN".equals(role)) {
                                    String displayName = u.optString("display_name",
                                        u.optString("username", ""));
                                    if (!displayName.isEmpty()) {
                                        managerNames.add(displayName);
                                        managerIds.add(u.optInt("id"));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "loadManagersForSpinner error: " + e.getMessage());
            }

            final List<String> finalNames = managerNames;
            final List<Integer> finalIds  = managerIds;

            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (finalNames.isEmpty()) {
                    autoComplete.setEnabled(false);
                    autoComplete.setHint(isArabic()
                        ? "لا يوجد مدراء متاحون"
                        : "No managers available");
                    return;
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    finalNames
                );
                autoComplete.setAdapter(adapter);

                autoComplete.setOnItemClickListener((parent, view, position, id) -> {
                    if (position >= 0 && position < finalIds.size()) {
                        selectedId[0]   = finalIds.get(position);
                        selectedName[0] = finalNames.get(position);
                    }
                });
            });
        });
    }

    // ✅ إصلاح: إرسال manager_id في الـ payload
    private void saveBranch(int id, String name, String code,
                            String phone, String address, String city,
                            boolean active, int managerId) {
        if (!canManageBranches()) return;
        
        executeTask(() -> {
            MiraApiClient.ApiResponse response = null;
            String errorMsg = null;
            try {
                if (apiClient == null) return;
                
                JSONObject payload = new JSONObject();
                payload.put("branch_name",    name);
                payload.put("branch_name_ar", name);
                payload.put("branch_code",    code);
                payload.put("phone",          phone);
                payload.put("address",        address);
                payload.put("city",           city);
                payload.put("status",         active ? "active" : "inactive");
                
                // ✅ إضافة manager_id بشكل صحيح
                if (managerId > 0) {
                    payload.put("manager_id", managerId);
                }

                response = (id > 0)
                    ? apiClient.updateBranch(id, payload)
                    : apiClient.createBranch(payload);
            } catch (Exception e) {
                errorMsg = e.getMessage();
            }

            final MiraApiClient.ApiResponse fResponse = response;
            final String fError = errorMsg;

            mainHandler.post(() -> {
                if (!isAdded()) return;

                if (fResponse != null && fResponse.isSuccess) {
                    showToast(id > 0
                        ? (isArabic() ? "✅ تم التعديل" : "✅ Updated")
                        : (isArabic() ? "✅ تمت الإضافة" : "✅ Added"));
                    loadBranchesFromAPI(false);
                } else {
                    String msg = fError != null
                        ? "❌ " + fError
                        : (fResponse != null && fResponse.error != null
                            ? "❌ " + fResponse.error
                            : (isArabic() ? "❌ فشل الحفظ" : "❌ Save failed"));
                    showToast(msg);
                }
            });
        });
    }

    private void deleteBranch(BranchData b) {
        Context context = getContextSafely();
        if (context == null || !canManageBranches()) return;
        
        // ✅ منع حذف الفرع النشط
        if (b.id == getActiveBranchId()) {
            showToast(isArabic()
                ? "⚠️ لا يمكن حذف الفرع النشط حالياً"
                : "⚠️ Cannot delete the active branch");
            return;
        }
        
        new MaterialAlertDialogBuilder(new ContextThemeWrapper(context, R.style.MiraDialogTheme))
            .setTitle(isArabic() ? "حذف فرع" : "Delete Branch")
            .setMessage((isArabic() ? "هل أنت متأكد من حذف \"" : "Delete \"") + b.name + "\"?")
            .setPositiveButton(isArabic() ? "حذف" : "Delete", (d, w) -> {
                executeTask(() -> {
                    try {
                        if (apiClient == null) return;
                        JSONObject payload = new JSONObject();
                        payload.put("status", "inactive");
                        MiraApiClient.ApiResponse response = apiClient.updateBranch(b.id, payload);
                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            
                            if (response != null && response.isSuccess) {
                                showToast(isArabic() ? "✅ تم الحذف" : "✅ Deleted");
                                loadBranchesFromAPI(false);
                            } else {
                                String err = response != null && response.error != null
                                    ? response.error
                                    : (isArabic() ? "فشل الحذف" : "Delete failed");
                                showToast("❌ " + err);
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (isAdded()) showToast("❌ " + e.getMessage());
                        });
                    }
                });
            })
            .setNegativeButton(isArabic() ? "إلغاء" : "Cancel", null)
            .show();
    }

    // ═══════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
        shutdownExecutorSafely();
        
        branchesContainer    = null;
        tvTotalBranches      = null;
        tvActiveBranches     = null;
        tvTotalItems         = null;
        tvOfflineWarning     = null;
        btnAddBranch         = null;
        swipeRefreshBranches = null;
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

    // ═══════════════════════════════════════════════════════
    //  Data Class
    // ═══════════════════════════════════════════════════════

    static class BranchData {
        int id, itemCount;
        String name, code, phone, address, city;
        boolean isActive;
        // ✅ حقول جديدة
        int managerId;
        String managerName;

        BranchData(int id, String name, String code, String phone,
                   String address, String city, boolean active,
                   int items, int managerId, String managerName) {
            this.id = id;
            this.name = name;
            this.code = code;
            this.phone = phone;
            this.address = address;
            this.city = city;
            this.isActive = active;
            this.itemCount = items;
            this.managerId = managerId;
            this.managerName = managerName != null ? managerName : "";
        }
    }
        }
