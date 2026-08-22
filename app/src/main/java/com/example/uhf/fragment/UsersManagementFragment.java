package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Users Management Fragment - النسخة المحسنة الكاملة
 * 
 * إدارة المستخدمين والصلاحيات المتعددة مع دعم الفروع
 * 
 * المميزات الجديدة:
 * - نظام صلاحيات متعدد ومفصل
 * - قوالب صلاحيات جاهزة
 * - تصدير البيانات CSV
 * - فلترة متقدمة
 * - سجل النشاطات
 * - تحقق من توفر اسم المستخدم
 * - واجهة محسنة مع SwipeRefresh
 */
public class UsersManagementFragment extends Fragment {

    // ==================== UI Elements ====================
    private RecyclerView rvUsersList;
    private EditText etSearchUser;
    private TextView tvTotalUsers, tvActiveUsers, tvOnlineNow, tvSuspendedUsers;
    private Button btnAddUser, btnRefreshUsers, btnFilter, btnExport;
    private SwipeRefreshLayout swipeRefreshLayout;
    private LinearLayout emptyStateLayout;
    private TextView tvEmptyState;

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
    private SessionManager.Role currentUserRole;
    private int currentFilter = 0; // 0 = All, 1 = Active, 2 = Suspended, 3 = Online

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

    // ==================== Permission Templates ====================
    private static final Map<String, UserPermissions> PERMISSION_TEMPLATES = new HashMap<>();
    static {
        // قالب المدير الكامل
        UserPermissions adminFull = new UserPermissions();
        adminFull.setAllPermissions(true);
        PERMISSION_TEMPLATES.put("ADMIN_FULL", adminFull);

        // قالب المدير
        UserPermissions manager = new UserPermissions();
        manager.setCanViewInventory(true);
        manager.setCanEditInventory(true);
        manager.setCanViewReports(true);
        manager.setCanManageUsers(false);
        manager.setCanViewAuditLogs(true);
        manager.setCanExportData(true);
        manager.setCanApproveTransactions(true);
        manager.setCanAccessSettings(false);
        PERMISSION_TEMPLATES.put("MANAGER", manager);

        // قالب المشغل
        UserPermissions operator = new UserPermissions();
        operator.setCanViewInventory(true);
        operator.setCanEditInventory(false);
        operator.setCanViewReports(false);
        operator.setCanManageUsers(false);
        operator.setCanViewAuditLogs(false);
        operator.setCanExportData(false);
        operator.setCanApproveTransactions(false);
        operator.setCanAccessSettings(false);
        PERMISSION_TEMPLATES.put("OPERATOR", operator);

        // قالب المشاهد
        UserPermissions viewer = new UserPermissions();
        viewer.setCanViewInventory(true);
        viewer.setCanEditInventory(false);
        viewer.setCanViewReports(true);
        viewer.setCanManageUsers(false);
        viewer.setCanViewAuditLogs(false);
        viewer.setCanExportData(false);
        viewer.setCanApproveTransactions(false);
        viewer.setCanAccessSettings(false);
        PERMISSION_TEMPLATES.put("VIEWER", viewer);
    }

    // ==================== Password Generator ====================
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?";
    private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;
    private final SecureRandom random = new SecureRandom();

    // ==================== Counters & Cache ====================
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

