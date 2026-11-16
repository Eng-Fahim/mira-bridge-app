package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.security.KeyStore;

/**
 * SecureSessionManager - إدارة الجلسات الآمنة (نسخة متوافقة وشاملة)
 * - لا توجد أدوار ثابتة (Hardcoded Roles) لتجنب التلاعب المحلي.
 * - دعم كامل لجميع الدوال والمثودات المطلوبة لربط SessionManager بسلامة تامة.
 */
public class SecureSessionManager {

    private static final String TAG = "SecureSessionManager";

    private static volatile SecureSessionManager instance;
    private static final Object INSTANCE_LOCK = new Object();

    private static final String SECURE_PREFS_NAME = "mira_secure_session";
    private static final String USER_PREFS_NAME   = "mira_user_preferences";

    // مفاتيح التخزين الآمن
    private static final String KEY_AUTH_TOKEN            = "auth_token";
    private static final String KEY_TOKEN_EXPIRY          = "token_expiry";
    private static final String KEY_API_KEY               = "api_key";
    private static final String KEY_API_KEY_ID            = "api_key_id";
    private static final String KEY_API_KEY_PREFIX        = "api_key_prefix";
    private static final String KEY_LAST_BIOMETRIC_TOKEN  = "last_biometric_token";

    // مفاتيح التخزين العادي
    private static final String KEY_IS_LOGGED_IN                    = "is_logged_in";
    private static final String KEY_USERNAME                        = "username";
    private static final String KEY_DISPLAY_NAME                    = "display_name";
    private static final String KEY_USER_ID                         = "user_id";
    private static final String KEY_USER_ROLE                       = "user_role"; 
    private static final String KEY_USER_EMAIL                      = "user_email";
    private static final String KEY_USER_PHONE                      = "user_phone";
    private static final String KEY_SERVER_URL                      = "server_url";
    private static final String KEY_LAST_USERNAME                   = "last_username";
    private static final String KEY_GATE_ID                         = "gate_id";
    private static final String KEY_BIOMETRIC_ENABLED               = "biometric_enabled";
    private static final String KEY_BIOMETRIC_CONFIRM_ENABLED       = "biometric_confirm_enabled";
    private static final String KEY_LAST_BIOMETRIC_USER             = "last_biometric_user";
    private static final String KEY_LAST_BIOMETRIC_ROLE             = "last_biometric_role";
    private static final String KEY_LAST_BIOMETRIC_DISPLAY_NAME     = "last_biometric_display_name";
    private static final String KEY_LAST_BIOMETRIC_USER_ID          = "last_biometric_user_id";
    private static final String KEY_LAST_BIOMETRIC_BRANCH_ID        = "last_biometric_branch_id";
    private static final String KEY_LAST_BIOMETRIC_BRANCH_NAME      = "last_biometric_branch_name";
    private static final String KEY_BRANCH_ID                       = "branch_id";
    private static final String KEY_BRANCH_NAME                     = "branch_name";
    private static final String KEY_LAST_ACTIVE_TIME                = "last_app_active_time";
    private static final String KEY_SESSION_LOCKED                  = "session_locked";

    private static final String DEFAULT_SERVER_URL = "https://jewelry.ibreg.org/api";
    private static final long TOKEN_EXPIRY_BUFFER   = 60_000L;
    private static final long DEFAULT_TOKEN_DAYS    = 7L;

    private SharedPreferences securePrefs;
    private final SharedPreferences userPrefs;
    private KeyStore keyStore;

    // تخزين مؤقت خفيف
    private String  cachedAuthToken   = null;
    private long    tokenCacheTime    = 0;
    private Boolean cachedLoggedIn    = null;
    private long    loggedInCacheTime = 0;
    private String  cachedUsername    = null;
    private long    usernameCacheTime = 0;

