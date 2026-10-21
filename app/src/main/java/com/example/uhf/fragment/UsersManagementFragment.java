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

    /** ✅ Executor مشترك — يُغلق في onDestroyView */
    private ExecutorService ioExecutor;

    // ==================== Data ====================
    private UsersAdapter adapter;
    private final List<UserData> userList     = new ArrayList<>();
    private final List<UserData> filteredList = new ArrayList<>();
    private boolean isArabic;

    private SecureSessionManager.Role currentUserRole;

    // ==================== Constants ====================
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

            initializeViews(view);
            applyLanguage();
            loadBranchesCache();
            setupRecyclerView();
            setupButtons();
            setupSearch();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
            showToast("Error initializing: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadUsers();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (ioExecutor != null && !ioExecutor.isShutdown()) {
            ioExecutor.shutdown();
            ioExecutor = null;
        }

        rvUsersList        = null;
        etSearchUser       = null;
        tilSearchUser      = null;
        tvScreenTitle      = null;
        tvTotalUsers       = null;
        tvActiveUsers      = null;
        tvOnlineNow        = null;
        tvSuspendedUsers   = null;
        tvEmptyState       = null;
        emptyStateLayout   = null;
        progressLoading    = null;
        btnAddUser         = null;
        btnRefreshUsers    = null;
        btnExport          = null;
        swipeRefreshLayout = null;
        adapter            = null;
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
                    if (canManageUsers()) {
                        showUserDialog(null);
                    } else {
                        showToast(isArabic
                                ? "❌ ليس لديك صلاحية لإضافة مستخدمين"
                                : "❌ You don't have permission to add users");
                    }
                } catch (Exception e) {
                    showToast("Error: " + e.getMessage());
                }
            });
        }

        if (btnRefreshUsers != null) {
            btnRefreshUsers.setOnClickListener(v -> loadUsers());
        }

        if (btnExport != null) {
            btnExport.setOnClickListener(v -> showToast(isArabic
                    ? "🚧 تصدير المستخدمين قيد التطوير"
                    : "🚧 Users export — coming soon"));
        }

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::loadUsers);
            swipeRefreshLayout.setColorSchemeResources(
                    androidx.appcompat.R.color.abc_color_highlight_material);
        }
    }

    private void setupSearch() {
        if (etSearchUser == null) return;
        etSearchUser.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                filterUsers(s.toString());
                updateEmptyState();
            }
        });
    }

    private void applyLanguage() {
        if (btnAddUser  != null) btnAddUser.setText(isArabic ? "+ إضافة مستخدم" : "+ Add User");
        if (btnRefreshUsers != null) btnRefreshUsers.setContentDescription(
                isArabic ? "تحديث" : "Refresh");
        if (btnExport   != null) btnExport.setContentDescription(
                isArabic ? "تصدير" : "Export");

        if (tilSearchUser != null) {
            tilSearchUser.setHint(isArabic ? "بحث عن مستخدم..." : "Search users...");
        } else if (etSearchUser != null) {
            etSearchUser.setHint(isArabic ? "بحث عن مستخدم..." : "Search users...");
        }

        if (tvScreenTitle != null) {
            tvScreenTitle.setText(isArabic
                    ? "👥 إدارة المستخدمين والصلاحيات"
                    : "👥 Users & Permissions");
        }

        updateEmptyState();
    }

    // ═══════════════════════════════════════════════════════
    //  Permission Logic
    // ═══════════════════════════════════════════════════════

    private boolean canManageUsers() {
        return currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN
            || currentUserRole == SecureSessionManager.Role.ORG_ADMIN
            || currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER;
    }

    private boolean canDeleteUser(String targetRole) {
        if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN) return true;

        if (currentUserRole == SecureSessionManager.Role.ORG_ADMIN
                && !"SYSTEM_ADMIN".equals(targetRole)) {
            return true;
        }

        if (currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER) {
            String[] allowed = {"INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY"};
            for (String a : allowed) {
                if (a.equals(targetRole)) return true;
            }
        }
        return false;
    }

    private boolean canEditUser(String targetRole) {
        return canDeleteUser(targetRole);
    }

    private String[] getAllowedRoles() {
        if (currentUserRole == SecureSessionManager.Role.SYSTEM_ADMIN) {
            return ROLES;
        }
        if (currentUserRole == SecureSessionManager.Role.ORG_ADMIN) {
            return new String[]{"ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR",
                    "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"};
        }
        if (currentUserRole == SecureSessionManager.Role.BRANCH_MANAGER) {
            return new String[]{"INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY"};
        }
        return new String[]{};
    }

    private boolean isRoleAllowed(String role) {
        if (role == null) return false;
        for (String allowed : getAllowedRoles()) {
            if (allowed.equals(role)) return true;
        }
        return false;
    }

    /** ✅ Null-safe، ويعيد أيقونة + اسم الدور أو النص الخام */
    private String getRoleDisplayName(String role, boolean arabic) {
        if (role == null) return "";
        for (int i = 0; i < ROLES.length; i++) {
            if (ROLES[i].equals(role)) {
                return ROLE_ICONS[i] + " " + (arabic ? ROLES_AR[i] : ROLES_EN[i]);
            }
        }
        return role;
    }

    // ═══════════════════════════════════════════════════════
    //  Load Branches  (✅ تحديث آمن على UI thread)
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
                                item.id      = b.optInt("id");
                                item.name    = b.optString("branch_name", "");
                                item.code    = b.optString("branch_code", "");
                                item.address = b.optString("address", "");
                                newBranches.add(item);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "loadBranchesCache error: " + e.getMessage());
                }

                // fallback
                if (newBranches.isEmpty()) {
                    newBranches.add(new BranchItem(
                            1,
                            isArabic ? "الفرع الرئيسي" : "Main Branch",
                            "MAIN", ""));
                }

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    branchesList.clear();
                    branchesList.addAll(newBranches);
                });
            });
        } catch (RejectedExecutionException ignored) {
            Log.w(TAG, "loadBranchesCache: executor rejected");
        }
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
                                ? (isArabic ? "خطأ في الاتصال: " + fError
                                            : "Connection error: " + fError)
                                : (fResponse != null && fResponse.error != null
                                        ? fResponse.error
                                        : (isArabic ? "فشل تحميل المستخدمين"
                                                    : "Failed to load users"));
                        showToast(msg);
                        updateEmptyState();
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            Log.w(TAG, "loadUsers: executor rejected");
        }
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
                user.id          = u.optInt("id");
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
                        if (parts.length > 1) {
                            usedEmpNumbers.add(Integer.parseInt(parts[parts.length - 1]));
                        }
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
            for (int num : usedEmpNumbers) {
                if (num > maxNum) maxNum = num;
            }
            employeeCounter.set(Math.max(maxNum + 1, userList.size() + 1));

            filterUsers(etSearchUser != null ? etSearchUser.getText().toString() : "");
            updateStats();

        } catch (Exception e) {
            Log.e(TAG, "parseUsers error", e);
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
    }

    private void updateEmptyState() {
        if (emptyStateLayout == null) return;

        boolean isEmpty = filteredList.isEmpty();
        emptyStateLayout.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (rvUsersList != null) {
            rvUsersList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }

        if (isEmpty && tvEmptyState != null) {
            String query = etSearchUser != null
                    ? etSearchUser.getText().toString().trim() : "";
            tvEmptyState.setText(!query.isEmpty()
                    ? (isArabic ? "لا نتائج لـ: " + query : "No results for: " + query)
                    : (isArabic ? "لا يوجد مستخدمين" : "No users yet"));
        }
    }

    private void showToast(String message) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Toast error: " + e.getMessage());
        }
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

        for (int i = 4; i < length; i++) {
            sb.append(ALL.charAt(random.nextInt(ALL.length())));
        }

        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }

    private String suggestUsername(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "";
        String cleaned = displayName.trim()
                .replaceAll("\\s+", ".")
                .toLowerCase()
                .replaceAll("[^a-z0-9.]", "");
        if (cleaned.isEmpty()) return "user" + (System.currentTimeMillis() % 10000);
        return cleaned;
    }

    private void copyToClipboard(String text) {
        if (getContext() == null || text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("password", text));
        showToast(isArabic ? "📋 تم نسخ كلمة المرور!" : "📋 Password copied!");
    }

    private String generateEmployeeId(String branchCode) {
        String prefix = (branchCode != null && branchCode.length() >= 2)
                ? branchCode.substring(0, 2).toUpperCase()
                : "HQ";
        return prefix + "-" + String.format(Locale.US, "%03d",
                employeeCounter.getAndIncrement());
    }

    private boolean isPasswordStrong(String password) {
        if (password == null || password.length() < 8) return false;
        if (!password.matches(".*[A-Z].*")) return false;
        if (!password.matches(".*[0-9].*")) return false;
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*")) return false;
        return true;
    }

    // ═══════════════════════════════════════════════════════
    //  User Dialog  (✅ القوائم تعمل الآن)
    // ═══════════════════════════════════════════════════════

    private void showUserDialog(@Nullable UserData existingUser) {
        try {
            if (!isAdded() || getContext() == null) return;

            boolean isEdit = existingUser != null;

            View dialogView = LayoutInflater.from(getContext())
                    .inflate(R.layout.dialog_user_edit, null);

            Context themedContext = new ContextThemeWrapper(
                    requireContext(), R.style.MiraDialogTheme);

            AlertDialog dialog = new MaterialAlertDialogBuilder(themedContext)
                    .setView(dialogView)
                    .setCancelable(true)
                    .create();

            if (dialog.getWindow() != null) {
                boolean isDarkMode = (getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;

                dialog.getWindow().setBackgroundDrawableResource(
                        isDarkMode ? R.drawable.bg_mira_dialog_dark
                                   : R.drawable.bg_mira_dialog);
            }

            TextView tvTitle       = dialogView.findViewById(R.id.tvDialogTitle);
            TextView tvSubtitle    = dialogView.findViewById(R.id.tvDialogSubtitle);
            EditText etDisplayName = dialogView.findViewById(R.id.etDisplayName);
            EditText etUsername    = dialogView.findViewById(R.id.etUsername);
            EditText etPassword    = dialogView.findViewById(R.id.etPassword);
            EditText etEmployeeId  = dialogView.findViewById(R.id.etEmployeeId);
            EditText etBranch      = dialogView.findViewById(R.id.etBranch);
            EditText etRole        = dialogView.findViewById(R.id.etRole);

            TextInputLayout tilBranch = dialogView.findViewById(R.id.tilBranch);
            TextInputLayout tilRole   = dialogView.findViewById(R.id.tilRole);

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

            // ✅ LinkedHashSet للحفاظ على الترتيب
            final Set<String> selectedRoles = new LinkedHashSet<>();
            if (isEdit && existingUser.roles != null) {
                selectedRoles.addAll(existingUser.roles);
            }

            if (btnCloseDialog != null) btnCloseDialog.setOnClickListener(v -> dialog.dismiss());

            if (tvTitle != null) {
                tvTitle.setText(isEdit
                        ? (isArabic ? "تعديل مستخدم" : "Edit User")
                        : (isArabic ? "إضافة مستخدم جديد" : "Add New User"));
            }
            if (tvSubtitle != null) {
                tvSubtitle.setText(isEdit
                        ? (isArabic ? "تعديل بيانات المستخدم الحالي" : "Edit current user data")
                        : (isArabic ? "أدخل بيانات المستخدم الجديد" : "Enter new user data"));
            }

            // ═══════════════════════════════════════════════════
            //  ✅ [FIX] الفروع — المستمع على EditText وليس TextInputLayout
            // ═══════════════════════════════════════════════════
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
                if (initialBranchIndex < 0) initialBranchIndex = 0;
            }

            if (initialBranchIndex < 0) initialBranchIndex = 0;

            // ✅ تتبع الاختيار الحالي كمتغيرات (بدل تحليل النص)
            final int[]    selectedBranchId   = { activeBranchId };
            final String[] selectedBranchName = {
                    branchNames.get(Math.min(initialBranchIndex, branchNames.size() - 1))
                              .replaceAll("\\s*\\(.*\\)\\s*$", "").trim()
            };
            final int[]    selectedBranchIndex = { initialBranchIndex };

            if (etBranch != null) {
                etBranch.setText(branchNames.get(initialBranchIndex));

                // ✅ منع الكتابة ولوحة المفاتيح، مع السماح بالنقر
                etBranch.setFocusable(false);
                etBranch.setFocusableInTouchMode(false);
                etBranch.setClickable(true);
                etBranch.setCursorVisible(false);
                etBranch.setLongClickable(false);
                etBranch.setInputType(InputType.TYPE_NULL);

                etBranch.setOnClickListener(v -> {
                    if (!isAdded() || getContext() == null) return;

                    if (branchNames.isEmpty()) {
                        showToast(isArabic
                                ? "لا توجد فروع متاحة"
                                : "No branches available");
                        return;
                    }

                    new MaterialAlertDialogBuilder(
                            new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
                            .setTitle(isArabic ? "اختر الفرع" : "Select Branch")
                            .setItems(branchNames.toArray(new String[0]),
                                    (d, which) -> {
                                        etBranch.setText(branchNames.get(which));
                                        etBranch.setError(null);
                                        selectedBranchIndex[0] = which;

                                        // ✅ تحديث معرّف/اسم الفرع كمتغيرات
                                        if (which < branchesList.size()) {
                                            BranchItem bi = branchesList.get(which);
                                            selectedBranchId[0]   = bi.id;
                                            selectedBranchName[0] = bi.name;
                                        } else {
                                            // fallback
                                            selectedBranchId[0]   = activeBranchId;
                                            selectedBranchName[0] = branchNames.get(which)
                                                    .replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
                                        }
                                    })
                            .show();
                });
            }

            // ═══════════════════════════════════════════════════
            //  ✅ [FIX] الصلاحيات — المستمع على EditText وليس TextInputLayout
            // ═══════════════════════════════════════════════════
            updateRoleDisplay(etRole, selectedRoles);

            if (etRole != null) {
                etRole.setHint(isArabic
                        ? "اضغط لاختيار الصلاحيات (متعدد)"
                        : "Tap to select permissions (Multiple)");

                etRole.setFocusable(false);
                etRole.setFocusableInTouchMode(false);
                etRole.setClickable(true);
                etRole.setCursorVisible(false);
                etRole.setLongClickable(false);
                etRole.setInputType(InputType.TYPE_NULL);

                etRole.setOnClickListener(v -> {
                    if (!isAdded() || getContext() == null) return;

                    showMultiRolePicker(selectedRoles,
                            roles -> updateRoleDisplay(etRole, roles));
                });
            }

            // ─── رقم الموظف ───
            String branchCode = branchesManager.getActiveBranchCode();
            if (etEmployeeId != null && !isEdit) {
                etEmployeeId.setText(generateEmployeeId(branchCode));
                etEmployeeId.setEnabled(false);
            }

            // ─── أزرار كلمة المرور ───
            if (btnGeneratePassword != null) {
                btnGeneratePassword.setOnClickListener(v -> {
                    String pwd = generateStrongPassword(12);
                    if (etPassword != null) etPassword.setText(pwd);
                    showToast(isArabic
                            ? "🔐 تم توليد كلمة مرور قوية!"
                            : "🔐 Strong password generated!");
                });
            }

            if (btnCopyPassword != null) {
                btnCopyPassword.setOnClickListener(v -> {
                    String pwd = etPassword != null
                            ? etPassword.getText().toString() : "";
                    if (!pwd.isEmpty()) copyToClipboard(pwd);
                    else showToast(isArabic
                            ? "لا توجد كلمة مرور لنسخها"
                            : "No password to copy");
                });
            }

            if (btnSuggestUsername != null && etDisplayName != null && etUsername != null) {
                btnSuggestUsername.setOnClickListener(v -> {
                    String name = etDisplayName.getText().toString().trim();
                    if (!name.isEmpty()) etUsername.setText(suggestUsername(name));
                    else showToast(isArabic
                            ? "أدخل الاسم الظاهر أولاً"
                            : "Enter display name first");
                });
            }

            // ─── تعبئة بيانات التعديل ───
            if (isEdit) {
                if (etDisplayName != null) etDisplayName.setText(existingUser.displayName);

                if (etUsername != null) {
                    etUsername.setText(existingUser.username);
                    etUsername.setEnabled(false);
                }

                if (etPassword != null) {
                    etPassword.setHint(isArabic
                            ? "اتركه فارغاً لعدم التغيير"
                            : "Leave empty to keep unchanged");
                }

                if (etEmployeeId != null) etEmployeeId.setText(existingUser.employeeId);

                // ✅ ضبط فرع المستخدم الحالي بدقة
                if (existingUser.branchId > 0) {
                    for (int i = 0; i < branchesList.size(); i++) {
                        if (branchesList.get(i).id == existingUser.branchId) {
                            if (i < branchNames.size() && etBranch != null) {
                                etBranch.setText(branchNames.get(i));
                            }
                            selectedBranchId[0]   = branchesList.get(i).id;
                            selectedBranchName[0] = branchesList.get(i).name;
                            selectedBranchIndex[0] = i;
                            break;
                        }
                    }
                } else if (existingUser.branchName != null
                        && !existingUser.branchName.isEmpty()
                        && etBranch != null) {
                    etBranch.setText(existingUser.branchName);
                    selectedBranchName[0] = existingUser.branchName;
                }

                updateRoleDisplay(etRole, selectedRoles);

                if (layoutStep1    != null) layoutStep1.setVisibility(View.VISIBLE);
                if (layoutStep2    != null) layoutStep2.setVisibility(View.VISIBLE);
                if (stepsIndicator != null) stepsIndicator.setVisibility(View.GONE);
                if (btnNextStep    != null) btnNextStep.setVisibility(View.GONE);
                if (btnPrevStep    != null) btnPrevStep.setVisibility(View.GONE);
                if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
                if (btnCancel      != null) {
                    btnCancel.setVisibility(View.VISIBLE);
                    btnCancel.setText(isArabic ? "إلغاء" : "Cancel");
                }
                if (btnSaveUser    != null) btnSaveUser.setVisibility(View.VISIBLE);

            } else {
                if (layoutStep1    != null) layoutStep1.setVisibility(View.VISIBLE);
                if (layoutStep2    != null) layoutStep2.setVisibility(View.GONE);
                if (stepsIndicator != null) stepsIndicator.setVisibility(View.VISIBLE);
                if (btnNextStep    != null) btnNextStep.setVisibility(View.VISIBLE);
                if (btnSaveUser    != null) btnSaveUser.setVisibility(View.GONE);
                if (btnPrevStep    != null) btnPrevStep.setVisibility(View.GONE);
                if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
                if (btnCancel      != null) {
                    btnCancel.setVisibility(View.VISIBLE);
                    btnCancel.setText(isArabic ? "إلغاء" : "Cancel");
                }
            }

            // ─── زر التالي ───
            if (btnNextStep != null) {
                btnNextStep.setOnClickListener(v -> {
                    String displayName = etDisplayName != null
                            ? etDisplayName.getText().toString().trim() : "";
                    String username = etUsername != null
                            ? etUsername.getText().toString().trim() : "";
                    String password = etPassword != null
                            ? etPassword.getText().toString().trim() : "";

                    if (displayName.isEmpty()) {
                        if (etDisplayName != null)
                            etDisplayName.setError(isArabic ? "مطلوب" : "Required");
                        return;
                    }
                    if (username.isEmpty()) {
                        if (etUsername != null)
                            etUsername.setError(isArabic ? "مطلوب" : "Required");
                        return;
                    }
                    if (!isEdit && !isPasswordStrong(password)) {
                        showToast(isArabic
                                ? "كلمة المرور لا تستوفي المتطلبات الأمنية"
                                : "Password does not meet security requirements");
                        return;
                    }

                    if (layoutStep1 != null) layoutStep1.setVisibility(View.GONE);
                    if (layoutStep2 != null) layoutStep2.setVisibility(View.VISIBLE);
                    if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
                    if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
                    if (btnPrevStep != null) btnPrevStep.setVisibility(View.VISIBLE);
                });
            }

            // ─── زر الرجوع ───
            if (btnPrevStep != null) {
                btnPrevStep.setOnClickListener(v -> {
                    if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
                    if (layoutStep2 != null) layoutStep2.setVisibility(View.GONE);
                    if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
                    if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
                    if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
                });
            }

            // ─── زر الإلغاء ───
            if (btnCancel != null) {
                btnCancel.setText(isArabic ? "إلغاء" : "Cancel");
                btnCancel.setVisibility(View.VISIBLE);
                btnCancel.setOnClickListener(v -> dialog.dismiss());
            }

            // ─── زر الحفظ ───
            if (btnSaveUser != null) {
                btnSaveUser.setOnClickListener(v -> {
                    String displayName = etDisplayName != null
                            ? etDisplayName.getText().toString().trim() : "";
                    String username = etUsername != null
                            ? etUsername.getText().toString().trim() : "";
                    String password = etPassword != null
                            ? etPassword.getText().toString().trim() : "";
                    String empId = etEmployeeId != null
                            ? etEmployeeId.getText().toString().trim() : "";

                    if (selectedRoles.isEmpty()) {
                        showToast(isArabic
                                ? "⚠️ يجب اختيار صلاحية واحدة على الأقل"
                                : "⚠️ At least one permission is required");
                        return;
                    }
                    if (displayName.isEmpty()) {
                        showToast(isArabic ? "الاسم مطلوب" : "Name is required");
                        return;
                    }
                    if (username.isEmpty()) {
                        showToast(isArabic ? "اسم المستخدم مطلوب" : "Username is required");
                        return;
                    }

                    // ✅ التحقق من صلاحية الأدوار
                    for (String role : selectedRoles) {
                        if (!isRoleAllowed(role)) {
                            showToast(isArabic
                                    ? "❌ ليس لديك صلاحية لإنشاء دور: " + role
                                    : "❌ You don't have permission to create role: " + role);
                            return;
                        }
                    }

                    // ✅ الدور الأساسي بشكل حتمي: الاحتفاظ بدور المستخدم الحالي إن أمكن، وإلا أول دور في ROLES
                    String primaryRole = null;
                    if (isEdit && existingUser.role != null
                            && selectedRoles.contains(existingUser.role)) {
                        primaryRole = existingUser.role;
                    } else {
                        for (String r : ROLES) {
                            if (selectedRoles.contains(r)) {
                                primaryRole = r;
                                break;
                            }
                        }
                        if (primaryRole == null) {
                            primaryRole = selectedRoles.iterator().next();
                        }
                    }

                    // ✅ الفرع من المتغيرات المتابعة (لا تحليل نصي)
                    final int    fBranchId   = selectedBranchId[0] > 0
                            ? selectedBranchId[0]
                            : branchesManager.getActiveBranchId();
                    final String fBranchName = selectedBranchName[0] != null
                            && !selectedBranchName[0].isEmpty()
                            ? selectedBranchName[0]
                            : (isArabic ? "الفرع الرئيسي" : "Main Branch");

                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);
                    if (tvLoadingText  != null) tvLoadingText.setText(isArabic
                            ? "جاري الحفظ..." : "Saving...");
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
                                if (isEdit) payload.put("user_id", existingUser.id);

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

                                if (loadingOverlay != null)
                                    loadingOverlay.setVisibility(View.GONE);

                                if (fResponse != null && fResponse.isSuccess) {
                                    showToast(isEdit
                                            ? (isArabic
                                                    ? "✅ تم تعديل المستخدم بنجاح"
                                                    : "✅ User updated successfully")
                                            : (isArabic
                                                    ? "✅ تمت إضافة المستخدم بنجاح"
                                                    : "✅ User added successfully"));
                                    dialog.dismiss();
                                    loadUsers();
                                } else {
                                    String msg = fError != null
                                            ? (isArabic
                                                    ? "❌ خطأ: " + fError
                                                    : "❌ Error: " + fError)
                                            : (fResponse != null && fResponse.error != null
                                                    ? "❌ " + fResponse.error
                                                    : (isArabic
                                                            ? "❌ فشل العملية"
                                                            : "❌ Operation failed"));
                                    showToast(msg);
                                    btnSaveUser.setEnabled(true);
                                }
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
    //  Helpers — Role Display & Picker
    // ═══════════════════════════════════════════════════════

    private void updateRoleDisplay(EditText etRole, Set<String> selectedRoles) {
        if (etRole == null) return;

        if (selectedRoles == null || selectedRoles.isEmpty()) {
            etRole.setText("");
            return;
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String role : selectedRoles) {
            if (count > 0) sb.append(" + ");
            sb.append(getRoleDisplayName(role, isArabic));
            count++;
            if (count >= 2) break;
        }

        if (selectedRoles.size() > 2) {
            sb.append(" +").append(selectedRoles.size() - 2)
              .append(isArabic ? " أخرى" : " more");
        }

        etRole.setText(sb.toString());
    }

    /** ✅ نسخة مؤقتة: لا تُعدّل selectedRoles حتى يضغط المستخدم "تأكيد" */
    private void showMultiRolePicker(Set<String> selectedRoles, RolePickerCallback callback) {
        try {
            if (!isAdded() || getContext() == null) return;

            String[] allowedRoles = getAllowedRoles();
            if (allowedRoles == null || allowedRoles.length == 0) {
                showToast(isArabic
                        ? "لا توجد صلاحيات متاحة لدورك"
                        : "No permissions available for your role");
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
                    .setTitle(isArabic
                            ? "اختر الصلاحيات (يمكن اختيار أكثر من واحدة)"
                            : "Select Permissions (Multiple allowed)")
                    .setMultiChoiceItems(displayNames, checked,
                            (d, which, isChecked) -> {
                                if (isChecked) tempSelection.add(allowedRoles[which]);
                                else           tempSelection.remove(allowedRoles[which]);
                            })
                    .setPositiveButton(isArabic ? "تأكيد" : "OK",
                            (d, which) -> {
                                // ✅ ننسخ الآن فقط
                                selectedRoles.clear();
                                selectedRoles.addAll(tempSelection);
                                if (callback != null) callback.onRolesSelected(selectedRoles);
                            })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "showMultiRolePicker error: " + e.getMessage());
        }
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

    interface RolePickerCallback {
        void onRolesSelected(Set<String> roles);
    }

    // ═══════════════════════════════════════════════════════
    //  Delete User
    // ═══════════════════════════════════════════════════════

    private void deleteUser(int userId, String name, String role) {
        try {
            if (!isAdded() || getContext() == null) return;

            if (!canDeleteUser(role)) {
                showToast(isArabic
                        ? "❌ ليس لديك صلاحية لحذف هذا المستخدم"
                        : "❌ You don't have permission to delete this user");
                return;
            }

            new MaterialAlertDialogBuilder(
                    new ContextThemeWrapper(requireContext(), R.style.MiraDialogTheme))
                    .setTitle(isArabic ? "حذف مستخدم" : "Delete User")
                    .setMessage((isArabic
                            ? "هل أنت متأكد من حذف \""
                            : "Are you sure you want to delete \"")
                            + name + "\"؟")
                    .setPositiveButton(isArabic ? "حذف" : "Delete", (d, w) -> {
                        if (ioExecutor == null || ioExecutor.isShutdown()) {
                            showToast(isArabic ? "❌ الخدمة غير متاحة"
                                                : "❌ Service unavailable");
                            return;
                        }
                        try {
                            ioExecutor.execute(() -> {
                                MiraApiClient.ApiResponse response = null;
                                String errorMsg = null;
                                try {
                                    response = apiClient.deleteUser(userId);
                                } catch (Exception e) {
                                    errorMsg = e.getMessage();
                                }
                                final MiraApiClient.ApiResponse fResponse = response;
                                final String fError = errorMsg;

                                mainHandler.post(() -> {
                                    if (!isAdded()) return;
                                    if (fResponse != null && fResponse.isSuccess) {
                                        showToast(isArabic
                                                ? "✅ تم حذف المستخدم بنجاح"
                                                : "✅ User deleted successfully");
                                        loadUsers();
                                    } else {
                                        String msg = fError != null
                                                ? "Error: " + fError
                                                : (isArabic
                                                        ? "❌ فشل حذف المستخدم"
                                                        : "❌ Failed to delete user");
                                        showToast(msg);
                                    }
                                });
                            });
                        } catch (RejectedExecutionException ignored) {
                            showToast(isArabic ? "❌ الخدمة غير متاحة"
                                                : "❌ Service unavailable");
                        }
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();

        } catch (Exception e) {
            showToast("Error: " + e.getMessage());
        }
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
        int id;
        String name;
        String code;
        String address;

        BranchItem() {}

        BranchItem(int id, String name, String code, String address) {
            this.id      = id;
            this.name    = name;
            this.code    = code;
            this.address = address;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Adapter
    // ═══════════════════════════════════════════════════════

    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        final List<UserData> list;
        final boolean isArabic;

        UsersAdapter(List<UserData> list, boolean isArabic) {
            this.list     = list;
            this.isArabic = isArabic;
        }

        @NonNull
        @Override
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
                if (h.tvUsername    != null) h.tvUsername.setText("@" + u.username);

                if (h.tvRole != null) {
                    if (u.roles != null && !u.roles.isEmpty()) {
                        // ✅ LinkedHashSet يحافظ على الترتيب
                        h.tvRole.setText(formatRolesDisplay(new LinkedHashSet<>(u.roles)));
                    } else {
                        h.tvRole.setText(getRoleDisplayName(u.role, isArabic));
                    }
                }

                if (h.tvEmployeeId != null) {
                    if (u.employeeId != null && !u.employeeId.isEmpty()) {
                        h.tvEmployeeId.setText("🪪 " + u.employeeId);
                        h.tvEmployeeId.setVisibility(View.VISIBLE);
                    } else {
                        h.tvEmployeeId.setVisibility(View.GONE);
                    }
                }

                if (h.tvBranchName != null) {
                    if (u.branchName != null && !u.branchName.isEmpty()) {
                        h.tvBranchName.setText("🏢 " + u.branchName);
                        h.tvBranchName.setVisibility(View.VISIBLE);
                    } else {
                        h.tvBranchName.setVisibility(View.GONE);
                    }
                }

                if ("active".equals(u.status)) {
                    if (h.vStatusDot != null)
                        h.vStatusDot.setBackgroundResource(R.drawable.circle_green);
                    if (h.tvStatus != null) {
                        h.tvStatus.setText(u.isOnline
                                ? (isArabic ? "🟢 متصل" : "🟢 Online")
                                : (isArabic ? "🟢 نشط" : "🟢 Active"));
                        h.tvStatus.setTextColor(u.isOnline ? 0xFF3B82F6 : 0xFF22C55E);
                    }
                } else {
                    if (h.vStatusDot != null)
                        h.vStatusDot.setBackgroundResource(R.drawable.circle_red);
                    if (h.tvStatus != null) {
                        h.tvStatus.setText(isArabic ? "🔴 موقوف" : "🔴 Suspended");
                        h.tvStatus.setTextColor(0xFFEF4444);
                    }
                }

                if (h.btnEdit != null) {
                    if (canEditUser(u.role)) {
                        h.btnEdit.setVisibility(View.VISIBLE);
                        h.btnEdit.setOnClickListener(v -> showUserDialog(u));
                    } else {
                        h.btnEdit.setVisibility(View.GONE);
                    }
                }

                if (h.btnDelete != null) {
                    if (canDeleteUser(u.role)) {
                        h.btnDelete.setVisibility(View.VISIBLE);
                        h.btnDelete.setOnClickListener(v ->
                                deleteUser(u.id, u.displayName, u.role));
                    } else {
                        h.btnDelete.setVisibility(View.GONE);
                    }
                }

                if (h.btnPermissions != null) {
                    h.btnPermissions.setVisibility(View.GONE);
                }

            } catch (Exception e) {
                Log.e(TAG, "Adapter bind error: " + e.getMessage());
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

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
