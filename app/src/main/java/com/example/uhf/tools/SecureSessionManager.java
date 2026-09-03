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
 * المميزات:
 * - تشفير كامل للبيانات الحساسة باستخدام EncryptedSharedPreferences مع معالجة حماية الانهيار (Fallback Safety)
 * - دعم المصادقة البيومترية والأوفلاين
 * - إدارة صلاحية التوكن
 * - متوافق مع النظام الحالي
 * 
 * @version 2.1.0
 */
public class SecureSessionManager {
    
    private static final String TAG = "SecureSessionManager";
    
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
    private static final long TOKEN_EXPIRY_BUFFER = 60000L; // دقيقة واحدة
    private static final long REFRESH_THRESHOLD = 300000L; // 5 دقائق
    
    // التشفير البيومتري
    private static final String BIOMETRIC_KEY_ALIAS = "mira_biometric_key";
    private static final String BIOMETRIC_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    
    private SharedPreferences securePrefs;
    private final SharedPreferences userPrefs;
    private KeyStore keyStore;
    
    public enum Role {
        SYSTEM_ADMIN,
        ORG_ADMIN,
        ADMIN,
        BRANCH_MANAGER,
        INVENTORY_MGR,
        POS_OPERATOR,
        SECURITY,
        GATE_SECURITY,
        AUDITOR
    }
    
    public SecureSessionManager(Context context) {
        // التخزين العادي
        this.userPrefs = context.getApplicationContext().getSharedPreferences(USER_PREFS_NAME, Context.MODE_PRIVATE);
        
        // تهيئة KeyStore
        try {
            this.keyStore = KeyStore.getInstance("AndroidKeyStore");
            this.keyStore.load(null);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing KeyStore", e);
        }

        // إنشاء التخزين المشفر مع حماية من الانهيار (Crash Safe Init)
        this.securePrefs = createEncryptedSharedPreferences(context.getApplicationContext());
    }

    /**
     * إنشاء EncryptedSharedPreferences بأمان وإعادة التجديد عند حدوث Corrupted Key
     */
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
            Log.e(TAG, "EncryptedSharedPreferences corrupt or unreadable, re-creating...", e);
            
