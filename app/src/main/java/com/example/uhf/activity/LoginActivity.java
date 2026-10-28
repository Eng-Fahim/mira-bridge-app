package com.example.uhf.activity;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.MiraRfid.sound.MiraSoundManager;
import com.example.uhf.tools.MemoryManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private static final String DEFAULT_SERVER_URL = "https://id.ibreg.org/wp-json";
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000;
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final int HASH_KEY_LENGTH = 256;

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private TextInputLayout tilServerUrl, tilUsername, tilPassword;
    private MaterialButton btnLogin, btnBiometric, btnSaveServer, btnToggleLanguage;
    private MaterialTextView tvServerSettings, tvAppVersion, tvBrandName, tvBrandSubtitle, tvBrandDescription;
    private MaterialTextView tvSignInTitle, tvSignInSubtitle, tvSuccessMessage, tvLockStatus;
    private ImageView ivAppLogo;
    private MaterialCardView serverConfigCard;
    private ProgressBar pbLoading;
    private View loadingContainer, loginCard, logoContainer, successOverlay;

    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private MiraOrgProfileManager orgProfileManager;
    private MiraSoundManager soundManager;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static ExecutorService bgExecutor;
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicInteger failedAttempts = new AtomicInteger(0);
    private long lockoutUntil = 0;

    private SharedPreferences encryptedPrefs;
    private boolean isArabic = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate started");

        // ═══════════════════════════════════════════════════════════════
        // 1. حماية الشاشة من الالتقاط
        // ═══════════════════════════════════════════════════════════════
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );

        isDestroyed.set(false);
        initExecutor();

        MemoryManager.getInstance().registerActivity(this);
        registerComponentCallbacks(MemoryManager.getInstance());

        setContentView(R.layout.activity_login);

        // ═══════════════════════════════════════════════════════════════
        // 2. اللغة الحالية
        // ═══════════════════════════════════════════════════════════════
        String currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        isArabic = currentLang.isEmpty() || currentLang.startsWith("ar");

        // ═══════════════════════════════════════════════════════════════
        // ⚠️ 3. التهيئة الأساسية — يجب أن تكون قبل أي فحص يستخدمها
        // ═══════════════════════════════════════════════════════════════
        sessionManager = new SessionManager(getApplicationContext());
soundManager = MiraSoundManager.getInstance(getApplicationContext());

