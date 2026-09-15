package com.example.uhf.utils;

import android.content.Context;
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

    // ✅ الاحتفاظ بـ enum القديم للتوافق
    public enum FeedbackType {
        KNOWN_ITEM,
        UNKNOWN_ITEM,
        DUPLICATE_ITEM,
        MISSING_ITEM,
        COMPLETED,
        ERROR
    }

    private final MiraSoundManager miraSoundManager;

    // ==================== Constructor ====================

    public SoundManager(Context context) {
        // ✅ استخدام Singleton من MiraSoundManager
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
                // قراءة قطعة معروفة (في الكتالوج)
                return SoundEvent.TAG_READ_NEW;
            
            case UNKNOWN_ITEM:
                // قراءة قطعة غير معروفة
                return SoundEvent.SEARCH_NOT_FOUND;
            
            case DUPLICATE_ITEM:
                // قراءة قطعة مكررة
                return SoundEvent.TAG_READ_DUPLICATE;
            
            case MISSING_ITEM:
                // قطعة مفقودة (لم تُمسح)
                return SoundEvent.TAWKID_REJECTED;
            
            case COMPLETED:
                // اكتمال الجرد
                return SoundEvent.GENERAL_SUCCESS;
            
            case ERROR:
                // خطأ عام
                return SoundEvent.GENERAL_ERROR;
            
            default:
                return SoundEvent.TAG_READ_NEW;
        }
    }

    // ==================== الواجهات القديمة الأخرى ====================

    /**
     * ✅ playSound — متوافق مع الكود القديم
     */
    public void playSound(Context context, int id) {
        try {
            SoundEvent event = (id == 1) ? SoundEvent.GENERAL_SUCCESS : SoundEvent.GENERAL_ERROR;
            miraSoundManager.play(event);
        } catch (Exception e) {
            Log.e(TAG, "playSound error: " + e.getMessage());
        }
    }

    /**
     * ✅ playSuccessSound — متوافق
     */
    public void playSuccessSound(Context context) {
        playSound(context, 1);
    }

    /**
     * ✅ playErrorSound — متوافق
     */
    public void playErrorSound(Context context) {
        playSound(context, 2);
    }

    /**
     * ✅ playDelayedSound — متوافق
     */
    public void playDelayedSound(Context context, int value) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            playSound(context, 1);
        }, Math.max(10, 1000 - (value * 10)));
    }

    // ==================== Cleanup ====================

    /**
     * ✅ release — متوافق (لكن لا يفعل شيئاً — Singleton يبقى)
     */
    public void release() {
        // ✅ لا نفعل شيئاً — MiraSoundManager Singleton يبقى حياً
        Log.d(TAG, "release called — ignoring (MiraSoundManager is Singleton)");
    }

    /**
     * ✅ cleanup — متوافق
     */
    public void cleanup() {
        // ✅ لا نفعل شيئاً
        Log.d(TAG, "cleanup called — ignoring (MiraSoundManager is Singleton)");
    }
}
