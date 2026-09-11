package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import com.example.uhf.api.MiraApiClient; // ✅ import ناقص

import java.util.ArrayList;
import java.util.List;

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
    private final Context context;
    private MiraApiClient apiClient; // ✅ إضافة المتغير
    
    // في أعلى الكلاس — أضف:
private final List<OnBranchChangedListener> listeners = new ArrayList<>();

public interface OnBranchChangedListener {
    void onBranchChanged();
}

public void addListener(OnBranchChangedListener listener) {
    if (listener != null && !listeners.contains(listener)) {
        listeners.add(listener);
    }
}

public void removeListener(OnBranchChangedListener listener) {
    listeners.remove(listener);
}

public void notifyListeners() {
    // ✅ تنفيذ على الـ main thread
    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        List<OnBranchChangedListener> copy = new ArrayList<>(listeners);
        for (OnBranchChangedListener l : copy) {
            if (l != null) {
                try {
                    l.onBranchChanged();
                } catch (Exception e) {
                    Log.e(TAG, "Listener error: " + e.getMessage());
                }
            }
        }
    });
}
    private MiraBranchesManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        apiClient = MiraApiClient.getInstance(context); // ✅ تهيئة apiClient
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
    
    // ✅ إشعار المستمعين
    notifyListeners();
}
    
    // في MiraBranchesManager.java - أضف هذه الدالة

/**
 * ✅ حفظ الفروع من استجابة السيرفر
 */
