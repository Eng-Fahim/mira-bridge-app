package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PermissionManager v4.4 — Server-Driven & Live Sync
 * ═══════════════════════════════════════════════════════════════════
 *  متطابق 100% مع PermissionsNormalizer::MODULES (23 وحدة قياسية)
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final String PREFS_NAME = "MIRA_PERMISSIONS";
    private static final String KEY_CAPABILITIES = "capabilities_json";
    private static final String KEY_LEGACY       = "legacy_permissions_json";
    private static final String KEY_FRAGMENTS    = "fragments_json";
    private static final String KEY_VERSION      = "permissions_version";

    private String currentVersion = null;

    private static volatile PermissionManager instance;
    private final Context context;
    private final SessionManager sessionManager;
    private final SharedPreferences prefs;

    private JSONObject capabilities;
    private JSONObject legacyPermissions;
    private JSONObject fragments;

    // الأدوار — constants فقط (للعرض)
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

    public static final String FEATURE_DASHBOARD      = "dashboard";
    public static final String FEATURE_INVENTORY      = "inventory";
    public static final String FEATURE_POS            = "pos";
    public static final String FEATURE_SCRAP          = "scrap";
    public static final String FEATURE_TRANSFERS      = "transfers";
    public static final String FEATURE_IDENTITY       = "rfid";
    public static final String FEATURE_RADAR          = "radar";
    public static final String FEATURE_GATES          = "gate";
    public static final String FEATURE_REPORTS        = "reports";
    public static final String FEATURE_ACCOUNTING     = "accounting";
    public static final String FEATURE_ADMIN          = "admin";
    public static final String FEATURE_USERS          = "users";
    public static final String FEATURE_BRANCHES       = "branches";
    public static final String FEATURE_SERVER_CONFIG  = "settings";
    public static final String FEATURE_GOLD_RATES     = "gold_rates";
    public static final String FEATURE_PROFILE        = "profile";
    public static final String FEATURE_SALES_HISTORY  = "sales_history";
    public static final String FEATURE_QUICK_ACCESS   = "admin";
    public static final String FEATURE_RFID_WRITE     = "rfid";
    public static final String FEATURE_RFID_BINDINGS  = "rfid";
    public static final String FEATURE_READER_SETUP   = "setup";
    public static final String FEATURE_SOUND_SETTINGS = "sound_settings";
    public static final String FEATURE_SNAPSHOTS      = "inventory";
    public static final String FEATURE_PARTIES        = "parties";
    public static final String FEATURE_VOUCHERS       = "vouchers";
    public static final String FEATURE_CHEQUES        = "cheques";
    public static final String FEATURE_PURCHASES      = "purchases";
    public static final String FEATURE_AUDIT          = "audit";

    // ═══════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════
    private PermissionManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessionManager = new SessionManager(this.context);
        this.prefs = createEncryptedPrefs(this.context);
        loadFromPrefs();
    }

    private static SharedPreferences createEncryptedPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context, PREFS_NAME, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences failed, falling back", e);
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.deleteSharedPreferences(PREFS_NAME);
                }
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                return EncryptedSharedPreferences.create(
                        context, PREFS_NAME, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception ex) {
                return context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
            }
        }
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
    //  Storage
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

    public void saveFromServer(JSONObject fullResponseJson) {
        saveFromServerInternal(fullResponseJson);
    }

    private boolean saveFromServerInternal(JSONObject fullResponseJson) {
        if (fullResponseJson == null) return false;
        try {
            JSONObject data = fullResponseJson.optJSONObject("data");
            JSONObject legacy = parseJsonObjectField(fullResponseJson, "legacy_permissions");

            if (data != null && data.has("mobile_capabilities")) {
                JSONObject caps = parseJsonObjectField(data, "mobile_capabilities");
                if (caps != null) capabilities = caps;

                JSONObject perms = parseJsonObjectField(data, "permissions");
                if (perms != null) {
                    legacyPermissions = perms;
                } else {
                    JSONObject rootPerms = parseJsonObjectField(fullResponseJson, "permissions");
                    if (rootPerms != null) legacyPermissions = rootPerms;
                }
            } else if (data != null && data.has("modules")) {
                capabilities = data;
                if (legacy != null) legacyPermissions = legacy;
            } else {
                Log.w(TAG, "saveFromServer: ignoring response (no capabilities payload)");
                return false;
            }

            fragments = deriveFragments(capabilities);

            prefs.edit()
                 .putString(KEY_CAPABILITIES, capabilities.toString())
                 .putString(KEY_LEGACY,       legacyPermissions.toString())
                 .putString(KEY_FRAGMENTS,    fragments.toString())
                 .apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveFromServer error: " + e.getMessage());
            return false;
        }
    }

    private JSONObject parseJsonObjectField(JSONObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null;
        Object raw = parent.opt(key);
        if (raw instanceof JSONObject) {
            return (JSONObject) raw;
        }
        if (raw instanceof String) {
            String str = ((String) raw).trim();
            if (str.startsWith("{")) {
                try {
                    return new JSONObject(str);
                } catch (Exception e) {
                    Log.e(TAG, "Failed parsing JSON string for key: " + key, e);
                }
            }
        }
        return null;
    }

    @Deprecated
    public void savePermissions(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(permissionsJson);
            saveFromServer(root.has("success") || root.has("data")
                ? root : wrapLegacyOnly(root));
        } catch (Exception e) {
            Log.e(TAG, "savePermissions error: " + e.getMessage());
        }
    }

    @Deprecated
    public void savePermissions(JSONObject permissionsJson) {
        if (permissionsJson != null) savePermissions(permissionsJson.toString());
    }

    private JSONObject wrapLegacyOnly(JSONObject legacy) {
        try {
            JSONObject root = new JSONObject();
            root.put("data", new JSONObject());
            root.put("legacy_permissions", legacy);
            return root;
        } catch (Exception e) { return new JSONObject(); }
    }

    // ═══════════════════════════════════════════════════════
    //  Fragments — مشتقة بدقة من PermissionsNormalizer::MODULES
    // ═══════════════════════════════════════════════════════
    private JSONObject deriveFragments(JSONObject caps) {
        JSONObject out = new JSONObject();
        if (caps == null) return out;
        JSONObject modules = caps.optJSONObject("modules");
        if (modules == null) return out;

        String[][] map = {
            // المحاسبة والمالية
            {"Accounting", "accounting"}, {"FiscalYears", "accounting"},
            {"OpeningBalances", "accounting"}, {"FinancialReports", "accounting"},
            {"ProfitReport", "accounting"}, {"AccountingCharts", "accounting"},
            {"AccountingAuditLog", "accounting"}, {"AccountingSetup", "accounting"},
            {"CashReconciliation", "accounting"},
            {"Vouchers", "vouchers"},
            {"Cheques", "cheques"},

            // نقاط البيع والمبيعات
            {"POS", "pos"}, {"PosFragment", "pos"},
            {"SalesHistory", "sales_history"},

            // المخزون والتحويلات والصياغة
            {"Inventory", "inventory"}, {"InventoryPro", "inventory"},
            {"MiraInventory", "inventory"}, {"Scan", "inventory"},
            {"Snapshots", "inventory"}, {"GoldConversion", "inventory"},
            {"Transfers", "transfers"}, {"TransferDetail", "transfers"},

            // الذهب الكسر (مربوط بوحدة scrap المستقلة)
            {"ScrapGoldBuy", "scrap"}, {"MiraScrapGoldBuy", "scrap"},
            {"ScrapHistory", "scrap"}, {"MiraScrapHistory", "scrap"},
            {"ScrapBatches", "scrap"}, {"MiraScrapBatches", "scrap"},
            {"ScrapAging", "scrap"}, {"MiraScrapAging", "scrap"},
            {"ScrapProfitReport", "scrap"},

            // المشتريات والعملاء والأسعار
            {"PurchaseInvoice", "purchases"},
            {"Parties", "parties"}, {"MiraParties", "parties"},
            {"GoldRates", "gold_rates"},

            // RFID وتهيئة القارئ (مربوطة بوحدتي rfid و setup)
            {"IdentityStudio", "rfid"},
            {"MiraIdentityStudio", "rfid"},
            {"RFIDWrite", "rfid"},
            {"RfidBindings", "rfid"},
            {"ReaderSetup", "setup"}, {"UHFSet", "setup"},
            {"ReaderControl", "setup"},

            // الرادار والبوابات
            {"Radar", "radar"}, {"Locator", "radar"},
            {"SecureGate", "gate"}, {"GateSettings", "gate"}, {"Gates", "gate"},

            // التقارير والتدقيق والإدارة
            {"Reports", "reports"}, {"ReportsPro", "reports"},
            {"AuditLogs", "audit"},
            {"AdminHub", "admin"},
            {"QuickAccessSettings", "admin"},
            {"UsersManagement", "users"},
            {"Branches", "branches"}, {"BranchesManagement", "branches"},
            {"ServerConfig", "settings"},

            // الشاشات الأساسية
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

        // شاشة استوديو التكويد تعمل بصلاحية rfid أو صلاحية تشغيل/تعديل المخزون inventory
        JSONObject rfidMod = modules.optJSONObject("rfid");
        JSONObject invMod  = modules.optJSONObject("inventory");
        boolean canIdentity = (rfidMod != null && rfidMod.optBoolean("enabled", false))
                || (invMod != null && invMod.optBoolean("enabled", false)
                    && ("operate".equals(invMod.optString("scope"))
                        || "write".equals(invMod.optString("scope"))
                        || "full".equals(invMod.optString("scope"))));
        if (canIdentity) {
            try {
                out.put("IdentityStudio", true);
                out.put("MiraIdentityStudio", true);
            } catch (Exception ignored) {}
        }

        // شاشات ونوافذ عامة لكل مستخدم مسجّل دخول
        try {
            out.put("Dashboard", true);
            out.put("Home", true);
            out.put("HomeFragment", true);
            out.put("Profile", true);
            out.put("Notifications", true);
            out.put("SoundSettings", true);
            out.put("Receipt", true);
            out.put("QuickActions", true);
        } catch (Exception ignored) {}

        // تجاوز صريح من السيرفر إن وُجد
        JSONObject serverFragments = caps.optJSONObject("fragments");
        if (serverFragments != null) {
            Iterator<String> keys = serverFragments.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    out.put(key, serverFragments.optBoolean(key, false));
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════
    //  Module helper — المصدر الوحيد للحقيقة
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

    private boolean fragmentEnabled(String key) {
        return fragments != null && fragments.optBoolean(key, false);
    }

    private boolean scopeAllows(String moduleName, String action) {
        JSONObject m = module(moduleName);
        if (m == null || !m.optBoolean("enabled", false)) return false;

        // 1. أولوية للتخصيصات الفردية القادمة من السيرفر داخل actions
        JSONObject actions = m.optJSONObject("actions");
        if (actions != null && actions.has(action)) {
            return actions.optBoolean(action, false);
        }

        // 2. الاشتقاق الافتراضي من scope للدور
        String scope = m.optString("scope", "view");

        switch (action) {
            case "view":
                return true;
            case "create":
            case "edit":
            case "write":
            case "operate":
                return "operate".equals(scope) || "write".equals(scope) || "full".equals(scope);
            case "delete":
                return "full".equals(scope);
            case "export":
                return "full".equals(scope) || "view_all".equals(scope);
            default:
                return false;
        }
    }

    public boolean canRefund() {
        return hasPermission(FEATURE_POS, "refund")
            || hasPermission(FEATURE_SALES_HISTORY, "refund");
    }

    public boolean hasPermission(String feature, String action) {
        if (scopeAllows(feature, action)) return true;

        if (legacyPermissions != null) {
            JSONObject f = legacyPermissions.optJSONObject(feature);
            if (f != null && f.optBoolean(action, false)) return true;
        }
        return false;
    }

    public boolean canView(String feature) {
        return hasPermission(feature, "view")
            || hasPermission(feature, "edit")
            || hasPermission(feature, "delete");
    }

    public boolean canEdit(String feature) {
        return hasPermission(feature, "edit") || hasPermission(feature, "delete");
    }

    public boolean canDelete(String feature) {
        return hasPermission(feature, "delete");
    }

    public boolean canExport(String feature) {
        return hasPermission(feature, "export") || canView(feature);
    }

    // ═══════════════════════════════════════════════════════
    //  Role checks — للعرض فقط
    // ═══════════════════════════════════════════════════════
    public String getRoleForDisplay() {
        return sessionManager.getRoleString();
    }

    public boolean isSystemAdmin()    { return moduleEnabled("admin"); }
    public boolean isOrgAdmin()       { return moduleEnabled("admin"); }
    public boolean isFinanceManager() { return moduleEnabled("accounting"); }
    public boolean isInventoryManager(){ return moduleEnabled("inventory"); }
    public boolean isBranchManager()  { return moduleEnabled("branches"); }
    public boolean isAccountant()     { return moduleEnabled("accounting"); }
    public boolean isAuditor()        { return moduleEnabled("audit"); }
    public boolean isPosOperator()    { return moduleEnabled("pos"); }
    public boolean isSecurity()       { return moduleEnabled("gate"); }
    public boolean isGateSecurity()   { return moduleEnabled("gate"); }

    public boolean isAdmin() {
        return moduleEnabled("admin");
    }
    public boolean isManager() {
        return moduleEnabled("admin") || moduleEnabled("branches") || moduleEnabled("inventory");
    }
    public boolean isAccountingUser() {
        return moduleEnabled("accounting") || moduleEnabled("audit");
    }
    public boolean isGateUser() {
        return moduleEnabled("gate");
    }

    // ═══════════════════════════════════════════════════════
    //  canX() — كلها من modules (السيرفر يقرر)
    // ═══════════════════════════════════════════════════════
    public boolean canPerformAccountingSetup()     { return scopeAllows("accounting", "edit"); }
    public boolean canManageUsersAdvanced()        { return scopeAllows("users", "edit"); }
    public boolean canViewDashboard()              { return true; }
    public boolean canViewProfile()                { return true; }

    public boolean canViewInventory()              { return moduleEnabled("inventory"); }
    public boolean canEditInventory()              { return scopeAllows("inventory", "edit"); }
    public boolean canDeleteInventory()            { return scopeAllows("inventory", "delete"); }

    public boolean canViewPos()                    { return moduleEnabled("pos"); }
    public boolean canUsePos()                     { return scopeAllows("pos", "operate"); }

    public boolean canViewTransfers()              { return moduleEnabled("transfers"); }
    public boolean canCreateTransfer()             { return scopeAllows("transfers", "edit"); }
    public boolean canApproveTransfer()            { return scopeAllows("transfers", "edit"); }

    public boolean canViewIdentity()               { return moduleEnabled("rfid") || scopeAllows("inventory", "operate"); }
    public boolean canUseIdentity()                { return scopeAllows("rfid", "edit") || scopeAllows("inventory", "operate"); }

    public boolean canViewRadar()                  { return moduleEnabled("radar"); }

    public boolean canViewGates()                  { return moduleEnabled("gate"); }
    public boolean canManageGates()                { return scopeAllows("gate", "edit"); }

    public boolean canViewReports()                { return moduleEnabled("reports"); }
    public boolean canExportReports()              { return scopeAllows("reports", "export"); }

    public boolean canViewAccounting()             { return moduleEnabled("accounting"); }
    public boolean canEditAccounting()             { return scopeAllows("accounting", "edit"); }

    public boolean canViewAdminHub()               { return moduleEnabled("admin"); }
    public boolean canManageUsers()                { return scopeAllows("users", "edit"); }
    public boolean canViewBranches()               { return moduleEnabled("branches"); }
    public boolean canManageBranches()             { return scopeAllows("branches", "edit"); }

    public boolean canViewServerConfig()           { return moduleEnabled("settings"); }

    public boolean canViewGoldRates()              { return moduleEnabled("gold_rates"); }
    public boolean canEditGoldRates()              { return scopeAllows("gold_rates", "edit"); }

    public boolean canViewSalesHistory()           { return moduleEnabled("sales_history"); }
    public boolean canManageQuickAccess()          { return scopeAllows("admin", "edit") || scopeAllows("settings", "edit"); }

    public boolean canViewAllBranches()            { return fragmentEnabled("SalesHistoryAllBranches"); }

    public boolean canWriteRfid()                  { return scopeAllows("rfid", "edit"); }
    public boolean canViewRfidBindings()           { return moduleEnabled("rfid"); }
    public boolean canViewReaderSetup()            { return moduleEnabled("setup") || moduleEnabled("rfid"); }
    public boolean canViewSoundSettings()          { return true; }
    public boolean canViewSnapshots()              { return canViewInventory(); }

    public boolean canViewScrapGold()              { return moduleEnabled("scrap"); }
    public boolean canBuyScrapGold()               { return scopeAllows("scrap", "edit"); }
    public boolean canViewScrapAging()             { return canViewScrapGold(); }

    public boolean canViewParties()                { return moduleEnabled("parties"); }
    public boolean canViewCashReconciliation()     { return moduleEnabled("accounting"); }
    public boolean canCreatePurchaseInvoice()      { return scopeAllows("purchases", "edit"); }
    public boolean canViewAuditLogs()              { return moduleEnabled("audit"); }
    public boolean canViewGoldConversion()         { return scopeAllows("inventory", "edit"); }

    // Legacy aliases
    public boolean canAccessIdentityStudio() { return canUseIdentity(); }
    public boolean canAccessRadar()          { return canViewRadar(); }
    public boolean canAccessGate()           { return canViewGates(); }
    public boolean canAccessAccounting()     { return canViewAccounting(); }
    public boolean canAccessTransfers()      { return canViewTransfers(); }

    // ═══════════════════════════════════════════════════════
    //  Quick Actions
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
    //  Dashboard widgets — من modules.dashboard.variant
    // ═══════════════════════════════════════════════════════
    private String dashboardVariant() {
        JSONObject d = module("dashboard");
        return d == null ? "pos" : d.optString("variant", "pos");
    }

    public boolean canViewDashboardHero() {
        String v = dashboardVariant();
        return "full".equals(v) || "hero_inventory".equals(v);
    }
    public boolean canViewDashboardSales() {
        String v = dashboardVariant();
        return "full".equals(v) || "pos".equals(v);
    }
    public boolean canViewDashboardGoldPrices()    { return canViewGoldRates(); }
    public boolean canViewDashboardInventoryByKarat(){ return canViewInventory(); }
    public boolean canViewDashboardScrap()         { return moduleEnabled("scrap") || moduleEnabled("inventory"); }
    public boolean canViewDashboardScrapAging()    { return canViewDashboardScrap(); }
    public boolean canViewDashboardCharts() {
        String v = dashboardVariant();
        return "full".equals(v) || "hero_inventory".equals(v);
    }
    public boolean canViewDashboardAlerts() {
        String v = dashboardVariant();
        return "full".equals(v) || "hero_inventory".equals(v);
    }
    public boolean canViewDashboardRecentActivity(){ return true; }
    public boolean canRefreshGoldManually()        { return scopeAllows("gold_rates", "edit"); }
    public boolean canRevealSensitiveValues() {
        String v = dashboardVariant();
        return "full".equals(v) || "hero_inventory".equals(v);
    }

    // ═══════════════════════════════════════════════════════
    //  Live Refresh + Listeners
    // ═══════════════════════════════════════════════════════
    public interface OnPermissionsChangedListener {
        void onPermissionsChanged();
    }

    private final java.util.concurrent.CopyOnWriteArrayList<OnPermissionsChangedListener> listeners
            = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addListener(OnPermissionsChangedListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(OnPermissionsChangedListener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (OnPermissionsChangedListener l : listeners) {
            try { l.onPermissionsChanged(); }
            catch (Throwable t) { Log.e(TAG, "listener error", t); }
        }
    }

    public String getStoredVersion() {
        if (currentVersion == null) {
            currentVersion = prefs.getString(KEY_VERSION, "");
        }
        return currentVersion == null ? "" : currentVersion;
    }

    public boolean needsRefresh(String serverVersion) {
        if (serverVersion == null || serverVersion.isEmpty()) return false;
        String local = getStoredVersion();
        if (local.isEmpty()) return true;
        return !local.equals(serverVersion);
    }

    public boolean applyFromServer(JSONObject fullResponseJson, String serverVersion) {
        if (fullResponseJson == null) return false;

        String oldVersion = getStoredVersion();

        boolean saved = saveFromServerInternal(fullResponseJson);
        if (!saved) {
            Log.w(TAG, "applyFromServer: save failed, NOT updating version");
            return false;
        }

        boolean versionChanged = false;
        if (serverVersion != null && !serverVersion.isEmpty()) {
            versionChanged = !serverVersion.equals(oldVersion);
            currentVersion = serverVersion;
            prefs.edit().putString(KEY_VERSION, serverVersion).apply();
        }

        if (versionChanged) {
            Log.i(TAG, "🔔 Permissions changed — notifying " + listeners.size() + " listener(s)");
            notifyListeners();
        }
        return versionChanged;
    }

    public void clearOnLogout() {
        clearPermissions();
        currentVersion = null;
        prefs.edit().remove(KEY_VERSION).apply();
        notifyListeners();
    }

    @Deprecated
    public void clearPermissions() {
        capabilities = new JSONObject();
        legacyPermissions = new JSONObject();
        fragments = new JSONObject();
        currentVersion = null;
        prefs.edit().remove(KEY_CAPABILITIES)
                    .remove(KEY_LEGACY)
                    .remove(KEY_FRAGMENTS)
                    .remove(KEY_VERSION).apply();
    }

    // ═══════════════════════════════════════════════════════
    //  Master Gate
    // ═══════════════════════════════════════════════════════
    public boolean canAccessFragment(String fragmentTag) {
        if (fragmentTag == null || fragmentTag.trim().isEmpty()) return false;
        final String tag = fragmentTag.trim();

        if ("IdentityStudio".equalsIgnoreCase(tag) || "MiraIdentityStudio".equalsIgnoreCase(tag)) {
            return canViewIdentity();
        }
        if ("QuickActions".equalsIgnoreCase(tag) || "Receipt".equalsIgnoreCase(tag)) {
            return true;
        }

        if (fragments != null && fragments.has(tag)) {
            return fragments.optBoolean(tag, false);
        }

        String mod = moduleForFragment(tag);
        if (mod != null) return moduleEnabled(mod);

        Log.w(TAG, "🚫 Unknown fragment denied (server-driven): " + tag);
        return false;
    }

    private String moduleForFragment(String tag) {
        switch (tag.toLowerCase()) {
            case "dashboard": case "home": case "homefragment":
            case "profile": case "soundsettings": case "notifications":
            case "receipt": case "quickactions":
                return null;
            case "serverconfig": return "settings";
            case "accountingsetup": return "accounting";
            case "cashreconciliation": return "accounting";
            case "readercontrol": case "readersetup": case "uhfset": return "setup";
            case "scrapgoldbuy": case "mirascrapgoldbuy":
            case "scraphistory": case "mirascraphistory":
            case "scrapbatches": case "mirascrapbatches":
            case "scrapaging": case "mirascrapaging":
            case "scrapprofitreport":
                return "scrap";
            case "inventory": case "inventorypro": case "mirainventory":
            case "scan": case "snapshots": case "goldconversion":
                return "inventory";
            case "parties": case "miraparties": return "parties";
            case "purchaseinvoice": return "purchases";
            case "auditlogs": return "audit";
            case "vouchers": return "vouchers";
            case "cheques": return "cheques";
            case "pos": case "posfragment": return "pos";
            case "transfers": case "transferdetail": return "transfers";
            case "identitystudio": case "miraidentitystudio":
            case "rfidwrite": case "rfidbindings": case "rfidsettings":
                return "rfid";
            case "radar": case "locator": return "radar";
            case "securegate": case "gatesettings": case "gates": return "gate";
            case "reports": case "reportspro": return "reports";
            case "accounting": case "fiscalyears": case "openingbalances":
            case "financialreports": case "profitreport":
            case "accountingcharts": case "accountingauditlog":
                return "accounting";
            case "adminhub": case "quickaccesssettings": case "nxpsecurity":
                return "admin";
            case "usersmanagement": return "users";
            case "goldrates": return "gold_rates";
            case "saleshistory": return "sales_history";
            case "branches": case "branchesmanagement": return "branches";
            default:
                return null;
        }
    }
}
