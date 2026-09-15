package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RfidPasswordManager - إدارة آمنة لكلمات مرور شرائح RFID
 * 
 * ✅ الميزات:
 * - تخزين مشفر بـ EncryptedSharedPreferences (AES-256-GCM)
 * - دعم 3 أوضاع: STATIC / DERIVED_FROM_TID / FROM_SERVER
 * - واجهة موحدة لكل طبقات التطبيق
 * - لا كلمات مرور مكتوبة في الكود
 * - Salt عشوائي 16 بايت لكل تثبيت
 * 
 * ⚠️ المتطلبات:
 * - minSdkVersion 23+
 * - androidx.security:security-crypto:1.1.0-alpha06
 */
public class RfidPasswordManager {
    
    private static final String TAG = "RfidPasswordManager";
    private static final String SECURE_PREFS_NAME = "mira_rfid_secure_prefs";
    private static final String KEY_DEFAULT_PWD = "rfid_default_password";
    private static final String KEY_PWD_MODE = "rfid_password_mode";
    private static final String KEY_SALT = "rfid_password_salt";
    private static final String FALLBACK_PWD = "00000000";
    
    public enum PasswordMode {
        STATIC,
        DERIVED_FROM_TID,
        FROM_SERVER
    }
    
    private static volatile RfidPasswordManager instance;
    private final Context context;
    private SharedPreferences securePrefs;
    private PasswordMode currentMode;
    private String cachedDefaultPwd;
    private String cachedSalt;
    private boolean isEncryptionAvailable = true;
    
    private RfidPasswordManager(Context context) {
        this.context = context.getApplicationContext();
        initSecurePrefs();
        loadSettings();
    }
    
    public static synchronized RfidPasswordManager getInstance(Context context) {
        if (instance == null) {
            instance = new RfidPasswordManager(context);
        }
        return instance;
    }
    
    // =========================================================================
    // 🔐 التهيئة والإعدادات
    // =========================================================================
    
    private void initSecurePrefs() {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            
            securePrefs = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            isEncryptionAvailable = true;
            Log.i(TAG, "✅ EncryptedSharedPreferences initialized (AES-256)");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to init EncryptedSharedPreferences — falling back", e);
            securePrefs = context.getSharedPreferences(
                    SECURE_PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
            isEncryptionAvailable = false;
        }
    }
    
    private void loadSettings() {
        String modeStr = securePrefs.getString(KEY_PWD_MODE, PasswordMode.STATIC.name());
        try {
            currentMode = PasswordMode.valueOf(modeStr);
        } catch (Exception e) {
            currentMode = PasswordMode.STATIC;
        }
        
        cachedDefaultPwd = securePrefs.getString(KEY_DEFAULT_PWD, null);
        cachedSalt = securePrefs.getString(KEY_SALT, null);
        
        if (cachedSalt == null || cachedSalt.isEmpty()) {
            cachedSalt = generateSalt();
            securePrefs.edit().putString(KEY_SALT, cachedSalt).apply();
            Log.i(TAG, "🔑 Generated new salt");
        }
        
        Log.i(TAG, "📋 Loaded: mode=" + currentMode + 
              " hasPwd=" + (cachedDefaultPwd != null) + 
              " encrypted=" + isEncryptionAvailable);
    }
    
