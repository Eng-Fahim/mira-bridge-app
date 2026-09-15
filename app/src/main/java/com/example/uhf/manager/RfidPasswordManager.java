package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RfidPasswordManager - إدارة آمنة لكلمات مرور شرائح RFID
 * 
 * ✅ الميزات:
 * - تخزين مشفر بـ EncryptedSharedPreferences (AES-256)
 * - دعم 3 أوضاع: ثابت / مشتق من TID / من السيرفر
 * - واجهة موحدة لكل طبقات التطبيق
 * - لا كلمات مرور في الكود
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
    
    /**
     * أوضاع كلمة المرور المتاحة
     */
    public enum PasswordMode {
        /** كلمة مرور ثابتة (من إعدادات المستخدم) */
        STATIC,
        /** مشتقة من TID + Salt (فريدة لكل شريحة) */
        DERIVED_FROM_TID,
        /** من السيرفر (للمؤسسات الكبيرة) */
        FROM_SERVER
    }
    
    private static RfidPasswordManager instance;
    private final Context context;
    private SharedPreferences securePrefs;
    private PasswordMode currentMode;
    private String cachedDefaultPwd;
    private String cachedSalt;
    
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
    
    /**
     * ✅ تهيئة EncryptedSharedPreferences (AES-256-GCM)
     * 
     * يتم إنشاء MasterKey في Android Keystore (غير قابل للاستخراج)
     */
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
            Log.i(TAG, "✅ EncryptedSharedPreferences initialized");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to init EncryptedSharedPreferences", e);
            // Fallback: استخدام SharedPreferences عادي (غير آمن لكن يعمل)
            securePrefs = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE);
        }
    }
    
    private void loadSettings() {
        String modeStr = securePrefs.getString(KEY_PWD_MODE, 
                PasswordMode.STATIC.name());
        try {
            currentMode = PasswordMode.valueOf(modeStr);
        } catch (Exception e) {
            currentMode = PasswordMode.STATIC;
        }
        
        cachedDefaultPwd = securePrefs.getString(KEY_DEFAULT_PWD, null);
        cachedSalt = securePrefs.getString(KEY_SALT, null);
        
        // توليد Salt إذا لم يكن موجوداً
        if (cachedSalt == null) {
            cachedSalt = generateSalt();
            securePrefs.edit().putString(KEY_SALT, cachedSalt).apply();
        }
        
        Log.i(TAG, "📋 Password mode: " + currentMode);
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
    
    /**
     * ✅ الدالة الرئيسية: احصل على كلمة المرور للشريحة
     * 
     * @param tid TID الشريحة (مطلوب فقط في وضع DERIVED_FROM_TID)
     * @return كلمة مرور 8 hex chars (16 حرف) أو null إذا لم تُضبط
     */
    public String getPasswordForTag(String tid) {
        switch (currentMode) {
            case STATIC:
                return cachedDefaultPwd;
                
            case DERIVED_FROM_TID:
                if (tid == null || tid.isEmpty()) {
                    Log.w(TAG, "getPasswordForTag: TID required for DERIVED mode");
                    return cachedDefaultPwd; // fallback
                }
                return derivePasswordFromTid(tid);
                
            case FROM_SERVER:
                // TODO: تطبيق جلب من السيرفر (يحتاج مؤشر ترابط)
                Log.w(TAG, "FROM_SERVER mode not yet implemented — using cached");
                return cachedDefaultPwd;
                
            default:
                return cachedDefaultPwd;
        }
    }
    
    /**
     * ✅ اشتقاق كلمة مرور فريدة من TID
     * 
     * الصيغة: SHA-256(TID + SALT) → أول 8 hex chars
     */
    private String derivePasswordFromTid(String tid) {
        try {
            String input = tid.toUpperCase() + cachedSalt;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            
            // تحويل إلى hex string
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 4; i++) { // 4 bytes = 8 hex chars
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            String derivedPwd = hexString.toString().toUpperCase();
            Log.d(TAG, "🔑 Derived password for TID " + tid + ": " + derivedPwd);
            return derivedPwd;
        } catch (Exception e) {
            Log.e(TAG, "derivePasswordFromTid error", e);
            return cachedDefaultPwd;
        }
    }
    
    // =========================================================================
    // ⚙️ إدارة الإعدادات
    // =========================================================================
    
    /**
     * ✅ تعيين كلمة المرور الافتراضية
     */
    public void setDefaultPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            Log.w(TAG, "setDefaultPassword: empty password ignored");
            return;
        }
        
        String normalized = password.trim().toUpperCase();
        if (normalized.length() != 8) {
            Log.w(TAG, "setDefaultPassword: password should be 8 hex chars");
        }
        
        cachedDefaultPwd = normalized;
        securePrefs.edit().putString(KEY_DEFAULT_PWD, normalized).apply();
        Log.i(TAG, "✅ Default password updated");
    }
    
    /**
     * ✅ تعيين وضع كلمة المرور
     */
    public void setPasswordMode(PasswordMode mode) {
        currentMode = mode;
        securePrefs.edit().putString(KEY_PWD_MODE, mode.name()).apply();
        Log.i(TAG, "✅ Password mode set to: " + mode);
    }
    
    public PasswordMode getPasswordMode() {
        return currentMode;
    }
    
    /**
     * ✅ هل تم ضبط كلمة المرور؟
     */
    public boolean hasDefaultPassword() {
        return cachedDefaultPwd != null && !cachedDefaultPwd.isEmpty();
    }
    
    /**
     * ✅ مسح كلمة المرور (لأغراض الأمان عند تسجيل الخروج)
     */
    public void clearPassword() {
        cachedDefaultPwd = null;
        securePrefs.edit().remove(KEY_DEFAULT_PWD).apply();
        Log.i(TAG, "🗑️ Password cleared");
    }
    
    /**
     * ✅ إعادة تعيين كل الإعدادات
     */
    public void resetAll() {
        securePrefs.edit().clear().apply();
        cachedDefaultPwd = null;
        cachedSalt = null;
        currentMode = PasswordMode.STATIC;
        Log.i(TAG, "🔄 All settings reset");
    }
}
