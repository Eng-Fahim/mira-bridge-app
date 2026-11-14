package com.example.uhf.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.example.uhf.R;
import com.example.uhf.tools.SessionManager;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "SplashActivity";
    private static final long SPLASH_DURATION = 2800;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable navigateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // حماية الشاشة والأمان
       /* getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );
        */

        setContentView(R.layout.splash_screen);

        executeCinematicAnimation();
        scheduleNavigation();
    }

        private void executeCinematicAnimation() {
    ImageView ivLogo    = findViewById(R.id.ivLogo);
    View      vGlow     = findViewById(R.id.vGlow);
    TextView  tvAppName = findViewById(R.id.tvAppName);
    View      divider   = findViewById(R.id.divider);
    TextView  tvTagline = findViewById(R.id.tvTagline);

    if (ivLogo != null) {
        ivLogo.setScaleX(0.5f);
        ivLogo.setScaleY(0.5f);
        ivLogo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(1200).setInterpolator(new FastOutSlowInInterpolator()).start();
    }

    if (vGlow != null) {
        vGlow.animate().alpha(1f).setDuration(1500).setStartDelay(300).start();
    }

    if (tvAppName != null) {
        tvAppName.setTranslationY(40f);
        // ❌ لا تضع أسطر setTextColor هنا، اتركها للـ XML
        tvAppName.animate().alpha(1f).translationY(0f)
                .setDuration(1000).setStartDelay(400).setInterpolator(new FastOutSlowInInterpolator()).start();
    }

    if (divider != null) {
        divider.setScaleX(0f);
        divider.animate().alpha(1f).scaleX(1f)
                .setDuration(800).setStartDelay(700).setInterpolator(new FastOutSlowInInterpolator()).start();
    }

    if (tvTagline != null) {
        tvTagline.setTranslationY(30f);
        // ❌ لا تضع أسطر setTextColor هنا، اتركها للـ XML
        tvTagline.animate().alpha(1f).translationY(0f)
                .setDuration(900).setStartDelay(850).setInterpolator(new FastOutSlowInInterpolator()).start();
    }
}


    private void scheduleNavigation() {
        navigateRunnable = () -> {
            if (!isFinishing() && !isDestroyed()) navigateToNextScreen();
        };
        mainHandler.postDelayed(navigateRunnable, SPLASH_DURATION);
    }

    private void navigateToNextScreen() {
        Class<?> target;
        if (isLicenseActivated()) {
            Log.d(TAG, "✅ License active — → LoginActivity");
            target = LoginActivity.class;
        } else {
            Log.d(TAG, "⚠️ License NOT active — → ActivationActivity");
            target = ActivationActivity.class;
        }
        Intent intent = new Intent(SplashActivity.this, target);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ⚡ فحص صارم — يتطلب activation token + serverUrl صحيح
    // ═══════════════════════════════════════════════════════════════════════

    private boolean isLicenseActivated() {
        try {
            SessionManager sm = new SessionManager(getApplicationContext());

            String token = null;
            try {
                java.lang.reflect.Method m = sm.getClass().getMethod("getActivationToken");
                Object r = m.invoke(sm);
                if (r instanceof String) token = (String) r;
            } catch (Exception e) {
                Log.w(TAG, "getActivationToken not available: " + e.getMessage());
            }

            if (token == null || token.isEmpty()) {
                Log.d(TAG, "❌ No activation token → NOT activated");
                return false;
            }

            String serverUrl = sm.getServerUrl();
            if (serverUrl == null || serverUrl.isEmpty()) {
                Log.d(TAG, "❌ No serverUrl → NOT activated");
                return false;
            }
            if (serverUrl.contains("id.ibreg.org") || serverUrl.contains("wp-json")) {
                Log.d(TAG, "❌ serverUrl is legacy WordPress → NOT activated");
                return false;
            }
            if (serverUrl.endsWith("/api/v1") || serverUrl.endsWith("/api/v1/")) {
                Log.d(TAG, "❌ serverUrl is center URL (/api/v1), not customer URL → NOT activated");
                return false;
            }

            Log.d(TAG, "✅ Activated: token=" + token.length() + " chars, server=" + serverUrl);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "License check failed: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (navigateRunnable != null) mainHandler.removeCallbacks(navigateRunnable);
    }
}
