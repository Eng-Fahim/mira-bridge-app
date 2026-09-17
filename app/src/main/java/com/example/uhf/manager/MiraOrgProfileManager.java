package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 🏢 MiraOrgProfileManager - مدير بيانات الجهة المركزي
 * 
 * ✅ المسؤوليات:
 * - إدارة بيانات المؤسسة (اسم، شعار، اتصال، ...)
 * - حفظ واسترجاع بيانات المستخدم الأساسية
 * - المزامنة من السيرفر (/profile/get)
 * 
 * 🛡️ الحماية من التسريبات:
 * - استخدام WeakReference للمستمعين
 * - تنظيف تلقائي للمراجع الميتة
 */
public class MiraOrgProfileManager {

    private static final String TAG = "MiraOrgProfile";
    private static final String PREFS_NAME = "MIRA_ORG_PROFILE";
    private static final String AUTH_PREFS_NAME = "MIRA_AUTH";
    private static final long SYNC_COOLDOWN_MS = 15 * 60 * 1000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static MiraOrgProfileManager instance;
    private final SharedPreferences prefs;
    private final Context context;

    private final List<WeakReference<OnProfileChangedListener>> listeners = new ArrayList<>();

    public interface OnProfileChangedListener {
        void onProfileChanged();
    }

    public interface SyncCallback {
        void onSuccess();
        void onError(String error);
    }

