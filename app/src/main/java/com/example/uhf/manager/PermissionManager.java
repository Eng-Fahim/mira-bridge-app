package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final String PREFS_NAME = "MIRA_PERMISSIONS";
    private static final String KEY_PERMISSIONS_JSON = "permissions_json";

    private static volatile PermissionManager instance;
    private final Context context;
    private final SessionManager sessionManager;
    private JSONObject permissions;
    private SharedPreferences prefs;

    // الأدوار
    public static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String ROLE_ORG_ADMIN = "ORG_ADMIN";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_BRANCH_MANAGER = "BRANCH_MANAGER";
    public static final String ROLE_INVENTORY_MGR = "INVENTORY_MGR";
    public static final String ROLE_POS_OPERATOR = "POS_OPERATOR";
    public static final String ROLE_SECURITY = "SECURITY";
    public static final String ROLE_GATE_SECURITY = "GATE_SECURITY";
    public static final String ROLE_AUDITOR = "AUDITOR";

    // الميزات
    public static final String FEATURE_DASHBOARD = "dashboard";
    public static final String FEATURE_INVENTORY = "inventory";
    public static final String FEATURE_POS = "pos";
    public static final String FEATURE_TRANSFERS = "transfers";
    public static final String FEATURE_IDENTITY = "identity";
    public static final String FEATURE_RADAR = "radar";
    public static final String FEATURE_GATES = "gates";
    public static final String FEATURE_REPORTS = "reports";
    public static final String FEATURE_ACCOUNTING = "accounting";
    public static final String FEATURE_ADMIN = "admin";
    public static final String FEATURE_USERS = "users";
    public static final String FEATURE_SERVER_CONFIG = "server_config";
    public static final String FEATURE_GOLD_RATES = "gold_rates";
    public static final String FEATURE_PROFILE = "profile";
    public static final String FEATURE_SALES_HISTORY = "sales_history";
    public static final String FEATURE_QUICK_ACCESS = "quick_access";

    private PermissionManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessionManager = new SessionManager(this.context);
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadPermissions();
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    public static PermissionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PermissionManager.class) {
                if (instance == null) {
                    instance = new PermissionManager(context);
                }
            }
        }
        return instance;
    }

    private void loadPermissions() {
        String json = prefs.getString(KEY_PERMISSIONS_JSON, "");
        if (!json.isEmpty()) {
            try {
                permissions = new JSONObject(json);
            } catch (Exception e) {
                permissions = new JSONObject();
                Log.e(TAG, "Error loading permissions: " + e.getMessage());
            }
        } else {
            permissions = new JSONObject();
        }
    }

    public void savePermissions(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.trim().isEmpty()) return;

        try {
            permissions = new JSONObject(permissionsJson);
            prefs.edit().putString(KEY_PERMISSIONS_JSON, permissionsJson).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving permissions: " + e.getMessage());
        }
    }

    public void savePermissions(JSONObject permissionsJson) {
        if (permissionsJson != null) {
            permissions = permissionsJson;
            prefs.edit().putString(KEY_PERMISSIONS_JSON, permissionsJson.toString()).apply();
        }
    }

    public boolean hasPermission(String feature, String action) {
        // المدير الإداري والمشرف العام يستطيعان الوصول دائماً
        if (isAdmin()) return true;

        if (permissions == null) return false;

        try {
            JSONObject featureObj = permissions.optJSONObject(feature);
            if (featureObj != null) {
                return featureObj.optBoolean(action, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking permission: " + e.getMessage());
        }
        return false;
    }

    public boolean canView(String feature) {
        if (isAdmin()) return true;
        return hasPermission(feature, "view") || hasPermission(feature, "edit") || hasPermission(feature, "delete");
    }

    public boolean canEdit(String feature) {
        if (isAdmin()) return true;
        return hasPermission(feature, "edit") || hasPermission(feature, "delete");
    }

    public boolean canDelete(String feature) {
        if (isAdmin()) return true;
        return hasPermission(feature, "delete");
    }

    public boolean canExport(String feature) {
        if (isAdmin()) return true;
        return hasPermission(feature, "export") || canView(feature);
    }

    // ==================== أدوار المستخدمين ====================

    public boolean isSystemAdmin() {
        return ROLE_SYSTEM_ADMIN.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isOrgAdmin() {
        return ROLE_ORG_ADMIN.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isAdmin() {
        String role = sessionManager.getRoleString();
        if (role == null) return false;
        return ROLE_ADMIN.equalsIgnoreCase(role) || isSystemAdmin() || isOrgAdmin();
    }

    public boolean isBranchManager() {
        return ROLE_BRANCH_MANAGER.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isInventoryManager() {
        return ROLE_INVENTORY_MGR.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isPosOperator() {
        return ROLE_POS_OPERATOR.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isSecurity() {
        return ROLE_SECURITY.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isGateSecurity() {
        return ROLE_GATE_SECURITY.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isAuditor() {
        return ROLE_AUDITOR.equalsIgnoreCase(sessionManager.getRoleString());
    }

    public boolean isManager() {
        return isAdmin() || isBranchManager() || isInventoryManager();
    }

    // ==================== صلاحيات الواجهات ====================

    public boolean canViewDashboard() {
        return true; // الصفحة الرئيسية مسموحة للجميع
    }

    public boolean canViewInventory() {
        return canView(FEATURE_INVENTORY) || isManager();
    }

    public boolean canEditInventory() {
        return canEdit(FEATURE_INVENTORY) || isManager();
    }

    public boolean canDeleteInventory() {
        return canDelete(FEATURE_INVENTORY) || isAdmin();
    }

    public boolean canViewPos() {
        return canView(FEATURE_POS) || isPosOperator() || isManager();
    }

    public boolean canUsePos() {
        return canViewPos();
    }

    public boolean canViewTransfers() {
        return canView(FEATURE_TRANSFERS) || isManager();
    }

    public boolean canCreateTransfer() {
        return canEdit(FEATURE_TRANSFERS) || isManager();
    }

    public boolean canViewIdentity() {
        return canView(FEATURE_IDENTITY) || isManager();
    }

    public boolean canUseIdentity() {
        return canEdit(FEATURE_IDENTITY) || isManager();
    }

    public boolean canViewRadar() {
        return canView(FEATURE_RADAR) || isSecurity() || isGateSecurity() || isManager();
    }

    public boolean canViewGates() {
        return canView(FEATURE_GATES) || isSecurity() || isGateSecurity() || isManager();
    }

    public boolean canManageGates() {
        return canEdit(FEATURE_GATES) || isAdmin() || isBranchManager();
    }

    public boolean canViewReports() {
        return canView(FEATURE_REPORTS) || isManager() || isAuditor();
    }

    public boolean canExportReports() {
        return canExport(FEATURE_REPORTS) || isManager() || isAuditor();
    }

    public boolean canViewAccounting() {
        return canView(FEATURE_ACCOUNTING) || isAdmin() || isBranchManager() || isAuditor();
    }

    public boolean canEditAccounting() {
        return canEdit(FEATURE_ACCOUNTING) || isAdmin();
    }

    public boolean canViewAdminHub() {
        return isAdmin() || isBranchManager();
    }

    public boolean canManageUsers() {
        return isAdmin() || (isBranchManager() && canEdit(FEATURE_USERS));
    }

    public boolean canViewServerConfig() {
        return isAdmin();
    }

    public boolean canViewGoldRates() {
        return canView(FEATURE_GOLD_RATES) || isManager();
    }

    public boolean canEditGoldRates() {
        return canEdit(FEATURE_GOLD_RATES) || isAdmin() || isBranchManager();
    }

    public boolean canViewProfile() {
        return true;
    }

    public boolean canViewSalesHistory() {
        return canView(FEATURE_SALES_HISTORY) || isPosOperator() || isManager();
    }

    public boolean canManageQuickAccess() {
        return isAdmin() || isBranchManager();
    }

    // ==================== فحص الوصول للواجهات (محدث وآمن مرن) ====================

    public boolean canAccessFragment(String fragmentTag) {
        if (fragmentTag == null || fragmentTag.trim().isEmpty()) return true;

        String tag = fragmentTag.trim();

        // 1. الواجهات المتاحة دائماً للجميع
        if (tag.equalsIgnoreCase("Dashboard") || tag.equalsIgnoreCase("Home") ||
            tag.equalsIgnoreCase("HomeFragment") || tag.equalsIgnoreCase("Profile")) {
            return true;
        }

        // 2. إذا كان مدير نظام أو أدمن افتح كل شيء
        if (isAdmin()) return true;

        // 3. مطابقة مرنة لا تتأثر بالـ Capital/Small letters
        if (tag.equalsIgnoreCase("Scan") || tag.equalsIgnoreCase("Inventory") || tag.equalsIgnoreCase("InventoryPro")) {
            return canViewInventory();
        }
        if (tag.equalsIgnoreCase("POS") || tag.equalsIgnoreCase("PosFragment")) {
            return canUsePos();
        }
        if (tag.equalsIgnoreCase("Transfers") || tag.equalsIgnoreCase("TransferDetail")) {
            return canViewTransfers();
        }
        if (tag.equalsIgnoreCase("IdentityStudio")) {
            return canUseIdentity();
        }
        if (tag.equalsIgnoreCase("Radar")) {
            return canViewRadar();
        }
        if (tag.equalsIgnoreCase("SecureGate") || tag.equalsIgnoreCase("GateSettings")) {
            return canViewGates();
        }
        if (tag.equalsIgnoreCase("Reports") || tag.equalsIgnoreCase("ReportsPro")) {
            return canViewReports();
        }
        if (tag.equalsIgnoreCase("Accounting") || tag.equalsIgnoreCase("Vouchers") ||
            tag.equalsIgnoreCase("FiscalYears") || tag.equalsIgnoreCase("OpeningBalances") ||
            tag.equalsIgnoreCase("FinancialReports") || tag.equalsIgnoreCase("ProfitReport") ||
            tag.equalsIgnoreCase("AccountingCharts") || tag.equalsIgnoreCase("AccountingAuditLog")) {
            return canViewAccounting();
        }
        if (tag.equalsIgnoreCase("AdminHub")) {
            return canViewAdminHub();
        }
        if (tag.equalsIgnoreCase("UsersManagement")) {
            return canManageUsers();
        }
        if (tag.equalsIgnoreCase("ServerConfig")) {
            return canViewServerConfig();
        }
        if (tag.equalsIgnoreCase("GoldRates")) {
            return canViewGoldRates();
        }
        if (tag.equalsIgnoreCase("SalesHistory")) {
            return canViewSalesHistory();
        }
        if (tag.equalsIgnoreCase("QuickAccessSettings")) {
            return canManageQuickAccess();
        }
        if (tag.equalsIgnoreCase("ReaderSetup")) {
            return isManager();
        }
        if (tag.equalsIgnoreCase("RFIDWrite") || tag.equalsIgnoreCase("RfidBindings")) {
            return canEditInventory();
        }
        if (tag.equalsIgnoreCase("Branches")) {
            return isManager();
        }

        // افتراض الأمان: إذا لم نجد الـ Tag وكان مستخدماً عادياً، اسمح بالتحميل لتجنب الشاشة السوداء طالما أتى من القائمة
        return true;
    }

    public void clearPermissions() {
        permissions = new JSONObject();
        prefs.edit().remove(KEY_PERMISSIONS_JSON).apply();
    }
}
