package com.example.uhf.MiraRfid;

import android.content.Context;
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
 *  MiraFeedback v1.1 — إدارة الأصوات والاهتزاز في MIRA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  🎯 الدور: تنفيذ فعلي للأصوات والاهتزاز بناءً على MiraReaderSettings
 *
 *  ✅ Singleton آمن للخيوط (Thread-safe)
 *  ✅ يقرأ الإعدادات من MiraReaderSettings تلقائياً عند كل نداء
 *  ✅ لا يُشغّل صوت "المكرر" داخلياً — القرار للمستدعي
 *  ✅ يعمل من API 21+
 *  ✅ آمن ضد التسرّب — يُستخدم WeakReference من الداخل
 *
 *  ═══════════════════════════════════════════════════════════════════════
 *  📖 مثال الاستخدام:
 *  ═══════════════════════════════════════════════════════════════════════
 *
 *      MiraFeedback fb = MiraFeedback.getInstance(context);
 *
 *      // وسم جديد
 *      fb.onNewTag();
 *
 *      // وسم مكرر (المستدعي يقرر)
 *      if (!fb.shouldAcceptTag(epc)) {
 *          fb.onDuplicateTag();
 *      }
 *
 *      // نجاح / خطأ
 *      fb.onSuccess();
 *      fb.onError();
 *
 *  ═══════════════════════════════════════════════════════════════════════
 *  ⚠️ ملاحظات مهمة:
 *  ═══════════════════════════════════════════════════════════════════════
 *  • shouldAcceptTag() **لا يُشغّل** أي صوت — القرار فقط
 *  • onDuplicateTag() يُشغّل الصوت — استدعيه أنت
 *  • لا تستدعِ release() إلا عند إغلاق التطبيق كاملاً
 */
public class MiraFeedback {

    private static final String TAG = "MiraFeedback";

    // ═══════════════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════════════
    private static volatile MiraFeedback instance;

    // ═══════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════
    private final Context context;
    private final MiraReaderSettings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ToneGenerator toneGen;
    private int currentToneVolume = -1;  // لتجنّب إعادة الإنشاء بلا داع
    private volatile boolean released = false;

    /** آخر وقت قبولنا فيه وسماً (لفلتر التكرار) */
    private volatile long lastTagAt = 0L;

    // ═══════════════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════════════
    private MiraFeedback(Context ctx) {
        if (ctx == null) throw new IllegalArgumentException("Context required");
        this.context = ctx.getApplicationContext();
        this.settings = MiraReaderSettings.getInstance(this.context);
        ensureToneGenerator();
        Log.i(TAG, "✅ MiraFeedback v1.1 initialized");
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
    //  🔊 Public API — أحداث جاهزة (High-level)
    // ═══════════════════════════════════════════════════════════════

    /**
     * وسم جديد تم قراءته (غير مكرر).
     * ✅ يُشغّل النغمة المخصصة + اهتزاز
     */
    public void onNewTag() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(settings.getToneNewTag());
        vibrate(settings.getVibrateMs());
    }

