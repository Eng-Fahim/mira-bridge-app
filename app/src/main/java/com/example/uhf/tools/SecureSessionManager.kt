package com.example.uhf.tools

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.Nullable
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SecureSessionManager - إدارة الجلسات الآمنة للتطبيق
 *
 * 💎 Kotlin Edition (v3.0) — هندسة نظيفة، أمان عالٍ، وتوافقية تامة.
 */
class SecureSessionManager private constructor(context: Context) {

    private val userPrefs: SharedPreferences
    private val securePrefs: SharedPreferences
    private var keyStore: KeyStore? = null

    // تخزين مؤقت (Cached Fields) لتحسين الأداء
    private var cachedAuthToken: String? = null
    private var tokenCacheTime: Long = 0
    private var cachedApiKey: String? = null
    private var apiKeyCacheTime: Long = 0
    private var cachedLoggedIn: Boolean? = null
    private var loggedInCacheTime: Long = 0
    private var cachedUsername: String? = null
    private var usernameCacheTime: Long = 0
    private var cachedServerUrl: String? = null
    private var serverUrlCacheTime: Long = 0
    private var cachedLastActiveTime: Long? = null
    private var cachedSessionLocked: Boolean? = null

    enum class Role {
        SYSTEM_ADMIN,
        ORG_ADMIN,
        FINANCE_MANAGER,
        INVENTORY_MGR,
        BRANCH_MANAGER,
        ACCOUNTANT,
        AUDITOR,
        POS_OPERATOR,
        GATE_SECURITY,
        SECURITY,
        ADMIN
    }

    init {
        val appContext = context.applicationContext
        userPrefs = appContext.getSharedPreferences(USER_PREFS_NAME, Context.MODE_PRIVATE)

        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing KeyStore", e)
        }