// ═══════════════════════════════════════════════════════
//  ⚡ MIRA License — فحص سلامة الترخيص
// ═══════════════════════════════════════════════════════
if (!checkLicenseIntegrity()) {
    Log.w(TAG, "🚫 License integrity check failed — blocking app");
    // Dialog يُظهر + ينهي النشاط
    return;
}

        // ═══════════════════════════════════════════════════════════════
        // 4. فحص الترخيص (MIRA License Center) — قبل أي شيء آخر
        // ═══════════════════════════════════════════════════════════════
        if (!sessionManager.hasActivationToken()) {
            Log.w(TAG, "⚠️ No activation token — redirecting to ActivationActivity");
            Intent intent = new Intent(this, ActivationActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // ✅ فحص انتهاء فترة السماح دون اتصال
        String offlineStatus = sessionManager.getOfflineStatus();
        if ("BLOCKED".equals(offlineStatus)) {
            long days = sessionManager.getDaysSinceLastHeartbeat();
            Log.w(TAG, "🚫 License BLOCKED — offline for " + days + " days");
            new AlertDialog.Builder(this)
                    .setTitle(isArabic ? "🔴 الترخيص يحتاج اتصالاً" : "🔴 License Needs Connection")
                    .setMessage(isArabic
                            ? "انقطع الاتصال بالخادم لأكثر من " + sessionManager.getOfflineGraceDays()
                                + " أيام.\n\nيرجى الاتصال بالإنترنت لتجديد الترخيص.\n\nقد يكون هذا بسبب تغيير في الجهاز أو محاولة تلاعب."
                            : "Offline for more than " + sessionManager.getOfflineGraceDays()
                                + " days.\n\nPlease connect to internet to renew license.")
                    .setCancelable(false)
                    .setPositiveButton(isArabic ? "إعادة المحاولة" : "Retry", (d, w) -> recreate())
                    .setNeutralButton(isArabic ? "إعادة التفعيل" : "Re-Activate", (d, w) -> {
                        sessionManager.clearLicense();
                        startActivity(new Intent(this, ActivationActivity.class));
                        finish();
                    })
                    .show();
            return;
        }

        if ("WARNING".equals(offlineStatus) || "RESTRICTED".equals(offlineStatus)) {
            long daysLeft = sessionManager.getOfflineGraceDays() - sessionManager.getDaysSinceLastHeartbeat();
            Log.w(TAG, "⚠️ License warning — " + daysLeft + " days left offline");
            Toast.makeText(this,
                    isArabic ? "⚠️ تحذير: انقطع الاتصال بـ " + daysLeft + " أيام. اتصل بالإنترنت."
                            : "⚠️ Warning: offline for " + daysLeft + " days. Please connect.",
                    Toast.LENGTH_LONG).show();
        }

        // ═══════════════════════════════════════════════════════════════
        // 5. التحقق المباشر من الجلسة (منطق قديم — لم يُمس)
        // ═══════════════════════════════════════════════════════════════
        if (sessionManager.isLoggedIn()) {
            if (sessionManager.hasValidToken()) {
                navigateToMainDirect();
                return;
            } else {
                sessionManager.logoutUser();
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 6. معالجة انتهاء الجلسة إن وجدت
        // ═══════════════════════════════════════════════════════════════
        if (getIntent() != null && getIntent().getBooleanExtra("session_expired", false)) {
            Toast.makeText(this,
                    isArabic ? "انتهت صلاحية الجلسة - سجل الدخول مجدداً" : "Session expired - Please sign in again",
                    Toast.LENGTH_LONG).show();
            if (soundManager != null) {
                soundManager.play(MiraSoundManager.SoundEvent.LOGOUT);
            }
        }

        orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());

        // ═══════════════════════════════════════════════════════════════
        // 7. تهيئة الواجهة والأحداث
        // ═══════════════════════════════════════════════════════════════
        initViews();
        setupTextWatchers();
        setupServerConfigToggle();
        setupLanguageToggle();

        // ═══════════════════════════════════════════════════════════════
        // 8. تهيئة التشفير في الخلفية
        // ═══════════════════════════════════════════════════════════════
        initializeEncryption();

        // ═══════════════════════════════════════════════════════════════
        // 9. تحريك الواجهة وتحميل البيانات
        // ═══════════════════════════════════════════════════════════════
        animateEntrance();
        setupProfileListener();

        mainHandler.postDelayed(() -> {
            if (isActivityAlive()) {
                restoreLastUsername();
                loadOrgProfileFromCloud();
            }
        }, 300);

        Log.d(TAG, "onCreate completed");
    }

    private synchronized static void initExecutor() {
        if (bgExecutor == null || bgExecutor.isShutdown() || bgExecutor.isTerminated()) {
            bgExecutor = Executors.newFixedThreadPool(3);
        }
    }

    private void executeSafeBg(Runnable runnable) {
        initExecutor();
        try {
            bgExecutor.execute(runnable);
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Task rejected, using fallback thread", e);
            new Thread(runnable).start();
        }
    }

    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed() && !isDestroyed.get();
    }

    private void initializeEncryption() {
        executeSafeBg(() -> {
            try {
                MasterKey masterKey = new MasterKey.Builder(this)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                encryptedPrefs = EncryptedSharedPreferences.create(
                        this,
                        "secure_login_prefs_v2",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                Log.d(TAG, "Encryption initialized successfully");
            } catch (GeneralSecurityException | IOException e) {
                Log.e(TAG, "EncryptedSharedPreferences failed, using private fallback", e);
                encryptedPrefs = getSharedPreferences("fallback_login_prefs_v2", MODE_PRIVATE);
            }

            mainHandler.post(() -> {
                if (isActivityAlive()) {
                    checkLockoutStatus();
                    setupBiometric();
                    updateBiometricButtonState();
                }
            });
        });
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
        tvSuccessMessage = findViewById(R.id.tvSuccessMessage);
        ivAppLogo = findViewById(R.id.ivAppLogo);
        serverConfigCard = findViewById(R.id.serverConfigCard);
        pbLoading = findViewById(R.id.pbLoading);
        loadingContainer = findViewById(R.id.loadingContainer);
        loginCard = findViewById(R.id.loginCard);
        logoContainer = findViewById(R.id.logoContainer);
        successOverlay = findViewById(R.id.successOverlay);

        String savedUrl = sessionManager.getServerUrl();
        if (savedUrl != null && !savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        } else {
            etServerUrl.setText(DEFAULT_SERVER_URL);
            sessionManager.setServerUrl(DEFAULT_SERVER_URL);
            MiraServerConfig.getInstance(this).setBaseUrl(DEFAULT_SERVER_URL);
        }

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
                if (!url.endsWith("/")) url += "/";
                sessionManager.setServerUrl(url);
                MiraServerConfig.getInstance(this).setBaseUrl(url);

                MiraApiClient client = MiraApiClient.getInstance(getApplicationContext());
                if (client != null) {
                    client.refreshUrl();
                    client.invalidateTokenCache();
                }
                apiClient = client;

                Log.d(TAG, "✅ Server URL saved, using same instance: " + url);

                Toast.makeText(this, isArabic ? "✓ تم حفظ رابط الخادم بنجاح" : "✓ Server URL saved", Toast.LENGTH_SHORT).show();
                serverConfigCard.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> serverConfigCard.setVisibility(View.GONE)).start();
            } else {
                Toast.makeText(this,
                        isArabic ? "⚠️ يجب استخدام HTTPS للاتصال الآمن" : "⚠️ HTTPS required for secure connection",
                        Toast.LENGTH_LONG).show();
            }
        });

        updateUiTexts();
        applyOrgProfile();
    }

    private void hideServerConfigFields() {
        if (tilServerUrl != null) tilServerUrl.setVisibility(View.GONE);
        if (etServerUrl != null) etServerUrl.setVisibility(View.GONE);
        if (serverConfigCard != null) serverConfigCard.setVisibility(View.GONE);
        if (btnSaveServer != null) btnSaveServer.setVisibility(View.GONE);
        if (tvServerSettings != null) tvServerSettings.setVisibility(View.VISIBLE);
    }

    private void setupServerConfigToggle() {
        if (tvServerSettings != null) {
            tvServerSettings.setOnClickListener(v -> {
                if (serverConfigCard.getVisibility() == View.GONE) {
                    if (tilServerUrl != null) tilServerUrl.setVisibility(View.VISIBLE);
                    if (etServerUrl != null) etServerUrl.setVisibility(View.VISIBLE);
                    if (btnSaveServer != null) btnSaveServer.setVisibility(View.VISIBLE);

                    serverConfigCard.setVisibility(View.VISIBLE);
                    serverConfigCard.setAlpha(0f);
                    serverConfigCard.animate().alpha(1f).setDuration(300).start();
                } else {
                    serverConfigCard.animate().alpha(0f).setDuration(200)
                            .withEndAction(() -> serverConfigCard.setVisibility(View.GONE)).start();
                }
            });
        }
    }

    private void setupLanguageToggle() {
        if (btnToggleLanguage != null) {
            btnToggleLanguage.setOnClickListener(v -> {
                isArabic = !isArabic;
                String langTag = isArabic ? "ar" : "en";
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langTag));
                recreate();
            });
        }
    }

    private void updateUiTexts() {
        getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        if (tvSignInTitle != null) tvSignInTitle.setText(isArabic ? "مرحباً بعودتك" : "Welcome Back");
        if (tvSignInSubtitle != null) tvSignInSubtitle.setText(isArabic ? "قم بتسجيل الدخول للمتابعة" : "Sign in to continue");
        if (tilUsername != null) tilUsername.setHint(isArabic ? "اسم المستخدم" : "Username");
        if (tilPassword != null) tilPassword.setHint(isArabic ? "كلمة المرور" : "Password");
        if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText(isArabic ? "تسجيل الدخول" : "Sign In");
        if (btnToggleLanguage != null) btnToggleLanguage.setText(isArabic ? "EN / AR" : "AR / EN");
        if (tvSuccessMessage != null) tvSuccessMessage.setText(isArabic ? "تم تسجيل الدخول بنجاح" : "Login Successful");
    }

    private void applyOrgProfile() {
        if (!isActivityAlive()) return;
        if (orgProfileManager == null) {
            orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());
        }

        if (tvBrandName != null) {
            String name = isArabic ? orgProfileManager.getOrgNameAr() : orgProfileManager.getOrgNameEn();
            tvBrandName.setText((name != null && !name.isEmpty()) ? name : "MIRA Jewelry");
        }

        if (tvBrandSubtitle != null) tvBrandSubtitle.setText("MIRA ID™ Smart Jewelry Tag");

        if (tvBrandDescription != null) {
            tvBrandDescription.setText(isArabic ?
                    "منظومة التتبع والمصادقة المعتمدة للمجوهرات" :
                    "Enterprise Jewelry Traceability & Authentication");
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

    private void setupProfileListener() {
        if (orgProfileManager == null) return;
        profileListener = () -> {
            if (isActivityAlive()) mainHandler.post(this::applyOrgProfile);
        };
        orgProfileManager.addListener(profileListener);
    }

    private void loadOrgProfileFromCloud() {
        if (apiClient == null) return;

        executeSafeBg(() -> {
            try {
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
            } catch (Exception e) {
                Log.e(TAG, "loadOrgProfileFromCloud error: " + e.getMessage());
            }
        });
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
        if (etUsername != null && s == etUsername.getText() && tilUsername != null) tilUsername.setError(null);
        else if (etPassword != null && s == etPassword.getText() && tilPassword != null) tilPassword.setError(null);
        else if (etServerUrl != null && s == etServerUrl.getText() && tilServerUrl != null) tilServerUrl.setError(null);
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

    private boolean isValidServerUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        boolean isLocalhost = url.contains("10.0.2.2") || url.contains("localhost") ||
                url.contains("127.0.0.1") || url.startsWith("192.168.");
        return isLocalhost ? (url.startsWith("http://") || url.startsWith("https://")) : url.startsWith("https://");
    }

    private void restoreLastUsername() {
        String lastUsername = sessionManager.getLastUsername();
        if (lastUsername != null && !lastUsername.isEmpty() && etUsername != null) {
            etUsername.setText(lastUsername);
            etUsername.setSelection(lastUsername.length());
        }
    }

    private void checkLockoutStatus() {
        if (encryptedPrefs == null) return;

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
            updateLoginButtonState();
        }
    }

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
                checkLockoutStatus();
                Toast.makeText(this,
                        isArabic ? "⛔ تم قفل تسجيل الدخول لمدة 5 دقائق بسبب محاولات كثيرة" :
                                "⛔ Login locked for 5 minutes due to too many attempts",
                        Toast.LENGTH_LONG).show();

                mainHandler.postDelayed(() -> {
                    if (isActivityAlive()) {
                        checkLockoutStatus();
                        Toast.makeText(this,
                                isArabic ? "✓ تم فتح تسجيل الدخول - يمكنك المحاولة الآن" :
                                        "✓ Login unlocked - You can try now",
                                Toast.LENGTH_SHORT).show();
                    }
                }, LOCKOUT_DURATION_MS);
            });
        }
    }

    private void resetFailedAttempts() {
        failedAttempts.set(0);
        lockoutUntil = 0;
        if (encryptedPrefs != null) {
            encryptedPrefs.edit()
                    .putInt("failed_attempts", 0)
                    .putLong("lockout_until", 0)
                    .apply();
        }
        if (tvLockStatus != null) tvLockStatus.setVisibility(View.GONE);
    }

    private void attemptApiLogin() {
        String username = getText(etUsername);
        String password = getText(etPassword);
        String serverUrl = getText(etServerUrl);

        Log.d(TAG, "Login Attempt username=" + username);

        if (System.currentTimeMillis() < lockoutUntil) {
            long remainingMinutes = (lockoutUntil - System.currentTimeMillis()) / (60 * 1000);
            Toast.makeText(this,
                    String.format(isArabic ? "⛔ محظور - حاول بعد %d دقيقة" : "⛔ Locked - Try in %d min",
                            Math.max(1, remainingMinutes)),
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (serverUrl.isEmpty()) {
            serverUrl = sessionManager.getServerUrl();
            if (serverUrl.isEmpty()) serverUrl = DEFAULT_SERVER_URL;
            etServerUrl.setText(serverUrl);
        }

        if (!isValidServerUrl(serverUrl)) {
            tilServerUrl.setError(isArabic ? "يجب استخدام HTTPS" : "HTTPS required");
            shakeView(tilServerUrl);
            playTone(false);
            return;
        }

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

        apiClient = MiraApiClient.getInstance(getApplicationContext());
        if (apiClient != null) {
            apiClient.invalidateTokenCache();
            apiClient.refreshUrl();
            Log.d(TAG, "✅ Using same MiraApiClient instance, cache invalidated");
        }

        executeSafeBg(() -> {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            MiraApiClient.ApiResponse response = null;
            boolean isNetworkError = false;

            try {
                response = apiClient.login(username, password, deviceId);
                if (response != null) isNetworkError = response.isNetworkError;
            } catch (Exception e) {
                isNetworkError = true;
            }

            final boolean finalIsNetworkError = isNetworkError;
            final MiraApiClient.ApiResponse finalResponse = response;

            mainHandler.post(() -> {
                if (!isActivityAlive()) return;
                showLoading(false);

                if (finalResponse != null && finalResponse.isSuccess) {
                    resetFailedAttempts();
                    sessionManager.saveLastUsername(username);

                    if (encryptedPrefs != null) {
                        byte[] salt = generateSalt();
                        String passwordHash = hashPasswordWithSalt(password, salt);
                        if (passwordHash != null) {
                            encryptedPrefs.edit()
                                    .putString("offline_salt_" + username, Base64.encodeToString(salt, Base64.NO_WRAP))
                                    .putString("offline_hash_" + username, passwordHash)
                                    .apply();
                        }
                    }

                    handleLoginSuccess(finalResponse, username, password);

                } else if (finalIsNetworkError || finalResponse == null) {
                    if (verifyOfflineLogin(username, password)) {
                        new AlertDialog.Builder(LoginActivity.this)
                                .setTitle(isArabic ? "📡 وضع الأوفلاين" : "📡 Offline Mode")
                                .setMessage(isArabic ?
                                        "أنت غير متصل بالإنترنت.\n\nسيتم تسجيل الدخول باستخدام البيانات المحلية.\nهل تريد المتابعة؟" :
                                        "You are offline.\n\nLogin will use local data.\nDo you want to continue?")
                                .setPositiveButton(isArabic ? "✅ متابعة" : "✅ Continue", (dialog, which) -> {
                                    dialog.dismiss();
                                    resetFailedAttempts();
                                    playTone(true);
                                    showSuccessOverlayAndNavigate();
                                })
                                .setNegativeButton(isArabic ? "❌ إلغاء" : "❌ Cancel", (dialog, which) -> dialog.dismiss())
                                .setCancelable(false)
                                .show();
                    } else {
                        recordFailedAttempt();
                        playTone(false);
                        Toast.makeText(LoginActivity.this,
                                isArabic ? "❌ لا يمكن التحقق من بيانات الدخول بدون اتصال\nيجب تسجيل الدخول مرة واحدة على الأقل بالإنترنت" :
                                        "❌ Cannot verify credentials offline\nYou must login at least once with internet connection",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    recordFailedAttempt();
                    playTone(false);
                    int errorCode = finalResponse.code;
                    String errorMsg = getErrorMessage(errorCode, isArabic ? "فشل الاتصال بالخادم" : "Connection failed");
                    Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    shakeView(loginCard);
                }
            });
        });
    }

    private void handleLoginSuccess(MiraApiClient.ApiResponse response, String username, String password) {
        Log.d(TAG, "Login Success for user: " + username);
        playTone(true);

        if (sessionManager != null) {
            sessionManager.resetAutoLock();
        }

        Context appContext = getApplicationContext();

        String token = response.optString("token", "");
        String roleStr = response.optString("role", "ADMIN");
        String displayName = response.optString("display_name", username);
        int userId = response.optInt("user_id", 0);
        int branchId = response.optInt("branch_id", 1);
        String branchName = response.optString("branch_name", "");
        String branchCode = response.optString("branch_code", "");
        String serverUrl = getText(etServerUrl);

        if (serverUrl.isEmpty()) serverUrl = sessionManager.getServerUrl();

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String apiKey = response.optString("api_key", "");
        String gateId = response.optString("gate_id", "");

        if (!apiKey.isEmpty()) {
            String effectiveGateId = !gateId.isEmpty() ? gateId : deviceId;
            sessionManager.saveApiKey(apiKey);
            sessionManager.saveGateId(effectiveGateId);
        }

        if (!serverUrl.isEmpty()) {
            sessionManager.setServerUrl(serverUrl);
            MiraServerConfig.getInstance(appContext).setBaseUrl(serverUrl);
        }

        String permissionsJson = response.optString("permissions", "");
        if (!permissionsJson.isEmpty()) {
            PermissionManager.resetInstance();
            PermissionManager pm = PermissionManager.getInstance(appContext);
            pm.savePermissions(permissionsJson);
        }

        sessionManager.createLoginSession(username, roleStr, token, serverUrl);
        sessionManager.setDisplayName(displayName);
        sessionManager.setUserId(userId);
        sessionManager.setBranchId(branchId);

        sessionManager.saveBiometricUser(username, roleStr, displayName, userId, token);
        sessionManager.saveBiometricBranch(branchId, branchName);

        boolean biometricEnabled = true;
        if (response.data != null) {
            biometricEnabled = response.data.optBoolean("biometric_enabled", true);
        }
        sessionManager.setBiometricLoginEnabled(biometricEnabled);

        MiraBranchesManager bm = MiraBranchesManager.getInstance(appContext);
        bm.setActiveBranch(branchId, branchName, branchCode, "", "");
        bm.setUserBranchLocked(true);

        MiraApiClient client = MiraApiClient.getInstance(appContext);
        if (client != null) {
            client.invalidateTokenCache();
            client.refreshUrl();
            Log.d(TAG, "✅ Token cache invalidated, using same instance");
        }

        showSuccessOverlayAndNavigate();
    }

    private void setupBiometric() {
        try {
            boolean biometricEnabled = sessionManager.isBiometricLoginEnabled();
            if (!biometricEnabled) {
                if (btnBiometric != null) btnBiometric.setVisibility(View.GONE);
                return;
            }

            BiometricManager bm = BiometricManager.from(this);
            int canAuthenticate = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

            if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
                if (btnBiometric != null) btnBiometric.setVisibility(View.GONE);
                return;
            }

            Executor executor = ContextCompat.getMainExecutor(this);
            biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    String lastUser        = sessionManager.getLastBiometricUser();
                    String lastRole        = sessionManager.getLastBiometricRole();
                    String lastDisplayName = sessionManager.getLastBiometricDisplayName();
                    int    lastUserId      = sessionManager.getLastBiometricUserId();
                    String lastToken       = sessionManager.getLastBiometricToken();

                    if (lastUser.isEmpty()) {
                        playTone(false);
                        Toast.makeText(LoginActivity.this,
                                isArabic ? "قم بتسجيل الدخول بكلمة المرور أولاً" : "Sign in with password first",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    sessionManager.createLoginSession(
                            lastUser,
                            lastRole,
                            lastToken.isEmpty() ? "BIOMETRIC_TOKEN" : lastToken,
                            sessionManager.getServerUrl()
                    );
                    sessionManager.setDisplayName(lastDisplayName);
                    sessionManager.setUserId(lastUserId);

                    int savedBranchId = sessionManager.getLastBiometricBranchId();
                    String savedBranchName = sessionManager.getLastBiometricBranchName();
                    if (savedBranchId > 0) {
                        MiraBranchesManager bm = MiraBranchesManager.getInstance(getApplicationContext());
                        bm.setActiveBranch(savedBranchId, savedBranchName, "", "", "");
                        bm.setUserBranchLocked(true);
                    }

                    if (sessionManager.getLastBiometricUser().isEmpty() == false) {
                        sessionManager.saveBiometricUser(lastUser, lastRole, lastDisplayName, lastUserId, lastToken);
                    }

                    sessionManager.resetAutoLock();
                    resetFailedAttempts();
                    playTone(true);

                    showSuccessOverlayAndNavigate();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        playTone(false);
                        Toast.makeText(LoginActivity.this, errString, Toast.LENGTH_SHORT).show();
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

            BiometricPrompt.PromptInfo.Builder promptBuilder = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(isArabic ? "تسجيل الدخول الحيوي" : "Biometric Sign In")
                    .setSubtitle(isArabic ? "استخدم بصمة الإصبع للمتابعة" : "Use fingerprint to continue")
                    .setNegativeButtonText(isArabic ? "إلغاء" : "Cancel");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                promptBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            }

            promptInfo = promptBuilder.build();

            if (btnBiometric != null) {
                btnBiometric.setOnClickListener(v -> {
                    if (!sessionManager.isBiometricLoginEnabled()) {
                        Toast.makeText(this, isArabic ? "البصمة معطلة" : "Biometric disabled", Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (!sessionManager.getLastBiometricUser().isEmpty() || sessionManager.isLoggedIn()) {
                        if (biometricPrompt != null && promptInfo != null) {
                            biometricPrompt.authenticate(promptInfo);
                        }
                    } else {
                        playTone(false);
                        Toast.makeText(this,
                                isArabic ? "سجل الدخول بكلمة المرور أولاً لتفعيل البصمة" : "Sign in with password first",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Biometric setup error: " + e.getMessage(), e);
            if (btnBiometric != null) btnBiometric.setVisibility(View.GONE);
        }
    }

    private void updateBiometricButtonState() {
        if (btnBiometric != null) {
            boolean biometricEnabled = sessionManager.isBiometricLoginEnabled();
            boolean hasBiometricUser = !sessionManager.getLastBiometricUser().isEmpty();
            btnBiometric.setVisibility(biometricEnabled && hasBiometricUser ? View.VISIBLE : View.GONE);
        }
    }

    private boolean verifyOfflineLogin(String username, String password) {
        try {
            if (encryptedPrefs == null) return false;

            String savedSaltBase64 = encryptedPrefs.getString("offline_salt_" + username, "");
            String savedHash = encryptedPrefs.getString("offline_hash_" + username, "");

            if (savedSaltBase64.isEmpty() || savedHash.isEmpty()) return false;

            byte[] salt = Base64.decode(savedSaltBase64, Base64.DEFAULT);
            String currentHash = hashPasswordWithSalt(password, salt);

            if (currentHash != null && currentHash.equals(savedHash)) {
                sessionManager.createLoginSession(username,
                        sessionManager.getLastBiometricRole(),
                        "OFFLINE_TOKEN",
                        sessionManager.getServerUrl());
                sessionManager.setDisplayName(sessionManager.getLastBiometricDisplayName());
                sessionManager.setUserId(sessionManager.getLastBiometricUserId());

                int savedBranchId = sessionManager.getLastBiometricBranchId();
                String savedBranchName = sessionManager.getLastBiometricBranchName();
                if (savedBranchId > 0) {
                    MiraBranchesManager bm = MiraBranchesManager.getInstance(getApplicationContext());
                    bm.setActiveBranch(savedBranchId, savedBranchName, "", "", "");
                    bm.setUserBranchLocked(true);
                }

                sessionManager.resetAutoLock();

                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "verifyOfflineLogin error: " + e.getMessage());
            return false;
        }
    }

    private String hashPasswordWithSalt(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            Log.e(TAG, "Password hashing error", e);
            return null;
        }
    }

    private byte[] generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    private void playTone(boolean isSuccess) {
        if (soundManager == null) return;
        soundManager.play(isSuccess
                ? MiraSoundManager.SoundEvent.LOGIN_SUCCESS
                : MiraSoundManager.SoundEvent.LOGIN_FAILED);
    }

    private String getErrorMessage(int errorCode, String fallbackMessage) {
        switch (errorCode) {
            case 400: return isArabic ? "بيانات غير صالحة - تحقق من المدخلات" : "Invalid data - Check inputs";
            case 401: return isArabic ? "بيانات الدخول غير صحيحة" : "Invalid credentials";
            case 403: return isArabic ? "ليس لديك صلاحية للدخول" : "Access denied";
            case 404: return isArabic ? "الخادم غير موجود - تحقق من الرابط" : "Server not found";
            case 408:
            case 504: return isArabic ? "انتهت مهلة الاتصال - حاول مرة أخرى" : "Connection timeout";
            case 429: return isArabic ? "محاولات كثيرة - انتظر قليلاً" : "Too many attempts";
            case 500: return isArabic ? "خطأ في الخادم - حاول لاحقاً" : "Server error";
            case 503: return isArabic ? "الخدمة غير متاحة حالياً" : "Service unavailable";
            default: return fallbackMessage != null ? fallbackMessage : (isArabic ? "خطأ في الاتصال" : "Connection error");
        }
    }

    private void showLoading(boolean loading) {
        if (loadingContainer != null) loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null) {
            btnLogin.setEnabled(!loading);
            btnLogin.setText(loading ? "" : (isArabic ? "تسجيل الدخول" : "Sign In"));
        }
        if (btnBiometric != null) btnBiometric.setEnabled(!loading);
        if (etUsername != null) etUsername.setEnabled(!loading);
        if (etPassword != null) etPassword.setEnabled(!loading);
    }

    private void shakeView(View view) {
        if (view == null) return;
        ObjectAnimator.ofFloat(view, "translationX", 0, 20, -20, 20, -20, 10, -10, 5, -5, 0).setDuration(500).start();
    }

    private void showSuccessOverlayAndNavigate() {
        if (successOverlay != null) {
            successOverlay.setVisibility(View.VISIBLE);
            successOverlay.animate().alpha(1.0f).setDuration(350).withEndAction(() -> {
                mainHandler.postDelayed(this::navigateToMainDirect, 400);
            }).start();
        } else {
            navigateToMainDirect();
        }
    }

    private void navigateToMainDirect() {
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
        Log.d(TAG, "onDestroy called");
        MemoryManager.getInstance().unregisterActivity(this);
        unregisterComponentCallbacks(MemoryManager.getInstance());
        isDestroyed.set(true);

        if (orgProfileManager != null && profileListener != null) {
            orgProfileManager.removeListener(profileListener);
        }

        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    // ═══════════════════════════════════════════════════════
//  ⚡ MIRA License Integrity Check
// ═══════════════════════════════════════════════════════

/**
 * يفحص سلامة الترخيص محلياً (بدون اتصال بالإنترنت).
 * 
 * @return true إذا الترخيص سليم أو تم جلبه بنجاح.
 */
private boolean checkLicenseIntegrity() {
    // [1] فحص وجود blob مخزّن محلياً
    MiraBlobStorage.BlobData storedBlob = MiraBlobStorage.load(this);
    
    if (storedBlob != null) {
        // تحقق محلي فقط (بدون إنترنت)
        MiraBlobVerifier.VerifyResult result = MiraBlobVerifier.verify(
                storedBlob.blobJson,
                storedBlob.signatureB64,
                storedBlob.publicKeyB64
        );
        
        if (result.valid) {
            // فحص الصلاحية الزمنية
            MiraBlobVerifier.VerifyResult fullResult =
                    MiraBlobVerifier.verifyAndValidate(storedBlob, storedBlob);
            
            if (fullResult.valid) {
                Log.d(TAG, "✅ License valid (local verify, v" + storedBlob.version + ")");
                return true;
            } else {
                Log.w(TAG, "⏰ License expired: " + fullResult.message);
                showBlockingDialog(
                        isArabic ? "⏰ انتهى الترخيص" : "⏰ License Expired",
                        fullResult.message
                );
                return false;
            }
        } else {
            Log.e(TAG, "🚨 License tampered: " + result.message);
            showBlockingDialog(
                    isArabic ? "⚠️ ترخيص مُعدَّل" : "⚠️ License Tampered",
                    result.message
            );
            return false;
        }
    }
    
    // [2] لا يوجد blob مخزّن — محاولة جلب من الخادم
    Log.d(TAG, "📥 No local blob — fetching from server...");
    
    if (MiraBlobFetcher.fetchVerifyAndStore(this)) {
        Log.d(TAG, "✅ License fetched and verified");
        return true;
    }
    
    // [3] فشل الجلب والتحقق
    Log.e(TAG, "❌ License check failed entirely");
    showBlockingDialog(
            isArabic ? "❌ لا يوجد ترخيص صالح" : "❌ No Valid License",
            isArabic
                    ? "لم نتمكن من التحقق من ترخيصك.\n\n"
                    + "تحقق من اتصالك بالإنترنت، ثم أعد المحاولة.\n"
                    + "إذا استمرت المشكلة، تواصل مع ميرا تكنولوجي."
                    : "Could not verify your license.\n\n"
                    + "Check your internet connection and try again.\n"
                    + "If the issue persists, contact MIRA Technology."
    );
    return false;
}

/**
 * حوار مانع — يُغلق التطبيق بعد الضغط
 */
private void showBlockingDialog(String title, String message) {
    if (isFinishing()) return;
    
    new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(
                    isArabic ? "حسناً" : "OK",
                    (d, w) -> finish()
            )
            .setNeutralButton(
                    isArabic ? "إعادة المحاولة" : "Retry",
                    (d, w) -> recreate()
            )
            .show();
}
}
