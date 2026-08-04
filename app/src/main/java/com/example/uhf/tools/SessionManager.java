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

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    // ✅ هيكل الصلاحيات - مستويان للإدارة
    public enum Role {
        SYSTEM_ADMIN,    // 🔧 مهندس النظام - وصول كامل لكل شيء
        ORG_ADMIN,       // 🏢 مدير الجهة - إدارة المستخدمين والمخزون
        INVENTORY_MGR,   // 📊 مدير المخزون
        POS_OPERATOR,    // 🛒 موظف مبيعات
        SECURITY,        // 🔒 أمن
        GATE_SECURITY,   // 🚪 أمن البوابات
        AUDITOR          // 🔍 مدقق
    }

    public SessionManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    // ==================== إنشاء الجلسة ====================

    public void createLoginSession(String username, String role, String token, String serverUrl) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USERNAME, username);
        editor.putString(KEY_USER_ROLE, role);
        editor.putString(KEY_AUTH_TOKEN, token);
        editor.putString(KEY_SERVER_URL, serverUrl);
        editor.apply();
    }

    // ==================== استعلامات الجلسة ====================

    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    /**
     * ✅ getUserRole ترجع Role مباشرة
     */
    public Role getUserRole() {
        String roleStr = pref.getString(KEY_USER_ROLE, "INVENTORY_MGR");
        try {
            return Role.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            return Role.INVENTORY_MGR;
        }
    }

    /**
     * ✅ getRoleString للاستخدامات النصية
     */
    public String getRoleString() {
        return pref.getString(KEY_USER_ROLE, "INVENTORY_MGR");
    }

    /**
     * ✅ التحقق إذا كان المستخدم مدير نظام
     */
    public boolean isSystemAdmin() {
        return "SYSTEM_ADMIN".equals(getRoleString());
    }

    /**
     * ✅ التحقق إذا كان المستخدم مدير جهة أو أعلى
     */
    public boolean isOrgAdminOrHigher() {
        String role = getRoleString();
        return "SYSTEM_ADMIN".equals(role) || "ORG_ADMIN".equals(role);
    }

    /**
     * ✅ التحقق إذا كان المستخدم مدير (بأي مستوى)
     */
    public boolean isAnyAdmin() {
        String role = getRoleString();
        return "SYSTEM_ADMIN".equals(role) || "ORG_ADMIN".equals(role);
    }

    public String getUsername() {
        return pref.getString(KEY_USERNAME, "");
    }

    public String getUserName() {
        return pref.getString(KEY_USERNAME, "");
    }

    public String getAuthToken() {
        return pref.getString(KEY_AUTH_TOKEN, "");
    }

    // ==================== userId ====================

    public void setUserId(int userId) {
        editor.putInt(KEY_USER_ID, userId);
        editor.apply();
    }

    public int getUserId() {
        return pref.getInt(KEY_USER_ID, 0);
    }

    // ==================== displayName ====================

    public void setDisplayName(String displayName) {
        editor.putString(KEY_DISPLAY_NAME, displayName);
        editor.apply();
    }

    public String getDisplayName() {
        String name = pref.getString(KEY_DISPLAY_NAME, "");
        return name.isEmpty() ? getUsername() : name;
    }

    // ==================== رابط الخادم ====================

    public String getServerUrl() {
        return pref.getString(KEY_SERVER_URL, "");
    }

    public void setServerUrl(String url) {
        editor.putString(KEY_SERVER_URL, url);
        editor.apply();
    }

    // ==================== البصمة ====================

    public void setBiometricEnabled(boolean enabled) {
        editor.putBoolean(KEY_BIOMETRIC_ENABLED, enabled);
        editor.apply();
    }

    public boolean isBiometricEnabled() {
        return pref.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public void saveBiometricUser(String username, String role) {
        editor.putString(KEY_LAST_BIOMETRIC_USER, username);
        editor.putString(KEY_LAST_BIOMETRIC_ROLE, role);
        editor.apply();
    }

    public String getLastBiometricUser() {
        return pref.getString(KEY_LAST_BIOMETRIC_USER, "");
    }

    public String getLastBiometricRole() {
        return pref.getString(KEY_LAST_BIOMETRIC_ROLE, "INVENTORY_MGR");
    }

    // ==================== تسجيل الخروج ====================

    public void logoutUser() {
        boolean keepBiometric = pref.getBoolean(KEY_BIOMETRIC_ENABLED, false);
        String lastUser = pref.getString(KEY_LAST_BIOMETRIC_USER, "");
        String lastRole = pref.getString(KEY_LAST_BIOMETRIC_ROLE, "");
        
        editor.clear();
        
        if (keepBiometric) {
            editor.putBoolean(KEY_BIOMETRIC_ENABLED, true);
            editor.putString(KEY_LAST_BIOMETRIC_USER, lastUser);
            editor.putString(KEY_LAST_BIOMETRIC_ROLE, lastRole);
        }
        
        editor.apply();
    }

    public void logout() {
        logoutUser();
    }
}
