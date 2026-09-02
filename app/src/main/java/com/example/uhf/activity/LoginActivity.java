package com.example.uhf.activity;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.MemoryManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.example.uhf.tools.CertificatePinningManager;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private static final String DEFAULT_SERVER_URL = "https://sa.mirabarcode.com/wp-json";
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;
    private static final String ENCRYPTION_ALGORITHM = "AES";
    private static final String ENCRYPTION_KEY = "MIRA_LOGIN_ENCRYPTION_KEY_v1";

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private TextInputLayout tilServerUrl, tilUsername, tilPassword;
    private MaterialButton btnLogin, btnBiometric, btnSaveServer, btnToggleLanguage;
    private MaterialTextView tvServerSettings, tvAppVersion, tvBrandName, tvBrandSubtitle, tvBrandDescription;
    private MaterialTextView tvSignInTitle, tvSignInSubtitle;
    private ImageView ivAppLogo;
    private MaterialCardView serverConfigCard;
    private ProgressBar pbLoading;
    private View loadingContainer, loginCard, logoContainer;
    private MaterialTextView tvLockStatus;
    
    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private MiraOrgProfileManager orgProfileManager;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService bgExecutor = Executors.newFixedThreadPool(3);
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicInteger failedAttempts = new AtomicInteger(0);
    private long lockoutUntil = 0;

    private SharedPreferences encryptedPrefs;
    private SecretKey encryptionKey;

    private boolean isArabic = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // ✅ 1. منع التقاط الشاشة
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );
        
        isDestroyed.set(false);
        // ✅ تسجيل النشاط في MemoryManager
        MemoryManager.getInstance().registerActivity(this);
        registerComponentCallbacks(MemoryManager.getInstance());
        
        // ✅ 2. تهيئة الواجهة
        setContentView(R.layout.activity_login);
        
        // ✅ 3. تهيئة SessionManager بسياق التطبيق
        sessionManager = new SessionManager(getApplicationContext());

        // ✅ 4. التحقق من تسجيل الدخول مع فحص التوكن
        if (sessionManager.isLoggedIn()) {
            if (sessionManager.hasValidToken()) {
                navigateToMain();
                return;
            } else {
                sessionManager.logoutUser();
                Toast.makeText(this, 
                    isArabic ? "انتهت صلاحية الجلسة - سجل الدخول مجدداً" : 
                    "Session expired - Please sign in again", 
                    Toast.LENGTH_SHORT).show();
            }
        }

        // ✅ 5. فحص Root
        if (isDeviceRooted()) {
            Toast.makeText(this, 
                isArabic ? "⚠️ تحذير: تم اكتشاف صلاحيات Root" : 
                "⚠️ Warning: Root access detected", 
                Toast.LENGTH_LONG).show();
        }

        // ✅ 6. اللغة
        String currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        isArabic = currentLang.isEmpty() || currentLang.startsWith("ar");

        // ✅ 7. تهيئة التشفير
        initializeEncryption();
        
        // ✅ 8. باقي التهيئة - استخدام سياق التطبيق
        orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());
        
        initViews();
        disableAutofill();
        setupBiometric();
        setupServerConfigToggle();
        animateEntrance();
        setupTextWatchers();
        updateUiLanguage(isArabic);
        setupProfileListener();
        updateBiometricButtonState();
        restoreLastUsername();
        checkLockoutStatus();
        
        // ✅ 9. تحميل البيانات من السحابة
        loadOrgProfileFromCloud();
    }

    /**
     * ✅ فحص Root Detection
     */
    private boolean isDeviceRooted() {
        String[] rootPaths = {
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        };
        
        for (String path : rootPaths) {
            if (new File(path).exists()) {
                return true;
            }
        }
        
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }
        
        return false;
    }

    /**
     * تهيئة التشفير الآمن باستخدام EncryptedSharedPreferences
     */
    private void initializeEncryption() {
    try {
        // ✅ استخدام MasterKey مع التحقق من وجوده
        MasterKey masterKey = new MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build();

        encryptedPrefs = EncryptedSharedPreferences.create(
            this,
            "secure_login_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );

        // ✅ استخدام مفتاح ثابت بدلاً من توليد مفتاح جديد
        // هذا يمنع مشكلة "BadPaddingException" عند تحديث التطبيق
        byte[] keyBytes = new byte[32]; // 256-bit key
        byte[] defaultKey = "MIRA_LOGIN_ENCRYPTION_KEY_2024_v1".getBytes("UTF-8");
        System.arraycopy(defaultKey, 0, keyBytes, 0, Math.min(defaultKey.length, keyBytes.length));
        encryptionKey = new SecretKeySpec(keyBytes, ENCRYPTION_ALGORITHM);
        
    } catch (GeneralSecurityException | IOException e) {
        Log.e(TAG, "Encryption initialization failed", e);
        
        // ✅ Fallback آمن - استخدام SharedPreferences العادي
        encryptedPrefs = getSharedPreferences("fallback_login_prefs", MODE_PRIVATE);
        
        // ✅ مفتاح بديل ثابت
        try {
            byte[] keyBytes = "MIRA_FALLBACK_KEY_1234567890".getBytes("UTF-8");
            encryptionKey = new SecretKeySpec(keyBytes, ENCRYPTION_ALGORITHM);
        } catch (Exception ex) {
            Log.e(TAG, "Fallback key creation failed", ex);
            encryptionKey = null;
        }
    }
}

    /**
     * تشفير كلمة المرور قبل الإرسال
     */
    private String encryptPassword(String password) {
    // ✅ التحقق أولاً
    if (encryptionKey == null) {
        Log.w(TAG, "encryptionKey is null, using SHA-256 hash");
        return hashPassword(password);
    }
    
    try {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
        byte[] encryptedBytes = cipher.doFinal(password.getBytes("UTF-8"));
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);
    } catch (Exception e) {
        Log.e(TAG, "Password encryption failed, using SHA-256 hash", e);
        return hashPassword(password);
    }
}

    /**
     * Hash كلمة المرور كطبقة حماية إضافية
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.e(TAG, "Password hashing failed", e);
            return password;
        }
    }

    /**
     * التحقق من صحة URL (يجب أن يكون HTTPS)
     */
    private boolean isValidServerUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        // السماح بـ localhost للتطوير فقط
        boolean isLocalhost = url.contains("10.0.2.2") || url.contains("localhost") || 
                             url.contains("127.0.0.1") || url.startsWith("192.168.");
        
        if (isLocalhost) {
            return url.startsWith("http://") || url.startsWith("https://");
        }
        
        return url.startsWith("https://");
    }

    /**
     * استرجاع آخر اسم مستخدم تم استخدامه
     */
    private void restoreLastUsername() {
        String lastUsername = sessionManager.getLastUsername();
        if (lastUsername != null && !lastUsername.isEmpty() && etUsername != null) {
            etUsername.setText(lastUsername);
            etUsername.setSelection(lastUsername.length());
        }
    }

    /**
     * التحقق من حالة القفل المؤقت
     */
    private void checkLockoutStatus() {
    if (encryptedPrefs == null) {
        Log.w(TAG, "encryptedPrefs is null, skipping lockout check");
        return;
    }
    
    lockoutUntil = encryptedPrefs.getLong("lockout_until", 0);
    int savedAttempts = encryptedPrefs.getInt("failed_attempts", 0);
    failedAttempts.set(savedAttempts);
    
    if (System.currentTimeMillis() < lockoutUntil) {
        long remainingSeconds = (lockoutUntil - System.currentTimeMillis()) / 1000;
        long remainingMinutes = remainingSeconds / 60;
        long remainingSecs = remainingSeconds % 60;
        
        if (tvLockStatus != null) {
            tvLockStatus.setVisibility(View.VISIBLE);
            tvLockStatus.setText(String.format(
                isArabic ? "⛔ تم قفل تسجيل الدخول - حاول بعد %02d:%02d" : 
                "⛔ Login locked - Try in %02d:%02d",
                remainingMinutes, remainingSecs));
        }
        
        if (btnLogin != null) {
            btnLogin.setEnabled(false);
            btnLogin.setAlpha(0.5f);
        }
    } else if (tvLockStatus != null) {
        tvLockStatus.setVisibility(View.GONE);
    }
}

    private void executeSafeBg(Runnable runnable) {
        if (bgExecutor != null && !bgExecutor.isShutdown() && !bgExecutor.isTerminated()) {
            try {
                bgExecutor.execute(runnable);
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Task rejected", e);
                new Thread(runnable).start();
            }
        } else {
            new Thread(runnable).start();
        }
    }

    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed() && !isDestroyed.get();
    }

    private void initViews() {
        etServerUrl = findViewById(R.id.etServerUrl);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        tilServerUrl = findViewById(R.id.tilServerUrl);
        tilUsername = findViewById(R.id.tilUsername);
        tilPassword = findViewById(R.id.tilPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnBiometric = findViewById(R.id.btnBiometric);
        btnSaveServer = findViewById(R.id.btnSaveServer);
        btnToggleLanguage = findViewById(R.id.btnToggleLanguage);
        tvServerSettings = findViewById(R.id.tvServerSettings);
        tvLockStatus = findViewById(R.id.tvLockStatus);
        tvAppVersion = findViewById(R.id.tvAppVersion);
        tvBrandName = findViewById(R.id.tvBrandName);
        tvBrandSubtitle = findViewById(R.id.tvBrandSubtitle);
        tvBrandDescription = findViewById(R.id.tvBrandDescription);
        tvSignInTitle = findViewById(R.id.tvSignInTitle);
        tvSignInSubtitle = findViewById(R.id.tvSignInSubtitle);
        ivAppLogo = findViewById(R.id.ivAppLogo);
        serverConfigCard = findViewById(R.id.serverConfigCard);
        pbLoading = findViewById(R.id.pbLoading);
        loadingContainer = findViewById(R.id.loadingContainer);
        loginCard = findViewById(R.id.loginCard);
        logoContainer = findViewById(R.id.logoContainer);

        // ✅ تحسين: استخدام العنوان الافتراضي إذا لم يوجد
        String savedUrl = sessionManager.getServerUrl();
        if (savedUrl != null && !savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        } else {
            // ✅ استخدام العنوان الافتراضي
            etServerUrl.setText(DEFAULT_SERVER_URL);
            sessionManager.setServerUrl(DEFAULT_SERVER_URL);
            MiraServerConfig.getInstance(this).setBaseUrl(DEFAULT_SERVER_URL);
        }
               
    // ✅ إخفاء إعدادات الخادم من المستخدم
    hideServerConfigFields();

        try {
            tvAppVersion.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " Enterprise");
        } catch (Exception e) {
            tvAppVersion.setText("v1.0.0 Enterprise");
        }

        apiClient = MiraApiClient.getInstance(getApplicationContext());

        btnLogin.setOnClickListener(v -> attemptApiLogin());

        btnSaveServer.setOnClickListener(v -> {
            String url = getText(etServerUrl);
            if (isValidServerUrl(url)) {
                // ✅ تنظيف الرابط
                if (!url.endsWith("/")) {
                    url += "/";
                }
                sessionManager.setServerUrl(url);
                MiraServerConfig.getInstance(this).setBaseUrl(url);
                
                // ✅ إعادة تهيئة API Client
                MiraApiClient.reloadInstance(getApplicationContext());
apiClient = MiraApiClient.getInstance(getApplicationContext());
                
                Toast.makeText(this, isArabic ? "✓ تم حفظ رابط الخادم بنجاح" : "✓ Server URL saved", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, 
                    isArabic ? "⚠️ يجب استخدام HTTPS للاتصال الآمن" : "⚠️ HTTPS required for secure connection", 
                    Toast.LENGTH_LONG).show();
            }
        });

        if (btnToggleLanguage != null) {
            btnToggleLanguage.setOnClickListener(v -> {
                isArabic = !isArabic;
                String langTag = isArabic ? "ar" : "en";
                AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(langTag));
                
                finish();
                startActivity(getIntent());
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            });
        }

        applyOrgProfile();
    }
    
    private void hideServerConfigFields() {
    if (tilServerUrl != null) tilServerUrl.setVisibility(View.GONE);
    if (etServerUrl != null) etServerUrl.setVisibility(View.GONE);
    if (tvServerSettings != null) tvServerSettings.setVisibility(View.GONE);
    if (serverConfigCard != null) serverConfigCard.setVisibility(View.GONE);
    if (btnSaveServer != null) btnSaveServer.setVisibility(View.GONE);
}

    private void disableAutofill() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (etUsername != null) etUsername.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            if (etPassword != null) etPassword.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            if (etServerUrl != null) etServerUrl.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
    }

    private void setupProfileListener() {
        if (orgProfileManager == null) return;
        
        profileListener = () -> {
            if (isActivityAlive()) {
                mainHandler.post(this::applyOrgProfile);
            }
        };
        
        orgProfileManager.addListener(profileListener);
    }

    private void loadOrgProfileFromCloud() {
    if (apiClient == null) {
        Log.w(TAG, "apiClient is null, skipping cloud profile load");
        return;
    }
    
    executeSafeBg(() -> {
        MiraApiClient.ApiResponse response = apiClient.getOrgProfile();
        
        if (response != null && response.isSuccess && response.data != null) {
            JSONObject org = response.data.optJSONObject("organization");
            
            if (org != null && orgProfileManager != null) {
                orgProfileManager.saveFromServer(org);
            }
            
            if (isActivityAlive()) {
                mainHandler.post(this::applyOrgProfile);
            }
        }
    });
}

    private void updateUiLanguage(boolean arabic) {
        getWindow().getDecorView().setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        if (arabic) {
            if (tvSignInTitle != null) tvSignInTitle.setText("تسجيل الدخول");
            if (tvSignInSubtitle != null) tvSignInSubtitle.setText("أدخل بيانات الاعتماد الخاصة بك للمتابعة");
            if (tilUsername != null) tilUsername.setHint("اسم المستخدم");
            if (tilPassword != null) tilPassword.setHint("كلمة المرور");
            if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText("دخول النظام");
            if (tvServerSettings != null) tvServerSettings.setText("🔧 إعدادات الخادم والاتصال (Server Settings)");
            if (btnToggleLanguage != null) btnToggleLanguage.setText("EN / AR");
        } else {
            if (tvSignInTitle != null) tvSignInTitle.setText("Sign In");
            if (tvSignInSubtitle != null) tvSignInSubtitle.setText("Enter your credentials to continue");
            if (tilUsername != null) tilUsername.setHint("Username");
            if (tilPassword != null) tilPassword.setHint("Password");
            if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText("Sign In");
            if (tvServerSettings != null) tvServerSettings.setText("🔧 Server Configuration");
            if (btnToggleLanguage != null) btnToggleLanguage.setText("AR / EN");
        }
        applyOrgProfile();
    }