        securePrefs = createEncryptedSharedPreferences(appContext)
    }

    companion object {
        private const val TAG = "SecureSessionManager"

        @Volatile
        private var instance: SecureSessionManager? = null
        private val INSTANCE_LOCK = Any()

        // أسماء الملفات
        private const val SECURE_PREFS_NAME = "mira_secure_session"
        private const val USER_PREFS_NAME = "mira_user_preferences"

        // مفاتيح التخزين الآمن
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_KEY_ID = "api_key_id"
        private const val KEY_API_KEY_PREFIX = "api_key_prefix"
        private const val KEY_API_KEY_CREATED_AT = "api_key_created_at"
        private const val KEY_LAST_BIOMETRIC_TOKEN = "last_biometric_token"

        // مفاتيح التخزين العادي
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USERNAME = "username"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_ROLE = "user_role"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LAST_USERNAME = "last_username"
        private const val KEY_GATE_ID = "gate_id"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_BIOMETRIC_CONFIRM_ENABLED = "biometric_confirm_enabled"
        private const val KEY_LAST_BIOMETRIC_USER = "last_biometric_user"
        private const val KEY_LAST_BIOMETRIC_ROLE = "last_biometric_role"
        private const val KEY_LAST_BIOMETRIC_DISPLAY_NAME = "last_biometric_display_name"
        private const val KEY_LAST_BIOMETRIC_USER_ID = "last_biometric_user_id"
        private const val KEY_LAST_BIOMETRIC_BRANCH_ID = "last_biometric_branch_id"
        private const val KEY_LAST_BIOMETRIC_BRANCH_NAME = "last_biometric_branch_name"
        private const val KEY_BRANCH_ID = "branch_id"
        private const val KEY_BRANCH_NAME = "branch_name"
        private const val KEY_LAST_ACTIVE_TIME = "last_app_active_time"
        private const val KEY_SESSION_LOCKED = "session_locked"

        // مفاتيح الترخيص (MIRA License Center)
        private const val KEY_ACTIVATION_TOKEN = "activation_token"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_LICENSE_TYPE = "license_type"
        private const val KEY_LICENSE_VALID_FROM = "license_valid_from"
        private const val KEY_LICENSE_VALID_UNTIL = "license_valid_until"
        private const val KEY_LICENSE_MAX_DEVICES = "license_max_devices"
        private const val KEY_LICENSE_MAX_USERS = "license_max_users"
        private const val KEY_LAST_HEARTBEAT = "license_last_heartbeat"
        private const val KEY_OFFLINE_GRACE_DAYS = "license_offline_grace_days"
        private const val KEY_OFFLINE_WARNING_DAY = "license_offline_warning_day"
        private const val KEY_OFFLINE_RESTRICT_DAY = "license_offline_restrict_day"
        private const val KEY_ACTIVATION_AT = "license_activated_at"

        // القيم الافتراضية
        private const val DEFAULT_SERVER_URL = "https://jewelry.ibreg.org/api"
        private const val TOKEN_EXPIRY_BUFFER = 60_000L
        private const val REFRESH_THRESHOLD = 300_000L
        private const val CACHE_VALIDITY_MS = 5_000L
        private const val DEFAULT_TOKEN_DAYS = 7L

        // التشفير البيومتري
        private const val BIOMETRIC_KEY_ALIAS = "mira_biometric_key"
        private const val BIOMETRIC_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        @JvmStatic
        fun getInstance(context: Context): SecureSessionManager {
            return instance ?: synchronized(INSTANCE_LOCK) {
                instance ?: SecureSessionManager(context.applicationContext).also { instance = it }
            }
        }

        @JvmStatic
        fun resetInstance() {
            synchronized(INSTANCE_LOCK) {
                instance = null
                Log.d(TAG, "🔄 SecureSessionManager Singleton reset")
            }
        }
    }

    private fun createEncryptedSharedPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences corrupt, attempting recovery...", e)
            try {
                context.deleteSharedPreferences(SECURE_PREFS_NAME)
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context, SECURE_PREFS_NAME, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Critical: Failed to recreate EncryptedSharedPreferences securely!", ex)
                throw RuntimeException("Security Error: Cannot initialize encrypted storage.", ex)
            }
        }
    }

    fun invalidateCache() {
        cachedAuthToken = null
        tokenCacheTime = 0
        cachedApiKey = null
        apiKeyCacheTime = 0
        cachedLoggedIn = null
        loggedInCacheTime = 0
        cachedUsername = null
        usernameCacheTime = 0
        cachedServerUrl = null
        serverUrlCacheTime = 0
        cachedLastActiveTime = null
        cachedSessionLocked = null
        Log.d(TAG, "🗑️ Session cache invalidated")
    }

    // ═══════════════════════════════════════════════════════════════════
    // إدارة الجلسة
    // ═══════════════════════════════════════════════════════════════════

    @JvmOverloads
    fun createLoginSession(
        username: String?,
        role: String?,
        token: String?,
        serverUrl: String?,
        expiresInSeconds: Long = DEFAULT_TOKEN_DAYS * 24 * 60 * 60
    ) {
        try {
            val expiryTime = if (expiresInSeconds > 0) {
                System.currentTimeMillis() + (expiresInSeconds * 1000L)
            } else {
                System.currentTimeMillis() + (DEFAULT_TOKEN_DAYS * 24 * 60 * 60 * 1000L)
            }

            val tokenOk = securePrefs.edit()
                .putString(KEY_AUTH_TOKEN, token ?: "")
                .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                .commit()

            if (!tokenOk) {
                Log.e(TAG, "🚨 createLoginSession: token commit FAILED — token NOT persisted!")
            }

            val finalServerUrl = if (!serverUrl.isNullOrEmpty()) {
                normalizeServerUrl(serverUrl)
            } else {
                DEFAULT_SERVER_URL
            }

            userPrefs.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_USERNAME, username ?: "")
                .putString(KEY_USER_ROLE, role ?: Role.INVENTORY_MGR.name)
                .putString(KEY_LAST_USERNAME, username ?: "")
                .putString(KEY_SERVER_URL, finalServerUrl)
                .apply()

            invalidateCache()
            Log.d(TAG, "✅ Login session created for: $username")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating login session", e)
        }
    }

    fun updateToken(newToken: String?, expiresInSeconds: Long) {
        val expiryTime = System.currentTimeMillis() + (expiresInSeconds * 1000L)
        securePrefs.edit()
            .putString(KEY_AUTH_TOKEN, newToken ?: "")
            .putLong(KEY_TOKEN_EXPIRY, expiryTime)
            .apply()
        cachedAuthToken = newToken
        tokenCacheTime = System.currentTimeMillis()
    }

    @JvmOverloads
    fun saveAuthToken(token: String?, expiresInSeconds: Long = DEFAULT_TOKEN_DAYS * 24 * 60 * 60) {
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ Attempted to save empty auth token")
            return
        }
        try {
            val expiryTime = if (expiresInSeconds > 0) {
                System.currentTimeMillis() + (expiresInSeconds * 1000L)
            } else {
                System.currentTimeMillis() + (DEFAULT_TOKEN_DAYS * 24 * 60 * 60 * 1000L)
            }

            val ok = securePrefs.edit()
                .putString(KEY_AUTH_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                .commit()

            if (!ok) {
                Log.e(TAG, "🚨 saveAuthToken: commit FAILED — token NOT persisted!")
                return
            }

            cachedAuthToken = token
            tokenCacheTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "saveAuthToken error: ${e.message}", e)
        }
    }

    fun getAuthToken(): String {
        val now = System.currentTimeMillis()
        if (!cachedAuthToken.isNullOrEmpty() && (now - tokenCacheTime) < CACHE_VALIDITY_MS) {
            return cachedAuthToken!!
        }

        val token = try {
            securePrefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "getAuthToken read failed: ${e.message}")
            ""
        }

        if (token.isNotEmpty()) {
            cachedAuthToken = token
            tokenCacheTime = now
        } else {
            cachedAuthToken = null
            tokenCacheTime = 0
        }

        return token
    }

    fun hasValidToken(): Boolean {
        return try {
            val token = getAuthToken()
            if (token.isEmpty()) return false
            val expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
            if (expiryTime == 0L) return true
            System.currentTimeMillis() < (expiryTime - TOKEN_EXPIRY_BUFFER)
        } catch (e: Exception) {
            false
        }
    }

    fun needsTokenRefresh(): Boolean {
        return try {
            val expiryTime = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
            if (expiryTime == 0L) return false
            System.currentTimeMillis() > (expiryTime - REFRESH_THRESHOLD)
        } catch (e: Exception) {
            true
        }
    }

    fun hasOfflineSession(): Boolean {
        val token = getAuthToken()
        val lastBioToken = getLastBiometricToken()
        return token.isNotEmpty() || lastBioToken.isNotEmpty()
    }

    fun logoutUser() {
        setSessionLocked(false)
        setLastAppActiveTime(0)

        val keepBiometric = isBiometricEnabled()
        val apiKey = getApiKey()
        val serverUrl = getServerUrl()
        val lastUsername = getLastUsername()
        val gateId = getGateId()

        val activationToken = getActivationToken()
        val deviceUuid = getDeviceUuid()
        val licenseType = getLicenseType()
        val licenseFrom = getLicenseValidFrom()
        val licenseUntil = getLicenseValidUntil()
        val licenseMaxDev = getLicenseMaxDevices()
        val licenseMaxUsr = getLicenseMaxUsers()
        val activationAt = getActivationTimestamp()

        val biometricUser = getLastBiometricUser()
        val biometricRole = getLastBiometricRole()
        val biometricDisplayName = getLastBiometricDisplayName()
        val biometricUserId = getLastBiometricUserId()
        val biometricToken = getLastBiometricToken()
        val biometricBranchId = getLastBiometricBranchId()
        val biometricBranchName = getLastBiometricBranchName()

        securePrefs.edit().clear().commit()
        userPrefs.edit().clear().commit()

        val userEditor = userPrefs.edit()
        val secureEditor = securePrefs.edit()

        if (keepBiometric) {
            userEditor.putBoolean(KEY_BIOMETRIC_ENABLED, true)
                .putString(KEY_LAST_BIOMETRIC_USER, biometricUser)
                .putString(KEY_LAST_BIOMETRIC_ROLE, biometricRole)
                .putString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, biometricDisplayName)
                .putInt(KEY_LAST_BIOMETRIC_USER_ID, biometricUserId)
                .putInt(KEY_LAST_BIOMETRIC_BRANCH_ID, biometricBranchId)
                .putString(KEY_LAST_BIOMETRIC_BRANCH_NAME, biometricBranchName)
            secureEditor.putString(KEY_LAST_BIOMETRIC_TOKEN, biometricToken)
        }

        if (activationToken.isNotEmpty()) {
            secureEditor.putString(KEY_ACTIVATION_TOKEN, activationToken)
                .putLong(KEY_ACTIVATION_AT, activationAt)
        }
        if (deviceUuid.isNotEmpty()) {
            secureEditor.putString(KEY_DEVICE_UUID, deviceUuid)
        }
        if (licenseType.isNotEmpty()) {
            userEditor.putString(KEY_LICENSE_TYPE, licenseType)
                .putString(KEY_LICENSE_VALID_FROM, licenseFrom)
                .putString(KEY_LICENSE_VALID_UNTIL, licenseUntil)
                .putInt(KEY_LICENSE_MAX_DEVICES, licenseMaxDev)
                .putInt(KEY_LICENSE_MAX_USERS, licenseMaxUsr)
        }

        if (apiKey.isNotEmpty()) secureEditor.putString(KEY_API_KEY, apiKey)
        if (serverUrl.isNotEmpty()) userEditor.putString(KEY_SERVER_URL, serverUrl)
        if (lastUsername.isNotEmpty()) userEditor.putString(KEY_LAST_USERNAME, lastUsername)
        if (gateId.isNotEmpty()) userEditor.putString(KEY_GATE_ID, gateId)

        userEditor.commit()
        secureEditor.commit()

        invalidateCache()
        Log.d(TAG, "User logged out securely")
    }

    fun logout() = logoutUser()

    fun restoreBiometricSession(): Boolean {
        return try {
            val username = getLastBiometricUser()
            val role = getLastBiometricRole()
            val displayName = getLastBiometricDisplayName()
            val userId = getLastBiometricUserId()
            val token = getLastBiometricToken()
            val branchId = getLastBiometricBranchId()
            val branchName = getLastBiometricBranchName()

            if (username.isEmpty() || token.isEmpty()) {
                Log.e(TAG, "❌ Cannot restore: biometric credentials missing")
                return false
            }

            val expiryTime = System.currentTimeMillis() + (DEFAULT_TOKEN_DAYS * 24 * 60 * 60 * 1000L)

            val tokenOk = securePrefs.edit()
                .putString(KEY_AUTH_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                .commit()

            if (!tokenOk) {
                Log.e(TAG, "🚨 restoreBiometricSession: token commit FAILED")
            }

            userPrefs.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_USERNAME, username)
                .putString(KEY_USER_ROLE, role)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putInt(KEY_USER_ID, userId)
                .putInt(KEY_BRANCH_ID, branchId)
                .putString(KEY_BRANCH_NAME, branchName)
                .apply()

            invalidateCache()
            Log.d(TAG, "✅ Biometric session restored for: $username")
            true
        } catch (e: Exception) {
            Log.e(TAG, "restoreBiometricSession error: ${e.message}", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // إدارة المستخدم والصلاحيات
    // ═══════════════════════════════════════════════════════════════════

    fun isLoggedIn(): Boolean {
        val now = System.currentTimeMillis()
        if (cachedLoggedIn != null && (now - loggedInCacheTime) < CACHE_VALIDITY_MS) {
            return cachedLoggedIn!!
        }
        cachedLoggedIn = userPrefs.getBoolean(KEY_IS_LOGGED_IN, false)
        loggedInCacheTime = now
        return cachedLoggedIn!!
    }

    fun getUsername(): String {
        val now = System.currentTimeMillis()
        if (!cachedUsername.isNullOrEmpty() && (now - usernameCacheTime) < CACHE_VALIDITY_MS) {
            return cachedUsername!!
        }
        cachedUsername = userPrefs.getString(KEY_USERNAME, "") ?: ""
        usernameCacheTime = now
        return cachedUsername!!
    }

    fun getUserName(): String = getUsername()

    fun setUsername(username: String?) {
        if (username != null) {
            userPrefs.edit().putString(KEY_USERNAME, username).apply()
            cachedUsername = username
            usernameCacheTime = System.currentTimeMillis()
        }
    }

    fun getDisplayName(): String {
        val name = userPrefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        return if (name.isEmpty()) getUsername() else name
    }

    fun setDisplayName(displayName: String?) {
        if (displayName != null) userPrefs.edit().putString(KEY_DISPLAY_NAME, displayName).apply()
    }

    fun getUserId(): Int = userPrefs.getInt(KEY_USER_ID, 0)
    fun setUserId(userId: Int) { userPrefs.edit().putInt(KEY_USER_ID, userId).apply() }

    fun getEmail(): String = userPrefs.getString(KEY_USER_EMAIL, "") ?: ""
    fun setEmail(email: String?) { if (email != null) userPrefs.edit().putString(KEY_USER_EMAIL, email).apply() }

    fun getPhone(): String = userPrefs.getString(KEY_USER_PHONE, "") ?: ""
    fun setPhone(phone: String?) { if (phone != null) userPrefs.edit().putString(KEY_USER_PHONE, phone).apply() }

    fun getRoleString(): String = userPrefs.getString(KEY_USER_ROLE, "INVENTORY_MGR") ?: "INVENTORY_MGR"
    fun getRole(): String = getRoleString()

    fun setRole(role: String?) {
        if (role != null) userPrefs.edit().putString(KEY_USER_ROLE, role).apply()
    }

    fun getUserRole(): Role {
        return try {
            Role.valueOf(getRoleString().uppercase(Locale.ROOT))
        } catch (e: IllegalArgumentException) {
            Role.INVENTORY_MGR
        }
    }

    fun isSystemAdmin(): Boolean = Role.SYSTEM_ADMIN.name.equals(getRoleString(), ignoreCase = true)
    fun isOrgAdmin(): Boolean = Role.ORG_ADMIN.name.equals(getRoleString(), ignoreCase = true)

    fun isAnyAdmin(): Boolean {
        val role = getRoleString()
        return Role.SYSTEM_ADMIN.name.equals(role, true) ||
                Role.ORG_ADMIN.name.equals(role, true) ||
                Role.FINANCE_MANAGER.name.equals(role, true) ||
                Role.ADMIN.name.equals(role, true)
    }

    fun isAdmin(): Boolean = isAnyAdmin()

    fun canPerformAccountingSetup(): Boolean {
        val role = getRoleString()
        return Role.SYSTEM_ADMIN.name.equals(role, true) ||
                Role.ORG_ADMIN.name.equals(role, true) ||
                Role.FINANCE_MANAGER.name.equals(role, true) ||
                Role.ACCOUNTANT.name.equals(role, true)
    }

    fun isManager(): Boolean {
        val role = getRoleString()
        return isAnyAdmin() ||
                Role.BRANCH_MANAGER.name.equals(role, true) ||
                Role.INVENTORY_MGR.name.equals(role, true)
    }

    fun isAccountant(): Boolean = Role.ACCOUNTANT.name.equals(getRoleString(), true)
    fun isFinanceManager(): Boolean = Role.FINANCE_MANAGER.name.equals(getRoleString(), true)
    fun isInventoryManager(): Boolean = Role.INVENTORY_MGR.name.equals(getRoleString(), true)
    fun isPosOperator(): Boolean = Role.POS_OPERATOR.name.equals(getRoleString(), true)
    fun isBranchManager(): Boolean = Role.BRANCH_MANAGER.name.equals(getRoleString(), true)
    fun isAuditor(): Boolean = Role.AUDITOR.name.equals(getRoleString(), true)
    fun isSecurity(): Boolean = Role.SECURITY.name.equals(getRoleString(), true)
    fun isGateSecurity(): Boolean = Role.GATE_SECURITY.name.equals(getRoleString(), true)

    fun isSecurityRelated(): Boolean {
        val role = getRoleString()
        return Role.SECURITY.name.equals(role, true) || Role.GATE_SECURITY.name.equals(role, true)
    }

    // ═══════════════════════════════════════════════════════════════════
    // إعدادات الخادم
    // ═══════════════════════════════════════════════════════════════════

    fun getServerUrl(): String {
        val now = System.currentTimeMillis()
        if (!cachedServerUrl.isNullOrEmpty() && (now - serverUrlCacheTime) < CACHE_VALIDITY_MS) {
            return cachedServerUrl!!
        }
        var url = userPrefs.getString(KEY_SERVER_URL, "") ?: ""
        if (url.isEmpty()) {
            url = DEFAULT_SERVER_URL
            setServerUrl(url)
        }
        cachedServerUrl = url
        serverUrlCacheTime = now
        return url
    }

    fun setServerUrl(url: String?) {
        if (url.isNullOrEmpty()) return
        val normalizedUrl = normalizeServerUrl(url)
        userPrefs.edit().putString(KEY_SERVER_URL, normalizedUrl).apply()
        cachedServerUrl = normalizedUrl
        serverUrlCacheTime = System.currentTimeMillis()
    }

    fun hasServerUrl(): Boolean = getServerUrl().isNotEmpty()

    fun resetServerUrl() {
        userPrefs.edit().remove(KEY_SERVER_URL).apply()
        setServerUrl(DEFAULT_SERVER_URL)
    }

    private fun normalizeServerUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.endsWith("/")) normalized += "/"
        return normalized
    }

    // ═══════════════════════════════════════════════════════════════════
    // API Key & Gate & Username
    // ═══════════════════════════════════════════════════════════════════

    fun saveApiKey(apiKey: String?) {
        if (!apiKey.isNullOrEmpty()) {
            securePrefs.edit().putString(KEY_API_KEY, apiKey).apply()
            cachedApiKey = apiKey
            apiKeyCacheTime = System.currentTimeMillis()
        }
    }

    fun getApiKey(): String {
        val now = System.currentTimeMillis()
        if (!cachedApiKey.isNullOrEmpty() && (now - apiKeyCacheTime) < CACHE_VALIDITY_MS) {
            return cachedApiKey!!
        }
        cachedApiKey = securePrefs.getString(KEY_API_KEY, "") ?: ""
        apiKeyCacheTime = now
        return cachedApiKey!!
    }

    fun hasApiKey(): Boolean = getApiKey().isNotEmpty()

    fun clearApiKey() {
        securePrefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_KEY_ID)
            .remove(KEY_API_KEY_PREFIX)
            .remove(KEY_API_KEY_CREATED_AT)
            .apply()
        cachedApiKey = null
        apiKeyCacheTime = 0
    }

    fun saveApiKeyInfo(apiKey: String?, keyId: String?, keyPrefix: String?, createdAt: String?) {
        securePrefs.edit()
            .putString(KEY_API_KEY, apiKey ?: "")
            .putString(KEY_API_KEY_ID, keyId ?: "")
            .putString(KEY_API_KEY_PREFIX, keyPrefix ?: "")
            .putString(KEY_API_KEY_CREATED_AT, createdAt ?: "")
            .apply()
        cachedApiKey = apiKey
        apiKeyCacheTime = System.currentTimeMillis()
    }

    fun getApiKeyId(): String = securePrefs.getString(KEY_API_KEY_ID, "") ?: ""
    fun getApiKeyPrefix(): String = securePrefs.getString(KEY_API_KEY_PREFIX, "") ?: ""

    fun saveGateId(gateId: String?) { if (gateId != null) userPrefs.edit().putString(KEY_GATE_ID, gateId).apply() }
    fun getGateId(): String = userPrefs.getString(KEY_GATE_ID, "") ?: ""
    fun clearGateId() { userPrefs.edit().remove(KEY_GATE_ID).apply() }

    fun saveLastUsername(username: String?) { if (username != null) userPrefs.edit().putString(KEY_LAST_USERNAME, username).apply() }
    fun getLastUsername(): String = userPrefs.getString(KEY_LAST_USERNAME, "") ?: ""
    fun clearLastUsername() { userPrefs.edit().remove(KEY_LAST_USERNAME).apply() }

    // ═══════════════════════════════════════════════════════════════════
    // المصادقة البيومترية والتشفير (AndroidKeyStore)
    // ═══════════════════════════════════════════════════════════════════

    fun setBiometricEnabled(enabled: Boolean) { userPrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply() }
    fun isBiometricEnabled(): Boolean = userPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    fun setBiometricLoginEnabled(enabled: Boolean) = setBiometricEnabled(enabled)
    fun isBiometricLoginEnabled(): Boolean = isBiometricEnabled()

    fun setBiometricConfirmEnabled(enabled: Boolean) { userPrefs.edit().putBoolean(KEY_BIOMETRIC_CONFIRM_ENABLED, enabled).apply() }
    fun isBiometricConfirmEnabled(): Boolean = userPrefs.getBoolean(KEY_BIOMETRIC_CONFIRM_ENABLED, false)

    fun saveBiometricUser(username: String?, role: String?, displayName: String?, userId: Int, token: String?) {
        userPrefs.edit()
            .putString(KEY_LAST_BIOMETRIC_USER, username ?: "")
            .putString(KEY_LAST_BIOMETRIC_ROLE, role ?: "")
            .putString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, displayName ?: "")
            .putInt(KEY_LAST_BIOMETRIC_USER_ID, userId)
            .apply()
        securePrefs.edit()
            .putString(KEY_LAST_BIOMETRIC_TOKEN, token ?: "")
            .apply()
    }

    fun getLastBiometricUser(): String = userPrefs.getString(KEY_LAST_BIOMETRIC_USER, "") ?: ""
    fun getLastBiometricRole(): String = userPrefs.getString(KEY_LAST_BIOMETRIC_ROLE, Role.INVENTORY_MGR.name) ?: Role.INVENTORY_MGR.name
    fun getLastBiometricDisplayName(): String = userPrefs.getString(KEY_LAST_BIOMETRIC_DISPLAY_NAME, "") ?: ""
    fun getLastBiometricUserId(): Int = userPrefs.getInt(KEY_LAST_BIOMETRIC_USER_ID, 0)
    fun getLastBiometricToken(): String = securePrefs.getString(KEY_LAST_BIOMETRIC_TOKEN, "") ?: ""

    fun saveBiometricBranch(branchId: Int, branchName: String?) {
        userPrefs.edit()
            .putInt(KEY_LAST_BIOMETRIC_BRANCH_ID, branchId)
            .putString(KEY_LAST_BIOMETRIC_BRANCH_NAME, branchName ?: "")
            .apply()
    }

    fun getLastBiometricBranchId(): Int = userPrefs.getInt(KEY_LAST_BIOMETRIC_BRANCH_ID, -1)
    fun getLastBiometricBranchName(): String = userPrefs.getString(KEY_LAST_BIOMETRIC_BRANCH_NAME, "") ?: ""

    fun clearBiometricData() {
        securePrefs.edit().remove(KEY_LAST_BIOMETRIC_TOKEN).apply()
        userPrefs.edit()
            .remove(KEY_LAST_BIOMETRIC_USER)
            .remove(KEY_LAST_BIOMETRIC_ROLE)
            .remove(KEY_LAST_BIOMETRIC_DISPLAY_NAME)
            .remove(KEY_LAST_BIOMETRIC_USER_ID)
            .remove(KEY_LAST_BIOMETRIC_BRANCH_ID)
            .remove(KEY_LAST_BIOMETRIC_BRANCH_NAME)
            .apply()
    }

    @Throws(GeneralSecurityException::class)
    fun createBiometricKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    @Nullable
    fun getBiometricKey(): SecretKey? {
        return try {
            keyStore?.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            null
        }
    }

    fun isBiometricKeyAvailable(): Boolean = getBiometricKey() != null

    @Nullable
    fun encryptWithBiometricKey(data: String): String? {
        return try {
            var key = getBiometricKey() ?: createBiometricKey()
            val cipher = Cipher.getInstance(BIOMETRIC_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedData = cipher.doFinal(data.toByteArray())
            val iv = cipher.iv
            val combined = ByteArray(iv.size + encryptedData.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedData, 0, combined, iv.size, encryptedData.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting with biometric key", e)
            null
        }
    }

    @Nullable
    fun decryptWithBiometricKey(encryptedBase64: String): String? {
        return try {
            val key = getBiometricKey() ?: return null
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(BIOMETRIC_TRANSFORMATION)
            val ivLength = 12 // GCM standard IV length
            val iv = ByteArray(ivLength)
            val encryptedData = ByteArray(combined.size - ivLength)
            System.arraycopy(combined, 0, iv, 0, ivLength)
            System.arraycopy(combined, ivLength, encryptedData, 0, encryptedData.size)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decryptedData = cipher.doFinal(encryptedData)
            String(decryptedData)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting with biometric key", e)
            null
        }
    }

    fun deleteBiometricKey() {
        try {
            keyStore?.deleteEntry(BIOMETRIC_KEY_ALIAS)
            clearBiometricData()
            setBiometricEnabled(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting biometric key", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Auto-Lock & Activity
    // ═══════════════════════════════════════════════════════════════════

    fun setLastAppActiveTime(timestamp: Long) {
        cachedLastActiveTime = timestamp
        userPrefs.edit().putLong(KEY_LAST_ACTIVE_TIME, timestamp).apply()
    }

    fun getLastAppActiveTime(): Long {
        if (cachedLastActiveTime != null) return cachedLastActiveTime!!
        val valTime = userPrefs.getLong(KEY_LAST_ACTIVE_TIME, 0)
        cachedLastActiveTime = valTime
        return valTime
    }

    fun setSessionLocked(locked: Boolean) {
        cachedSessionLocked = locked
        userPrefs.edit().putBoolean(KEY_SESSION_LOCKED, locked).apply()
    }

    fun isSessionLocked(): Boolean {
        if (cachedSessionLocked != null) return cachedSessionLocked!!
        val valLocked = userPrefs.getBoolean(KEY_SESSION_LOCKED, false)
        cachedSessionLocked = valLocked
        return valLocked
    }

    fun touchActivity() { setLastAppActiveTime(System.currentTimeMillis()) }

    fun isInactivityTimeout(timeoutMs: Long): Boolean {
        val last = getLastAppActiveTime()
        if (last <= 0) return false
        return (System.currentTimeMillis() - last) > timeoutMs
    }

    fun unlockSession() {
        setSessionLocked(false)
        touchActivity()
    }

    fun resetAutoLock() = unlockSession()

    // ═══════════════════════════════════════════════════════════════════
    // إدارة الفرع (Branch ID & Name)
    // ═══════════════════════════════════════════════════════════════════

    fun setBranchId(branchId: Int) { userPrefs.edit().putInt(KEY_BRANCH_ID, branchId).apply() }

    fun getBranchId(): Int {
        val branchId = userPrefs.getInt(KEY_BRANCH_ID, -1)
        if (branchId > 0) return branchId
        val bioBranchId = getLastBiometricBranchId()
        if (bioBranchId > 0) return bioBranchId
        return -1
    }

    fun setBranchName(branchName: String?) { if (branchName != null) userPrefs.edit().putString(KEY_BRANCH_NAME, branchName).apply() }
    fun getBranchName(): String = userPrefs.getString(KEY_BRANCH_NAME, "") ?: ""

    // ═══════════════════════════════════════════════════════════════════
    // ترخيص النظام (MIRA License Center)
    // ═══════════════════════════════════════════════════════════════════

    fun saveActivationToken(token: String?) {
        if (token.isNullOrEmpty()) return
        securePrefs.edit()
            .putString(KEY_ACTIVATION_TOKEN, token)
            .putLong(KEY_ACTIVATION_AT, System.currentTimeMillis())
            .commit()
    }

    fun getActivationToken(): String = securePrefs.getString(KEY_ACTIVATION_TOKEN, "") ?: ""
    fun hasActivationToken(): Boolean = getActivationToken().isNotEmpty()

    fun saveDeviceUuid(uuid: String?) { if (uuid != null) securePrefs.edit().putString(KEY_DEVICE_UUID, uuid).apply() }
    fun getDeviceUuid(): String = securePrefs.getString(KEY_DEVICE_UUID, "") ?: ""

    fun saveLicenseInfo(type: String?, validFrom: String?, validUntil: String?, maxDevices: Int, maxUsers: Int) {
        userPrefs.edit()
            .putString(KEY_LICENSE_TYPE, type ?: "")
            .putString(KEY_LICENSE_VALID_FROM, validFrom ?: "")
            .putString(KEY_LICENSE_VALID_UNTIL, validUntil ?: "")
            .putInt(KEY_LICENSE_MAX_DEVICES, maxDevices)
            .putInt(KEY_LICENSE_MAX_USERS, maxUsers)
            .apply()
    }

    fun getLicenseType(): String = userPrefs.getString(KEY_LICENSE_TYPE, "") ?: ""
    fun getLicenseValidFrom(): String = userPrefs.getString(KEY_LICENSE_VALID_FROM, "") ?: ""
    fun getLicenseValidUntil(): String = userPrefs.getString(KEY_LICENSE_VALID_UNTIL, "") ?: ""
    fun getLicenseMaxDevices(): Int = userPrefs.getInt(KEY_LICENSE_MAX_DEVICES, 0)
    fun getLicenseMaxUsers(): Int = userPrefs.getInt(KEY_LICENSE_MAX_USERS, 0)

    fun saveLastHeartbeat(timestampMs: Long) { userPrefs.edit().putLong(KEY_LAST_HEARTBEAT, timestampMs).apply() }
    fun getLastHeartbeat(): Long = userPrefs.getLong(KEY_LAST_HEARTBEAT, 0L)

    fun saveOfflineSettings(graceDays: Int, warnDay: Int, restrictDay: Int) {
        userPrefs.edit()
            .putInt(KEY_OFFLINE_GRACE_DAYS, graceDays)
            .putInt(KEY_OFFLINE_WARNING_DAY, warnDay)
            .putInt(KEY_OFFLINE_RESTRICT_DAY, restrictDay)
            .apply()
    }

    fun getOfflineGraceDays(): Int = userPrefs.getInt(KEY_OFFLINE_GRACE_DAYS, 7)
    fun getOfflineWarningDay(): Int = userPrefs.getInt(KEY_OFFLINE_WARNING_DAY, 4)
    fun getOfflineRestrictDay(): Int = userPrefs.getInt(KEY_OFFLINE_RESTRICT_DAY, 6)

    fun getActivationTimestamp(): Long = securePrefs.getLong(KEY_ACTIVATION_AT, 0L)

    fun getDaysSinceLastHeartbeat(): Int {
        val last = getLastHeartbeat()
        if (last <= 0) return 0
        val diffMs = System.currentTimeMillis() - last
        return (diffMs / (24 * 60 * 60 * 1000L)).toInt()
    }

    fun getOfflineStatus(): String {
        val days = getDaysSinceLastHeartbeat()
        return when {
            days >= getOfflineGraceDays() + 1 -> "BLOCKED"
            days >= getOfflineRestrictDay() -> "RESTRICTED"
            days >= getOfflineWarningDay() -> "WARNING"
            else -> "ONLINE"
        }
    }

    fun clearLicense() {
        securePrefs.edit()
            .remove(KEY_ACTIVATION_TOKEN)
            .remove(KEY_DEVICE_UUID)
            .remove(KEY_ACTIVATION_AT)
            .apply()
        userPrefs.edit()
            .remove(KEY_LICENSE_TYPE)
            .remove(KEY_LICENSE_VALID_FROM)
            .remove(KEY_LICENSE_VALID_UNTIL)
            .remove(KEY_LICENSE_MAX_DEVICES)
            .remove(KEY_LICENSE_MAX_USERS)
            .remove(KEY_LAST_HEARTBEAT)
            .remove(KEY_OFFLINE_GRACE_DAYS)
            .remove(KEY_OFFLINE_WARNING_DAY)
            .remove(KEY_OFFLINE_RESTRICT_DAY)
            .apply()
    }

    // ═══════════════════════════════════════════════════════════════════
    // الأدوات المساعدة والتنظيف
    // ═══════════════════════════════════════════════════════════════════

    fun clearAll() {
        securePrefs.edit().clear().commit()
        userPrefs.edit().clear().commit()
        invalidateCache()
        deleteBiometricKey()
        setSessionLocked(false)
        setLastAppActiveTime(0)
    }

    fun getSessionSummary(): String {
        return "isLoggedIn=${isLoggedIn()}, username=${getUsername()}, role=${getRoleString()}, hasValidToken=${hasValidToken()}, biometricEnabled=${isBiometricEnabled()}, hasApiKey=${hasApiKey()}, serverUrl=${getServerUrl()}"
    }

    fun clearAuthToken() {
        securePrefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .apply()
        cachedAuthToken = null
        tokenCacheTime = 0
    }

    fun clearSessionCompletely() {
        securePrefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
        userPrefs.edit()
            .remove(KEY_IS_LOGGED_IN)
            .remove(KEY_USERNAME)
            .remove(KEY_USER_ROLE)
            .apply()
        invalidateCache()
    }
}
