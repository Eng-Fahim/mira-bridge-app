package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private MaterialButton btnLogin, btnBiometric;
    private ProgressBar pbLoading;
    private View loadingContainer;
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
    }

    private void initViews() {
        etServerUrl = findViewById(R.id.etServerUrl);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnBiometric = findViewById(R.id.btnBiometric);
        pbLoading = findViewById(R.id.pbLoading);
        loadingContainer = findViewById(R.id.loadingContainer);

        // استرجاع آخر رابط خادم محفوظ
        String savedUrl = sessionManager.getServerUrl();
        if (!savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
        }

        // تهيئة عميل API بالرابط المحفوظ
        apiClient = MiraApiClient.getInstance(this);

        btnLogin.setOnClickListener(v -> attemptApiLogin());
    }

    // ==========================================
    // 🌐 تسجيل الدخول عبر MiraApiClient المركزي
    // ==========================================
    private void attemptApiLogin() {
        String serverUrl = etServerUrl.getText() != null ? etServerUrl.getText().toString().trim() : "";
        String username = etUsername.getText() != null ? etUsername.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        // التحقق من صحة المدخلات
        if (serverUrl.isEmpty()) {
            etServerUrl.setError("يرجى إدخال رابط الخادم");
            return;
        }
        if (username.isEmpty()) {
            etUsername.setError("يرجى إدخال اسم المستخدم");
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("يرجى إدخال كلمة المرور");
            return;
        }

        // حفظ رابط الخادم
        sessionManager.setServerUrl(serverUrl);

        showLoading(true);

        // تنفيذ الطلب في خلفية منفصلة
        bgExecutor.execute(() -> {
            try {
                // بناء JSON للمصادقة
                JSONObject authPayload = new JSONObject();
                authPayload.put("action", "login");
                authPayload.put("username", username);
                authPayload.put("password", password);
                authPayload.put("device_id", android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));

                // استخدام ApiClient لإرسال الطلب
                // ملاحظة: نحتاج إضافة دالة authenticate في MiraApiClient أو استخدام post مباشرة
                MiraApiClient.ApiResponse response = authenticateWithApi(authPayload);

                mainHandler.post(() -> {
                    showLoading(false);

                    if (response == null) {
                        Toast.makeText(LoginActivity.this, 
                            "فشل الاتصال بالخادم - تحقق من الرابط", 
                            Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (response.isSuccess) {
                        handleLoginSuccess(response, username, serverUrl);
                    } else {
                        String errorMsg = response.optString("message", "بيانات الاعتماد غير صالحة");
                        if (response.code == 401) {
                            errorMsg = "اسم المستخدم أو كلمة المرور غير صحيحة";
                        } else if (response.code == 403) {
                            errorMsg = "صلاحيات غير كافية للدخول";
                        } else if (response.code == 0 || response.code == -1) {
                            errorMsg = "تعذر الاتصال بالخادم: " + response.error;
                        }
                        Toast.makeText(LoginActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(LoginActivity.this, 
                        "خطأ: " + e.getMessage(), 
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * 🟢 دالة مساعدة للمصادقة عبر API
     * تستخدم MiraApiClient مباشرة
     */
    private MiraApiClient.ApiResponse authenticateWithApi(JSONObject payload) {
        try {
            // استخدام authorize endpoint مع context=login
            String epc = payload.optString("username", "");
            JSONObject extraParams = new JSONObject();
            extraParams.put("password", payload.optString("password", ""));
            extraParams.put("action", "login");
            extraParams.put("device_id", payload.optString("device_id", ""));
            
            return apiClient.authorize(epc, "login", extraParams);
        } catch (Exception e) {
            return new MiraApiClient.ApiResponse(-1, null, e.getMessage());
        }
    }

    /**
     * 🟢 معالجة نجاح تسجيل الدخول
     */
    private void handleLoginSuccess(MiraApiClient.ApiResponse response, String username, String serverUrl) {
        try {
            JSONObject data = response.data;
            
            String token = data != null ? data.optString("token", 
                data.optString("auth_token", "MIRA_SEC_TOKEN")) : "MIRA_SEC_TOKEN";
            
            String roleStr = data != null ? data.optString("role", 
                data.optString("user_role", "ADMIN")) : "ADMIN";
            
            String displayName = data != null ? data.optString("display_name", username) : username;
            
            // حفظ الجلسة محلياً
            sessionManager.createLoginSession(username, roleStr, token, serverUrl);
            sessionManager.setDisplayName(displayName);
            
            // حفظ بيانات البصمة للاستخدام المستقبلي
            sessionManager.saveBiometricUser(username, roleStr);
            sessionManager.setBiometricEnabled(true);
            
            Toast.makeText(LoginActivity.this, 
                "مرحباً " + displayName + " ✅", 
                Toast.LENGTH_SHORT).show();
            
            // انتقال متأخر قليلاً لرؤية الرسالة
            mainHandler.postDelayed(this::navigateToMain, 500);
            
        } catch (Exception e) {
            Toast.makeText(LoginActivity.this, 
                "تم الدخول ولكن حدث خطأ في حفظ البيانات", 
                Toast.LENGTH_SHORT).show();
            navigateToMain();
        }
    }

    // ==========================================
    // 🔒 إعداد المصادقة بالبصمة (Biometric Prompt)
    // ==========================================
    private void setupBiometric() {
        // التحقق من توفر البصمة
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // البصمة غير متاحة - إخفاء الزر أو تعطيله
            btnBiometric.setEnabled(false);
            btnBiometric.setAlpha(0.4f);
            
            String reason;
            switch (canAuth) {
                case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                    reason = "الجهاز لا يدعم البصمة";
                    break;
                case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                    reason = "مستشعر البصمة غير متاح حالياً";
                    break;
                case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                    reason = "لم يتم تسجيل بصمة على الجهاز";
                    break;
                default:
                    reason = "البصمة غير مدعومة";
            }
            btnBiometric.setText(reason);
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        
        biometricPrompt = new BiometricPrompt(LoginActivity.this, executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    
                    // استرجاع بيانات آخر مستخدم سجل دخول
                    String lastUser = sessionManager.getLastBiometricUser();
                    String lastRole = sessionManager.getLastBiometricRole();
                    
                    if (!lastUser.isEmpty() && sessionManager.isLoggedIn()) {
                        Toast.makeText(getApplicationContext(), 
                            "مرحباً بعودتك " + lastUser + " 👋", 
                            Toast.LENGTH_SHORT).show();
                        navigateToMain();
                    } else if (!lastUser.isEmpty()) {
                        // المستخدم مسجل للبصمة لكن الجلسة انتهت - نحتاج إعادة تفعيل الجلسة
                        sessionManager.createLoginSession(
                            lastUser, lastRole, 
                            "BIOMETRIC_RESTORED_TOKEN", 
                            sessionManager.getServerUrl()
                        );
                        Toast.makeText(getApplicationContext(), 
                            "تمت استعادة الجلسة - مرحباً " + lastUser, 
                            Toast.LENGTH_SHORT).show();
                        navigateToMain();
                    } else {
                        Toast.makeText(getApplicationContext(), 
                            "يرجى تسجيل الدخول بكلمة المرور أولاً لتفعيل البصمة", 
                            Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    // لا نظهر رسالة خطأ إذا ضغط المستخدم "إلغاء" (errorCode 13 = USER_CANCELED)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(getApplicationContext(), 
                            "خطأ في المصادقة: " + errString, 
                            Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(getApplicationContext(), 
                        "لم يتم التعرف على البصمة، حاول مجدداً", 
                        Toast.LENGTH_SHORT).show();
                }
            });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("تسجيل الدخول السريع")
            .setSubtitle("استخدم بصمة الإصبع للوصول إلى MIRA")
            .setDescription("تأكيد الهوية للمصادقة الآمنة")
            .setNegativeButtonText("استخدام كلمة المرور")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build();

        btnBiometric.setOnClickListener(v -> {
            // التحقق من وجود بيانات بيومترية سابقة
            if (!sessionManager.getLastBiometricUser().isEmpty() || sessionManager.isLoggedIn()) {
                biometricPrompt.authenticate(promptInfo);
            } else {
                Toast.makeText(LoginActivity.this, 
                    "يرجى تسجيل الدخول بكلمة المرور مرة واحدة أولاً لتفعيل البصمة", 
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    // ==========================================
    // ⏳ عرض/إخفاء شاشة التحميل
    // ==========================================
    private void showLoading(boolean loading) {
        if (loadingContainer != null) {
            loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        pbLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnBiometric.setEnabled(!loading);
        
        if (loading) {
            btnLogin.setText("");
        } else {
            btnLogin.setText("دخول المنظومة");
        }
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
