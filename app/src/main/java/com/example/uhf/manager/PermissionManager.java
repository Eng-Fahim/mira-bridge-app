package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final String PREFS_NAME = "MIRA_PERMISSIONS";
    private static final String KEY_PERMISSIONS_JSON = "permissions_json";

    private static volatile PermissionManager instance;
    private final Context context;
    private final SessionManager sessionManager;
    private JSONObject permissions;
    private final SharedPreferences prefs;

    // الأدوار كقيم نصية للمقارنة الآمنة
    public static final String ROLE_SYSTEM_ADMIN    = "SYSTEM_ADMIN";
    public static final String ROLE_ORG_ADMIN       = "ORG_ADMIN";
    public static final String ROLE_FINANCE_MANAGER = "FINANCE_MANAGER";
    public static final String ROLE_INVENTORY_MGR   = "INVENTORY_MGR";
    public static final String ROLE_BRANCH_MANAGER  = "BRANCH_MANAGER";
    public static final String ROLE_ACCOUNTANT      = "ACCOUNTANT";
    public static final String ROLE_AUDITOR         = "AUDITOR";
    public static final String ROLE_POS_OPERATOR    = "POS_OPERATOR";
    public static final String ROLE_GATE_SECURITY   = "GATE_SECURITY";
    public static final String ROLE_SECURITY        = "SECURITY";
    public static final String ROLE_ADMIN           = "ADMIN";

    private PermissionManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessionManager = new SessionManager(this.context);
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadPermissions();
    }

    public static synchronized void resetInstance() { instance = null; }

    public static PermissionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PermissionManager.class) {
                if (instance == null) instance = new PermissionManager(context);
            }
        }
        return instance;
    }

    private void loadPermissions() {
        String json = prefs.getString(KEY_PERMISSIONS_JSON, "{}");
        try {
            permissions = new JSONObject(json);
        } catch (Exception e) {
            permissions = new JSONObject();
            Log.e(TAG, "Error loading permissions: " + e.getMessage());
        }
    }

    public void savePermissions(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.trim().isEmpty()) return;
        try {
            permissions = new JSONObject(permissionsJson);
            prefs.edit().putString(KEY_PERMISSIONS_JSON, permissionsJson).apply();
        } catch (Exception e) { Log.e(TAG, "Error saving permissions: " + e.getMessage()); }
    }

    public void clearPermissions() {
        permissions = new JSONObject();
        prefs.edit().remove(KEY_PERMISSIONS_JSON).apply();
    }

    private String role() { 
        return sessionManager != null ? sessionManager.getRoleString() : ""; 
    }

    // ══════════════════════════════════════════════════════════════
    // ✅ دورات الفحص المطلوبة لمنع أخطاء البناء في UHFMainActivity
    // ══════════════════════════════════════════════════════════════
    public boolean isSystemAdmin()    { return ROLE_SYSTEM_ADMIN.equalsIgnoreCase(role()); }
    public boolean isOrgAdmin()       { return ROLE_ORG_ADMIN.equalsIgnoreCase(role()); }
    public boolean isFinanceManager() { return ROLE_FINANCE_MANAGER.equalsIgnoreCase(role()); }
    public boolean isInventoryManager(){ return ROLE_INVENTORY_MGR.equalsIgnoreCase(role()); }
    public boolean isBranchManager()  { return ROLE_BRANCH_MANAGER.equalsIgnoreCase(role()); }
    public boolean isAccountant()     { return ROLE_ACCOUNTANT.equalsIgnoreCase(role()); }
    public boolean isAuditor()        { return ROLE_AUDITOR.equalsIgnoreCase(role()); }
    public boolean isPosOperator()    { return ROLE_POS_OPERATOR.equalsIgnoreCase(role()); }
    public boolean isSecurity()       { return ROLE_SECURITY.equalsIgnoreCase(role()); }
    public boolean isGateSecurity()   { return ROLE_GATE_SECURITY.equalsIgnoreCase(role()); }

    public boolean isAdmin() {
        String r = role();
        if (r == null) return false;
        return ROLE_ADMIN.equalsIgnoreCase(r) || isSystemAdmin() || isOrgAdmin() || isFinanceManager();
    }

    // ══════════════════════════════════════════════════════════════
    // ✅ فحص الصلاحيات الديناميكي (المستمد من السيرفر)
    // ══════════════════════════════════════════════════════════════
    public boolean hasPermission(String feature, String action) {
        if (isSystemAdmin()) return true;
        if (permissions == null) return false;
        try {
            JSONObject featureObj = permissions.optJSONObject(feature);
            if (featureObj != null) return featureObj.optBoolean(action, false);
        } catch (Exception e) { Log.e(TAG, "Error checking permission: " + e.getMessage()); }
        return false;
    }

    public boolean canView(String feature) {
        if (isSystemAdmin()) return true;
        return hasPermission(feature, "view") || hasPermission(feature, "edit") || hasPermission(feature, "delete");
    }

    public boolean canEdit(String feature) {
        if (isSystemAdmin()) return true;
        return hasPermission(feature, "edit") || hasPermission(feature, "delete");
    }

    public boolean canDelete(String feature) {
        if (isSystemAdmin()) return true;
        return hasPermission(feature, "delete");
    }

    public boolean canExport(String feature) {
        if (isSystemAdmin()) return true;
        return hasPermission(feature, "export") || canView(feature);
    }

    // ══════════════════════════════════════════════════════════════
    // ✅ دالة canAccessFragment المطلوبة لـ MiraReportsProFragment
    // ══════════════════════════════════════════════════════════════
    public boolean canAccessFragment(String fragmentTag) {
        if (fragmentTag == null || fragmentTag.trim().isEmpty()) return false;
        if (isSystemAdmin()) return true;
        
        // السماح العام بالشاشات الأساسية
        final String tag = fragmentTag.trim().toLowerCase();
        if (tag.equals("dashboard") || tag.equals("home") || tag.equals("profile") || tag.equals("reports")) {
            return true;
        }

        // الفحص العام بناءً على الـ permissions القادمة من السيرفر
        return canView(tag) || canEdit(tag) || isAdmin();
    }

    public List<String> getAllowedQuickActions() {
        List<String> allowed = new ArrayList<>();
        if (permissions == null) return allowed;

        Iterator<String> keys = permissions.keys();
        while (keys.hasNext()) {
            String feature = keys.next();
            if (canView(feature)) {
                allowed.add(feature);
            }
        }
        return allowed;
    }

        // ══════════════════════════════════════════════════════════════
    // ✅ إضافات تكميلية لضمان توافق PermissionManager مع UHFMainActivity
    // ══════════════════════════════════════════════════════════════

    public boolean isManager() {
        return isAdmin() || isBranchManager() || isFinanceManager() || isInventoryManager();
    }

    public boolean isGateUser() {
        return isGateSecurity() || isSecurity();
    }

    public boolean canViewServerConfig() {
        return isSystemAdmin() || isOrgAdmin() || isAdmin();
    }

    public boolean canViewProfile() {
        return true; // متاح لجميع المستخدمين المسجلين
    }

    public boolean canViewSoundSettings() {
        return true;
    }

    public boolean canViewGoldRates() {
        return canView("gold_rates") || true;
    }

    public boolean canManageQuickAccess() {
        return isAdmin() || isBranchManager() || isSystemAdmin();
    }

    public boolean canViewBranches() {
        return canView("branches") || isManager() || isAdmin();
    }

    public boolean canViewInventory() {
        return canView("inventory") || isInventoryManager() || isBranchManager() || isAdmin();
    }

    public boolean canEditInventory() {
        return canEdit("inventory") || isInventoryManager() || isBranchManager() || isSystemAdmin();
    }

    public boolean canUsePos() {
        return isPosOperator() || isBranchManager() || isAdmin();
    }

    public boolean canViewTransfers() {
        return canView("transfers") || isManager() || isAdmin();
    }

    public boolean canExportReports() {
        return canExport("reports") || isManager() || isAdmin();
    }

    public boolean canCreateTransfer() {
        return canEdit("transfers") || isPosOperator() || isInventoryManager() || isBranchManager();
    }

    public boolean canViewRadar() {
        return canView("radar") || isInventoryManager() || isBranchManager() || isAdmin();
    }

    public boolean canViewAccounting() {
        return canView("accounting") || isAccountant() || isFinanceManager() || isManager() || isAdmin();
    }

    public boolean canPerformAccountingSetup() {
        return isSystemAdmin() || isOrgAdmin() || isFinanceManager() || isAccountant();
    }
    // ══════════════════════════════════════════════════════════════
    // ✅ دوال DashboardUIBuilder و DashboardRoleController المفقودة
    // ══════════════════════════════════════════════════════════════

    public boolean canViewGates() {
        return isGateSecurity() || isSecurity() || isManager() || isAdmin();
    }

    public boolean canBuyScrapGold() {
        return isPosOperator() || isInventoryManager() || isManager() || isAdmin();
    }

    public boolean canViewSalesHistory() {
        return isPosOperator() || isManager() || isAdmin() || canView("sales");
    }

    public boolean canViewReports() {
        return isManager() || isAdmin() || canView("reports");
    }

    public boolean canViewDashboardHero() {
        return !isGateUser() || isSystemAdmin();
    }

    public boolean canViewDashboardGoldPrices() {
        return true; // متاح للجميع
    }

    public boolean canViewDashboardSales() {
        return isPosOperator() || isManager() || isAdmin();
    }

    public boolean canViewDashboardInventoryByKarat() {
        return isInventoryManager() || isManager() || isAdmin();
    }

    public boolean canViewDashboardScrap() {
        return isInventoryManager() || isPosOperator() || isManager() || isAdmin();
    }

    public boolean canViewDashboardScrapAging() {
        return isManager() || isAdmin();
    }

    public boolean canViewDashboardCharts() {
        return isManager() || isAdmin() || isSystemAdmin();
    }

    public boolean canViewDashboardAlerts() {
        return true;
    }

    public boolean canViewDashboardRecentActivity() {
        return !isGateUser();
    }
    // ══════════════════════════════════════════════════════════════
    // ✅ إضافات MiraAdminHub و Dashboard المفقودة
    // ══════════════════════════════════════════════════════════════
    public boolean canRefreshGoldManually() {
        return isManager() || isAdmin() || isSystemAdmin();
    }

    public boolean canViewReaderSetup() {
        return isSystemAdmin() || isOrgAdmin() || isManager();
    }

    public boolean canManageGates() {
        return isSystemAdmin() || isOrgAdmin() || isBranchManager() || isManager();
    }

    public boolean canManageUsers() {
        return isSystemAdmin() || isOrgAdmin();
    }

}