private void applyOrgProfile() {
    if (!isActivityAlive()) return;

    if (orgProfileManager == null) {
        orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());
    }
        
        if (tvBrandName != null) {
            if (isArabic) {
                String nameAr = orgProfileManager.getOrgNameAr();
                tvBrandName.setText(nameAr != null && !nameAr.isEmpty() ? nameAr : "MIRA Jewelry");
            } else {
                String nameEn = orgProfileManager.getOrgNameEn();
                tvBrandName.setText(nameEn != null && !nameEn.isEmpty() ? nameEn : "MIRA Jewelry");
            }
        }
        
        if (tvBrandSubtitle != null) {
            tvBrandSubtitle.setText("MIRA ID™ Smart Jewelry Tag");
        }
        
        if (tvBrandDescription != null) {
            if (isArabic) {
                tvBrandDescription.setText("منظومة التتبع والمصادقة المعتمدة للمجوهرات");
            } else {
                tvBrandDescription.setText("Enterprise Jewelry Traceability & Authentication");
            }
        }
        
        if (ivAppLogo != null) {
            String logoBase64 = orgProfileManager.getOrgLogo();
            if (logoBase64 != null && !logoBase64.isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(logoBase64, Base64.DEFAULT);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    ivAppLogo.setImageBitmap(bitmap);
                } catch (Exception e) {
                    ivAppLogo.setImageResource(R.drawable.ic_luxury_mira_logo);
                }
            } else {
                ivAppLogo.setImageResource(R.drawable.ic_luxury_mira_logo);
            }
        }
    }

    private void setupServerConfigToggle() {
        if (tvServerSettings != null) {
            tvServerSettings.setOnClickListener(v -> {
                if (serverConfigCard.getVisibility() == View.GONE) {
                    serverConfigCard.setVisibility(View.VISIBLE);
                    serverConfigCard.setAlpha(0f);
                    serverConfigCard.animate().alpha(1f).setDuration(300).start();
                    tvServerSettings.setText(isArabic ? "🔧 إخفاء إعدادات الخادم" : "🔧 Hide Server Settings");
                } else {
                    serverConfigCard.animate().alpha(0f).setDuration(200).withEndAction(() -> serverConfigCard.setVisibility(View.GONE)).start();
                    tvServerSettings.setText(isArabic ? "🔧 إعدادات الخادم والاتصال (Server Settings)" : "🔧 Server Configuration");
                }
            });
        }
    }

    private void animateEntrance() {
        if (loginCard == null) return;
        loginCard.setAlpha(0f);
        loginCard.setTranslationY(60f);
        loginCard.animate().alpha(1f).translationY(0f).setDuration(600)
            .setInterpolator(new AccelerateDecelerateInterpolator()).setStartDelay(150).start();
        if (logoContainer != null) {
            logoContainer.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in_up));
        }
    }

    private void setupTextWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { 
                updateLoginButtonState();
                clearFieldError(s);
            }
        };
        if (etUsername != null) etUsername.addTextChangedListener(watcher);
        if (etPassword != null) etPassword.addTextChangedListener(watcher);
        if (etServerUrl != null) etServerUrl.addTextChangedListener(watcher);
    }

    private void clearFieldError(Editable s) {
        if (s == etUsername.getText() && tilUsername != null) {
            tilUsername.setError(null);
        } else if (s == etPassword.getText() && tilPassword != null) {
            tilPassword.setError(null);
        } else if (s == etServerUrl.getText() && tilServerUrl != null) {
            tilServerUrl.setError(null);
        }
    }

    private void updateLoginButtonState() {
        String username = getText(etUsername);
        String password = getText(etPassword);
        boolean valid = !username.isEmpty() && password.length() >= 4;
        boolean isLocked = System.currentTimeMillis() < lockoutUntil;
        
        if (btnLogin != null) {
            btnLogin.setEnabled(valid && !isLocked);
            btnLogin.setAlpha(valid && !isLocked ? 1f : 0.5f);
        }
    }

    private String getText(TextInputEditText editText) {
        return (editText != null && editText.getText() != null) ? editText.getText().toString().trim() : "";
    }

    private void playTone(boolean isSuccess) {
        try {
            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            toneGen.startTone(isSuccess ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_SUP_ERROR, 150);
        } catch (Exception e) {
            Log.e(TAG, "Tone error: " + e.getMessage());
        }
    }

    /**
     * الحصول على رسالة خطأ مناسبة حسب رمز الخطأ
     */
    private String getErrorMessage(int errorCode, String fallbackMessage) {
        switch (errorCode) {
            case 400:
                return isArabic ? "بيانات غير صالحة - تحقق من المدخلات" : "Invalid data - Check your inputs";
            case 401:
                return isArabic ? "بيانات الدخول غير صحيحة" : "Invalid credentials";
            case 403:
                return isArabic ? "ليس لديك صلاحية للدخول" : "Access denied";
            case 404:
                return isArabic ? "الخادم غير موجود - تحقق من الرابط" : "Server not found - Check URL";
            case 408:
            case 504:
                return isArabic ? "انتهت مهلة الاتصال - حاول مرة أخرى" : "Connection timeout - Try again";
            case 429:
                return isArabic ? "محاولات كثيرة - انتظر قليلاً" : "Too many attempts - Please wait";
            case 500:
                return isArabic ? "خطأ في الخادم - حاول لاحقاً" : "Server error - Try later";
            case 503:
                return isArabic ? "الخدمة غير متاحة حالياً" : "Service unavailable";
            default:
                return fallbackMessage != null ? fallbackMessage : 
                    (isArabic ? "خطأ في الاتصال - حاول مرة أخرى" : "Connection error - Try again");
        }
    }

    /**
     * تسجيل محاولة فاشلة وفحص القفل
     */
    private void recordFailedAttempt() {
        int attempts = failedAttempts.incrementAndGet();
        
        if (encryptedPrefs != null) {
            encryptedPrefs.edit().putInt("failed_attempts", attempts).apply();
        }
        
        if (attempts >= MAX_LOGIN_ATTEMPTS) {
            lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS;
            if (encryptedPrefs != null) {
                encryptedPrefs.edit()
                    .putLong("lockout_until", lockoutUntil)
                    .putInt("failed_attempts", 0)
                    .apply();
            }
            failedAttempts.set(0);
            
            mainHandler.post(() -> {
                if (btnLogin != null) {
                    btnLogin.setEnabled(false);
                    btnLogin.setText(isArabic ? "محظور لمدة 5 دقائق" : "Locked for 5 min");
                }
                Toast.makeText(this, 
                    isArabic ? "⛔ تم قفل تسجيل الدخول لمدة 5 دقائق بسبب محاولات كثيرة" : 
                    "⛔ Login locked for 5 minutes due to too many attempts", 
                    Toast.LENGTH_LONG).show();
                
                // جدولة فتح القفل
                mainHandler.postDelayed(() -> {
                    if (isActivityAlive() && btnLogin != null) {
                        btnLogin.setEnabled(true);
                        updateLoginButtonState();
                        Toast.makeText(this, 
                            isArabic ? "✓ تم فتح تسجيل الدخول - يمكنك المحاولة الآن" : 
                            "✓ Login unlocked - You can try now", 
                            Toast.LENGTH_SHORT).show();
                    }
                }, LOCKOUT_DURATION_MS);
            });
        }
    }

    /**
     * إعادة تعيين عداد المحاولات الفاشلة
     */
    private void resetFailedAttempts() {
        failedAttempts.set(0);
        lockoutUntil = 0;
        if (encryptedPrefs != null) {
            encryptedPrefs.edit()
                .putInt("failed_attempts", 0)
                .putLong("lockout_until", 0)
                .apply();
        }
    }

    private void attemptApiLogin() {
        // التحقق من القفل
        if (System.currentTimeMillis() < lockoutUntil) {
            long remainingMinutes = (lockoutUntil - System.currentTimeMillis()) / (60 * 1000);
            Toast.makeText(this, 
                String.format(isArabic ? "⛔ محظور - حاول بعد %d دقيقة" : "⛔ Locked - Try in %d min", 
                    Math.max(1, remainingMinutes)), 
                Toast.LENGTH_LONG).show();
            return;
        }

        String username = getText(etUsername);
        String password = getText(etPassword);
        String serverUrl = getText(etServerUrl);

        // ✅ إذا كان الرابط فارغاً، استخدم الافتراضي
        if (serverUrl.isEmpty()) {
            serverUrl = sessionManager.getServerUrl();
            if (serverUrl.isEmpty()) {
                serverUrl = DEFAULT_SERVER_URL;
            }
            etServerUrl.setText(serverUrl);
        }

        // التحقق من صحة URL
        if (!isValidServerUrl(serverUrl)) {
            tilServerUrl.setError(isArabic ? "يجب استخدام HTTPS" : "HTTPS required");
            shakeView(tilServerUrl);
            playTone(false);
            return;
        }

        // ✅ حفظ الرابط قبل المحاولة
        sessionManager.setServerUrl(serverUrl);
        MiraServerConfig.getInstance(this).setBaseUrl(serverUrl);

        if (username.isEmpty()) {
            tilUsername.setError(isArabic ? "اسم المستخدم مطلوب" : "Username required");
            shakeView(tilUsername);
            playTone(false);
            return;
        }
        if (password.length() < 4) {
            tilPassword.setError(isArabic ? "كلمة المرور قصيرة جداً" : "Password too short");
            shakeView(tilPassword);
            playTone(false);
            return;
        }

        tilUsername.setError(null);
        tilPassword.setError(null);
        tilServerUrl.setError(null);
        showLoading(true);

        // ✅ إعادة تهيئة API Client بالرابط الجديد
        MiraApiClient.reloadInstance(getApplicationContext());
apiClient = MiraApiClient.getInstance(getApplicationContext());

        // تشفير كلمة المرور قبل الإرسال
        // ✅ إرسال كلمة المرور الأصلية - HTTPS يقوم بالتشفير
        final String finalPassword = password;

        executeSafeBg(() -> {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            MiraApiClient.ApiResponse response = apiClient.login(username, finalPassword, deviceId);
            
            mainHandler.post(() -> {
                if (!isActivityAlive()) return;
                showLoading(false);
                
                if (response != null && response.isSuccess) {
                    // إعادة تعيين عداد المحاولات الفاشلة عند النجاح
                    resetFailedAttempts();
                    
                    // حفظ اسم المستخدم للدخول المستقبلي
                    sessionManager.saveLastUsername(username);
                    
                    // التحقق من حالة المستخدم
                    String userStatus = "active";
                    if (response.data != null) {
                        userStatus = response.data.optString("user_status", "active");
                    }
                    
                    if ("suspended".equals(userStatus)) {
                        playTone(false);
                        Toast.makeText(LoginActivity.this, 
                            isArabic ? "⛔ حسابك موقوف - تواصل مع الإدارة" : "⛔ Account suspended - Contact admin",
                            Toast.LENGTH_LONG).show();
                        return;
                    }
                    
                    if ("pending".equals(userStatus)) {
                        playTone(false);
                        Toast.makeText(LoginActivity.this, 
                            isArabic ? "⏳ حسابك قيد المراجعة - انتظر الموافقة" : "⏳ Account pending approval",
                            Toast.LENGTH_LONG).show();
                        return;
                    }
                    
                    handleLoginSuccess(response, username, password);
                } else {
                    // تسجيل محاولة فاشلة
                    recordFailedAttempt();
                    
                    playTone(false);
                    int errorCode = response != null ? response.code : 0;
                    String errorMsg;
                    
                    if (response != null && response.data != null) {
                        String serverMessage = response.data.optString("message", null);
                        errorMsg = getErrorMessage(errorCode, serverMessage);
                    } else {
                        errorMsg = getErrorMessage(errorCode, 
                            isArabic ? "فشل الاتصال بالخادم" : "Connection failed");
                    }
                    
                    Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    shakeView(loginCard);
                }
            });
        });
    }

   private void handleLoginSuccess(MiraApiClient.ApiResponse response, String username, String password) {
    playTone(true);

    // ✅ استخدام سياق التطبيق لجميع الـ Managers
    Context appContext = getApplicationContext();
    
    String token = response.optString("token", "");
    String roleStr = response.optString("role", "ADMIN");
    String displayName = response.optString("display_name", username);
    int userId = response.optInt("user_id", 0);
    int branchId = response.optInt("branch_id", 1);
    String branchName = response.optString("branch_name", "");
    String branchCode = response.optString("branch_code", "");
    String serverUrl = getText(etServerUrl);
    
    if (serverUrl.isEmpty()) {
        serverUrl = sessionManager.getServerUrl();
    }
    
    String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    String apiKey = response.optString("api_key", "");
    String gateId = response.optString("gate_id", "");
    
    // ✅ حفظ البيانات في SessionManager فقط (مصدر واحد للحقيقة)
    if (!apiKey.isEmpty()) {
        String effectiveGateId = !gateId.isEmpty() ? gateId : deviceId;
        sessionManager.saveApiKey(apiKey);
        sessionManager.saveGateId(effectiveGateId);
        // ❌ لا نستخدم MiraServerConfig.saveSession بعد الآن
        // MiraServerConfig.getInstance(this).saveSession(apiKey, effectiveGateId, token);
    }
    
    if (!serverUrl.isEmpty()) {
        sessionManager.setServerUrl(serverUrl);
        // ✅ تحديث MiraServerConfig للتوافق
        MiraServerConfig.getInstance(appContext).setBaseUrl(serverUrl);
    }
    
    String permissionsJson = response.optString("permissions", "");
    if (!permissionsJson.isEmpty()) {
        PermissionManager pm = PermissionManager.getInstance(appContext);
        pm.savePermissions(permissionsJson);
    }

    // ✅ حفظ بيانات الجلسة
    sessionManager.createLoginSession(username, roleStr, token, serverUrl);
    sessionManager.setDisplayName(displayName);
    sessionManager.setUserId(userId);
    sessionManager.saveBiometricUser(username, roleStr, displayName, userId, token);
    sessionManager.saveBiometricBranch(branchId, branchName);
    
    boolean biometricEnabled = true;
    if (response.data != null) {
        biometricEnabled = response.data.optBoolean("biometric_enabled", true);
    }
    sessionManager.setBiometricLoginEnabled(biometricEnabled);

    // ✅ استخدام سياق التطبيق لـ MiraBranchesManager
    MiraBranchesManager bm = MiraBranchesManager.getInstance(appContext);
    bm.setActiveBranch(branchId, branchName, branchCode, "", "");
    bm.setUserBranchLocked(true);

    // ✅ الانتقال إلى النشاط الرئيسي
    navigateToMain();
}

