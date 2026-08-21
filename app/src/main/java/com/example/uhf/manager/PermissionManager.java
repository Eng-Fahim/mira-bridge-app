package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

/**
 * MIRA Permission Manager
 * إدارة صلاحيات المستخدمين حسب الأدوار
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final String PREFS_NAME = "MIRA_PERMISSIONS";
    private static final String KEY_PERMISSIONS_JSON = "permissions_json";

    private static PermissionManager instance;
    private final Context context;
    private final SessionManager sessionManager;
    private JSONObject permissions;
    private SharedPreferences prefs;

    // ✅ الأدوار
    public static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String ROLE_ORG_ADMIN = "ORG_ADMIN";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_BRANCH_MANAGER = "BRANCH_MANAGER";
    public static final String ROLE_INVENTORY_MGR = "INVENTORY_MGR";
    public static final String ROLE_POS_OPERATOR = "POS_OPERATOR";
    public static final String ROLE_SECURITY = "SECURITY";
    public static final String ROLE_GATE_SECURITY = "GATE_SECURITY";
    public static final String ROLE_AUDITOR = "AUDITOR";

    // ✅ الميزات
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

    public static synchronized PermissionManager getInstance(Context context) {
        if (instance == null) {
            instance = new PermissionManager(context);
        }
        return instance;
    }

    /**
     * ✅ تحميل الصلاحيات من SharedPreferences
     */
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

    /**
     * ✅ حفظ الصلاحيات من استجابة تسجيل الدخول
     */
    public void savePermissions(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.isEmpty()) return;
        
        try {
            permissions = new JSONObject(permissionsJson);
            prefs.edit().putString(KEY_PERMISSIONS_JSON, permissionsJson).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving permissions: " + e.getMessage());
        }
    }

    /**
     * ✅ حفظ الصلاحيات من JSONObject
     */
    public void savePermissions(JSONObject permissionsJson) {
        if (permissionsJson != null) {
            permissions = permissionsJson;
            prefs.edit().putString(KEY_PERMISSIONS_JSON, permissionsJson.toString()).apply();
        }
    }

    /**
     * ✅ التحقق من صلاحية عامة
     */
    public boolean hasPermission(String feature, String action) {
        if (isSystemAdmin()) return true; // ✅ مدير النظام يملك كل شيء
        
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

    /**
     * ✅ التحقق من صلاحية عرض
     */
    public boolean canView(String feature) {
        return hasPermission(feature, "view") || hasPermission(feature, "edit") || hasPermission(feature, "delete");
    }

    /**
     * ✅ التحقق من صلاحية تعديل
     */
    public boolean canEdit(String feature) {
        return hasPermission(feature, "edit") || hasPermission(feature, "delete");
    }

    /**
     * ✅ التحقق من صلاحية حذف
     */
    public boolean canDelete(String feature) {
        return hasPermission(feature, "delete");
    }

    /**
     * ✅ التحقق من صلاحية تصدير
     */
    public boolean canExport(String feature) {
        return hasPermission(feature, "export") || canView(feature);
    }

    // ==================== ✅ أدوار المستخدمين ====================

    public boolean isSystemAdmin() {
        return ROLE_SYSTEM_ADMIN.equals(sessionManager.getRoleString());
    }

    public boolean isOrgAdmin() {
        return ROLE_ORG_ADMIN.equals(sessionManager.getRoleString());
    }

    public boolean isAdmin() {
        String role = sessionManager.getRoleString();
        return ROLE_ADMIN.equals(role) || isSystemAdmin() || isOrgAdmin();
    }

    public boolean isBranchManager() {
        return ROLE_BRANCH_MANAGER.equals(sessionManager.getRoleString());
    }

    public boolean isInventoryManager() {
        return ROLE_INVENTORY_MGR.equals(sessionManager.getRoleString());
    }

    public boolean isPosOperator() {
        return ROLE_POS_OPERATOR.equals(sessionManager.getRoleString());
    }

    public boolean isSecurity() {
        return ROLE_SECURITY.equals(sessionManager.getRoleString());
    }

    public boolean isGateSecurity() {
        return ROLE_GATE_SECURITY.equals(sessionManager.getRoleString());
    }

    public boolean isAuditor() {
        return ROLE_AUDITOR.equals(sessionManager.getRoleString());
    }

    public boolean isManager() {
        return isAdmin() || isBranchManager() || isInventoryManager();
    }

    // ==================== ✅ صلاحيات الواجهات ====================

    public boolean canViewDashboard() {
        return true; // ✅ الجميع يرى الداشبورد
    }

    public boolean canViewInventory() {
        return canView(FEATURE_INVENTORY);
    }

    public boolean canEditInventory() {
        return canEdit(FEATURE_INVENTORY);
    }

    public boolean canDeleteInventory() {
        return canDelete(FEATURE_INVENTORY);
    }

    public boolean canViewPos() {
        return canView(FEATURE_POS);
    }

    public boolean canUsePos() {
        return canView(FEATURE_POS) || isPosOperator() || isManager();
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
        return isSystemAdmin() || isOrgAdmin();
    }

    public boolean canViewGoldRates() {
        return canView(FEATURE_GOLD_RATES) || isManager();
    }

    public boolean canEditGoldRates() {
        return canEdit(FEATURE_GOLD_RATES) || isAdmin() || isBranchManager();
    }

    public boolean canViewProfile() {
        return true; // ✅ الجميع يرى ملفه الشخصي
    }

    public boolean canViewSalesHistory() {
        return canView(FEATURE_SALES_HISTORY) || isPosOperator() || isManager();
    }

    public boolean canManageQuickAccess() {
        return isAdmin() || isBranchManager();
    }

    // ==================== ✅ فحص الوصول للواجهات ====================

    public boolean canAccessFragment(String fragmentTag) {
        if (fragmentTag == null) return false;
        
        switch (fragmentTag) {
            case "Dashboard":
                return canViewDashboard();
            case "Scan":
            case "Inventory":
            case "InventoryPro":
                return canViewInventory();
            case "POS":
                return canUsePos();
            case "Transfers":
            case "TransferDetail":
                return canViewTransfers();
            case "IdentityStudio":
                return canUseIdentity();
            case "Radar":
                return canViewRadar();
            case "SecureGate":
            case "GateSettings":
                return canViewGates();
            case "Reports":
            case "ReportsPro":
                return canViewReports();
            case "Accounting":
            case "Vouchers":
            case "FiscalYears":
            case "OpeningBalances":
            case "FinancialReports":
            case "ProfitReport":
            case "AccountingCharts":
            case "AccountingAuditLog":
                return canViewAccounting();
            case "AdminHub":
                return canViewAdminHub();
            case "UsersManagement":
                return canManageUsers();
            case "ServerConfig":
                return canViewServerConfig();
            case "GoldRates":
                return canViewGoldRates();
            case "Profile":
                return canViewProfile();
            case "SalesHistory":
                return canViewSalesHistory();
            case "QuickAccessSettings":
                return canManageQuickAccess();
            case "ReaderSetup":
                return isAdmin() || isInventoryManager();
            case "RFIDWrite":
                return canEditInventory();
            default:
                return true; // ✅ الواجهات غير المعروفة متاحة افتراضياً
        }
    }

    /**
     * ✅ الحصول على قائمة الواجهات المسموحة
     */
    public String[] getAllowedFragments() {
        List<String> allowed = new ArrayList<>();
        
        String[] allFragments = {
            "Dashboard", "Scan", "POS", "Transfers", "IdentityStudio",
            "Radar", "SecureGate", "Reports", "Accounting", "AdminHub",
            "UsersManagement", "ServerConfig", "GoldRates", "Profile",
            "SalesHistory", "QuickAccessSettings"
        };
        
        for (String fragment : allFragments) {
            if (canAccessFragment(fragment)) {
                allowed.add(fragment);
            }
        }
        
        return allowed.toArray(new String[0]);
    }

    /**
     * ✅ مسح الصلاحيات (عند تسجيل الخروج)
     */
    public void clearPermissions() {
        permissions = new JSONObject();
        prefs.edit().remove(KEY_PERMISSIONS_JSON).apply();
    }
}