            // حذف الملف التالف لإنقاذ التطبيق من الانهيار
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.deleteSharedPreferences(SECURE_PREFS_NAME);
                } else {
                    File prefFile = new File(context.getFilesDir().getParent() + "/shared_prefs/" + SECURE_PREFS_NAME + ".xml");
                    if (prefFile.exists()) {
                        prefFile.delete();
                    }
                }
                
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
            } catch (Exception ex) {
                Log.e(TAG, "Fallback to standard unencrypted shared prefs due to critical crypto error", ex);
                return context.getSharedPreferences(SECURE_PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
            }
        }
    }
    
    // ==================== إدارة الجلسة ====================
    
    /**
     * إنشاء جلسة دخول جديدة (بالوقت المحدد)
     */
    public void createLoginSession(String username, String role, String token, 
                                   String serverUrl, long expiresInSeconds) {
        try {
            long expiryTime = (expiresInSeconds > 0) 
                    ? (System.currentTimeMillis() + (expiresInSeconds * 1000L)) 
                    : (System.currentTimeMillis() + (86400 * 1000L * 30)); // 30 يوماً أوفلاين افتراضياً
            
            // حفظ البيانات الحساسة مشفرة
            securePrefs.edit()
                    .putString(KEY_AUTH_TOKEN, token != null ? token : "")
                    .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                    .apply();
            
            // حفظ البيانات غير الحساسة
            String finalServerUrl = (serverUrl != null && !serverUrl.isEmpty()) 
                    ? normalizeServerUrl(serverUrl) 
                    : DEFAULT_SERVER_URL;
            
            userPrefs.edit()
                    .putBoolean(KEY_IS_LOGGED_IN, true)
                    .putString(KEY_USERNAME, username != null ? username : "")
                    .putString(KEY_USER_ROLE, role != null ? role : Role.INVENTORY_MGR.name())
                    .putString(KEY_LAST_USERNAME, username != null ? username : "")
                    .putString(KEY_SERVER_URL, finalServerUrl)
                    .apply();
            
            Log.d(TAG, "Login session created for user: " + username);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating login session", e);
        }
    }
    
    /**
     * إنشاء جلسة دخول جديدة (متوافقة مع الواجهة القديمة)
     */
    public void createLoginSession(String username, String role, String token, String serverUrl) {
        createLoginSession(username, role, token, serverUrl, 86400 * 7); // 7 أيام افتراضياً
    }
    
    /**
     * تحديث التوكن
     */
    public void updateToken(String newToken, long expiresInSeconds) {
        long expiryTime = System.currentTimeMillis() + (expiresInSeconds * 1000L);
        securePrefs.edit()
                .putString(KEY_AUTH_TOKEN, newToken)
                .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                .apply();
    }
    
    /**
     * الحصول على التوكن
     */
    public String getAuthToken() {
        return securePrefs.getString(KEY_AUTH_TOKEN, "");
    }
    
    /**
     * التحقق من صلاحية التوكن (محمية للأوفلاين)
     */
    public boolean hasValidToken() {
        try {
            String token = getAuthToken();
            if (token == null || token.isEmpty()) {
                return false;
            }
            long expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L);
            // إذا لم يتم تعيين تاريخ انتهاء صريح، يعتبر التوكن الموجود صالحاً
            if (expiryTime == 0L) {
                return true;
            }
            return System.currentTimeMillis() < (expiryTime - TOKEN_EXPIRY_BUFFER);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * التحقق من الحاجة لتجديد التوكن
     */
    public boolean needsTokenRefresh() {
        try {
            long expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L);
            if (expiryTime == 0L) return false;
            return System.currentTimeMillis() > (expiryTime - REFRESH_THRESHOLD);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * التحقق من وجود جلسة قابلة للعمل أوفلاين
     */
    public boolean hasOfflineSession() {
        String token = getAuthToken();
        String lastBioToken = getLastBiometricToken();
        return (token != null && !token.isEmpty()) || (lastBioToken != null && !lastBioToken.isEmpty());
    }
    
    /**
     * تسجيل الخروج الآمن
     */
    public void logoutUser() {
        // حفظ البيانات التي يجب أن تبقى
        boolean keepBiometric = isBiometricEnabled();
        String apiKey = getApiKey();
        String serverUrl = getServerUrl();
        String lastUsername = getLastUsername();
        String gateId = getGateId();
        
        // حفظ البيانات البيومترية إذا كانت مفعلة
        String biometricUser = getLastBiometricUser();
        String biometricRole = getLastBiometricRole();
        String biometricDisplayName = getLastBiometricDisplayName();
        int biometricUserId = getLastBiometricUserId();
        String biometricToken = getLastBiometricToken();
        int biometricBranchId = getLastBiometricBranchId();
        String biometricBranchName = getLastBiometricBranchName();
        
        // مسح جميع البيانات
        securePrefs.edit().clear().apply();
        userPrefs.edit().clear().apply();
        
        // استعادة البيانات الضرورية
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
        
        if (apiKey != null && !apiKey.isEmpty()) {
            secureEditor.putString(KEY_API_KEY, apiKey);
        }
        
        if (serverUrl != null && !serverUrl.isEmpty()) {
            userEditor.putString(KEY_SERVER_URL, serverUrl);
        }
        
        if (lastUsername != null && !lastUsername.isEmpty()) {
            userEditor.putString(KEY_LAST_USERNAME, lastUsername);
        }
        
        if (gateId != null && !gateId.isEmpty()) {
            userEditor.putString(KEY_GATE_ID, gateId);
        }
        
        userEditor.apply();
        secureEditor.apply();
        
        Log.d(TAG, "User logged out securely");
    }
    
    /**
     * تسجيل الخروج (متوافقة مع الواجهة القديمة)
     */
    public void logout() {
        logoutUser();
    }
    
    // ==================== إدارة المستخدم ====================
    
    public boolean isLoggedIn() {
        return userPrefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }
    
    public String getUsername() {
        return userPrefs.getString(KEY_USERNAME, "");
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
    
    public boolean isSystemAdmin() {
        return Role.SYSTEM_ADMIN.name().equals(getRoleString());
    }
    
    public boolean isAnyAdmin() {
        String role = getRoleString();
        return Role.SYSTEM_ADMIN.name().equals(role) ||
                Role.ORG_ADMIN.name().equals(role) ||
                Role.ADMIN.name().equals(role);
    }
    
    public boolean isAdmin() {
        return isAnyAdmin();
    }
    
    public boolean isManager() {
        String role = getRoleString();
        return isAnyAdmin() || Role.BRANCH_MANAGER.name().equals(role);
    }
    
    public boolean isSecurityRelated() {
        String role = getRoleString();
        return Role.SECURITY.name().equals(role) ||
                Role.GATE_SECURITY.name().equals(role);
    }
    
    // ==================== إدارة عنوان الخادم ====================
    
    public String getServerUrl() {
        String url = userPrefs.getString(KEY_SERVER_URL, "");
        if (url == null || url.isEmpty()) {
            url = DEFAULT_SERVER_URL;
            setServerUrl(url);
        }
        return url;
    }
    
    public void setServerUrl(String url) {
        if (url == null || url.isEmpty()) {
            Log.e(TAG, "Cannot save empty server URL!");
            return;
        }
        
        String normalizedUrl = normalizeServerUrl(url);
        userPrefs.edit().putString(KEY_SERVER_URL, normalizedUrl).apply();
        Log.d(TAG, "Server URL saved: " + normalizedUrl);
    }
    
    public boolean hasServerUrl() {
        String url = userPrefs.getString(KEY_SERVER_URL, "");
        return url != null && !url.isEmpty();
    }
    
    public void resetServerUrl() {
        userPrefs.edit().remove(KEY_SERVER_URL).apply();
        setServerUrl(DEFAULT_SERVER_URL);
    }
    
    private String normalizeServerUrl(String url) {
        String normalized = url.trim();
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }
    
    // ==================== إدارة API Key ====================
    
    public void saveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            securePrefs.edit().putString(KEY_API_KEY, apiKey).apply();
            Log.d(TAG, "API Key saved");
        }
    }
    
    public void saveApiKeyInfo(String apiKey, String keyId, String keyPrefix, String createdAt) {
        securePrefs.edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_API_KEY_ID, keyId)
                .putString(KEY_API_KEY_PREFIX, keyPrefix)
                .putString(KEY_API_KEY_CREATED_AT, createdAt)
                .apply();
    }
    
    public String getApiKey() {
        return securePrefs.getString(KEY_API_KEY, "");
    }
    
    public String getApiKeyId() {
        return securePrefs.getString(KEY_API_KEY_ID, "");
    }
    
    public String getApiKeyPrefix() {
        return securePrefs.getString(KEY_API_KEY_PREFIX, "");
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
        Log.d(TAG, "API Key cleared");
    }
    
    // ==================== إدارة Gate ID ====================
    
    public void saveGateId(String gateId) {
        if (gateId != null && !gateId.isEmpty()) {
            userPrefs.edit().putString(KEY_GATE_ID, gateId).apply();
            Log.d(TAG, "Gate ID saved");
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
    
    public void setBiometricConfirmEnabled(boolean enabled) {
        userPrefs.edit().putBoolean(KEY_BIOMETRIC_CONFIRM_ENABLED, enabled).apply();
    }
    
    public boolean isBiometricConfirmEnabled() {
        return userPrefs.getBoolean(KEY_BIOMETRIC_CONFIRM_ENABLED, false);
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
        return userPrefs.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, 1);
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
    
    /**
     * إنشاء مفتاح بيومتري في KeyStore
     */
    public SecretKey createBiometricKey() throws GeneralSecurityException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                "AES", "AndroidKeyStore");
        
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
    
    /**
     * الحصول على المفتاح البيومتري
     */
    @Nullable
    public SecretKey getBiometricKey() {
        try {
            if (keyStore == null) return null;
            return (SecretKey) keyStore.getKey(BIOMETRIC_KEY_ALIAS, null);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * التحقق من توفر المفتاح البيومتري
     */
    public boolean isBiometricKeyAvailable() {
        return getBiometricKey() != null;
    }
    
    /**
     * تشفير البيانات باستخدام المفتاح البيومتري
     */
    @Nullable
    public String encryptWithBiometricKey(String data) {
        try {
            SecretKey key = getBiometricKey();
            if (key == null) {
                key = createBiometricKey();
            }
            
            Cipher cipher = Cipher.getInstance(BIOMETRIC_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            
            byte[] encryptedData = cipher.doFinal(data.getBytes());
            byte[] iv = cipher.getIV();
            
            // حفظ IV + البيانات المشفرة معاً
            byte[] combined = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);
            
            return Base64.encodeToString(combined, Base64.NO_WRAP);
            
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting with biometric key", e);
            return null;
        }
    }
    
    /**
     * فك تشفير البيانات باستخدام المفتاح البيومتري
     */
    @Nullable
    public String decryptWithBiometricKey(String encryptedBase64) {
        try {
            SecretKey key = getBiometricKey();
            if (key == null) {
                return null;
            }
            
            byte[] combined = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            
            // استخراج IV والبيانات المشفرة
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
    
    /**
     * حذف المفتاح البيومتري
     */
    public void deleteBiometricKey() {
        try {
            if (keyStore != null) {
                keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS);
            }
            clearBiometricData();
            setBiometricEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "Error deleting biometric key", e);
        }
    }
    
    // ==================== وظائف مساعدة ====================
    
    /**
     * مسح جميع البيانات
     */
    public void clearAll() {
        securePrefs.edit().clear().apply();
        userPrefs.edit().clear().apply();
        deleteBiometricKey();
        Log.d(TAG, "All session data cleared");
    }
    
    /**
     * الحصول على ملخص حالة الجلسة
     */
    public String getSessionSummary() {
        return "isLoggedIn=" + isLoggedIn() +
                ", username=" + getUsername() +
                ", role=" + getRoleString() +
                ", hasValidToken=" + hasValidToken() +
                ", biometricEnabled=" + isBiometricEnabled() +
                ", hasApiKey=" + hasApiKey() +
                ", serverUrl=" + getServerUrl();
    }
}
