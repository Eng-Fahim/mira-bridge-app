package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PermissionManager v3.1 — Enterprise Role Matrix (Complete)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  التغييرات في v3.1:
 *   ✅ إضافة System Admin bypass في بداية canAccessFragment
 *   ✅ إضافة 8 دوال جديدة (Scrap Gold + Parties + Cash + Purchase + Audit)
 *   ✅ إضافة if blocks لهذه الشاشات في canAccessFragment
 *
 *  التغييرات في v3.0:
 *   ✅ مصفوفة أدوار صريحة (Explicit Role Matrix)
 *   ✅ إلغاء isManager() الشامل من الدوال الحساسة
 *   ✅ canAccessFragment ترجع false افتراضياً (Whitelist)
 *   ✅ إزالة التكرار في الفحوصات
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final String PREFS_NAME = "MIRA_PERMISSIONS";
    private static final String KEY_PERMISSIONS_JSON = "permissions_json";

    private static volatile PermissionManager instance;
    private final Context context;
    private final SessionManager sessionManager;
    private JSONObject permissions;
    private final SharedPreferences prefs;

    // ═══════════════════════════════════════════════════════
    //  الأدوار
    // ═══════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════
    //  Features
    // ═══════════════════════════════════════════════════════
    public static final String FEATURE_DASHBOARD      = "dashboard";
    public static final String FEATURE_INVENTORY      = "inventory";
    public static final String FEATURE_POS            = "pos";
    public static final String FEATURE_TRANSFERS      = "transfers";
    public static final String FEATURE_IDENTITY       = "identity";
    public static final String FEATURE_RADAR          = "radar";
    public static final String FEATURE_GATES          = "gates";
    public static final String FEATURE_REPORTS        = "reports";
    public static final String FEATURE_ACCOUNTING     = "accounting";
    public static final String FEATURE_ADMIN          = "admin";
    public static final String FEATURE_USERS          = "users";
    public static final String FEATURE_BRANCHES       = "branches";
    public static final String FEATURE_SERVER_CONFIG  = "server_config";
    public static final String FEATURE_GOLD_RATES     = "gold_rates";
    public static final String FEATURE_PROFILE        = "profile";
    public static final String FEATURE_SALES_HISTORY  = "sales_history";
    public static final String FEATURE_QUICK_ACCESS   = "quick_access";
    public static final String FEATURE_RFID_WRITE     = "rfid_write";
    public static final String FEATURE_RFID_BINDINGS  = "rfid_bindings";
    public static final String FEATURE_READER_SETUP   = "reader_setup";
    public static final String FEATURE_SOUND_SETTINGS = "sound_settings";
    public static final String FEATURE_SNAPSHOTS      = "snapshots";

    // ═══════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════
    //  Storage
    // ═══════════════════════════════════════════════════════
    private void loadPermissions() {
        String json = prefs.getString(KEY_PERMISSIONS_JSON, "");
        try {
            permissions = json.isEmpty() ? new JSONObject() : new JSONObject(json);
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

    public void clearPermissions() {
        permissions = new JSONObject();
        prefs.edit().remove(KEY_PERMISSIONS_JSON).apply();
    }

    // ═══════════════════════════════════════════════════════
    //  Low-level Feature Checks
    // ═══════════════════════════════════════════════════════
    public boolean hasPermission(String feature, String action) {
        if (isSystemAdmin()) return true;
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
        if (isSystemAdmin()) return true;
        return hasPermission(feature, "view")
            || hasPermission(feature, "edit")
            || hasPermission(feature, "delete");
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

    // ═══════════════════════════════════════════════════════
    //  Role Checks
    // ═══════════════════════════════════════════════════════
    private String role() {
        return sessionManager.getRoleString();
    }

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
        return ROLE_ADMIN.equalsIgnoreCase(r)
            || isSystemAdmin()
            || isOrgAdmin()
            || isFinanceManager();
    }

    public boolean isManager() {
        return isAdmin()
            || isBranchManager()
            || isInventoryManager();
    }

    public boolean isAccountingUser() {
        return isAccountant() || isFinanceManager() || isAuditor();
    }

    public boolean isGateUser() {
        return isGateSecurity() || isSecurity();
    }

    // ═══════════════════════════════════════════════════════
    //  Setup
    // ═══════════════════════════════════════════════════════
    public boolean canPerformAccountingSetup() {
        return isSystemAdmin() || isOrgAdmin()
            || isFinanceManager() || isAccountant();
    }

    public boolean canManageUsersAdvanced() {
        return isSystemAdmin() || isOrgAdmin();
    }

    // ═══════════════════════════════════════════════════════
    //  Feature Checks — Individual
    // ═══════════════════════════════════════════════════════

    public boolean canViewDashboard() { return true; }
    public boolean canViewProfile()   { return true; }

    // ─── Inventory ───
    public boolean canViewInventory() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || isAuditor()
            || canView(FEATURE_INVENTORY);
    }

    public boolean canEditInventory() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canEdit(FEATURE_INVENTORY);
    }

    public boolean canDeleteInventory() {
        return isSystemAdmin() || isOrgAdmin()
            || canDelete(FEATURE_INVENTORY);
    }

    // ─── POS ───
    public boolean canViewPos() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isPosOperator()
            || isAuditor()
            || canView(FEATURE_POS);
    }

    public boolean canUsePos() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isPosOperator()
            || canEdit(FEATURE_POS);
    }

    // ─── Transfers ───
    public boolean canViewTransfers() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isInventoryManager()
            || isAuditor() || isFinanceManager()
            || canView(FEATURE_TRANSFERS);
    }

    public boolean canCreateTransfer() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isInventoryManager()
            || canEdit(FEATURE_TRANSFERS);
    }

    public boolean canApproveTransfer() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager()
            || canEdit(FEATURE_TRANSFERS);
    }

    // ─── Identity Studio ───
    public boolean canViewIdentity() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canView(FEATURE_IDENTITY);
    }

    public boolean canUseIdentity() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canEdit(FEATURE_IDENTITY);
    }

    // ─── Radar ───
    public boolean canViewRadar() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || isGateUser()
            || canView(FEATURE_RADAR);
    }

    // ─── Gates ───
    public boolean canViewGates() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isGateUser()
            || isAuditor()
            || canView(FEATURE_GATES);
    }

    public boolean canManageGates() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager()
            || canEdit(FEATURE_GATES);
    }

    // ─── Reports ───
    public boolean canViewReports() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isInventoryManager()
            || isFinanceManager() || isAccountant()
            || isAuditor()
            || canView(FEATURE_REPORTS);
    }

    public boolean canExportReports() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isFinanceManager()
            || isAccountant()
            || canExport(FEATURE_REPORTS);
    }

    // ─── Accounting ───
    public boolean canViewAccounting() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager()
            || isFinanceManager()
            || isAccountant()
            || isAuditor()
            || canView(FEATURE_ACCOUNTING);
    }

    public boolean canEditAccounting() {
        return isSystemAdmin() || isOrgAdmin()
            || isFinanceManager()
            || isAccountant()
            || canEdit(FEATURE_ACCOUNTING);
    }

    // ─── AdminHub ───
    public boolean canViewAdminHub() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager()
            || isFinanceManager()
            || canView(FEATURE_ADMIN);
    }

    // ─── Users ───
    public boolean canManageUsers() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager()
            || canEdit(FEATURE_USERS);
    }

    // ─── Branches ───
    public boolean canViewBranches() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager()
            || canView(FEATURE_BRANCHES);
    }

    public boolean canManageBranches() {
        return isSystemAdmin() || isOrgAdmin()
            || canEdit(FEATURE_BRANCHES);
    }

    // ─── Server Config ───
    public boolean canViewServerConfig() {
        return isSystemAdmin() || isOrgAdmin()
            || isFinanceManager()
            || canView(FEATURE_SERVER_CONFIG);
    }

    // ─── Gold Rates ───
    public boolean canViewGoldRates() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isFinanceManager()
            || isAccountant() || isInventoryManager()
            || canView(FEATURE_GOLD_RATES);
    }

    public boolean canEditGoldRates() {
        return isSystemAdmin() || isOrgAdmin()
            || isFinanceManager()
            || canEdit(FEATURE_GOLD_RATES);
    }

    // ─── Sales History ───
    public boolean canViewSalesHistory() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager() || isFinanceManager()
            || isAccountant() || isPosOperator() || isAuditor()
            || canView(FEATURE_SALES_HISTORY);
    }

    // ─── Quick Access ───
    public boolean canManageQuickAccess() {
        return isSystemAdmin() || isOrgAdmin()
            || isBranchManager()
            || canEdit(FEATURE_QUICK_ACCESS);
    }

    // ─── RFID Write ───
    public boolean canWriteRfid() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canEdit(FEATURE_RFID_WRITE);
    }

    // ─── RFID Bindings ───
    public boolean canViewRfidBindings() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || isAuditor()
            || canView(FEATURE_RFID_BINDINGS);
    }

    // ─── Reader Setup ───
    public boolean canViewReaderSetup() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canView(FEATURE_READER_SETUP);
    }

    // ─── Sound ───
    public boolean canViewSoundSettings() { return true; }

    // ─── Snapshots ───
    public boolean canViewSnapshots() { return canViewInventory(); }

    // ═══════════════════════════════════════════════════════
    //  Scrap Gold (كسر الذهب) — Business Functions
    // ═══════════════════════════════════════════════════════
    public boolean canViewScrapGold() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || isAuditor()
            || canView(FEATURE_INVENTORY);
    }

    public boolean canBuyScrapGold() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canEdit(FEATURE_INVENTORY);
    }

    public boolean canViewScrapAging() {
        return canViewScrapGold() || isAuditor();
    }

    // ═══════════════════════════════════════════════════════
    //  Parties (عملاء وموردين)
    // ═══════════════════════════════════════════════════════
    public boolean canViewParties() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || isFinanceManager() || isAccountant()
            || canView(FEATURE_REPORTS);
    }

    // ═══════════════════════════════════════════════════════
    //  Cash Reconciliation (تقفيل الخزينة)
    // ═══════════════════════════════════════════════════════
    public boolean canViewCashReconciliation() {
        return isSystemAdmin() || isOrgAdmin()
            || isFinanceManager() || isBranchManager()
            || isAccountant()
            || canView(FEATURE_ACCOUNTING);
    }

    // ═══════════════════════════════════════════════════════
    //  Purchase Invoice (فاتورة مشتريات)
    // ═══════════════════════════════════════════════════════
    public boolean canCreatePurchaseInvoice() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || isAccountant()
            || canEdit(FEATURE_ACCOUNTING);
    }

    // ═══════════════════════════════════════════════════════
    //  Audit Logs (سجل التدقيق)
    // ═══════════════════════════════════════════════════════
    public boolean canViewAuditLogs() {
        return isSystemAdmin() || isOrgAdmin()
            || isAuditor() || isBranchManager()
            || canView(FEATURE_ADMIN);
    }

    // ═══════════════════════════════════════════════════════
    //  Gold Conversion (أمر صياغة) — متاح للجميع
    // ═══════════════════════════════════════════════════════
    public boolean canViewGoldConversion() {
        return true;
    }

    // ═══════════════════════════════════════════════════════
    //  Legacy Aliases
    // ═══════════════════════════════════════════════════════
    public boolean canAccessIdentityStudio() { return canUseIdentity(); }
    public boolean canAccessRadar()          { return canViewRadar(); }
    public boolean canAccessGate()           { return canViewGates() && isGateUser() || isAdmin() || isBranchManager(); }
    public boolean canAccessAccounting()     { return canViewAccounting(); }
    public boolean canAccessTransfers()      { return canViewTransfers(); }

    // ═══════════════════════════════════════════════════════
    //  Quick Actions (FAB)
    // ═══════════════════════════════════════════════════════
    public List<String> getAllowedQuickActions() {
        List<String> allowed = new ArrayList<>();
        if (canAccessFragment("IdentityStudio"))    allowed.add("identity");
        if (canAccessFragment("Radar"))             allowed.add("radar");
        if (canAccessFragment("SecureGate"))        allowed.add("gate");
        if (canAccessFragment("Reports"))           allowed.add("reports");
        if (canAccessFragment("Accounting"))        allowed.add("accounting");
        if (canAccessFragment("Transfers"))         allowed.add("transfers");
        if (canAccessFragment("AdminHub"))          allowed.add("admin_hub");
        if (canAccessFragment("SalesHistory"))      allowed.add("sales_history");
        if (canAccessFragment("ServerConfig"))      allowed.add("server_config");
        if (canAccessFragment("GoldRates"))         allowed.add("gold_rates");
        if (canAccessFragment("UsersManagement"))   allowed.add("users");
        if (canAccessFragment("Branches"))          allowed.add("branches");
        if (canAccessFragment("RFIDWrite"))         allowed.add("rfid_write");
        if (canAccessFragment("RfidBindings"))      allowed.add("rfid_bindings");
        return allowed;
    }

    // ═══════════════════════════════════════════════════════
    //  Dashboard Widgets (Role-based)
    // ═══════════════════════════════════════════════════════
    public boolean canViewDashboardHero() {
        return isSystemAdmin() || isOrgAdmin() || isFinanceManager()
            || isInventoryManager() || isBranchManager() || isAuditor()
            || canView(FEATURE_DASHBOARD + "_hero");
    }

    public boolean canViewDashboardSales() {
        return isSystemAdmin() || isOrgAdmin() || isFinanceManager()
            || isBranchManager() || isAccountant() || isAuditor()
            || canView(FEATURE_DASHBOARD + "_sales");
    }

    public boolean canViewDashboardGoldPrices() {
        return canViewGoldRates();
    }

    public boolean canViewDashboardInventoryByKarat() {
        return canViewInventory();
    }

    public boolean canViewDashboardScrap() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canView(FEATURE_INVENTORY);
    }

    public boolean canViewDashboardScrapAging() {
        return canViewDashboardScrap() || isAuditor();
    }

    public boolean canViewDashboardCharts() {
        return isSystemAdmin() || isOrgAdmin() || isFinanceManager()
            || isBranchManager() || isAccountant() || isAuditor()
            || canView(FEATURE_REPORTS);
    }

    public boolean canViewDashboardAlerts() {
        return isSystemAdmin() || isOrgAdmin()
            || isInventoryManager() || isBranchManager()
            || canEdit(FEATURE_INVENTORY);
    }

    public boolean canViewDashboardRecentActivity() {
        return true;
    }

    public boolean canRefreshGoldManually() {
        return isSystemAdmin() || isOrgAdmin() || isFinanceManager()
            || canEdit(FEATURE_GOLD_RATES);
    }

    public boolean canRevealSensitiveValues() {
        return isSystemAdmin() || isOrgAdmin() || isFinanceManager()
            || isInventoryManager() || isBranchManager()
            || isAccountant() || isAuditor()
            || canView(FEATURE_DASHBOARD + "_sensitive");
    }

    // ═══════════════════════════════════════════════════════
    //  Master Gate — canAccessFragment (Whitelist — Safe Default)
    // ═══════════════════════════════════════════════════════
    public boolean canAccessFragment(String fragmentTag) {
        if (fragmentTag == null || fragmentTag.trim().isEmpty()) return false;

        final String tag = fragmentTag.trim();

        // ✅ [v3.1] System Admin يتخطى كل الفحوصات
        if (isSystemAdmin()) return true;

        // ─── 1. متاح دائماً للجميع ───
        switch (tag.toLowerCase()) {
            case "dashboard":
            case "home":
            case "homefragment":
            case "profile":
            case "soundsettings":
            case "notifications":
            case "receipt":
            case "goldconversion":
                return true;
        }

        // ─── 2. شاشات النظام الحساسة ───
        if (tag.equalsIgnoreCase("ServerConfig"))      return canViewServerConfig();
        if (tag.equalsIgnoreCase("AccountingSetup"))   return canPerformAccountingSetup();
        if (tag.equalsIgnoreCase("ReaderControl"))     return canViewReaderSetup();

        // ─── 3. Scrap Gold (كسر الذهب) ───
        if (tag.equalsIgnoreCase("ScrapGoldBuy") || tag.equalsIgnoreCase("MiraScrapGoldBuy"))
            return canBuyScrapGold();

        if (tag.equalsIgnoreCase("ScrapHistory") || tag.equalsIgnoreCase("MiraScrapHistory"))
            return canViewScrapGold();

        if (tag.equalsIgnoreCase("ScrapBatches") || tag.equalsIgnoreCase("MiraScrapBatches"))
            return canViewScrapGold();

        if (tag.equalsIgnoreCase("ScrapAging") || tag.equalsIgnoreCase("MiraScrapAging"))
            return canViewScrapAging();

        if (tag.equalsIgnoreCase("ScrapProfitReport"))
            return canViewScrapGold();

        // ─── 4. Business ───
        if (tag.equalsIgnoreCase("Parties") || tag.equalsIgnoreCase("MiraParties"))
            return canViewParties();

        if (tag.equalsIgnoreCase("CashReconciliation"))
            return canViewCashReconciliation();

        if (tag.equalsIgnoreCase("PurchaseInvoice"))
            return canCreatePurchaseInvoice();

        if (tag.equalsIgnoreCase("AuditLogs"))
            return canViewAuditLogs();

        if (tag.equalsIgnoreCase("GoldConversion"))
            return canViewGoldConversion();

        if (tag.equalsIgnoreCase("Vouchers"))
            return canViewAccounting();

        // ─── 5. الفحوصات المنطقية ───
        if (tag.equalsIgnoreCase("Scan") || tag.equalsIgnoreCase("Inventory")
                || tag.equalsIgnoreCase("InventoryPro") || tag.equalsIgnoreCase("MiraInventory"))
            return canViewInventory();

        if (tag.equalsIgnoreCase("POS") || tag.equalsIgnoreCase("PosFragment"))
            return canUsePos();

        if (tag.equalsIgnoreCase("Transfers") || tag.equalsIgnoreCase("TransferDetail"))
            return canViewTransfers();

        if (tag.equalsIgnoreCase("IdentityStudio"))
            return canUseIdentity();

        if (tag.equalsIgnoreCase("Radar") || tag.equalsIgnoreCase("Locator"))
            return canViewRadar();

        if (tag.equalsIgnoreCase("SecureGate") || tag.equalsIgnoreCase("GateSettings")
                || tag.equalsIgnoreCase("Gates"))
            return canViewGates();

        if (tag.equalsIgnoreCase("Reports") || tag.equalsIgnoreCase("ReportsPro"))
            return canViewReports();

        if (tag.equalsIgnoreCase("Accounting")
                || tag.equalsIgnoreCase("FiscalYears") || tag.equalsIgnoreCase("OpeningBalances")
                || tag.equalsIgnoreCase("FinancialReports") || tag.equalsIgnoreCase("ProfitReport")
                || tag.equalsIgnoreCase("AccountingCharts") || tag.equalsIgnoreCase("AccountingAuditLog"))
            return canViewAccounting();

        if (tag.equalsIgnoreCase("AdminHub"))
            return canViewAdminHub();

        if (tag.equalsIgnoreCase("UsersManagement"))
            return canManageUsers();

        if (tag.equalsIgnoreCase("GoldRates"))
            return canViewGoldRates();

        if (tag.equalsIgnoreCase("SalesHistory"))
            return canViewSalesHistory();

        if (tag.equalsIgnoreCase("QuickAccessSettings"))
            return canManageQuickAccess();

        if (tag.equalsIgnoreCase("Branches") || tag.equalsIgnoreCase("BranchesManagement"))
            return canViewBranches();

        if (tag.equalsIgnoreCase("ReaderSetup") || tag.equalsIgnoreCase("UHFSet"))
            return canViewReaderSetup();

        if (tag.equalsIgnoreCase("RFIDWrite"))
            return canWriteRfid();

        if (tag.equalsIgnoreCase("RfidBindings"))
            return canViewRfidBindings();

        if (tag.equalsIgnoreCase("RfidSettings"))
            return isSystemAdmin() || isOrgAdmin() || isInventoryManager();

        if (tag.equalsIgnoreCase("SoundSettings"))
            return canViewSoundSettings();

        if (tag.equalsIgnoreCase("Snapshots"))
            return canViewSnapshots();

        if (tag.equalsIgnoreCase("NxpSecurity"))
            return isSystemAdmin() || isOrgAdmin();

        // ─── 6. Default: DENY (آمن) ───
        Log.w(TAG, "🚫 Unknown fragment tag denied: " + tag);
        return false;
    }
}
