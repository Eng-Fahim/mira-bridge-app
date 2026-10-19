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
 *  MiraFeedback v1.2 — إدارة الأصوات والاهتزاز في MIRA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  🎯 الدور: تنفيذ فعلي للأصوات والاهتزاز بناءً على MiraReaderSettings
 *
 *  [v1.2] إضافات:
 *    ✅ onNewTag(boolean isKnown) — يُميّز القطعة المعروفة عن المجهولة
 *    ✅ onUnknownTag() — نغمة + اهتزاز مميز للقطع غير المسجّلة
 *    ✅ دعم TONE_UNKNOWN_ITEM في playTone()
 *
 *  [v1.1] الأساس:
 *    ✅ shouldAcceptTag() بدون صوت (القرار للمستدعي)
 *    ✅ refreshVolume() لتحديث الحجم فوراً
 *    ✅ حماية كاملة ضد الاستدعاء بعد release()
 *
 *  ═══════════════════════════════════════════════════════════════════════
 *  📖 مثال الاستخدام:
 *  ═══════════════════════════════════════════════════════════════════════
 *
 *      MiraFeedback fb = MiraFeedback.getInstance(context);
 *
 *      // وسم جديد — مع تمييز المعروف/المجهول
 *      fb.onNewTag(true);   // ✅ معروف في الكتالوج
 *      fb.onNewTag(false);  // 🆕 غير مسجّل
 *
 *      // أو مباشرة
 *      fb.onUnknownTag();   // ⚠️ نغمة + اهتزاز مميز
 *
 *      // مكرر (المستدعي يقرر)
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
    private int currentToneVolume = -1;
    private volatile boolean released = false;

    
/** آخر وقت قبولنا فيه وسماً (لفلتر التكرار) — للتوافق العكسي */
private volatile long lastTagAt = 0L;

/** ✅ [Fix-A1] فلتر per-EPC — كل وسم له وقته المستقل */
private final java.util.Map<String, Long> recentEpcsMap =
        new java.util.concurrent.ConcurrentHashMap<>();
