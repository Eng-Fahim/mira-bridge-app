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
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1️⃣ ربط الواجهة أولاً وقبل أي عمليات أخرى
        setContentView(R.layout.activity_login);

        // 2️⃣ تهيئة الجلسة
        sessionManager = new SessionManager(this);
        if (sessionManager.isLoggedIn()) {
            navigateToMain();
            return;
        }

        // 3️⃣ ربط العناصر بحذر
        try {
            etUsername = findViewById(R.id.etUsername);
            etPassword = findViewById(R.id.etPassword);
            btnLogin = findViewById(R.id.btnLogin);
            tvRfidStatus = findViewById(R.id.tvRfidStatus);

            if (btnLogin != null) {
                btnLogin.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String username = etUsername != null ? etUsername.getText().toString().trim() : "";
                        
                        if (username.equalsIgnoreCase("admin")) {
                            sessionManager.createLoginSession("admin", "ADMIN", "TOKEN_ADMIN");
                        } else {
                            sessionManager.createLoginSession("user", "INVENTORY", "TOKEN_INV");
                        }
                        
                        Toast.makeText(LoginActivity.this, "تم تسجيل الدخول بنجاح", Toast.LENGTH_SHORT).show();
                        navigateToMain();
                    }
                });
            }

            View cardRfidAuth = findViewById(R.id.cardRfidAuth);
            if (cardRfidAuth != null) {
                cardRfidAuth.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sessionManager.createLoginSession("Employee_RFID", "INVENTORY", "TOKEN_RFID");
                        navigateToMain();
                    }
                });
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void navigateToMain() {
        Intent intent = new Intent(LoginActivity.this, UHFMainActivity.class);
        startActivity(intent);
        finish();
    }
}
