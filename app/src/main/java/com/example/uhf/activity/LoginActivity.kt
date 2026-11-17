package com.example.uhf.activity

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.uhf.MiraRfid.sound.MiraSoundManager
import com.example.uhf.R
import com.example.uhf.api.MiraApiClient
import com.example.uhf.license.MiraBlobFetcher
import com.example.uhf.license.MiraBlobStorage
import com.example.uhf.license.MiraBlobVerifier
import com.example.uhf.manager.MiraBranchesManager
import com.example.uhf.manager.MiraOrgProfileManager
import com.example.uhf.manager.MiraServerConfig
import com.example.uhf.manager.PermissionManager
import com.example.uhf.tools.MemoryManager
import com.example.uhf.tools.SessionManager
import com.example.uhf.ui.theme.MiraJewelryTheme
import com.example.uhf.ui.theme.MiraTheme
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.roundToInt

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val DEFAULT_SERVER_URL = "https://jewelry.ibreg.org/api"

        private const val MAX_LOGIN_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
        private const val PBKDF2_ITERATIONS = 10000
        private const val HASH_KEY_LENGTH = 256

        private var bgExecutor: ExecutorService? = null

        @Synchronized
        private fun initExecutor() {
            if (bgExecutor == null || bgExecutor!!.isShutdown || bgExecutor!!.isTerminated) {
                bgExecutor = Executors.newFixedThreadPool(3)
            }
        }
    }

    private lateinit var sessionManager: SessionManager
    private var apiClient: MiraApiClient? = null
    private var orgProfileManager: MiraOrgProfileManager? = null
    private var soundManager: MiraSoundManager? = null
    private var profileListener: MiraOrgProfileManager.OnProfileChangedListener? = null
    private var biometricPrompt: BiometricPrompt? = null
    private var promptInfo: BiometricPrompt.PromptInfo? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDestroyedFlag = AtomicBoolean(false)
    private val failedAttempts = AtomicInteger(0)
    private var encryptedPrefs: SharedPreferences? = null

    // ═══ حالات الواجهة التفاعلية (Compose Reactive States) ═══
    private var isArabicState by mutableStateOf(true)
    private var orgNameState by mutableStateOf("MIRA Jewelry")
    private var orgLogoBitmapState by mutableStateOf<ImageBitmap?>(null)
    private var serverUrlState by mutableStateOf(DEFAULT_SERVER_URL)
    private var usernameState by mutableStateOf("")
    private var passwordState by mutableStateOf("")
    private var appVersionState by mutableStateOf("v1.6.0 Enterprise")

    private var usernameErrorState by mutableStateOf<String?>(null)
    private var passwordErrorState by mutableStateOf<String?>(null)
    private var serverErrorState by mutableStateOf<String?>(null)

    private var isCheckingLicenseState by mutableStateOf(false)
    private var isLoggingInState by mutableStateOf(false)
    private var biometricAvailableState by mutableStateOf(false)
    private var lockoutUntilState by mutableLongStateOf(0L)
    private var shakeCounterState by mutableIntStateOf(0)

    private var showSuccessOverlayState by mutableStateOf(false)
    private var loggedInUserDisplayState by mutableStateOf("")
    private var loggedInBranchDisplayState by mutableStateOf("")

    private var blockedDialogDataState by mutableStateOf<Pair<String, String>?>(null)
    private var offlineConfirmCredentialsState by mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        // 1. حماية الشاشة
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        isDestroyedFlag.set(false)
        initExecutor()

        MemoryManager.getInstance().registerActivity(this)
        registerComponentCallbacks(MemoryManager.getInstance())

        // 2. اللغة والتهيئة الأساسية
        val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        isArabicState = currentLang.isEmpty() || currentLang.startsWith("ar")

        sessionManager = SessionManager(applicationContext)
        soundManager = MiraSoundManager.getInstance(applicationContext)
        apiClient = MiraApiClient.getInstance(applicationContext)

        initInitialData()

        setContent {
            val layoutDirection = if (isArabicState) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                MiraJewelryTheme {
                    LoginVaultScreen(
                        isArabic = isArabicState,
                        orgName = orgNameState,
                        orgLogoBitmap = orgLogoBitmapState,
                        appVersion = appVersionState,
                        serverUrl = serverUrlState,
                        onServerUrlChange = {
                            serverUrlState = it
                            serverErrorState = null
                        },
                        onSaveServerUrl = { saveServerUrl(it) },
                        serverError = serverErrorState,
                        username = usernameState,
                        onUsernameChange = {
                            usernameState = it
                            usernameErrorState = null
                        },
                        usernameError = usernameErrorState,
                        password = passwordState,
                        onPasswordChange = {
                            passwordState = it
                            passwordErrorState = null
                        },
                        passwordError = passwordErrorState,
                        isCheckingLicense = isCheckingLicenseState,
                        isLoggingIn = isLoggingInState,
                        biometricAvailable = biometricAvailableState,
                        lockoutUntilMs = lockoutUntilState,
                        shakeCounter = shakeCounterState,
                        showSuccessOverlay = showSuccessOverlayState,
                        loggedInDisplayName = loggedInUserDisplayState,
                        loggedInBranchName = loggedInBranchDisplayState,
                        blockedDialog = blockedDialogDataState,
                        onBlockedRetry = {
                            blockedDialogDataState = null
                            recreate()
                        },
                        onBlockedReActivate = {
                            blockedDialogDataState = null
                            sessionManager.clearLicense()
                            startActivity(Intent(this, ActivationActivity::class.java))
                            finish()
                        },
                        showOfflineConfirm = offlineConfirmCredentialsState != null,
                        onConfirmOfflineLogin = {
                            offlineConfirmCredentialsState = null
                            resetFailedAttempts()
                            playTone(true)
                            showSuccessOverlayAndNavigate()
                        },
                        onCancelOfflineLogin = {
                            offlineConfirmCredentialsState = null
                        },
                        onToggleLanguage = { toggleLanguage() },
                        onSignInClick = { attemptApiLogin() },
                        onBiometricClick = { triggerBiometricPrompt() }
                    )
                }
            }
        }

        // 3. فحص الترخيص في الخلفية
        checkLicenseIntegrityAsync()
    }

    private fun initInitialData() {
        val savedUrl = sessionManager.serverUrl
        if (!savedUrl.isNullOrEmpty()) {
            serverUrlState = savedUrl
        } else {
            serverUrlState = DEFAULT_SERVER_URL
            sessionManager.setServerUrl(DEFAULT_SERVER_URL)
            MiraServerConfig.getInstance(this).setBaseUrl(DEFAULT_SERVER_URL)
        }

        appVersionState = try {
            val vName = packageManager.getPackageInfo(packageName, 0).versionName
            "v$vName Enterprise"
        } catch (_: Exception) {
            "v1.6.0 Enterprise"
        }

        applyOrgProfile()
    }

    private fun checkLicenseIntegrityAsync() {
        if (!sessionManager.hasActivationToken()) {
            Log.d(TAG, "No activation token → skip blob check")
            continueOnCreateAfterLicenseCheck()
            return
        }

        val storedBlob = MiraBlobStorage.load(this)
        if (storedBlob != null) {
            val result = MiraBlobVerifier.verify(
                storedBlob.blobJson, storedBlob.signatureB64, storedBlob.publicKeyB64
            )
            if (result.valid) {
                val fullResult = MiraBlobVerifier.verifyAndValidate(storedBlob, storedBlob)
                if (fullResult.valid) {
                    Log.d(TAG, "✅ License blob valid (v${storedBlob.version})")
                    continueOnCreateAfterLicenseCheck()
                    return
                } else {
                    Log.w(TAG, "⚠️ Stored blob expired: ${fullResult.message} — will try refresh")
                }
            } else {
                Log.w(TAG, "⚠️ Stored blob tampered: ${result.message} — will try refresh")
            }
        }

        Log.d(TAG, "📥 Fetching blob from server (optional)")
        isCheckingLicenseState = true

        executeSafeBg {
            var fetched = false
            try {
                fetched = MiraBlobFetcher.fetchVerifyAndStore(this@LoginActivity)
            } catch (e: Exception) {
                Log.w(TAG, "blob fetch exception: ${e.message}")
            }

            mainHandler.post {
                if (!isActivityAlive()) return@post
                isCheckingLicenseState = false

                if (fetched) {
                    Log.d(TAG, "✅ Blob fetched from server")
                } else {
                    Log.w(TAG, "⚠️ Blob fetch failed — but activation token exists, proceeding")
                }
                continueOnCreateAfterLicenseCheck()
            }
        }
    }

    private fun continueOnCreateAfterLicenseCheck() {
        // [1] فحص activation token
        if (!sessionManager.hasActivationToken()) {
            Log.w(TAG, "⚠️ No activation token — redirecting to ActivationActivity")
            val intent = Intent(this, ActivationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            return
        }

        // [2] فحص offline status
        val offlineStatus = sessionManager.offlineStatus
        if ("BLOCKED" == offlineStatus) {
            val days = sessionManager.daysSinceLastHeartbeat
            Log.w(TAG, "🚫 License BLOCKED — offline for $days days")
            val title = if (isArabicState) "الترخيص يحتاج اتصالاً بالخادم" else "License Needs Connection"
            val msg = if (isArabicState) {
                "انقطع الاتصال بالخادم لأكثر من ${sessionManager.offlineGraceDays} أيام.\n\nيرجى الاتصال بالإنترنت لتجديد صلاحية الترخيص."
            } else {
                "Offline for more than ${sessionManager.offlineGraceDays} days.\n\nPlease connect to internet to renew license."
            }
            blockedDialogDataState = title to msg
            return
        }

        if ("WARNING" == offlineStatus || "RESTRICTED" == offlineStatus) {
            val daysLeft = sessionManager.offlineGraceDays - sessionManager.daysSinceLastHeartbeat
            Toast.makeText(
                this,
                if (isArabicState) "⚠️ تحذير: انقطع الاتصال بـ $daysLeft أيام."
                else "⚠️ Warning: offline for $daysLeft days.",
                Toast.LENGTH_LONG
            ).show()
        }

        // [3] فحص الجلسة القائمة
        if (sessionManager.isLoggedIn) {
            if (sessionManager.hasValidToken()) {
                navigateToMainDirect()
                return
            } else {
                sessionManager.logoutUser()
            }
        }

        // [4] معالجة انتهاء الجلسة
        if (intent != null && intent.getBooleanExtra("session_expired", false)) {
            Toast.makeText(
                this,
                if (isArabicState) "انتهت صلاحية الجلسة - سجل الدخول مجدداً"
                else "Session expired - Please sign in again",
                Toast.LENGTH_LONG
            ).show()
            soundManager?.play(MiraSoundManager.SoundEvent.LOGOUT)
        }

        orgProfileManager = MiraOrgProfileManager.getInstance(applicationContext)

        // 5. تهيئة التشفير والبصمة
        initializeEncryption()
        setupProfileListener()

        mainHandler.postDelayed({
            if (isActivityAlive()) {
                restoreLastUsername()
                loadOrgProfileFromCloud()
            }
        }, 250)

        Log.d(TAG, "onCreate completed")
    }

    private fun executeSafeBg(runnable: Runnable) {
        initExecutor()
        try {
            bgExecutor?.execute(runnable)
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "Task rejected, using fallback thread", e)
            Thread(runnable).start()
        }
    }

    private fun isActivityAlive(): Boolean {
        return !isFinishing && !isDestroyed && !isDestroyedFlag.get()
    }

    private fun initializeEncryption() {
        executeSafeBg {
            try {
                val masterKey = MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                encryptedPrefs = EncryptedSharedPreferences.create(
                    this,
                    "secure_login_prefs_v2",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                Log.d(TAG, "Encryption initialized successfully")
            } catch (e: Exception) {
                when (e) {
                    is GeneralSecurityException, is IOException -> {
                        Log.e(TAG, "EncryptedSharedPreferences failed, using private fallback", e)
                        encryptedPrefs = getSharedPreferences("fallback_login_prefs_v2", MODE_PRIVATE)
                    }
                    else -> throw e
                }
            }

            mainHandler.post {
                if (isActivityAlive()) {
                    checkLockoutStatus()
                    setupBiometric()
                    updateBiometricButtonState()
                }
            }
        }
    }

    private fun toggleLanguage() {
        isArabicState = !isArabicState
        val langTag = if (isArabicState) "ar" else "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langTag))
        recreate()
    }

    private fun saveServerUrl(rawUrl: String) {
        var url = rawUrl.trim()
        if (isValidServerUrl(url)) {
            if (!url.endsWith("/")) url += "/"
            serverUrlState = url
            sessionManager.setServerUrl(url)
            MiraServerConfig.getInstance(this).setBaseUrl(url)

            val client = MiraApiClient.getInstance(applicationContext)
            if (client != null) {
                client.refreshUrl()
                client.invalidateTokenCache()
            }
            apiClient = client

            Log.d(TAG, "✅ Server URL saved: $url")
            Toast.makeText(
                this,
                if (isArabicState) "✓ تم حفظ رابط الخادم بنجاح" else "✓ Server URL saved",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            serverErrorState = if (isArabicState) {
                "يجب استخدام HTTPS للاتصال الآمن"
            } else {
                "HTTPS required for secure connection"
            }
            triggerShake()
        }
    }

    private fun applyOrgProfile() {
        if (!isActivityAlive()) return
        if (orgProfileManager == null) {
            orgProfileManager = MiraOrgProfileManager.getInstance(applicationContext)
        }

        val name = if (isArabicState) orgProfileManager?.orgNameAr else orgProfileManager?.orgNameEn
        orgNameState = if (!name.isNullOrEmpty()) name else "MIRA Jewelry"

        val logoBase64 = orgProfileManager?.orgLogo
        if (!logoBase64.isNullOrEmpty()) {
            try {
                val bytes = Base64.decode(logoBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                orgLogoBitmapState = bitmap?.asImageBitmap()
            } catch (_: Exception) {
                orgLogoBitmapState = null
            }
        } else {
            orgLogoBitmapState = null
        }
    }

    private fun setupProfileListener() {
        val manager = orgProfileManager ?: return
        profileListener = MiraOrgProfileManager.OnProfileChangedListener {
            if (isActivityAlive()) mainHandler.post { applyOrgProfile() }
        }
        manager.addListener(profileListener)
    }

    private fun loadOrgProfileFromCloud() {
        val client = apiClient ?: return
        executeSafeBg {
            try {
                val response = client.orgProfile
                if (response != null && response.isSuccess && response.data != null) {
                    val org = response.data.optJSONObject("organization")
                    if (org != null && orgProfileManager != null) {
                        orgProfileManager?.saveFromServer(org)
                    }
                    if (isActivityAlive()) {
                        mainHandler.post { applyOrgProfile() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadOrgProfileFromCloud error: ${e.message}")
            }
        }
    }

    private fun isValidServerUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val isLocalhost = url.contains("10.0.2.2") || url.contains("localhost") ||
                url.contains("127.0.0.1") || url.startsWith("192.168.")
        return if (isLocalhost) (url.startsWith("http://") || url.startsWith("https://")) else url.startsWith("https://")
    }

    private fun restoreLastUsername() {
        val lastUsername = sessionManager.lastUsername
        if (!lastUsername.isNullOrEmpty() && usernameState.isEmpty()) {
            usernameState = lastUsername
        }
    }

    private fun checkLockoutStatus() {
        val prefs = encryptedPrefs ?: return
        val until = prefs.getLong("lockout_until", 0L)
        val savedAttempts = prefs.getInt("failed_attempts", 0)
        failedAttempts.set(savedAttempts)
        lockoutUntilState = until
    }

    private fun recordFailedAttempt() {
        val attempts = failedAttempts.incrementAndGet()
        encryptedPrefs?.edit()?.putInt("failed_attempts", attempts)?.apply()

        if (attempts >= MAX_LOGIN_ATTEMPTS) {
            val newLockout = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            lockoutUntilState = newLockout
            encryptedPrefs?.edit()
                ?.putLong("lockout_until", newLockout)
                ?.putInt("failed_attempts", 0)
                ?.apply()
            failedAttempts.set(0)

            mainHandler.post {
                Toast.makeText(
                    this,
                    if (isArabicState) "⛔ تم قفل تسجيل الدخول لمدة 5 دقائق بسبب محاولات كثيرة"
                    else "⛔ Login locked for 5 minutes due to too many attempts",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resetFailedAttempts() {
        failedAttempts.set(0)
        lockoutUntilState = 0L
        encryptedPrefs?.edit()
            ?.putInt("failed_attempts", 0)
            ?.putLong("lockout_until", 0L)
            ?.apply()
    }

    private fun attemptApiLogin() {
        val username = usernameState.trim()
        val password = passwordState.trim()
        var currentServer = serverUrlState.trim()

        Log.d(TAG, "Login Attempt username=$username")

        if (System.currentTimeMillis() < lockoutUntilState) {
            val remainingMinutes = (lockoutUntilState - System.currentTimeMillis()) / (60 * 1000)
            Toast.makeText(
                this,
                String.format(
                    Locale.US,
                    if (isArabicState) "⛔ محظور - حاول بعد %d دقيقة" else "⛔ Locked - Try in %d min",
                    maxOf(1L, remainingMinutes)
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (currentServer.isEmpty()) {
            currentServer = sessionManager.serverUrl
            if (currentServer.isEmpty()) currentServer = DEFAULT_SERVER_URL
            serverUrlState = currentServer
        }

        if (!isValidServerUrl(currentServer)) {
            serverErrorState = if (isArabicState) "يجب استخدام HTTPS" else "HTTPS required"
            triggerShake()
            playTone(false)
            return
        }

        sessionManager.setServerUrl(currentServer)
        MiraServerConfig.getInstance(this).setBaseUrl(currentServer)

        if (username.isEmpty()) {
            usernameErrorState = if (isArabicState) "اسم المستخدم مطلوب" else "Username required"
            triggerShake()
            playTone(false)
            return
        }
        if (password.length < 4) {
            passwordErrorState = if (isArabicState) "كلمة المرور قصيرة جداً" else "Password too short"
            triggerShake()
            playTone(false)
            return
        }

        usernameErrorState = null
        passwordErrorState = null
        serverErrorState = null
        isLoggingInState = true

        val client = MiraApiClient.getInstance(applicationContext)
        apiClient = client
        if (client != null) {
            client.invalidateTokenCache()
            client.refreshUrl()
        }

        executeSafeBg {
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            var response: MiraApiClient.ApiResponse? = null
            var isNetworkError = false

            try {
                response = client?.login(username, password, deviceId)
                if (response != null) isNetworkError = response.isNetworkError
            } catch (_: Exception) {
                isNetworkError = true
            }

            mainHandler.post {
                if (!isActivityAlive()) return@post
                isLoggingInState = false

                if (response != null && response.isSuccess) {
                    resetFailedAttempts()
                    sessionManager.saveLastUsername(username)

                    encryptedPrefs?.let { prefs ->
                        val salt = generateSalt()
                        val passwordHash = hashPasswordWithSalt(password, salt)
                        if (passwordHash != null) {
                            prefs.edit()
                                .putString("offline_salt_$username", Base64.encodeToString(salt, Base64.NO_WRAP))
                                .putString("offline_hash_$username", passwordHash)
                                .apply()
                        }
                    }

                    handleLoginSuccess(response, username)
                } else if (isNetworkError || response == null) {
                    if (verifyOfflineLogin(username, password)) {
                        offlineConfirmCredentialsState = username to password
                    } else {
                        recordFailedAttempt()
                        playTone(false)
                        triggerShake()
                        Toast.makeText(
                            this,
                            if (isArabicState) "❌ لا يمكن التحقق من بيانات الدخول بدون اتصال\nيجب تسجيل الدخول مرة واحدة على الأقل بالإنترنت"
                            else "❌ Cannot verify credentials offline\nYou must login at least once with internet connection",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    recordFailedAttempt()
                    playTone(false)
                    triggerShake()
                    val errorMsg = getErrorMessage(
                        response.code,
                        if (isArabicState) "فشل الاتصال بالخادم" else "Connection failed"
                    )
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleLoginSuccess(response: MiraApiClient.ApiResponse, username: String) {
        Log.d(TAG, "Login Success for user: $username")
        playTone(true)

        sessionManager.resetAutoLock()
        val appContext = applicationContext

        val token = response.optString("token", "")
        val roleStr = response.optString("role", "ADMIN")
        val displayName = response.optString("display_name", username)
        val userId = response.optInt("user_id", 0)
        val branchId = response.optInt("branch_id", 1)
        val branchName = response.optString("branch_name", "")
        val branchCode = response.optString("branch_code", "")
        var serverUrl = serverUrlState.trim()
        if (serverUrl.isEmpty()) serverUrl = sessionManager.serverUrl

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val apiKey = response.optString("api_key", "")
        val gateId = response.optString("gate_id", "")

        if (apiKey.isNotEmpty()) {
            val effectiveGateId = gateId.ifEmpty { deviceId }
            sessionManager.saveApiKey(apiKey)
            sessionManager.saveGateId(effectiveGateId)
        }

        if (serverUrl.isNotEmpty()) {
            sessionManager.setServerUrl(serverUrl)
            MiraServerConfig.getInstance(appContext).setBaseUrl(serverUrl)
        }

        // 1. حفظ الصلاحيات القادمة من السيرفر حصرياً
        PermissionManager.resetInstance()
        val pm = PermissionManager.getInstance(appContext)
        val permissionsJson = response.optString("permissions", "")
        if (!permissionsJson.isNullOrEmpty()) {
            pm.savePermissions(permissionsJson)
            Log.d(TAG, "✅ Server-driven permissions saved successfully")
        } else {
            Log.w(TAG, "⚠️ Permissions JSON empty from server response, using role fallback")
        }

        // 2. إنشاء جلسة الدخول
        sessionManager.createLoginSession(username, roleStr, token, serverUrl)
        sessionManager.setDisplayName(displayName)
        sessionManager.setUserId(userId)
        sessionManager.setBranchId(branchId)

        sessionManager.saveBiometricUser(username, roleStr, displayName, userId, token)
        sessionManager.saveBiometricBranch(branchId, branchName)

        val biometricEnabled = response.data?.optBoolean("biometric_enabled", true) ?: true
        sessionManager.setBiometricLoginEnabled(biometricEnabled)

        val bm = MiraBranchesManager.getInstance(appContext)
        bm.setActiveBranch(branchId, branchName, branchCode, "", "")
        bm.setUserBranchLocked(true)

        val client = MiraApiClient.getInstance(appContext)
        if (client != null) {
            client.invalidateTokenCache()
            client.refreshUrl()
        }

        loggedInUserDisplayState = displayName
        loggedInBranchDisplayState = branchName
        showSuccessOverlayAndNavigate()
    }

    private fun setupBiometric() {
        try {
            if (!sessionManager.isBiometricLoginEnabled) {
                biometricAvailableState = false
                return
            }

            val bm = BiometricManager.from(this)
            val canAuthenticate = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
                biometricAvailableState = false
                return
            }

            val executor: Executor = ContextCompat.getMainExecutor(this)
            biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val lastUser = sessionManager.lastBiometricUser
                    val lastRole = sessionManager.lastBiometricRole
                    val lastDisplayName = sessionManager.lastBiometricDisplayName
                    val lastUserId = sessionManager.lastBiometricUserId
                    val lastToken = sessionManager.lastBiometricToken

                    if (lastUser.isEmpty()) {
                        playTone(false)
                        Toast.makeText(
                            this@LoginActivity,
                            if (isArabicState) "قم بتسجيل الدخول بكلمة المرور أولاً" else "Sign in with password first",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    sessionManager.createLoginSession(
                        lastUser,
                        lastRole,
                        lastToken.ifEmpty { "BIOMETRIC_TOKEN" },
                        sessionManager.serverUrl
                    )
                    sessionManager.setDisplayName(lastDisplayName)
                    sessionManager.setUserId(lastUserId)

                    val savedBranchId = sessionManager.lastBiometricBranchId
                    val savedBranchName = sessionManager.lastBiometricBranchName
                    if (savedBranchId > 0) {
                        val branchesManager = MiraBranchesManager.getInstance(applicationContext)
                        branchesManager.setActiveBranch(savedBranchId, savedBranchName, "", "", "")
                        branchesManager.setUserBranchLocked(true)
                    }

                    if (sessionManager.lastBiometricUser.isNotEmpty()) {
                        sessionManager.saveBiometricUser(lastUser, lastRole, lastDisplayName, lastUserId, lastToken)
                    }

                    sessionManager.resetAutoLock()
                    resetFailedAttempts()
                    playTone(true)

                    loggedInUserDisplayState = lastDisplayName.ifEmpty { lastUser }
                    loggedInBranchDisplayState = savedBranchName
                    showSuccessOverlayAndNavigate()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        playTone(false)
                        Toast.makeText(this@LoginActivity, errString, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    playTone(false)
                    Toast.makeText(
                        this@LoginActivity,
                        if (isArabicState) "لم يتم التعرف على بصمة الإصبع" else "Fingerprint not recognized",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })

            val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (isArabicState) "تسجيل الدخول الحيوي" else "Biometric Sign In")
                .setSubtitle(if (isArabicState) "استخدم بصمة الإصبع للمتابعة" else "Use fingerprint to continue")
                .setNegativeButtonText(if (isArabicState) "إلغاء" else "Cancel")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                promptBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }

            promptInfo = promptBuilder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Biometric setup error: ${e.message}", e)
            biometricAvailableState = false
        }
    }

    private fun triggerBiometricPrompt() {
        if (!sessionManager.isBiometricLoginEnabled) {
            Toast.makeText(
                this,
                if (isArabicState) "البصمة معطلة" else "Biometric disabled",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (sessionManager.lastBiometricUser.isNotEmpty() || sessionManager.isLoggedIn) {
            val prompt = biometricPrompt
            val info = promptInfo
            if (prompt != null && info != null) {
                prompt.authenticate(info)
            }
        } else {
            playTone(false)
            Toast.makeText(
                this,
                if (isArabicState) "سجل الدخول بكلمة المرور أولاً لتفعيل البصمة" else "Sign in with password first",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateBiometricButtonState() {
        val biometricEnabled = sessionManager.isBiometricLoginEnabled
        val hasBiometricUser = sessionManager.lastBiometricUser.isNotEmpty()
        biometricAvailableState = biometricEnabled && hasBiometricUser
    }

    private fun verifyOfflineLogin(username: String, password: String): Boolean {
        return try {
            val prefs = encryptedPrefs ?: return false
            val savedSaltBase64 = prefs.getString("offline_salt_$username", "") ?: ""
            val savedHash = prefs.getString("offline_hash_$username", "") ?: ""

            if (savedSaltBase64.isEmpty() || savedHash.isEmpty()) return false

            val salt = Base64.decode(savedSaltBase64, Base64.DEFAULT)
            val currentHash = hashPasswordWithSalt(password, salt)

            if (currentHash != null && currentHash == savedHash) {
                sessionManager.createLoginSession(
                    username,
                    sessionManager.lastBiometricRole,
                    "OFFLINE_TOKEN",
                    sessionManager.serverUrl
                )
                sessionManager.setDisplayName(sessionManager.lastBiometricDisplayName)
                sessionManager.setUserId(sessionManager.lastBiometricUserId)

                val savedBranchId = sessionManager.lastBiometricBranchId
                val savedBranchName = sessionManager.lastBiometricBranchName
                if (savedBranchId > 0) {
                    val bm = MiraBranchesManager.getInstance(applicationContext)
                    bm.setActiveBranch(savedBranchId, savedBranchName, "", "", "")
                    bm.setUserBranchLocked(true)
                }

                sessionManager.resetAutoLock()
                loggedInUserDisplayState = sessionManager.lastBiometricDisplayName.ifEmpty { username }
                loggedInBranchDisplayState = savedBranchName
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyOfflineLogin error: ${e.message}")
            false
        }
    }

    private fun hashPasswordWithSalt(password: String, salt: ByteArray): String? {
        return try {
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_KEY_LENGTH)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = skf.generateSecret(spec).encoded
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (e: Exception) {
            when (e) {
                is NoSuchAlgorithmException, is InvalidKeySpecException -> {
                    Log.e(TAG, "Password hashing error", e)
                    null
                }
                else -> throw e
            }
        }
    }

    private fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt
    }

    private fun playTone(isSuccess: Boolean) {
        soundManager?.play(
            if (isSuccess) MiraSoundManager.SoundEvent.LOGIN_SUCCESS
            else MiraSoundManager.SoundEvent.LOGIN_FAILED
        )
    }

    private fun getErrorMessage(errorCode: Int, fallbackMessage: String?): String {
        return when (errorCode) {
            400 -> if (isArabicState) "بيانات غير صالحة - تحقق من المدخلات" else "Invalid data - Check inputs"
            401 -> if (isArabicState) "بيانات الدخول غير صحيحة" else "Invalid credentials"
            403 -> if (isArabicState) "ليس لديك صلاحية للدخول" else "Access denied"
            404 -> if (isArabicState) "الخادم غير موجود - تحقق من الرابط" else "Server not found"
            408, 504 -> if (isArabicState) "انتهت مهلة الاتصال - حاول مرة أخرى" else "Connection timeout"
            429 -> if (isArabicState) "محاولات كثيرة - انتظر قليلاً" else "Too many attempts"
            500 -> if (isArabicState) "خطأ في الخادم - حاول لاحقاً" else "Server error"
            503 -> if (isArabicState) "الخدمة غير متاحة حالياً" else "Service unavailable"
            else -> fallbackMessage ?: if (isArabicState) "خطأ في الاتصال" else "Connection error"
        }
    }

    private fun triggerShake() {
        shakeCounterState++
    }

    private fun showSuccessOverlayAndNavigate() {
        showSuccessOverlayState = true
        mainHandler.postDelayed({
            if (isActivityAlive()) navigateToMainDirect()
        }, 850)
    }

    private fun navigateToMainDirect() {
        val intent = Intent(this, UHFMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("fresh_start", true)
        }
        startActivity(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onResume() {
        super.onResume()
        applyOrgProfile()
        updateBiometricButtonState()
        checkLockoutStatus()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        MemoryManager.getInstance().unregisterActivity(this)
        unregisterComponentCallbacks(MemoryManager.getInstance())
        isDestroyedFlag.set(true)

        if (orgProfileManager != null && profileListener != null) {
            orgProfileManager?.removeListener(profileListener)
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  🎨 واجهة تسجيل الدخول الفاخرة (Jetpack Compose Luxury Vault UI)
// ═══════════════════════════════════════════════════════════════════════════

private val EmeraldAccent = Color(0xFF10B981)
private val CyanAccent    = Color(0xFF38BDF8)
private val ErrorCrimson  = Color(0xFFEF4444)

@Composable
private fun LoginVaultScreen(
    isArabic: Boolean,
    orgName: String,
    orgLogoBitmap: ImageBitmap?,
    appVersion: String,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onSaveServerUrl: (String) -> Unit,
    serverError: String?,
    username: String,
    onUsernameChange: (String) -> Unit,
    usernameError: String?,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordError: String?,
    isCheckingLicense: Boolean,
    isLoggingIn: Boolean,
    biometricAvailable: Boolean,
    lockoutUntilMs: Long,
    shakeCounter: Int,
    showSuccessOverlay: Boolean,
    loggedInDisplayName: String,
    loggedInBranchName: String,
    blockedDialog: Pair<String, String>?,
    onBlockedRetry: () -> Unit,
    onBlockedReActivate: () -> Unit,
    showOfflineConfirm: Boolean,
    onConfirmOfflineLogin: () -> Unit,
    onCancelOfflineLogin: () -> Unit,
    onToggleLanguage: () -> Unit,
    onSignInClick: () -> Unit,
    onBiometricClick: () -> Unit
) {
    val colors = MiraTheme.colors
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    var passwordVisible by remember { mutableStateOf(false) }
    var serverPanelExpanded by remember { mutableStateOf(false) }

    // مؤقت حي لحالة القفل الأمني (Lockout Countdown)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntilMs) {
        while (lockoutUntilMs > System.currentTimeMillis()) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L)
        }
        nowMs = System.currentTimeMillis()
    }
    val remainingLockSeconds = ((lockoutUntilMs - nowMs) / 1000L).coerceAtLeast(0L)
    val isLocked = remainingLockSeconds > 0L

    // حركة اهتزاز البطاقة عند الخطأ
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeCounter) {
        if (shakeCounter > 0) {
            val keyframes = listOf(18f, -18f, 14f, -14f, 8f, -8f, 0f)
            for (target in keyframes) {
                shakeOffset.animateTo(target, animationSpec = tween(durationMillis = 55))
            }
        }
    }

    // دوران حلقة الشعار ولمعة الذهب
    val infiniteTransition = rememberInfiniteTransition(label = "vaultLoops")
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )
    val shimmerX by infiniteTransition.animateFloat(
        initialValue = -250f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "buttonShimmer"
    )

    val canSubmit = username.trim().isNotEmpty() && password.trim().length >= 4 && !isLocked && !isLoggingIn

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.backgroundTop
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.backgroundTop, colors.backgroundMid, colors.backgroundTop)
                    )
                )
        ) {
            // إضاءة محيطية ملكية خلف الشعار
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.goldPrimary.copy(alpha = colors.glowAlpha),
                            CyanAccent.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(center.x, 175.dp.toPx()),
                        radius = size.minDimension * 0.72f
                    ),
                    radius = size.minDimension * 0.72f,
                    center = Offset(center.x, 175.dp.toPx())
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                // 1. شريط الأدوات العلوي (تبديل اللغة + إعدادات الخادم)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // زر إعدادات الخادم
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.cardSurface.copy(alpha = 0.85f),
                        border = BorderStroke(
                            1.dp,
                            if (serverPanelExpanded) colors.goldPrimary else colors.cardBorder
                        ),
                        modifier = Modifier.clickable {
                            serverPanelExpanded = !serverPanelExpanded
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "الخادم" else "Server",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (serverPanelExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // شارة إصدار النظام
                    Text(
                        text = appVersion,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary
                    )

                    // زر تبديل اللغة
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colors.cardSurface.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier.clickable { onToggleLanguage() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                tint = colors.goldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "EN" else "عربي",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.goldPrimary
                            )
                        }
                    }
                }

                // 2. لوحة إعدادات الخادم القابلة للطي
                AnimatedVisibility(
                    visible = serverPanelExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                        border = BorderStroke(1.dp, colors.goldPrimary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (isArabic) "رابط خادم المنشأة (HTTPS)" else "Facility Server Endpoint (HTTPS)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.goldPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = onServerUrlChange,
                                singleLine = true,
                                isError = serverError != null,
                                textStyle = TextStyle(
                                    fontSize = 13.sp,
                                    textDirection = TextDirection.Ltr,
                                    color = colors.textPrimary
                                ),
                                placeholder = {
                                    Text(
                                        text = "https://jewelry.ibreg.org/api",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary.copy(alpha = 0.5f)
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.goldPrimary,
                                    unfocusedBorderColor = colors.cardBorder,
                                    errorBorderColor = ErrorCrimson
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (serverError != null) {
                                Text(
                                    text = serverError,
                                    color = ErrorCrimson,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = {
                                        onSaveServerUrl(serverUrl)
                                        if (serverError == null) serverPanelExpanded = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.goldPrimary,
                                        contentColor = Color(0xFF060B19)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isArabic) "حفظ الرابط" else "Save URL",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 3. ميدالية الشعار الملكية + هوية المنشأة
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(134.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        rotate(ringRotation) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    listOf(
                                        Color.Transparent,
                                        colors.goldPrimary,
                                        colors.goldHighlight,
                                        CyanAccent.copy(alpha = 0.75f),
                                        Color.Transparent
                                    )
                                ),
                                startAngle = 0f,
                                sweepAngle = 250f,
                                useCenter = false,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                                topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - 8.dp.toPx(),
                                    size.height - 8.dp.toPx()
                                )
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(108.dp)
                            .clip(CircleShape)
                            .background(colors.cardSurface)
                            .border(1.5.dp, colors.cardBorder, CircleShape)
                    ) {
                        if (orgLogoBitmap != null) {
                            Image(
                                bitmap = orgLogoBitmap,
                                contentDescription = orgName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_mira_id_logo),
                                contentDescription = "MIRA ID Logo",
                                modifier = Modifier.size(92.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = orgName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "MIRA ID™ SMART JEWELRY TAG",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp,
                    color = colors.goldPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 4. بطاقة تسجيل الدخول الرئيسية (مع حركة الاهتزاز)
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp)
                    ) {
                        // رأس البطاقة
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = if (isArabic) "مرحباً بعودتك" else "Welcome Back",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = if (isArabic) "سجّل الدخول للوصول إلى خزنة المنشأة" else "Sign in to access facility vault",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.12f))
                                    .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LockPerson,
                                    contentDescription = null,
                                    tint = colors.goldPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // مؤشر التحقق من الترخيص في الخلفية
                        AnimatedVisibility(visible = isCheckingLicense) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.goldPrimary.copy(alpha = 0.08f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = colors.goldPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isArabic) "جارٍ التحقق من سلامة الترخيص..." else "Verifying license integrity...",
                                    fontSize = 12.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        // شريط القفل الأمني عند تجاوز المحاولات (Lockout Countdown)
                        AnimatedVisibility(visible = isLocked) {
                            val mins = remainingLockSeconds / 60
                            val secs = remainingLockSeconds % 60
                            val formattedTime = String.format(Locale.US, "%02d:%02d", mins, secs)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ErrorCrimson.copy(alpha = 0.12f))
                                    .border(1.dp, ErrorCrimson.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GppBad,
                                    contentDescription = null,
                                    tint = ErrorCrimson,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isArabic) {
                                        "تم قفل الدخول مؤقتاً — حاول بعد $formattedTime"
                                    } else {
                                        "Login temporarily locked — Retry in $formattedTime"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (colors.isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // حقل اسم المستخدم
                        OutlinedTextField(
                            value = username,
                            onValueChange = onUsernameChange,
                            enabled = !isLoggingIn && !isLocked,
                            singleLine = true,
                            isError = usernameError != null,
                            label = {
                                Text(if (isArabic) "اسم المستخدم" else "Username")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = colors.goldPrimary
                                )
                            },
                            trailingIcon = {
                                if (username.isNotEmpty() && !isLoggingIn) {
                                    IconButton(onClick = { onUsernameChange("") }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear",
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.goldPrimary,
                                unfocusedBorderColor = colors.cardBorder,
                                focusedLabelColor = colors.goldPrimary,
                                errorBorderColor = ErrorCrimson,
                                focusedContainerColor = colors.backgroundTop.copy(alpha = 0.40f),
                                unfocusedContainerColor = colors.backgroundTop.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (usernameError != null) {
                            Text(
                                text = usernameError,
                                color = ErrorCrimson,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // حقل كلمة المرور
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            enabled = !isLoggingIn && !isLocked,
                            singleLine = true,
                            isError = passwordError != null,
                            label = {
                                Text(if (isArabic) "كلمة المرور" else "Password")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = colors.goldPrimary
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = "Toggle Password",
                                        tint = colors.textSecondary
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (canSubmit) onSignInClick()
                                }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.goldPrimary,
                                unfocusedBorderColor = colors.cardBorder,
                                focusedLabelColor = colors.goldPrimary,
                                errorBorderColor = ErrorCrimson,
                                focusedContainerColor = colors.backgroundTop.copy(alpha = 0.40f),
                                unfocusedContainerColor = colors.backgroundTop.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (passwordError != null) {
                            Text(
                                text = passwordError,
                                color = ErrorCrimson,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(22.dp))

                        // 5. صف أزرار الدخول (زر الدخول الذهبي + زر البصمة الحيوية)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val buttonBrush = if (canSubmit || isLoggingIn) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        colors.goldDeep,
                                        colors.goldPrimary,
                                        colors.goldHighlight,
                                        colors.goldPrimary
                                    ),
                                    start = Offset(shimmerX, 0f),
                                    end = Offset(shimmerX + 260f, 90f)
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(colors.trackBackground, colors.trackBackground)
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(buttonBrush)
                                    .clickable(enabled = canSubmit) {
                                        focusManager.clearFocus()
                                        onSignInClick()
                                    }
                            ) {
                                if (isLoggingIn) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF060B19),
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (isArabic) "جارٍ التحقق..." else "Authenticating...",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF060B19)
                                        )
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Login,
                                            contentDescription = null,
                                            tint = if (canSubmit) Color(0xFF060B19) else colors.textSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isArabic) "تسجيل الدخول" else "Sign In",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (canSubmit) Color(0xFF060B19) else colors.textSecondary
                                        )
                                    }
                                }
                            }

                            // زر البصمة الحيوية المخصص
                            if (biometricAvailable) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(colors.goldPrimary.copy(alpha = 0.14f))
                                        .border(1.5.dp, colors.goldPrimary, RoundedCornerShape(15.dp))
                                        .clickable(enabled = !isLoggingIn && !isLocked) {
                                            onBiometricClick()
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Fingerprint,
                                        contentDescription = "Biometric Login",
                                        tint = colors.goldPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(20.dp))

                // 6. تذييل الأمان
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) {
                            "اتصال مشفر AES-256 • منظومة ميرا تكنولوجي"
                        } else {
                            "AES-256 Encrypted Session • MIRA Technology"
                        },
                        fontSize = 11.sp,
                        color = colors.textSecondary
                    )
                }
            }

            // 7. شاشة نجاح تسجيل الدخول السينمائية (Success Overlay)
            AnimatedVisibility(
                visible = showSuccessOverlay,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(200))
            ) {
                LoginSuccessOverlay(
                    isArabic = isArabic,
                    displayName = loggedInDisplayName,
                    branchName = loggedInBranchName
                )
            }
        }
    }

    // 8. حوار تأكيد الدخول في وضع الأوفلاين
    if (showOfflineConfirm) {
        OfflineModeDialog(
            isArabic = isArabic,
            onConfirm = onConfirmOfflineLogin,
            onCancel = onCancelOfflineLogin
        )
    }

    // 9. حوار حظر الترخيص عند انقطاع الاتصال الطويل
    blockedDialog?.let { (title, message) ->
        LicenseBlockedDialog(
            isArabic = isArabic,
            title = title,
            message = message,
            onRetry = onBlockedRetry,
            onReActivate = onBlockedReActivate
        )
    }
}

@Composable
private fun LoginSuccessOverlay(
    isArabic: Boolean,
    displayName: String,
    branchName: String
) {
    val colors = MiraTheme.colors
    var animateCheck by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animateCheck = true }

    val scale by animateFloatAsState(
        targetValue = if (animateCheck) 1f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "successScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundTop.copy(alpha = 0.96f))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .scale(scale)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(EmeraldAccent.copy(alpha = 0.16f))
                    .border(2.dp, EmeraldAccent, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = EmeraldAccent,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = if (isArabic) "تم تسجيل الدخول بنجاح" else "Authentication Verified",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )

            if (displayName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isArabic) "أهلاً بك، $displayName" else "Welcome, $displayName",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.goldPrimary,
                    textAlign = TextAlign.Center
                )
            }

            if (branchName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = colors.cardSurface,
                    border = BorderStroke(1.dp, colors.cardBorder)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Storefront,
                            contentDescription = null,
                            tint = colors.goldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = branchName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineModeDialog(
    isArabic: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = MiraTheme.colors
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.5.dp, colors.goldPrimary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(22.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(colors.goldPrimary.copy(alpha = 0.14f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = null,
                        tint = colors.goldPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (isArabic) "وضع العمل بدون اتصال (أوفلاين)" else "Offline Working Mode",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isArabic) {
                        "لا يوجد اتصال بالإنترنت حالياً.\nسيتم الدخول باستخدام بياناتك المشفرة محلياً ومزامنة العمليات لاحقاً.\nهل تريد المتابعة؟"
                    } else {
                        "You are currently offline.\nYou will sign in using locally encrypted credentials and sync later.\nDo you want to continue?"
                    },
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (isArabic) "إلغاء" else "Cancel",
                            color = colors.textSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF060B19)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (isArabic) "متابعة" else "Continue",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseBlockedDialog(
    isArabic: Boolean,
    title: String,
    message: String,
    onRetry: () -> Unit,
    onReActivate: () -> Unit
) {
    val colors = MiraTheme.colors
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.cardSurface),
            border = BorderStroke(1.5.dp, ErrorCrimson),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(22.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(ErrorCrimson.copy(alpha = 0.14f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SyncProblem,
                        contentDescription = null,
                        tint = ErrorCrimson,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onReActivate,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.goldPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (isArabic) "إعادة التفعيل" else "Re-Activate",
                            color = colors.goldPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.goldPrimary,
                            contentColor = Color(0xFF060B19)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (isArabic) "إعادة المحاولة" else "Retry",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
