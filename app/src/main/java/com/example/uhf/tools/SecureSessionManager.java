package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * SecureSessionManager - إدارة الجلسات الآمنة للتطبيق
 * 
 * ✅ v2.0 — Singleton Pattern (يُهيأ مرة واحدة فقط)
 * 
 * التحسينات الحرجة:
 * - Singleton Pattern لمنع إعادة تهيئة EncryptedSharedPreferences
 * - تخزين مؤقت (Cache) للقيم المستخدمة كثيراً
 * - منع ANR بسبب إعادة فتح AndroidKeyStore
 */
public class SecureSessionManager {
    
    private static final String TAG = "SecureSessionManager";
    
    // ✅ Singleton Pattern — يمنع إنشاء نسخ متعددة
    private static volatile SecureSessionManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // أسماء الملفات
    private static final String SECURE_PREFS_NAME = "mira_secure_session";
    private static final String USER_PREFS_NAME = "mira_user_preferences";
    
    // مفاتيح التخزين الآمن
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_TOKEN_EXPIRY = "token_expiry";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_KEY_ID = "api_key_id";
    private static final String KEY_API_KEY_PREFIX = "api_key_prefix";
    private static final String KEY_API_KEY_CREATED_AT = "api_key_created_at";
    private static final String KEY_LAST_BIOMETRIC_TOKEN = "last_biometric_token";
    
    // أضف مع المفاتيح الأخرى
    private static final String KEY_BRANCH_ID = "branch_id";
    private static final String KEY_BRANCH_NAME = "branch_name";
    // مفاتيح التخزين العادي
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_LAST_USERNAME = "last_username";
    private static final String KEY_GATE_ID = "gate_id";
    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";
    private static final String KEY_BIOMETRIC_CONFIRM_ENABLED = "biometric_confirm_enabled";
    private static final String KEY_LAST_BIOMETRIC_USER = "last_biometric_user";
    private static final String KEY_LAST_BIOMETRIC_ROLE = "last_biometric_role";
    private static final String KEY_LAST_BIOMETRIC_DISPLAY_NAME = "last_biometric_display_name";
    private static final String KEY_LAST_BIOMETRIC_USER_ID = "last_biometric_user_id";
    private static final String KEY_LAST_BIOMETRIC_BRANCH_ID = "last_biometric_branch_id";
    private static final String KEY_LAST_BIOMETRIC_BRANCH_NAME = "last_biometric_branch_name";
    
    // القيم الافتراضية
    private static final String DEFAULT_SERVER_URL = "https://sa.mirabarcode.com/wp-json";
    private static final long TOKEN_EXPIRY_BUFFER = 60000L;
    private static final long REFRESH_THRESHOLD = 300000L;
    private static final long CACHE_VALIDITY_MS = 5000; // ✅ 5 ثواني تخزين مؤقت
    
    // التشفير البيومتري
    private static final String BIOMETRIC_KEY_ALIAS = "mira_biometric_key";
    private static final String BIOMETRIC_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    
    private SharedPreferences securePrefs;
    private final SharedPreferences userPrefs;
    private KeyStore keyStore;
    
    // ✅ تخزين مؤقت للقيم المستخدمة كثيراً
    private String cachedAuthToken = null;
    private long tokenCacheTime = 0;
    
    private String cachedApiKey = null;
    private long apiKeyCacheTime = 0;
    
    private Boolean cachedLoggedIn = null;
    private long loggedInCacheTime = 0;
    
    private String cachedUsername = null;
    private long usernameCacheTime = 0;
    
    private String cachedServerUrl = null;
    private long serverUrlCacheTime = 0;
    
    public enum Role {
    SYSTEM_ADMIN,
    ORG_ADMIN,
    FINANCE_MANAGER,   // ⭐ جديد
    INVENTORY_MGR,
    BRANCH_MANAGER,
    ACCOUNTANT,        // ⭐ جديد
    AUDITOR,
    POS_OPERATOR,
    GATE_SECURITY,
    SECURITY,
    ADMIN              // alias قديم
}
    
