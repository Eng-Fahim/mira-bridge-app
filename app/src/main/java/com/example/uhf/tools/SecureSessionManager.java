package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * SecureSessionManager - إدارة الجلسات الآمنة
 * نسخة Java متوافقة مع المشروع الحالي
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
    private static final String KEY_LAST_BIOMETRIC_TOKEN = "last_biometric_token";
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
    
    private final SharedPreferences securePrefs;
    private final SharedPreferences userPrefs;
    private final KeyStore keyStore;
    
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
        try {
            // إنشاء التخزين المشفر
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            
            this.securePrefs = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            
            // التخزين العادي
            this.userPrefs = context.getSharedPreferences(USER_PREFS_NAME, Context.MODE_PRIVATE);
            
            // تهيئة KeyStore
            this.keyStore = KeyStore.getInstance("AndroidKeyStore");
            this.keyStore.load(null);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing SecureSessionManager", e);
            throw new RuntimeException("Failed to initialize secure storage", e);
        }
    }
    
    // ==================== إدارة الجلسة ====================
    
    /**
     * إنشاء جلسة دخول جديدة
     */
    public void createLoginSession(String username, String role, String token, 
                                   String serverUrl, long expiresInSeconds) {
        try {
            long expiryTime = System.currentTimeMillis() + (expiresInSeconds * 1000L);
            
            // حفظ البيانات الحساسة مشفرة
            securePrefs.edit()
                    .putString(KEY_AUTH_TOKEN, token)
                    .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                    .apply();
            
            // حفظ البيانات غير الحساسة
            String finalServerUrl = (serverUrl != null && !serverUrl.isEmpty()) 
                    ? normalizeServerUrl(serverUrl) 
                    : DEFAULT_SERVER_URL;
            
            userPrefs.edit()
                    .putBoolean(KEY_IS_LOGGED_IN, true)
                    .putString(KEY_USERNAME, username)
                    .putString(KEY_USER_ROLE, role)
                    .putString(KEY_LAST_USERNAME, username)
                    .putString(KEY_SERVER_URL, finalServerUrl)
                    .apply();
            
            Log.d(TAG, "Login session created for user: " + username);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating login session", e);
            throw new SecurityException("Failed to create secure session", e);
        }
    }
    
    /**
     * إنشاء جلسة دخول جديدة (متوافقة مع الواجهة القديمة)
     */
    public void createLoginSession(String username, String role, String token, String serverUrl) {
        createLoginSession(username, role, token, serverUrl, 3600); // ساعة واحدة افتراضياً
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
     * التحقق من صلاحية التوكن
     */
    public boolean hasValidToken() {
        try {
            long expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L);
            return System.currentTimeMillis() < expiryTime - TOKEN_EXPIRY_BUFFER;
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
            return System.currentTimeMillis() > expiryTime - REFRESH_THRESHOLD;
        } catch (Exception e) {
            return true;
        }
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
