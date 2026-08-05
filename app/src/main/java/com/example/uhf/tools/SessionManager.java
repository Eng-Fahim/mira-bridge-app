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

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    public enum Role {
        SYSTEM_ADMIN,
        ORG_ADMIN,
        BRANCH_MANAGER,
        INVENTORY_MGR,
        POS_OPERATOR,
        SECURITY,
        GATE_SECURITY,
        AUDITOR
    }

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

    public boolean isLoggedIn() { return pref.getBoolean(KEY_IS_LOGGED_IN, false); }

    public Role getUserRole() {
        String roleStr = pref.getString(KEY_USER_ROLE, "INVENTORY_MGR");
        try { return Role.valueOf(roleStr); }
        catch (IllegalArgumentException e) { return Role.INVENTORY_MGR; }
    }

    public String getRoleString() { return pref.getString(KEY_USER_ROLE, "INVENTORY_MGR"); }
    public boolean isSystemAdmin() { return "SYSTEM_ADMIN".equals(getRoleString()); }

    public boolean isAnyAdmin() {
        String role = getRoleString();
        return "SYSTEM_ADMIN".equals(role) || "ORG_ADMIN".equals(role);
    }

    public boolean isManager() {
        String role = getRoleString();
        return "SYSTEM_ADMIN".equals(role) || "ORG_ADMIN".equals(role) || "BRANCH_MANAGER".equals(role);
    }

    public String getUsername()   { return pref.getString(KEY_USERNAME, ""); }
    public String getUserName()   { return pref.getString(KEY_USERNAME, ""); }
    public String getAuthToken()  { return pref.getString(KEY_AUTH_TOKEN, ""); }

    public void setUserId(int userId) { editor.putInt(KEY_USER_ID, userId); editor.apply(); }
    public int getUserId()             { return pref.getInt(KEY_USER_ID, 0); }

    public void setDisplayName(String displayName) { editor.putString(KEY_DISPLAY_NAME, displayName); editor.apply(); }
    public String getDisplayName() {
        String name = pref.getString(KEY_DISPLAY_NAME, "");
        return name.isEmpty() ? getUsername() : name;
    }

    public String getServerUrl()         { return pref.getString(KEY_SERVER_URL, ""); }
    public void setServerUrl(String url) { editor.putString(KEY_SERVER_URL, url); editor.apply(); }

    public void setBiometricEnabled(boolean enabled) { editor.putBoolean(KEY_BIOMETRIC_ENABLED, enabled); editor.apply(); }
    public boolean isBiometricEnabled()              { return pref.getBoolean(KEY_BIOMETRIC_ENABLED, false); }

    public void saveBiometricUser(String username, String role, String displayName, int userId, String token) {
        editor.putString(KEY_LAST_BIOMETRIC_USER, username);
        editor.putString(KEY_LAST_BIOMETRIC_ROLE, role);
        editor.putString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, displayName);
        editor.putInt(KEY_LAST_BIOMETRIC_USER_ID, userId);
        editor.putString(KEY_LAST_BIOMETRIC_TOKEN, token);
        editor.apply();
    }

    public String getLastBiometricUser()        { return pref.getString(KEY_LAST_BIOMETRIC_USER, ""); }
    public String getLastBiometricRole()        { return pref.getString(KEY_LAST_BIOMETRIC_ROLE, "INVENTORY_MGR"); }
    public String getLastBiometricDisplayName() { return pref.getString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, ""); }
    public int getLastBiometricUserId()         { return pref.getInt(KEY_LAST_BIOMETRIC_USER_ID, 0); }
    public String getLastBiometricToken()       { return pref.getString(KEY_LAST_BIOMETRIC_TOKEN, ""); }

    public void saveBiometricBranch(int branchId, String branchName) {
        editor.putInt(KEY_LAST_BIOMETRIC_BRANCH_ID, branchId);
        editor.putString(KEY_LAST_BIOMETRIC_BRANCH_NAME, branchName);
        editor.apply();
    }

    public int getLastBiometricBranchId()      { return pref.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, 1); }
    public String getLastBiometricBranchName() { return pref.getString(KEY_LAST_BIOMETRIC_BRANCH_NAME, ""); }

    public void logoutUser() {
        boolean keepBiometric = pref.getBoolean(KEY_BIOMETRIC_ENABLED, false);
        String lastUser = pref.getString(KEY_LAST_BIOMETRIC_USER, "");
        String lastRole = pref.getString(KEY_LAST_BIOMETRIC_ROLE, "");
        String lastDisplayName = pref.getString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, "");
        int lastUserId = pref.getInt(KEY_LAST_BIOMETRIC_USER_ID, 0);
        String lastToken = pref.getString(KEY_LAST_BIOMETRIC_TOKEN, "");
        int lastBranchId = pref.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, 1);
        String lastBranchName = pref.getString(KEY_LAST_BIOMETRIC_BRANCH_NAME, "");

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

        editor.apply();
    }

    public void logout() { logoutUser(); }
}
