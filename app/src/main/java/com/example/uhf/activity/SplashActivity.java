package com.example.uhf.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.example.uhf.R;
import com.example.uhf.license.LicenseManager;
import com.example.uhf.tools.SessionManager;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "SplashActivity";
    private static final long SPLASH_DURATION = 2800; // 2.8 ثانية

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable navigateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.SplashTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.splash_screen);

        executeCinematicAnimation();
        scheduleNavigation();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Animation (كما هو — لم يُلمس)
    // ═══════════════════════════════════════════════════════════════════════

    private void executeCinematicAnimation() {
        ImageView ivLogo = findViewById(R.id.ivLogo);
        View      vGlow  = findViewById(R.id.vGlow);
        TextView  tvAppName = findViewById(R.id.tvAppName);
        View      divider = findViewById(R.id.divider);
        TextView  tvTagline = findViewById(R.id.tvTagline);

        ivLogo.setScaleX(0.5f);
        ivLogo.setScaleY(0.5f);
        tvAppName.setTranslationY(40f);
        tvTagline.setTranslationY(30f);
        divider.setScaleX(0f);

        FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();

        ivLogo.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(1200)
                .setInterpolator(interpolator)
                .start();

        vGlow.animate()
                .alpha(1.0f)
                .setDuration(1500)
                .setStartDelay(300)
                .start();

        tvAppName.animate()
                .alpha(1.0f)
                .translationY(0f)
                .setDuration(1000)
                .setStartDelay(400)
                .setInterpolator(interpolator)
                .start();

        divider.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .setDuration(800)
                .setStartDelay(700)
                .setInterpolator(interpolator)
                .start();

        tvTagline.animate()
                .alpha(1.0f)
                .translationY(0f)
                .setDuration(900)
                .setStartDelay(850)
                .setInterpolator(interpolator)
                .start();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ⚡ Navigation — الآن يفحص الترخيص قبل اختيار الشاشة
    // ═══════════════════════════════════════════════════════════════════════

    private void scheduleNavigation() {
        navigateRunnable = () -> {
            if (!isFinishing() && !isDestroyed()) {
                navigateToNextScreen();
            }
        };
        mainHandler.postDelayed(navigateRunnable, SPLASH_DURATION);
    }

    private void navigateToNextScreen() {
        Class<?> target;
        if (isLicenseActivated()) {
            Log.d(TAG, "✅ License active — going to LoginActivity");
            target = LoginActivity.class;
        } else {
            Log.d(TAG, "⚠️ License NOT active — going to ActivationActivity");
            target = ActivationActivity.class;
        }

        Intent intent = new Intent(SplashActivity.this, target);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  فحص حالة الترخيص — يستخدم LicenseManager أولاً، ثم SessionManager
    // ═══════════════════════════════════════════════════════════════════════

    private boolean isLicenseActivated() {
        // ─── المحاولة 1: LicenseManager.isActivated(Context) ───
        try {
            java.lang.reflect.Method m = LicenseManager.class.getMethod(
                    "isActivated", Context.class);
            Object result = m.invoke(null, getApplicationContext());
            if (result instanceof Boolean) {
                Log.d(TAG, "isActivated(Context) → " + result);
                return (Boolean) result;
            }
        } catch (NoSuchMethodException ignored) {
            // ليس موجوداً — نجرّب التوقيع الآخر
        } catch (Exception e) {
            Log.w(TAG, "isActivated(Context) call failed: " + e.getMessage());
        }

        // ─── المحاولة 2: LicenseManager.isActivated() ───
        try {
            java.lang.reflect.Method m = LicenseManager.class.getMethod("isActivated");
            Object result = m.invoke(null);
            if (result instanceof Boolean) {
                Log.d(TAG, "isActivated() → " + result);
                return (Boolean) result;
            }
        } catch (NoSuchMethodException ignored) {
            // ليس موجوداً
        } catch (Exception e) {
            Log.w(TAG, "isActivated() call failed: " + e.getMessage());
        }

        // ─── المحاولة 3: LicenseManager.isLicenseValid(Context) ───
        try {
            java.lang.reflect.Method m = LicenseManager.class.getMethod(
                    "isLicenseValid", Context.class);
            Object result = m.invoke(null, getApplicationContext());
            if (result instanceof Boolean) {
                Log.d(TAG, "isLicenseValid(Context) → " + result);
                return (Boolean) result;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "isLicenseValid(Context) call failed: " + e.getMessage());
        }

        // ─── Fallback: فحص SessionManager عن تفعيل محفوظ ───
        try {
            SessionManager sm = new SessionManager(getApplicationContext());

            // محاولة الحصول على activation token (إن وُجد)
            String activationToken = null;
            try {
                java.lang.reflect.Method m = sm.getClass().getMethod("getActivationToken");
                Object r = m.invoke(sm);
                if (r instanceof String) activationToken = (String) r;
            } catch (Exception ignored) {}

            if (activationToken != null && !activationToken.isEmpty()) {
                Log.d(TAG, "SessionManager has activationToken → activated");
                return true;
            }

            // محاولة الحصول على serverUrl (دل على تفعيل سابق)
            String serverUrl = sm.getServerUrl();
            if (serverUrl != null && !serverUrl.isEmpty()
                    && !serverUrl.contains("id.ibreg.org")) {  // ← ليس WP قديم
                Log.d(TAG, "SessionManager has serverUrl → activated (legacy)");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "SessionManager check failed: " + e.getMessage());
        }

        Log.d(TAG, "No activation evidence — treating as NOT activated");
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (navigateRunnable != null) {
            mainHandler.removeCallbacks(navigateRunnable);
        }
    }
}
