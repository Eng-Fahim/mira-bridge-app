package com.example.uhf.tools;

import android.content.Context;
import android.util.Log;
/**
 * SessionManager - كلاس التوافق الوسيط (نسخة مجردة وآمنة)
 * 
 * - لا يحتوي على أي أدوار محلية (Roles Enum) لتجنب كشف هيكلة النظام.
 * - يمرر البيانات والطلبات مباشرة إلى SecureSessionManager الآمن.
 */
@Deprecated
public class SessionManager {

    private static final String TAG = "SessionManager";

    private final SecureSessionManager secureManager;
    private final Context context;

    private String  cachedUsername     = null;
    private long    usernameCheckTime  = 0;
    private Boolean cachedLoggedIn     = null;
    private long    loggedInCheckTime  = 0;
    private static final long CACHE_VALIDITY = 3000; // 3 ثواني

    public SessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.secureManager = SecureSessionManager.getInstance(context);
        Log.d(TAG, "SessionManager created (using Singleton)");
    }

    public void invalidateCache() {
        this.cachedLoggedIn    = null;
        this.cachedUsername    = null;
        this.loggedInCheckTime = 0;
        this.usernameCheckTime = 0;
        secureManager.invalidateCache();
    }

    public boolean restoreBiometricSession() {
        boolean ok = secureManager.restoreBiometricSession();
        if (ok) {
            invalidateCache();
            Log.d(TAG, "✅ Biometric session restored via wrapper");
        }
        return ok;
    }

    public boolean isLoggedIn() {
        long now = System.currentTimeMillis();
        if (cachedLoggedIn != null && (now - loggedInCheckTime) < CACHE_VALIDITY) {
            return cachedLoggedIn;
        }
        cachedLoggedIn = secureManager.isLoggedIn();
        loggedInCheckTime = now;
        return cachedLoggedIn;
    }

    public String getUsername() {
        long now = System.currentTimeMillis();
        if (cachedUsername != null && (now - usernameCheckTime) < CACHE_VALIDITY) {
            return cachedUsername;
        }
        cachedUsername = secureManager.getUsername();
        usernameCheckTime = now;
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
        String lastUser  = getLastBiometricUser();
        String lastToken = getLastBiometricToken();
        return (lastUser != null && !lastUser.isEmpty())
                || (lastToken != null && !lastToken.isEmpty());
    }

    public boolean needsTokenRefresh() {
        return secureManager.needsTokenRefresh();
    }

    // ═══════════════════════════════════════════════════════
    // الأدوار (تُعامل كنصوص مجردة قادمة من السيرفر فقط)
    // ═══════════════════════════════════════════════════════

    public String getUserRole() {
        return secureManager.getRoleString();
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

    // ═══════════════════════════════════════════════════════
    // بيانات المستخدم
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // عنوان الخادم
    // ═══════════════════════════════════════════════════════

    public String getServerUrl() {
        return secureManager.getServerUrl();
    }

    public void setServerUrl(String url) {
        secureManager.setServerUrl(url);
    }

    public boolean hasServerUrl() {
        return secureManager.hasServerUrl();
    }

    // ═══════════════════════════════════════════════════════
    // API Key
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // Gate ID
    // ═══════════════════════════════════════════════════════

    public void saveGateId(String gateId) {
        secureManager.saveGateId(gateId);
    }

    public String getGateId() {
        return secureManager.getGateId();
    }

    // ═══════════════════════════════════════════════════════
    // تذكر اسم المستخدم
    // ═══════════════════════════════════════════════════════

    public void saveLastUsername(String username) {
        secureManager.saveLastUsername(username);
    }

    public String getLastUsername() {
        return secureManager.getLastUsername();
    }

    public void clearLastUsername() {
        secureManager.clearLastUsername();
    }

    // ═══════════════════════════════════════════════════════
    // المصادقة البيومترية
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // الفرع
    // ═══════════════════════════════════════════════════════

    public int getBranchId() {
        try {
            int branchId = secureManager.getBranchId();
            if (branchId > 0) return branchId;

            branchId = secureManager.getLastBiometricBranchId();
            if (branchId > 0) return branchId;

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

    // ═══════════════════════════════════════════════════════
    // التوكن
    // ═══════════════════════════════════════════════════════

    public void saveAuthToken(String token) {
        secureManager.saveAuthToken(token);
    }

    public void saveAuthToken(String token, long expiresInSeconds) {
        secureManager.saveAuthToken(token, expiresInSeconds);
    }

    // ═══════════════════════════════════════════════════════
    // تسجيل الخروج
    // ═══════════════════════════════════════════════════════

    public void logoutUser() {
        secureManager.logoutUser();
        invalidateCache();
        Log.d(TAG, "User logged out successfully");
    }

    public void logout() {
        logoutUser();
    }

    // ═══════════════════════════════════════════════════════
    // وظائف مساعدة
    // ═══════════════════════════════════════════════════════

    public void refreshProfileData() {
        invalidateCache();
    }

    public void clearAll() {
        secureManager.clearAll();
        invalidateCache();
    }

    public void clearAuthToken() {
        secureManager.clearAuthToken();
        invalidateCache();
    }

    public void clearSessionCompletely() {
        secureManager.clearSessionCompletely();
        invalidateCache();
    }

    // ═══════════════════════════════════════════════════════
    // Auto-Lock
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
        invalidateCache();
        Log.d(TAG, "Login session created for user: " + username);
    }

    public void unlockSession() {
        secureManager.unlockSession();
    }

    public void resetAutoLock() {
        secureManager.resetAutoLock();
    }

    public void createLoginSession(String username, String role, String token, String serverUrl) {
        secureManager.createLoginSession(username, role, token, serverUrl);
        invalidateCache();
        Log.d(TAG, "Login session created for user: " + username);
    }

    // ═══════════════════════════════════════════════════════
    // License (MIRA License Center)
    // ═══════════════════════════════════════════════════════

    public void saveActivationToken(String token) {
        secureManager.saveActivationToken(token);
    }

    public String getActivationToken() {
        return secureManager.getActivationToken();
    }

    public boolean hasActivationToken() {
        return secureManager.hasActivationToken();
    }

    public void saveDeviceUuid(String uuid) {
        secureManager.saveDeviceUuid(uuid);
    }

    public String getDeviceUuid() {
        return secureManager.getDeviceUuid();
    }

    public void saveLicenseInfo(String type, String validFrom, String validUntil,
                                int maxDevices, int maxUsers) {
        secureManager.saveLicenseInfo(type, validFrom, validUntil, maxDevices, maxUsers);
    }

    public String getLicenseType() { return secureManager.getLicenseType(); }
    public String getLicenseValidFrom() { return secureManager.getLicenseValidFrom(); }
    public String getLicenseValidUntil() { return secureManager.getLicenseValidUntil(); }
    public int getLicenseMaxDevices() { return secureManager.getLicenseMaxDevices(); }
    public int getLicenseMaxUsers() { return secureManager.getLicenseMaxUsers(); }

    public void saveLastHeartbeat(long timestampMs) {
        secureManager.saveLastHeartbeat(timestampMs);
    }

    public long getLastHeartbeat() {
        return secureManager.getLastHeartbeat();
    }

    public void saveOfflineSettings(int graceDays, int warnDay, int restrictDay) {
        secureManager.saveOfflineSettings(graceDays, warnDay, restrictDay);
    }

    public int getOfflineGraceDays()   { return secureManager.getOfflineGraceDays(); }
    public int getOfflineWarningDay()  { return secureManager.getOfflineWarningDay(); }
    public int getOfflineRestrictDay() { return secureManager.getOfflineRestrictDay(); }
    public int getDaysSinceLastHeartbeat() { return secureManager.getDaysSinceLastHeartbeat(); }
    public String getOfflineStatus() { return secureManager.getOfflineStatus(); }
    public long getActivationTimestamp() { return secureManager.getActivationTimestamp(); }

    public void clearLicense() {
        secureManager.clearLicense();
        invalidateCache();
    }
        public boolean isAnyAdmin() {
        String role = getRoleString().toUpperCase();
        return role.contains("ADMIN") || role.contains("SUPER") || role.contains("OWNER") || role.contains("SYSTEM_ADMIN");
    }

}
