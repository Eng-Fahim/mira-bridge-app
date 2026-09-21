package com.example.uhf.manager.inventory;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.View;

import com.example.uhf.utils.SoundManager;

/**
 * ✅ InventoryFeedbackHelper v1.0
 * 
 * مسؤول عن:
 * - الاهتزاز (Vibrator) حسب نوع المسح
 * - الوميض البصري (Flash Overlay)
 * - Debounce لمنع الإفراط
 * 
 * يوفر على Fragment:
 * - 80 سطر
 * - تعقيد API 31+
 * - منطق Debounce
 */
public class InventoryFeedbackHelper {

    private static final String TAG = "InventoryFeedback";

    // ✅ Debounce constants
    private static final long HAPTIC_DEBOUNCE_MS = 150;
    private static final long FLASH_DEBOUNCE_MS = 120;

    // ✅ Debounce state
    private long lastHapticTime = 0;
    private long lastFlashTime = 0;
    private SoundManager.FeedbackType lastFeedbackType = null;

    // ✅ Vibrator
    private Vibrator vibrator;

    // ✅ View
    private View flashOverlay;

    // ==================== Constructor ====================

    public InventoryFeedbackHelper(Context context) {
        initVibrator(context);
    }

    // ==================== View Binding ====================

    /**
     * ✅ ربط View الوميض (يُستدعى من initViews)
     */
    public void setFlashOverlay(View flashOverlay) {
        this.flashOverlay = flashOverlay;
    }

    // ==================== Vibrator ====================

    /**
     * تهيئة Vibrator (متوافق مع API 31+)
     */
    private void initVibrator(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager)
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator)
                        context.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception e) {
            Log.e(TAG, "initVibrator error", e);
            vibrator = null;
        }
    }

    /**
     * ✅ اهتزاز حسب النوع (مع Debounce)
     */
    public void hapticFeedback(SoundManager.FeedbackType type) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (type == null) return;

        // ✅ Debounce: لا تكرر نفس النوع خلال 150ms
        long now = System.currentTimeMillis();
        if (now - lastHapticTime < HAPTIC_DEBOUNCE_MS && type == lastFeedbackType) {
            return;
        }
        lastHapticTime = now;
        lastFeedbackType = type;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                switch (type) {
                    case KNOWN_ITEM:
                        vibrator.vibrate(VibrationEffect.createOneShot(
                                40, VibrationEffect.DEFAULT_AMPLITUDE));
                        break;
                    case DUPLICATE_ITEM:
                        vibrator.vibrate(VibrationEffect.createWaveform(
                                new long[]{0, 25, 40, 25}, -1));
                        break;
                    case UNKNOWN_ITEM:
                        vibrator.vibrate(VibrationEffect.createOneShot(150, 255));
                        break;
                    case COMPLETED:
                        vibrator.vibrate(VibrationEffect.createWaveform(
                                new long[]{0, 50, 40, 50, 40, 100}, -1));
                        break;
                    case ERROR:
                        vibrator.vibrate(VibrationEffect.createWaveform(
                                new long[]{0, 200, 80, 200}, -1));
                        break;
                }
            } else {
                vibrator.vibrate(40);
            }
        } catch (Exception e) {
            Log.e(TAG, "hapticFeedback error", e);
        }
    }

    // ==================== Flash ====================

    /**
     * ✅ وميض ملون (مع Debounce)
     */
    public void flash(int colorInt) {
        if (flashOverlay == null) return;

        // ✅ Debounce: لا تكرر الوميض خلال 120ms
        long now = System.currentTimeMillis();
        if (now - lastFlashTime < FLASH_DEBOUNCE_MS) {
            return;
        }
        lastFlashTime = now;

        try {
            flashOverlay.setBackgroundColor(colorInt);
            flashOverlay.setAlpha(0.6f);

            android.animation.ObjectAnimator anim =
                    android.animation.ObjectAnimator.ofFloat(flashOverlay, "alpha", 0.6f, 0f);
            anim.setDuration(280);
            anim.start();
        } catch (Exception e) {
            Log.e(TAG, "flash error", e);
        }
    }

    /**
     * ✅ وميض حسب نوع المسح (اختصار)
     */
    public void flashByFeedbackType(SoundManager.FeedbackType type) {
        if (type == null) return;

        switch (type) {
            case KNOWN_ITEM:
                flash(0x8816A34A);  // أخضر
                break;
            case UNKNOWN_ITEM:
                flash(0x88DC2626);  // أحمر
                break;
            case DUPLICATE_ITEM:
                flash(0x883B82F6);  // أزرق
                break;
            case COMPLETED:
                flash(0x88FCD34D);  // ذهبي
                break;
            case ERROR:
                flash(0x88EF4444);  // أحمر قوي
                break;
        }
    }

    /**
     * ✅ اهتزاز + وميض معاً (اختصار)
     */
    public void feedback(SoundManager.FeedbackType type) {
        hapticFeedback(type);
        flashByFeedbackType(type);
    }

    // ==================== Reset ====================

    /**
     * ✅ تصفير Debounce (يُستدعى عند بدء/إيقاف الجرد)
     */
    public void resetDebounce() {
        lastHapticTime = 0;
        lastFlashTime = 0;
        lastFeedbackType = null;
    }

    // ==================== Cleanup ====================

    /**
     * ✅ تنظيف
     */
    public void cleanup() {
        vibrator = null;
        flashOverlay = null;
        lastFeedbackType = null;
    }
}
