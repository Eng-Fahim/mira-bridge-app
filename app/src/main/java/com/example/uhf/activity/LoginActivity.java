package com.example.uhf.activity;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private TextInputLayout tilServerUrl, tilUsername, tilPassword;
    private MaterialButton btnLogin, btnBiometric;
    private MaterialTextView tvAppVersion, tvConnectionStatus;
    private ProgressBar pbLoading;
    private View loadingContainer, loginCard;
    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);

        // إذا كانت الجلسة مسجلة مسبقاً، انتقل للشاشة الرئيسية
        if (sessionManager.isLoggedIn()) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_login);
        initViews();
        setupBiometric();
        animateEntrance();
        setupTextWatchers();
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
        pbLoading = findViewById(R.id.pbLoading);
        loadingContainer = findViewById(R.id.loadingContainer);
        loginCard = findViewById(R.id.loginCard);
        tvAppVersion = findViewById(R.id.tvAppVersion);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);

        // استرجاع آخر رابط خادم محفوظ
        String savedUrl = sessionManager.getServerUrl();
        if (!savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        }

        // تعيين رقم الإصدار
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvAppVersion.setText("MIRA Enterprise v" + versionName);
        } catch (Exception e) {
            tvAppVersion.setText("MIRA Enterprise");
        }

        // تهيئة عميل API
        apiClient = MiraApiClient.getInstance(this);

        btnLogin.setOnClickListener(v -> attemptApiLogin());

        // فحص أولي للاتصال
        checkServerConnectivity();
    }

    /**
     * 🎬 حركة دخول أنيقة للبطاقة
     */
    private void animateEntrance() {
        if (loginCard == null) return;
        
        loginCard.setAlpha(0f);
        loginCard.setTranslationY(80f);
        
        loginCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(700)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .setStartDelay(200)
            .start();
    }

    /**
     * 📝 مراقبة النصوص لتفعيل/تعطيل الأزرار ديناميكياً
     */
    private void setupTextWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateLoginButtonState();
            }
        };

        etServerUrl.addTextChangedListener(watcher);
        etUsername.addTextChangedListener(watcher);
        etPassword.addTextChangedListener(watcher);
    }

    private void updateLoginButtonState() {
        String serverUrl = getText(etServerUrl);
        String username = getText(etUsername);
        String password = getText(etPassword);
        
        boolean valid = !serverUrl.isEmpty() && !username.isEmpty() && password.length() >= 4;
        btnLogin.setEnabled(valid);
        btnLogin.setAlpha(valid ? 1f : 0.5f);
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    /**
     * 🌐 فحص الاتصال بالخادم
     */
    private void checkServerConnectivity() {
        String serverUrl = getText(etServerUrl);
        if (serverUrl.isEmpty()) {
            tvConnectionStatus.setText("⚪ في انتظار إدخال رابط الخادم");
            return;
        }

        tvConnectionStatus.setText("🔵 جاري فحص الاتصال...");
        
        bgExecutor.execute(() -> {
            boolean reachable = pingServer(serverUrl);
            mainHandler.post(() -> {
                if (reachable) {
                    tvConnectionStatus.setText("🟢 الخادم متصل وجاهز");
                    tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_light));
                } else {
                    tvConnectionStatus.setText("🔴 تعذر الاتصال بالخادم");
                    tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_light));
                }
            });
        });
    }

    private boolean pingServer(String serverUrl) {
        try {
            java.net.URL url = new java.net.URL(serverUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================
    // 🌐 تسجيل الدخول عبر MiraApiClient
    // ==========================================
    private void attemptApiLogin() {
        String serverUrl = getText(etServerUrl);
        String username = getText(etUsername);
        String password = getText(etPassword);

        // التحقق من صحة المدخلات
        if (serverUrl.isEmpty()) {
            tilServerUrl.setError("يرجى إدخال رابط الخادم");
            shakeView(tilServerUrl);
            return;
        }
        if (username.isEmpty()) {
            tilUsername.setError("يرجى إدخال اسم المستخدم");
            shakeView(tilUsername);
            return;
        }
        if (password.length() < 4) {
            tilPassword.setError("كلمة المرور قصيرة جداً");
            shakeView(tilPassword);
            return;
        }

        // مسح الأخطاء
        tilServerUrl.setError(null);
        tilUsername.setError(null);
        tilPassword.setError(null);

        // حفظ رابط الخادم
        sessionManager.setServerUrl(serverUrl);

        showLoading(true);

        bgExecutor.execute(() -> {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            MiraApiClient.ApiResponse response = apiClient.login(username, password, deviceId);

            mainHandler.post(() -> {
                showLoading(false);

                if (response != null && response.isSuccess) {
                    handleLoginSuccess(response, username, serverUrl);
                } else {
                    String errorMsg;
                    if (response == null) {
                        errorMsg = "فشل الاتصال بالخادم - تحقق من الرابط";
                    } else {
                        errorMsg = response.optString("message", "بيانات الاعتماد غير صالحة");
                        if (response.code == 401) errorMsg = "❌ اسم المستخدم أو كلمة المرور غير صحيحة";
                        else if (response.code == 403) errorMsg = "🔒 صلاحيات غير كافية للدخول";
                    }
                    Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    shakeView(loginCard);
                }
            });
        });
    }

    /**
     * 🟢 معالجة نجاح تسجيل الدخول
     */
    private void handleLoginSuccess(MiraApiClient.ApiResponse response, String username, String serverUrl) {
        String token = response.optString("token", "");
        String roleStr = response.optString("role", "ADMIN");
        String displayName = response.optString("display_name", username);
        int userId = response.optInt("user_id", 0);

        sessionManager.createLoginSession(username, roleStr, token, serverUrl);
        sessionManager.setDisplayName(displayName);
        sessionManager.setUserId(userId);
        sessionManager.saveBiometricUser(username, roleStr);
        sessionManager.setBiometricEnabled(true);

        Toast.makeText(LoginActivity.this, "👋 مرحباً " + displayName + " ✅", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(this::navigateToMain, 400);
    }

    // ==========================================
    // 🔒 المصادقة بالبصمة
    // ==========================================
    private void setupBiometric() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            btnBiometric.setVisibility(View.GONE);
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);

        biometricPrompt = new BiometricPrompt(LoginActivity.this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    handleBiometricSuccess();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(getApplicationContext(), "خطأ: " + errString, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(getApplicationContext(), "بصمة غير متطابقة - حاول مجدداً", Toast.LENGTH_SHORT).show();
                }
            });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("تسجيل الدخول بالبصمة")
            .setSubtitle("استخدم بصمة إصبعك للدخول السريع")
            .setDescription("ضع إصبعك على مستشعر البصمة")
            .setNegativeButtonText("استخدام كلمة المرور")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();

        btnBiometric.setOnClickListener(v -> {
            if (!sessionManager.getLastBiometricUser().isEmpty() || sessionManager.isLoggedIn()) {
                biometricPrompt.authenticate(promptInfo);
            } else {
                Toast.makeText(LoginActivity.this, "يرجى تسجيل الدخول بكلمة المرور أولاً", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleBiometricSuccess() {
        String lastUser = sessionManager.getLastBiometricUser();
        String lastRole = sessionManager.getLastBiometricRole();

        if (!lastUser.isEmpty() && sessionManager.isLoggedIn()) {
            Toast.makeText(this, "👋 مرحباً بعودتك " + lastUser, Toast.LENGTH_SHORT).show();
            navigateToMain();
        } else if (!lastUser.isEmpty()) {
            sessionManager.createLoginSession(lastUser, lastRole, "BIOMETRIC_RESTORED_TOKEN", sessionManager.getServerUrl());
            Toast.makeText(this, "🔄 تمت استعادة الجلسة", Toast.LENGTH_SHORT).show();
            navigateToMain();
        } else {
            Toast.makeText(this, "يرجى تسجيل الدخول بكلمة المرور أولاً", Toast.LENGTH_LONG).show();
        }
    }

    // ==========================================
    // ⏳ عرض/إخفاء التحميل
    // ==========================================
    private void showLoading(boolean loading) {
        if (loadingContainer != null) loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnBiometric.setEnabled(!loading);
        btnLogin.setText(loading ? "" : "دخول المنظومة");
        etServerUrl.setEnabled(!loading);
        etUsername.setEnabled(!loading);
        etPassword.setEnabled(!loading);
    }

    /**
     * 🎯 تأثير اهتزاز للعنصر
     */
    private void shakeView(View view) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, "translationX", 0, 25, -25, 25, -25, 15, -15, 6, -6, 0);
        animator.setDuration(600);
        animator.start();
    }

    // ==========================================
    // 🏠 الانتقال للشاشة الرئيسية
    // ==========================================
    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.this, UHFMainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