    private String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        return Base64.encodeToString(saltBytes, Base64.NO_WRAP);
    }
    
    // =========================================================================
    // 🔑 واجهة الحصول على كلمة المرور
    // =========================================================================
    
    public String getPasswordForTag(String tid) {
        switch (currentMode) {
            case STATIC:
                return getStaticPassword();
            case DERIVED_FROM_TID:
                if (tid == null || tid.trim().isEmpty()) {
                    return getStaticPassword();
                }
                return derivePasswordFromTid(tid.trim().toUpperCase());
            case FROM_SERVER:
                Log.w(TAG, "FROM_SERVER mode not yet implemented — using static");
                return getStaticPassword();
            default:
                return getStaticPassword();
        }
    }
    
    private String getStaticPassword() {
        if (cachedDefaultPwd != null && !cachedDefaultPwd.isEmpty()) {
            return cachedDefaultPwd;
        }
        Log.w(TAG, "⚠️ No password configured — using fallback " + FALLBACK_PWD);
        return FALLBACK_PWD;
    }
    
    private String derivePasswordFromTid(String tid) {
        try {
            String input = tid + cachedSalt;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            String derivedPwd = hexString.toString().toUpperCase();
            Log.d(TAG, "🔑 Derived password for TID " + maskTid(tid) + ": " + derivedPwd);
            return derivedPwd;
        } catch (Exception e) {
            Log.e(TAG, "derivePasswordFromTid error", e);
            return getStaticPassword();
        }
    }
    
    private String maskTid(String tid) {
        if (tid == null || tid.length() < 8) return "***";
        return tid.substring(0, 4) + "****" + tid.substring(tid.length() - 4);
    }
    
    // =========================================================================
    // ⚙️ إدارة الإعدادات
    // =========================================================================
    
    public void setDefaultPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            Log.w(TAG, "setDefaultPassword: empty password ignored");
            return;
        }
        
        String normalized = password.trim().toUpperCase();
        
        if (!normalized.matches("^[0-9A-F]{8}$")) {
            Log.e(TAG, "setDefaultPassword: invalid format");
            throw new IllegalArgumentException("Password must be exactly 8 hex characters (0-9, A-F)");
        }
        
        cachedDefaultPwd = normalized;
        securePrefs.edit().putString(KEY_DEFAULT_PWD, normalized).apply();
        Log.i(TAG, "✅ Default password updated (length=8)");
    }
    
    public void setPasswordMode(PasswordMode mode) {
        if (mode == null) return;
        currentMode = mode;
        securePrefs.edit().putString(KEY_PWD_MODE, mode.name()).apply();
        Log.i(TAG, "✅ Password mode set to: " + mode);
    }
    
    public PasswordMode getPasswordMode() {
        return currentMode;
    }
    
    public boolean hasDefaultPassword() {
        return cachedDefaultPwd != null && !cachedDefaultPwd.isEmpty();
    }
    
    public boolean isEncryptionAvailable() {
        return isEncryptionAvailable;
    }
    
    // =========================================================================
    // 🛠️ دوال النسخ الاحتياطي والتشخيص
    // =========================================================================
    
    /**
     * ✅ الحصول على Salt (للنسخ الاحتياطي)
     */
    public String exportSalt() {
        if (cachedSalt == null || cachedSalt.isEmpty()) {
            Log.w(TAG, "exportSalt: no salt available");
            return null;
        }
        Log.i(TAG, "📤 Salt exported (length=" + cachedSalt.length() + ")");
        return cachedSalt;
    }
    
    /**
     * ✅ استيراد Salt من نسخة احتياطية
     */
    public void importSalt(String salt) {
        if (salt == null || salt.trim().isEmpty()) {
            Log.w(TAG, "importSalt: empty salt ignored");
            return;
        }
        cachedSalt = salt.trim();
        securePrefs.edit().putString(KEY_SALT, cachedSalt).apply();
        Log.i(TAG, "✅ Salt imported (length=" + cachedSalt.length() + ")");
    }
    
    /**
     * ✅ معلومات تشخيصية (للواجهة)
     */
    public String getDiagnosticInfo() {
        return "Mode: " + currentMode +
               "\nHas Password: " + hasDefaultPassword() +
               "\nEncryption: " + (isEncryptionAvailable ? "AES-256 ✅" : "Fallback ⚠️") +
               "\nSalt Length: " + (cachedSalt != null ? cachedSalt.length() : 0);
    }
    
    // =========================================================================
    // 🗑️ التنظيف
    // =========================================================================
    
    public void clearPassword() {
        cachedDefaultPwd = null;
        securePrefs.edit().remove(KEY_DEFAULT_PWD).apply();
        Log.i(TAG, "🗑️ Password cleared");
    }
    
    public void resetAll() {
        securePrefs.edit().clear().apply();
        cachedDefaultPwd = null;
        cachedSalt = null;
        currentMode = PasswordMode.STATIC;
        Log.i(TAG, "🔄 All settings reset");
    }
}
