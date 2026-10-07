package com.example.uhf.MiraRfid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.utils.MiraFileLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  GateRfidBridge v1.0 — الجسر الموحّد للبوابة الأمنية
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  الهدف:
 *  ──────
 *  فك ارتباط MiraSecureGateFragment من Chainway SDK مباشرة،
 *  وتوحيد الوصول للقارئ عبر RFIDManager.
 *
 *  الفوائد:
 *  ────────
 *  ✅ يعمل مع Chainway C72 (المحمول)
 *  ✅ يعمل مع VANCH VF-747P (الثابت)
 *  ✅ يعمل مع أي قارئ مستقبلي يطبّق MiraIRFIDReader
 *  ✅ Threading آمن (executor أحادي)
 *  ✅ لا ANR، لا Race Conditions
 *  ✅ Lifecycle واضح (start/stop/release)
 *
 *  المعمارية:
 *  ─────────
 *  MiraSecureGateFragment
 *      ↓ يستدعي
 *  GateRfidBridge (هذا الملف)
 *      ↓ يفوض
 *  RFIDManager.getActiveReader()
 *      ↓ يوفر
 *  ChainwayReader / VanchReader (MiraIRFIDReader)
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class GateRfidBridge {

    private static final String TAG = "GateRfidBridge";

    /** مهلة انتظار تحرير منفذ UART قبل فتح الكاميرا */
    private static final long CAMERA_PORT_RELEASE_MS = 150;

    /** نطاق قوة الإرسال المسموح */
    private static final int MIN_POWER_DBM = 5;
    private static final int MAX_POWER_DBM = 30;

    // ═══════════════════════════════════════════════════════════
    //  واجهة المستمع — يستخدمها MiraSecureGateFragment
    // ═══════════════════════════════════════════════════════════

    public interface GateTagListener {
        /** يُستدعى عند قراءة وسم جديد (على Main Thread) */
        void onTagRead(String epc, int rssi);

        /** بدأ المسح بنجاح */
        void onScanStarted();

        /** توقف المسح */
        void onScanStopped();

        /** حدث خطأ */
        void onError(String error);
    }

    // ═══════════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════════

    private static volatile GateRfidBridge instance;

    public static GateRfidBridge getInstance(Context context) {
        if (instance == null) {
            synchronized (GateRfidBridge.class) {
                if (instance == null) {
                    instance = new GateRfidBridge(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════
    //  الحقول
    // ═══════════════════════════════════════════════════════════

    private final Context appContext;
    private final ExecutorService hardwareExecutor;
    private final Handler mainHandler;

    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean isReleased = new AtomicBoolean(false);

    private volatile GateTagListener currentListener;
    private volatile int currentPowerDbm = MAX_POWER_DBM;

    // ═══════════════════════════════════════════════════════════
    //  Constructor
    // ═══════════════════════════════════════════════════════════

    private GateRfidBridge(Context context) {
        this.appContext = context;
        this.hardwareExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GateRfidBridge-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
        MiraFileLogger.log(TAG, "✅ GateRfidBridge initialized");
    }

    // ═══════════════════════════════════════════════════════════
    //  Reader Listener Adapter (MiraIRFIDReader → GateTagListener)
    // ═══════════════════════════════════════════════════════════

    private final MiraIRFIDReader.OnTagReadListener readerListener =
            new MiraIRFIDReader.OnTagReadListener() {

        @Override
        public void onTagRead(String epc, int rssi) {
            final GateTagListener listener = currentListener;
            if (listener == null) return;
            if (epc == null || epc.trim().isEmpty()) return;

            final String cleanEpc = epc.trim().toUpperCase();
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onTagRead(cleanEpc, rssi);
                } catch (Exception e) {
                    MiraFileLogger.logError(TAG, "onTagRead dispatch error", e);
                }
            });
        }

        @Override
        public void onScanStarted() {
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onScanStarted();
                } catch (Exception ignored) {}
            });
        }

        @Override
        public void onScanStopped() {
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onScanStopped();
                } catch (Exception ignored) {}
            });
        }

        @Override
        public void onConnectionChanged(boolean connected) {
            MiraFileLogger.log(TAG, "🔌 Reader connection changed: " + connected);
        }

        @Override
        public void onError(String error) {
            MiraFileLogger.logError(TAG, "Reader error: " + error);
            final String errMsg = (error != null) ? error : "Unknown error";
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onError(errMsg);
                } catch (Exception ignored) {}
            });
        }
    };

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ بدء المسح باستخدام القارئ النشط (Chainway أو VANCH)
     *
     * @param listener  المستمع للوسوم
     * @param powerDbm  قوة الإرسال (5-30)
     * @return true إذا قُبل الطلب (لا يعني النجاح الفعلي)
     */
    public boolean startScan(GateTagListener listener, int powerDbm) {
        if (isReleased.get()) {
            MiraFileLogger.logWarn(TAG, "❌ Cannot start — bridge released");
            return false;
        }
        if (listener == null) {
            MiraFileLogger.logWarn(TAG, "❌ startScan: null listener");
            return false;
        }

        // ✅ احفظ القيم
        currentListener = listener;
        currentPowerDbm = clampPower(powerDbm);

        // ✅ نفّذ على Executor
        try {
            hardwareExecutor.execute(this::doStartScan);
            return true;
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startScan dispatch failed", e);
            return false;
        }
    }

    private void doStartScan() {
        try {
            // 1) احصل على القارئ النشط
            RFIDManager manager = RFIDManager.getInstance(appContext);
            MiraIRFIDReader reader = manager.getActiveReader();

            if (reader == null) {
                MiraFileLogger.logWarn(TAG, "❌ No active reader — cannot start");
                notifyError("No active RFID reader configured");
                return;
            }

            // 2) اربط المستمع
            reader.setOnTagReadListener(readerListener);

            // 3) طبّق الطاقة
            try {
                reader.setPower(currentPowerDbm);
                MiraFileLogger.log(TAG, "⚡ Power set: " + currentPowerDbm + " dBm");
            } catch (Exception e) {
                MiraFileLogger.logWarn(TAG, "Failed to set power: " + e.getMessage());
            }

            // 4) تأكد من الاتصال
            if (!reader.isConnected()) {
                MiraFileLogger.log(TAG, "🔌 Reader not connected — connecting...");
                if (!reader.initialize()) {
                    notifyError("Reader initialize failed");
                    return;
                }
                if (!reader.connect()) {
                    notifyError("Reader connect failed");
                    return;
                }
            }

            // 5) ابدأ المسح
            boolean ok = reader.startScan();
            isScanning.set(ok);

            if (ok) {
                MiraFileLogger.log(TAG, "🚀 Scan started ["
                        + reader.getClass().getSimpleName() + "]");
            } else {
                MiraFileLogger.logWarn(TAG, "❌ Scan start returned false");
                notifyError("Failed to start scan");
            }

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStartScan exception", e);
            notifyError("doStartScan: " + e.getMessage());
        }
    }

    /**
     * ✅ إيقاف المسح مع callback اختياري
     */
    public void stopScan(Runnable onComplete) {
        try {
            hardwareExecutor.execute(() -> {
                try {
                    doStopScan();
                } catch (Exception e) {
                    MiraFileLogger.logError(TAG, "stopScan exception", e);
                } finally {
                    if (onComplete != null) {
                        mainHandler.post(onComplete);
                    }
                }
            });
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "stopScan dispatch failed", e);
            if (onComplete != null) mainHandler.post(onComplete);
        }
    }

    private void doStopScan() {
        // ✅ ذرّي: إذا كانت false، لا تُكرر
        if (!isScanning.getAndSet(false)) {
            MiraFileLogger.log(TAG, "⏹️ stopScan: skipped (already stopped)");
            return;
        }

        try {
            RFIDManager manager = RFIDManager.getInstance(appContext);
            MiraIRFIDReader reader = manager.getActiveReader();

            if (reader != null) {
                reader.stopScan();
                reader.setOnTagReadListener(null);
                MiraFileLogger.log(TAG, "⏹️ Scan stopped");
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStopScan error", e);
        }
    }

    /**
     * ✅ التمهيد لفتح الكاميرا:
     *   1. أوقف المسح
     *   2. انتظر 150ms (تحرير UART port)
     *   3. نفّذ الـ callback
     */
    public void prepareForCamera(Runnable launchAction) {
        if (launchAction == null) return;

        MiraFileLogger.log(TAG, "📷 Preparing for camera...");

        stopScan(() -> mainHandler.postDelayed(() -> {
            try {
                MiraFileLogger.log(TAG, "📷 UART released — launching camera");
                launchAction.run();
            } catch (Exception e) {
                MiraFileLogger.logError(TAG, "prepareForCamera launch error", e);
            }
        }, CAMERA_PORT_RELEASE_MS));
    }

    /**
     * ✅ هل البوابة تمسح الآن؟
     */
    public boolean isScanning() {
        return isScanning.get();
    }

    /**
     * ✅ هل يوجد قارئ نشط جاهز؟
     */
    public boolean isReaderReady() {
        try {
            RFIDManager manager = RFIDManager.getInstance(appContext);
            MiraIRFIDReader reader = manager.getActiveReader();
            return reader != null;
        } catch (Exception e) {
            return false;
        }
    }
/**
 * ✅ هل القارئ النشط محاكياً (SIMULATOR)؟
 */
public boolean isSimulationMode() {
    try {
        RFIDManager manager = RFIDManager.getInstance(appContext);
        MiraIRFIDReader reader = manager.getActiveReader();
        return reader instanceof com.example.uhf.MiraRfid.readers.SimulatorReader;
    } catch (Exception e) {
        return false;
    }
}

    /**
     * ✅ إغلاق نظيف (يُستدعى عند إنهاء التطبيق)
     */
    public void shutdown() {
        if (!isReleased.compareAndSet(false, true)) return;

        MiraFileLogger.log(TAG, "🛑 Shutting down GateRfidBridge");

        try {
            doStopScan();
        } catch (Exception ignored) {}

        try {
            hardwareExecutor.shutdownNow();
        } catch (Exception ignored) {}

        currentListener = null;
        instance = null;
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void notifyError(String error) {
        final String msg = (error != null) ? error : "Unknown error";
        mainHandler.post(() -> {
            try {
                GateTagListener l = currentListener;
                if (l != null) l.onError(msg);
            } catch (Exception ignored) {}
        });
    }

    private int clampPower(int powerDbm) {
        if (powerDbm < MIN_POWER_DBM) return MIN_POWER_DBM;
        if (powerDbm > MAX_POWER_DBM) return MAX_POWER_DBM;
        return powerDbm;
    }
}
