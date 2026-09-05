package com.example.uhf.tools;

import android.content.Context;
import android.util.Log;

import com.example.uhf.tools.FirebaseDiagnostics;

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
    private final Context context;
    
    // ✅ تخزين مؤقت للقيم المستخدمة كثيراً
    private String cachedUsername = null;
    private Boolean cachedLoggedIn = null;
    private long lastCheckTime = 0;
    private static final long CACHE_VALIDITY = 3000; // 3 ثواني

    public SessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.secureManager = new SecureSessionManager(context);
    }

    public boolean isLoggedIn() {
        // ✅ تخزين مؤقت
        long now = System.currentTimeMillis();
        if (cachedLoggedIn != null && (now - lastCheckTime) < CACHE_VALIDITY) {
            return cachedLoggedIn;
        }
        
        cachedLoggedIn = secureManager.isLoggedIn();
        lastCheckTime = now;
        return cachedLoggedIn;
    }

    public String getUsername() {
        // ✅ تخزين مؤقت
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
        String token = secureManager.getAuthToken();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Get Auth Token", 
            "result=" + (token != null && !token.isEmpty() ? "PRESENT" : "EMPTY"));
        
        return token;
    }

    // ✅ فحص صلاحية التوكن مع التتبع المطور
    public boolean hasValidToken() {
        boolean valid = secureManager.hasValidToken();
        
        // ✅ تتبع تفصيلي
        FirebaseDiagnostics.log("SESSION", "Check Valid Token", "result=" + valid);
        
        return valid;
    }

    // ✅ فحص وجود جلسة محلية قابلة للاستخدام في الأوفلاين
    public boolean hasOfflineSession() {
        String lastUser = getLastBiometricUser();
        String lastToken = getLastBiometricToken();
        boolean hasOffline = (lastUser != null && !lastUser.isEmpty()) || (lastToken != null && !lastToken.isEmpty());
        
        FirebaseDiagnostics.log("SESSION", "Check Offline Session", "hasOffline=" + hasOffline);
        return hasOffline;
    }

    // ✅ فحص الحاجة للتجديد
    public boolean needsTokenRefresh() {
        return secureManager.needsTokenRefresh();
    }

    // ==================== الأدوار ====================
    
    public SecureSessionManager.Role getUserRole() {
        SecureSessionManager.Role role = secureManager.getUserRole();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Get User Role", "role=" + (role != null ? role.name() : "null"));
        
        return role;
    }

    public String getRoleString() {
        return secureManager.getRoleString();
    }

    public String getRole() {
        return secureManager.getRoleString();
    }

    public void setRole(String role) {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Set Role", "role=" + role);
        
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
        String displayName = secureManager.getDisplayName();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Get Display Name", 
            "result=" + (displayName != null && !displayName.isEmpty() ? displayName : "EMPTY"));
        
        return displayName;
    }

    public void setDisplayName(String displayName) {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Set Display Name", "name=" + displayName);
        
        secureManager.setDisplayName(displayName);
    }

    public int getUserId() {
        int userId = secureManager.getUserId();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Get User ID", "result=" + userId);
        
        return userId;
    }

    public void setUserId(int userId) {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Set User ID", "userId=" + userId);
        
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
        String serverUrl = secureManager.getServerUrl();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Get Server URL", 
            "result=" + (serverUrl != null && !serverUrl.isEmpty() ? serverUrl : "EMPTY"));
        
        return serverUrl;
    }

    public void setServerUrl(String url) {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Set Server URL", "url=" + url);
        
        secureManager.setServerUrl(url);
    }

    public boolean hasServerUrl() {
        return secureManager.hasServerUrl();
    }

    // ==================== API Key ====================
    
    public void saveApiKey(String apiKey) {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Save API Key", 
            "key=" + (apiKey != null && !apiKey.isEmpty() ? "PRESENT" : "EMPTY"));
        
        secureManager.saveApiKey(apiKey);
    }

    public String getApiKey() {
        String apiKey = secureManager.getApiKey();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Get API Key", 
            "result=" + (apiKey != null && !apiKey.isEmpty() ? "PRESENT" : "EMPTY"));
        
        return apiKey;
    }

    public boolean hasApiKey() {
        boolean hasKey = secureManager.hasApiKey();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Check API Key", "result=" + hasKey);
        
        return hasKey;
    }

    public void clearApiKey() {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Clear API Key", "Clearing...");
        
        secureManager.clearApiKey();
        
        FirebaseDiagnostics.log("SESSION", "API Key Cleared", "Done");
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
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Save Gate ID", "gateId=" + gateId);
        
        secureManager.saveGateId(gateId);
    }

    public String getGateId() {
        return secureManager.getGateId();
    }

    // ==================== تذكر اسم المستخدم ====================
    
    public void saveLastUsername(String username) {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Save Last Username", "username=" + username);
        
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
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Save Biometric User", "user=" + username);
        
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
        // ✅ تتبع - مهم جداً
        String username = secureManager.getUsername();
        FirebaseDiagnostics.log("SESSION", "Logout Started", "user=" + (username != null ? username : "null"));
        FirebaseDiagnostics.logSessionState("BEFORE_LOGOUT", username, true);
        
        secureManager.logoutUser();
        
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Logout Completed", "All session data cleared");
        FirebaseDiagnostics.logSessionState("AFTER_LOGOUT", null, false);
    }

    public void logout() {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Logout Called", "Calling logoutUser()");
        
        logoutUser();
    }

    // ==================== وظائف مساعدة جديدة لحماية الأوفلاين ====================
    
    public void refreshProfileData() {
        // يمكن إضافة منطق إضافي هنا إذا لزم
    }

    public void clearAll() {
        // ✅ تتبع
        FirebaseDiagnostics.log("SESSION", "Clear All", "Clearing all data...");
        
        secureManager.clearAll();
        
        FirebaseDiagnostics.log("SESSION", "Clear All Completed", "Done");
    }
    
    /**
     * مسح التوكن نهائياً
     */
    public void clearAuthToken() {
        FirebaseDiagnostics.log("SESSION", "Clear Auth Token", "Clearing permanently...");
        secureManager.clearAuthToken();
        FirebaseDiagnostics.log("SESSION", "Auth Token Cleared", "Done");
    }
    
    /**
     * مسح الجلسة بالكامل
     */
    public void clearSessionCompletely() {
        FirebaseDiagnostics.log("SESSION", "Clear Session", "Clearing completely...");
        secureManager.clearSessionCompletely();
        FirebaseDiagnostics.log("SESSION", "Session Cleared", "Done");
    }

    

}  // ✅ قوس واحد فقط
