package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;
import com.example.uhf.tools.SessionManager;

public class LoginActivity extends AppCompatActivity {

    private EditText etUsername, etPassword;
    private Button btnLogin;
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
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
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

        btnLogin.setEnabled(false);
        btnLogin.setText("جاري التحقق...");

        // محاكاة عملية التحقق واختيار الدور تلقائياً
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            btnLogin.setEnabled(true);
            btnLogin.setText("🔐 التحقق والدخول للنظام");

            // تحديد الدور بناءً على اسم المستخدم للتحقق من الصلاحيات
            SessionManager.Role role = SessionManager.Role.ADMIN;
            if (username.toLowerCase().contains("pos")) {
                role = SessionManager.Role.POS_OPERATOR;
            } else if (username.toLowerCase().contains("audit")) {
                role = SessionManager.Role.INVENTORY_MGR;
            }

            sessionManager.createLoginSession(username, role.name(), "MIRA_TOKEN_9988", "https://api.mira.cloud");
            Toast.makeText(LoginActivity.this, "تم تسجيل الدخول بنجاح: " + username, Toast.LENGTH_SHORT).show();

            navigateToMain();
        }, 1000);
    }

    private void navigateToMain() {
        // ✅ إصلاح Context الـ Intent هنا (استخدام LoginActivity.this بدلاً من LoginActivity.class)
        Intent intent = new Intent(LoginActivity.this, UHFMainActivity.class);
        startActivity(intent);
        finish();
    }
}
