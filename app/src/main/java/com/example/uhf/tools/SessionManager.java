package com.example.uhf.tools;

import android.content.Context;
import android.util.Log;

/**
 * SessionManager - كلاس التوافق الوسيط
 * 
 * ✅ v2.0 — تحسين الأداء:
 * - استخدام SecureSessionManager.getInstance() بدلاً من new
 * - هذا يمنع ANR بسبب إعادة تهيئة EncryptedSharedPreferences
 * 
 * يحافظ على الواجهة القديمة لجميع الاستدعاءات في التطبيق،
 * لكنه يوجهها داخليًا إلى SecureSessionManager الآمن.
 * 
 * @deprecated استخدم SecureSessionManager مباشرة في الكود الجديد
 */
@Deprecated
public class SessionManager {

    private static final String TAG = "SessionManager";
    
    private final SecureSessionManager secureManager;
    private final Context context;
    
    // ✅ تخزين مؤقت للقيم المستخدمة كثيراً
    private String cachedUsername = null;
    private Boolean cachedLoggedIn = null;
    private long lastCheckTime = 0;
    private static final long CACHE_VALIDITY = 3000; // 3 ثواني

    public SessionManager(Context context) {
        this.context = context.getApplicationContext();
        // ✅ استخدام Singleton — لا يُنشئ EncryptedSharedPreferences جديد
        this.secureManager = SecureSessionManager.getInstance(context);
        Log.d(TAG, "SessionManager created (using Singleton)");
    }

    public boolean isLoggedIn() {
        long now = System.currentTimeMillis();
        if (cachedLoggedIn != null && (now - lastCheckTime) < CACHE_VALIDITY) {
            return cachedLoggedIn;
        }
        
        cachedLoggedIn = secureManager.isLoggedIn();
        lastCheckTime = now;
        return cachedLoggedIn;
    }

    public String getUsername() {
        long now = System.currentTimeMillis();
        if (cachedUsername != null && (now - lastCheckTime) < CACHE_VALIDITY) {
            return cachedUsername;
        }
        
        cachedUsername = secureManager.getUsername();
        return cachedUsername;
    }

    public String getUserName() {
        return secureManager.getUsername();
    }

    public String getAuthToken() {
        return secureManager.getAuthToken();
    }

    public boolean hasValidToken() {
        return secureManager.hasValidToken();
    }

    public boolean hasOfflineSession() {
        String lastUser = getLastBiometricUser();
        String lastToken = getLastBiometricToken();
        return (lastUser != null && !lastUser.isEmpty()) || (lastToken != null && !lastToken.isEmpty());
    }

    public boolean needsTokenRefresh() {
        return secureManager.needsTokenRefresh();
    }

    // ==================== الأدوار ====================
    
    public SecureSessionManager.Role getUserRole() {
        return secureManager.getUserRole();
    }

    public String getRoleString() {
        return secureManager.getRoleString();
    }

    public String getRole() {
        return secureManager.getRoleString();
    }

    public void setRole(String role) {
        secureManager.setRole(role);
    }

    public boolean isSystemAdmin() {
        return secureManager.isSystemAdmin();
    }

    public boolean isAnyAdmin() {
        return secureManager.isAnyAdmin();
    }

    public boolean isAdmin() {
        return secureManager.isAnyAdmin();
    }

    public boolean isManager() {
        return secureManager.isManager();
    }

    // ==================== بيانات المستخدم ====================

    public String getDisplayName() {
        return secureManager.getDisplayName();
    }

    public void setDisplayName(String displayName) {
        secureManager.setDisplayName(displayName);
    }

    public int getUserId() {
        return secureManager.getUserId();
    }

    public void setUserId(int userId) {
        secureManager.setUserId(userId);
    }

    public String getPhone() {
        return secureManager.getPhone();
    }

    public void setPhone(String phone) {
        secureManager.setPhone(phone);
    }

    public String getEmail() {
        return secureManager.getEmail();
    }

    public void setEmail(String email) {
        secureManager.setEmail(email);
    }

    public void setUsername(String username) {
        secureManager.setUsername(username);
    }

    // ==================== عنوان الخادم ====================
    
    public String getServerUrl() {
        return secureManager.getServerUrl();
    }

    public void setServerUrl(String url) {
        secureManager.setServerUrl(url);
    }

    public boolean hasServerUrl() {
        return secureManager.hasServerUrl();
    }

    // ==================== API Key ====================
    
    public void saveApiKey(String apiKey) {
        secureManager.saveApiKey(apiKey);
    }

    public String getApiKey() {
        return secureManager.getApiKey();
    }

    public boolean hasApiKey() {
        return secureManager.hasApiKey();
    }

    public void clearApiKey() {
        secureManager.clearApiKey();
    }

    public void saveApiKeyInfo(String apiKey, String keyId, String keyPrefix, String createdAt) {
        secureManager.saveApiKeyInfo(apiKey, keyId, keyPrefix, createdAt);
    }

    public String getApiKeyId() {
        return secureManager.getApiKeyId();
    }

    public String getApiKeyPrefix() {
        return secureManager.getApiKeyPrefix();
    }

