package com.example.uhf.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
 
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION = 2800; // 2.8 ثانية
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable navigateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // تعيين الثيم البرمجي صراحةً لتأكيد القفز فوق الشاشة الافتراضية
        setTheme(R.style.SplashTheme);
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.splash_screen);

        executeCinematicAnimation();
        scheduleNavigation();
    }

    private void executeCinematicAnimation() {
        ImageView ivLogo = findViewById(R.id.ivLogo);
        View vGlow = findViewById(R.id.vGlow);
        TextView tvAppName = findViewById(R.id.tvAppName);
        View divider = findViewById(R.id.divider);
        TextView tvTagline = findViewById(R.id.tvTagline);

        // إعداد الموضع الأولي للتحريك التدريجي
        ivLogo.setScaleX(0.5f);
        ivLogo.setScaleY(0.5f);
        tvAppName.setTranslationY(40f);
        tvTagline.setTranslationY(30f);
        divider.setScaleX(0f);

        FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();

        // 1. دخول وتكبير الشعار + ظهور التوهج
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

        // 2. انزلاق وتلاشي اسم التطبيق
        tvAppName.animate()
                .alpha(1.0f)
                .translationY(0f)
                .setDuration(1000)
                .setStartDelay(400)
                .setInterpolator(interpolator)
                .start();

        // 3. تمدد الخط الفاصل الذهبي
        divider.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .setDuration(800)
                .setStartDelay(700)
                .setInterpolator(interpolator)
                .start();

        // 4. ظهور الشعار الفرعي
        tvTagline.animate()
                .alpha(1.0f)
                .translationY(0f)
                .setDuration(900)
                .setStartDelay(850)
                .setInterpolator(interpolator)
                .start();
    }

    private void scheduleNavigation() {
        navigateRunnable = () -> {
            if (!isFinishing() && !isDestroyed()) {
                Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        };
        mainHandler.postDelayed(navigateRunnable, SPLASH_DURATION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (navigateRunnable != null) {
            mainHandler.removeCallbacks(navigateRunnable);
        }
    }
}
