package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREF_NAME = "MiraEnterpriseSession";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY_NAME = "displayName";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USER_ROLE = "userRole";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_SERVER_URL = "serverUrl";
    private static final String KEY_BIOMETRIC_ENABLED = "biometricEnabled";
    private static final String KEY_LAST_BIOMETRIC_USER = "lastBiometricUser";
    private static final String KEY_LAST_BIOMETRIC_ROLE = "lastBiometricRole";
    private static final String KEY_LAST_BIOMETRIC_DISPLAY_NAME = "lastBiometricDisplayName";
    private static final String KEY_LAST_BIOMETRIC_USER_ID = "lastBiometricUserId";
    private static final String KEY_LAST_BIOMETRIC_TOKEN = "lastBiometricToken";
    private static final String KEY_LAST_BIOMETRIC_BRANCH_ID = "lastBiometricBranchId";
    private static final String KEY_LAST_BIOMETRIC_BRANCH_NAME = "lastBiometricBranchName";
    
    // ✅ إضافة متغيرات API Key
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_API_KEY_ID = "apiKeyId";
    private static final String KEY_API_KEY_PREFIX = "apiKeyPrefix";
    private static final String KEY_API_KEY_CREATED_AT = "apiKeyCreatedAt";
    private static final String KEY_GATE_ID = "gate_id";
    
    // ✅ إضافة متغير لتذكر اسم المستخدم
    private static final String KEY_LAST_USERNAME = "lastUsername";

    public enum Role {
        SYSTEM_ADMIN,
        ORG_ADMIN,
        ADMIN,
        BRANCH_MANAGER,
        INVENTORY_MGR,
        POS_OPERATOR,
        SECURITY,
        GATE_SECURITY,
        AUDITOR
    }

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    public SessionManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    public void createLoginSession(String username, String role, String token, String serverUrl) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USERNAME, username);
        editor.putString(KEY_USER_ROLE, role);
        editor.putString(KEY_AUTH_TOKEN, token);
        editor.putString(KEY_SERVER_URL, serverUrl);
        editor.apply();
    }

    // ==================== API Key ====================
    
    public void saveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            editor.putString(KEY_API_KEY, apiKey);
            editor.apply();
        }
    }
    
    public String getApiKey() {
        return pref.getString(KEY_API_KEY, "");
    }
    
    public boolean hasApiKey() {
        String apiKey = getApiKey();
        return !apiKey.isEmpty();
    }
    
    public void saveGateId(String gateId) {
        pref.edit().putString(KEY_GATE_ID, gateId).apply();
    }

    public String getGateId() {
        return pref.getString(KEY_GATE_ID, "");
    }
    
    public void saveApiKeyInfo(String apiKey, String keyId, String keyPrefix, String createdAt) {
        editor.putString(KEY_API_KEY, apiKey);
        editor.putString(KEY_API_KEY_ID, keyId);
        editor.putString(KEY_API_KEY_PREFIX, keyPrefix);
        editor.putString(KEY_API_KEY_CREATED_AT, createdAt);
        editor.apply();
    }
    
    public String getApiKeyId() {
        return pref.getString(KEY_API_KEY_ID, "");
    }
    
    public String getApiKeyPrefix() {
        return pref.getString(KEY_API_KEY_PREFIX, "");
    }
    
    public void clearApiKey() {
        editor.remove(KEY_API_KEY);
        editor.remove(KEY_API_KEY_ID);
        editor.remove(KEY_API_KEY_PREFIX);
        editor.remove(KEY_API_KEY_CREATED_AT);
        editor.remove(KEY_GATE_ID);
        editor.apply();
    }

    // ==================== تذكر اسم المستخدم ====================
    
    /**
     * حفظ آخر اسم مستخدم تم تسجيل الدخول به بنجاح
     */
    public void saveLastUsername(String username) {
        if (username != null && !username.isEmpty()) {
            editor.putString(KEY_LAST_USERNAME, username);
            editor.apply();
        }
    }
    
    /**
     * استرجاع آخر اسم مستخدم تم استخدامه
     */
    public String getLastUsername() {
        return pref.getString(KEY_LAST_USERNAME, "");
    }
    
    /**
     * مسح اسم المستخدم المحفوظ
     */
    public void clearLastUsername() {
        editor.remove(KEY_LAST_USERNAME);
        editor.apply();
    }

    public boolean isLoggedIn() { 
        return pref.getBoolean(KEY_IS_LOGGED_IN, false); 
    }

    public Role getUserRole() {
        String roleStr = pref.getString(KEY_USER_ROLE, "INVENTORY_MGR");
        try { 
            return Role.valueOf(roleStr); 
        } catch (IllegalArgumentException e) { 
            return Role.INVENTORY_MGR; 
        }
    }

    public String getRoleString() { 
        return pref.getString(KEY_USER_ROLE, "INVENTORY_MGR"); 
    }

    public String getRole() { 
        return getRoleString(); 
    }

    public void setRole(String role) { 
        editor.putString(KEY_USER_ROLE, role); 
        editor.apply(); 
    }

    public boolean isSystemAdmin() { 
        return "SYSTEM_ADMIN".equals(getRoleString()); 
    }

    public boolean isAnyAdmin() {
        String role = getRoleString();
        return "SYSTEM_ADMIN".equals(role) || "ORG_ADMIN".equals(role) || "ADMIN".equals(role);
    }

    public boolean isAdmin() {
        return isAnyAdmin();
    }

    public boolean isManager() {
        String role = getRoleString();
        return "SYSTEM_ADMIN".equals(role) || "ORG_ADMIN".equals(role) || "ADMIN".equals(role) || "BRANCH_MANAGER".equals(role);
    }

    public String getUsername() { return pref.getString(KEY_USERNAME, ""); }
    public String getUserName() { return pref.getString(KEY_USERNAME, ""); }
    public String getAuthToken() { return pref.getString(KEY_AUTH_TOKEN, ""); }

    public void setUserId(int userId) { editor.putInt(KEY_USER_ID, userId); editor.apply(); }
    public int getUserId() { return pref.getInt(KEY_USER_ID, 0); }

    public void setDisplayName(String displayName) { editor.putString(KEY_DISPLAY_NAME, displayName); editor.apply(); }
    public String getDisplayName() {
        String name = pref.getString(KEY_DISPLAY_NAME, "");
        return name.isEmpty() ? getUsername() : name;
    }

    public String getServerUrl() { return pref.getString(KEY_SERVER_URL, ""); }
    public void setServerUrl(String url) { editor.putString(KEY_SERVER_URL, url); editor.apply(); }

    public String getPhone() { 
        return pref.getString("phone", ""); 
    }
    public void setPhone(String phone) { 
        editor.putString("phone", phone); 
        editor.apply(); 
    }

    public void setBiometricEnabled(boolean enabled) { editor.putBoolean(KEY_BIOMETRIC_ENABLED, enabled); editor.apply(); }
    public boolean isBiometricEnabled() { return pref.getBoolean(KEY_BIOMETRIC_ENABLED, false); }

    public void setBiometricLoginEnabled(boolean enabled) { 
        setBiometricEnabled(enabled); 
    }
    public boolean isBiometricLoginEnabled() { 
        return isBiometricEnabled(); 
    }
    public void setBiometricConfirmEnabled(boolean enabled) { 
        editor.putBoolean("biometric_confirm_enabled", enabled); 
        editor.apply(); 
    }
    public boolean isBiometricConfirmEnabled() { 
        return pref.getBoolean("biometric_confirm_enabled", false); 
    }

    public void saveBiometricUser(String username, String role, String displayName, int userId, String token) {
        editor.putString(KEY_LAST_BIOMETRIC_USER, username);
        editor.putString(KEY_LAST_BIOMETRIC_ROLE, role);
        editor.putString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, displayName);
        editor.putInt(KEY_LAST_BIOMETRIC_USER_ID, userId);
        editor.putString(KEY_LAST_BIOMETRIC_TOKEN, token);
        editor.apply();
    }

    public String getLastBiometricUser() { return pref.getString(KEY_LAST_BIOMETRIC_USER, ""); }
    public String getLastBiometricRole() { return pref.getString(KEY_LAST_BIOMETRIC_ROLE, "INVENTORY_MGR"); }
    public String getLastBiometricDisplayName() { return pref.getString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, ""); }
    public int getLastBiometricUserId() { return pref.getInt(KEY_LAST_BIOMETRIC_USER_ID, 0); }
    public String getLastBiometricToken() { return pref.getString(KEY_LAST_BIOMETRIC_TOKEN, ""); }

    public void saveBiometricBranch(int branchId, String branchName) {
        editor.putInt(KEY_LAST_BIOMETRIC_BRANCH_ID, branchId);
        editor.putString(KEY_LAST_BIOMETRIC_BRANCH_NAME, branchName);
        editor.apply();
    }

    public int getLastBiometricBranchId() { return pref.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, 1); }
    public String getLastBiometricBranchName() { return pref.getString(KEY_LAST_BIOMETRIC_BRANCH_NAME, ""); }

    public void refreshProfileData() {
        // يمكن إضافة منطق إضافي هنا إذا لزم
    }

    public void logoutUser() {
    // ✅ حفظ القيم التي يجب أن تبقى
    boolean keepBiometric = pref.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    String lastUser = pref.getString(KEY_LAST_BIOMETRIC_USER, "");
    String lastRole = pref.getString(KEY_LAST_BIOMETRIC_ROLE, "");
    String lastDisplayName = pref.getString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, "");
    int lastUserId = pref.getInt(KEY_LAST_BIOMETRIC_USER_ID, 0);
    String lastToken = pref.getString(KEY_LAST_BIOMETRIC_TOKEN, "");
    int lastBranchId = pref.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, 1);
    String lastBranchName = pref.getString(KEY_LAST_BIOMETRIC_BRANCH_NAME, "");
    String apiKey = pref.getString(KEY_API_KEY, "");
    String gateId = pref.getString(KEY_GATE_ID, "");
    String lastUsername = pref.getString(KEY_LAST_USERNAME, "");
    String serverUrl = pref.getString(KEY_SERVER_URL, ""); // ✅ أضف هذا السطر

    editor.clear();

    if (keepBiometric) {
        editor.putBoolean(KEY_BIOMETRIC_ENABLED, true);
        editor.putString(KEY_LAST_BIOMETRIC_USER, lastUser);
        editor.putString(KEY_LAST_BIOMETRIC_ROLE, lastRole);
        editor.putString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, lastDisplayName);
        editor.putInt(KEY_LAST_BIOMETRIC_USER_ID, lastUserId);
        editor.putString(KEY_LAST_BIOMETRIC_TOKEN, lastToken);
        editor.putInt(KEY_LAST_BIOMETRIC_BRANCH_ID, lastBranchId);
        editor.putString(KEY_LAST_BIOMETRIC_BRANCH_NAME, lastBranchName);
    }
    
    if (!apiKey.isEmpty()) {
        editor.putString(KEY_API_KEY, apiKey);
    }
    if (!gateId.isEmpty()) {
        editor.putString(KEY_GATE_ID, gateId);
    }
    if (!lastUsername.isEmpty()) {
        editor.putString(KEY_LAST_USERNAME, lastUsername);
    }
    if (!serverUrl.isEmpty()) {
        editor.putString(KEY_SERVER_URL, serverUrl); // ✅ أضف هذا السطر
    }

    editor.apply();
}

    public void logout() { logoutUser(); }
}
