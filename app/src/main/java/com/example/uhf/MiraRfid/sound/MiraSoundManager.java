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
 *
 * ✅ يدعم:
 *  - ToneGenerator (نغمات جاهزة)
 *  - Thread-Safety
 *  - منع تسريب الموارد
 *  - Reset to defaults
 *  - Fallback آمن
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

    // ==================== الأحداث ====================

    public enum SoundEvent {
        // أحداث النظام والتسجيل
        LOGIN_SUCCESS("sys_login_success", "تسجيل الدخول الناجح",
                ToneGenerator.TONE_PROP_BEEP, 150, new long[]{0, 50, 50, 50}),
        LOGIN_FAILED("sys_login_failed", "فشل تسجيل الدخول",
                ToneGenerator.TONE_PROP_BEEP2, 300, new long[]{0, 100, 100, 100}),
        LOGOUT("sys_logout", "تسجيل الخروج",
                ToneGenerator.TONE_PROP_NACK, 200, new long[]{0, 50}),
        SESSION_EXPIRED("sys_session_expired", "انتهاء الجلسة",
                ToneGenerator.TONE_PROP_NACK, 250, new long[]{0, 80, 80}),
        ACCOUNT_LOCKED("sys_account_locked", "قفل الحساب",
                ToneGenerator.TONE_SUP_ERROR, 500, new long[]{0, 200, 100, 200}),

        // عمليات الجرد و RFID
        TAG_READ_NEW("rfid_tag_new", "قراءة عنصر جديد (RFID)",
                ToneGenerator.TONE_PROP_BEEP, 80, new long[]{0, 30}),
        TAG_READ_DUPLICATE("rfid_tag_dup", "قراءة عنصر مكرر",
                ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE, 40, new long[]{}),
        TAG_READ_UNKNOWN("rfid_tag_unknown", "قراءة عنصر غير معروف",
                ToneGenerator.TONE_PROP_BEEP2, 120, new long[]{0, 50, 50}),
        SCAN_STARTED("rfid_scan_start", "بدء مسح الجرد",
                ToneGenerator.TONE_CDMA_PIP, 100, new long[]{0, 40}),
        SCAN_STOPPED("rfid_scan_stop", "إيقاف مسح الجرد",
                ToneGenerator.TONE_PROP_BEEP, 150, new long[]{0, 80}),
        SCAN_COMPLETED("rfid_scan_complete", "اكتمال الجرد",
                ToneGenerator.TONE_CDMA_CONFIRM, 300, new long[]{0, 100, 80, 100}),

        // عمليات المبيعات
        SALE_SUCCESS("sale_success", "إتمام عملية بيع بنجاح",
                ToneGenerator.TONE_PROP_ACK, 250, new long[]{0, 60, 40, 60}),
        SALE_FAILED("sale_failed", "فشل أو إلغاء البيع",
                ToneGenerator.TONE_SUP_ERROR, 400, new long[]{0, 150, 50, 150}),

        // التوكيد والتحقق
        TAWKID_VERIFIED("tawkid_verified", "توكيد ومطابقة ناجحة",
                ToneGenerator.TONE_CDMA_CONFIRM, 200, new long[]{0, 80, 40, 80}),
        TAWKID_REJECTED("tawkid_rejected", "رفض التوكيد أو عدم تطابق",
                ToneGenerator.TONE_SUP_BUSY, 350, new long[]{0, 200}),

        // البحث
        SEARCH_FOUND("search_found", "العثور على نتيجة البحث",
                ToneGenerator.TONE_PROP_BEEP, 100, new long[]{0, 40}),
        SEARCH_NOT_FOUND("search_not_found", "لم يتم العثور على نتائج",
                ToneGenerator.TONE_SUP_RADIO_NOTAVAIL, 250, new long[]{0, 100}),

        // الأمان
        SECURITY_BREACH("security_breach", "🚨 اختراق أمني",
                ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800,
                new long[]{0, 200, 100, 200, 100, 200}),
        SOLD_ITEM_DETECTED("security_sold_item", "💰 قطعة مبيعة تم اكتشافها",
                ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 600,
                new long[]{0, 150, 50, 150}),

        // الشبكة
        NETWORK_CONNECTED("net_connected", "متصل بالشبكة",
                ToneGenerator.TONE_PROP_ACK, 100, new long[]{0, 30}),
        NETWORK_LOST("net_lost", "انقطع الاتصال",
                ToneGenerator.TONE_PROP_NACK, 200, new long[]{0, 80}),

        // عام
        GENERAL_SUCCESS("gen_success", "عملية ناجحة عامة",
                ToneGenerator.TONE_PROP_ACK, 150, new long[]{0, 50}),
        GENERAL_ERROR("gen_error", "خطأ عام في النظام",
                ToneGenerator.TONE_SUP_ERROR, 300, new long[]{0, 100, 100, 100}),
        GENERAL_WARNING("gen_warning", "تحذير عام",
                ToneGenerator.TONE_PROP_NACK, 200, new long[]{0, 60, 60}),
        GENERAL_INFO("gen_info", "معلومة عامة",
                ToneGenerator.TONE_PROP_PROMPT, 100, new long[]{0, 30});

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

    // ==================== Singleton ====================

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

    // ==================== التهيئة ====================

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

    /** ✅ تحرير ToneGenerator بشكل آمن */
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

    /** ✅ إعادة تحميل الإعدادات */
    public void reloadSettings() {
        releaseToneGenerator();
        initAudioAndVibrator();
        Log.d(TAG, "✅ Settings reloaded");
    }

    // ==================== التشغيل ====================

    public void play(SoundEvent event) {
        if (event == null) return;

        boolean masterSound = isMasterSoundEnabled();
        boolean masterVibration = isMasterVibrationEnabled();

        if (masterSound && isEventSoundEnabled(event)) {
            playTone(getEventTone(event), event.defaultDuration);
        }

        if (masterVibration && isEventVibrationEnabled(event)) {
            vibratePattern(event.defaultVibePattern);
        }
    }

    public void playPreview(int toneType, int durationMs) {
        playTone(toneType, durationMs);
    }

    public void playCustomTone(int toneType, int durationMs, long[] vibePattern) {
        if (isMasterSoundEnabled()) {
            playTone(toneType, durationMs);
        }
        if (isMasterVibrationEnabled() && vibePattern != null) {
            vibratePattern(vibePattern);
        }
    }

    /**
     * ✅ Thread-safe + Fallback
     * ملاحظة: ToneGenerator.startTone() تُرجع boolean (true = نجاح)
     */
    private void playTone(int toneType, int duration) {
        synchronized (toneLock) {
            try {
                if (toneGenerator == null) {
                    initAudioAndVibrator();
                }
                if (toneGenerator != null) {
                    boolean success = toneGenerator.startTone(toneType, duration);
                    if (!success) {
                        Log.w(TAG, "⚠️ نغمة غير مدعومة: " + toneType + " — استخدام الافتراضي");
                        try {
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, duration);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "خطأ أثناء تشغيل النغمة: " + e.getMessage());
            }
        }
    }

    private void vibratePattern(long[] pattern) {
        try {
            if (vibrator != null && vibrator.hasVibrator()
                    && pattern != null && pattern.length > 0) {
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

    // ==================== إعدادات التحكم ====================

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

    /** ✅ إعادة كل الإعدادات للوضع الافتراضي */
    public void resetToDefaults() {
        prefs.edit().clear().apply();
        releaseToneGenerator();
        initAudioAndVibrator();
        Log.d(TAG, "✅ Reset to defaults");
    }

    // ==================== التحرير النهائي ====================

    /** ✅ استخدمه عند إغلاق التطبيق */
    public void shutdown() {
        releaseToneGenerator();
        vibrator = null;
        Log.d(TAG, "✅ Shutdown complete");
    }

    /** ✅ متوافق مع الكود القديم */
    public void release() {
        Log.d(TAG, "release() called — ignoring (Singleton)");
    }
}
