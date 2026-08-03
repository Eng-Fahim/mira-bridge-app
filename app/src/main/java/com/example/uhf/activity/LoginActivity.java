package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerUrl, etUsername, etPassword;
    private MaterialButton btnLogin;
    private ProgressBar pbLoading;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sessionManager = new SessionManager(this);

        // إذا كان المستخدم مسجلاً بالفعل، الانتقال فوراً للشاشة الرئيسية
        if (sessionManager.isLoggedIn()) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        initViews();
    }

    private void initViews() {
        etServerUrl = findViewById(R.id.etServerUrl);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        pbLoading = findViewById(R.id.pbLoading);

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String server = etServerUrl.getText() != null ? etServerUrl.getText().toString().trim() : "";
        String username = etUsername.getText() != null ? etUsername.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        if (username.isEmpty()) {
            etUsername.setError("يرجى إدخال اسم المستخدم");
            return;
        }

        if (password.isEmpty()) {
            etPassword.setError("يرجى إدخال كلمة المرور");
            return;
        }

        showLoading(true);

        // 💡 محاكاة عملية التحقق أو الربط مع السيرفر
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            showLoading(false);

            // تحديد الدور بناءً على اسم المستخدم للتحقق من الصلاحيات (مرحلياً)
            SessionManager.Role role = SessionManager.Role.ADMIN;
            if (username.toLowerCase().contains("pos")) {
                role = SessionManager.Role.POS_OPERATOR;
            } else if (username.toLowerCase().contains("audit")) {
                role = SessionManager.Role.INVENTORY_MGR;
            }

            sessionManager.createLoginSession(username, role.name(), "MIRA_TOKEN_9988", server);
            Toast.makeText(LoginActivity.this, "تم تسجيل الدخول بنجاح: " + username, Toast.LENGTH_SHORT).show();

            navigateToMain();
        }, 1200);
    }

    private void showLoading(boolean loading) {
        pbLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnLogin.setText(loading ? "" : "دخول المنظومة");
    }

    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.class, UHFMainActivity.class);
        startActivity(intent);
        finish();
    }
}
