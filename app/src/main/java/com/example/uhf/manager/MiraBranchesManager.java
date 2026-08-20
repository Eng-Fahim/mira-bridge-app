package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;

public class MiraBranchesManager {

    private static final String TAG = "MiraBranchesManager";
    private static final String PREFS_NAME = "MIRA_BRANCHES";
    private static final String KEY_ACTIVE_BRANCH_ID = "active_branch_id";
    private static final String KEY_ACTIVE_BRANCH_NAME = "active_branch_name";
    private static final String KEY_ACTIVE_BRANCH_CODE = "active_branch_code";
    private static final String KEY_ACTIVE_BRANCH_TYPE = "active_branch_type";
    private static final String KEY_ACTIVE_BRANCH_CITY = "active_branch_city";
    private static final String KEY_BRANCH_LOCKED = "branch_locked";
    private static final String KEY_BRANCHES_CACHE = "branches_cache";
    private static final String KEY_BRANCHES_LAST_SYNC = "branches_last_sync";

    private static MiraBranchesManager instance;
    private final SharedPreferences prefs;
    private final Context context; // ✅ إضافة المتغير

    private MiraBranchesManager(Context context) {
        this.context = context.getApplicationContext(); // ✅ حفظ المرجع
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static MiraBranchesManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraBranchesManager(context);
        }
        return instance;
    }

    public void setActiveBranch(int branchId, String branchName, String branchCode,
                                String branchType, String city) {
        prefs.edit()
            .putInt(KEY_ACTIVE_BRANCH_ID, branchId)
            .putString(KEY_ACTIVE_BRANCH_NAME, branchName)
            .putString(KEY_ACTIVE_BRANCH_CODE, branchCode)
            .putString(KEY_ACTIVE_BRANCH_TYPE, branchType)
            .putString(KEY_ACTIVE_BRANCH_CITY, city)
            .apply();
        
        Log.d(TAG, "✅ Active branch saved: ID=" + branchId + ", Name=" + branchName);
    }

    public int getActiveBranchId() {
        try {
            int branchId = prefs.getInt(KEY_ACTIVE_BRANCH_ID, 1); // ✅ افتراضي 1
            Log.d(TAG, "Active Branch ID: " + branchId);
            return branchId;
        } catch (Exception e) {
            Log.e(TAG, "Error getting branch ID: " + e.getMessage());
            return 1; // ✅ افتراضي 1
        }
    }

    public String getActiveBranchName() {
        return prefs.getString(KEY_ACTIVE_BRANCH_NAME, "الفرع الرئيسي");
    }

    public String getActiveBranchCode() {
        return prefs.getString(KEY_ACTIVE_BRANCH_CODE, "MAIN-001");
    }

    public String getActiveBranchType() {
        return prefs.getString(KEY_ACTIVE_BRANCH_TYPE, "main");
    }

    public String getActiveBranchCity() {
        return prefs.getString(KEY_ACTIVE_BRANCH_CITY, "");
    }

    public boolean hasActiveBranch() {
        return prefs.contains(KEY_ACTIVE_BRANCH_ID);
    }

    public String getActiveBranchDisplayName() {
        String name = getActiveBranchName();
        String city = getActiveBranchCity();
        return !city.isEmpty() ? name + " - " + city : name;
    }

    public boolean isMainBranch() {
        return "main".equals(getActiveBranchType());
    }

    public void setUserBranchLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_BRANCH_LOCKED, locked).apply();
    }

    public boolean isBranchLocked() {
        return prefs.getBoolean(KEY_BRANCH_LOCKED, false);
    }

    public void cacheBranches(JSONArray branches) {
        prefs.edit()
            .putString(KEY_BRANCHES_CACHE, branches.toString())
            .putLong(KEY_BRANCHES_LAST_SYNC, System.currentTimeMillis())
            .apply();
    }

    public JSONArray getCachedBranches() {
        try {
            String json = prefs.getString(KEY_BRANCHES_CACHE, "[]");
            return new JSONArray(json);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing cached branches: " + e.getMessage());
            return new JSONArray();
        }
    }

    public long getLastSyncTime() {
        return prefs.getLong(KEY_BRANCHES_LAST_SYNC, 0);
    }

    public boolean needsRefresh() {
        return System.currentTimeMillis() - getLastSyncTime() > 3600000;
    }

    public void clear() {
        prefs.edit().clear().apply();
        Log.d(TAG, "Branches data cleared");
    }
}
