package com.example.uhf.MiraRfid.sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * نظام إدارة الأصوات والاهتزازات المركزية لمنظومة Mira Smart Systems.
 * يدعم تخصيص الأصوات لكل حدث في النظام بشكل مستقل مع التخزين الداخلي.
 *
 * ✅ محسّن لـ:
 *  - Thread-Safety (synchronized على ToneGenerator)
 *  - منع تسريب الموارد (release قبل إعادة التهيئة)
 *  - Reset to defaults
 *  - Fallback للنغمات غير المدعومة
 */
public class MiraSoundManager {

    private static final String TAG = "MiraSoundManager";
    private static final String PREFS_NAME = "mira_sound_settings";

    private static final String PREF_MASTER_SOUND = "master_sound_enabled";
    private static final String PREF_MASTER_VIBRATION = "master_vibration_enabled";
    private static final String PREF_VOLUME_LEVEL = "sound_volume_level";

    private static final int DEFAULT_VOLUME = 80;
    private static final int STREAM_TYPE = AudioManager.STREAM_NOTIFICATION;

    private static MiraSoundManager instance;

    private final Context context;
    private final SharedPreferences prefs;

    private ToneGenerator toneGenerator;
    private Vibrator vibrator;

    /** ✅ قفل لحماية ToneGenerator من الوصول المتزامن */
    private final Object toneLock = new Object();

    /**
     * مصفوفة الأحداث والخصائص المتاحة في كامل النظام
     */
    public enum SoundEvent {
        // أحداث النظام والتسجيل
        LOGIN_SUCCESS("sys_login_success", "تسجيل الدخول الناجح",
                ToneGenerator.TONE_PROP_BEEP, 150, new long[]{0, 50, 50, 50}),
        LOGIN_FAILED("sys_login_failed", "فشل تسجيل الدخول",
                ToneGenerator.TONE_PROP_BEEP2, 300, new long[]{0, 100, 100, 100}),
        LOGOUT("sys_logout", "تسجيل الخروج",
                ToneGenerator.TONE_PROP_NACK, 200, new long[]{0, 50}),

        // عمليات الجرد و RFID
        TAG_READ_NEW("rfid_tag_new", "قراءة عنصر جديد (RFID)",
                ToneGenerator.TONE_PROP_BEEP, 80, new long[]{0, 30}),
        TAG_READ_DUPLICATE("rfid_tag_dup", "قراءة عنصر مكرر",
                ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE, 40, new long[]{}),
        SCAN_STARTED("rfid_scan_start", "بدء مسح الجرد",
                ToneGenerator.TONE_CDMA_PIP, 100, new long[]{0, 40}),
        SCAN_STOPPED("rfid_scan_stop", "إيقاف مسح الجرد",
                ToneGenerator.TONE_PROP_BEEP, 150, new long[]{0, 80}),

        // عمليات المبيعات (Mira Lite)
        SALE_SUCCESS("sale_success", "إتمام عملية بيع بنجاح",
                ToneGenerator.TONE_PROP_ACK, 250, new long[]{0, 60, 40, 60}),
        SALE_FAILED("sale_failed", "فشل أو إلغاء البيع",
                ToneGenerator.TONE_SUP_ERROR, 400, new long[]{0, 150, 50, 150}),

        // عمليات التوكيد والتحقق (Tawkid)
        TAWKID_VERIFIED("tawkid_verified", "توكيد ومطابقة ناجحة",
                ToneGenerator.TONE_CDMA_CONFIRM, 200, new long[]{0, 80, 40, 80}),
        TAWKID_REJECTED("tawkid_rejected", "رفض التوكيد أو عدم تطابق",
                ToneGenerator.TONE_SUP_BUSY, 350, new long[]{0, 200}),