    // ==================== Gate ID ====================
    
    public void saveGateId(String gateId) {
        secureManager.saveGateId(gateId);
    }

    public String getGateId() {
        return secureManager.getGateId();
    }

    // ==================== تذكر اسم المستخدم ====================
    
    public void saveLastUsername(String username) {
        secureManager.saveLastUsername(username);
    }

    public String getLastUsername() {
        return secureManager.getLastUsername();
    }

    public void clearLastUsername() {
        secureManager.clearLastUsername();
    }

    // ==================== المصادقة البيومترية ====================
    
    public void setBiometricEnabled(boolean enabled) {
        secureManager.setBiometricEnabled(enabled);
    }

    public boolean isBiometricEnabled() {
        return secureManager.isBiometricEnabled();
    }

    public void setBiometricLoginEnabled(boolean enabled) {
        secureManager.setBiometricEnabled(enabled);
    }

    public boolean isBiometricLoginEnabled() {
        return secureManager.isBiometricEnabled();
    }

    public void setBiometricConfirmEnabled(boolean enabled) {
        secureManager.setBiometricConfirmEnabled(enabled);
    }

    public boolean isBiometricConfirmEnabled() {
        return secureManager.isBiometricConfirmEnabled();
    }

    public void saveBiometricUser(String username, String role, String displayName, int userId, String token) {
        secureManager.saveBiometricUser(username, role, displayName, userId, token);
    }

    public String getLastBiometricUser() {
        return secureManager.getLastBiometricUser();
    }

    public String getLastBiometricRole() {
        return secureManager.getLastBiometricRole();
    }

    public String getLastBiometricDisplayName() {
        return secureManager.getLastBiometricDisplayName();
    }

    public int getLastBiometricUserId() {
        return secureManager.getLastBiometricUserId();
    }

    public String getLastBiometricToken() {
        return secureManager.getLastBiometricToken();
    }

    public void saveBiometricBranch(int branchId, String branchName) {
        secureManager.saveBiometricBranch(branchId, branchName);
    }

    public int getLastBiometricBranchId() {
        return secureManager.getLastBiometricBranchId();
    }

    public String getLastBiometricBranchName() {
        return secureManager.getLastBiometricBranchName();
    }

    // ==================== معرف الفرع ====================
    
    public int getBranchId() {
        try {
            int branchId = secureManager.getBranchId();
            if (branchId > 0) {
                return branchId;
            }
            
            branchId = secureManager.getLastBiometricBranchId();
            if (branchId > 0) {
                return branchId;
            }
            
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "getBranchId error: " + e.getMessage());
            return -1;
        }
    }

    public void setBranchId(int branchId) {
        try {
            secureManager.setBranchId(branchId);
        } catch (Exception e) {
            Log.e(TAG, "setBranchId error: " + e.getMessage());
        }
    }

    public void saveAuthToken(String token) {
        secureManager.saveAuthToken(token);
    }

    public void saveAuthToken(String token, long expiresInSeconds) {
        secureManager.saveAuthToken(token, expiresInSeconds);
    }

    // ==================== تسجيل الخروج ====================
    
    public void logoutUser() {
        secureManager.logoutUser();
        Log.d(TAG, "User logged out successfully");
    }

    public void logout() {
        logoutUser();
    }

    // ==================== وظائف مساعدة ====================
    
    public void refreshProfileData() {
        // يمكن إضافة منطق إضافي هنا إذا لزم
    }

    public void clearAll() {
        secureManager.clearAll();
    }
    
    public void clearAuthToken() {
        secureManager.clearAuthToken();
    }

    public void clearSessionCompletely() {
        secureManager.clearSessionCompletely();
    }
    
    // ═══════════════════════════════════════════════════════
    // ✅ Auto-Lock Wrappers
    // ═══════════════════════════════════════════════════════

    public void setLastAppActiveTime(long timestamp) {
        secureManager.setLastAppActiveTime(timestamp);
    }

    public long getLastAppActiveTime() {
        return secureManager.getLastAppActiveTime();
    }

    public void touchActivity() {
        secureManager.touchActivity();
    }

    public void setSessionLocked(boolean locked) {
        secureManager.setSessionLocked(locked);
    }

    public boolean isSessionLocked() {
        return secureManager.isSessionLocked();
    }

    public boolean isInactivityTimeout(long timeoutMs) {
        return secureManager.isInactivityTimeout(timeoutMs);
    }

    public void createLoginSession(String username, String role, String token, 
                                    String serverUrl, long expiresInSeconds) {
        secureManager.createLoginSession(username, role, token, serverUrl, expiresInSeconds);
        Log.d(TAG, "Login session created for user: " + username 
                + " (expires in " + expiresInSeconds + "s)");
    }

    public void unlockSession() {
        secureManager.unlockSession();
    }

    public void resetAutoLock() {
        secureManager.resetAutoLock();
    }

    // ==================== إنشاء جلسة دخول ====================

    public void createLoginSession(String username, String role, String token, String serverUrl) {
        secureManager.createLoginSession(username, role, token, serverUrl);
        Log.d(TAG, "Login session created for user: " + username);
    }
}
