package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREF_NAME = "MIRA_SECURE_SESSION";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_SERVER_URL = "server_url";

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    // الأدوار المتاحة للنظام (System Roles)
    public enum Role {
        ADMIN,          // وصول كامل لجميع التبويبات
        POS_OPERATOR,   // مبيعات فقط (POS + Scan)
        INVENTORY_MGR,  // الجرد والرادار والتتبع فقط
        GATE_SECURITY   // بوابات الحماية فقط
    }

    public SessionManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    public void createLoginSession(String username, String role, String token, String serverUrl) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USER_NAME, username);
        editor.putString(KEY_USER_ROLE, role);
        editor.putString(KEY_AUTH_TOKEN, token);
        editor.putString(KEY_SERVER_URL, serverUrl);
        editor.apply();
    }

    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public String getUserName() {
        return pref.getString(KEY_USER_NAME, "المستخدم");
    }

    public Role getUserRole() {
        String roleStr = pref.getString(KEY_USER_ROLE, Role.ADMIN.name());
        try {
            return Role.valueOf(roleStr);
        } catch (Exception e) {
            return Role.ADMIN;
        }
    }

    public String getServerUrl() {
        return pref.getString(KEY_SERVER_URL, "https://api.mira.cloud");
    }

    public void logout() {
        editor.clear();
        editor.apply();
    }
}