    private MiraOrgProfileManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraOrgProfileManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraOrgProfileManager(context.getApplicationContext());
        }
        return instance;
    }

    // ==================== المستمعون (آمن ضد التسريبات) ====================

    public void addListener(OnProfileChangedListener listener) {
        if (listener == null) return;

        synchronized (listeners) {
            cleanupDeadListeners();

            for (WeakReference<OnProfileChangedListener> ref : listeners) {
                OnProfileChangedListener existing = ref.get();
                if (existing != null && existing == listener) {
                    Log.d(TAG, "Listener already registered, skipping");
                    return;
                }
            }

            listeners.add(new WeakReference<>(listener));
            Log.d(TAG, "Listener added. Total: " + listeners.size());
        }
    }

    public void removeListener(OnProfileChangedListener listener) {
        if (listener == null) return;

        synchronized (listeners) {
            Iterator<WeakReference<OnProfileChangedListener>> it = listeners.iterator();
            while (it.hasNext()) {
                OnProfileChangedListener existing = it.next().get();
                if (existing == null || existing == listener) {
                    it.remove();
                }
            }
            Log.d(TAG, "Listener removed. Remaining: " + listeners.size());
        }
    }

    private void cleanupDeadListeners() {
        Iterator<WeakReference<OnProfileChangedListener>> it = listeners.iterator();
        while (it.hasNext()) {
            if (it.next().get() == null) {
                it.remove();
            }
        }
    }

    public void notifyListeners() {
        mainHandler.post(() -> {
            List<OnProfileChangedListener> aliveListeners = new ArrayList<>();

            synchronized (listeners) {
                cleanupDeadListeners();
                for (WeakReference<OnProfileChangedListener> ref : listeners) {
                    OnProfileChangedListener listener = ref.get();
                    if (listener != null) aliveListeners.add(listener);
                }
            }

            for (OnProfileChangedListener listener : aliveListeners) {
                try {
                    listener.onProfileChanged();
                } catch (Exception e) {
                    Log.e(TAG, "Listener error: " + e.getMessage());
                }
            }
        });
    }

    // ==================== المزامنة من السيرفر ====================

    public void syncFromServer(MiraApiClient apiClient, boolean force, SyncCallback callback) {
        if (apiClient == null) {
            if (callback != null) callback.onError("apiClient is null");
            return;
        }

        if (!force) {
            long lastSync = prefs.getLong("last_server_sync", 0);
            long now = System.currentTimeMillis();

            if (now - lastSync < SYNC_COOLDOWN_MS) {
                Log.d(TAG, "syncFromServer skipped (cooldown active)");
                if (callback != null) callback.onSuccess();
                return;
            }
        }

        Log.i(TAG, "🔄 Starting syncFromServer...");

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getOrgProfile();

                if (response == null || !response.isSuccess || response.data == null) {
                    String error = response != null ? response.error : "Unknown error";
                    Log.w(TAG, "⚠️ syncFromServer failed: " + error);
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError(error);
                    });
                    return;
                }

                JSONObject organization = response.data.optJSONObject("organization");
                if (organization != null) {
                    saveOrganizationFromServer(organization);
                }

                JSONObject user = response.data.optJSONObject("user");
                if (user != null) {
                    syncBranchToBranchesManager(user);
                }

                JSONObject systemConfig = response.data.optJSONObject("system_config");
                if (systemConfig != null) {
                    saveSystemConfigFromServer(systemConfig);
                }

                prefs.edit().putLong("last_server_sync", System.currentTimeMillis()).apply();
                notifyListeners();

                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess();
                });

            } catch (Exception e) {
                Log.e(TAG, "syncFromServer error: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            }
        }).start();
    }

    public void syncFromServer(MiraApiClient apiClient, SyncCallback callback) {
        syncFromServer(apiClient, false, callback);
    }

    // ==================== دوال حفظ داخلية ====================

    private void saveOrganizationFromServer(JSONObject org) {
        if (org == null) return;

        SharedPreferences.Editor editor = prefs.edit();

        String nameAr = org.optString("name_ar", "").trim();
        String nameEn = org.optString("name_en", "").trim();
        String phone = org.optString("phone", "").trim();
        String email = org.optString("email", "").trim();
        String tax = org.optString("tax_number", "").trim();
        String logo = org.optString("logo_url", "").trim();
        String slogan = org.optString("slogan", "").trim();
        String address = org.optString("address", "").trim();
        String website = org.optString("website", "").trim();

        if (!nameAr.isEmpty()) {
            editor.putString("org_name_ar", nameAr);
            editor.putString("org_name", nameAr);
        }
        if (!nameEn.isEmpty()) editor.putString("org_name_en", nameEn);
        if (!phone.isEmpty()) editor.putString("org_phone", phone);
        if (!email.isEmpty()) editor.putString("org_email", email);
        if (!tax.isEmpty()) editor.putString("tax_number", tax);
        if (!logo.isEmpty()) editor.putString("org_logo", logo);
        if (!slogan.isEmpty()) editor.putString("org_slogan", slogan);
        if (!address.isEmpty()) editor.putString("org_address", address);
        if (!website.isEmpty()) editor.putString("org_website", website);

        editor.apply();
        Log.d(TAG, "✅ Organization saved: ar=" + nameAr + ", en=" + nameEn);
    }

    private void syncBranchToBranchesManager(JSONObject user) {
        if (user == null) return;

        try {
            int branchId = user.optInt("branch_id", 0);
            String branchName = user.optString("branch_name", "").trim();

            if (branchId > 0 && !branchName.isEmpty()) {
                MiraBranchesManager branchesMgr = MiraBranchesManager.getInstance(context);
                branchesMgr.updateActiveBranchFromServer(branchId, branchName);
                Log.d(TAG, "Branch updated via MiraBranchesManager: " + branchName);
            }
        } catch (Exception e) {
            Log.e(TAG, "syncBranchToBranchesManager error: " + e.getMessage());
        }
    }

    private void saveSystemConfigFromServer(JSONObject config) {
        if (config == null) return;

        prefs.edit()
                .putString("system_currency_ar", config.optString("currency_ar", "دولار"))
                .putString("system_currency_en", config.optString("currency_en", "USD"))
                .putBoolean("enable_rfid", config.optBoolean("enable_rfid", true))
                .putBoolean("enable_barcode", config.optBoolean("enable_barcode", true))
                .putBoolean("enable_biometric", config.optBoolean("enable_biometric", true))
                .apply();
    }

    // ==================== Getters ====================

    public String getOrgName() {
        return prefs.getString("org_name", "MIRA Jewelry");
    }

    public String getOrgSlogan() {
        return prefs.getString("org_slogan", "Jewelry Enterprise Platform");
    }

    public String getOrgPhone() {
        return prefs.getString("org_phone", "");
    }

    public String getOrgEmail() {
        return prefs.getString("org_email", "");
    }

    public String getOrgAddress() {
        return prefs.getString("org_address", "");
    }

    public String getOrgWebsite() {
        return prefs.getString("org_website", "");
    }

    public String getOrgNameEn() {
        return prefs.getString("org_name_en", "");
    }

    public String getOrgSloganEn() {
        return prefs.getString("org_slogan_en", "");
    }

    public String getOrgLogo() {
        return prefs.getString("org_logo", "");
    }

    public String getTaxNumber() {
        return prefs.getString("tax_number", "VAT-3100293841");
    }

    public String getOrgNameAr() {
        return prefs.getString("org_name_ar", prefs.getString("org_name", "ميرا للتكنولوجيا"));
    }

    public long getLastSyncTime() {
        return prefs.getLong("last_server_sync", 0);
    }

    // ==================== Setters ====================

    public void setTaxNumber(String taxNumber) {
        prefs.edit().putString("tax_number", taxNumber).apply();
        notifyListeners();
    }

    public void saveOrgProfile(String name, String slogan, String phone, String address, String taxNumber) {
        prefs.edit()
                .putString("org_name", name)
                .putString("org_slogan", slogan)
                .putString("org_phone", phone)
                .putString("org_address", address)
                .putString("tax_number", taxNumber)
                .apply();
        notifyListeners();
    }

    public void saveFromServer(JSONObject org) {
        if (org == null) return;
        saveOrganizationFromServer(org);
        notifyListeners();
    }

    public void saveFullProfile(String nameAr, String nameEn, String phone, String email, String tax) {
        prefs.edit()
                .putString("org_name_ar", nameAr)
                .putString("org_name_en", nameEn)
                .putString("org_name", nameAr)
                .putString("org_phone", phone)
                .putString("org_email", email)
                .putString("tax_number", tax)
                .apply();
        notifyListeners();
    }

    /**
     * ✅ حفظ كامل لبيانات المنظمة (جميع الحقول)
     * يُستدعى من MiraProfileFragment بعد نجاح الحفظ على السيرفر
     */
    public void saveFullProfileExtended(String nameAr, String nameEn, String phone,
                                        String email, String tax, String slogan,
                                        String address, String website) {
        prefs.edit()
                .putString("org_name_ar", nameAr)
                .putString("org_name_en", nameEn)
                .putString("org_name", nameAr)
                .putString("org_phone", phone)
                .putString("org_email", email)
                .putString("tax_number", tax)
                .putString("org_slogan", slogan)
                .putString("org_address", address)
                .putString("org_website", website)
                .apply();
        notifyListeners();
        Log.d(TAG, "✅ saveFullProfileExtended called");
    }

    // ==================== دوال مساعدة ====================

    public String getDisplayName() {
        String locale = java.util.Locale.getDefault().getLanguage();
        if (locale.equals("ar")) {
            String ar = getOrgNameAr();
            return ar.isEmpty() ? getOrgNameEn() : ar;
        }
        String en = getOrgNameEn();
        return en.isEmpty() ? getOrgNameAr() : en;
    }

    public String getFullAppTitle() {
        String name = getDisplayName();
        return name.isEmpty() ? "MIRA Jewel" : name + " - MIRA";
    }

    public String getAppSignature() {
        return getDisplayName() + " | " + getOrgSlogan();
    }

    public String getContactInfo() {
        StringBuilder sb = new StringBuilder();
        String phone = getOrgPhone();
        String email = getOrgEmail();
        String address = getOrgAddress();

        if (!phone.isEmpty()) sb.append("📞 ").append(phone);
        if (!email.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("📧 ").append(email);
        }
        if (!address.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("📍 ").append(address);
        }
        return sb.toString();
    }

    public boolean isProfileComplete() {
        return !getOrgName().equals("MIRA Jewelry")
                && !getOrgName().isEmpty();
    }

    // ==================== دوال المستخدم ====================

    public int getCurrentUserId() {
        try {
            SharedPreferences authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE);
            return authPrefs.getInt("user_id", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public String getAuthToken() {
        try {
            SharedPreferences authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE);
            String token = authPrefs.getString("auth_token", "");
            if (token.isEmpty()) {
                token = authPrefs.getString("session_token", "");
            }
            return token;
        } catch (Exception e) {
            return "";
        }
    }

    public void saveUserAuth(int userId, String token) {
        try {
            SharedPreferences authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE);
            authPrefs.edit()
                    .putInt("user_id", userId)
                    .putString("auth_token", token)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveUserAuth error: " + e.getMessage());
        }
    }

    // ==================== تنظيف الجلسة (Logout) ====================

    /**
     * ✅ تنظيف آمن عند تسجيل الخروج
     * ⚠️ ملاحظة: لا يمسح بيانات المؤسسة (نحتاجها بعد إعادة الدخول)
     * فقط يزيل المستمعين ويحذف بيانات الجلسة المؤقتة
     */
    public void logout() {
        try {
            Log.i(TAG, "🚪 MiraOrgProfileManager.logout() called");

            // 1. إزالة جميع المستمعين
            synchronized (listeners) {
                listeners.clear();
            }

            // 2. مسح بيانات الجلسة المؤقتة (MIRA_AUTH)
            SharedPreferences authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE);
            authPrefs.edit().clear().apply();

            // 3. مسح cooldown المزامنة (ليتم المزامنة عند إعادة الدخول)
            prefs.edit().remove("last_server_sync").apply();

            Log.i(TAG, "✅ MiraOrgProfileManager logout complete");
        } catch (Exception e) {
            Log.e(TAG, "logout error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ مسح كامل (يُستخدم فقط عند إلغاء تثبيت التطبيق أو مسح البيانات)
     */
    public void clearAll() {
        try {
            synchronized (listeners) {
                listeners.clear();
            }
            prefs.edit().clear().apply();
            Log.i(TAG, "✅ clearAll complete");
        } catch (Exception e) {
            Log.e(TAG, "clearAll error: " + e.getMessage(), e);
        }
    }
}
