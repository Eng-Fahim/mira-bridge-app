package com.example.uhf.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.MiraRfid.sound.MiraSoundManager;
import com.example.uhf.MiraRfid.sound.MiraSoundManager.SoundEvent;

/**
 * ✅ SoundManager - Wrapper للتوافق مع الكود القديم
 *
 * يحوّل FeedbackType (القديم) → SoundEvent (الجديد)
 * ثم يستدعي MiraSoundManager (المصدر الوحيد)
 *
 * الفائدة:
 * - الكود القديم (playBeep) يعمل بدون تغيير
 * - إعدادات MiraSoundSettingsFragment تُطبَّق تلقائياً
 * - لا تكرار في المنطق
 */
public class SoundManager {

    private static final String TAG = "SoundManager (Wrapper)";

    /** الاحتفاظ بـ enum القديم للتوافق */
    public enum FeedbackType {
        KNOWN_ITEM,
        UNKNOWN_ITEM,
        DUPLICATE_ITEM,
        MISSING_ITEM,
        COMPLETED,
        ERROR
    }

    private final MiraSoundManager miraSoundManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== Constructor ====================

    public SoundManager(Context context) {
        this.miraSoundManager = MiraSoundManager.getInstance(context);
        Log.d(TAG, "SoundManager (wrapper) initialized");
    }

    // ==================== playBeep (الواجهة القديمة) ====================

    /**
     * ✅ تحويل FeedbackType → SoundEvent + التشغيل
     */
    public void playBeep(FeedbackType feedbackType) {
        if (feedbackType == null) return;
        try {
            SoundEvent event = mapFeedbackToEvent(feedbackType);
            if (event != null) {
                miraSoundManager.play(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "playBeep error: " + e.getMessage());
        }
    }

    /**
     * ✅ التحويل بين الأنظمة
     */
    private SoundEvent mapFeedbackToEvent(FeedbackType feedbackType) {
        switch (feedbackType) {
            case KNOWN_ITEM:
                return SoundEvent.TAG_READ_NEW;
            case UNKNOWN_ITEM:
                return SoundEvent.SEARCH_NOT_FOUND;
            case DUPLICATE_ITEM:
                return SoundEvent.TAG_READ_DUPLICATE;
            case MISSING_ITEM:
                return SoundEvent.TAWKID_REJECTED;
            case COMPLETED:
                return SoundEvent.GENERAL_SUCCESS;
            case ERROR:
                return SoundEvent.GENERAL_ERROR;
            default:
                return SoundEvent.TAG_READ_NEW;
        }
    }

    // ==================== الواجهات القديمة الأخرى ====================

    /** ✅ playSound — متوافق */
    public void playSound(Context context, int id) {
        try {
            SoundEvent event = (id == 1)
                    ? SoundEvent.GENERAL_SUCCESS
                    : SoundEvent.GENERAL_ERROR;
            miraSoundManager.play(event);
        } catch (Exception e) {
            Log.e(TAG, "playSound error: " + e.getMessage());
        }
    }

    /** ✅ playSuccessSound — متوافق */
    public void playSuccessSound(Context context) {
        playSound(context, 1);
    }

    /** ✅ playErrorSound — متوافق */
    public void playErrorSound(Context context) {
        playSound(context, 2);
    }

    /**
     * ✅ playDelayedSound — نسخة واضحة تأخذ تأخيراً صريحاً بالميلي ثانية
     *    (تم إصلاح المنطق الغامض السابق)
     */
    public void playDelayedSound(Context context, long delayMs) {
        mainHandler.postDelayed(
                () -> playSound(context, 1),
                Math.max(10, delayMs));
    }
    
    // ═══════════════════════════════════════════════════════════
//  ✅ دالة مخصصة لباحث القطع (MIRA Locator)
//  ترشد المستخدم للاقتراب من القطعة بصوت متدرج
// ═══════════════════════════════════════════════════════════

/**
 * تشغيل صوت البحث الميداني حسب مستوى القرب
 *
 * @param level 0 = بعيد (صوت خفيف منخفض)
 *              1 = متوسط (صوت "ping" واضح)
 *              2 = قريب (صوت نجاح حاد)
 */
public void playLocatorBeep(Context context, int level) {
    try {
        SoundEvent event;
        switch (level) {
            case 2:
                // 🔥 قريب — صوت نجاح حاد (يقترب من الهدف)
                event = SoundEvent.GENERAL_SUCCESS;
                break;
            case 1:
                // 📍 متوسط — صوت "قراءة" جديد (ping متكرر)
                event = SoundEvent.TAG_READ_NEW;
                break;
            case 0:
            default:
                // 🌐 بعيد — صوت خفيف للتنبيه بالاستمرار
                // نستخدم DUPLICATE لأنه أقصر وأخف
                event = SoundEvent.TAG_READ_DUPLICATE;
                break;
        }
        miraSoundManager.play(event);
    } catch (Exception e) {
        Log.e(TAG, "playLocatorBeep error: " + e.getMessage());
    }
}

    // ==================== Cleanup ====================
    /**
     * ✅ لا يفعل شيئاً — MiraSoundManager Singleton يبقى حياً
     */
    public void release() {
        Log.d(TAG, "release called — ignoring (MiraSoundManager is Singleton)");
    }

    /** ✅ متوافق */
    public void cleanup() {
        Log.d(TAG, "cleanup called — ignoring (MiraSoundManager is Singleton)");
    }
}
