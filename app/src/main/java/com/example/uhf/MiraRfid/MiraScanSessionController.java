package com.example.uhf.MiraRfid;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import java.lang.ref.WeakReference;

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  MiraScanSessionController v1.2
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  🎯 الدور: مفوّض (Delegate) لكل منطق "الجلسة" في شاشة الجرد
 *
 *  ✅ يفصل تماماً بين:
 *      • View  (Fragment — يرسم فقط)
 *      • Logic (هذا الكلاس — يقرر فقط)
 *      • Data  (Settings + Feedback — Singleton)
 *
 *  ✅ يستمع تلقائياً لتغييرات MiraReaderSettings عبر WeakReference
 *
 *  [v1.2] إضافات:
 *    ✅ confirmTagAccepted(boolean isKnownItem) — تمييز المعروف/المجهول
 *    ✅ notifyUnknownTag() — صوت تنبيه للقطع غير المسجّلة
 *
 *  [v1.1] إصلاح:
 *    ✅ تهيئة autoStopRunnable و settingsListener في constructor
 *       لتفادي خطأ "hostRef might not have been initialized"
 */
public class MiraScanSessionController {

    private static final String TAG = "MiraScanSession";

    // ═══════════════════════════════════════════════════════════════
    //  واجهة المضيف (Fragment)
    // ═══════════════════════════════════════════════════════════════
    public interface Host {
        Context getHostContext();
        Activity getHostActivity();
        boolean isHostAlive();

        /** تم اكتشاف وسم جديد — عالج (أضف للقائمة) */
        void onSessionTagAccepted(String epc, int rssi);

        /** وصلنا للحد الأقصى للوسوم — أوقف من فضلك */
        void onSessionLimitReached(int max);

        /** يجب إيقاف المسح تلقائياً (مهلة / وضع مفرد) */
        void onSessionShouldStop(String reason);
    }

    // ═══════════════════════════════════════════════════════════════
    //  قرار قبول الوسم
    // ═══════════════════════════════════════════════════════════════
    public enum Decision {
        ACCEPT_NEW,          // وسم جديد — اقبله
        ACCEPT_DUPLICATE,    // مكرر — اقبله (لكن بدون زيادة العداد)
        REJECT_LIMIT,        // رفض: وصلنا للحد الأقصى
        REJECT_DUP_FILTER,   // رفض: فلتر التكرار رفضه
        REJECT_SESSION_OFF   // رفض: لا توجد جلسة نشطة
    }

    // ═══════════════════════════════════════════════════════════════
    //  State — الحقول المُرتبطة بـ Host
    // ═══════════════════════════════════════════════════════════════
    private final WeakReference<Host> hostRef;
    private final MiraReaderSettings settings;
    private final MiraFeedback feedback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile int sessionTagCount = 0;
    private volatile boolean active = false;
    private volatile boolean released = false;

    // ✅ [v1.1] بدون final — تُهيَّأ في الـ constructor
    private MiraReaderSettings.OnSettingsChangedListener settingsListener;
    private Runnable autoStopRunnable;

