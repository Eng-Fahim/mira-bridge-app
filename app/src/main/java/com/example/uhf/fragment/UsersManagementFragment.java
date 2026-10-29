package com.example.uhf.fragment;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.view.ContextThemeWrapper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.license.MiraBlobFetcher;
import com.example.uhf.license.MiraBlobStorage;
import com.example.uhf.license.MiraBlobVerifier;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.SecureSessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class UsersManagementFragment extends Fragment {

    private static final String TAG = "UsersManagement";

    // ==================== UI Elements ====================
    private RecyclerView rvUsersList;
    private EditText etSearchUser;
    private TextInputLayout tilSearchUser;
    private TextView tvScreenTitle;
    private TextView tvTotalUsers, tvActiveUsers, tvOnlineNow, tvSuspendedUsers;
    private TextView tvEmptyState;
    private TextView tvUserLimitBadge;
    private View emptyStateLayout;
    private CircularProgressIndicator progressLoading;
    private Button btnAddUser, btnRefreshUsers;
    private MaterialButton btnExport;
    private SwipeRefreshLayout swipeRefreshLayout;

    // ==================== Managers & API ====================
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService ioExecutor;

    // ==================== Data ====================
    private UsersAdapter adapter;
    private final List<UserData> userList     = new ArrayList<>();
    private final List<UserData> filteredList = new ArrayList<>();
    private boolean isArabic;

    private SecureSessionManager.Role currentUserRole;

    // ==================== Dynamic Roles (loaded from DB) ====================
    private static class RoleDefinition {
        String name;
        String nameAr;
        String nameEn;
        String icon;
        int level;
    }
    private final List<RoleDefinition> roleDefinitions = new ArrayList<>();
    private boolean rolesLoadedFromServer = false;

    // ==================== Password Generator ====================
    private static final String UPPER   = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER   = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS  = "0123456789";
    private static final String SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?";
    private static final String ALL     = UPPER + LOWER + DIGITS + SYMBOLS;
    private final SecureRandom random = new SecureRandom();

    // ==================== Counters & Cache ====================
    private final AtomicInteger employeeCounter = new AtomicInteger(1);
    private final List<BranchItem> branchesList = new ArrayList<>();

    // ═══════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_users_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            ioExecutor = Executors.newSingleThreadExecutor();

            isArabic = Locale.getDefault().getLanguage().equals("ar");
            if (!isArabic) {
                try {
                    isArabic = getResources().getConfiguration()
                            .locale.getLanguage().equals("ar");
                } catch (Exception ignored) {}
            }

            apiClient       = MiraApiClient.getInstance(requireContext());
            sessionManager  = new SessionManager(requireContext());
            branchesManager = MiraBranchesManager.getInstance(requireContext());
            currentUserRole = sessionManager.getUserRole();

            // ⚡ تحميل الأدوار الافتراضية أولاً (fallback سريع)
            loadDefaultRoles();

            initializeViews(view);
            applyLanguage();
            loadBranchesCache();
            setupRecyclerView();
            setupButtons();
            setupSearch();

            // ⚡ جلب الأدوار من DB (يستبدل الافتراضية عند النجاح)
            loadRolesFromServer();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
            showToast("Error initializing: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (ioExecutor != null && !ioExecutor.isShutdown()) {
            ioExecutor.execute(() -> {
                try {
                    boolean updated = MiraBlobFetcher.fetchVerifyAndStore(requireContext());
                    Log.d(TAG, "Blob refresh: " + (updated ? "✅" : "⚠️"));
                } catch (Exception e) {
                    Log.w(TAG, "Blob refresh failed: " + e.getMessage());
                }
            });
        }

        loadUsers();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (ioExecutor != null && !ioExecutor.isShutdown()) {
            ioExecutor.shutdown();
            ioExecutor = null;
        }

        rvUsersList = null; etSearchUser = null; tilSearchUser = null;
        tvScreenTitle = null; tvTotalUsers = null; tvActiveUsers = null;
        tvOnlineNow = null; tvSuspendedUsers = null; tvEmptyState = null;
        tvUserLimitBadge = null; emptyStateLayout = null; progressLoading = null;
        btnAddUser = null; btnRefreshUsers = null; btnExport = null;
        swipeRefreshLayout = null; adapter = null;
    }

    // ═══════════════════════════════════════════════════════
    //  Roles — Load from Server (FIX #1)
    // ═══════════════════════════════════════════════════════

    private void loadDefaultRoles() {
        roleDefinitions.clear();
        String[][] defaults = {
                {"SYSTEM_ADMIN",    "مهندس النظام",  "System Admin",     "🔧", "100"},
                {"ORG_ADMIN",       "مدير الجهة",    "Org Admin",        "🏢", "90"},
                {"FINANCE_MANAGER", "مدير مالي",     "Finance Manager",  "💰", "85"},
                {"INVENTORY_MGR",   "مدير المخزون",  "Inventory Manager","📊", "80"},
                {"BRANCH_MANAGER",  "مدير الفرع",    "Branch Manager",   "👤", "75"},
                {"ACCOUNTANT",      "محاسب",          "Accountant",       "📈", "70"},
                {"AUDITOR",         "مدقق",           "Auditor",          "🔍", "60"},
                {"POS_OPERATOR",    "موظف مبيعات",   "POS Operator",     "🛒", "50"},
                {"GATE_SECURITY",   "أمن البوابات",  "Gate Security",    "🚪", "35"},
                {"SECURITY",        "أمن",            "Security",         "🔒", "30"}
        };
        for (String[] d : defaults) {
            RoleDefinition def = new RoleDefinition();
            def.name   = d[0];
            def.nameAr = d[1];
            def.nameEn = d[2];
            def.icon   = d[3];
            def.level  = Integer.parseInt(d[4]);
            roleDefinitions.add(def);
        }
    }

    /**
     * ✅ يجلب الأدوار من قاعدة البيانات عبر GET /admin/roles-list
     * عند النجاح: يستبدل القائمة المحلية
     * عند الفشل: يبقى على القائمة الافتراضية
     */
    private void loadRolesFromServer() {
        if (ioExecutor == null || apiClient == null) {
            Log.w(TAG, "loadRolesFromServer: not ready, using defaults");
            return;
        }

        try {
            ioExecutor.execute(() -> {
                MiraApiClient.ApiResponse response = null;
                try {
                    response = apiClient.getRolesList();
                } catch (Throwable t) {
                    Log.w(TAG, "getRolesList threw: " + t.getMessage());
                }
                final MiraApiClient.ApiResponse fResponse = response;

                if (fResponse == null || !fResponse.isSuccess || fResponse.data == null) {
                    Log.w(TAG, "Roles load failed, using defaults");
                    return;
                }

                try {
                    JSONArray arr = fResponse.data.optJSONArray("roles");
                    if (arr == null) arr = fResponse.data.optJSONArray("data");
                    if (arr == null || arr.length() == 0) {
                        Log.w(TAG, "Roles array empty");
                        return;
                    }

                    List<RoleDefinition> loaded = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.optJSONObject(i);
                        if (r == null) continue;
                        String roleName = r.optString("role_name", "").trim();
                        if (roleName.isEmpty()) continue;

                        RoleDefinition def = new RoleDefinition();
                        def.name   = roleName;
                        def.nameAr = r.optString("role_name_ar", roleName);
                        def.nameEn = r.optString("role_name", roleName);
                        def.level  = r.optInt("level", 0);
                        def.icon   = iconForRole(roleName);
                        loaded.add(def);
                    }

                    if (loaded.isEmpty()) return;

                    // رتب حسب level تنازلياً
                    loaded.sort((a, b) -> Integer.compare(b.level, a.level));

                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        roleDefinitions.clear();
                        roleDefinitions.addAll(loaded);
                        rolesLoadedFromServer = true;
                        Log.i(TAG, "✅ Loaded " + loaded.size() + " roles from server");

                        // تحديث القائمة الحالية إن كانت معروضة
                        if (adapter != null) adapter.notifyDataSetChanged();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "loadRolesFromServer parse error: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            Log.w(TAG, "loadRolesFromServer: executor rejected");
        }
    }

    private String iconForRole(String roleName) {
        if (roleName == null) return "👤";
        switch (roleName) {
            case "SYSTEM_ADMIN":    return "🔧";
            case "ORG_ADMIN":       return "🏢";
            case "FINANCE_MANAGER": return "💰";
            case "INVENTORY_MGR":   return "📊";
            case "BRANCH_MANAGER":  return "👤";
            case "ACCOUNTANT":      return "📈";
            case "AUDITOR":         return "🔍";
            case "POS_OPERATOR":    return "🛒";
            case "GATE_SECURITY":   return "🚪";
            case "SECURITY":        return "🔒";
            default:                return "👤";
        }
    }

    private RoleDefinition findRole(String roleName) {
        if (roleName == null) return null;
        for (RoleDefinition def : roleDefinitions) {
            if (def.name.equals(roleName)) return def;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════

    private void initializeViews(View view) {
        rvUsersList        = view.findViewById(R.id.rvUsersList);
        etSearchUser       = view.findViewById(R.id.etSearchUser);
        tilSearchUser      = view.findViewById(R.id.tilSearchUser);
        tvScreenTitle      = view.findViewById(R.id.tvScreenTitle);
        tvTotalUsers       = view.findViewById(R.id.tvTotalUsers);
        tvActiveUsers      = view.findViewById(R.id.tvActiveUsers);
        tvOnlineNow        = view.findViewById(R.id.tvOnlineNow);
        tvSuspendedUsers   = view.findViewById(R.id.tvSuspendedUsers);
        tvEmptyState       = view.findViewById(R.id.tvEmptyState);
        tvUserLimitBadge   = view.findViewById(R.id.tvUserLimitBadge);
        emptyStateLayout   = view.findViewById(R.id.emptyStateLayout);
        progressLoading    = view.findViewById(R.id.progressLoading);
        btnAddUser         = view.findViewById(R.id.btnAddUser);
        btnRefreshUsers    = view.findViewById(R.id.btnRefreshUsers);
        btnExport          = view.findViewById(R.id.btnExport);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
    }

    private void setupRecyclerView() {
        if (rvUsersList == null) return;
        rvUsersList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new UsersAdapter(filteredList, isArabic);
        rvUsersList.setAdapter(adapter);
    }

    private void setupButtons() {
        if (btnAddUser != null) {
            btnAddUser.setOnClickListener(v -> {
                try {
                    if (!canManageUsers()) {
                        showToast(isArabic ? "❌ ليس لديك صلاحية" : "❌ No permission");
                        return;
                    }
                    if (!checkLicenseBeforeAction()) return;
                    showUserDialog(null);
                } catch (Exception e) {
                    showToast("Error: " + e.getMessage());
                }
            });
        }

        if (btnRefreshUsers != null) btnRefreshUsers.setOnClickListener(v -> {
            loadUsers();
            loadRolesFromServer(); // ← تحديث الأدوار أيضاً
        });

        if (btnExport != null) {
            btnExport.setOnClickListener(v -> showToast(isArabic
                    ? "🚧 تصدير المستخدمين قيد التطوير" : "🚧 Export — coming soon"));
        }

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                loadUsers();
                loadRolesFromServer();
            });
            swipeRefreshLayout.setColorSchemeResources(
                    androidx.appcompat.R.color.abc_color_highlight_material);
        }
    }

    private void setupSearch() {
        if (etSearchUser == null) return;
        etSearchUser.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filterUsers(s.toString());
                updateEmptyState();
            }
        });
    }

    private void applyLanguage() {
        if (btnAddUser  != null) btnAddUser.setText(isArabic ? "+ إضافة مستخدم" : "+ Add User");
        if (btnRefreshUsers != null) btnRefreshUsers.setContentDescription(isArabic ? "تحديث" : "Refresh");
        if (btnExport   != null) btnExport.setContentDescription(isArabic ? "تصدير" : "Export");
        if (tilSearchUser != null) tilSearchUser.setHint(isArabic ? "بحث..." : "Search...");
        else if (etSearchUser != null) etSearchUser.setHint(isArabic ? "بحث..." : "Search...");
        if (tvScreenTitle != null) {
            tvScreenTitle.setText(isArabic ? "👥 إدارة المستخدمين" : "👥 Users Management");
        }
        updateEmptyState();
    }

    // ═══════════════════════════════════════════════════════
    //  Permission Logic
    // ═══════════════════════════════════════════════════════

    private boolean canManageUsers() {
        return currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN
                || currentUserRole == SecureSessionManager.Role.ORG_ADMIN;
    }

    private boolean canEditUser(String targetRole) {
        if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN) return true;
        if (currentUserRole == SecureSessionManager.Role.ORG_ADMIN
                && !"SYSTEM_ADMIN".equals(targetRole)) return true;
        return false;
    }

    private String[] getAllowedRoles() {
        List<String> allowed = new ArrayList<>();
        if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN) {
            for (RoleDefinition def : roleDefinitions) allowed.add(def.name);
        } else if (currentUserRole == SecureSessionManager.Role.ORG_ADMIN) {
            for (RoleDefinition def : roleDefinitions) {
                if (!"SYSTEM_ADMIN".equals(def.name)) allowed.add(def.name);
            }
        }
        return allowed.toArray(new String[0]);
    }

    private boolean isRoleAllowed(String role) {
        if (role == null) return false;
        for (String allowed : getAllowedRoles()) {
            if (allowed.equals(role)) return true;
        }
        return false;
    }

    private String getRoleDisplayName(String role, boolean arabic) {
        RoleDefinition def = findRole(role);
        if (def != null) return def.icon + " " + (arabic ? def.nameAr : def.nameEn);
        return role != null ? role : "";
    }

    // ═══════════════════════════════════════════════════════
    //  License Check
    // ═══════════════════════════════════════════════════════
    private boolean checkLicenseBeforeAction() {
        try {
            MiraBlobStorage.BlobData blob = MiraBlobStorage.load(requireContext());
            if (blob == null) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(isArabic ? "⚠️ لا يوجد ترخيص" : "⚠️ No License")
                        .setMessage(isArabic ? "أعد فتح التطبيق أو تواصل مع ميرا."
                                            : "Reopen the app or contact MIRA.")
                        .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                        .show();
                return false;
            }

            MiraBlobVerifier.VerifyResult verify = MiraBlobVerifier.verify(
                    blob.blobJson, blob.signatureB64, blob.publicKeyB64);
            if (!verify.valid) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(isArabic ? "🚨 ترخيص مُعدَّل" : "🚨 License Tampered")
                        .setMessage(verify.message)
                        .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                        .setCancelable(false)
                        .show();
                return false;
            }

            MiraBlobVerifier.VerifyResult full = MiraBlobVerifier.verifyAndValidate(blob, blob);
            if (!full.valid) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(isArabic ? "⏰ ترخيص غير صالح" : "⏰ License Invalid")
                        .setMessage(full.message)
                        .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                        .setCancelable(false)
                        .show();
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "checkLicenseBeforeAction error", e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Load Branches
    // ═══════════════════════════════════════════════════════

    private void loadBranchesCache() {
        if (ioExecutor == null || apiClient == null) return;
        try {
            ioExecutor.execute(() -> {
                final List<BranchItem> newBranches = new ArrayList<>();
                try {
                    MiraApiClient.ApiResponse response = apiClient.getBranches();
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray arr = response.data.optJSONArray("branches");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject b = arr.optJSONObject(i);
                                if (b == null) continue;
                                if (!"active".equals(b.optString("status"))) continue;
                                BranchItem item = new BranchItem();
                                item.id = b.optInt("id");
                                item.name = b.optString("branch_name", "");
                                item.code = b.optString("branch_code", "");
                                item.address = b.optString("address", "");
                                newBranches.add(item);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "loadBranchesCache error: " + e.getMessage());
                }

                if (newBranches.isEmpty()) {
                    newBranches.add(new BranchItem(1,
                            isArabic ? "الفرع الرئيسي" : "Main Branch", "MAIN", ""));
                }

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    branchesList.clear();
                    branchesList.addAll(newBranches);
                });
            });
        } catch (RejectedExecutionException ignored) {}
    }

    // ═══════════════════════════════════════════════════════
    //  Load Users
    // ═══════════════════════════════════════════════════════

    private void loadUsers() {
        if (ioExecutor == null || apiClient == null) return;

        if (progressLoading != null && userList.isEmpty()
                && (swipeRefreshLayout == null || !swipeRefreshLayout.isRefreshing())) {
            progressLoading.setVisibility(View.VISIBLE);
        }

        try {
            ioExecutor.execute(() -> {
                MiraApiClient.ApiResponse response = null;
                String errorMsg = null;
                try {
                    response = apiClient.getUsers();
                } catch (Exception e) {
                    errorMsg = e.getMessage();
                }

                final MiraApiClient.ApiResponse fResponse = response;
                final String fError = errorMsg;

                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
                    if (progressLoading    != null) progressLoading.setVisibility(View.GONE);

                    if (fResponse != null && fResponse.isSuccess && fResponse.data != null) {
                        parseUsers(fResponse.data);
                    } else {
                        String msg = fError != null
                                ? (isArabic ? "خطأ: " + fError : "Error: " + fError)
                                : (fResponse != null && fResponse.error != null
                                        ? fResponse.error
                                        : (isArabic ? "فشل التحميل" : "Load failed"));
                        showToast(msg);
                        updateEmptyState();
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void parseUsers(JSONObject data) {
        try {
            userList.clear();
            if (data == null) { updateStats(); return; }

            JSONArray users = data.optJSONArray("users");
            if (users == null) { updateStats(); return; }

            int currentBranchId = branchesManager.getActiveBranchId();
            Set<Integer> usedEmpNumbers = new HashSet<>();

            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) continue;

                UserData user    = new UserData();
                user.id          = u.optInt("id", u.optInt("user_id", 0));
                user.username    = u.optString("username", "");
                user.displayName = u.optString("display_name", "");
                user.role        = u.optString("role", "");
                user.employeeId  = u.optString("employee_id", "");
                user.status      = u.optString("status", "active");
                user.lastLogin   = u.optString("last_login", "");
                user.isOnline    = u.optBoolean("is_online", false);
                user.branchName  = u.optString("branch_name", "");
                user.branchId    = u.optInt("branch_id", 0);

                user.roles = new ArrayList<>();
                if (!user.role.isEmpty()) user.roles.add(user.role);
                JSONArray rolesArr = u.optJSONArray("roles");
                if (rolesArr != null) {
                    for (int j = 0; j < rolesArr.length(); j++) {
                        String r = rolesArr.optString(j, "");
                        if (!r.isEmpty() && !user.roles.contains(r)) user.roles.add(r);
                    }
                }

                if (!user.employeeId.isEmpty()) {
                    try {
                        String[] parts = user.employeeId.split("-");
                        if (parts.length > 1)
                            usedEmpNumbers.add(Integer.parseInt(parts[parts.length - 1]));
                    } catch (Exception ignored) {}
                }

                boolean shouldShow = false;
                if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN
                        || currentUserRole == SecureSessionManager.Role.ORG_ADMIN) {
                    shouldShow = true;
                } else if (currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER) {
                    if (user.branchId == currentBranchId) shouldShow = true;
                }
                if (shouldShow) userList.add(user);
            }

            int maxNum = 0;
            for (int num : usedEmpNumbers) if (num > maxNum) maxNum = num;
            employeeCounter.set(Math.max(maxNum + 1, userList.size() + 1));

            filterUsers(etSearchUser != null ? etSearchUser.getText().toString() : "");
            updateStats();

        } catch (Exception e) {
            Log.e(TAG, "parseUsers error", e);
        }
    }

    private void filterUsers(String query) {
        filteredList.clear();
        if (query == null || query.isEmpty()) {
            filteredList.addAll(userList);
        } else {
            String q = query.toLowerCase();
            for (UserData u : userList) {
                if ((u.displayName != null && u.displayName.toLowerCase().contains(q))
                        || (u.username   != null && u.username.toLowerCase().contains(q))
                        || (u.role       != null && u.role.toLowerCase().contains(q))
                        || (u.employeeId != null && u.employeeId.toLowerCase().contains(q))
                        || (u.branchName != null && u.branchName.toLowerCase().contains(q))) {
                    filteredList.add(u);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateStats() {
        int total = 0, active = 0, online = 0, suspended = 0;
        for (UserData u : userList) {
            total++;
            if ("active".equals(u.status)) active++;
            else suspended++;
            if (u.isOnline) online++;
        }
        if (tvTotalUsers     != null) tvTotalUsers.setText(String.valueOf(total));
        if (tvActiveUsers    != null) tvActiveUsers.setText(String.valueOf(active));
        if (tvOnlineNow      != null) tvOnlineNow.setText(String.valueOf(online));
        if (tvSuspendedUsers != null) tvSuspendedUsers.setText(String.valueOf(suspended));
        updateEmptyState();
        updateUserLimitBadge();
    }

    private void updateEmptyState() {
        if (emptyStateLayout == null) return;
        boolean isEmpty = filteredList.isEmpty();
        emptyStateLayout.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (rvUsersList != null) {
            rvUsersList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }
        if (isEmpty && tvEmptyState != null) {
            String query = etSearchUser != null ? etSearchUser.getText().toString().trim() : "";
            tvEmptyState.setText(!query.isEmpty()
                    ? (isArabic ? "لا نتائج لـ: " + query : "No results for: " + query)
                    : (isArabic ? "لا يوجد مستخدمين" : "No users yet"));
        }
    }

    private void updateUserLimitBadge() {
        if (tvUserLimitBadge == null) return;
        int maxUsers = 0;
        try {
            MiraBlobStorage.BlobData blob = MiraBlobStorage.load(requireContext());
            if (blob != null) maxUsers = blob.maxUsers;
            else maxUsers = sessionManager.getLicenseMaxUsers();
        } catch (Exception e) {
            maxUsers = sessionManager.getLicenseMaxUsers();
        }

        if (maxUsers <= 0) { tvUserLimitBadge.setVisibility(View.GONE); return; }

        int activeUsers = 0;
        for (UserData u : userList) if ("active".equals(u.status)) activeUsers++;

        tvUserLimitBadge.setVisibility(View.VISIBLE);
        int remaining = maxUsers - activeUsers;
        String text;
        int color;
        if (remaining <= 0) {
            text = isArabic ? "🔴 " + activeUsers + "/" + maxUsers : "🔴 " + activeUsers + "/" + maxUsers;
            color = 0xFFC0392B;
        } else if (remaining <= 2) {
            text = isArabic ? "🟠 " + activeUsers + "/" + maxUsers + " (متبقٍ " + remaining + ")"
                            : "🟠 " + activeUsers + "/" + maxUsers + " (" + remaining + " left)";
            color = 0xFFF39C12;
        } else {
            text = isArabic ? "🟢 " + activeUsers + "/" + maxUsers
                            : "🟢 " + activeUsers + "/" + maxUsers;
            color = 0xFF27AE60;
        }
        tvUserLimitBadge.setText(text);
        tvUserLimitBadge.setTextColor(color);

        if (btnAddUser != null) {
            boolean canAdd = remaining > 0;
            btnAddUser.setEnabled(canAdd);
            btnAddUser.setAlpha(canAdd ? 1.0f : 0.5f);
        }
    }

    private void showToast(String message) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) { Log.e(TAG, "Toast error", e); }
    }

    // ═══════════════════════════════════════════════════════
    //  Password Generator
    // ═══════════════════════════════════════════════════════

    private String generateStrongPassword(int length) {
        if (length < 8) length = 8;
        StringBuilder sb = new StringBuilder(length);
        sb.append(UPPER.charAt(random.nextInt(UPPER.length())));
        sb.append(LOWER.charAt(random.nextInt(LOWER.length())));
        sb.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        sb.append(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
        for (int i = 4; i < length; i++) sb.append(ALL.charAt(random.nextInt(ALL.length())));
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i]; chars[i] = chars[j]; chars[j] = temp;
        }
        return new String(chars);
    }

    private String suggestUsername(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";
        String cleaned = displayName.trim()
                .replaceAll("\\s+", ".").toLowerCase()
                .replaceAll("[^a-z0-9.]", "");
        if (cleaned.isEmpty()) return "user" + (System.currentTimeMillis() % 10000);
        return cleaned;
    }

    private void copyToClipboard(String text) {
        if (getContext() == null || text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("password", text));
        showToast(isArabic ? "📋 تم نسخ كلمة المرور!" : "📋 Password copied!");
    }

    private String generateEmployeeId(String branchCode) {
        String prefix = (branchCode != null && branchCode.length() >= 2)
                ? branchCode.substring(0, 2).toUpperCase() : "HQ";
        return prefix + "-" + String.format(Locale.US, "%03d", employeeCounter.getAndIncrement());
    }

    private boolean isPasswordStrong(String password) {
        if (password == null || password.length() < 8) return false;
        if (!password.matches(".*[A-Z].*")) return false;
        if (!password.matches(".*[0-9].*")) return false;
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*")) return false;
        return true;
    }

    // ═══════════════════════════════════════════════════════
    //  User Dialog
    // ═══════════════════════════════════════════════════════

    private void showUserDialog(@Nullable UserData existingUser) {
        try {
            if (!isAdded() || getContext() == null) return;

            boolean isEdit = existingUser != null;

            // ✅ FIX #2: تحقق صارم من معرف المستخدم
            if (isEdit && existingUser.id <= 0) {
                Log.e(TAG, "Cannot edit: invalid user ID (0) for " + existingUser.username);
                showToast(isArabic ? "⚠️ معرف المستخدم غير صالح" : "⚠️ Invalid user ID");
                return;
            }

            View dialogView = LayoutInflater.from(getContext())
                    .inflate(R.layout.dialog_user_edit, null);

            Context themedContext = new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme);
            AlertDialog dialog = new MaterialAlertDialogBuilder(themedContext)
                    .setView(dialogView).setCancelable(true).create();

            if (dialog.getWindow() != null) {
                boolean isDark = (getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                dialog.getWindow().setBackgroundDrawableResource(
                        isDark ? R.drawable.bg_mira_dialog_dark : R.drawable.bg_mira_dialog);
            }

            TextView tvTitle       = dialogView.findViewById(R.id.tvDialogTitle);
            TextView tvSubtitle    = dialogView.findViewById(R.id.tvDialogSubtitle);
            EditText etDisplayName = dialogView.findViewById(R.id.etDisplayName);
            EditText etUsername    = dialogView.findViewById(R.id.etUsername);
            EditText etPassword    = dialogView.findViewById(R.id.etPassword);
            EditText etEmployeeId  = dialogView.findViewById(R.id.etEmployeeId);
            EditText etBranch      = dialogView.findViewById(R.id.etBranch);
            EditText etRole        = dialogView.findViewById(R.id.etRole);

            Button btnCancel   = dialogView.findViewById(R.id.btnCancelUser);
            Button btnNextStep = dialogView.findViewById(R.id.btnNextStep);
            Button btnPrevStep = dialogView.findViewById(R.id.btnPrevStep);
            Button btnSaveUser = dialogView.findViewById(R.id.btnSaveUser);

            LinearLayout layoutStep1    = dialogView.findViewById(R.id.layoutStep1);
            LinearLayout layoutStep2    = dialogView.findViewById(R.id.layoutStep2);
            LinearLayout layoutButtons1 = dialogView.findViewById(R.id.layoutButtons1);
            LinearLayout stepsIndicator = dialogView.findViewById(R.id.stepsIndicator);

            MaterialButton btnGeneratePassword = dialogView.findViewById(R.id.btnGeneratePassword);
            MaterialButton btnCopyPassword     = dialogView.findViewById(R.id.btnCopyPassword);

            ImageButton btnSuggestUsername = dialogView.findViewById(R.id.btnSuggestUsername);
            ImageButton btnCloseDialog     = dialogView.findViewById(R.id.btnCloseDialog);

            FrameLayout loadingOverlay = dialogView.findViewById(R.id.loadingOverlay);
            TextView tvLoadingText     = dialogView.findViewById(R.id.tvLoadingText);

            final Set<String> selectedRoles = new LinkedHashSet<>();
            if (isEdit && existingUser.roles != null) selectedRoles.addAll(existingUser.roles);

            if (btnCloseDialog != null) btnCloseDialog.setOnClickListener(v -> dialog.dismiss());

            if (tvTitle != null) tvTitle.setText(isEdit
                    ? (isArabic ? "تعديل مستخدم" : "Edit User")
                    : (isArabic ? "إضافة مستخدم" : "Add User"));
            if (tvSubtitle != null) tvSubtitle.setText(isEdit
                    ? (isArabic ? "تعديل بيانات المستخدم" : "Edit user data")
                    : (isArabic ? "أدخل بيانات المستخدم" : "Enter user data"));

            // ═══ Branch Picker ═══
            final List<String> branchNames = new ArrayList<>();
            int activeBranchId = branchesManager.getActiveBranchId();
            int initialBranchIndex = -1;
            for (int i = 0; i < branchesList.size(); i++) {
                BranchItem b = branchesList.get(i);
                branchNames.add(b.name + " (" + b.code + ")");
                if (b.id == activeBranchId) initialBranchIndex = i;
            }
            if (branchNames.isEmpty()) {
                branchNames.add(isArabic ? "الفرع الرئيسي (MAIN)" : "Main Branch (MAIN)");
                initialBranchIndex = 0;
            }
            if (initialBranchIndex < 0) initialBranchIndex = 0;

            final int[] selectedBranchId = {activeBranchId};
            final String[] selectedBranchName = {
                    branchNames.get(initialBranchIndex).replaceAll("\\s*\\(.*\\)\\s*$", "").trim()
            };

            if (etBranch != null) {
                etBranch.setText(branchNames.get(initialBranchIndex));
                etBranch.setFocusable(false);
                etBranch.setClickable(true);
                etBranch.setInputType(InputType.TYPE_NULL);
                etBranch.setOnClickListener(v -> {
                    if (!isAdded() || getContext() == null || branchNames.isEmpty()) return;
                    new MaterialAlertDialogBuilder(
                            new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
                            .setTitle(isArabic ? "اختر الفرع" : "Select Branch")
                            .setItems(branchNames.toArray(new String[0]), (d, which) -> {
                                etBranch.setText(branchNames.get(which));
                                if (which < branchesList.size()) {
                                    selectedBranchId[0] = branchesList.get(which).id;
                                    selectedBranchName[0] = branchesList.get(which).name;
                                }
                            })
                            .show();
                });
            }

            updateRoleDisplay(etRole, selectedRoles);
            if (etRole != null) {
                etRole.setHint(isArabic ? "اضغط لاختيار الصلاحيات" : "Tap to select permissions");
                etRole.setFocusable(false);
                etRole.setClickable(true);
                etRole.setInputType(InputType.TYPE_NULL);
                etRole.setOnClickListener(v -> {
                    if (!isAdded() || getContext() == null) return;
                    showMultiRolePicker(selectedRoles, roles -> updateRoleDisplay(etRole, roles));
                });
            }

            String branchCode = branchesManager.getActiveBranchCode();
            if (etEmployeeId != null && !isEdit) {
                etEmployeeId.setText(generateEmployeeId(branchCode));
                etEmployeeId.setEnabled(false);
            }

            if (btnGeneratePassword != null) {
                btnGeneratePassword.setOnClickListener(v -> {
                    String pwd = generateStrongPassword(12);
                    if (etPassword != null) etPassword.setText(pwd);
                    showToast(isArabic ? "🔐 تم التوليد!" : "🔐 Generated!");
                });
            }

            if (btnCopyPassword != null) {
                btnCopyPassword.setOnClickListener(v -> {
                    String pwd = etPassword != null ? etPassword.getText().toString() : "";
                    if (!pwd.isEmpty()) copyToClipboard(pwd);
                });
            }

            if (btnSuggestUsername != null && etDisplayName != null && etUsername != null) {
                btnSuggestUsername.setOnClickListener(v -> {
                    String name = etDisplayName.getText().toString().trim();
                    if (!name.isEmpty()) etUsername.setText(suggestUsername(name));
                });
            }

            // ═══ Fill edit data ═══
            if (isEdit) {
                if (etDisplayName != null) etDisplayName.setText(existingUser.displayName);
                if (etUsername != null) {
                    etUsername.setText(existingUser.username);
                    etUsername.setEnabled(false);
                }
                if (etPassword != null) {
                    etPassword.setHint(isArabic ? "اتركه فارغاً" : "Leave empty to keep");
                }
                if (etEmployeeId != null) etEmployeeId.setText(existingUser.employeeId);

                if (existingUser.branchId > 0) {
                    for (int i = 0; i < branchesList.size(); i++) {
                        if (branchesList.get(i).id == existingUser.branchId) {
                            if (i < branchNames.size() && etBranch != null)
                                etBranch.setText(branchNames.get(i));
                            selectedBranchId[0] = branchesList.get(i).id;
                            selectedBranchName[0] = branchesList.get(i).name;
                            break;
                        }
                    }
                }
                updateRoleDisplay(etRole, selectedRoles);

                if (layoutStep1    != null) layoutStep1.setVisibility(View.VISIBLE);
                if (layoutStep2    != null) layoutStep2.setVisibility(View.VISIBLE);
                if (stepsIndicator != null) stepsIndicator.setVisibility(View.GONE);
                if (btnNextStep    != null) btnNextStep.setVisibility(View.GONE);
                if (btnPrevStep    != null) btnPrevStep.setVisibility(View.GONE);
                if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
                if (btnCancel      != null) { btnCancel.setVisibility(View.VISIBLE); btnCancel.setText(isArabic ? "إلغاء" : "Cancel"); }
                if (btnSaveUser    != null) btnSaveUser.setVisibility(View.VISIBLE);
            } else {
                if (layoutStep1    != null) layoutStep1.setVisibility(View.VISIBLE);
                if (layoutStep2    != null) layoutStep2.setVisibility(View.GONE);
                if (stepsIndicator != null) stepsIndicator.setVisibility(View.VISIBLE);
                if (btnNextStep    != null) btnNextStep.setVisibility(View.VISIBLE);
                if (btnSaveUser    != null) btnSaveUser.setVisibility(View.GONE);
                if (btnPrevStep    != null) btnPrevStep.setVisibility(View.GONE);
                if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
                if (btnCancel      != null) { btnCancel.setVisibility(View.VISIBLE); btnCancel.setText(isArabic ? "إلغاء" : "Cancel"); }
            }

            if (btnNextStep != null) {
                btnNextStep.setOnClickListener(v -> {
                    String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                    String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                    String password = etPassword != null ? etPassword.getText().toString().trim() : "";

                    if (displayName.isEmpty()) { if (etDisplayName != null) etDisplayName.setError(isArabic ? "مطلوب" : "Required"); return; }
                    if (username.isEmpty()) { if (etUsername != null) etUsername.setError(isArabic ? "مطلوب" : "Required"); return; }
                    if (!isEdit && !isPasswordStrong(password)) {
                        showToast(isArabic ? "كلمة المرور ضعيفة" : "Password too weak");
                        return;
                    }
                    if (layoutStep1 != null) layoutStep1.setVisibility(View.GONE);
                    if (layoutStep2 != null) layoutStep2.setVisibility(View.VISIBLE);
                    if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
                    if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
                    if (btnPrevStep != null) btnPrevStep.setVisibility(View.VISIBLE);
                });
            }

            if (btnPrevStep != null) {
                btnPrevStep.setOnClickListener(v -> {
                    if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
                    if (layoutStep2 != null) layoutStep2.setVisibility(View.GONE);
                    if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
                    if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
                    if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
                });
            }

            if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

            // ═══ Save ═══
            if (btnSaveUser != null) {
                btnSaveUser.setOnClickListener(v -> {
                    String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                    String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                    String password = etPassword != null ? etPassword.getText().toString().trim() : "";
                    String empId    = etEmployeeId != null ? etEmployeeId.getText().toString().trim() : "";

                    if (selectedRoles.isEmpty()) {
                        showToast(isArabic ? "اختر صلاحية" : "Select permission");
                        return;
                    }
                    if (displayName.isEmpty()) { showToast(isArabic ? "الاسم مطلوب" : "Name required"); return; }
                    if (username.isEmpty()) { showToast(isArabic ? "اسم المستخدم مطلوب" : "Username required"); return; }

                    for (String role : selectedRoles) {
                        if (!isRoleAllowed(role)) {
                            showToast(isArabic ? "❌ ليس لديك صلاحية لهذا الدور: " + role
                                                : "❌ Not allowed to assign: " + role);
                            return;
                        }
                    }

                    String primaryRole = null;
                    if (isEdit && existingUser.role != null && selectedRoles.contains(existingUser.role)) {
                        primaryRole = existingUser.role;
                    } else {
                        for (String r : getAllowedRoles()) {
                            if (selectedRoles.contains(r)) { primaryRole = r; break; }
                        }
                        if (primaryRole == null) primaryRole = selectedRoles.iterator().next();
                    }

                    final int fBranchId = selectedBranchId[0] > 0
                            ? selectedBranchId[0] : branchesManager.getActiveBranchId();
                    final String fBranchName = selectedBranchName[0] != null && !selectedBranchName[0].isEmpty()
                            ? selectedBranchName[0]
                            : (isArabic ? "الفرع الرئيسي" : "Main Branch");

                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);
                    if (tvLoadingText != null) tvLoadingText.setText(isArabic ? "جاري الحفظ..." : "Saving...");
                    btnSaveUser.setEnabled(false);

                    final String fPrimaryRole = primaryRole;
                    final List<String> fRolesList = new ArrayList<>(selectedRoles);

                    if (ioExecutor == null || ioExecutor.isShutdown()) {
                        showToast(isArabic ? "❌ الخدمة غير متاحة" : "❌ Service unavailable");
                        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                        btnSaveUser.setEnabled(true);
                        return;
                    }

                    try {
                        ioExecutor.execute(() -> {
                            MiraApiClient.ApiResponse response = null;
                            String errorMsg = null;
                            try {
                                JSONObject payload = new JSONObject();
                                payload.put("username",     username);
                                payload.put("display_name", displayName);
                                payload.put("role",         fPrimaryRole);
                                payload.put("employee_id",  empId);
                                payload.put("branch_id",    fBranchId);
                                payload.put("branch_name",  fBranchName);

                                JSONArray rolesArray = new JSONArray();
                                for (String r : fRolesList) rolesArray.put(r);
                                payload.put("roles", rolesArray);

                                if (!password.isEmpty()) payload.put("password", password);

                                // ✅ FIX #2: إرسال user_id و id معاً (fallback)
                                if (isEdit) {
                                    payload.put("user_id", existingUser.id);
                                    payload.put("id",      existingUser.id);
                                    Log.d(TAG, "updateUser payload: " + payload.toString());
                                }

                                response = isEdit
                                        ? apiClient.updateUser(payload)
                                        : apiClient.createUser(payload);
                            } catch (Exception e) {
                                errorMsg = e.getMessage();
                            }

                            final MiraApiClient.ApiResponse fResponse = response;
                            final String fError = errorMsg;

                            mainHandler.post(() -> {
                                if (!isAdded()) return;
                                if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);

                                if (fResponse != null && fResponse.isSuccess) {
                                    showToast(isEdit
                                            ? (isArabic ? "✅ تم التعديل" : "✅ Updated")
                                            : (isArabic ? "✅ تمت الإضافة" : "✅ Added"));
                                    dialog.dismiss();
                                    loadUsers();
                                    return;
                                }

                                String errorCode = (fResponse != null && fResponse.data != null)
                                        ? fResponse.data.optString("code", "") : "";

                                if ("user_limit_reached".equals(errorCode)) {
                                    int maxUsers = 0;
                                    JSONObject limits = (fResponse != null && fResponse.data != null)
                                            ? fResponse.data.optJSONObject("limits") : null;
                                    if (limits != null) maxUsers = limits.optInt("max_users", 0);

                                    new MaterialAlertDialogBuilder(
                                            new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
                                            .setTitle(isArabic ? "🚫 الحد الأقصى" : "🚫 Limit Reached")
                                            .setMessage(isArabic
                                                    ? "الترخيص يسمح بـ " + maxUsers + " مستخدم فقط."
                                                    : "License allows max " + maxUsers + " users.")
                                            .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                                            .show();
                                    if (btnSaveUser != null) btnSaveUser.setEnabled(true);
                                    return;
                                }

                                String msg = fError != null
                                        ? (isArabic ? "❌ خطأ: " + fError : "❌ Error: " + fError)
                                        : (fResponse != null && fResponse.error != null
                                                ? "❌ " + fResponse.error
                                                : (isArabic ? "❌ فشل" : "❌ Failed"));
                                showToast(msg);
                                if (btnSaveUser != null) btnSaveUser.setEnabled(true);
                            });
                        });
                    } catch (RejectedExecutionException ree) {
                        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                        btnSaveUser.setEnabled(true);
                        showToast(isArabic ? "❌ الخدمة غير متاحة" : "❌ Service unavailable");
                    }
                });
            }

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "showUserDialog error", e);
            showToast("Error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Role Display & Picker
    // ═══════════════════════════════════════════════════════

    private void updateRoleDisplay(EditText etRole, Set<String> selectedRoles) {
        if (etRole == null) return;
        if (selectedRoles == null || selectedRoles.isEmpty()) { etRole.setText(""); return; }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String role : selectedRoles) {
            if (count > 0) sb.append(" + ");
            sb.append(getRoleDisplayName(role, isArabic));
            count++;
            if (count >= 2) break;
        }
        if (selectedRoles.size() > 2) {
            sb.append(" +").append(selectedRoles.size() - 2).append(isArabic ? " أخرى" : " more");
        }
        etRole.setText(sb.toString());
    }

    private void showMultiRolePicker(Set<String> selectedRoles, RolePickerCallback callback) {
        try {
            if (!isAdded() || getContext() == null) return;

            String[] allowedRoles = getAllowedRoles();
            if (allowedRoles == null || allowedRoles.length == 0) {
                showToast(isArabic ? "لا توجد صلاحيات متاحة" : "No permissions available");
                return;
            }

            String[] displayNames = new String[allowedRoles.length];
            final Set<String> tempSelection = new LinkedHashSet<>(selectedRoles);
            boolean[] checked = new boolean[allowedRoles.length];
            for (int i = 0; i < allowedRoles.length; i++) {
                displayNames[i] = getRoleDisplayName(allowedRoles[i], isArabic);
                checked[i] = tempSelection.contains(allowedRoles[i]);
            }

            new MaterialAlertDialogBuilder(
                    new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
                    .setTitle(isArabic ? "اختر الصلاحيات" : "Select Permissions")
                    .setMultiChoiceItems(displayNames, checked, (d, which, isChecked) -> {
                        if (isChecked) tempSelection.add(allowedRoles[which]);
                        else tempSelection.remove(allowedRoles[which]);
                    })
                    .setPositiveButton(isArabic ? "تأكيد" : "OK", (d, w) -> {
                        selectedRoles.clear();
                        selectedRoles.addAll(tempSelection);
                        if (callback != null) callback.onRolesSelected(selectedRoles);
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } catch (Exception e) { Log.e(TAG, "showMultiRolePicker error", e); }
    }

    private String formatRolesDisplay(Set<String> roles) {
        if (roles == null || roles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String role : roles) {
            if (count > 0) sb.append(" + ");
            sb.append(getRoleDisplayName(role, isArabic));
            count++;
        }
        return sb.toString();
    }

    interface RolePickerCallback { void onRolesSelected(Set<String> roles); }

    // ═══════════════════════════════════════════════════════
    //  ✅ FIX #3: Toggle User Status (بدلاً من الحذف)
    // ═══════════════════════════════════════════════════════

    private void toggleUserStatus(int userId, String name, String currentStatus) {
        try {
            if (!isAdded() || getContext() == null) return;
            if (userId <= 0) {
                showToast(isArabic ? "⚠️ معرف غير صالح" : "⚠️ Invalid ID");
                return;
            }
            if (!canEditUser(null)) {
                showToast(isArabic ? "❌ ليس لديك صلاحية" : "❌ No permission");
                return;
            }

            boolean isActive = "active".equals(currentStatus);
            final String newStatus = isActive ? "inactive" : "active";
            String action = isActive
                    ? (isArabic ? "تعليق" : "Suspend")
                    : (isArabic ? "تفعيل" : "Activate");

            new MaterialAlertDialogBuilder(
                    new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
                    .setTitle(action + " " + (isArabic ? "المستخدم" : "User"))
                    .setMessage(isArabic
                            ? "هل تريد " + action + " \"" + name + "\"؟"
                            : "Do you want to " + action.toLowerCase() + " \"" + name + "\"?")
                    .setPositiveButton(action, (d, w) -> {
                        if (ioExecutor == null || ioExecutor.isShutdown()) {
                            showToast(isArabic ? "❌ الخدمة غير متاحة" : "❌ Service unavailable");
                            return;
                        }
                        try {
                            ioExecutor.execute(() -> {
                                MiraApiClient.ApiResponse response = null;
                                String err = null;
                                try {
                                    // محاولة استخدام toggleUserStatus إن وُجد
                                    response = apiClient.toggleUserStatus(userId, newStatus);

                                    // fallback: updateUser
                                    if (response == null || !response.isSuccess) {
                                        JSONObject payload = new JSONObject();
                                        payload.put("user_id", userId);
                                        payload.put("id",      userId);
                                        payload.put("status",  newStatus);
                                        response = apiClient.updateUser(payload);
                                    }
                                } catch (Exception e) {
                                    err = e.getMessage();
                                }
                                final MiraApiClient.ApiResponse fRes = response;
                                final String fErr = err;

                                mainHandler.post(() -> {
                                    if (!isAdded()) return;
                                    if (fRes != null && fRes.isSuccess) {
                                        showToast(isArabic
                                                ? "✅ تم " + action + " المستخدم"
                                                : "✅ User " + action.toLowerCase() + "d");
                                        loadUsers();
                                    } else {
                                        showToast(fErr != null ? fErr
                                                : (isArabic ? "❌ فشلت العملية" : "❌ Failed"));
                                    }
                                });
                            });
                        } catch (RejectedExecutionException ignored) {
                            showToast(isArabic ? "❌ الخدمة غير متاحة" : "❌ Service unavailable");
                        }
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();

        } catch (Exception e) { Log.e(TAG, "toggleUserStatus error", e); }
    }

    // ═══════════════════════════════════════════════════════
    //  Data Classes
    // ═══════════════════════════════════════════════════════

    static class UserData {
        int id;
        int branchId;
        String username;
        String displayName;
        String role;
        List<String> roles;
        String employeeId;
        String status;
        String lastLogin;
        String branchName;
        boolean isOnline;
    }

    static class BranchItem {
        int id; String name; String code; String address;
        BranchItem() {}
        BranchItem(int id, String name, String code, String address) {
            this.id = id; this.name = name; this.code = code; this.address = address;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Adapter
    // ═══════════════════════════════════════════════════════

    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        final List<UserData> list;
        final boolean isArabic;

        UsersAdapter(List<UserData> list, boolean isArabic) {
            this.list = list; this.isArabic = isArabic;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user_row, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            try {
                UserData u = list.get(pos);
                if (h.tvDisplayName != null) h.tvDisplayName.setText(u.displayName);
                if (h.tvUsername != null) h.tvUsername.setText("@" + u.username);

                if (h.tvRole != null) {
                    if (u.roles != null && !u.roles.isEmpty()) {
                        h.tvRole.setText(formatRolesDisplay(new LinkedHashSet<>(u.roles)));
                    } else {
                        h.tvRole.setText(getRoleDisplayName(u.role, isArabic));
                    }
                }

                if (h.tvEmployeeId != null) {
                    if (u.employeeId != null && !u.employeeId.isEmpty()) {
                        h.tvEmployeeId.setText("🪪 " + u.employeeId);
                        h.tvEmployeeId.setVisibility(View.VISIBLE);
                    } else h.tvEmployeeId.setVisibility(View.GONE);
                }

                if (h.tvBranchName != null) {
                    if (u.branchName != null && !u.branchName.isEmpty()) {
                        h.tvBranchName.setText("🏢 " + u.branchName);
                        h.tvBranchName.setVisibility(View.VISIBLE);
                    } else h.tvBranchName.setVisibility(View.GONE);
                }

                if ("active".equals(u.status)) {
                    if (h.vStatusDot != null) h.vStatusDot.setBackgroundResource(R.drawable.circle_green);
                    if (h.tvStatus != null) {
                        h.tvStatus.setText(u.isOnline
                                ? (isArabic ? "🟢 متصل" : "🟢 Online")
                                : (isArabic ? "🟢 نشط" : "🟢 Active"));
                        h.tvStatus.setTextColor(u.isOnline ? 0xFF3B82F6 : 0xFF22C55E);
                    }
                } else {
                    if (h.vStatusDot != null) h.vStatusDot.setBackgroundResource(R.drawable.circle_red);
                    if (h.tvStatus != null) {
                        h.tvStatus.setText(isArabic ? "🔴 موقوف" : "🔴 Suspended");
                        h.tvStatus.setTextColor(0xFFEF4444);
                    }
                }

                if (h.btnEdit != null) {
                    if (canEditUser(u.role)) {
                        h.btnEdit.setVisibility(View.VISIBLE);
                        h.btnEdit.setOnClickListener(v -> showUserDialog(u));
                    } else h.btnEdit.setVisibility(View.GONE);
                }

                // ✅ FIX #3: استبدال الحذف بـ "تعليق/تفعيل"
                if (h.btnDelete != null) {
                    if (canEditUser(u.role)) {
                        h.btnDelete.setVisibility(View.VISIBLE);
                        h.btnDelete.setOnClickListener(v ->
                                toggleUserStatus(u.id, u.displayName, u.status));
                    } else h.btnDelete.setVisibility(View.GONE);
                }

                if (h.btnPermissions != null) h.btnPermissions.setVisibility(View.GONE);

            } catch (Exception e) { Log.e(TAG, "Adapter bind error", e); }
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDisplayName, tvUsername, tvRole, tvEmployeeId, tvStatus, tvBranchName;
            View vStatusDot;
            ImageButton btnEdit, btnDelete, btnPermissions;

            VH(View v) {
                super(v);
                tvDisplayName  = v.findViewById(R.id.tvRowDisplayName);
                tvUsername     = v.findViewById(R.id.tvRowUsername);
                tvRole         = v.findViewById(R.id.tvRowRole);
                tvEmployeeId   = v.findViewById(R.id.tvRowEmployeeId);
                tvStatus       = v.findViewById(R.id.tvRowStatus);
                tvBranchName   = v.findViewById(R.id.tvRowBranchName);
                vStatusDot     = v.findViewById(R.id.vStatusDot);
                btnEdit        = v.findViewById(R.id.btnRowEdit);
                btnDelete      = v.findViewById(R.id.btnRowDelete);
                btnPermissions = v.findViewById(R.id.btnPermissions);
            }
        }
    }
}