    public static SecureSessionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new SecureSessionManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SecureSessionManager(Context context) {
        this.userPrefs = context.getApplicationContext()
                .getSharedPreferences(USER_PREFS_NAME, Context.MODE_PRIVATE);

        try {
            this.keyStore = KeyStore.getInstance("AndroidKeyStore");
            this.keyStore.load(null);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing KeyStore", e);
        }

        this.securePrefs = createEncryptedSharedPreferences(context.getApplicationContext());
    }

    private SharedPreferences createEncryptedSharedPreferences(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.deleteSharedPreferences(SECURE_PREFS_NAME);
                }
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                return EncryptedSharedPreferences.create(
                        context, SECURE_PREFS_NAME, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception ex) {
                return context.getSharedPreferences(SECURE_PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
            }
        }
    }

    public void invalidateCache() {
        cachedAuthToken   = null;
        tokenCacheTime    = 0;
        cachedLoggedIn    = null;
        loggedInCacheTime = 0;
        cachedUsername    = null;
        usernameCacheTime = 0;
    }

    public void createLoginSession(String username, String role, String token,
                                   String serverUrl, long expiresInSeconds) {
        try {
            long expiryTime = (expiresInSeconds > 0)
                    ? (System.currentTimeMillis() + (expiresInSeconds * 1000L))
                    : (System.currentTimeMillis() + (DEFAULT_TOKEN_DAYS * 24 * 60 * 60 * 1000L));

            securePrefs.edit()
                    .putString(KEY_AUTH_TOKEN, token != null ? token : "")
                    .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                    .commit();

            String finalServerUrl = (serverUrl != null && !serverUrl.isEmpty())
                    ? normalizeServerUrl(serverUrl) : DEFAULT_SERVER_URL;

            userPrefs.edit()
                    .putBoolean(KEY_IS_LOGGED_IN, true)
                    .putString(KEY_USERNAME, username != null ? username : "")
                    .putString(KEY_USER_ROLE, role != null ? role : "USER")
                    .putString(KEY_LAST_USERNAME, username != null ? username : "")
                    .putString(KEY_SERVER_URL, finalServerUrl)
                    .apply();

            invalidateCache();
        } catch (Exception e) {
            Log.e(TAG, "Error creating login session", e);
        }
    }

    public void createLoginSession(String username, String role, String token, String serverUrl) {
        createLoginSession(username, role, token, serverUrl, DEFAULT_TOKEN_DAYS * 24 * 60 * 60);
    }

    public void saveAuthToken(String token, long expiresInSeconds) {
        if (token == null || token.isEmpty()) return;
        try {
            long expiryTime = System.currentTimeMillis() + (expiresInSeconds * 1000L);
            securePrefs.edit()
                    .putString(KEY_AUTH_TOKEN, token)
                    .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                    .commit();

            cachedAuthToken = token;
            tokenCacheTime  = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "saveAuthToken error", e);
        }
    }

    public String getAuthToken() {
        long now = System.currentTimeMillis();
        if (cachedAuthToken != null && !cachedAuthToken.isEmpty()
                && (now - tokenCacheTime) < 5000L) {
            return cachedAuthToken;
        }
        String token = securePrefs.getString(KEY_AUTH_TOKEN, "");
        if (token != null && !token.isEmpty()) {
            cachedAuthToken = token;
            tokenCacheTime  = now;
        }
        return token != null ? token : "";
    }

    public boolean hasValidToken() {
        try {
            String token = getAuthToken();
            if (token.isEmpty()) return false;
            long expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L);
            if (expiryTime == 0L) return true;
            return System.currentTimeMillis() < (expiryTime - TOKEN_EXPIRY_BUFFER);
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // 🛠️ الدوال الإضافية التي يحتاجها SessionManager لمنع أخطاء الـ Build
    // =========================================================================

    public boolean restoreBiometricSession() {
        // إذا كان هناك مستخدم سابق تم حفظ بياناته بيومترياً واسترجعت بنجاح
        String lastUser = getLastUsername();
        if (lastUser != null && !lastUser.isEmpty()) {
            userPrefs.edit().putBoolean(KEY_IS_LOGGED_IN, true).apply();
            return true;
        }
        return false;
    }

    public boolean needsTokenRefresh() {
        try {
            long expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L);
            if (expiryTime == 0L) return false;
            // إذا بقي أقل من ساعة على انتهاء التوكن
            long timeLeft = expiryTime - System.currentTimeMillis();
            return timeLeft > 0 && timeLeft < (60 * 60 * 1000L);
        } catch (Exception e) {
            return false;
        }
    }

    public void setRole(String role) {
        userPrefs.edit().putString(KEY_USER_ROLE, role != null ? role : "USER").apply();
    }

    public String getDisplayName() {
        return userPrefs.getString(KEY_DISPLAY_NAME, getUsername());
    }

    public void setDisplayName(String displayName) {
        if (displayName != null) {
            userPrefs.edit().putString(KEY_DISPLAY_NAME, displayName).apply();
        }
    }

    public int getUserId() {
        return userPrefs.getInt(KEY_USER_ID, -1);
    }

    public void setUserId(int userId) {
        userPrefs.edit().putInt(KEY_USER_ID, userId).apply();
    }

    public String getPhone() {
        return userPrefs.getString(KEY_USER_PHONE, "");
    }

    public void setPhone(String phone) {
        if (phone != null) {
            userPrefs.edit().putString(KEY_USER_PHONE, phone).apply();
        }
    }

    public String getEmail() {
        return userPrefs.getString(KEY_USER_EMAIL, "");
    }

    public void setEmail(String email) {
        if (email != null) {
            userPrefs.edit().putString(KEY_USER_EMAIL, email).apply();
        }
    }

    public void setUsername(String username) {
        if (username != null) {
            userPrefs.edit().putString(KEY_USERNAME, username).apply();
            invalidateCache();
        }
    }

    public boolean hasServerUrl() {
        String url = userPrefs.getString(KEY_SERVER_URL, "");
        return url != null && !url.isEmpty();
    }

    public boolean hasApiKey() {
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }

    public void clearApiKey() {
        securePrefs.edit().remove(KEY_API_KEY).apply();
    }

    public void saveGateId(String gateId) {
        if (gateId != null) {
            userPrefs.edit().putString(KEY_GATE_ID, gateId).apply();
        }
    }

    public String getGateId() {
        return userPrefs.getString(KEY_GATE_ID, "");
    }

    public void saveLastUsername(String username) {
        if (username != null) {
            userPrefs.edit().putString(KEY_LAST_USERNAME, username).apply();
        }
    }

    public String getLastUsername() {
        return userPrefs.getString(KEY_LAST_USERNAME, "");
    }

    public void clearLastUsername() {
        userPrefs.edit().remove(KEY_LAST_USERNAME).apply();
    }

    public boolean isBiometricEnabled() {
        return userPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public String getLastBiometricUser() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_USER, "");
    }

    public String getLastBiometricRole() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_ROLE, "");
    }

    public String getLastBiometricDisplayName() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, "");
    }

    public int getLastBiometricUserId() {
        return userPrefs.getInt(KEY_LAST_BIOMETRIC_USER_ID, -1);
    }

    public String getLastBiometricToken() {
        return securePrefs.getString(KEY_LAST_BIOMETRIC_TOKEN, "");
    }

    public int getLastBiometricBranchId() {
        return userPrefs.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, -1);
    }

    public String getLastBiometricBranchName() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_BRANCH_NAME, "");
    }

    // =========================================================================

    public void logoutUser() {
        setSessionLocked(false);
        setLastAppActiveTime(0);

        boolean keepBiometric = isBiometricEnabled();
        String apiKey       = getApiKey();
        String serverUrl    = getServerUrl();
        String lastUsername = getLastUsername();
        String gateId       = getGateId();
        
        String activationToken = getActivationToken();
        String deviceUuid      = getDeviceUuid();
        String licenseType     = getLicenseType();
        String licenseFrom     = getLicenseValidFrom();
        String licenseUntil    = getLicenseValidUntil();
        int    licenseMaxDev   = getLicenseMaxDevices();
        int    licenseMaxUsr   = getLicenseMaxUsers();
        long   activationAt    = getActivationTimestamp();  

        String biometricUser        = getLastBiometricUser();
        String biometricRole        = getLastBiometricRole();
        String biometricDisplayName = getLastBiometricDisplayName();
        int    biometricUserId      = getLastBiometricUserId();
        String biometricToken       = getLastBiometricToken();
        int    biometricBranchId    = getLastBiometricBranchId();
        String biometricBranchName  = getLastBiometricBranchName();

        securePrefs.edit().clear().apply();
        userPrefs.edit().clear().apply();

        SharedPreferences.Editor userEditor   = userPrefs.edit();
        SharedPreferences.Editor secureEditor = securePrefs.edit();

        if (keepBiometric) {
            userEditor.putBoolean(KEY_BIOMETRIC_ENABLED, true);
            userEditor.putString(KEY_LAST_BIOMETRIC_USER, biometricUser);
            userEditor.putString(KEY_LAST_BIOMETRIC_ROLE, biometricRole);
            userEditor.putString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, biometricDisplayName);
            userEditor.putInt(KEY_LAST_BIOMETRIC_USER_ID, biometricUserId);
            userEditor.putInt(KEY_LAST_BIOMETRIC_BRANCH_ID, biometricBranchId);
            userEditor.putString(KEY_LAST_BIOMETRIC_BRANCH_NAME, biometricBranchName);
            secureEditor.putString(KEY_LAST_BIOMETRIC_TOKEN, biometricToken);
        }

        if (activationToken != null && !activationToken.isEmpty()) {
            secureEditor.putString(KEY_ACTIVATION_TOKEN, activationToken);
            secureEditor.putLong(KEY_ACTIVATION_AT, activationAt);
        }
        if (deviceUuid != null && !deviceUuid.isEmpty()) {
            secureEditor.putString(KEY_DEVICE_UUID, deviceUuid);
        }
        if (licenseType != null && !licenseType.isEmpty()) {
            userEditor.putString(KEY_LICENSE_TYPE, licenseType);
            userEditor.putString(KEY_LICENSE_VALID_FROM, licenseFrom);
            userEditor.putString(KEY_LICENSE_VALID_UNTIL, licenseUntil);
            userEditor.putInt(KEY_LICENSE_MAX_DEVICES, licenseMaxDev);
            userEditor.putInt(KEY_LICENSE_MAX_USERS, licenseMaxUsr);
        }
        if (apiKey != null && !apiKey.isEmpty()) secureEditor.putString(KEY_API_KEY, apiKey);
        if (serverUrl != null && !serverUrl.isEmpty()) userEditor.putString(KEY_SERVER_URL, serverUrl);
        if (lastUsername != null && !lastUsername.isEmpty()) userEditor.putString(KEY_LAST_USERNAME, lastUsername);
        if (gateId != null && !gateId.isEmpty()) userEditor.putString(KEY_GATE_ID, gateId);

        userEditor.apply();
        secureEditor.apply();
        invalidateCache();
    }

    public boolean isLoggedIn() {
        long now = System.currentTimeMillis();
        if (cachedLoggedIn != null && (now - loggedInCacheTime) < 5000L) {
            return cachedLoggedIn;
        }
        cachedLoggedIn = userPrefs.getBoolean(KEY_IS_LOGGED_IN, false);
        loggedInCacheTime = now;
        return cachedLoggedIn;
    }

    public String getUsername() {
        long now = System.currentTimeMillis();
        if (cachedUsername != null && (now - usernameCacheTime) < 5000L) {
            return cachedUsername;
        }
        cachedUsername = userPrefs.getString(KEY_USERNAME, "");
        usernameCacheTime = now;
        return cachedUsername;
    }

    public String getRoleString() {
        return userPrefs.getString(KEY_USER_ROLE, "USER");
    }

    public String getServerUrl() {
        String url = userPrefs.getString(KEY_SERVER_URL, "");
        if (url == null || url.isEmpty()) {
            url = DEFAULT_SERVER_URL;
            setServerUrl(url);
        }
        return url;
    }

    public void setServerUrl(String url) {
        if (url == null || url.isEmpty()) return;
        userPrefs.edit().putString(KEY_SERVER_URL, normalizeServerUrl(url)).apply();
    }

    private String normalizeServerUrl(String url) {
        String normalized = url.trim();
        if (!normalized.endsWith("/")) normalized += "/";
        return normalized;
    }

    public void saveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            securePrefs.edit().putString(KEY_API_KEY, apiKey).apply();
        }
    }

    public String getApiKey() {
        return securePrefs.getString(KEY_API_KEY, "");
    }

    public void setBranchId(int branchId) {
        userPrefs.edit().putInt(KEY_BRANCH_ID, branchId).apply();
    }

    public int getBranchId() {
        int branchId = userPrefs.getInt(KEY_BRANCH_ID, -1);
        if (branchId > 0) return branchId;
        return userPrefs.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, -1);
    }

    // دوال الترخيص (MIRA License Center)
    private static final String KEY_ACTIVATION_TOKEN       = "activation_token";
    private static final String KEY_DEVICE_UUID            = "device_uuid";
    private static final String KEY_LICENSE_TYPE           = "license_type";
    private static final String KEY_LICENSE_VALID_FROM     = "license_valid_from";
    private static final String KEY_LICENSE_VALID_UNTIL    = "license_valid_until";
    private static final String KEY_LICENSE_MAX_DEVICES    = "license_max_devices";
    private static final String KEY_LICENSE_MAX_USERS      = "license_max_users";
    private static final String KEY_LAST_HEARTBEAT         = "license_last_heartbeat";
    private static final String KEY_OFFLINE_GRACE_DAYS     = "license_offline_grace_days";
    private static final String KEY_OFFLINE_WARNING_DAY    = "license_offline_warning_day";
    private static final String KEY_OFFLINE_RESTRICT_DAY   = "license_offline_restrict_day";
    private static final String KEY_ACTIVATION_AT          = "license_activated_at";

    public void saveActivationToken(String token) {
        if (token == null || token.isEmpty()) return;
        securePrefs.edit().putString(KEY_ACTIVATION_TOKEN, token).putLong(KEY_ACTIVATION_AT, System.currentTimeMillis()).commit();
    }

    public String getActivationToken() {
        return securePrefs.getString(KEY_ACTIVATION_TOKEN, "");
    }

    public boolean hasActivationToken() {
        return !getActivationToken().isEmpty();
    }

    public void saveDeviceUuid(String uuid) {
        if (uuid != null && !uuid.isEmpty()) securePrefs.edit().putString(KEY_DEVICE_UUID, uuid).apply();
    }

    public String getDeviceUuid() {
        return securePrefs.getString(KEY_DEVICE_UUID, "");
    }

    public void saveLicenseInfo(String type, String validFrom, String validUntil, int maxDevices, int maxUsers) {
        userPrefs.edit().putString(KEY_LICENSE_TYPE, type).putString(KEY_LICENSE_VALID_FROM, validFrom)
                .putString(KEY_LICENSE_VALID_UNTIL, validUntil).putInt(KEY_LICENSE_MAX_DEVICES, maxDevices)
                .putInt(KEY_LICENSE_MAX_USERS, maxUsers).apply();
    }

    public String getLicenseType() { return userPrefs.getString(KEY_LICENSE_TYPE, ""); }
    public String getLicenseValidFrom() { return userPrefs.getString(KEY_LICENSE_VALID_FROM, ""); }
    public String getLicenseValidUntil() { return userPrefs.getString(KEY_LICENSE_VALID_UNTIL, ""); }
    public int getLicenseMaxDevices() { return userPrefs.getInt(KEY_LICENSE_MAX_DEVICES, 0); }
    public int getLicenseMaxUsers() { return userPrefs.getInt(KEY_LICENSE_MAX_USERS, 0); }

    public void saveLastHeartbeat(long timestampMs) {
        userPrefs.edit().putLong(KEY_LAST_HEARTBEAT, timestampMs).apply();
    }

    public long getLastHeartbeat() {
        return userPrefs.getLong(KEY_LAST_HEARTBEAT, 0L);
    }

    public void saveOfflineSettings(int graceDays, int warnDay, int restrictDay) {
        userPrefs.edit().putInt(KEY_OFFLINE_GRACE_DAYS, graceDays).putInt(KEY_OFFLINE_WARNING_Day, warnDay)
                .putInt(KEY_OFFLINE_RESTRICT_DAY, restrictDay).apply();
    }

    public int getOfflineGraceDays()   { return userPrefs.getInt(KEY_OFFLINE_GRACE_DAYS, 7); }
    public int getOfflineWarningDay()  { return userPrefs.getInt(KEY_OFFLINE_WARNING_DAY, 4); }
    public int getOfflineRestrictDay() { return userPrefs.getInt(KEY_OFFLINE_RESTRICT_DAY, 6); }

    public long getActivationTimestamp() { return securePrefs.getLong(KEY_ACTIVATION_AT, 0L); }

    public int getDaysSinceLastHeartbeat() {
        long last = getLastHeartbeat();
        if (last <= 0) return 0;
        return (int) ((System.currentTimeMillis() - last) / (24 * 60 * 60 * 1000L));
    }

    public String getOfflineStatus() {
        int days = getDaysSinceLastHeartbeat();
        if (days >= getOfflineGraceDays() + 1) return "BLOCKED";
        if (days >= getOfflineRestrictDay()) return "RESTRICTED";
        if (days >= getOfflineWarningDay()) return "WARNING";
        return "ONLINE";
    }

    public void clearLicense() {
        securePrefs.edit().remove(KEY_ACTIVATION_TOKEN).remove(KEY_DEVICE_UUID).remove(KEY_ACTIVATION_AT).apply();
        userPrefs.edit().remove(KEY_LICENSE_TYPE).remove(KEY_LICENSE_VALID_FROM).remove(KEY_LICENSE_VALID_UNTIL)
                .remove(KEY_LICENSE_MAX_DEVICES).remove(KEY_LICENSE_MAX_USERS).remove(KEY_LAST_HEARTBEAT).apply();
    }

    public void setLastAppActiveTime(long timestamp) {
        userPrefs.edit().putLong(KEY_LAST_ACTIVE_TIME, timestamp).apply();
    }

    public long getLastAppActiveTime() {
        return userPrefs.getLong(KEY_LAST_ACTIVE_TIME, 0);
    }

    public void setSessionLocked(boolean locked) {
        userPrefs.edit().putBoolean(KEY_SESSION_LOCKED, locked).apply();
    }

    public boolean isSessionLocked() {
        return userPrefs.getBoolean(KEY_SESSION_LOCKED, false);
    }

    public void touchActivity() {
        setLastAppActiveTime(System.currentTimeMillis());
    }

    public boolean isInactivityTimeout(long timeoutMs) {
        long last = getLastAppActiveTime();
        if (last <= 0) return false;
        return (System.currentTimeMillis() - last) > timeoutMs;
    }

    public void clearAll() {
        securePrefs.edit().clear().apply();
        userPrefs.edit().clear().apply();
        invalidateCache();
    }
}