    // ═══════════════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════════════
    public MiraScanSessionController(Host host) {
        if (host == null) throw new IllegalArgumentException("Host required");
        this.hostRef = new WeakReference<>(host);
        Context ctx = host.getHostContext();
        if (ctx == null) throw new IllegalStateException("Context required");

        this.settings = MiraReaderSettings.getInstance(ctx);
        this.feedback = MiraFeedback.getInstance(ctx);

        // ✅ [v1.1] تهيئة الحقول التي تعتمد على hostRef
        this.autoStopRunnable = () -> {
            if (active && !released) {
                Log.i(TAG, "⏹️ Auto-stop fired");
                Host h = hostRef.get();
                if (h != null && h.isHostAlive()) {
                    h.onSessionShouldStop("auto-stop");
                }
            }
        };

        this.settingsListener = this::onSettingsChanged;

        // ✅ استمع لأي تغيير في الإعدادات
        settings.addListener(settingsListener);

        Log.i(TAG, "✅ MiraScanSessionController v1.2 created");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /** يُستدعى مرة واحدة عند بدء كل جلسة مسح (جديدة أو مستأنفة) */
    public void onScanStarted(boolean isResume) {
        if (released) return;
        active = true;

        if (!isResume) {
            sessionTagCount = 0;
            feedback.resetDuplicateFilter();
            Log.d(TAG, "🆕 New session — counters reset");
        } else {
            Log.d(TAG, "🔄 Resume — keeping count=" + sessionTagCount);
        }

        feedback.onStartInventory();
        applyKeepScreenOn(true);

        // ⏱️ مؤقت الإيقاف التلقائي
        mainHandler.removeCallbacks(autoStopRunnable);
        int autoStop = settings.getAutoStopMs();
        if (autoStop > 0) {
            mainHandler.postDelayed(autoStopRunnable, autoStop);
            Log.i(TAG, "⏱️ Auto-stop scheduled in " + autoStop + "ms");
        }
    }

    /** يُستدعى عند أي إيقاف (pause أو stop نهائي) */
    public void onScanStopped() {
        if (released) return;
        active = false;

        mainHandler.removeCallbacks(autoStopRunnable);
        feedback.onStopInventory();
        applyKeepScreenOn(false);
    }

    /** يُستدعى عند reset الجلسة بالكامل */
    public void onSessionReset() {
        sessionTagCount = 0;
        feedback.resetDuplicateFilter();
        Log.d(TAG, "🧹 Session reset");
    }

    /** يُستدعى من onDestroyView */
    public void release() {
        released = true;
        active = false;
        mainHandler.removeCallbacks(autoStopRunnable);
        try {
            if (settingsListener != null) {
                settings.removeListener(settingsListener);
            }
        } catch (Exception ignored) {}
        Log.i(TAG, "🛑 Controller released");
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎯 Tag Gatekeeping — الدالة الأهم
    // ═══════════════════════════════════════════════════════════════

    /**
     * تقيّم وسمًا قادمًا وتقرر مصيره.
     * لا تُعدّل أي شيء — القرار فقط.
     */
    public Decision evaluateTag(String epc) {
        if (released || !active) return Decision.REJECT_SESSION_OFF;

        // 1) الحد الأقصى
        int max = settings.getMaxTagsPerSession();
        if (max > 0 && sessionTagCount >= max) {
            return Decision.REJECT_LIMIT;
        }

        // 2) فلترة التكرار (تعتمد على MiraFeedback داخلياً)
        if (settings.isFilterDuplicates()) {
            if (!feedback.shouldAcceptTag(epc)) {
                return Decision.ACCEPT_DUPLICATE;
            }
        }

        return Decision.ACCEPT_NEW;
    }

    // ═══════════════════════════════════════════════════════════════
    //  ✅ Confirm Tag Accepted — [v1.2]
    // ═══════════════════════════════════════════════════════════════

    /**
     * 🆕 [v1.2] يُستدعى من Host بعد قبول وسم جديد — مع تمييز المعروف/المجهول.
     *
     * @param isKnownItem  true = موجود في الكتالوج، false = غير مسجّل
     *
     * سلوك الصوت:
     *   • معروف    → onNewTag()      — بيب مزدوج
     *   • غير معروف → onUnknownTag() — تنبيه ثلاثي + اهتزاز مزدوج
     */
    public void confirmTagAccepted(boolean isKnownItem) {
        sessionTagCount++;
        feedback.onNewTag(isKnownItem);

        Log.d(TAG, "✅ Tag accepted (count=" + sessionTagCount
                + ", known=" + isKnownItem + ")");
    }

    /**
     * ✅ نسخة قديمة للتوافق — تتعامل مع الوسم كـ "معروف".
     *
     * @deprecated استخدم confirmTagAccepted(boolean isKnownItem)
     */
    @Deprecated
    public void confirmTagAccepted() {
        confirmTagAccepted(true);
    }

    /** يُستدعى من Host عند وسم مكرر */
    public void confirmTagDuplicate() {
        feedback.onDuplicateTag();
    }

    /** يُستدعى من Host عند تجاوز الحد */
    public void confirmLimitReached() {
        feedback.onError();
        Host h = hostRef.get();
        if (h != null && h.isHostAlive()) {
            h.onSessionLimitReached(settings.getMaxTagsPerSession());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎯 Single Mode Helper
    // ═══════════════════════════════════════════════════════════════

    public boolean isSingleMode() {
        return settings.getReadMode() == MiraReaderSettings.READ_MODE_SINGLE;
    }

    /** جدولة إيقاف بعد أول وسم في الوضع المفرد */
    public void scheduleSingleModeStop() {
        if (!isSingleMode()) return;
        mainHandler.postDelayed(() -> {
            if (active && !released) {
                Host h = hostRef.get();
                if (h != null && h.isHostAlive()) {
                    Log.i(TAG, "🎯 Single mode — stopping");
                    h.onSessionShouldStop("single-mode");
                }
            }
        }, 300);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Success / Error / Unknown
    // ═══════════════════════════════════════════════════════════════

    public void notifySuccess() { feedback.onSuccess(); }
    public void notifyError()   { feedback.onError(); }

    /** 🆕 [v1.2] صوت تنبيه للقطع غير المسجّلة */
    public void notifyUnknownTag() { feedback.onUnknownTag(); }

    // ═══════════════════════════════════════════════════════════════
    //  Getters
    // ═══════════════════════════════════════════════════════════════
    public int  getSessionTagCount() { return sessionTagCount; }
    public boolean isActive()        { return active; }
    public boolean isReleased()      { return released; }

    // ═══════════════════════════════════════════════════════════════
    //  Internals
    // ═══════════════════════════════════════════════════════════════

    private void onSettingsChanged() {
        if (released) return;
        Host h = hostRef.get();
        if (h == null || !h.isHostAlive()) return;

        mainHandler.post(() -> {
            if (released) return;
            try {
                applyKeepScreenOn(settings.isKeepScreenOn() && active);

                // ✅ حدّث حجم الصوت في ToneGenerator فوراً
                feedback.refreshVolume();

                Log.d(TAG, "🔔 Settings re-applied (active=" + active + ")");
            } catch (Exception e) {
                Log.e(TAG, "onSettingsChanged error", e);
            }
        });
    }

    private void applyKeepScreenOn(boolean on) {
        try {
            Host h = hostRef.get();
            if (h == null) return;
            Activity a = h.getHostActivity();
            if (a == null) return;
            if (on) {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Exception ignored) {}
    }
}