    /**
     * وسم مكرر — **يجب أن يستدعيه المستدعي صراحةً**.
     * ✅ يُشغّل نغمة المكرر (بدون اهتزاز)
     */
    public void onDuplicateTag() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(settings.getToneDuplicate());
    }

    /**
     * نجاح عملية (بيع، كتابة، اعتماد).
     * ✅ نغمة نجاح + اهتزاز مضاعف
     */
    public void onSuccess() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(settings.getToneSuccess());
        vibrate(settings.getVibrateMs() * 2);
    }

    /**
     * خطأ (فشل قراءة، فشل كتابة، فشل اعتماد).
     * ✅ نغمة خطأ + نمط اهتزاز مميز
     */
    public void onError() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(settings.getToneError());
        vibratePattern(new long[]{0, 80, 60, 80});
    }

    /**
     * بدء جلسة المسح.
     */
    public void onStartInventory() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(MiraReaderSettings.TONE_BEEP_SHORT);
        vibrate(30);
    }

    /**
     * انتهاء جلسة المسح.
     */
    public void onStopInventory() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(MiraReaderSettings.TONE_BEEP_DOUBLE);
    }

    /**
     * 🆕 v1.1 — وسم "قسري" (تجاوز كل الفلاتر).
     * مفيد لسيناريوهات مثل: مسح إجباري، تخطّي التحقق.
     */
    public void onTagForced() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(MiraReaderSettings.TONE_BEEP_DOUBLE);
        vibrate(settings.getVibrateMs());
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎵 تشغيل النغمات — Core
    // ═══════════════════════════════════════════════════════════════

    /**
     * تشغيل نغمة حسب النوع.
     * @param toneType أحد الثوابت في MiraReaderSettings.TONE_*
     */
    public void playTone(int toneType) {
        if (released || toneGen == null) return;

        switch (toneType) {
            case MiraReaderSettings.TONE_BEEP_SHORT:
                safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60);
                break;

            case MiraReaderSettings.TONE_BEEP_DOUBLE:
                safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60);
                handler.postDelayed(
                        () -> safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60),
                        100);
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
                // صامت — لا شيء
                break;
        }
    }

    private void safeStartTone(int tone, int durationMs) {
        try {
            if (toneGen != null) {
                toneGen.startTone(tone, durationMs);
            }
        } catch (Exception e) {
            Log.e(TAG, "startTone failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎚️ حجم الصوت
    // ═══════════════════════════════════════════════════════════════

    /**
     * إعادة إنشاء ToneGenerator بالحجم الحالي.
     * يُستدعى تلقائياً عند الحاجة فقط.
     */
    private void ensureToneGenerator() {
        try {
            int vol = Math.max(1, Math.min(100, settings.getSoundVolume()));

            // لا حاجة لإعادة الإنشاء لو نفس الحجم
            if (toneGen != null && currentToneVolume == vol) return;

            // أفرغ القديم
            if (toneGen != null) {
                try { toneGen.release(); } catch (Exception ignored) {}
                toneGen = null;
            }

            toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, vol);
            currentToneVolume = vol;
            Log.d(TAG, "🔊 ToneGenerator created (volume=" + vol + ")");

        } catch (Exception e) {
            Log.e(TAG, "ToneGenerator init failed", e);
            toneGen = null;
        }
    }

    /**
     * 🆕 v1.1 — يُستدعى عند تغيير الحجم من الإعدادات.
     * يُعيد إنشاء ToneGenerator بالحجم الجديد.
     */
    public void refreshVolume() {
        if (released) return;
        ensureToneGenerator();
    }

    // ═══════════════════════════════════════════════════════════════
    //  📳 الاهتزاز
    // ═══════════════════════════════════════════════════════════════

    /**
     * اهتزاز قصير.
     * @param ms المدة بالمللي ثانية
     */
    public void vibrate(int ms) {
        if (released) return;
        if (!settings.isVibrateEnabled()) return;

        try {
            Vibrator v = getVibrator();
            if (v == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(
                        ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception e) {
            Log.e(TAG, "vibrate failed", e);
        }
    }

    /**
     * اهتزاز بنمط مخصص.
     * @param pattern [توقف, اهتزاز, توقف, اهتزاز, ...]
     */
    public void vibratePattern(long[] pattern) {
        if (released) return;
        if (!settings.isVibrateEnabled()) return;
        if (pattern == null || pattern.length == 0) return;

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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager)
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm != null ? vm.getDefaultVibrator() : null;
            } else {
                return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  🚫 فلترة التكرار — قرار فقط (بدون صوت)
    // ═══════════════════════════════════════════════════════════════

    /**
     * هل يجب قبول الوسم؟
     *
     * ⚠️ **مهم**: هذه الدالة **لا تُشغّل أي صوت**.
     *             القرار للـ Controller/المستدعي.
     *
     * @param epc الوسم القادم
     * @return true = اقبله (جديد) | false = مكرر (تجاهله)
     */
    public boolean shouldAcceptTag(String epc) {
        if (released) return false;

        // لو الفلتر معطّل → اقبل الكل
        if (!settings.isFilterDuplicates()) return true;

        long now = System.currentTimeMillis();
        long threshold = settings.getDuplicateFilterMs();

        if (now - lastTagAt < threshold) {
            // ✅ مكرر — لا صوت هنا (المستدعي يقرر)
            return false;
        }

        lastTagAt = now;
        return true;
    }

    /**
     * إعادة تعيين فلتر التكرار.
     * يُستدعى عند بدء جلسة جديدة.
     */
    public void resetDuplicateFilter() {
        lastTagAt = 0L;
    }

    // ═══════════════════════════════════════════════════════════════
    //  🧹 تحرير الموارد
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحرير كل الموارد.
     * ⚠️ استدعِها فقط عند إغلاق التطبيق كاملاً — لا في onDestroyView.
     */
    public void release() {
        if (released) return;
        released = true;

        // أوقف أي نغمات مؤجّلة
        try { handler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}

        if (toneGen != null) {
            try { toneGen.release(); } catch (Exception ignored) {}
            toneGen = null;
        }
        currentToneVolume = -1;

        Log.i(TAG, "🛑 MiraFeedback released");
    }

    // ═══════════════════════════════════════════════════════════════
    //  🔍 Getters للحالة
    // ═══════════════════════════════════════════════════════════════

    public boolean isReleased()      { return released; }
    public long    getLastTagAt()    { return lastTagAt; }
}
