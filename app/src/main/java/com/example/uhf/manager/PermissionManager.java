package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private static final String PREFS_NAME = "MIRA_PERMISSIONS";
    private static final String KEY_PERMISSIONS_JSON = "permissions_json";

    private static volatile PermissionManager instance;
    private final SharedPreferences prefs;
    private JSONObject permissions;

    private PermissionManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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

    private void loadPermissions() {
        String json = prefs.getString(KEY_PERMISSIONS_JSON, "{}");
        try {
            permissions = new JSONObject(json);
        } catch (Exception e) {
            permissions = new JSONObject();
            Log.e(TAG, "Error loading permissions: " + e.getMessage());
        }
    }

    // تُستدعى مباشرة بعد نجاح عملية الـ Login وحفظ الـ JSON القادم من السيرفر
    public void savePermissions(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.trim().isEmpty()) return;
        try {
            permissions = new JSONObject(permissionsJson);
            prefs.edit().putString(KEY_PERMISSIONS_JSON, permissionsJson).apply();
        } catch (Exception e) { 
            Log.e(TAG, "Error saving permissions: " + e.getMessage()); 
        }
    }

    public void clearPermissions() {
        permissions = new JSONObject();
        prefs.edit().remove(KEY_PERMISSIONS_JSON).apply();
    }

    /**
     * الفحص الأساسي والديناميكي المستمد بالكامل من السيرفر
     */
    public boolean hasPermission(String feature, String action) {
        if (permissions == null) return false;
        try {
            // إذا كان النظام يعتبره المشرف العام (ولكن الأفضل الاعتماد على ما يرسله السيرفر)
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
        return hasPermission(feature, "view") || hasPermission(feature, "edit") || hasPermission(feature, "delete");
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

    /**
     * إرجاع كافة الميزات المتاحة ديناميكياً لتعبئة القوائم أو الـ Quick Actions في الواجهة
     */
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
}
