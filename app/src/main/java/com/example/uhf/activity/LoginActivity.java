package com.example.uhf.activity;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
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
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private TextInputLayout tilServerUrl, tilUsername, tilPassword;
    private MaterialButton btnLogin, btnBiometric, btnSaveServer, btnToggleLanguage;
    private MaterialTextView tvServerSettings, tvAppVersion, tvBrandName, tvBrandSubtitle, tvBrandDescription, tvSignInTitle, tvSignInSubtitle;
    private ImageView ivAppLogo;
    private MaterialCardView serverConfigCard;
    private ProgressBar pbLoading;
    private View loadingContainer, loginCard;
    
    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor bgExecutor = Executors.newSingleThreadExecutor();

    private boolean isArabic = true; // اللغة الافتراضية عربية

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
        updateUiLanguage(isArabic); // تطبيق اللغة الافتراضية عند البدء
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
        tvBrandDescription = findViewById(R.id.tvBrandDescription);
        tvSignInTitle = findViewById(R.id.tvSignInTitle);
        tvSignInSubtitle = findViewById(R.id.tvSignInSubtitle);
        ivAppLogo = findViewById(R.id.ivAppLogo);
        serverConfigCard = findViewById(R.id.serverConfigCard);
        pbLoading = findViewById(R.id.pbLoading);
        loadingContainer = findViewById(R.id.loadingContainer);
        loginCard = findViewById(R.id.loginCard);

        // تعبئة رابط الخادم المحفوظ
        String savedUrl = sessionManager.getServerUrl();
        if (savedUrl != null && !savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        }

        // رقم الإصدار
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

        // زر التبديل بين اللغات
        if (btnToggleLanguage != null) {
            btnToggleLanguage.setOnClickListener(v -> {
                isArabic = !isArabic;
                updateUiLanguage(isArabic);
            });
        }
        
        // تطبيق بروفايل وشعار الجهة المحفوظ ديناميكياً
        applyOrgProfile();
    }

    /**
     * 🌐 تحديث نصوص الواجهة واتجاهها بناءً على اللغة المختارة
     */
    private void updateUiLanguage(boolean arabic) {
        if (arabic) {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            if (tvSignInTitle != null) tvSignInTitle.setText("تسجيل الدخول");
            if (tvSignInSubtitle != null) tvSignInSubtitle.setText("أدخل بيانات الاعتماد الخاصة بك للمتابعة");
            if (tilUsername != null) tilUsername.setHint("اسم المستخدم / البريد الإلكتروني");
            if (tilPassword != null) tilPassword.setHint("كلمة المرور");
            if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText("دخول النظام");
            if (tvServerSettings != null) tvServerSettings.setText("🔧 إعدادات الخادم والاتصال (Server Settings)");
        } else {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            if (tvSignInTitle != null) tvSignInTitle.setText("Sign In");
            if (tvSignInSubtitle != null) tvSignInSubtitle.setText("Enter your credentials to continue");
            if (tilUsername != null) tilUsername.setHint("Username / Email");
            if (tilPassword != null) tilPassword.setHint("Password");
            if (btnLogin != null && (pbLoading == null || !pbLoading.isShown())) btnLogin.setText("Sign In");
            if (tvServerSettings != null) tvServerSettings.setText("🔧 Server Configuration");
        }
        
        // إعادة تطبيق الهوية لترجمة الوصف حسب اللغة إن أمكن
        applyOrgProfile();
    }

    /**
     * 🏢 تطبيق بيانات وشعار الجهة المحفوظة ديناميكياً على الواجهة
     */
    /**
 * 🏢 تطبيق بيانات وشعار الجهة المحفوظة ديناميكياً على الواجهة
 */