    // ═══════════════════════════════════════════════════════
    // ✅ Singleton Pattern
    // ═══════════════════════════════════════════════════════
    
    /**
     * ✅ الحصول على النسخة الوحيدة (Singleton)
     * 
     * مهم جداً:
     * - يُنشئ EncryptedSharedPreferences مرة واحدة فقط
     * - يمنع ANR بسبب إعادة تهيئة AndroidKeyStore
     * - آمن للاستخدام من أي thread
     */
    public static SecureSessionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new SecureSessionManager(context.getApplicationContext());
                    Log.d(TAG, "✅ SecureSessionManager Singleton created");
                }
            }
        }
        return instance;
    }
    
    /**
     * ✅ إعادة تعيين النسخة الوحيدة
     * تُستخدم فقط عند logout كامل أو اختبارات
     */
    public static void resetInstance() {
        synchronized (INSTANCE_LOCK) {
            instance = null;
            Log.d(TAG, "🔄 SecureSessionManager Singleton reset");
        }
    }
    
    /**
     * ⚠️ private constructor — استخدم getInstance()
     */
    private SecureSessionManager(Context context) {
        this.userPrefs = context.getApplicationContext().getSharedPreferences(USER_PREFS_NAME, Context.MODE_PRIVATE);
        
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
            Log.e(TAG, "EncryptedSharedPreferences corrupt, re-creating...", e);
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
    
    // ==================== إدارة الجلسة ====================
    
    public void createLoginSession(String username, String role, String token, 
                                   String serverUrl, long expiresInSeconds) {
        try {
            long expiryTime = (expiresInSeconds > 0) 
                    ? (System.currentTimeMillis() + (expiresInSeconds * 1000L)) 
                    : (System.currentTimeMillis() + (86400 * 1000L * 30));
            
            securePrefs.edit()
                    .putString(KEY_AUTH_TOKEN, token != null ? token : "")
                    .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                    .apply();
            
            // ✅ تحديث التخزين المؤقت
            cachedAuthToken = token;
            tokenCacheTime = System.currentTimeMillis();
            
            String finalServerUrl = (serverUrl != null && !serverUrl.isEmpty()) 
                    ? normalizeServerUrl(serverUrl) : DEFAULT_SERVER_URL;
            
            userPrefs.edit()
                    .putBoolean(KEY_IS_LOGGED_IN, true)
                    .putString(KEY_USERNAME, username != null ? username : "")
                    .putString(KEY_USER_ROLE, role != null ? role : Role.INVENTORY_MGR.name())
                    .putString(KEY_LAST_USERNAME, username != null ? username : "")
                    .putString(KEY_SERVER_URL, finalServerUrl)
                    .apply();
            
            // ✅ تحديث التخزين المؤقت
            cachedLoggedIn = true;
            cachedUsername = username;
            cachedServerUrl = finalServerUrl;
            loggedInCacheTime = System.currentTimeMillis();
            usernameCacheTime = System.currentTimeMillis();
            serverUrlCacheTime = System.currentTimeMillis();
            
            Log.d(TAG, "Login session created for user: " + username);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating login session", e);
        }
    }
    
    public void createLoginSession(String username, String role, String token, String serverUrl) {
        createLoginSession(username, role, token, serverUrl, 86400 * 7);
    }
    
    public void updateToken(String newToken, long expiresInSeconds) {
        long expiryTime = System.currentTimeMillis() + (expiresInSeconds * 1000L);
        securePrefs.edit()
                .putString(KEY_AUTH_TOKEN, newToken)
                .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                .apply();
        // ✅ تحديث التخزين المؤقت
        cachedAuthToken = newToken;
        tokenCacheTime = System.currentTimeMillis();
    }
    
    /**
     * ✅ حفظ التوكن (JWT) — كان مفقوداً!
     */
    public void saveAuthToken(String token) {
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "⚠️ Attempted to save empty auth token");
            return;
        }
        try {
            securePrefs.edit().putString(KEY_AUTH_TOKEN, token).apply();
            cachedAuthToken = token;
            tokenCacheTime = System.currentTimeMillis();
            Log.d(TAG, "✅ Auth token saved (" + token.length() + " chars)");
        } catch (Exception e) {
            Log.e(TAG, "saveAuthToken error: " + e.getMessage());
        }
    }

    public void saveAuthToken(String token, long expiresInSeconds) {
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "⚠️ Attempted to save empty auth token");
            return;
        }
        try {
            long expiryTime = (expiresInSeconds > 0)
                    ? (System.currentTimeMillis() + (expiresInSeconds * 1000L))
                    : (System.currentTimeMillis() + (86400 * 1000L * 30));
            
            securePrefs.edit()
                    .putString(KEY_AUTH_TOKEN, token)
                    .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                    .apply();
            
            cachedAuthToken = token;
            tokenCacheTime = System.currentTimeMillis();
            Log.d(TAG, "✅ Auth token saved with expiry: " + expiresInSeconds + "s");
        } catch (Exception e) {
            Log.e(TAG, "saveAuthToken error: " + e.getMessage());
        }
    }
    
    public String getAuthToken() {
        long now = System.currentTimeMillis();
        if (cachedAuthToken != null && (now - tokenCacheTime) < CACHE_VALIDITY_MS) {
            return cachedAuthToken;
        }
        cachedAuthToken = securePrefs.getString(KEY_AUTH_TOKEN, "");
        tokenCacheTime = now;
        return cachedAuthToken;
    }
    
    public boolean hasValidToken() {
        try {
            String token = getAuthToken();
            if (token == null || token.isEmpty()) return false;
            long expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L);
            if (expiryTime == 0L) return true;
            return System.currentTimeMillis() < (expiryTime - TOKEN_EXPIRY_BUFFER);
        } catch (Exception e) {
            return false;
        }
    }
    
    public boolean needsTokenRefresh() {
        try {
            long expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L);
            if (expiryTime == 0L) return false;
            return System.currentTimeMillis() > (expiryTime - REFRESH_THRESHOLD);
        } catch (Exception e) {
            return true;
        }
    }

    public boolean hasOfflineSession() {
        String token = getAuthToken();
        String lastBioToken = getLastBiometricToken();
        return (token != null && !token.isEmpty()) || (lastBioToken != null && !lastBioToken.isEmpty());
    }
    
    public void logoutUser() {
        setSessionLocked(false);
        setLastAppActiveTime(0);
        
        boolean keepBiometric = isBiometricEnabled();
        String apiKey = getApiKey();
        String serverUrl = getServerUrl();
        String lastUsername = getLastUsername();
        String gateId = getGateId();
        
        String biometricUser = getLastBiometricUser();
        String biometricRole = getLastBiometricRole();
        String biometricDisplayName = getLastBiometricDisplayName();
        int biometricUserId = getLastBiometricUserId();
        String biometricToken = getLastBiometricToken();
        int biometricBranchId = getLastBiometricBranchId();
        String biometricBranchName = getLastBiometricBranchName();
        
        securePrefs.edit().clear().apply();
        userPrefs.edit().clear().apply();
        
        // ✅ مسح التخزين المؤقت
        cachedAuthToken = null;
        cachedApiKey = null;
        cachedLoggedIn = false;
        cachedUsername = null;
        cachedServerUrl = null;
        tokenCacheTime = 0;
        apiKeyCacheTime = 0;
        loggedInCacheTime = System.currentTimeMillis();
        usernameCacheTime = 0;
        serverUrlCacheTime = 0;
        
        SharedPreferences.Editor userEditor = userPrefs.edit();
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
        
        if (apiKey != null && !apiKey.isEmpty()) secureEditor.putString(KEY_API_KEY, apiKey);
        if (serverUrl != null && !serverUrl.isEmpty()) userEditor.putString(KEY_SERVER_URL, serverUrl);
        if (lastUsername != null && !lastUsername.isEmpty()) userEditor.putString(KEY_LAST_USERNAME, lastUsername);
        if (gateId != null && !gateId.isEmpty()) userEditor.putString(KEY_GATE_ID, gateId);
        
        userEditor.apply();
        secureEditor.apply();
        
        Log.d(TAG, "User logged out securely");
    }
    
    public void logout() {
        logoutUser();
    }
    
    // ==================== إدارة المستخدم ====================
    
    public boolean isLoggedIn() {
        long now = System.currentTimeMillis();
        if (cachedLoggedIn != null && (now - loggedInCacheTime) < CACHE_VALIDITY_MS) {
            return cachedLoggedIn;
        }
        cachedLoggedIn = userPrefs.getBoolean(KEY_IS_LOGGED_IN, false);
        loggedInCacheTime = now;
        return cachedLoggedIn;
    }
    
    public String getUsername() {
        long now = System.currentTimeMillis();
        if (cachedUsername != null && (now - usernameCacheTime) < CACHE_VALIDITY_MS) {
            return cachedUsername;
        }
        cachedUsername = userPrefs.getString(KEY_USERNAME, "");
        usernameCacheTime = now;
        return cachedUsername;
    }
    
    public String getUserName() {
        return getUsername();
    }
    
    public Role getUserRole() {
        String roleStr = getRoleString();
        try {
            return Role.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            return Role.INVENTORY_MGR;
        }
    }
    
    public String getRoleString() {
        return userPrefs.getString(KEY_USER_ROLE, Role.INVENTORY_MGR.name());
    }
    
    public String getRole() {
        return getRoleString();
    }
    
    public void setRole(String role) {
        userPrefs.edit().putString(KEY_USER_ROLE, role).apply();
    }
    
    public String getDisplayName() {
        String name = userPrefs.getString(KEY_DISPLAY_NAME, "");
        return name.isEmpty() ? getUsername() : name;
    }
    
    public void setDisplayName(String displayName) {
        userPrefs.edit().putString(KEY_DISPLAY_NAME, displayName).apply();
    }
    
    public int getUserId() {
        return userPrefs.getInt(KEY_USER_ID, 0);
    }
    
    public void setUserId(int userId) {
        userPrefs.edit().putInt(KEY_USER_ID, userId).apply();
    }
    
    // ==================== فحوصات الأدوار ====================
    
    // ==================== فحوصات الأدوار ====================

