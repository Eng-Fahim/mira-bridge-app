package com.example.uhf.tools;

import android.content.Context;
import android.util.Log;

/**
 * SessionManager - كلاس التوافق الوسيط
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

    public SessionManager(Context context) {
        this.secureManager = new SecureSessionManager(context);
    }

    // ==================== إدارة الجلسة ====================
    
    public void createLoginSession(String username, String role, String token, String serverUrl) {
        secureManager.createLoginSession(username, role, token, serverUrl);
        Log.d(TAG, "Login session created for user: " + username);
    }

    public boolean isLoggedIn() {
        return secureManager.isLoggedIn();
    }

    public String getUsername() {
        return secureManager.getUsername();
    }

    public String getUserName() {
        return secureManager.getUsername();
    }

    public String getAuthToken() {
        return secureManager.getAuthToken();
    }

    // ✅ إضافة فحص صلاحية التوكن
    public boolean hasValidToken() {
        return secureManager.hasValidToken();
    }

    // ✅ إضافة فحص الحاجة للتجديد
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
        return "";
    }

    public void setPhone(String phone) {
        // يمكن إضافة التخزين إذا لزم
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

    // ==================== تسجيل الخروج ====================
    
    public void logoutUser() {
        secureManager.logoutUser();
    }

    public void logout() {
        secureManager.logoutUser();
    }

    // ==================== وظائف مساعدة ====================
    
    public void refreshProfileData() {
        // يمكن إضافة منطق إضافي هنا إذا لزم
    }

    public void clearAll() {
        secureManager.clearAll();
    }
}
