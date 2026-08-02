package com.example.uhf.activity;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREF_NAME = "MiraEnterpriseSession";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_USER_ROLE = "userRole"; // ADMIN, INVENTORY, SALES
    private static final String KEY_AUTH_TOKEN = "authToken";

    private final SharedPreferences pref;
    private final SharedPreferences.Editor editor;

    public SessionManager(Context context) {
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }

    // إنشاء جلسة دخول جديدة
    public void createLoginSession(String username, String role, String token) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USERNAME, username);
        editor.putString(KEY_USER_ROLE, role);
        editor.putString(KEY_AUTH_TOKEN, token);
        editor.apply();
    }

    // التحقق هل الموظف مسجل دخوله مسبقاً؟
    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    // جلب دور الموظف الحالي (الصلاحية)
    public String getUserRole() {
        return pref.getString(KEY_USER_ROLE, "INVENTORY"); // الافتراضي
    }

    public String getUsername() {
        return pref.getString(KEY_USERNAME, "");
    }

    // تسجيل الخروج وتصفير الجلسة
    public void logoutUser() {
        editor.clear();
        editor.apply();
    }
}