private static final int MAX_RECENT_EPCS = 2000;
    // ═══════════════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════════════
    private MiraFeedback(Context ctx) {
        if (ctx == null) throw new IllegalArgumentException("Context required");
        this.context = ctx.getApplicationContext();
        this.settings = MiraReaderSettings.getInstance(this.context);
        ensureToneGenerator();
        Log.i(TAG, "✅ MiraFeedback v1.2 initialized");
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

    /**
     * 🆕 [v1.2] وسم جديد مع تمييز المعروف/المجهول.
     *
     * @param isKnown true = موجود في الكتالوج، false = غير مسجّل
     */
    public void onNewTag(boolean isKnown) {
        if (isKnown) {
            onNewTag();
        } else {
            onUnknownTag();
        }
    }

    /**
     * وسم جديد تم قراءته (معروف في الكتالوج).
     * ✅ يُشغّل نغمة "قطعة جديدة" + اهتزاز
     */
    public void onNewTag() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        playTone(settings.getToneNewTag());
        vibrate(settings.getVibrateMs());
    }

    /**
     * 🆕 [v1.2] وسم غير معروف (غير مسجّل في الكتالوج).
     *
     * نغمة تنبيه مميزة + اهتزاز مزدوج، حتى يُميّزها المستخدم
     * عن القطعة المعروفة بدون النظر إلى الشاشة.
     */
    public void onUnknownTag() {
        if (released) return;
        if (!settings.isSoundEnabled()) return;

        // 🎵 نغمة التنبيه (قابلة للتخصيص)
        playTone(settings.getToneUnknownItem());

        // 📳 اهتزاز مميز: نمط "اهتزاز-توقف-اهتزاز-توقف-اهتزاز"
        vibratePattern(new long[]{0, 60, 50, 60, 50, 60});
    }

    /**
     * وسم مكرر — **يجب أن يستدعيه المستدعي صراحةً**.
     * ✅ يُشغّل نغمة المكرر (بدون اهتزاز — لأن المستخدم يعرف)
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
     * 🆕 وسم "قسري" (تجاوز كل الفلاتر).
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
                // 🔔 بيب واحد قصير
                safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60);
                break;

            case MiraReaderSettings.TONE_BEEP_DOUBLE:
                // 🔔🔔 بيب + بيب (بعد 100ms)
                safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60);
                handler.postDelayed(
                        () -> safeStartTone(ToneGenerator.TONE_PROP_BEEP, 60),
                        100);
                break;

            case MiraReaderSettings.TONE_BEEP_LONG:
                // 🔔 بيب طويل منخفض
                safeStartTone(ToneGenerator.TONE_PROP_BEEP2, 300);
                break;

            case MiraReaderSettings.TONE_CLICK:
                // 🖱️ كليك خفيف
                safeStartTone(ToneGenerator.TONE_PROP_ACK, 40);
                break;

            case MiraReaderSettings.TONE_SUCCESS:
                // ✅ نغمة نجاح (CDMA alert)
                safeStartTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
                break;

            case MiraReaderSettings.TONE_ERROR:
                // ❌ نغمة خطأ
                safeStartTone(ToneGenerator.TONE_SUP_ERROR, 400);
                break;

            case MiraReaderSettings.TONE_UNKNOWN_ITEM:
                // 🆕 [v1.2] تنبيه "غير معروف"
                //     نمط: بيب + بيب أسرع + بيب (ثلاث بيبات متتابعة)
                safeStartTone(ToneGenerator.TONE_PROP_BEEP, 50);
                handler.postDelayed(
                        () -> safeStartTone(ToneGenerator.TONE_PROP_BEEP, 50), 80);
                handler.postDelayed(
                        () -> safeStartTone(ToneGenerator.TONE_PROP_BEEP, 50), 160);
                break;

            case MiraReaderSettings.TONE_NONE:
            default:
                // صامت
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
     */
    private void ensureToneGenerator() {
        try {
            int vol = Math.max(1, Math.min(100, settings.getSoundVolume()));

            if (toneGen != null && currentToneVolume == vol) return;

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
     * يُستدعى عند تغيير الحجم من الإعدادات.
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
     *
     * @param epc الوسم القادم
     * @return true = اقبله (جديد) | false = مكرر (تجاهله)
     */
    public boolean shouldAcceptTag(String epc) {
    if (released) return false;
    if (epc == null || epc.isEmpty()) return false;

    if (!settings.isFilterDuplicates()) return true;

    long now = System.currentTimeMillis();
    long threshold = settings.getDuplicateFilterMs();

    // ✅ [Fix-A1] فلترة per-EPC — الوسوم المختلفة لا تحجب بعضها
    Long lastForThisEpc = recentEpcsMap.get(epc);
    if (lastForThisEpc != null && (now - lastForThisEpc) < threshold) {
        lastTagAt = now;   // للتوافق العكسي فقط
        return false;      // نفس الوسم — مكرر
    }

    recentEpcsMap.put(epc, now);
    lastTagAt = now;

    // 🧹 تنظيف دوري — لا تكبر الـ Map بلا حد
    if (recentEpcsMap.size() > MAX_RECENT_EPCS) {
    recentEpcsMap.entrySet().removeIf(e -> (now - e.getValue()) > threshold * 2);
    
    // ✅ [احتياطي] إذا بقيت أكبر من الحد — احذف الأقدم
    if (recentEpcsMap.size() > MAX_RECENT_EPCS) {
        recentEpcsMap.clear();   // أو احتفظ بآخر 1000 فقط
    }
}
    return true;
}

    /**
     * إعادة تعيين فلتر التكرار.
     */
    public void resetDuplicateFilter() {
    lastTagAt = 0L;
    // ✅ [Fix-A1] امسح خريطة الـ EPCs أيضاً
    recentEpcsMap.clear();
}

    // ═══════════════════════════════════════════════════════════════
    //  🧹 تحرير الموارد
    // ═══════════════════════════════════════════════════════════════

    /**
     * تحرير كل الموارد.
     * ⚠️ استدعِها فقط عند إغلاق التطبيق كاملاً.
     */
    public void release() {
        if (released) return;
        released = true;

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
