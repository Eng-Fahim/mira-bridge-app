package com.example.uhf.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.tools.SecureSessionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class UsersManagementFragment extends Fragment {

    // ==================== UI Elements ====================
    private RecyclerView rvUsersList;
    private EditText etSearchUser;
    private TextView tvTotalUsers, tvActiveUsers, tvOnlineNow;
    private Button btnAddUser, btnRefreshUsers;

    // ==================== Managers & API ====================
    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== Data ====================
    private UsersAdapter adapter;
    private final List<UserData> userList = new ArrayList<>();
    private final List<UserData> filteredList = new ArrayList<>();
    private boolean isArabic;
    private SecureSessionManager.Role currentUserRole;

    // ==================== Roles & Icons ====================
    private static final String[] ROLES = {
        "SYSTEM_ADMIN", "ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR",
        "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"
    };

    private static final String[] ROLES_AR = {
        "مهندس النظام", "مدير الجهة", "مدير الفرع", "مدير المخزون",
        "موظف مبيعات", "أمن", "أمن البوابات", "مدقق"
    };

    private static final String[] ROLES_EN = {
        "System Admin", "Organization Admin", "Branch Manager", "Inventory Manager",
        "POS Operator", "Security", "Gate Security", "Auditor"
    };

    private static final String[] ROLE_ICONS = {
        "🔧", "🏢", "👤", "📊", "🛒", "🔒", "🚪", "🔍"
    };

    // ==================== Granular Permissions ====================
    public static class GranularPermissions {
        public static final String[] MODULES_AR = {
            "🛒 قسم المبيعات", "📦 قسم المخزون والورشة", "📊 التقارير والإدارة"
        };
        
        public static final String[][] PERMISSION_KEYS = {
            {"sales:view", "sales:create_invoice", "sales:apply_discount", "sales:refund"},
            {"inventory:view", "inventory:add_item", "inventory:adjust_stock", "inventory:transfer"},
            {"reports:view_financial", "users:manage", "settings:modify"}
        };

        public static final String[][] PERMISSION_LABELS_AR = {
            {"عرض المبيعات", "إنشاء فاتورة جديدة", "تطبيق الخصومات", "إجراء المرتجعات"},
            {"عرض المخزون", "إضافة أصناف جديدة", "تعديل الكميات", "تحويل بين الفروع"},
            {"عرض التقارير المالية", "إدارة المستخدمين", "تعديل إعدادات النظام"}
        };
    }

    // ==================== Password Generator ====================
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;
    private final SecureRandom random = new SecureRandom();

    private final AtomicInteger employeeCounter = new AtomicInteger(1);
    private final List<BranchItem> branchesList = new ArrayList<>();

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_users_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
            apiClient = MiraApiClient.getInstance(requireContext());
            sessionManager = new SessionManager(requireContext());
            branchesManager = MiraBranchesManager.getInstance(requireContext());
            currentUserRole = sessionManager.getUserRole();

            initializeViews(view);
            applyLanguage();
            loadBranchesCache();
            setupRecyclerView();
            setupButtons();
            setupSearch();
            loadUsers();
        } catch (Exception e) {
            showToast("Error initializing: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadUsers();
    }

    // ==================== Initialization ====================

    private void initializeViews(View view) {
        rvUsersList = view.findViewById(R.id.rvUsersList);
        etSearchUser = view.findViewById(R.id.etSearchUser);
        tvTotalUsers = view.findViewById(R.id.tvTotalUsers);
        tvActiveUsers = view.findViewById(R.id.tvActiveUsers);
        tvOnlineNow = view.findViewById(R.id.tvOnlineNow);
        btnAddUser = view.findViewById(R.id.btnAddUser);
        btnRefreshUsers = view.findViewById(R.id.btnRefreshUsers);
    }

    private void setupRecyclerView() {
        rvUsersList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new UsersAdapter(filteredList, isArabic);
        rvUsersList.setAdapter(adapter);
    }

    private void setupButtons() {
        btnAddUser.setOnClickListener(v -> {
            if (canManageUsers()) {
                showUserDialog(null);
            } else {
                showToast(isArabic ? "❌ ليس لديك صلاحية لإضافة مستخدمين" : "❌ You don't have permission to add users");
            }
        });

        btnRefreshUsers.setOnClickListener(v -> loadUsers());
    }

    private void setupSearch() {
        etSearchUser.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filterUsers(s.toString()); }
        });
    }

    private void applyLanguage() {
        if (btnAddUser != null) btnAddUser.setText(isArabic ? "+ إضافة مستخدم" : "+ Add User");
        if (etSearchUser != null) etSearchUser.setHint(isArabic ? "بحث عن مستخدم..." : "Search users...");
        if (btnRefreshUsers != null) btnRefreshUsers.setText(isArabic ? "تحديث" : "Refresh");
    }

    // ==================== Permission Logic ====================

    private boolean canManageUsers() {
        return currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN
            || currentUserRole == SecureSessionManager.Role.ORG_ADMIN
            || currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER;
    }

    private boolean canDeleteUser(String targetRole) {
        if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN) return true;
        if (currentUserRole == SecureSessionManager.Role.ORG_ADMIN && !"SYSTEM_ADMIN".equals(targetRole)) return true;
        if (currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER) {
            String[] allowed = {"INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY"};
            for (String a : allowed) { if (a.equals(targetRole)) return true; }
        }
        return false;
    }

    private boolean canEditUser(String targetRole) {
        return canDeleteUser(targetRole);
    }

    private String[] getAllowedRoles() {
        if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN) return ROLES;
        if (currentUserRole == SecureSessionManager.Role.ORG_ADMIN) {
            return new String[]{"ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"};
        }
        if (currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER) {
            return new String[]{"INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY"};
        }
        return new String[]{};
    }

    private boolean isRoleAllowed(String role) {
        for (String allowed : getAllowedRoles()) {
            if (allowed.equals(role)) return true;
        }
        return false;
    }

    private String getRoleDisplayName(String role, boolean arabic) {
        for (int i = 0; i < ROLES.length; i++) {
            if (ROLES[i].equals(role)) {
                return ROLE_ICONS[i] + " " + (arabic ? ROLES_AR[i] : ROLES_EN[i]);
            }
        }
        return role;
    }

    // ==================== Load Branches & Users ====================

    private void loadBranchesCache() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getBranches();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("branches");
                    branchesList.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject b = arr.optJSONObject(i);
                            if (b != null && "active".equals(b.optString("status"))) {
                                BranchItem item = new BranchItem();
                                item.id = b.optInt("id");
                                item.name = b.optString("branch_name", "");
                                item.code = b.optString("branch_code", "");
                                branchesList.add(item);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (branchesList.isEmpty()) {
                    branchesList.add(new BranchItem(1, "الفرع الرئيسي", "MAIN"));
                }
            }
        });
    }

    private void loadUsers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getUsers();
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        parseUsers(response.data);
                    } else {
                        showToast(isArabic ? "فشل تحميل البيانات" : "Failed to load data");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> showToast("Error: " + e.getMessage()));
            }
        });
    }

    private void parseUsers(JSONObject data) {
        try {
            userList.clear();
            if (data == null) return;
            JSONArray users = data.optJSONArray("users");
            if (users == null) return;

            int currentBranchId = branchesManager.getActiveBranchId();

            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) continue;

                UserData user = new UserData();
                user.id = u.optInt("id");
                user.username = u.optString("username", "");
                user.displayName = u.optString("display_name", "");
                user.role = u.optString("role", "");
                user.employeeId = u.optString("employee_id", "");
                user.status = u.optString("status", "active");
                user.isOnline = u.optBoolean("is_online", false);
                user.branchName = u.optString("branch_name", "");
                user.branchId = u.optInt("branch_id", 0);

                user.roles = new ArrayList<>();
                user.roles.add(user.role);
                
                // تحميل الصلاحيات المخصصة
                JSONArray permsArr = u.optJSONArray("granular_permissions");
                if (permsArr != null) {
                    for (int j = 0; j < permsArr.length(); j++) {
                        user.granularPermissions.add(permsArr.optString(j));
                    }
                }

                if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN ||
                    currentUserRole == SecureSessionManager.Role.ORG_ADMIN ||
                    (currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER && user.branchId == currentBranchId)) {
                    userList.add(user);
                }
            }

            filterUsers(etSearchUser != null ? etSearchUser.getText().toString() : "");
            updateStats();
        } catch (Exception e) {
            showToast("Parse error: " + e.getMessage());
        }
    }

    private void filterUsers(String query) {
        filteredList.clear();
        if (query == null || query.isEmpty()) {
            filteredList.addAll(userList);
        } else {
            String q = query.toLowerCase();
            for (UserData u : userList) {
                if ((u.displayName != null && u.displayName.toLowerCase().contains(q)) ||
                    (u.username != null && u.username.toLowerCase().contains(q)) ||
                    (u.role != null && u.role.toLowerCase().contains(q))) {
                    filteredList.add(u);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateStats() {
        int total = userList.size();
        int active = 0, online = 0;
        for (UserData u : userList) {
            if ("active".equals(u.status)) active++;
            if (u.isOnline) online++;
        }
        if (tvTotalUsers != null) tvTotalUsers.setText(String.valueOf(total));
        if (tvActiveUsers != null) tvActiveUsers.setText(String.valueOf(active));
        if (tvOnlineNow != null) tvOnlineNow.setText(String.valueOf(online));
    }

    // ==================== User Dialog & Granular Picker ====================

    private void showUserDialog(@Nullable UserData existingUser) {
        if (!isAdded() || getContext() == null) return;

        boolean isEdit = existingUser != null;
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create();

        EditText etDisplayName = dialogView.findViewById(R.id.etDisplayName);
        EditText etUsername = dialogView.findViewById(R.id.etUsername);
        EditText etPassword = dialogView.findViewById(R.id.etPassword);
        EditText etEmployeeId = dialogView.findViewById(R.id.etEmployeeId);
        EditText etBranch = dialogView.findViewById(R.id.etBranch);
        EditText etRole = dialogView.findViewById(R.id.etRole);
        Button btnSaveUser = dialogView.findViewById(R.id.btnSaveUser);

        final Set<String> selectedRoles = new HashSet<>();
        final Set<String> selectedPermissions = new HashSet<>();

        if (isEdit && existingUser != null) {
            selectedRoles.addAll(existingUser.roles);
            selectedPermissions.addAll(existingUser.granularPermissions);
            if (etDisplayName != null) etDisplayName.setText(existingUser.displayName);
            if (etUsername != null) { etUsername.setText(existingUser.username); etUsername.setEnabled(false); }
        }

        updateRoleDisplay(etRole, selectedRoles);

        if (etRole != null) {
            etRole.setFocusable(false);
            etRole.setOnClickListener(v -> showGranularRoleAndPermissionsPicker(selectedRoles, selectedPermissions, () -> updateRoleDisplay(etRole, selectedRoles)));
        }

        if (btnSaveUser != null) {
            btnSaveUser.setOnClickListener(v -> {
                String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                
                if (displayName.isEmpty() || username.isEmpty() || selectedRoles.isEmpty()) {
                    showToast(isArabic ? "يرجى استكمال البيانات المطلوبة" : "Please complete required fields");
                    return;
                }

                saveUserApi(isEdit, existingUser != null ? existingUser.id : 0, username, displayName,
                        etPassword != null ? etPassword.getText().toString() : "",
                        selectedRoles.iterator().next(), selectedRoles, selectedPermissions, dialog);
            });
        }

        dialog.show();
    }

    /**
     * ✅ نافذة اختيار الدور مع تحديد الصلاحيات الجزئية (Granular)
     */
    private void showGranularRoleAndPermissionsPicker(Set<String> selectedRoles, Set<String> selectedPermissions, Runnable onComplete) {
        String[] allowedRoles = getAllowedRoles();
        String[] roleNames = new String[allowedRoles.length];
        for (int i = 0; i < allowedRoles.length; i++) {
            roleNames[i] = getRoleDisplayName(allowedRoles[i], isArabic);
        }

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "اختر الدور الوظيفي الرئيسي" : "Select Primary Role")
            .setItems(roleNames, (dialog, which) -> {
                selectedRoles.clear();
                selectedRoles.add(allowedRoles[which]);

                // الانتقال لتحديد الصلاحيات الجزئية للقسم
                showDetailedPermissionsDialog(selectedPermissions, onComplete);
            })
            .show();
    }

    private void showDetailedPermissionsDialog(Set<String> selectedPermissions, Runnable onComplete) {
        String[] modules = GranularPermissions.MODULES_AR;

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "تخصيص الصلاحيات الفرعية" : "Customize Sub-Permissions")
            .setItems(modules, (dialog, moduleIndex) -> {
                String[] keys = GranularPermissions.PERMISSION_KEYS[moduleIndex];
                String[] labels = GranularPermissions.PERMISSION_LABELS_AR[moduleIndex];
                boolean[] checked = new boolean[keys.length];

                for (int i = 0; i < keys.length; i++) {
                    checked[i] = selectedPermissions.contains(keys[i]);
                }

                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(modules[moduleIndex])
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                        if (isChecked) selectedPermissions.add(keys[which]);
                        else selectedPermissions.remove(keys[which]);
                    })
                    .setPositiveButton(isArabic ? "حفظ الصلاحيات" : "Save Permissions", (d, w) -> {
                        if (onComplete != null) onComplete.run();
                    })
                    .show();
            })
            .setPositiveButton(isArabic ? "إتمام" : "Done", (d, w) -> {
                if (onComplete != null) onComplete.run();
            })
            .show();
    }

    private void saveUserApi(boolean isEdit, int userId, String username, String displayName,
                             String password, String primaryRole, Set<String> roles,
                             Set<String> permissions, AlertDialog dialog) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("username", username);
                payload.put("display_name", displayName);
                payload.put("role", primaryRole);
                if (!password.isEmpty()) payload.put("password", password);

                JSONArray rolesArr = new JSONArray();
                for (String r : roles) rolesArr.put(r);
                payload.put("roles", rolesArr);

                JSONArray permsArr = new JSONArray();
                for (String p : permissions) permsArr.put(p);
                payload.put("granular_permissions", permsArr);

                if (isEdit) payload.put("user_id", userId);

                MiraApiClient.ApiResponse response = isEdit ? apiClient.updateUser(payload) : apiClient.createUser(payload);

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        showToast(isArabic ? "✅ تم الحفظ بنجاح" : "✅ Saved successfully");
                        dialog.dismiss();
                        loadUsers();
                    } else {
                        showToast(isArabic ? "❌ فشل الحفظ" : "❌ Save failed");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> showToast("Error: " + e.getMessage()));
            }
        });
    }

    private void updateRoleDisplay(EditText etRole, Set<String> selectedRoles) {
        if (etRole == null) return;
        if (selectedRoles.isEmpty()) { etRole.setText(""); return; }
        StringBuilder sb = new StringBuilder();
        for (String role : selectedRoles) {
            sb.append(getRoleDisplayName(role, isArabic)).append(" ");
        }
        etRole.setText(sb.toString().trim());
    }

    private void showToast(String message) {
        if (getContext() != null && isAdded()) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Data Classes ====================

    static class UserData {
        int id, branchId;
        String username, displayName, role, employeeId, status, branchName;
        boolean isOnline;
        List<String> roles = new ArrayList<>();
        Set<String> granularPermissions = new HashSet<>();
    }

    static class BranchItem {
        int id;
        String name, code;
        BranchItem() {}
        BranchItem(int id, String name, String code) {
            this.id = id; this.name = name; this.code = code;
        }
    }

    // ==================== Adapter الموحد بصرياً ====================

    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        final List<UserData> list;
        final boolean isArabic;

        UsersAdapter(List<UserData> list, boolean isArabic) {
            this.list = list;
            this.isArabic = isArabic;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_row, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserData u = list.get(pos);

            if (h.tvDisplayName != null) h.tvDisplayName.setText(u.displayName);
            if (h.tvUsername != null) h.tvUsername.setText("@" + u.username);
            if (h.tvRole != null) h.tvRole.setText(getRoleDisplayName(u.role, isArabic));

            // جلب الألوان ديناميكياً من الثيم لضمان التوافق مع Dark/Light Mode
            Context ctx = h.itemView.getContext();
            TypedValue typedValueVal = new TypedValue();
            
            ctx.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValueVal, true);
            int colorPrimary = typedValueVal.data;

            ctx.getTheme().resolveAttribute(com.google.android.material.R.attr.colorError, typedValueVal, true);
            int colorError = typedValueVal.data;

            if ("active".equals(u.status)) {
                if (h.tvStatus != null) {
                    h.tvStatus.setText(u.isOnline ? (isArabic ? "🟢 متصل" : "🟢 Online") : (isArabic ? "🟢 نشط" : "🟢 Active"));
                    h.tvStatus.setTextColor(colorPrimary);
                }
            } else {
                if (h.tvStatus != null) {
                    h.tvStatus.setText(isArabic ? "🔴 موقوف" : "🔴 Suspended");
                    h.tvStatus.setTextColor(colorError);
                }
            }

            if (h.btnEdit != null) {
                h.btnEdit.setVisibility(canEditUser(u.role) ? View.VISIBLE : View.GONE);
                h.btnEdit.setOnClickListener(v -> showUserDialog(u));
            }
        }

        @Override
        public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDisplayName, tvUsername, tvRole, tvStatus;
            ImageButton btnEdit, btnDelete;

            VH(View v) {
                super(v);
                tvDisplayName = v.findViewById(R.id.tvRowDisplayName);
                tvUsername = v.findViewById(R.id.tvRowUsername);
                tvRole = v.findViewById(R.id.tvRowRole);
                tvStatus = v.findViewById(R.id.tvRowStatus);
                btnEdit = v.findViewById(R.id.btnRowEdit);
                btnDelete = v.findViewById(R.id.btnRowDelete);
            }
        }
    }
}