        // عمليات البحث والاستعلام
        SEARCH_FOUND("search_found", "العثور على نتيجة البحث",
                ToneGenerator.TONE_PROP_BEEP, 100, new long[]{0, 40}),
        SEARCH_NOT_FOUND("search_not_found", "لم يتم العثور على نتائج",
                ToneGenerator.TONE_SUP_RADIO_NOTAVAIL, 250, new long[]{0, 100}),

        // التنبيهات العامة والأخطاء
        GENERAL_SUCCESS("gen_success", "عملية ناجحة عامة",
                ToneGenerator.TONE_PROP_ACK, 150, new long[]{0, 50}),
        GENERAL_ERROR("gen_error", "خطأ عام في النظام",
                ToneGenerator.TONE_SUP_ERROR, 300, new long[]{0, 100, 100, 100});

        public final String key;
        public final String displayName;
        public final int defaultTone;
        public final int defaultDuration;
        public final long[] defaultVibePattern;

        SoundEvent(String key, String displayName, int defaultTone,
                   int defaultDuration, long[] defaultVibePattern) {
            this.key = key;
            this.displayName = displayName;
            this.defaultTone = defaultTone;
            this.defaultDuration = defaultDuration;
            this.defaultVibePattern = defaultVibePattern;
        }
    }

