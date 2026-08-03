package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.uhf.R;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executor;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private MaterialButton btnLogin, btnBiometric;
    private ProgressBar pbLoading;
    private SessionManager sessionManager;
    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);
        httpClient = new OkHttpClient();

        // إذا كانت الجلسة مسجلة مسبقاً وتوجد بيانات اعتماد، انتقل للشاشة الرئيسية
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

        // تعيين آخر رابط خادم تم حفظه
        etServerUrl.setText(sessionManager.getServerUrl());

        btnLogin.setOnClickListener(v -> attemptApiLogin());
    }

    // ==========================================
    // 🌐 الاتصال الفعلي بملف class-gate-api.php
    // ==========================================
    private void attemptApiLogin() {
        String serverUrl = etServerUrl.getText() != null ? etServerUrl.getText().toString().trim() : "";
        String username = etUsername.getText() != null ? etUsername.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

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

        showLoading(true);

        // بناء طلب الـ POST الموجه إلى class-gate-api.php
        RequestBody formBody = new FormBody.Builder()
                .add("action", "login")
                .add("username", username)
                .add("password", password)
                .build();

        // تأكد من أن المسار ينتهي بالملف المطلوب أو مسار الـ API الصحيح
        String endpoint = serverUrl.endsWith("/") ? serverUrl + "class-gate-api.php" : serverUrl + "/class-gate-api.php";

        Request request = new Request.Builder()
                .url(endpoint)
                .post(formBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(LoginActivity.this, "فشل الاتصال بالخادم: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String responseData = response.body().string();
                    runOnUiThread(() -> {
                        showLoading(false);
                        try {
                            JSONObject jsonResponse = new JSONObject(responseData);
                            boolean success = jsonResponse.optBoolean("success", false);

                            if (success) {
                                String token = jsonResponse.optString("token", "MIRA_SEC_TOKEN");
                                String roleStr = jsonResponse.optString("role", "ADMIN"); // ADMIN, POS_OPERATOR, etc.
                                
                                // حفظ الجلسة محلياً
                                sessionManager.createLoginSession(username, roleStr, token, serverUrl);
                                
                                Toast.makeText(LoginActivity.this, "تم تسجيل الدخول بنجاح", Toast.LENGTH_SHORT).show();
                                navigateToMain();
                            } else {
                                String msg = jsonResponse.optString("message", "بيانات الاعتماد غير صالحة");
                                Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_LONG).show();
                            }
                        } catch (JSONException e) {
                            Toast.makeText(LoginActivity.this, "خطأ في قراءة استجابة الخادم", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        showLoading(false);
                        Toast.makeText(LoginActivity.this, "خطأ في الاستجابة من الخادم (رمز: " + response.code() + ")", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    // ==========================================
    // 🔒 إعداد المصادقة بالبصمة (Biometric Prompt)
    // ==========================================
    private void setupBiometric() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(LoginActivity.this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                Toast.makeText(getApplicationContext(), "تم التحقق بالبصمة بنجاح!", Toast.LENGTH_SHORT).show();
                navigateToMain();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(getApplicationContext(), "خطأ في المصادقة: " + errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(getApplicationContext(), "لم يتم التعرف على البصمة، حاول مجدداً", Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("تسجيل الدخول السريع بمنظومة MIRA")
                .setSubtitle("استخدم بصمة الإصبع للوصول السريع لصلاحياتك")
                .setNegativeButtonText("إلغاء واستخدام كلمة المرور")
                .build();

        btnBiometric.setOnClickListener(v -> {
            if (sessionManager.isLoggedIn()) {
                biometricPrompt.authenticate(promptInfo);
            } else {
                Toast.makeText(this, "يرجى تسجيل الدخول بكلمة المرور لمرة واحدة أولاً لتفعيل البصمة", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showLoading(boolean loading) {
        pbLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnBiometric.setEnabled(!loading);
        btnLogin.setText(loading ? "" : "دخول المنظومة");
    }

    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.this, UHFMainActivity.class);
        startActivity(intent);
        finish();
    }
}
