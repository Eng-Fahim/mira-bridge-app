package com.example.uhf.activity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.example.uhf.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final long MIN_SPLASH_DURATION = 3200; // 3.2 ثانية لتجربة سينمائية مكتملة
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor();
    
    private Runnable navigateRunnable;
    private long startTime;
    private boolean isAppReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // تفعيل وضع الشاشة الكاملة السلس (Immersive Edge-to-Edge)
        enableImmersiveMode();
        
        setContentView(R.layout.splash_screen);

        startTime = System.currentTimeMillis();

        // البدء بالتحريك البصري والتهيئة البرمجية بالتوازي
        executeCinematicSequence();
        performAsyncInitialization();
    }

    private void enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void executeCinematicSequence() {
        ImageView ivLogo = findViewById(R.id.ivLogo);
        View vGlow = findViewById(R.id.vGlow);
        TextView tvAppName = findViewById(R.id.tvAppName);
        View divider = findViewById(R.id.divider);
        TextView tvTagline = findViewById(R.id.tvTagline);

        // 1. التهيئية الأولية للخصائص قبل الأنيميشن
        ivLogo.setAlpha(0f);
        ivLogo.setScaleX(0.3f);
        ivLogo.setScaleY(0.3f);

        vGlow.setAlpha(0f);
        vGlow.setScaleX(0.5f);
        vGlow.setScaleY(0.5f);

        tvAppName.setAlpha(0f);
        tvAppName.setTranslationY(60f);

        divider.setAlpha(0f);
        divider.setScaleX(0f);

        tvTagline.setAlpha(0f);
        tvTagline.setTranslationY(40f);

        FastOutSlowInInterpolator fastOutSlow = new FastOutSlowInInterpolator();
        OvershootInterpolator overshoot = new OvershootInterpolator(1.2f);

        // 2. بناء تسلسل التحريك عبر AnimatorSet لضمان التزامن الدقيق
        
        // ظهور الشعار مع انبعاج ناعم (Overshoot Effect)
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(ivLogo, View.ALPHA, 0f, 1f).setDuration(1000);
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(ivLogo, View.SCALE_X, 0.3f, 1.05f, 1.0f).setDuration(1200);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(ivLogo, View.SCALE_Y, 0.3f, 1.05f, 1.0f).setDuration(1200);
        logoScaleX.setInterpolator(overshoot);
        logoScaleY.setInterpolator(overshoot);

        // إشعاع التوهج الفوري خلف الشعار
        ObjectAnimator glowAlpha = ObjectAnimator.ofFloat(vGlow, View.ALPHA, 0f, 0.8f, 0.4f).setDuration(1400);
        ObjectAnimator glowScaleX = ObjectAnimator.ofFloat(vGlow, View.SCALE_X, 0.5f, 1.2f).setDuration(1400);
        ObjectAnimator glowScaleY = ObjectAnimator.ofFloat(vGlow, View.SCALE_Y, 0.5f, 1.2f).setDuration(1400);

        // انزلاق اسم التطبيق
        ObjectAnimator nameAlpha = ObjectAnimator.ofFloat(tvAppName, View.ALPHA, 0f, 1f).setDuration(800);
        ObjectAnimator nameTransY = ObjectAnimator.ofFloat(tvAppName, View.TRANSLATION_Y, 60f, 0f).setDuration(800);
        nameTransY.setInterpolator(fastOutSlow);

        // تمدد الخط الفاصل
        ObjectAnimator divAlpha = ObjectAnimator.ofFloat(divider, View.ALPHA, 0f, 1f).setDuration(600);
        ObjectAnimator divScaleX = ObjectAnimator.ofFloat(divider, View.SCALE_X, 0f, 1f).setDuration(700);
        divScaleX.setInterpolator(fastOutSlow);

        // ظهور الشعار الفرعي
        ObjectAnimator tagAlpha = ObjectAnimator.ofFloat(tvTagline, View.ALPHA, 0f, 1f).setDuration(800);
        ObjectAnimator tagTransY = ObjectAnimator.ofFloat(tvTagline, View.TRANSLATION_Y, 40f, 0f).setDuration(800);
        tagTransY.setInterpolator(fastOutSlow);

        // تجميع وتحفيز الأنيميشن المتدرج
        AnimatorSet primarySet = new AnimatorSet();
        primarySet.playTogether(logoAlpha, logoScaleX, logoScaleY, glowAlpha, glowScaleX, glowScaleY);

        AnimatorSet secondarySet = new AnimatorSet();
        secondarySet.playTogether(nameAlpha, nameTransY, divAlpha, divScaleX);

        AnimatorSet masterSet = new AnimatorSet();
        masterSet.playSequentially(primarySet, secondarySet, tagAlpha);
        
        // ربط حركات التوهج وانزلاق الشعار الفرعي
        masterSet.play(tagAlpha).with(tagTransY);
        masterSet.start();

        // 3. تأثير Pulse متكرر خفيف للشعار والتوهج لإعطاء حيوية أثناء الانتظار
        masterSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!isFinishing() && !isDestroyed()) {
                    startSubtlePulseAnimation(ivLogo, vGlow);
                }
            }
        });
    }

    private void startSubtlePulseAnimation(View logo, View glow) {
        ObjectAnimator pulseLogoX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 1.0f, 1.03f, 1.0f);
        ObjectAnimator pulseLogoY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1.0f, 1.03f, 1.0f);
        pulseLogoX.setRepeatCount(ObjectAnimator.INFINITE);
        pulseLogoY.setRepeatCount(ObjectAnimator.INFINITE);
        pulseLogoX.setDuration(1500);
        pulseLogoY.setDuration(1500);

        ObjectAnimator pulseGlowAlpha = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.4f, 0.7f, 0.4f);
        pulseGlowAlpha.setRepeatCount(ObjectAnimator.INFINITE);
        pulseGlowAlpha.setDuration(1500);

        AnimatorSet pulseSet = new AnimatorSet();
        pulseSet.playTogether(pulseLogoX, pulseLogoY, pulseGlowAlpha);
        pulseSet.start();
    }

    /**
     * تنفيذ أي عمليات تهيئة خلفية (مثل تهيئة SDK الـ UHF أو الـ Database) 
     * لعدم إضاعة وقت شاشة الـ Splash بدون فائدة.
     */
    private void performAsyncInitialization() {
        initExecutor.execute(() -> {
            // قم بوضع أي تهيئة كـ (AppDatabase.getInstance, UHF Reader Init, etc.) هنا
            try {
                Thread.sleep(500); // محاكاة وقت التحميل الخلفي
            } catch (InterruptedException ignored) {}

            isAppReady = true;
            mainHandler.post(this::checkAndNavigate);
        });
    }

    private void checkAndNavigate() {
        long elapsedTime = System.currentTimeMillis() - startTime;
        long remainingTime = MIN_SPLASH_DURATION - elapsedTime;

        if (remainingTime > 0) {
            navigateRunnable = this::navigateToLogin;
            mainHandler.postDelayed(navigateRunnable, remainingTime);
        } else {
            navigateToLogin();
        }
    }

    private void navigateToLogin() {
        if (!isFinishing() && !isDestroyed()) {
            Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
            startActivity(intent);
            
            // انتقال سينمائي سلس ناعم (Fade In/Out مع إغلاق النشاط)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (navigateRunnable != null) {
            mainHandler.removeCallbacks(navigateRunnable);
        }
        initExecutor.shutdown();
    }
}