    private MiraSoundManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initAudioAndVibrator();
    }

    public static synchronized MiraSoundManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraSoundManager(context);
        }
        return instance;
    }

    // ==================== التهيئة والتحرير ====================

    private void initAudioAndVibrator() {
        synchronized (toneLock) {
            try {
                int volume = prefs.getInt(PREF_VOLUME_LEVEL, DEFAULT_VOLUME);
                toneGenerator = new ToneGenerator(STREAM_TYPE, volume);
                if (vibrator == null) {
                    vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                }
            } catch (Exception e) {
                Log.e(TAG, "فشل تهيئة محرك الصوت أو الاهتزاز: " + e.getMessage());
            }
        }
    }

    /** ✅ تحرير ToneGenerator بشكل آمن لمنع تسريب الموارد */
    private void releaseToneGenerator() {
        synchronized (toneLock) {
            if (toneGenerator != null) {
                try {
                    toneGenerator.release();
                } catch (Exception e) {
                    Log.e(TAG, "خطأ في تحرير ToneGenerator: " + e.getMessage());
                }
                toneGenerator = null;
            }
        }
    }

    /**
     * ✅ إعادة تحميل الإعدادات (يحرر القديم ويعيد التهيئة)
     */
    public void reloadSettings() {
        releaseToneGenerator();
        initAudioAndVibrator();
        Log.d(TAG, "✅ Settings reloaded");
    }

    // ==================== التشغيل ====================

    /**
     * تشغيل الصوت والاهتزاز الخاص بحدث معين بناءً على إعدادات المستخدم
     */
    public void play(SoundEvent event) {
        if (event == null) return;

        boolean masterSound = isMasterSoundEnabled();
        boolean masterVibration = isMasterVibrationEnabled();

        // 1. تشغيل الصوت إذا كان مفعلاً كلياً وجزئياً
        if (masterSound && isEventSoundEnabled(event)) {
            playTone(getEventTone(event), event.defaultDuration);
        }

        // 2. تشغيل الاهتزاز إذا كان مفعلاً كلياً وجزئياً
        if (masterVibration && isEventVibrationEnabled(event)) {
            vibratePattern(event.defaultVibePattern);
        }
    }

    /**
     * تشغيل نغمة محددة مباشرة (مفيد لمعاينة الأصوات في واجهة الإعدادات)
     */
    public void playPreview(int toneType, int durationMs) {
        playTone(toneType, durationMs);
    }

    /**
     * ✅ تشغيل مخصص - صوت + اهتزاز مع نمط مخصص
     */
    public void playCustomTone(int toneType, int durationMs, long[] vibePattern) {
        if (isMasterSoundEnabled()) {
            playTone(toneType, durationMs);
        }
        if (isMasterVibrationEnabled() && vibePattern != null) {
            vibratePattern(vibePattern);
        }
    }

    /**
     * ✅ Thread-safe + Fallback للنغمات غير المدعومة
     */
    private void playTone(int toneType, int duration) {
        synchronized (toneLock) {
            try {
                if (toneGenerator == null) {
                    initAudioAndVibrator();
                }
                if (toneGenerator != null) {
                    int result = toneGenerator.startTone(toneType, duration);
                    if (result == ToneGenerator.TONE_UNKNOWN) {
                        Log.w(TAG, "نغمة غير مدعومة: " + toneType + " — استخدام الافتراضي");
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, duration);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "خطأ أثناء تشغيل النغمة: " + e.getMessage());
            }
        }
    }

    private void vibratePattern(long[] pattern) {
        try {
            if (vibrator != null && vibrator.hasVibrator() && pattern != null && pattern.length > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    VibrationEffect effect = VibrationEffect.createWaveform(pattern, -1);
                    vibrator.vibrate(effect);
                } else {
                    vibrator.vibrate(pattern, -1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "خطأ أثناء تشغيل الاهتزاز: " + e.getMessage());
        }
    }

    // ==================== إعدادات التحكم والحفظ ====================

    public boolean isMasterSoundEnabled() {
        return prefs.getBoolean(PREF_MASTER_SOUND, true);
    }

    public void setMasterSoundEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_MASTER_SOUND, enabled).apply();
    }

    public boolean isMasterVibrationEnabled() {
        return prefs.getBoolean(PREF_MASTER_VIBRATION, true);
    }

    public void setMasterVibrationEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_MASTER_VIBRATION, enabled).apply();
    }

    public int getVolumeLevel() {
        return prefs.getInt(PREF_VOLUME_LEVEL, DEFAULT_VOLUME);
    }

    /** ✅ إعادة تهيئة آمنة بدون تسريب */
    public void setVolumeLevel(int volume) {
        prefs.edit().putInt(PREF_VOLUME_LEVEL, volume).apply();
        releaseToneGenerator();
        initAudioAndVibrator();
    }

    public boolean isEventSoundEnabled(SoundEvent event) {
        return prefs.getBoolean(event.key + "_sound_enabled", true);
    }

    public void setEventSoundEnabled(SoundEvent event, boolean enabled) {
        prefs.edit().putBoolean(event.key + "_sound_enabled", enabled).apply();
    }

    public boolean isEventVibrationEnabled(SoundEvent event) {
        return prefs.getBoolean(event.key + "_vibe_enabled", true);
    }

    public void setEventVibrationEnabled(SoundEvent event, boolean enabled) {
        prefs.edit().putBoolean(event.key + "_vibe_enabled", enabled).apply();
    }

    public int getEventTone(SoundEvent event) {
        return prefs.getInt(event.key + "_tone_type", event.defaultTone);
    }

    public void setEventTone(SoundEvent event, int toneType) {
        prefs.edit().putInt(event.key + "_tone_type", toneType).apply();
    }

    // ==================== إعادة الضبط ====================

    /**
     * ✅ إعادة كل الإعدادات للوضع الافتراضي
     */
    public void resetToDefaults() {
        prefs.edit().clear().apply();
        releaseToneGenerator();
        initAudioAndVibrator();
        Log.d(TAG, "✅ Reset to defaults");
    }

    // ==================== التحرير النهائي ====================

    /**
     * ✅ استخدم هذا فقط عند إغلاق التطبيق بالكامل (لا تستدعيه أثناء التشغيل)
     */
    public void shutdown() {
        releaseToneGenerator();
        vibrator = null;
        Log.d(TAG, "✅ Shutdown complete");
    }

    /**
     * ✅ متوافق مع الكود القديم — لا يحرر ToneGenerator لأن Singleton يبقى حياً
     */
    public void release() {
        Log.d(TAG, "release() called — ignoring (Singleton)");
    }
}
