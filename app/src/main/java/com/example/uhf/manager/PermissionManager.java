package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PermissionManager v4.0 — Server-Driven Capabilities (No Local Logic)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  المبدأ:
 *    ✅ التطبيق لا يتخذ أي قرار إداري.
 *    ✅ كل القرارات تأتي من السيرفر عبر:
 *         GET /api/v1/mobile/capabilities
 *       التي تُرجع { data: {modules:...}, legacy_permissions: {...} }
 *
 *    ✅ كل الدوال القديمة محفوظة (توافق خلفي كامل).
 *    ✅ دوال canX() تقرأ من legacy_permissions.
 *    ✅ canAccessFragment() تقرأ من fragments (أو تُشتق من modules).
 *    ✅ لا مصفوفة أدوار، لا isSystemAdmin() كقرار محلي.
 *       (isSystemAdmin() تبقى موجودة لكنها تعرض فقط — لا تُستخدم كـ bypass)
 *
 *  Contract مع السيرفر:
 *    server -> saveCapabilities(json) -> { data, legacy_permissions }
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final String PREFS_NAME = "MIRA_PERMISSIONS";
    private static final String KEY_CAPABILITIES = "capabilities_json";
    private static final String KEY_LEGACY       = "legacy_permissions_json";
    private static final String KEY_FRAGMENTS    = "fragments_json";

    private static volatile PermissionManager instance;
    private final Context context;
    private final SessionManager sessionManager;
    private final SharedPreferences prefs;

    private JSONObject capabilities;         // data من السيرفر
    private JSONObject legacyPermissions;    // legacy_permissions من السيرفر
    private JSONObject fragments;            // fragments (اختياري، مشتق)

    // ═══════════════════════════════════════════════════════
    //  الأدوار — تبقى كـ constants للعرض فقط
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

    // Features (constants — للعرض فقط)
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
        loadFromPrefs();
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

    // ═══════════════════════════════════════════════════════
    //  Storage — يُستدعى فقط من طبقة الشبكة عند نجاح الطلب
    // ═══════════════════════════════════════════════════════
    private void loadFromPrefs() {
        try {
            String c = prefs.getString(KEY_CAPABILITIES, "");
            String l = prefs.getString(KEY_LEGACY, "");
            String f = prefs.getString(KEY_FRAGMENTS, "");
            capabilities      = c.isEmpty() ? new JSONObject() : new JSONObject(c);
            legacyPermissions = l.isEmpty() ? new JSONObject() : new JSONObject(l);
            fragments         = f.isEmpty() ? new JSONObject() : new JSONObject(f);
        } catch (Exception e) {
            capabilities = new JSONObject();
            legacyPermissions = new JSONObject();
            fragments = new JSONObject();
            Log.e(TAG, "loadFromPrefs error: " + e.getMessage());
        }
    }

    /**
     * يُستدعى من طبقة الشبكة بعد GET /api/v1/mobile/capabilities
     * أو من within /api/v1/mobile/me (حقل mobile_capabilities + permissions).
     *
     * @param fullResponseJson الـ JSON الكامل للاستجابة (سواء capabilities أو me)
     */
    public void saveFromServer(JSONObject fullResponseJson) {
        if (fullResponseJson == null) return;
        try {
            JSONObject data = fullResponseJson.optJSONObject("data");
            JSONObject legacy = fullResponseJson.optJSONObject("legacy_permissions");

            // حالة /me: data.user + data.mobile_capabilities + data.permissions
            if (data != null && data.has("mobile_capabilities")) {
                JSONObject caps = data.optJSONObject("mobile_capabilities");
                if (caps != null) capabilities = caps;
                JSONObject perms = data.optJSONObject("permissions");
                if (perms != null) legacyPermissions = perms;
            } else if (data != null) {
                // حالة /capabilities: data = capabilities نفسه
                capabilities = data;
                if (legacy != null) legacyPermissions = legacy;
            }

            // اشتقاق fragments من capabilities.modules (إن لم تكن مرسلة صراحة)
            fragments = deriveFragments(capabilities);

            prefs.edit()
                 .putString(KEY_CAPABILITIES, capabilities.toString())
                 .putString(KEY_LEGACY,       legacyPermissions.toString())
                 .putString(KEY_FRAGMENTS,    fragments.toString())
                 .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveFromServer error: " + e.getMessage());
        }
    }

    /** توافق خلفي مع الاسم القديم */
    @Deprecated
    public void savePermissions(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(permissionsJson);
            saveFromServer(root.has("success") || root.has("data")
                ? root
                : wrapLegacyOnly(root));
        } catch (Exception e) {
            Log.e(TAG, "savePermissions error: " + e.getMessage());
        }
    }

    /** توافق خلفي */
    @Deprecated
    public void savePermissions(JSONObject permissionsJson) {
        if (permissionsJson != null) savePermissions(permissionsJson.toString());
    }

    @Deprecated
    public void clearPermissions() {
        capabilities = new JSONObject();
        legacyPermissions = new JSONObject();
        fragments = new JSONObject();
        prefs.edit().remove(KEY_CAPABILITIES)
                    .remove(KEY_LEGACY)
                    .remove(KEY_FRAGMENTS).apply();
    }

    private JSONObject wrapLegacyOnly(JSONObject legacy) {
        try {
            JSONObject root = new JSONObject();
            JSONObject data = new JSONObject();
            root.put("data", data);
            root.put("legacy_permissions", legacy);
            return root;
        } catch (Exception e) { return new JSONObject(); }
    }

    // ═══════════════════════════════════════════════════════
    //  Fragments — مشتقة من modules (السيرفر هو من يحدد)
    // ═══════════════════════════════════════════════════════
    private JSONObject deriveFragments(JSONObject caps) {
        JSONObject out = new JSONObject();
        if (caps == null) return out;
        JSONObject modules = caps.optJSONObject("modules");
        if (modules == null) return out;

        // كل fragment له علاقة بوحدة. لا قرار — مجرد تمرير.
        String[][] map = {
            {"Accounting", "accounting"}, {"FiscalYears", "accounting"},
            {"OpeningBalances", "accounting"}, {"FinancialReports", "accounting"},
            {"ProfitReport", "accounting"}, {"AccountingCharts", "accounting"},
            {"AccountingAuditLog", "accounting"}, {"Vouchers", "vouchers"},
            {"POS", "pos"}, {"PosFragment", "pos"},
            {"Inventory", "inventory"}, {"InventoryPro", "inventory"},
            {"MiraInventory", "inventory"}, {"Scan", "inventory"},
            {"Transfers", "transfers"}, {"TransferDetail", "transfers"},
            {"IdentityStudio", "inventory"},
            {"Radar", "radar"}, {"Locator", "radar"},
            {"SecureGate", "gate"}, {"GateSettings", "gate"}, {"Gates", "gate"},
            {"Reports", "reports"}, {"ReportsPro", "reports"},
            {"AdminHub", "admin"},
            {"UsersManagement", "users"},
            {"Branches", "branches"}, {"BranchesManagement", "branches"},
            {"GoldRates", "gold_rates"},
            {"SalesHistory", "sales_history"},
            {"QuickAccessSettings", "quick_access"},
            {"ReaderSetup", "reader_setup"}, {"UHFSet", "reader_setup"},
            {"ReaderControl", "reader_setup"},
            {"RFIDWrite", "rfid_write"},
            {"RfidBindings", "rfid_bindings"},
            {"SoundSettings", "sound_settings"},
            {"Snapshots", "snapshots"},
            {"ScrapGoldBuy", "inventory"}, {"MiraScrapGoldBuy", "inventory"},
            {"ScrapHistory", "inventory"}, {"MiraScrapHistory", "inventory"},
            {"ScrapBatches", "inventory"}, {"MiraScrapBatches", "inventory"},
            {"ScrapAging", "inventory"}, {"MiraScrapAging", "inventory"},
            {"ScrapProfitReport", "inventory"},
            {"Parties", "parties"}, {"MiraParties", "parties"},
            {"CashReconciliation", "accounting"},
            {"PurchaseInvoice", "purchases"},
            {"AuditLogs", "audit"},
            {"ServerConfig", "settings"},
            {"AccountingSetup", "accounting"},
            {"GoldConversion", "inventory"},
            {"Dashboard", "dashboard"}, {"Home", "dashboard"},
            {"HomeFragment", "dashboard"},
            {"Profile", "profile"},
            {"Notifications", "profile"},
            {"Receipt", "pos"},
        };

        for (String[] pair : map) {
            String frag = pair[0], mod = pair[1];
            JSONObject m = modules.optJSONObject(mod);
            if (m != null && m.optBoolean("enabled", false)) {
                try { out.put(frag, true); } catch (Exception ignored) {}
            }
        }

        // ثوابت مسموحة للجميع (يطابق السيرفر: profile/dashboard)
        try {
            out.put("Dashboard", true);
            out.put("Home", true);
            out.put("HomeFragment", true);
            out.put("Profile", true);
            out.put("Notifications", true);
            out.put("SoundSettings", true);
            out.put("Receipt", true);
            out.put("GoldConversion", true);
        } catch (Exception ignored) {}
        return out;
    }

    // ═══════════════════════════════════════════════════════
    //  Low-level — تقرأ من legacy_permissions القادم من السيرفر
    // ═══════════════════════════════════════════════════════
    public boolean hasPermission(String feature, String action) {
        if (legacyPermissions == null) return false;
        JSONObject f = legacyPermissions.optJSONObject(feature);
        return f != null && f.optBoolean(action, false);
    }

    public boolean canView(String feature) {
        return hasPermission(feature, "view")
            || hasPermission(feature, "edit")
            || hasPermission(feature, "delete");
    }
    public boolean canEdit(String feature)   { return hasPermission(feature,"edit") || hasPermission(feature,"delete"); }
    public boolean canDelete(String feature) { return hasPermission(feature,"delete"); }
    public boolean canExport(String feature) { return hasPermission(feature,"export") || canView(feature); }

    // ═══════════════════════════════════════════════════════
    //  Module-scope helper (من capabilities)
    // ═══════════════════════════════════════════════════════
    private JSONObject module(String name) {
        if (capabilities == null) return null;
        JSONObject modules = capabilities.optJSONObject("modules");
        return modules == null ? null : modules.optJSONObject(name);
    }
    private boolean moduleEnabled(String name) {
        JSONObject m = module(name);
        return m != null && m.optBoolean("enabled", false);
    }
    private String moduleScope(String name) {
        JSONObject m = module(name);
        return m == null ? null : m.optString("scope", null);
    }

    // ═══════════════════════════════════════════════════════
    //  Role display-only (لا bypass محلي)
    // ═══════════════════════════════════════════════════════
    private String role() { return sessionManager.getRoleString(); }

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
        String r = role(); if (r == null) return false;
        return ROLE_ADMIN.equalsIgnoreCase(r) || isSystemAdmin() || isOrgAdmin() || isFinanceManager();
    }
    public boolean isManager() {
        return isAdmin() || isBranchManager() || isInventoryManager();
    }
    public boolean isAccountingUser() {
        return isAccountant() || isFinanceManager() || isAuditor();
    }
    public boolean isGateUser() { return isGateSecurity() || isSecurity(); }

    // ═══════════════════════════════════════════════════════
    //  كل دوال canX() — تقرأ من الوثيقة (Server-Decided)
    // ═══════════════════════════════════════════════════════
    public boolean canPerformAccountingSetup()     { return moduleEnabled("accounting"); }
    public boolean canManageUsersAdvanced()        { return moduleEnabled("users"); }
    public boolean canViewDashboard()              { return true; }
    public boolean canViewProfile()                { return true; }

    public boolean canViewInventory()              { return moduleEnabled("inventory"); }
    public boolean canEditInventory()              { return canEdit(FEATURE_INVENTORY); }
    public boolean canDeleteInventory()            { return canDelete(FEATURE_INVENTORY); }

    public boolean canViewPos()                    { return moduleEnabled("pos"); }
    public boolean canUsePos()                     { return canEdit(FEATURE_POS); }

    public boolean canViewTransfers()              { return moduleEnabled("transfers"); }
    public boolean canCreateTransfer()             { return canEdit(FEATURE_TRANSFERS); }
    public boolean canApproveTransfer()            { return canEdit(FEATURE_TRANSFERS); }

    public boolean canViewIdentity()               { return moduleEnabled("inventory"); }
    public boolean canUseIdentity()                { return canEdit(FEATURE_IDENTITY); }

    public boolean canViewRadar()                  { return moduleEnabled("radar"); }

    public boolean canViewGates()                  { return moduleEnabled("gate"); }
    public boolean canManageGates()                { return canEdit(FEATURE_GATES); }

    public boolean canViewReports()                { return moduleEnabled("reports"); }
    public boolean canExportReports()              { return canExport(FEATURE_REPORTS); }

    public boolean canViewAccounting()             { return moduleEnabled("accounting"); }
    public boolean canEditAccounting()             { return canEdit(FEATURE_ACCOUNTING); }

    public boolean canViewAdminHub()               { return moduleEnabled("admin"); }
    public boolean canManageUsers()                { return moduleEnabled("users"); }
    public boolean canViewBranches()               { return moduleEnabled("branches"); }
    public boolean canManageBranches()             { return canEdit(FEATURE_BRANCHES); }

    public boolean canViewServerConfig()           { return moduleEnabled("settings"); }

    public boolean canViewGoldRates()              { return moduleEnabled("gold_rates"); }
    public boolean canEditGoldRates()              { return canEdit(FEATURE_GOLD_RATES); }

    public boolean canViewSalesHistory()           { return moduleEnabled("sales_history"); }
    public boolean canManageQuickAccess()          { return canEdit(FEATURE_QUICK_ACCESS); }

    public boolean canWriteRfid()                  { return moduleEnabled("rfid"); }
    public boolean canViewRfidBindings()           { return moduleEnabled("rfid"); }
    public boolean canViewReaderSetup()            { return moduleEnabled("reader_setup") || moduleEnabled("rfid"); }
    public boolean canViewSoundSettings()          { return true; }
    public boolean canViewSnapshots()              { return canViewInventory(); }

    public boolean canViewScrapGold()              { return moduleEnabled("scrap") || moduleEnabled("inventory"); }
    public boolean canBuyScrapGold()               { return canEdit(FEATURE_INVENTORY); }
    public boolean canViewScrapAging()             { return canViewScrapGold(); }

    public boolean canViewParties()                { return moduleEnabled("parties"); }
    public boolean canViewCashReconciliation()     { return moduleEnabled("accounting"); }
    public boolean canCreatePurchaseInvoice()      { return moduleEnabled("purchases"); }
    public boolean canViewAuditLogs()              { return moduleEnabled("audit"); }
    public boolean canViewGoldConversion()         { return true; }

    // Legacy aliases
    public boolean canAccessIdentityStudio() { return canUseIdentity(); }
    public boolean canAccessRadar()          { return canViewRadar(); }
    public boolean canAccessGate()           { return canViewGates(); }
    public boolean canAccessAccounting()     { return canViewAccounting(); }
    public boolean canAccessTransfers()      { return canViewTransfers(); }

    // ═══════════════════════════════════════════════════════
    //  Quick Actions — من الوثيقة
    // ═══════════════════════════════════════════════════════
    public List<String> getAllowedQuickActions() {
        List<String> allowed = new ArrayList<>();
        JSONObject qa = capabilities == null ? null : capabilities.optJSONObject("quick_actions");
        if (qa != null) {
            Iterator<String> keys = qa.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (qa.optBoolean(k, false)) allowed.add(k);
            }
            if (!allowed.isEmpty()) return allowed;
        }
        // fallback مشتق من fragments
        if (canAccessFragment("IdentityStudio"))  allowed.add("identity");
        if (canAccessFragment("Radar"))           allowed.add("radar");
        if (canAccessFragment("SecureGate"))      allowed.add("gate");
        if (canAccessFragment("Reports"))         allowed.add("reports");
        if (canAccessFragment("Accounting"))      allowed.add("accounting");
        if (canAccessFragment("Transfers"))       allowed.add("transfers");
        if (canAccessFragment("AdminHub"))        allowed.add("admin_hub");
        if (canAccessFragment("SalesHistory"))    allowed.add("sales_history");
        if (canAccessFragment("ServerConfig"))    allowed.add("server_config");
        if (canAccessFragment("GoldRates"))       allowed.add("gold_rates");
        if (canAccessFragment("UsersManagement")) allowed.add("users");
        if (canAccessFragment("Branches"))        allowed.add("branches");
        if (canAccessFragment("RFIDWrite"))       allowed.add("rfid_write");
        if (canAccessFragment("RfidBindings"))    allowed.add("rfid_bindings");
        return allowed;
    }

    // ═══════════════════════════════════════════════════════
    //  Dashboard widgets — من الوثيقة
    // ═══════════════════════════════════════════════════════
    private boolean widget(String key) {
        if (capabilities == null) return false;
        JSONObject w = capabilities.optJSONObject("dashboard_widgets");
        return w != null && w.optBoolean(key, false);
    }
    public boolean canViewDashboardHero()          { return widget("hero"); }
    public boolean canViewDashboardSales()         { return widget("sales"); }
    public boolean canViewDashboardGoldPrices()    { return canViewGoldRates(); }
    public boolean canViewDashboardInventoryByKarat(){ return canViewInventory(); }
    public boolean canViewDashboardScrap()         { return moduleEnabled("scrap") || moduleEnabled("inventory"); }
    public boolean canViewDashboardScrapAging()    { return canViewDashboardScrap(); }
    public boolean canViewDashboardCharts()        { return widget("charts"); }
    public boolean canViewDashboardAlerts()        { return widget("alerts"); }
    public boolean canViewDashboardRecentActivity(){ return true; }
    public boolean canRefreshGoldManually()        { return canEdit(FEATURE_GOLD_RATES); }
    public boolean canRevealSensitiveValues()      { return widget("sensitive"); }

    // ═══════════════════════════════════════════════════════
    //  Master Gate — canAccessFragment (No Local Logic)
    // ═══════════════════════════════════════════════════════
    public boolean canAccessFragment(String fragmentTag) {
        if (fragmentTag == null || fragmentTag.trim().isEmpty()) return false;
        final String tag = fragmentTag.trim();

        // 1) ما يقوله السيرفر صراحة
        if (fragments != null && fragments.has(tag)) {
            return fragments.optBoolean(tag, false);
        }

        // 2) اشتقاق من modules (لا مصفوفة أدوار — فقط module match)
        String mod = moduleForFragment(tag);
        if (mod != null) return moduleEnabled(mod);

        // 3) افتراضي: لا
        Log.w(TAG, "🚫 Unknown fragment denied (server-driven): " + tag);
        return false;
    }

    private String moduleForFragment(String tag) {
        switch (tag.toLowerCase()) {
            case "dashboard": case "home": case "homefragment":
            case "profile": case "soundsettings": case "notifications":
            case "receipt": case "goldconversion":
                return null; // مسموح دائماً (أدناه)
            case "serverconfig": return "settings";
            case "accountingsetup": return "accounting";
            case "readercontrol": case "readersetup": case "uhfset": return "reader_setup";
            case "scrapgoldbuy": case "mirascrapgoldbuy":
            case "scraphistory": case "mirascraphistory":
            case "scrapbatches": case "mirascrapbatches":
            case "scrapaging": case "mirascrapaging":
            case "scrapprofitreport":
            case "inventory": case "inventorypro": case "mirainventory":
            case "scan":
                return "inventory";
            case "parties": case "miraparties": return "parties";
            case "cashreconciliation": return "accounting";
            case "purchaseinvoice": return "purchases";
            case "auditlogs": return "audit";
            case "vouchers": return "accounting";
            case "pos": case "posfragment": return "pos";
            case "transfers": case "transferdetail": return "transfers";
            case "identitystudio": return "inventory";
            case "radar": case "locator": return "radar";
            case "securegate": case "gatesettings": case "gates": return "gate";
            case "reports": case "reportspro": return "reports";
            case "accounting": case "fiscalyears": case "openingbalances":
            case "financialreports": case "profitreport":
            case "accountingcharts": case "accountingauditlog":
                return "accounting";
            case "adminhub": return "admin";
            case "usersmanagement": return "users";
            case "goldrates": return "gold_rates";
            case "saleshistory": return "sales_history";
            case "quickaccesssettings": return "quick_access";
            case "branches": case "branchesmanagement": return "branches";
            case "rfidwrite": return "rfid_write";
            case "rfidbindings": return "rfid_bindings";
            case "rfidsettings": return "rfid";
            case "snapshots": return "inventory";
            case "nxpsecurity": return "admin";
            default:
                // ثوابت مسموحة
                return null;
        }
    }
}