        // تهيئة المتغيرات الأساسية
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        apiClient = MiraApiClient.getInstance(requireContext());
        sessionManager = new SessionManager(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        currentUserRole = sessionManager.getUserRole();

        // ربط عناصر الواجهة
        initializeViews(view);
        
        // تطبيق اللغة
        applyLanguage();

        // تحميل الفروع مسبقاً
        loadBranchesCache();

        // إعداد RecyclerView
        setupRecyclerView();

        // إعداد الأزرار
        setupButtons();

        // إعداد البحث
        setupSearch();

        // تحميل البيانات
        loadUsers();
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
        tvSuspendedUsers = view.findViewById(R.id.tvSuspendedUsers);
        btnAddUser = view.findViewById(R.id.btnAddUser);
        btnRefreshUsers = view.findViewById(R.id.btnRefreshUsers);
        btnFilter = view.findViewById(R.id.btnFilter);
        btnExport = view.findViewById(R.id.btnExport);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
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
        
        btnFilter.setOnClickListener(v -> showFilterDialog());
        
        btnExport.setOnClickListener(v -> exportUsersData());

        swipeRefreshLayout.setOnRefreshListener(this::loadUsers);
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        );
    }

    private void setupSearch() {
        etSearchUser.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                filterUsers(s.toString());
            }
        });
    }

    // ==================== Language ====================

    private void applyLanguage() {
        if (btnAddUser != null) btnAddUser.setText(isArabic ? "+ إضافة مستخدم" : "+ Add User");
        if (etSearchUser != null) etSearchUser.setHint(isArabic ? "بحث عن مستخدم..." : "Search users...");
        if (btnRefreshUsers != null) btnRefreshUsers.setText(isArabic ? "تحديث" : "Refresh");
        if (btnFilter != null) btnFilter.setText(isArabic ? "تصفية" : "Filter");
        if (btnExport != null) btnExport.setText(isArabic ? "تصدير" : "Export");
        
        if (tvEmptyState != null) {
            tvEmptyState.setText(isArabic ? "لا يوجد مستخدمين" : "No users found");
        }
    }

    // ==================== Branch & Permission Logic ====================

    private int getBranchIdForNewUser() {
        return branchesManager.getActiveBranchId();
    }

    private boolean canManageUsers() {
        return currentUserRole == SessionManager.Role.SYSTEM_ADMIN
            || currentUserRole == SessionManager.Role.ORG_ADMIN
            || currentUserRole == SessionManager.Role.BRANCH_MANAGER;
    }

    private boolean canDeleteUser(String targetRole) {
        if (currentUserRole == SessionManager.Role.SYSTEM_ADMIN) return true;
        if (currentUserRole == SessionManager.Role.ORG_ADMIN && !"SYSTEM_ADMIN".equals(targetRole))
            return true;
        if (currentUserRole == SessionManager.Role.BRANCH_MANAGER) {
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

    private boolean canManagePermissions() {
        return currentUserRole == SessionManager.Role.SYSTEM_ADMIN
            || currentUserRole == SessionManager.Role.ORG_ADMIN;
    }

    private String[] getAllowedRoles() {
        if (currentUserRole == SessionManager.Role.SYSTEM_ADMIN) {
            return ROLES;
        }
        if (currentUserRole == SessionManager.Role.ORG_ADMIN) {
            return new String[]{"ORG_ADMIN", "BRANCH_MANAGER", "INVENTORY_MGR",
                "POS_OPERATOR", "SECURITY", "GATE_SECURITY", "AUDITOR"};
        }
        if (currentUserRole == SessionManager.Role.BRANCH_MANAGER) {
            return new String[]{"INVENTORY_MGR", "POS_OPERATOR", "SECURITY", "GATE_SECURITY"};
        }
        return new String[]{};
    }

    private boolean isRoleAllowed(String role) {
        String[] allowedRoles = getAllowedRoles();
        for (String allowed : allowedRoles) {
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

    private String extractRoleCode(String displayText) {
        for (String role : ROLES) {
            if (displayText.contains(role)) return role;
        }
        return "INVENTORY_MGR";
    }

    // ==================== Load Branches Cache ====================

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
                                item.address = b.optString("address", "");
                                branchesList.add(item);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (branchesList.isEmpty()) {
                    branchesList.add(new BranchItem(1, "الفرع الرئيسي", "MAIN", ""));
                    branchesList.add(new BranchItem(2, "فرع عدن", "ADN", ""));
                    branchesList.add(new BranchItem(3, "فرع تعز", "TAZ", ""));
                }
            }
        });
    }

    // ==================== Load Users ====================

    private void loadUsers() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(true);
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getUsers();
                mainHandler.post(() -> {
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        parseUsers(response.data);
                    } else {
                        showToast(isArabic ? "فشل تحميل المستخدمين" : "Failed to load users");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                    showToast(isArabic ? "خطأ في الاتصال بالخادم" : "Server connection error");
                });
            }
        });
    }

    private void parseUsers(JSONObject data) {
        userList.clear();
        if (data == null) return;

        JSONArray users = data.optJSONArray("users");
        if (users == null) return;

        int currentBranchId = branchesManager.getActiveBranchId();
        Set<Integer> usedEmpNumbers = new HashSet<>();

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
            user.lastLogin = u.optString("last_login", "");
            user.isOnline = u.optBoolean("is_online", false);
            user.branchName = u.optString("branch_name", "");
            user.branchId = u.optInt("branch_id", 0);
            
            // تحميل الصلاحيات المتعددة
            user.permissions = new UserPermissions();
            JSONObject permissionsObj = u.optJSONObject("permissions");
            if (permissionsObj != null) {
                user.permissions.setCanViewInventory(permissionsObj.optBoolean("can_view_inventory", false));
                user.permissions.setCanEditInventory(permissionsObj.optBoolean("can_edit_inventory", false));
                user.permissions.setCanViewReports(permissionsObj.optBoolean("can_view_reports", false));
                user.permissions.setCanManageUsers(permissionsObj.optBoolean("can_manage_users", false));
                user.permissions.setCanViewAuditLogs(permissionsObj.optBoolean("can_view_audit_logs", false));
                user.permissions.setCanExportData(permissionsObj.optBoolean("can_export_data", false));
                user.permissions.setCanApproveTransactions(permissionsObj.optBoolean("can_approve_transactions", false));
                user.permissions.setCanAccessSettings(permissionsObj.optBoolean("can_access_settings", false));
            }

            // استخراج رقم الموظف
            if (!user.employeeId.isEmpty()) {
                try {
                    String[] parts = user.employeeId.split("-");
                    if (parts.length > 1) {
                        usedEmpNumbers.add(Integer.parseInt(parts[parts.length - 1]));
                    }
                } catch (Exception ignored) {}
            }

            // تصفية حسب الصلاحية
            boolean shouldShow = false;
            if (currentUserRole == SessionManager.Role.SYSTEM_ADMIN
                || currentUserRole == SessionManager.Role.ORG_ADMIN) {
                shouldShow = true;
            } else if (currentUserRole == SessionManager.Role.BRANCH_MANAGER) {
                if (user.branchId == currentBranchId) {
                    shouldShow = true;
                }
            }

            if (shouldShow) {
                userList.add(user);
            }
        }

        // تحديث العداد
        int maxNum = 0;
        for (int num : usedEmpNumbers) {
            if (num > maxNum) maxNum = num;
        }
        employeeCounter.set(Math.max(maxNum + 1, userList.size() + 1));

        // تحديث الواجهة
        applyCurrentFilter();
        updateStats();
        updateEmptyState();
    }

    private void filterUsers(String query) {
        filteredList.clear();
        
        if (query == null || query.isEmpty()) {
            filteredList.addAll(userList);
        } else {
            String q = query.toLowerCase();
            for (UserData u : userList) {
                if ((u.displayName != null && u.displayName.toLowerCase().contains(q))
                    || (u.username != null && u.username.toLowerCase().contains(q))
                    || (u.role != null && u.role.toLowerCase().contains(q))
                    || (u.employeeId != null && u.employeeId.toLowerCase().contains(q))
                    || (u.branchName != null && u.branchName.toLowerCase().contains(q))) {
                    filteredList.add(u);
                }
            }
        }
        
        if (adapter != null) adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void applyCurrentFilter() {
        filteredList.clear();
        
        switch (currentFilter) {
            case 0: // All
                filteredList.addAll(userList);
                break;
            case 1: // Active
                for (UserData u : userList) {
                    if ("active".equals(u.status)) filteredList.add(u);
                }
                break;
            case 2: // Suspended
                for (UserData u : userList) {
                    if (!"active".equals(u.status)) filteredList.add(u);
                }
                break;
            case 3: // Online
                for (UserData u : userList) {
                    if (u.isOnline) filteredList.add(u);
                }
                break;
        }
        
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateStats() {
        int total = userList.size();
        int active = 0;
        int online = 0;
        int suspended = 0;
        
        for (UserData u : userList) {
            if ("active".equals(u.status)) {
                active++;
            } else {
                suspended++;
            }
            if (u.isOnline) online++;
        }
        
        if (tvTotalUsers != null) tvTotalUsers.setText(String.valueOf(total));
        if (tvActiveUsers != null) tvActiveUsers.setText(String.valueOf(active));
        if (tvOnlineNow != null) tvOnlineNow.setText(String.valueOf(online));
        if (tvSuspendedUsers != null) tvSuspendedUsers.setText(String.valueOf(suspended));
    }

    private void updateEmptyState() {
        if (emptyStateLayout != null) {
            if (filteredList.isEmpty()) {
                emptyStateLayout.setVisibility(View.VISIBLE);
                rvUsersList.setVisibility(View.GONE);
            } else {
                emptyStateLayout.setVisibility(View.GONE);
                rvUsersList.setVisibility(View.VISIBLE);
            }
        }
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Filter Dialog ====================

    private void showFilterDialog() {
        String[] filterOptions = isArabic ? 
            new String[]{"الكل", "نشط", "موقوف", "متصل الآن"} :
            new String[]{"All", "Active", "Suspended", "Online Now"};
        
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "تصفية المستخدمين" : "Filter Users")
            .setSingleChoiceItems(filterOptions, currentFilter, (dialog, which) -> {
                currentFilter = which;
                applyCurrentFilter();
                dialog.dismiss();
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    // ==================== Export Data ====================

    private void exportUsersData() {
        if (userList.isEmpty()) {
            showToast(isArabic ? "لا يوجد بيانات للتصدير" : "No data to export");
            return;
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                StringBuilder csv = new StringBuilder();
                csv.append("ID,Username,Display Name,Role,Employee ID,Branch,Status,Last Login,Permissions\n");
                
                for (UserData u : userList) {
                    csv.append(u.id).append(",")
                       .append(escapeCSV(u.username)).append(",")
                       .append(escapeCSV(u.displayName)).append(",")
                       .append(u.role).append(",")
                       .append(escapeCSV(u.employeeId)).append(",")
                       .append(escapeCSV(u.branchName)).append(",")
                       .append(u.status).append(",")
                       .append(escapeCSV(u.lastLogin)).append(",")
                       .append(getPermissionsSummary(u.permissions)).append("\n");
                }
                
                // حفظ الملف
                File exportDir = new File(requireContext().getExternalFilesDir(null), "exports");
                if (!exportDir.exists()) exportDir.mkdirs();
                
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                File file = new File(exportDir, "users_" + timestamp + ".csv");
                
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(csv.toString().getBytes("UTF-8"));
                fos.close();
                
                mainHandler.post(() -> {
                    showToast(isArabic ? "✅ تم تصدير البيانات بنجاح" : "✅ Data exported successfully");
                    
                    // فتح الملف للمشاركة
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/csv");
                    Uri uri = FileProvider.getUriForFile(requireContext(), 
                        requireContext().getPackageName() + ".provider", file);
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, 
                        isArabic ? "مشاركة البيانات" : "Share Data"));
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    showToast(isArabic ? "❌ فشل تصدير البيانات: " + e.getMessage() : 
                        "❌ Failed to export data: " + e.getMessage());
                });
            }
        });
    }

    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String getPermissionsSummary(UserPermissions permissions) {
        if (permissions == null) return "None";
        
        List<String> grantedPermissions = new ArrayList<>();
        if (permissions.isCanViewInventory()) grantedPermissions.add("ViewInv");
        if (permissions.isCanEditInventory()) grantedPermissions.add("EditInv");
        if (permissions.isCanViewReports()) grantedPermissions.add("ViewRep");
        if (permissions.isCanManageUsers()) grantedPermissions.add("MngUsr");
        if (permissions.isCanViewAuditLogs()) grantedPermissions.add("ViewAud");
        if (permissions.isCanExportData()) grantedPermissions.add("Export");
        if (permissions.isCanApproveTransactions()) grantedPermissions.add("Approve");
        if (permissions.isCanAccessSettings()) grantedPermissions.add("Settings");
        
        return String.join(";", grantedPermissions);
    }

    // ==================== Password Generator ====================

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
        if (getContext() == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("password", text));
        showToast(isArabic ? "📋 تم نسخ كلمة المرور!" : "📋 Password copied!");
    }

    private String generateEmployeeId(String branchCode) {
        String prefix = (branchCode != null && branchCode.length() >= 2)
            ? branchCode.substring(0, 2).toUpperCase()
            : "HQ";
        return prefix + "-" + String.format("%03d", employeeCounter.getAndIncrement());
    }

    // ==================== Password Strength ====================

    private boolean isPasswordStrong(String password) {
        if (password == null || password.length() < 8) return false;
        if (!password.matches(".*[A-Z].*")) return false;
        if (!password.matches(".*[0-9].*")) return false;
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*")) return false;
        return true;
    }

    private int getPasswordStrength(String password) {
        if (password == null || password.isEmpty()) return 0;
        int score = 0;
        if (password.length() >= 4) score++;
        if (password.length() >= 6) score++;
        if (password.length() >= 8) score++;
        if (password.matches(".*[A-Z].*")) score++;
        if (password.matches(".*[0-9].*")) score++;
        if (password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*")) score++;
        return Math.min(score, 4);
    }

    private void updateStrengthBars(View b1, View b2, View b3, View b4, TextView label, int strength) {
        int red = 0xFFEF4444;
        int orange = 0xFFF59E0B;
        int green = 0xFF22C55E;
        int gray = 0xFFCBD5E1;

        if (b1 != null) b1.setBackgroundColor(strength >= 1 ? (strength <= 2 ? red : orange) : gray);
        if (b2 != null) b2.setBackgroundColor(strength >= 2 ? orange : gray);
        if (b3 != null) b3.setBackgroundColor(strength >= 3 ? green : gray);
        if (b4 != null) b4.setBackgroundColor(strength >= 4 ? green : gray);

        if (label != null) {
            String[] labels = isArabic
                ? new String[]{"", "ضعيفة", "متوسطة", "جيدة", "قوية"}
                : new String[]{"", "Weak", "Medium", "Good", "Strong"};
            int[] colors = {Color.GRAY, red, orange, green, green};
            label.setText(strength > 0 ? labels[strength] : "");
            label.setTextColor(strength > 0 ? colors[strength] : gray);
        }
    }

    private void updatePasswordRequirements(TextView tv, String password) {
        if (tv == null) return;
        if (password == null || password.isEmpty()) {
            tv.setText(isArabic
                ? "🔒 يجب أن تحتوي على:\n• 8 أحرف على الأقل\n• حرف كبير (A-Z)\n• رقم (0-9)\n• رمز خاص (!@#$%^&*)"
                : "🔒 Must contain:\n• Min 8 characters\n• Uppercase (A-Z)\n• Digit (0-9)\n• Special char (!@#$%^&*)");
            tv.setTextColor(Color.GRAY);
            return;
        }

        StringBuilder req = new StringBuilder();
        req.append(password.length() >= 8 ? "✅ " : "❌ ")
           .append(isArabic ? "8 أحرف على الأقل" : "Min 8 characters").append("\n");
        req.append(password.matches(".*[A-Z].*") ? "✅ " : "❌ ")
           .append(isArabic ? "حرف كبير" : "Uppercase letter").append("\n");
        req.append(password.matches(".*[0-9].*") ? "✅ " : "❌ ")
           .append(isArabic ? "رقم" : "Digit").append("\n");
        req.append(password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?].*") ? "✅ " : "❌ ")
           .append(isArabic ? "رمز خاص" : "Special char");

        tv.setText(req.toString());
        tv.setTextColor(isPasswordStrong(password) ? 0xFF22C55E : 0xFFEF4444);
    }

    // ==================== Username Validation ====================

    private void checkUsernameAvailability(String username, TextView tvResult) {
        if (username == null || username.length() < 3) {
            if (tvResult != null) {
                tvResult.setText(isArabic ? "اسم المستخدم قصير جداً" : "Username too short");
                tvResult.setTextColor(0xFFEF4444);
            }
            return;
        }

        // فحص محلي أولاً
        for (UserData user : userList) {
            if (user.username.equalsIgnoreCase(username)) {
                if (tvResult != null) {
                    tvResult.setText(isArabic ? "❌ اسم المستخدم مستخدم بالفعل" : "❌ Username already taken");
                    tvResult.setTextColor(0xFFEF4444);
                }
                return;
            }
        }

        // فحص عبر API
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.checkUsername(username);
                mainHandler.post(() -> {
                    if (tvResult != null) {
                        if (response != null && response.isSuccess) {
                            boolean available = response.data != null && 
                                response.data.optBoolean("available", false);
                            tvResult.setText(available ? 
                                (isArabic ? "✅ اسم المستخدم متاح" : "✅ Username available") : 
                                (isArabic ? "❌ اسم المستخدم غير متاح" : "❌ Username not available"));
                            tvResult.setTextColor(available ? 0xFF22C55E : 0xFFEF4444);
                        }
                    }
                });
            } catch (Exception e) {
                // تجاهل أخطاء الفحص عبر API
            }
        });
    }

    // ==================== User Dialog ====================

    private void showUserDialog(@Nullable UserData existingUser) {
        if (!isAdded() || getContext() == null) return;

        boolean isEdit = existingUser != null;
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_edit, null);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // استخراج جميع عناصر الواجهة
        TextView tvTitle = dialogView.findViewById(R.id.tvDialogTitle);
        TextView tvSubtitle = dialogView.findViewById(R.id.tvDialogSubtitle);
        EditText etDisplayName = dialogView.findViewById(R.id.etDisplayName);
        EditText etUsername = dialogView.findViewById(R.id.etUsername);
        TextView tvUsernameStatus = dialogView.findViewById(R.id.tvUsernameStatus);
        EditText etPassword = dialogView.findViewById(R.id.etPassword);
        EditText etEmployeeId = dialogView.findViewById(R.id.etEmployeeId);
        com.google.android.material.textfield.MaterialAutoCompleteTextView etBranch =
            dialogView.findViewById(R.id.etBranch);
        com.google.android.material.textfield.MaterialAutoCompleteTextView etRole =
            dialogView.findViewById(R.id.etRole);
        Button btnCancel = dialogView.findViewById(R.id.btnCancelUser);
        Button btnNextStep = dialogView.findViewById(R.id.btnNextStep);
        Button btnPrevStep = dialogView.findViewById(R.id.btnPrevStep);
        Button btnSaveUser = dialogView.findViewById(R.id.btnSaveUser);
        Button btnManagePermissions = dialogView.findViewById(R.id.btnManagePermissions);
        LinearLayout layoutStep1 = dialogView.findViewById(R.id.layoutStep1);
        LinearLayout layoutStep2 = dialogView.findViewById(R.id.layoutStep2);
        LinearLayout layoutButtons1 = dialogView.findViewById(R.id.layoutButtons1);
        LinearLayout stepsIndicator = dialogView.findViewById(R.id.stepsIndicator);
        TextView tvStep1 = dialogView.findViewById(R.id.tvStep1);
        TextView tvStep2 = dialogView.findViewById(R.id.tvStep2);
        View vStepLine1 = dialogView.findViewById(R.id.vStepLine1);
        View vStrength1 = dialogView.findViewById(R.id.vStrength1);
        View vStrength2 = dialogView.findViewById(R.id.vStrength2);
        View vStrength3 = dialogView.findViewById(R.id.vStrength3);
        View vStrength4 = dialogView.findViewById(R.id.vStrength4);
        TextView tvStrengthLabel = dialogView.findViewById(R.id.tvStrengthLabel);
        TextView tvPasswordRequirements = dialogView.findViewById(R.id.tvPasswordRequirements);
        ImageButton btnGeneratePassword = dialogView.findViewById(R.id.btnGeneratePassword);
        ImageButton btnCopyPassword = dialogView.findViewById(R.id.btnCopyPassword);
        ImageButton btnSuggestUsername = dialogView.findViewById(R.id.btnSuggestUsername);
        LinearLayout loadingOverlay = dialogView.findViewById(R.id.loadingOverlay);
        TextView tvLoadingText = dialogView.findViewById(R.id.tvLoadingText);

        // العناوين
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

        // إعداد قائمة الفروع
        List<String> branchNames = new ArrayList<>();
        int activeBranchId = branchesManager.getActiveBranchId();
        int selectedBranchIndex = 0;

        for (int i = 0; i < branchesList.size(); i++) {
            BranchItem b = branchesList.get(i);
            branchNames.add(b.name + " (" + b.code + ")");
            if (b.id == activeBranchId) selectedBranchIndex = i;
        }
        if (branchNames.isEmpty()) {
            branchNames.add(isArabic ? "الفرع الرئيسي (MAIN)" : "Main Branch (MAIN)");
        }

        ArrayAdapter<String> branchAdapter = new ArrayAdapter<>(
            requireContext(), android.R.layout.simple_dropdown_item_1line, branchNames);
        if (etBranch != null) {
            etBranch.setAdapter(branchAdapter);
            etBranch.setText(branchNames.get(Math.min(selectedBranchIndex, branchNames.size() - 1)), false);
            etBranch.setOnClickListener(v -> etBranch.showDropDown());
        }

        // إعداد قائمة الصلاحيات
        List<String> roleNames = new ArrayList<>();
        String[] allowedRoles = getAllowedRoles();

        for (String roleCode : allowedRoles) {
            for (int i = 0; i < ROLES.length; i++) {
                if (ROLES[i].equals(roleCode)) {
                    roleNames.add(ROLE_ICONS[i] + " " + (isArabic ? ROLES_AR[i] : ROLES_EN[i]) + " (" + roleCode + ")");
                    break;
                }
            }
        }

        ArrayAdapter<String> roleAdapter = new ArrayAdapter<>(
            requireContext(), android.R.layout.simple_dropdown_item_1line, roleNames);
        if (etRole != null) {
            etRole.setAdapter(roleAdapter);
            if (!roleNames.isEmpty()) {
                int defaultIndex = Math.max(0, roleNames.size() - 2);
                etRole.setText(roleNames.get(defaultIndex), false);
            }
            etRole.setOnClickListener(v -> etRole.showDropDown());
        }

        // رقم موظف تلقائي
        String branchCode = branchesManager.getActiveBranchCode();
        if (etEmployeeId != null) {
            etEmployeeId.setText(generateEmployeeId(branchCode));
            etEmployeeId.setEnabled(false);
        }

        // متطلبات كلمة المرور الأولية
        if (tvPasswordRequirements != null) {
            tvPasswordRequirements.setText(isArabic
                ? "🔒 يجب أن تحتوي على:\n• 8 أحرف على الأقل\n• حرف كبير (A-Z)\n• رقم (0-9)\n• رمز خاص (!@#$%^&*)"
                : "🔒 Must contain:\n• Min 8 characters\n• Uppercase (A-Z)\n• Digit (0-9)\n• Special char (!@#$%^&*)");
        }

        // أزرار كلمة المرور
        if (btnGeneratePassword != null) {
            btnGeneratePassword.setOnClickListener(v -> {
                String pwd = generateStrongPassword(12);
                if (etPassword != null) etPassword.setText(pwd);
                showToast(isArabic ? "🔐 تم توليد كلمة مرور قوية!" : "🔐 Strong password generated!");
            });
        }

        if (btnCopyPassword != null) {
            btnCopyPassword.setOnClickListener(v -> {
                String pwd = etPassword != null ? etPassword.getText().toString() : "";
                if (!pwd.isEmpty()) {
                    copyToClipboard(pwd);
                } else {
                    showToast(isArabic ? "لا توجد كلمة مرور لنسخها" : "No password to copy");
                }
            });
        }

        if (btnSuggestUsername != null && etDisplayName != null && etUsername != null) {
            btnSuggestUsername.setOnClickListener(v -> {
                String name = etDisplayName.getText().toString().trim();
                if (!name.isEmpty()) {
                    etUsername.setText(suggestUsername(name));
                } else {
                    showToast(isArabic ? "أدخل الاسم الظاهر أولاً" : "Enter display name first");
                }
            });
        }

        // مراقبة اسم المستخدم
        if (etUsername != null && tvUsernameStatus != null) {
            etUsername.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (!isEdit) {
                        checkUsernameAvailability(s.toString(), tvUsernameStatus);
                    }
                }
            });
        }

        // مراقبة قوة كلمة المرور
        if (etPassword != null) {
            etPassword.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    String pwd = s.toString();
                    int strength = getPasswordStrength(pwd);
                    updateStrengthBars(vStrength1, vStrength2, vStrength3, vStrength4, tvStrengthLabel, strength);
                    updatePasswordRequirements(tvPasswordRequirements, pwd);
                }
            });
        }

        // تعبئة بيانات التعديل
        if (isEdit && existingUser != null) {
            if (etDisplayName != null) etDisplayName.setText(existingUser.displayName);
            if (etUsername != null) {
                etUsername.setText(existingUser.username);
                etUsername.setEnabled(false);
                if (tvUsernameStatus != null) tvUsernameStatus.setVisibility(View.GONE);
            }
            if (etPassword != null) {
                etPassword.setHint(isArabic ? "اتركه فارغاً لعدم التغيير" : "Leave empty to keep unchanged");
            }
            if (etEmployeeId != null) etEmployeeId.setText(existingUser.employeeId);

            // اختيار الفرع المناسب
            if (etBranch != null) {
                for (int i = 0; i < branchesList.size(); i++) {
                    if (branchesList.get(i).id == existingUser.branchId) {
                        if (i < branchNames.size()) {
                            etBranch.setText(branchNames.get(i), false);
                        }
                        break;
                    }
                }
            }

            // اختيار الصلاحية المناسبة
            if (etRole != null) {
                for (int i = 0; i < roleNames.size(); i++) {
                    if (roleNames.get(i).contains(existingUser.role)) {
                        etRole.setText(roleNames.get(i), false);
                        break;
                    }
                }
            }

            // وضع التعديل
            if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
            if (layoutStep2 != null) layoutStep2.setVisibility(View.VISIBLE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.GONE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.VISIBLE);
            if (btnCancel != null) btnCancel.setVisibility(View.VISIBLE);
            if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
            if (btnManagePermissions != null && canManagePermissions()) {
                btnManagePermissions.setVisibility(View.VISIBLE);
                btnManagePermissions.setOnClickListener(v -> {
                    showPermissionsDialog(existingUser);
                });
            }
        } else {
            // وضع الإضافة
            if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
            if (layoutStep2 != null) layoutStep2.setVisibility(View.GONE);
            if (stepsIndicator != null) stepsIndicator.setVisibility(View.VISIBLE);
            if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
            if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
            if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);
            if (layoutButtons1 != null) layoutButtons1.setVisibility(View.GONE);
            if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        }

        // زر التالي
        if (btnNextStep != null) {
            btnNextStep.setOnClickListener(v -> {
                String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                String password = etPassword != null ? etPassword.getText().toString().trim() : "";

                if (displayName.isEmpty()) {
                    if (etDisplayName != null) etDisplayName.setError(isArabic ? "مطلوب" : "Required");
                    return;
                }
                if (username.isEmpty()) {
                    if (etUsername != null) etUsername.setError(isArabic ? "مطلوب" : "Required");
                    return;
                }
                if (!isEdit && !isPasswordStrong(password)) {
                    showToast(isArabic ? "كلمة المرور لا تستوفي المتطلبات الأمنية" : "Password does not meet security requirements");
                    return;
                }

                if (layoutStep1 != null) layoutStep1.setVisibility(View.GONE);
                if (layoutStep2 != null) layoutStep2.setVisibility(View.VISIBLE);
                if (btnNextStep != null) btnNextStep.setVisibility(View.GONE);
                if (btnSaveUser != null) btnSaveUser.setVisibility(View.VISIBLE);
                if (btnPrevStep != null) btnPrevStep.setVisibility(View.VISIBLE);

                if (tvStep1 != null) {
                    tvStep1.setBackgroundResource(R.drawable.circle_outline);
                    tvStep1.setTextColor(0xFF44474E);
                }
                if (tvStep2 != null) {
                    tvStep2.setBackgroundResource(R.drawable.circle_primary);
                    tvStep2.setTextColor(Color.WHITE);
                }
                if (vStepLine1 != null) vStepLine1.setBackgroundColor(0xFF1A237E);
            });
        }

        // زر الرجوع
        if (btnPrevStep != null) {
            btnPrevStep.setOnClickListener(v -> {
                if (layoutStep1 != null) layoutStep1.setVisibility(View.VISIBLE);
                if (layoutStep2 != null) layoutStep2.setVisibility(View.GONE);
                if (btnNextStep != null) btnNextStep.setVisibility(View.VISIBLE);
                if (btnSaveUser != null) btnSaveUser.setVisibility(View.GONE);
                if (btnPrevStep != null) btnPrevStep.setVisibility(View.GONE);

                if (tvStep1 != null) {
                    tvStep1.setBackgroundResource(R.drawable.circle_primary);
                    tvStep1.setTextColor(Color.WHITE);
                }
                if (tvStep2 != null) {
                    tvStep2.setBackgroundResource(R.drawable.circle_outline);
                    tvStep2.setTextColor(0xFF44474E);
                }
            });
        }

        // زر الإلغاء
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        // زر الحفظ
        if (btnSaveUser != null) {
            btnSaveUser.setOnClickListener(v -> {
                String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                String password = etPassword != null ? etPassword.getText().toString().trim() : "";
                String empId = etEmployeeId != null ? etEmployeeId.getText().toString().trim() : "";
                String branchStr = etBranch != null ? etBranch.getText().toString().trim() : "";
                String roleStr = etRole != null ? etRole.getText().toString().trim() : "";

                if (displayName.isEmpty()) {
                    showToast(isArabic ? "الاسم مطلوب" : "Name is required");
                    return;
                }
                if (username.isEmpty()) {
                    showToast(isArabic ? "اسم المستخدم مطلوب" : "Username is required");
                    return;
                }
                if (branchStr.isEmpty()) {
                    showToast(isArabic ? "الفرع مطلوب" : "Branch is required");
                    return;
                }
                if (roleStr.isEmpty()) {
                    showToast(isArabic ? "الصلاحية مطلوبة" : "Role is required");
                    return;
                }

                String role = extractRoleCode(roleStr);

                if (!isEdit && !isRoleAllowed(role)) {
                    showToast(isArabic ? "❌ ليس لديك صلاحية لإنشاء هذا الدور" : "❌ You don't have permission to create this role");
                    return;
                }

                int branchId = activeBranchId;
                String branchName = branchStr.split("\\(")[0].trim();
                for (BranchItem b : branchesList) {
                    if (branchStr.contains(b.code) || branchStr.startsWith(b.name)) {
                        branchId = b.id;
                        branchName = b.name;
                        break;
                    }
                }

                if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);
                if (tvLoadingText != null) tvLoadingText.setText(isArabic ? "جاري الحفظ..." : "Saving...");
                if (btnSaveUser != null) btnSaveUser.setEnabled(false);
                if (btnCancel != null) btnCancel.setEnabled(false);
                if (btnPrevStep != null) btnPrevStep.setEnabled(false);

                final int fBranchId = branchId;
                final String fBranchName = branchName;

                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("username", username);
                        payload.put("display_name", displayName);
                        payload.put("role", role);
                        payload.put("employee_id", empId);
                        payload.put("branch_id", fBranchId);
                        payload.put("branch_name", fBranchName);

                        if (!password.isEmpty()) {
                            payload.put("password", password);
                        }
                        if (isEdit) {
                            payload.put("user_id", existingUser.id);
                        }

                        MiraApiClient.ApiResponse response = isEdit
                            ? apiClient.updateUser(payload)
                            : apiClient.createUser(payload);

                        mainHandler.post(() -> {
                            if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);

                            if (response != null && response.isSuccess) {
                                String msg = isEdit
                                    ? (isArabic ? "✅ تم تعديل المستخدم بنجاح" : "✅ User updated successfully")
                                    : (isArabic ? "✅ تمت إضافة المستخدم بنجاح" : "✅ User added successfully");
                                showToast(msg);
                                
                                // تسجيل النشاط
                                logUserActivity(isEdit ? existingUser.id : 0, 
                                    isEdit ? "UPDATE" : "CREATE", 
                                    "User: " + username);
                                
                                dialog.dismiss();
                                loadUsers();
                            } else {
                                String errorMsg = response != null
                                    ? response.optString("message", isArabic ? "فشل العملية" : "Operation failed")
                                    : (isArabic ? "فشل الاتصال بالخادم" : "Server connection failed");
                                showToast("❌ " + errorMsg);

                                if (btnSaveUser != null) btnSaveUser.setEnabled(true);
                                if (btnCancel != null) btnCancel.setEnabled(true);
                                if (btnPrevStep != null) btnPrevStep.setEnabled(true);
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                            showToast((isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage());
                            if (btnSaveUser != null) btnSaveUser.setEnabled(true);
                            if (btnCancel != null) btnCancel.setEnabled(true);
                            if (btnPrevStep != null) btnPrevStep.setEnabled(true);
                        });
                    }
                });
            });
        }

        dialog.show();
    }

    // ==================== Permissions Dialog ====================

    private void showPermissionsDialog(UserData user) {
        if (!canManagePermissions()) {
            showToast(isArabic ? "❌ ليس لديك صلاحية لإدارة الصلاحيات" : "❌ You don't have permission to manage permissions");
            return;
        }

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_user_permissions, null);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setTitle(isArabic ? "إدارة الصلاحيات: " + user.displayName : "Manage Permissions: " + user.displayName)
            .setCancelable(false)
            .create();

        // عناصر الواجهة
        CheckBox cbViewInventory = dialogView.findViewById(R.id.cbViewInventory);
        CheckBox cbEditInventory = dialogView.findViewById(R.id.cbEditInventory);
        CheckBox cbViewReports = dialogView.findViewById(R.id.cbViewReports);
        CheckBox cbManageUsers = dialogView.findViewById(R.id.cbManageUsers);
        CheckBox cbViewAuditLogs = dialogView.findViewById(R.id.cbViewAuditLogs);
        CheckBox cbExportData = dialogView.findViewById(R.id.cbExportData);
        CheckBox cbApproveTransactions = dialogView.findViewById(R.id.cbApproveTransactions);
        CheckBox cbAccessSettings = dialogView.findViewById(R.id.cbAccessSettings);
        Spinner spinnerTemplates = dialogView.findViewById(R.id.spinnerTemplates);
        Button btnApplyTemplate = dialogView.findViewById(R.id.btnApplyTemplate);
        Button btnSavePermissions = dialogView.findViewById(R.id.btnSavePermissions);
        Button btnCancelPermissions = dialogView.findViewById(R.id.btnCancelPermissions);
        LinearLayout loadingOverlay = dialogView.findViewById(R.id.loadingOverlay);

        // تعبئة الصلاحيات الحالية
        if (user.permissions != null) {
            cbViewInventory.setChecked(user.permissions.isCanViewInventory());
            cbEditInventory.setChecked(user.permissions.isCanEditInventory());
            cbViewReports.setChecked(user.permissions.isCanViewReports());
            cbManageUsers.setChecked(user.permissions.isCanManageUsers());
            cbViewAuditLogs.setChecked(user.permissions.isCanViewAuditLogs());
            cbExportData.setChecked(user.permissions.isCanExportData());
            cbApproveTransactions.setChecked(user.permissions.isCanApproveTransactions());
            cbAccessSettings.setChecked(user.permissions.isCanAccessSettings());
        }

        // إعداد قوالب الصلاحيات
        String[] templateNames = isArabic ? 
            new String[]{"اختر قالب...", "مدير كامل", "مدير", "مشغل", "مشاهد"} :
            new String[]{"Select template...", "Full Admin", "Manager", "Operator", "Viewer"};
        
        ArrayAdapter<String> templateAdapter = new ArrayAdapter<>(
            requireContext(), android.R.layout.simple_spinner_item, templateNames);
        templateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTemplates.setAdapter(templateAdapter);

        btnApplyTemplate.setOnClickListener(v -> {
            int position = spinnerTemplates.getSelectedItemPosition();
            if (position > 0) {
                String templateKey = "";
                switch (position) {
                    case 1: templateKey = "ADMIN_FULL"; break;
                    case 2: templateKey = "MANAGER"; break;
                    case 3: templateKey = "OPERATOR"; break;
                    case 4: templateKey = "VIEWER"; break;
                }
                
                UserPermissions template = PERMISSION_TEMPLATES.get(templateKey);
                if (template != null) {
                    cbViewInventory.setChecked(template.isCanViewInventory());
                    cbEditInventory.setChecked(template.isCanEditInventory());
                    cbViewReports.setChecked(template.isCanViewReports());
                    cbManageUsers.setChecked(template.isCanManageUsers());
                    cbViewAuditLogs.setChecked(template.isCanViewAuditLogs());
                    cbExportData.setChecked(template.isCanExportData());
                    cbApproveTransactions.setChecked(template.isCanApproveTransactions());
                    cbAccessSettings.setChecked(template.isCanAccessSettings());
                }
            }
        });

        btnSavePermissions.setOnClickListener(v -> {
            UserPermissions permissions = new UserPermissions();
            permissions.setCanViewInventory(cbViewInventory.isChecked());
            permissions.setCanEditInventory(cbEditInventory.isChecked());
            permissions.setCanViewReports(cbViewReports.isChecked());
            permissions.setCanManageUsers(cbManageUsers.isChecked());
            permissions.setCanViewAuditLogs(cbViewAuditLogs.isChecked());
            permissions.setCanExportData(cbExportData.isChecked());
            permissions.setCanApproveTransactions(cbApproveTransactions.isChecked());
            permissions.setCanAccessSettings(cbAccessSettings.isChecked());

            updateUserPermissions(user.id, permissions, loadingOverlay, dialog);
        });

        btnCancelPermissions.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void updateUserPermissions(int userId, UserPermissions permissions, 
                                       LinearLayout loadingOverlay, AlertDialog dialog) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("user_id", userId);
                payload.put("permissions", new JSONObject()
                    .put("can_view_inventory", permissions.isCanViewInventory())
                    .put("can_edit_inventory", permissions.isCanEditInventory())
                    .put("can_view_reports", permissions.isCanViewReports())
                    .put("can_manage_users", permissions.isCanManageUsers())
                    .put("can_view_audit_logs", permissions.isCanViewAuditLogs())
                    .put("can_export_data", permissions.isCanExportData())
                    .put("can_approve_transactions", permissions.isCanApproveTransactions())
                    .put("can_access_settings", permissions.isCanAccessSettings())
                );

                MiraApiClient.ApiResponse response = apiClient.updateUserPermissions(payload);

                mainHandler.post(() -> {
                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);

                    if (response != null && response.isSuccess) {
                        showToast(isArabic ? "✅ تم تحديث الصلاحيات بنجاح" : "✅ Permissions updated successfully");
                        
                        // تسجيل النشاط
                        logUserActivity(userId, "PERMISSION_CHANGE", "Updated permissions");
                        
                        dialog.dismiss();
                        loadUsers();
                    } else {
                        showToast(isArabic ? "❌ فشل تحديث الصلاحيات" : "❌ Failed to update permissions");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                    showToast((isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage());
                });
            }
        });
    }

    // ==================== Activity Logging ====================

    private void logUserActivity(int userId, String action, String details) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject logEntry = new JSONObject();
                logEntry.put("user_id", userId);
                logEntry.put("action", action);
                logEntry.put("details", details);
                logEntry.put("timestamp", System.currentTimeMillis());
                logEntry.put("performed_by", sessionManager.getUserId());
                
                apiClient.logActivity(logEntry);
            } catch (Exception e) {
                // Silent fail for logging
            }
        });
    }

    // ==================== Delete User ====================

    private void deleteUser(int userId, String name, String role) {
        if (!isAdded() || getContext() == null) return;

        if (!canDeleteUser(role)) {
            showToast(isArabic ? "❌ ليس لديك صلاحية لحذف هذا المستخدم" : "❌ You don't have permission to delete this user");
            return;
        }

        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "حذف مستخدم" : "Delete User")
            .setMessage((isArabic ? "هل أنت متأكد من حذف \"" : "Are you sure you want to delete \"")
                + name + "\"؟")
            .setPositiveButton(isArabic ? "حذف" : "Delete", (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.deleteUser(userId);
                    mainHandler.post(() -> {
                        showToast(response != null && response.isSuccess
                            ? (isArabic ? "✅ تم حذف المستخدم بنجاح" : "✅ User deleted successfully")
                            : (isArabic ? "❌ فشل حذف المستخدم" : "❌ Failed to delete user"));
                        
                        if (response != null && response.isSuccess) {
                            logUserActivity(userId, "DELETE", "Deleted user: " + name);
                        }
                        
                        loadUsers();
                    });
                });
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    // ==================== Data Classes ====================

    static class UserData {
        int id;
        int branchId;
        String username;
        String displayName;
        String role;
        String employeeId;
        String status;
        String lastLogin;
        String branchName;
        boolean isOnline;
        UserPermissions permissions;
    }

    static class UserPermissions {
        private boolean canViewInventory;
        private boolean canEditInventory;
        private boolean canViewReports;
        private boolean canManageUsers;
        private boolean canViewAuditLogs;
        private boolean canExportData;
        private boolean canApproveTransactions;
        private boolean canAccessSettings;

        // Getters and Setters
        public boolean isCanViewInventory() { return canViewInventory; }
        public void setCanViewInventory(boolean canViewInventory) { this.canViewInventory = canViewInventory; }
        
        public boolean isCanEditInventory() { return canEditInventory; }
        public void setCanEditInventory(boolean canEditInventory) { this.canEditInventory = canEditInventory; }
        
        public boolean isCanViewReports() { return canViewReports; }
        public void setCanViewReports(boolean canViewReports) { this.canViewReports = canViewReports; }
        
        public boolean isCanManageUsers() { return canManageUsers; }
        public void setCanManageUsers(boolean canManageUsers) { this.canManageUsers = canManageUsers; }
        
        public boolean isCanViewAuditLogs() { return canViewAuditLogs; }
        public void setCanViewAuditLogs(boolean canViewAuditLogs) { this.canViewAuditLogs = canViewAuditLogs; }
        
        public boolean isCanExportData() { return canExportData; }
        public void setCanExportData(boolean canExportData) { this.canExportData = canExportData; }
        
        public boolean isCanApproveTransactions() { return canApproveTransactions; }
        public void setCanApproveTransactions(boolean canApproveTransactions) { this.canApproveTransactions = canApproveTransactions; }
        
        public boolean isCanAccessSettings() { return canAccessSettings; }
        public void setCanAccessSettings(boolean canAccessSettings) { this.canAccessSettings = canAccessSettings; }
        
        public void setAllPermissions(boolean value) {
            this.canViewInventory = value;
            this.canEditInventory = value;
            this.canViewReports = value;
            this.canManageUsers = value;
            this.canViewAuditLogs = value;
            this.canExportData = value;
            this.canApproveTransactions = value;
            this.canAccessSettings = value;
        }
    }

    static class BranchItem {
        int id;
        String name;
        String code;
        String address;

        BranchItem() {}

        BranchItem(int id, String name, String code, String address) {
            this.id = id;
            this.name = name;
            this.code = code;
            this.address = address;
        }
    }

    // ==================== Adapter ====================

    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        final List<UserData> list;
        final boolean isArabic;

        UsersAdapter(List<UserData> list, boolean isArabic) {
            this.list = list;
            this.isArabic = isArabic;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_user_row, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserData u = list.get(pos);

            if (h.tvDisplayName != null) h.tvDisplayName.setText(u.displayName);
            if (h.tvUsername != null) h.tvUsername.setText("@" + u.username);
            if (h.tvRole != null) h.tvRole.setText(getRoleDisplayName(u.role, isArabic));
            if (h.tvEmployeeId != null && u.employeeId != null && !u.employeeId.isEmpty()) {
                h.tvEmployeeId.setText("🪪 " + u.employeeId);
                h.tvEmployeeId.setVisibility(View.VISIBLE);
            } else if (h.tvEmployeeId != null) {
                h.tvEmployeeId.setVisibility(View.GONE);
            }
            if (h.tvBranchName != null && u.branchName != null && !u.branchName.isEmpty()) {
                h.tvBranchName.setText("🏢 " + u.branchName);
                h.tvBranchName.setVisibility(View.VISIBLE);
            } else if (h.tvBranchName != null) {
                h.tvBranchName.setVisibility(View.GONE);
            }

            // عرض ملخص الصلاحيات
            if (h.tvPermissionsSummary != null && u.permissions != null) {
                StringBuilder permsSummary = new StringBuilder();
                if (u.permissions.isCanManageUsers()) permsSummary.append("👥 ");
                if (u.permissions.isCanEditInventory()) permsSummary.append("✏️📦 ");
                else if (u.permissions.isCanViewInventory()) permsSummary.append("👁📦 ");
                if (u.permissions.isCanViewReports()) permsSummary.append("📊 ");
                if (u.permissions.isCanExportData()) permsSummary.append("📤 ");
                if (u.permissions.isCanAccessSettings()) permsSummary.append("⚙️ ");
                
                if (permsSummary.length() > 0) {
                    h.tvPermissionsSummary.setText(permsSummary.toString().trim());
                    h.tvPermissionsSummary.setVisibility(View.VISIBLE);
                } else {
                    h.tvPermissionsSummary.setVisibility(View.GONE);
                }
            }

            // الحالة
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

            // أزرار الصلاحيات والتعديل والحذف
            if (h.btnPermissions != null) {
                if (canManagePermissions()) {
                    h.btnPermissions.setVisibility(View.VISIBLE);
                    h.btnPermissions.setOnClickListener(v -> showPermissionsDialog(u));
                } else {
                    h.btnPermissions.setVisibility(View.GONE);
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
                    h.btnDelete.setOnClickListener(v -> deleteUser(u.id, u.displayName, u.role));
                } else {
                    h.btnDelete.setVisibility(View.GONE);
                }
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDisplayName, tvUsername, tvRole, tvEmployeeId, tvStatus, tvBranchName, tvPermissionsSummary;
            View vStatusDot;
            ImageButton btnEdit, btnDelete, btnPermissions;

            VH(View v) {
                super(v);
                tvDisplayName = v.findViewById(R.id.tvRowDisplayName);
                tvUsername = v.findViewById(R.id.tvRowUsername);
                tvRole = v.findViewById(R.id.tvRowRole);
                tvEmployeeId = v.findViewById(R.id.tvRowEmployeeId);
                tvStatus = v.findViewById(R.id.tvRowStatus);
                tvBranchName = v.findViewById(R.id.tvRowBranchName);
                tvPermissionsSummary = v.findViewById(R.id.tvPermissionsSummary);
                vStatusDot = v.findViewById(R.id.vStatusDot);
                btnEdit = v.findViewById(R.id.btnRowEdit);
                btnDelete = v.findViewById(R.id.btnRowDelete);
                btnPermissions = v.findViewById(R.id.btnPermissions);
            }
        }
    }
            }