/**
 * ✅ عرض شاشة نجاح جميلة
 */
private void showSuccessOverlay(String displayName) {
    View successOverlay = findViewById(R.id.successOverlay);
    MaterialTextView tvSuccessMessage = findViewById(R.id.tvSuccessMessage);
    
    if (successOverlay != null && tvSuccessMessage != null) {
        String welcomeMessage = isArabic ? 
            "أهلاً بك " + displayName + " 👋" : 
            "Welcome " + displayName + " 👋";
        tvSuccessMessage.setText(welcomeMessage);
        
        successOverlay.setVisibility(View.VISIBLE);
        successOverlay.setAlpha(0f);
        successOverlay.animate()
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .start();
    }
}

/**
 * ✅ الانتقال مع أنيميشن سلس
 */
private void navigateToMainWithAnimation() {
    Intent intent = new Intent(this, UHFMainActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    intent.putExtra("fresh_start", true);
    startActivity(intent);
    
    // ✅ أنيميشن انتقال مخصص
    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    finish();
}
    private void restoreAllDataAfterLogin(Runnable onComplete) {
        executeSafeBg(() -> {
            try {
                MiraApiClient.ApiResponse goldResponse = apiClient.getGoldCurrencySettings();
                if (goldResponse != null && goldResponse.isSuccess && goldResponse.data != null) {
                    MiraPriceManager priceManager = MiraPriceManager.getInstance(this);
                    JSONObject data = goldResponse.data.optJSONObject("data");
                    if (data == null) data = goldResponse.data;
                    JSONObject goldData = data.optJSONObject("gold");
                    JSONObject currencyData = data.optJSONObject("currency");
                    if (goldData != null) priceManager.saveGoldSettings(goldData);
                    if (currencyData != null) priceManager.saveCurrencySettings(currencyData);
                }

                MiraApiClient.ApiResponse branchesResponse = apiClient.getBranches();
                if (branchesResponse != null && branchesResponse.isSuccess && branchesResponse.data != null) {
                    MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(this);
                    branchesManager.saveBranchesFromServer(branchesResponse.data);
                }

                MiraApiClient.ApiResponse orgResponse = apiClient.getOrgProfile();
                if (orgResponse != null && orgResponse.isSuccess && orgResponse.data != null) {
                    JSONObject org = orgResponse.data.optJSONObject("organization");
                    if (org != null && orgProfileManager != null) {
                        orgProfileManager.saveFromServer(org);
                        if (isActivityAlive()) {
                            mainHandler.post(this::applyOrgProfile);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Restore data error: " + e.getMessage());
            }

            if (isActivityAlive() && onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
    }

    /**
     * إعداد البصمة مع دعم PIN/Pattern/Password
     */
    private void setupBiometric() {
        boolean biometricEnabled = sessionManager.isBiometricLoginEnabled();
        
        if (!biometricEnabled) {
            if (btnBiometric != null) btnBiometric.setVisibility(View.GONE);
            return;
        }
        
        BiometricManager bm = BiometricManager.from(this);
        int canAuthenticate = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG | 
            BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            if (btnBiometric != null) btnBiometric.setVisibility(View.GONE);
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                String lastUser = sessionManager.getLastBiometricUser();
                String lastRole = sessionManager.getLastBiometricRole();
                String lastDisplayName = sessionManager.getLastBiometricDisplayName();
                int lastUserId = sessionManager.getLastBiometricUserId();
                String lastToken = sessionManager.getLastBiometricToken();

                if (!lastUser.isEmpty()) {
                    if (!sessionManager.isLoggedIn()) {
                        sessionManager.createLoginSession(lastUser, lastRole,
                            lastToken.isEmpty() ? "BIOMETRIC_TOKEN" : lastToken,
                            sessionManager.getServerUrl());
                        sessionManager.setDisplayName(lastDisplayName);
                        sessionManager.setUserId(lastUserId);

                     int savedBranchId = sessionManager.getLastBiometricBranchId();
String savedBranchName = sessionManager.getLastBiometricBranchName();
if (savedBranchId > 0) {
    MiraBranchesManager bm = MiraBranchesManager.getInstance(getApplicationContext());
    bm.setActiveBranch(savedBranchId, savedBranchName, "", "", "");
    bm.setUserBranchLocked(true);
}
                    }
                    
                    // إعادة تعيين عداد المحاولات عند نجاح البصمة
                    resetFailedAttempts();
                    
                    playTone(true);
                    String nameToShow = !lastDisplayName.isEmpty() ? lastDisplayName : lastUser;
                    Toast.makeText(LoginActivity.this,
                        (isArabic ? "أهلاً بعودتك " : "Welcome back ") + nameToShow + " 👋",
                        Toast.LENGTH_SHORT).show();
                    navigateToMain();
                } else {
                    playTone(false);
                    Toast.makeText(LoginActivity.this,
                        isArabic ? "قم بتسجيل الدخول بكلمة المرور أولاً" : "Sign in with password first",
                        Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && 
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    playTone(false);
                    Toast.makeText(LoginActivity.this, "Error: " + errString, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onAuthenticationFailed() {
                playTone(false);
                Toast.makeText(LoginActivity.this,
                    isArabic ? "لم يتم التعرف على بصمة الإصبع" : "Fingerprint not recognized",
                    Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
    .setTitle(isArabic ? "تسجيل الدخول الحيوي" : "Biometric Sign In")
    .setSubtitle(isArabic ? "استخدم بصمة الإصبع" : "Use fingerprint")
    .setNegativeButtonText(isArabic ? "استخدام كلمة المرور" : "Use password")
    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    .build();

        if (btnBiometric != null) {
            btnBiometric.setOnClickListener(v -> {
                if (!sessionManager.isBiometricLoginEnabled()) {
                    Toast.makeText(this,
                        isArabic ? "البصمة معطلة - فعلها من الإعدادات" : "Biometric disabled",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                
                if (!sessionManager.getLastBiometricUser().isEmpty() || sessionManager.isLoggedIn()) {
                    biometricPrompt.authenticate(promptInfo);
                } else {
                    playTone(false);
                    Toast.makeText(this,
                        isArabic ? "سجل الدخول بكلمة المرور أولاً لتفعيل البصمة" : "Sign in with password first",
                        Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /**
     * تحديث حالة زر البصمة
     */
    private void updateBiometricButtonState() {
        if (btnBiometric != null) {
            boolean biometricEnabled = sessionManager.isBiometricLoginEnabled();
            boolean hasBiometricUser = !sessionManager.getLastBiometricUser().isEmpty();
            btnBiometric.setVisibility(biometricEnabled && hasBiometricUser ? View.VISIBLE : View.GONE);
        }
    }

    private void showLoading(boolean loading) {
        if (loadingContainer != null) loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null) {
            btnLogin.setEnabled(!loading);
            btnLogin.setText(loading ? "" : (isArabic ? "دخول النظام" : "Sign In"));
        }
        if (btnBiometric != null) btnBiometric.setEnabled(!loading);
        if (etUsername != null) etUsername.setEnabled(!loading);
        if (etPassword != null) etPassword.setEnabled(!loading);
    }

    private void shakeView(View view) {
        if (view == null) return;
        ObjectAnimator.ofFloat(view, "translationX", 0, 20, -20, 20, -20, 10, -10, 5, -5, 0).setDuration(500).start();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, UHFMainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("fresh_start", true);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyOrgProfile();
        updateBiometricButtonState();
        checkLockoutStatus();
    }

    @Override
protected void onDestroy() {
    // ✅ إلغاء التسجيل والتنظيف
    MemoryManager.getInstance().unregisterActivity(this);
    unregisterComponentCallbacks(MemoryManager.getInstance());
    // ✅ تم حذف forceCleanup() لمنع مسح البيانات المهمة
    
    super.onDestroy();
    isDestroyed.set(true);
    
    if (orgProfileManager != null && profileListener != null) {
        orgProfileManager.removeListener(profileListener);
    }
    
    mainHandler.removeCallbacksAndMessages(null);
}
