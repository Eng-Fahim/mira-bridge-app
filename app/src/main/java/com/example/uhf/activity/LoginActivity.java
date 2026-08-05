package com.example.uhf.activity;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private TextInputLayout tilServerUrl, tilUsername, tilPassword;
    private MaterialButton btnLogin, btnBiometric, btnSaveServer, btnToggleLanguage;
    private MaterialTextView tvServerSettings, tvAppVersion, tvBrandName, tvBrandSubtitle, tvBrandDescription;
    private MaterialTextView tvSignInTitle, tvSignInSubtitle;
    private ImageView ivAppLogo;
    private MaterialCardView serverConfigCard;
    private ProgressBar pbLoading;
    private View loadingContainer, loginCard, logoContainer;

    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor bgExecutor = Executors.newSingleThreadExecutor();

    private boolean isArabic = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);

        if (sessionManager.isLoggedIn()) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_login);
        initViews();
        setupBiometric();
        setupServerConfigToggle();
        animateEntrance();
        setupTextWatchers();
        updateUiLanguage(isArabic);
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
        tvAppVersion = findViewById(R.id.tvAppVersion);
        tvBrandName = findViewById(R.id.tvBrandName);
        tvBrandSubtitle = findViewById(R.id.tvBrandSubtitle);
        tvSignInTitle = findViewById(R.id.tvSignInTitle);
        tvSignInSubtitle = findViewById(R.id.tvSignInSubtitle);
        ivAppLogo = findViewById(R.id.ivAppLogo);
        serverConfigCard = findViewById(R.id.serverConfigCard);
        pbLoading = findViewById(R.id.pbLoading);
        loadingContainer = findViewById(R.id.loadingContainer);
        loginCard = findViewById(R.id.loginCard);
        logoContainer = findViewById(R.id.logoContainer);

        String savedUrl = sessionManager.getServerUrl();
        if (savedUrl != null && !savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        }

        try {
            tvAppVersion.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " Enterprise");
        } catch (Exception e) {
            tvAppVersion.setText("v1.0.0 Enterprise");
        }

        apiClient = MiraApiClient.getInstance(this);

        btnLogin.setOnClickListener(v -> attemptApiLogin());

        btnSaveServer.setOnClickListener(v -> {
            String url = getText(etServerUrl);
            sessionManager.setServerUrl(url);
            Toast.makeText(this, isArabic ? "✓ تم حفظ رابط الخادم بنجاح" : "✓ Server URL saved", Toast.LENGTH_SHORT).show();
        });

        if (btnToggleLanguage != null) {
            btnToggleLanguage.setOnClickListener(v -> {
                isArabic = !isArabic;
                if (isArabic) {
                    AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags("ar"));
                } else {
                    AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags("en"));
                }
                updateUiLanguage(isArabic);
            });
        }

        applyOrgProfile();
        loadOrgProfileFromCloud();
    }

    // ==================== Cloud Org Profile ====================

    private void loadOrgProfileFromCloud() {
        bgExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getOrgProfile();
                if (response != null && response.isSuccess && response.data != null) {
                    String orgName = response.data.optString("org_name", "");
                    if (!orgName.isEmpty()) {
                        getSharedPreferences("MIRA_ORG_PROFILE", Context.MODE_PRIVATE)
                            .edit()
                            .putString("org_name", orgName)
                            .putString("org_slogan", response.data.optString("org_slogan", ""))
                            .putString("org_phone", response.data.optString("org_phone", ""))
                            .putString("org_email", response.data.optString("org_email", ""))
                            .putString("org_address", response.data.optString("org_address", ""))
                            .putString("org_website", response.data.optString("org_website", ""))
                            .apply();
                        mainHandler.post(this::applyOrgProfile);
                    }
                }
            } catch (Exception e) {
                Log.e("LoginActivity", "Cloud org load error: " + e.getMessage());
            }
        });
    }

    // ==================== Language ====================

    private void updateUiLanguage(boolean arabic) {
        if (arabic) {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            if (tvSignInTitle != null) tvSignInTitle.setText("تسجيل الدخول");
            if (tvSignInSubtitle != null) tvSignInSubtitle.setText("أدخل بيانات الاعتماد الخاصة بك للمتابعة");
            if (tilUsername != null) tilUsername.setHint("اسم المستخدم");
            if (tilPassword != null) tilPassword.setHint("كلمة المرور");
            if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText("دخول النظام");
            if (tvServerSettings != null) tvServerSettings.setText("🔧 إعدادات الخادم والاتصال (Server Settings)");
            if (btnToggleLanguage != null) btnToggleLanguage.setText("EN / AR");
        } else {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
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

    // ==================== Org Profile ====================

    private void applyOrgProfile() {
        MiraOrgProfileManager orgProfile = MiraOrgProfileManager.getInstance(this);
        String orgName = orgProfile.getOrgName();
        String orgSlogan = orgProfile.getOrgSlogan();

        if (tvBrandName != null && orgName != null && !orgName.isEmpty()) {
            tvBrandName.setText(orgName);
        }
        if (tvBrandSubtitle != null) {
            if (orgName != null && !orgName.isEmpty() && !orgName.equals("MIRA Jewelry")) {
                tvBrandSubtitle.setText(orgName + " Enterprise");
            } else {
                tvBrandSubtitle.setText("MIRA ID™ Smart Jewelry Tag");
            }
        }
        if (tvBrandDescription != null) {
            if (orgSlogan != null && !orgSlogan.isEmpty() && !orgSlogan.equals("Jewelry Enterprise Platform")) {
                tvBrandDescription.setText(orgSlogan);
            } else {
                tvBrandDescription.setText(isArabic ? "منظومة التتبع والمصادقة المعتمدة للمجوهرات" : "Enterprise Jewelry Traceability & Authentication");
            }
        }
        if (ivAppLogo != null) {
            ivAppLogo.setImageResource(R.drawable.ic_luxury_mira_logo);
        }
    }

    // ==================== Server Config ====================

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
        if (loginCard != null) {
            loginCard.setOnLongClickListener(v -> {
                if (tvServerSettings != null) tvServerSettings.performClick();
                return true;
            });
        }
    }

    // ==================== Animation ====================

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

    // ==================== Text Watchers ====================

    private void setupTextWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateLoginButtonState(); }
        };
        if (etUsername != null) etUsername.addTextChangedListener(watcher);
        if (etPassword != null) etPassword.addTextChangedListener(watcher);
    }

    private void updateLoginButtonState() {
        String username = getText(etUsername);
        String password = getText(etPassword);
        boolean valid = !username.isEmpty() && password.length() >= 4;
        if (btnLogin != null) {
            btnLogin.setEnabled(valid);
            btnLogin.setAlpha(valid ? 1f : 0.5f);
        }
    }

    private String getText(TextInputEditText editText) {
        return (editText != null && editText.getText() != null) ? editText.getText().toString().trim() : "";
    }

    // ==================== Tone ====================

    private void playTone(boolean isSuccess) {
        try {
            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            toneGen.startTone(isSuccess ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_SUP_ERROR, 150);
        } catch (Exception e) {
            Log.e("LoginActivity", "Tone error: " + e.getMessage());
        }
    }

    // ==================== Login ====================

    private void attemptApiLogin() {
        String username = getText(etUsername);
        String password = getText(etPassword);

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
        showLoading(true);

        bgExecutor.execute(() -> {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            MiraApiClient.ApiResponse response = apiClient.login(username, password, deviceId);
            mainHandler.post(() -> {
                showLoading(false);
                if (response != null && response.isSuccess) {
                    handleLoginSuccess(response, username);
                } else {
                    playTone(false);
                    String errorMsg = response != null
                        ? response.optString("message", isArabic ? "بيانات الاعتماد غير صالحة" : "Invalid credentials")
                        : (isArabic ? "فشل الاتصال بالخادم" : "Connection failed");
                    Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    shakeView(loginCard);
                }
            });
        });
    }

    private void handleLoginSuccess(MiraApiClient.ApiResponse response, String username) {
        playTone(true);

        String token = response.optString("token", "");
        String roleStr = response.optString("role", "ADMIN");
        String displayName = response.optString("display_name", username);
        int userId = response.optInt("user_id", 0);
        int branchId = response.optInt("branch_id", 1);
        String branchName = response.optString("branch_name", "");
        String branchCode = response.optString("branch_code", "");
        String serverUrl = getText(etServerUrl);

        // حفظ الجلسة
        sessionManager.createLoginSession(username, roleStr, token, serverUrl);
        sessionManager.setDisplayName(displayName);
        sessionManager.setUserId(userId);

        // حفظ بيانات البصمة كاملة
        sessionManager.saveBiometricUser(username, roleStr, displayName, userId, token);
        sessionManager.saveBiometricBranch(branchId, branchName);
        sessionManager.setBiometricEnabled(true);

        // حفظ الفرع النشط تلقائياً وقفله
        MiraBranchesManager bm = MiraBranchesManager.getInstance(this);
        bm.setActiveBranch(branchId, branchName, branchCode, "", "");
        bm.setUserBranchLocked(true);

        Toast.makeText(this, (isArabic ? "أهلاً بك " : "Welcome ") + displayName + " ✓", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(this::navigateToMain, 400);
    }

    // ==================== Biometric ====================

    private void setupBiometric() {
        BiometricManager bm = BiometricManager.from(this);
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
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
                        // إنشاء جلسة كاملة
                        sessionManager.createLoginSession(lastUser, lastRole,
                            lastToken.isEmpty() ? "BIOMETRIC_TOKEN" : lastToken,
                            sessionManager.getServerUrl());
                        sessionManager.setDisplayName(lastDisplayName);
                        sessionManager.setUserId(lastUserId);

                        // استعادة الفرع
                        int savedBranchId = sessionManager.getLastBiometricBranchId();
                        String savedBranchName = sessionManager.getLastBiometricBranchName();
                        if (savedBranchId > 0) {
                            MiraBranchesManager bm = MiraBranchesManager.getInstance(LoginActivity.this);
                            bm.setActiveBranch(savedBranchId, savedBranchName, "", "", "");
                            bm.setUserBranchLocked(true);
                        }
                    }
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
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
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
            .setSubtitle(isArabic ? "استخدم بصمة الإصبع لتسجيل الدخول السريع" : "Use your fingerprint to sign in")
            .setNegativeButtonText(isArabic ? "استخدام كلمة المرور" : "Use password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();

        if (btnBiometric != null) {
            btnBiometric.setOnClickListener(v -> {
                if (!sessionManager.getLastBiometricUser().isEmpty() || sessionManager.isLoggedIn()) {
                    biometricPrompt.authenticate(promptInfo);
                } else {
                    playTone(false);
                    Toast.makeText(this,
                        isArabic ? "سجل الدخول بكلمة المرور أولاً لتفعيل البصمة" : "Sign in with password first to enable biometric",
                        Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // ==================== Helpers ====================

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
        startActivity(new Intent(this, UHFMainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
            }
