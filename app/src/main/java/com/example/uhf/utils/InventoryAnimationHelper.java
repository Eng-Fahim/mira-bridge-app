package com.example.uhf.utils;

import android.animation.ObjectAnimator;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.uhf.R;

/**
 * ✅ InventoryAnimationHelper
 * 
 * مسؤول عن كل الرسوم المتحركة في شاشة الجرد:
 * - Animation على الأرقام
 * - Animation على ProgressBar
 * - Animation على Status Bar
 * - احتفال عند الاكتمال
 * 
 * الميزات:
 * - آمن للاستخدام من أي خيط
 * - لا يحتوي على مراجع للـ Fragment (لا تسريبات)
 * - يمكن استخدامه من أي مكان
 */
public class InventoryAnimationHelper {

    private static final String TAG = "InventoryAnimation";
    
    private static final long NUMBER_SCALE_DURATION = 120;
    private static final long NUMBER_RETURN_DURATION = 120;
    private static final long PROGRESS_ANIMATION_DURATION = 400;
    private static final long TEXT_FADE_DURATION = 80;
    private static final long TEXT_APPEAR_DURATION = 120;
    private static final long STATUS_BAR_FLASH_DURATION = 100;
    private static final long CELEBRATE_DURATION = 250;
    
    private final boolean isArabic;
    
    public InventoryAnimationHelper(boolean isArabic) {
        this.isArabic = isArabic;
    }
    
    // ==================== Number Animations ====================
    
    /**
     * ✅ Animation على رقم عند التغيير (نبضة صغيرة)
     */
    public void animateNumberChange(TextView textView, int oldValue, int newValue) {
        if (textView == null) return;
        
        try {
            if (oldValue == newValue) {
                textView.setText(String.valueOf(newValue));
                return;
            }
            
            textView.animate()
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(NUMBER_SCALE_DURATION)
                    .withEndAction(() -> {
                        textView.setText(String.valueOf(newValue));
                        textView.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(NUMBER_RETURN_DURATION)
                                .start();
                    })
                    .start();
        } catch (Exception e) {
            Log.e(TAG, "animateNumberChange error: " + e.getMessage());
            textView.setText(String.valueOf(newValue));
        }
    }
    
    // ==================== Progress Bar Animations ====================
    
    /**
     * ✅ Animation على ProgressBar
     */
    public void animateProgressBar(ProgressBar progressBar, int from, int to) {
        if (progressBar == null) return;
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ObjectAnimator.ofInt(progressBar, "progress", from, to)
                        .setDuration(PROGRESS_ANIMATION_DURATION)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                progressBar.setProgress(to);
            }
            
            // نبض خفيف على الحاوية
            View parent = (View) progressBar.getParent();
            if (parent != null) {
                parent.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .withEndAction(() -> {
                            parent.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(150)
                                    .start();
                        })
                        .start();
            }
        } catch (Exception e) {
            Log.e(TAG, "animateProgressBar error: " + e.getMessage());
            progressBar.setProgress(to);
        }
    }
    
    // ==================== Progress Text Animations ====================
    
    /**
     * ✅ Animation على نص النسبة (fade)
     */
    public void animateProgressText(TextView textView, int progress) {
        if (textView == null) return;
        
        try {
            String newText = progress + "%";
            if (!newText.equals(textView.getText().toString())) {
                textView.animate()
                        .alpha(0.3f)
                        .setDuration(TEXT_FADE_DURATION)
                        .withEndAction(() -> {
                            textView.setText(newText);
                            textView.animate()
                                    .alpha(1f)
                                    .setDuration(TEXT_APPEAR_DURATION)
                                    .start();
                        })
                        .start();
            }
        } catch (Exception e) {
            Log.e(TAG, "animateProgressText error: " + e.getMessage());
            textView.setText(progress + "%");
        }
    }
    
    // ==================== Status Bar Animation ====================
    
    /**
     * ✅ تحديث خلفية الشريط حسب الحالة
     * 
     * @param statusBar  الـ View (LinearLayout)
     * @param isConnected  هل الجهاز متصل
     * @param isScanning  هل الجرد قيد التشغيل
     */
    public void updateStatusBarBackground(View statusBar, 
                                          boolean isConnected, 
                                          boolean isScanning) {
        if (statusBar == null) return;
        
        try {
            int bgRes;
            if (isScanning) {
                bgRes = R.drawable.bg_status_bar_scanning;
            } else if (isConnected) {
                bgRes = R.drawable.bg_status_bar_ready;
            } else {
                bgRes = R.drawable.bg_status_bar_stopped;
            }
            
            statusBar.setBackgroundResource(bgRes);
            
            // Flash animation خفيفة
            statusBar.animate()
                    .alpha(0.85f)
                    .setDuration(STATUS_BAR_FLASH_DURATION)
                    .withEndAction(() -> {
                        statusBar.animate()
                                .alpha(1f)
                                .setDuration(150)
                                .start();
                    })
                    .start();
        } catch (Exception e) {
            Log.e(TAG, "updateStatusBarBackground error: " + e.getMessage());
        }
    }
    
    // ==================== Celebration ====================
    
    /**
     * ✅ احتفال عند اكتمال الجرد
     */
    public void celebrateCompletion(TextView progressText, 
                                    ProgressBar progressBar, 
                                    TextView scannedCount) {
        try {
            // نبض كبير على النسبة
            if (progressText != null) {
                progressText.animate()
                        .scaleX(1.5f)
                        .scaleY(1.5f)
                        .setDuration(300)
                        .setInterpolator(new OvershootInterpolator(1.5f))
                        .withEndAction(() -> {
                            progressText.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(300)
                                    .start();
                        })
                        .start();
            }
            
            // وميض على ProgressBar
            if (progressBar != null) {
                progressBar.animate()
                        .alpha(0.3f)
                        .setDuration(150)
                        .withEndAction(() -> {
                            progressBar.animate()
                                    .alpha(1f)
                                    .setDuration(150)
                                    .start();
                        })
                        .start();
            }
            
            // نبض على العدد
            if (scannedCount != null) {
                scannedCount.animate()
                        .scaleX(1.4f)
                        .scaleY(1.4f)
                        .setDuration(CELEBRATE_DURATION)
                        .withEndAction(() -> {
                            scannedCount.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(CELEBRATE_DURATION)
                                    .start();
                        })
                        .start();
            }
        } catch (Exception e) {
            Log.e(TAG, "celebrateCompletion error: " + e.getMessage());
        }
    }
    
    // ==================== Item Entry Animation ====================
    
    /**
     * ✅ Animation عند ظهور عنصر جديد في القائمة
     */
    public void animateItemEntry(View itemView, int position) {
        if (itemView == null) return;
        
        try {
            itemView.setAlpha(0f);
            itemView.setTranslationY(-30f);
            itemView.setScaleX(0.9f);
            itemView.setScaleY(0.9f);
            
            itemView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        } catch (Exception e) {
            Log.e(TAG, "animateItemEntry error: " + e.getMessage());
            itemView.setAlpha(1f);
            itemView.setTranslationY(0f);
            itemView.setScaleX(1f);
            itemView.setScaleY(1f);
        }
    }
}
