package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREF_NAME = "MiraEnterpriseSession";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY_NAME = "displayName";
    private static final String KEY_USER_ROLE = "userRole";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_SERVER_URL = "serverUrl";
    private static final String KEY_BIOMETRIC_ENABLED = "biometricEnabled";
    private static final String KEY_LAST_BIOMETRIC_USER = "lastBiometricUser";
    private static final String KEY_LAST_BIOMETRIC_ROLE = "lastBiometricRole";

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    public enum Role {
        ADMIN,
        POS_OPERATOR,
        INVENTORY_MGR,
        SECURITY,
        AUDITOR
    }

    public SessionManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    // ===================== إنشاء الجلسة =====================
    
    public void createLoginSession(String username, String role, String token, String serverUrl) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USERNAME, username);
        editor.putString(KEY_USER_ROLE, role);
        editor.putString(KEY_AUTH_TOKEN, token);
        editor.putString(KEY_SERVER_URL, serverUrl);
        editor.apply();
    }

    // ===================== استعلامات الجلسة =====================
    
    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public String getUserRole() {
        return pref.getString(KEY_USER_ROLE, "INVENTORY");
    }

    public String getUsername() {
        return pref.getString(KEY_USERNAME, "");
    }

    public String getAuthToken() {
        return pref.getString(KEY_AUTH_TOKEN, "");
    }

    // ===================== الاسم الظاهر =====================
    
    public void setDisplayName(String displayName) {
        editor.putString(KEY_DISPLAY_NAME, displayName);
        editor.apply();
    }

    public String getDisplayName() {
        String name = pref.getString(KEY_DISPLAY_NAME, "");
        return name.isEmpty() ? getUsername() : name;
    }

    // ===================== رابط الخادم =====================
    
    public String getServerUrl() {
        return pref.getString(KEY_SERVER_URL, "");
    }

    public void setServerUrl(String url) {
        editor.putString(KEY_SERVER_URL, url);
        editor.apply();
    }

    // ===================== البصمة =====================
    
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
        return pref.getString(KEY_LAST_BIOMETRIC_ROLE, "ADMIN");
    }

    // ===================== تسجيل الخروج =====================
    
    public void logoutUser() {
        boolean keepBiometric = pref.getBoolean(KEY_BIOMETRIC_ENABLED, false);
        String lastUser = pref.getString(KEY_LAST_BIOMETRIC_USER, "");
        String lastRole = pref.getString(KEY_LAST_BIOMETRIC_ROLE, "");
        
        editor.clear();
        
        // الاحتفاظ بإعدادات البصمة ورابط الخادم
        if (keepBiometric) {
            editor.putBoolean(KEY_BIOMETRIC_ENABLED, true);
            editor.putString(KEY_LAST_BIOMETRIC_USER, lastUser);
            editor.putString(KEY_LAST_BIOMETRIC_ROLE, lastRole);
        }
        
        editor.apply();
    }
}