private void applyOrgProfile() {
    MiraOrgProfileManager orgProfile = MiraOrgProfileManager.getInstance(this);
    
    String orgName = orgProfile.getOrgName();
    String orgSlogan = orgProfile.getOrgSlogan();

    // 1. اسم الماركة/الجهة في العنوان الرئيسي
    if (tvBrandName != null && orgName != null && !orgName.isEmpty()) {
        tvBrandName.setText(orgName);
    }

    // 2. تحديث العنوان الفرعي
    if (tvBrandSubtitle != null) {
        if (orgName != null && !orgName.isEmpty() && !orgName.equals("MIRA Jewelry")) {
            tvBrandSubtitle.setText(orgName + " Enterprise");
        } else {
            tvBrandSubtitle.setText("MIRA ID™ Smart Jewelry Tag");
        }
    }
    
    // 3. تحديث الوصف بشعار الجهة
    if (tvBrandDescription != null) {
        if (orgSlogan != null && !orgSlogan.isEmpty() && !orgSlogan.equals("Jewelry Enterprise Platform")) {
            tvBrandDescription.setText(orgSlogan);
        } else {
            tvBrandDescription.setText(isArabic ? "منظومة التتبع والمصادقة المعتمدة للمجوهرات" : "Enterprise Jewelry Traceability & Authentication");
        }
    }

    // 4. عرض الشعار الفخم المعتمد برمجياً
    if (ivAppLogo != null) {
        ivAppLogo.setImageResource(R.drawable.ic_luxury_mira_logo);
    }
}


    /**
     * 🔧 إظهار/إخفاء إعدادات الخادم (للمسؤولين فقط)
     */
    private void setupServerConfigToggle() {
        if (tvServerSettings != null) {
            tvServerSettings.setOnClickListener(v -> {
                if (serverConfigCard.getVisibility() == View.GONE) {
                    serverConfigCard.setVisibility(View.VISIBLE);
                    serverConfigCard.setAlpha(0f);
                    serverConfigCard.animate().alpha(1f).setDuration(300).start();
                    tvServerSettings.setText(isArabic ? "🔧 إخفاء إعدادات الخادم" : "🔧 Hide Server Settings");
                } else {
                    serverConfigCard.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                        serverConfigCard.setVisibility(View.GONE);
                    }).start();
                    tvServerSettings.setText(isArabic ? "🔧 إعدادات الخادم والاتصال (Server Settings)" : "🔧 Server Configuration");
                }
            });
        }

        // الضغط المطول على البطاقة يظهر الإعدادات أيضاً
        if (loginCard != null) {
            loginCard.setOnLongClickListener(v -> {
                if (tvServerSettings != null) {
                    tvServerSettings.performClick();
                }
                return true;
            });
        }
    }

    private void animateEntrance() {
        if (loginCard == null) return;
        loginCard.setAlpha(0f);
        loginCard.setTranslationY(60f);
        loginCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .setStartDelay(150)
            .start();
    }

    private void setupTextWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateLoginButtonState();
            }
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

    // ==========================================
    // 🌐 تسجيل الدخول
    // ==========================================
    private void attemptApiLogin() {
        String username = getText(etUsername);
        String password = getText(etPassword);

        if (username.isEmpty()) {
            tilUsername.setError(isArabic ? "اسم المستخدم مطلوب" : "Username required");
            shakeView(tilUsername);
            return;
        }
        if (password.length() < 4) {
            tilPassword.setError(isArabic ? "كلمة المرور قصيرة جداً" : "Password too short");
            shakeView(tilPassword);
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
        String token = response.optString("token", "");
        String roleStr = response.optString("role", "ADMIN");
        String displayName = response.optString("display_name", username);
        int userId = response.optInt("user_id", 0);
        String serverUrl = getText(etServerUrl);

        sessionManager.createLoginSession(username, roleStr, token, serverUrl);
        sessionManager.setDisplayName(displayName);
        sessionManager.setUserId(userId);
        sessionManager.saveBiometricUser(username, roleStr);
        sessionManager.setBiometricEnabled(true);

        Toast.makeText(this, (isArabic ? "أهلاً بك " : "Welcome ") + displayName + " ✓", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(this::navigateToMain, 400);
    }

    // ==========================================
    // 🔒 المصادقة بالبصمة
    // ==========================================
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
                if (!lastUser.isEmpty()) {
                    if (!sessionManager.isLoggedIn()) {
                        sessionManager.createLoginSession(lastUser, lastRole, "BIOMETRIC_TOKEN", sessionManager.getServerUrl());
                    }
                    Toast.makeText(LoginActivity.this, (isArabic ? "أهلاً عودتك " : "Welcome back ") + lastUser + " 👋", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                } else {
                    Toast.makeText(LoginActivity.this, isArabic ? "قم بتسجيل الدخول بكلمة المرور أولاً" : "Sign in with password first", Toast.LENGTH_LONG).show();
                }
            }
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(LoginActivity.this, "Error: " + errString, Toast.LENGTH_SHORT).show();
                }
            }
            @Override
            public void onAuthenticationFailed() {
                Toast.makeText(LoginActivity.this, isArabic ? "لم يتم التعرف على بصمة الإصبع" : "Fingerprint not recognized", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(isArabic ? "تسجيل الدخول الحيوي (Biometric)" : "Biometric Sign In")
            .setSubtitle(isArabic ? "استخدم بصمة الإصبع لتسجيل الدخول السريع" : "Use your fingerprint to sign in")
            .setNegativeButtonText(isArabic ? "استخدام كلمة المرور" : "Use password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();

        if (btnBiometric != null) {
            btnBiometric.setOnClickListener(v -> {
                if (!sessionManager.getLastBiometricUser().isEmpty() || sessionManager.isLoggedIn()) {
                    biometricPrompt.authenticate(promptInfo);
                } else {
                    Toast.makeText(this, isArabic ? "سجل الدخول بكلمة المرور أولاً لتفعيل البصمة" : "Sign in with password first to enable biometric", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void showLoading(boolean loading) {
        if (loadingContainer != null) loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnLogin != null) {
            btnLogin.setEnabled(!loading);
            if (loading) {
                btnLogin.setText("");
            } else {
                btnLogin.setText(isArabic ? "دخول النظام" : "Sign In");
            }
        }
        if (btnBiometric != null) btnBiometric.setEnabled(!loading);
        if (etUsername != null) etUsername.setEnabled(!loading);
        if (etPassword != null) etPassword.setEnabled(!loading);
    }

    private void shakeView(View view) {
        if (view == null) return;
        ObjectAnimator.ofFloat(view, "translationX", 0, 20, -20, 20, -20, 10, -10, 5, -5, 0)
            .setDuration(500).start();
    }

    private void navigateToMain() {
        startActivity(new Intent(this, UHFMainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
