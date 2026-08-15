package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION = 3000; // 3 ثواني

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.splash_screen);

        animateLogo();
        animateAppName();
        navigateToNextScreen();
    }

    private void animateLogo() {
        ImageView ivLogo = findViewById(R.id.ivLogo);
        
        AnimationSet animationSet = new AnimationSet(true);
        
        // تكبير الشعار
        ScaleAnimation scaleAnimation = new ScaleAnimation(
            0.3f, 1.0f, 0.3f, 1.0f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        );
        scaleAnimation.setDuration(1500);
        scaleAnimation.setFillAfter(true);
        
        // ظهور تدريجي
        AlphaAnimation alphaAnimation = new AlphaAnimation(0.0f, 1.0f);
        alphaAnimation.setDuration(2000);
        alphaAnimation.setFillAfter(true);
        
        animationSet.addAnimation(scaleAnimation);
        animationSet.addAnimation(alphaAnimation);
        
        ivLogo.startAnimation(animationSet);
    }

    private void animateAppName() {
        TextView tvAppName = findViewById(R.id.tvAppName);
        
        AlphaAnimation alphaAnimation = new AlphaAnimation(0.0f, 1.0f);
        alphaAnimation.setDuration(2000);
        alphaAnimation.setStartOffset(500);
        alphaAnimation.setFillAfter(true);
        
        tvAppName.startAnimation(alphaAnimation);
    }

    private void navigateToNextScreen() {
        new Handler().postDelayed(() -> {
            // الانتقال للشاشة الرئيسية أو تسجيل الدخول
            Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
            
            // تأثير الانتقال
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }, SPLASH_DURATION);
    }
}