public void saveBranchesFromServer(JSONObject data) {
    try {
        JSONArray branches = data.optJSONArray("branches");
        if (branches == null) {
            branches = data.optJSONArray("data");
        }
        
        if (branches == null || branches.length() == 0) {
            Log.w(TAG, "No branches found in server response");
            return;
        }
        
        SharedPreferences.Editor editor = prefs.edit();
        int currentActiveId = getActiveBranchId();   // ← احصل على الفرع النشط الحالي
        
        // حفظ قائمة الفروع كاملة
        editor.putString("branches_list", branches.toString());
        
        // حفظ كل فرع على حدة
        for (int i = 0; i < branches.length(); i++) {
            try {
                JSONObject branch = branches.getJSONObject(i);
                int branchId = branch.optInt("id", branch.optInt("branch_id", 0));
                String branchName = branch.optString("branch_name_ar",
                                    branch.optString("name",
                                    branch.optString("branch_name", "")));
                String branchCode = branch.optString("branch_code",
                                    branch.optString("code", ""));
                String branchAddress = branch.optString("address", "");
                String branchPhone = branch.optString("phone", "");
                String branchCity = branch.optString("city", "");
                String branchType = branch.optString("branch_type", "main");
                
                if (branchId > 0) {
                    editor.putInt("branch_id_" + branchId, branchId);
                    editor.putString("branch_name_" + branchId, branchName);
                    editor.putString("branch_code_" + branchId, branchCode);
                    editor.putString("branch_address_" + branchId, branchAddress);
                    editor.putString("branch_phone_" + branchId, branchPhone);
                    
                    // ✅ إذا كان هذا الفرع هو النشط → حدّث بياناته
                    if (branchId == currentActiveId && !branchName.isEmpty()) {
                        editor.putString(KEY_ACTIVE_BRANCH_NAME, branchName);
                        if (!branchCode.isEmpty()) editor.putString(KEY_ACTIVE_BRANCH_CODE, branchCode);
                        if (!branchCity.isEmpty()) editor.putString(KEY_ACTIVE_BRANCH_CITY, branchCity);
                        if (!branchType.isEmpty()) editor.putString(KEY_ACTIVE_BRANCH_TYPE, branchType);
                        
                        Log.d(TAG, "✅ Active branch refreshed: " + branchName);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Parse branch error: " + e.getMessage());
            }
        }
        
        editor.putLong(KEY_BRANCHES_LAST_SYNC, System.currentTimeMillis());
        editor.apply();
        
        Log.i(TAG, "✅ Branches saved: " + branches.length());
        
        // ✅ إشعار المستمعين (لأن الفرع النشط قد يكون تغيّر)
        notifyListeners();
        
    } catch (Exception e) {
        Log.e(TAG, "Save branches error: " + e.getMessage());
    }
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
/**
     * ✅ جلب جميع الفروع من السيرفر
     */
    public List<BranchItem> getAllBranches() {
        List<BranchItem> branches = new ArrayList<>();
        try {
            if (apiClient == null) {
                apiClient = MiraApiClient.getInstance(context);
            }
            
            MiraApiClient.ApiResponse response = apiClient.getBranches();
            if (response != null && response.isSuccess && response.data != null) {
                JSONArray arr = response.data.optJSONArray("branches");
                if (arr == null) {
                    JSONObject dataObj = response.data.optJSONObject("data");
                    if (dataObj != null) {
                        arr = dataObj.optJSONArray("branches");
                    }
                }
                
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b != null) {
                            BranchItem item = new BranchItem();
                            item.id = b.optInt("id");
                            item.name = b.optString("branch_name_ar", b.optString("branch_name", ""));
                            item.code = b.optString("branch_code", "");
                            branches.add(item);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading branches: " + e.getMessage());
        }
        return branches;
    }

/**
 * ✅ تحديث الفرع النشط من استجابة السيرفر
 * تُستدعى من MiraOrgProfileManager بعد مزامنة /profile/get
 */
public void updateActiveBranchFromServer(int branchId, String branchName) {
    if (branchId <= 0 || branchName == null || branchName.isEmpty()) return;
    
    try {
        prefs.edit()
            .putInt(KEY_ACTIVE_BRANCH_ID, branchId)
            .putString(KEY_ACTIVE_BRANCH_NAME, branchName)
            .apply();
        
        Log.i(TAG, "✅ Active branch updated: ID=" + branchId + ", Name=" + branchName);
    } catch (Exception e) {
        Log.e(TAG, "updateActiveBranchFromServer error: " + e.getMessage());
    }
}
/**
 * ✅ مزامنة كاملة للفروع من السيرفر
 * تستدعي /profile/get لاستخراج معلومات الفرع النشط
 * وتستدعي /branches لجلب قائمة الفروع كاملة
 */
/**
 * ✅ مزامنة قائمة الفروع من السيرفر
 * ⚠️ ملاحظة: الفرع النشط يُحدَّث تلقائياً من MiraOrgProfileManager.saveUserBranchInfo
 */
public void syncFromServer(SyncCallback callback) {
    if (apiClient == null) {
        apiClient = MiraApiClient.getInstance(context);
    }
    if (apiClient == null) {
        if (callback != null) callback.onError("apiClient not available");
        return;
    }
    
    new Thread(() -> {
        try {
            // ✅ فقط جلب قائمة الفروع — لا تكرر /profile/get
            MiraApiClient.ApiResponse branchesResponse = apiClient.getBranches();
            
            if (branchesResponse != null && branchesResponse.isSuccess && branchesResponse.data != null) {
                saveBranchesFromServer(branchesResponse.data);
                Log.i(TAG, "✅ Branches list synced");
            } else {
                String err = branchesResponse != null ? branchesResponse.error : "Unknown";
                Log.w(TAG, "⚠️ Branches sync failed: " + err);
            }
            
            if (callback != null) callback.onSuccess();
            
        } catch (Exception e) {
            Log.e(TAG, "syncFromServer error: " + e.getMessage(), e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }).start();
}
/**
 * ✅ واجهة رد النداء
 */
public interface SyncCallback {
    void onSuccess();
    void onError(String error);
}

    /**
     * ✅ كلاس معلومات الفرع
     */
    public static class BranchItem {
        public int id;
        public String name;
        public String code;
        
        public BranchItem() {}
        
        public BranchItem(int id, String name) {
            this.id = id;
            this.name = name;
        }
        
        public BranchItem(int id, String name, String code) {
            this.id = id;
            this.name = name;
            this.code = code;
        }
    }
}
