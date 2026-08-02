package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // ربط عناصر واجهة المستخدم (XML) بالبرمجة
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvRfidStatus = findViewById(R.id.tvRfidStatus);

        // 1️⃣ حدث زر تسجيل الدخول اليدوي
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = etUsername.getText().toString().trim();
                String password = etPassword.getText().toString().trim();

                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(LoginActivity.INSTANCE != null ? LoginActivity.this : LoginActivity.this, 
                            "الرجاء إدخال اسم المستخدم وكلمة المرور", Toast.LENGTH_SHORT).show();
                    return;
                }

                // محاكاة التحقق من بيانات الموظف (يمكن ربطها لاحقاً بقاعدة بيانات السيرفر أو Odoo)
                if (username.equals("admin") || username.equals("mira")) {
                    Toast.makeText(LoginActivity.this, "تم تسجيل الدخول بنجاح", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                } else {
                    Toast.makeText(LoginActivity.this, "بيانات الدخول غير صحيحة", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 2️⃣ محاكاة الاستماع لبطاقة الـ RFID عبر القارئ اليدوي عند الضغط على بطاقة الـ RFID في الواجهة
        View cardRfidAuth = findViewById(R.id.cardRfidAuth);
        if (cardRfidAuth != null) {
            cardRfidAuth.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tvRfidStatus.setText("🟢 تم استشعار بطاقة الموظف بنجاح!");
                    Toast.makeText(LoginActivity.this, "تم المصادقة عبر بطاقة الـ RFID", Toast.LENGTH_SHORT).show();
                    
                    // الانتقال المباشر للنظام الرئيسي
                    navigateToMain();
                }
            });
        }
    }

    // دالة الانتقال إلى صفحة الجرد والمخزن الرئيسية
    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.this, UHFMainActivity.class);
        startActivity(intent);
        finish(); // إغلاق صفحة الدخول حتى لا يعود إليها المستخدم بزر الخلف
    }
}