public boolean isSystemAdmin() {
    return Role.SYSTEM_ADMIN.name().equals(getRoleString());
}

/**
 * ✅ "Admin" يشمل: SYSTEM_ADMIN + ORG_ADMIN + FINANCE_MANAGER + ADMIN (legacy)
 * هذه الأدوار يمكنها إدارة النظام والمستخدمين.
 */
public boolean isAnyAdmin() {
    String role = getRoleString();
    return Role.SYSTEM_ADMIN.name().equals(role)
        || Role.ORG_ADMIN.name().equals(role)
        || Role.FINANCE_MANAGER.name().equals(role)
        || Role.ADMIN.name().equals(role);
}

/**
 * ✅ هل يمكنه إعداد النظام المحاسبي؟
 * SYSTEM_ADMIN + ORG_ADMIN + FINANCE_MANAGER + ACCOUNTANT
 */
public boolean canPerformAccountingSetup() {
    String role = getRoleString();
    return Role.SYSTEM_ADMIN.name().equals(role)
        || Role.ORG_ADMIN.name().equals(role)
        || Role.FINANCE_MANAGER.name().equals(role)
        || Role.ACCOUNTANT.name().equals(role);
}

/**
 * ✅ المدير — يشمل admin + branch manager
 */
public boolean isManager() {
    String role = getRoleString();
    return isAnyAdmin()
        || Role.BRANCH_MANAGER.name().equals(role);
}

