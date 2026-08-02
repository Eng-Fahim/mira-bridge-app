package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;

public class LoginActivity extends AppCompatActivity {

    private EditText etUsername, etPassword;
    private Button btnLogin;
    private TextView tvRfidStatus;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1️⃣ التحقق من الجلسة السابقة
        sessionManager = new SessionManager(this);
        if (sessionManager.isLoggedIn()) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvRfidStatus = findViewById(R.id.tvRfidStatus);

        // 2️⃣ زر تسجيل الدخول اليدوي
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = etUsername.getText().toString().trim();
                String password = etPassword.getText().toString().trim();

                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(LoginActivity.this, "الرجاء إدخال بيانات الدخول", Toast.LENGTH_SHORT).show();
                    return;
                }

                // محاكاة التحقق وتحديد الصلاحية
                if (username.equalsIgnoreCase("admin")) {
                    sessionManager.createLoginSession(username, "ADMIN", "TOKEN_ADMIN_SECURE_99");
                    Toast.makeText(LoginActivity.this, "مرحباً بك (مدير النظام)", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                } else if (username.equalsIgnoreCase("mira") || username.equalsIgnoreCase("user")) {
                    sessionManager.createLoginSession(username, "INVENTORY", "TOKEN_INV_SECURE_01");
                    Toast.makeText(LoginActivity.this, "مرحباً بك (مسؤول الجرد)", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                } else {
                    Toast.makeText(LoginActivity.this, "بيانات الدخول غير صحيحة", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 3️⃣ استجابة الضغط على كرت الـ RFID بالواجهة
        View cardRfidAuth = findViewById(R.id.cardRfidAuth);
        if (cardRfidAuth != null) {
            cardRfidAuth.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    authenticateViaRfid("EMP-RFID-88210");
                }
            });
        }
    }

    // المصادقة الفورية عبر الـ RFID
    private void authenticateViaRfid(String epcTag) {
        tvRfidStatus.setText("🟢 تم قراءة بطاقة الموظف: " + epcTag);
        sessionManager.createLoginSession("Employee_" + epcTag, "INVENTORY", "TOKEN_RFID_" + epcTag);
        Toast.makeText(LoginActivity.this, "تم الدخول السريع عبر بطاقة الموظف", Toast.LENGTH_SHORT).show();
        navigateToMain();
    }

    // التقاط ضغطة زر الزناد الفيزيائي للقارئ اليدوي (Key Trigger)
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // مفاتيح الزناد الشائعة في أجهزة القارئ اليدوي (139, 280, 293...)
        if (keyCode == 139 || keyCode == 280 || keyCode == 293) {
            tvRfidStatus.setText("📡 جاري قراءة بطاقة الموظف بالـ UHF...");
            // هنا يتم استدعاء مسح الـ RFID الفعلي من SDK القارئ
            authenticateViaRfid("CARD-TAG-9901");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.this, UHFMainActivity.class);
        startActivity(intent);
        finish();
    }
}
