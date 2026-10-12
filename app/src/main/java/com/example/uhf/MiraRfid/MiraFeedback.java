package com.example.uhf.MiraRfid;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraFeedback v1.0 — إدارة الأصوات والاهتزاز في MIRA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * - Singleton آمن للخيوط
 * - يعتمد على MiraReaderSettings للقراءة التلقائية للإعدادات
 * - نغمات جاهزة: بيب قصير، مزدوج، طويل، كليك، نجاح، خطأ
 * - اهتزاز بأنماط مختلفة (يعمل من API 26+)
 * - لا يسبب تسريبات (يُحرر تلقائياً)
 */
public class MiraFeedback {

    private static final String TAG = "MiraFeedback";
    private static volatile MiraFeedback instance;

    private final Context context;
    private final MiraReaderSettings settings;
    private ToneGenerator toneGen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTagAt = 0L;

    private MiraFeedback(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.settings = MiraReaderSettings.getInstance(ctx);
        try {
            int vol = (int) (settings.getSoundVolume() * 1.0); // 0-100
            toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, Math.max(10, vol));
        } catch (Exception e) {
            Log.e(TAG, "ToneGenerator init failed", e);
        }
    }

    public static MiraFeedback getInstance(Context ctx) {
        if (instance == null) {
            synchronized (MiraFeedback.class) {
                if (instance == null) instance = new MiraFeedback(ctx);
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════════
    //  🔊 Public API — أحداث جاهزة
    // ═══════════════════════════════════════════════════════════════

    /** وسم جديد تم قراءته (غير مكرر) */
    public void onNewTag() {
        if (!settings.isSoundEnabled()) return;
        playTone(settings.getToneNewTag());
        vibrate(settings.getVibrateMs());
    }

    /** وسم مكرر (تم رفضه بسبب فلترة التكرار) */
    public void onDuplicateTag() {
        if (!settings.isSoundEnabled()) return;
        playTone(settings.getToneDuplicate());
    }

    /** نجاح عملية (بيع، كتابة، إلخ) */
    public void onSuccess() {
        if (!settings.isSoundEnabled()) return;
        playTone(settings.getToneSuccess());
        vibrate(settings.getVibrateMs() * 2);
    }

    /** خطأ (فشل قراءة، فشل كتابة، إلخ) */
    public void onError() {
        if (!settings.isSoundEnabled()) return;
        playTone(settings.getToneError());
        vibratePattern(new long[]{0, 80, 60, 80});
    }

    /** بدء الجرد */
    public void onStartInventory() {
        if (!settings.isSoundEnabled()) return;
        playTone(MiraReaderSettings.TONE_BEEP_SHORT);
        vibrate(30);
    }

    /** انتهاء الجرد */
    public void onStopInventory() {
        if (!settings.isSoundEnabled()) return;
        playTone(MiraReaderSettings.TONE_BEEP_DOUBLE);
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎵 تشغيل النغمات — Core
    // ═══════════════════════════════════════════════════════════════
    public void playTone(int toneType) {
        if (toneGen == null) return;
        switch (toneType) {
            case MiraReaderSettings.TONE_BEEP_SHORT:
                safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60);
                break;
            case MiraReaderSettings.TONE_BEEP_DOUBLE:
                safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60);
                handler.postDelayed(() -> safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60), 100);
                break;
            case MiraReaderSettings.TONE_BEEP_LONG:
                safeStartTone(ToneGenerator.TONE_PROP_BEEP2, 300);
                break;
            case MiraReaderSettings.TONE_CLICK:
                safeStartTone(ToneGenerator.TONE_PROP_ACK, 40);
                break;
            case MiraReaderSettings.TONE_SUCCESS:
                safeStartTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
                break;
            case MiraReaderSettings.TONE_ERROR:
                safeStartTone(ToneGenerator.TONE_SUP_ERROR, 400);
                break;
            case MiraReaderSettings.TONE_NONE:
            default:
                // صامت
        }
    }

    private void safeStartTone(int tone, int durationMs) {
        try {
            toneGen.startTone(tone, durationMs);
        } catch (Exception e) {
            Log.e(TAG, "startTone failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  📳 الاهتزاز
    // ═══════════════════════════════════════════════════════════════
    public void vibrate(int ms) {
        if (!settings.isVibrateEnabled()) return;
        try {
            Vibrator v = getVibrator();
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception e) {
            Log.e(TAG, "vibrate failed", e);
        }
    }

    public void vibratePattern(long[] pattern) {
        if (!settings.isVibrateEnabled()) return;
        try {
            Vibrator v = getVibrator();
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Exception e) {
            Log.e(TAG, "vibratePattern failed", e);
        }
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager)
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        } else {
            return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  🚫 فلترة التكرار — Duplicate Filter
    // ═══════════════════════════════════════════════════════════════
    /**
     * @return true إذا كان الوسم جديداً (يجب معالجته)
     *         false إذا كان مكرراً (يجب تجاهله)
     */
    public boolean shouldAcceptTag(String epc) {
        if (!settings.isFilterDuplicates()) return true;
        long now = System.currentTimeMillis();
        if (now - lastTagAt < settings.getDuplicateFilterMs()) {
            onDuplicateTag();
            return false;
        }
        lastTagAt = now;
        return true;
    }

    /** إعادة تعيين فلتر التكرار (يُستدعى عند بدء جلسة جديدة). */
    public void resetDuplicateFilter() {
        lastTagAt = 0L;
    }

    // ═══════════════════════════════════════════════════════════════
    //  🧹 تحرير الموارد
    // ═══════════════════════════════════════════════════════════════
    public void release() {
        try {
            if (toneGen != null) {
                toneGen.release();
                toneGen = null;
            }
        } catch (Exception ignored) {}
    }
}