public boolean isAccountant() {
    return Role.ACCOUNTANT.name().equals(getRoleString());
}

public boolean isFinanceManager() {
    return Role.FINANCE_MANAGER.name().equals(getRoleString());
}

public boolean isInventoryManager() {
    return Role.INVENTORY_MGR.name().equals(getRoleString());
}

public boolean isPosOperator() {
    return Role.POS_OPERATOR.name().equals(getRoleString());
}

public boolean isBranchManager() {
    return Role.BRANCH_MANAGER.name().equals(getRoleString());
}

public boolean isAuditor() {
    return Role.AUDITOR.name().equals(getRoleString());
}

public boolean isSecurity() {
    return Role.SECURITY.name().equals(getRoleString());
}

public boolean isGateSecurity() {
    return Role.GATE_SECURITY.name().equals(getRoleString());
}

public boolean isAdmin() {
    return isAnyAdmin();
}

public boolean isSecurityRelated() {
    String role = getRoleString();
    return Role.SECURITY.name().equals(role)
        || Role.GATE_SECURITY.name().equals(role);
}
    
    // ═══════════════════════════════════════════════════════
    // ✅ الحقول الإضافية للمستخدم (Email, Phone, Username)
    // ═══════════════════════════════════════════════════════

    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_USER_PHONE = "user_phone";

    public String getEmail() {
        return userPrefs.getString(KEY_USER_EMAIL, "");
    }

    public void setEmail(String email) {
        if (email != null) {
            userPrefs.edit().putString(KEY_USER_EMAIL, email).apply();
        }
    }

    public String getPhone() {
        return userPrefs.getString(KEY_USER_PHONE, "");
    }

    public void setPhone(String phone) {
        if (phone != null) {
            userPrefs.edit().putString(KEY_USER_PHONE, phone).apply();
        }
    }

    public void setUsername(String username) {
        if (username != null && !username.isEmpty()) {
            userPrefs.edit().putString(KEY_USERNAME, username).apply();
            cachedUsername = username;
            usernameCacheTime = System.currentTimeMillis();
        }
    }
    
    // ==================== إدارة عنوان الخادم ====================
    
    public String getServerUrl() {
        long now = System.currentTimeMillis();
        if (cachedServerUrl != null && (now - serverUrlCacheTime) < CACHE_VALIDITY_MS) {
            return cachedServerUrl;
        }
        String url = userPrefs.getString(KEY_SERVER_URL, "");
        if (url == null || url.isEmpty()) {
            url = DEFAULT_SERVER_URL;
            setServerUrl(url);
        }
        cachedServerUrl = url;
        serverUrlCacheTime = now;
        return url;
    }
    
    public void setServerUrl(String url) {
        if (url == null || url.isEmpty()) return;
        String normalizedUrl = normalizeServerUrl(url);
        userPrefs.edit().putString(KEY_SERVER_URL, normalizedUrl).apply();
        cachedServerUrl = normalizedUrl;
        serverUrlCacheTime = System.currentTimeMillis();
    }
    
    public boolean hasServerUrl() {
        String url = getServerUrl();
        return url != null && !url.isEmpty();
    }
    
    public void resetServerUrl() {
        userPrefs.edit().remove(KEY_SERVER_URL).apply();
        setServerUrl(DEFAULT_SERVER_URL);
    }
    
    private String normalizeServerUrl(String url) {
        String normalized = url.trim();
        if (!normalized.endsWith("/")) normalized += "/";
        return normalized;
    }
    
    // ==================== إدارة API Key ====================
    
    public void saveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            securePrefs.edit().putString(KEY_API_KEY, apiKey).apply();
            cachedApiKey = apiKey;
            apiKeyCacheTime = System.currentTimeMillis();
        }
    }
    
    public String getApiKey() {
        long now = System.currentTimeMillis();
        if (cachedApiKey != null && (now - apiKeyCacheTime) < CACHE_VALIDITY_MS) {
            return cachedApiKey;
        }
        cachedApiKey = securePrefs.getString(KEY_API_KEY, "");
        apiKeyCacheTime = now;
        return cachedApiKey;
    }
    
    public boolean hasApiKey() {
        return !getApiKey().isEmpty();
    }
    
    public void clearApiKey() {
        securePrefs.edit()
                .remove(KEY_API_KEY)
                .remove(KEY_API_KEY_ID)
                .remove(KEY_API_KEY_PREFIX)
                .remove(KEY_API_KEY_CREATED_AT)
                .apply();
        cachedApiKey = null;
        apiKeyCacheTime = 0;
    }
    
    // ==================== Gate ID ====================
    
    public void saveGateId(String gateId) {
        if (gateId != null && !gateId.isEmpty()) {
            userPrefs.edit().putString(KEY_GATE_ID, gateId).apply();
        }
    }
    
    public String getGateId() {
        return userPrefs.getString(KEY_GATE_ID, "");
    }
    
    public void clearGateId() {
        userPrefs.edit().remove(KEY_GATE_ID).apply();
    }
    
    // ==================== تذكر اسم المستخدم ====================
    
    public void saveLastUsername(String username) {
        if (username != null && !username.isEmpty()) {
            userPrefs.edit().putString(KEY_LAST_USERNAME, username).apply();
        }
    }
    
    public String getLastUsername() {
        return userPrefs.getString(KEY_LAST_USERNAME, "");
    }
    
    public void clearLastUsername() {
        userPrefs.edit().remove(KEY_LAST_USERNAME).apply();
    }
    
    // ==================== المصادقة البيومترية ====================
    
    public void setBiometricEnabled(boolean enabled) {
        userPrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }
    
    public boolean isBiometricEnabled() {
        return userPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }
    
    public void setBiometricLoginEnabled(boolean enabled) {
        setBiometricEnabled(enabled);
    }
    
    public boolean isBiometricLoginEnabled() {
        return isBiometricEnabled();
    }
    
    public void saveBiometricUser(String username, String role, String displayName, int userId, String token) {
        userPrefs.edit()
                .putString(KEY_LAST_BIOMETRIC_USER, username)
                .putString(KEY_LAST_BIOMETRIC_ROLE, role)
                .putString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, displayName)
                .putInt(KEY_LAST_BIOMETRIC_USER_ID, userId)
                .apply();
        securePrefs.edit()
                .putString(KEY_LAST_BIOMETRIC_TOKEN, token)
                .apply();
    }
    
    public String getLastBiometricUser() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_USER, "");
    }
    
    public String getLastBiometricRole() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_ROLE, Role.INVENTORY_MGR.name());
    }
    
    public String getLastBiometricDisplayName() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, "");
    }
    
    public int getLastBiometricUserId() {
        return userPrefs.getInt(KEY_LAST_BIOMETRIC_USER_ID, 0);
    }
    
    public String getLastBiometricToken() {
        return securePrefs.getString(KEY_LAST_BIOMETRIC_TOKEN, "");
    }
    
    public void saveBiometricBranch(int branchId, String branchName) {
        userPrefs.edit()
                .putInt(KEY_LAST_BIOMETRIC_BRANCH_ID, branchId)
                .putString(KEY_LAST_BIOMETRIC_BRANCH_NAME, branchName)
                .apply();
    }
    
    public int getLastBiometricBranchId() {
        return userPrefs.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, -1);
    }
    
    public String getLastBiometricBranchName() {
        return userPrefs.getString(KEY_LAST_BIOMETRIC_BRANCH_NAME, "");
    }
    
    public void clearBiometricData() {
        securePrefs.edit().remove(KEY_LAST_BIOMETRIC_TOKEN).apply();
        userPrefs.edit()
                .remove(KEY_LAST_BIOMETRIC_USER)
                .remove(KEY_LAST_BIOMETRIC_ROLE)
                .remove(KEY_LAST_BIOMETRIC_DISPLAY_NAME)
                .remove(KEY_LAST_BIOMETRIC_USER_ID)
                .remove(KEY_LAST_BIOMETRIC_BRANCH_ID)
                .remove(KEY_LAST_BIOMETRIC_BRANCH_NAME)
                .apply();
    }
    
    // ==================== التشفير البيومتري ====================
    
    public SecretKey createBiometricKey() throws GeneralSecurityException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        android.security.keystore.KeyGenParameterSpec keyGenParameterSpec = 
                new android.security.keystore.KeyGenParameterSpec.Builder(
                        BIOMETRIC_KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | 
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build();
        keyGenerator.init(keyGenParameterSpec);
        return keyGenerator.generateKey();
    }
    
    @Nullable
    public SecretKey getBiometricKey() {
        try {
            if (keyStore == null) return null;
            return (SecretKey) keyStore.getKey(BIOMETRIC_KEY_ALIAS, null);
        } catch (Exception e) {
            return null;
        }
    }
    
    public boolean isBiometricKeyAvailable() {
        return getBiometricKey() != null;
    }
    
    @Nullable
    public String encryptWithBiometricKey(String data) {
        try {
            SecretKey key = getBiometricKey();
            if (key == null) key = createBiometricKey();
            Cipher cipher = Cipher.getInstance(BIOMETRIC_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encryptedData = cipher.doFinal(data.getBytes());
            byte[] iv = cipher.getIV();
            byte[] combined = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting with biometric key", e);
            return null;
        }
    }
    
    @Nullable
    public String decryptWithBiometricKey(String encryptedBase64) {
        try {
            SecretKey key = getBiometricKey();
            if (key == null) return null;
            byte[] combined = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(BIOMETRIC_TRANSFORMATION);
            int ivLength = cipher.getBlockSize();
            byte[] iv = new byte[ivLength];
            byte[] encryptedData = new byte[combined.length - ivLength];
            System.arraycopy(combined, 0, iv, 0, ivLength);
            System.arraycopy(combined, ivLength, encryptedData, 0, encryptedData.length);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decryptedData = cipher.doFinal(encryptedData);
            return new String(decryptedData);
        } catch (Exception e) {
            Log.e(TAG, "Error decrypting with biometric key", e);
            return null;
        }
    }
    
    public void deleteBiometricKey() {
        try {
            if (keyStore != null) keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS);
            clearBiometricData();
            setBiometricEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "Error deleting biometric key", e);
        }
    }
    
    // ═══════════════════════════════════════════════════════
    // ✅ Auto-Lock Support (Inactivity Timeout)
    // ═══════════════════════════════════════════════════════

    private static final String KEY_LAST_ACTIVE_TIME = "last_app_active_time";
    private static final String KEY_SESSION_LOCKED = "session_locked";

    public void setLastAppActiveTime(long timestamp) {
        try {
            userPrefs.edit().putLong(KEY_LAST_ACTIVE_TIME, timestamp).apply();
        } catch (Exception e) {
            Log.e(TAG, "setLastAppActiveTime error: " + e.getMessage());
        }
    }

    public long getLastAppActiveTime() {
        try {
            return userPrefs.getLong(KEY_LAST_ACTIVE_TIME, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public void touchActivity() {
        setLastAppActiveTime(System.currentTimeMillis());
    }

    public void setSessionLocked(boolean locked) {
        try {
            userPrefs.edit().putBoolean(KEY_SESSION_LOCKED, locked).apply();
        } catch (Exception e) {
            Log.e(TAG, "setSessionLocked error: " + e.getMessage());
        }
    }

    public boolean isSessionLocked() {
        try {
            return userPrefs.getBoolean(KEY_SESSION_LOCKED, false);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isInactivityTimeout(long timeoutMs) {
        try {
            long last = getLastAppActiveTime();
            if (last <= 0) return false;
            return (System.currentTimeMillis() - last) > timeoutMs;
        } catch (Exception e) {
            return false;
        }
    }

    public void unlockSession() {
        try {
            setSessionLocked(false);
            touchActivity();
            Log.d(TAG, "Session unlocked");
        } catch (Exception e) {
            Log.e(TAG, "unlockSession error: " + e.getMessage());
        }
    }

    public void resetAutoLock() {
        try {
            setSessionLocked(false);
            touchActivity();
            Log.d(TAG, "Auto-Lock reset");
        } catch (Exception e) {
            Log.e(TAG, "resetAutoLock error: " + e.getMessage());
        }
    }
    
    // ==================== وظائف مساعدة ====================
    
    public void clearAll() {
        securePrefs.edit().clear().apply();
        userPrefs.edit().clear().apply();
        cachedAuthToken = null;
        cachedApiKey = null;
        cachedLoggedIn = false;
        cachedUsername = null;
        cachedServerUrl = null;
        deleteBiometricKey();
        setSessionLocked(false);
        setLastAppActiveTime(0);
    }
    
    public String getSessionSummary() {
        return "isLoggedIn=" + isLoggedIn() +
                ", username=" + getUsername() +
                ", role=" + getRoleString() +
                ", hasValidToken=" + hasValidToken() +
                ", biometricEnabled=" + isBiometricEnabled() +
                ", hasApiKey=" + hasApiKey() +
                ", serverUrl=" + getServerUrl();
    }
    
    public void clearAuthToken() {
        try {
            securePrefs.edit()
                    .remove(KEY_AUTH_TOKEN)
                    .remove(KEY_TOKEN_EXPIRY)
                    .apply();
            cachedAuthToken = null;
            tokenCacheTime = 0;
        } catch (Exception e) {
            Log.e(TAG, "clearAuthToken error: " + e.getMessage());
        }
    }
    
    public void clearSessionCompletely() {
        try {
            securePrefs.edit()
                    .remove(KEY_AUTH_TOKEN)
                    .remove(KEY_TOKEN_EXPIRY)
                    .remove(KEY_REFRESH_TOKEN)
                    .apply();
            userPrefs.edit()
                    .remove(KEY_IS_LOGGED_IN)
                    .remove(KEY_USERNAME)
                    .remove(KEY_USER_ROLE)
                    .apply();
            cachedAuthToken = null;
            cachedLoggedIn = false;
            cachedUsername = null;
            tokenCacheTime = 0;
            loggedInCacheTime = 0;
            usernameCacheTime = 0;
        } catch (Exception e) {
            Log.e(TAG, "clearSessionCompletely error: " + e.getMessage());
        }
    }

    public void saveApiKeyInfo(String apiKey, String keyId, String keyPrefix, String createdAt) {
        securePrefs.edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_API_KEY_ID, keyId)
                .putString(KEY_API_KEY_PREFIX, keyPrefix)
                .putString(KEY_API_KEY_CREATED_AT, createdAt)
                .apply();
        cachedApiKey = apiKey;
        apiKeyCacheTime = System.currentTimeMillis();
    }

    public String getApiKeyId() {
        return securePrefs.getString(KEY_API_KEY_ID, "");
    }

    public String getApiKeyPrefix() {
        return securePrefs.getString(KEY_API_KEY_PREFIX, "");
    }

    public void setBiometricConfirmEnabled(boolean enabled) {
        userPrefs.edit().putBoolean(KEY_BIOMETRIC_CONFIRM_ENABLED, enabled).apply();
    }

    public boolean isBiometricConfirmEnabled() {
        return userPrefs.getBoolean(KEY_BIOMETRIC_CONFIRM_ENABLED, false);
    }
    
    // ==================== معرف الفرع ====================

    public void setBranchId(int branchId) {
        try {
            userPrefs.edit().putInt(KEY_BRANCH_ID, branchId).apply();
            Log.d(TAG, "Branch ID saved: " + branchId);
        } catch (Exception e) {
            Log.e(TAG, "setBranchId error: " + e.getMessage());
        }
    }

    public int getBranchId() {
        try {
            int branchId = userPrefs.getInt(KEY_BRANCH_ID, -1);
            if (branchId > 0) return branchId;

            int bioBranchId = getLastBiometricBranchId();
            if (bioBranchId > 0) return bioBranchId;

            return -1;
        } catch (Exception e) {
            Log.e(TAG, "getBranchId error: " + e.getMessage());
            return -1;
        }
    }
    
    public void setBranchName(String branchName) {
        try {
            userPrefs.edit().putString(KEY_BRANCH_NAME, branchName).apply();
        } catch (Exception e) {
            Log.e(TAG, "setBranchName error: " + e.getMessage());
        }
    }

    public String getBranchName() {
        return userPrefs.getString(KEY_BRANCH_NAME, "");
    }
    }
