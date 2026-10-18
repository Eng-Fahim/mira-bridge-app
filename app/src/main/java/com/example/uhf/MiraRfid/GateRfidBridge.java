package com.example.uhf.MiraRfid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.utils.MiraFileLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  GateRfidBridge v2.0 — الجسر الموحّد للبوابة الأمنية
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  v2.0 (هذا الإصدار) — إضافة VANCH Fixed:
 *  ────────────────────────────────────────
 *  ✅ يقرأ MiraGatesSettings.getActiveDeviceGate() لمعرفة البوابة المربوطة
 *  ✅ يوجّه حسب deviceType:
 *      • "vanch_fixed"      → VanchNetworkClient (TCP)
 *      • "chainway_c72"     → RFIDManager (Chainway)
 *      • "simulator"        → RFIDManager (Simulator)
 *      • "generic_bluetooth"→ RFIDManager
 *      • لا يوجد ربط       → RFIDManager (fallback آمن)
 *  ✅ onTagRead يعمل بنفس المسار لكلا النوعين
 *  ✅ doStopScan يوقف VANCH والقارئ الافتراضي بشكل صحيح
 *  ✅ shutdown ينظّف كلا المصدرين
 *
 *  v1.0 (محفوظ):
 *  ─────────────
 *  ✅ Threading آمن (executor أحادي)
 *  ✅ لا ANR، لا Race Conditions
 *  ✅ Lifecycle واضح (start/stop/release)
 *
 *  المعمارية:
 *  ─────────
 *  MiraSecureGateFragment
 *      ↓ يستدعي
 *  GateRfidBridge (هذا الملف)
 *      ↓ يفوض حسب deviceType
 *  ┌────────────────────┬──────────────────────┐
 *  │ vanch_fixed        │ chainway / simulator │
 *  │ VanchNetworkClient │ RFIDManager          │
 *  └────────────────────┴──────────────────────┘
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

    // ✅ [v2.0] VANCH Network Client
    private volatile VanchNetworkClient vanchClient;

    // ✅ [v2.0] نوع الجهاز النشط حالياً
    // "vanch_fixed" | "default" | null
    private volatile String activeDeviceType;

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
        MiraFileLogger.log(TAG, "✅ GateRfidBridge v2.0 initialized");
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
     * ✅ بدء المسح — يوجّه تلقائياً حسب البوابة المربوطة
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

        currentListener = listener;
        currentPowerDbm = clampPower(powerDbm);

        try {
            hardwareExecutor.execute(this::doStartScan);
            return true;
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startScan dispatch failed", e);
            return false;
        }
    }

    /**
     * ✅ [v2.0] يقرأ البوابة المربوطة ويوجّه حسب deviceType
     */
    private void doStartScan() {
        try {
            // 1) اقرأ البوابة المربوطة
            MiraGatesSettings settings =
                    MiraGatesSettings.getInstance(appContext);
            MiraGatesSettings.GateDevice activeGate =
                    settings.getActiveDeviceGate();

            if (activeGate == null) {
                MiraFileLogger.log(TAG,
                        "⚠️ No linked gate — falling back to default reader");
                activeDeviceType = "default";
                startDefaultReader();
                return;
            }

            activeDeviceType = activeGate.deviceType;

            MiraFileLogger.log(TAG,
                    "🎯 Active gate: " + activeGate.gateName
                            + " [" + activeGate.deviceType + "]");

            // 2) التوجيه
            if ("vanch_fixed".equals(activeGate.deviceType)) {
                startVanchClient(activeGate);
            } else {
                // chainway_c72 / generic_bluetooth / simulator
                startDefaultReader();
            }

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStartScan exception", e);
            notifyError("doStartScan: " + e.getMessage());
        }
    }

    /**
     * ✅ [v2.0] مسار VANCH Fixed (TCP)
     */
    private void startVanchClient(MiraGatesSettings.GateDevice gate) {
        try {
            String endpoint = gate.ipAddress;
            if (endpoint == null || endpoint.trim().isEmpty()) {
                notifyError("VANCH gate has no IP address");
                return;
            }

            MiraFileLogger.log(TAG,
                    "🚀 Starting VANCH client → " + endpoint);

            isScanning.set(true);

            vanchClient = new VanchNetworkClient(endpoint,
                    new VanchNetworkClient.VanchEventListener() {

                @Override
                public void onConnected() {
                    MiraFileLogger.log(TAG, "✅ VANCH connected");
                    mainHandler.post(() -> {
                        GateTagListener l = currentListener;
                        if (l != null) l.onScanStarted();
                    });
                }

                @Override
                public void onDisconnected(String reason) {
                    MiraFileLogger.log(TAG,
                            "🔌 VANCH disconnected: " + reason);
                }

                @Override
                public void onTagRead(String epc, int rssi) {
                    if (epc == null || epc.trim().isEmpty()) return;

                    final String cleanEpc = epc.trim().toUpperCase();
                    mainHandler.post(() -> {
                        try {
                            GateTagListener l = currentListener;
                            if (l != null) l.onTagRead(cleanEpc, rssi);
                        } catch (Exception e) {
                            MiraFileLogger.logError(TAG,
                                    "VANCH onTagRead dispatch error", e);
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    MiraFileLogger.logError(TAG, "VANCH error: " + error);
                    notifyError("VANCH: " + error);
                }
            });

            vanchClient.connect();

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startVanchClient exception", e);
            notifyError("startVanchClient: " + e.getMessage());
            isScanning.set(false);
        }
    }

    /**
     * ✅ [v2.0] المسار الافتراضي (Chainway / Simulator / Bluetooth)
     *    — نفس منطق v1.0 دون تغيير
     */
    private void startDefaultReader() {
        try {
            RFIDManager manager = RFIDManager.getInstance(appContext);
            MiraIRFIDReader reader = manager.getActiveReader();

            if (reader == null) {
                MiraFileLogger.logWarn(TAG, "❌ No active reader");
                notifyError("No active RFID reader configured");
                return;
            }

            reader.setOnTagReadListener(readerListener);

            try {
                reader.setPower(currentPowerDbm);
                MiraFileLogger.log(TAG, "⚡ Power: "
                        + currentPowerDbm + " dBm");
            } catch (Exception e) {
                MiraFileLogger.logWarn(TAG,
                        "Set power failed: " + e.getMessage());
            }

            if (!reader.isConnected()) {
                MiraFileLogger.log(TAG, "🔌 Connecting reader...");
                if (!reader.initialize() || !reader.connect()) {
                    notifyError("Reader connect failed");
                    return;
                }
            }

            boolean ok = reader.startScan();
            isScanning.set(ok);

            if (ok) {
                MiraFileLogger.log(TAG, "🚀 Scan started ["
                        + reader.getClass().getSimpleName() + "]");
            } else {
                notifyError("Failed to start scan");
            }

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startDefaultReader exception", e);
            notifyError("startDefaultReader: " + e.getMessage());
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

    /**
     * ✅ [v2.0] يوقف VANCH إن كان نشطاً + القارئ الافتراضي
     */
    private void doStopScan() {
        if (!isScanning.getAndSet(false)) {
            MiraFileLogger.log(TAG, "⏹️ stopScan: skipped (already stopped)");
            return;
        }

        try {
            // ✅ أوقف VANCH
            if (vanchClient != null) {
                MiraFileLogger.log(TAG, "⏹️ Stopping VANCH client");
                try {
                    vanchClient.disconnect();
                } catch (Exception e) {
                    MiraFileLogger.logError(TAG,
                            "VANCH disconnect error", e);
                }
                vanchClient = null;
            }

            // ✅ أوقف القارئ الافتراضي إن كان نشطاً
            if ("default".equals(activeDeviceType)) {
                RFIDManager manager = RFIDManager.getInstance(appContext);
                MiraIRFIDReader reader = manager.getActiveReader();
                if (reader != null) {
                    reader.stopScan();
                    reader.setOnTagReadListener(null);
                }
            }

            activeDeviceType = null;
            MiraFileLogger.log(TAG, "⏹️ Scan stopped");

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStopScan error", e);
        }
    }

    /**
     * ✅ التمهيد لفتح الكاميرا:
     *   1. أوقف المسح
     *   2. انتظر 150ms (تحرير UART port)
     *   3. نفّذ الـ callback
     *
     *   يعمل مع VANCH والافتراضي بلا تعديل.
     */
    public void prepareForCamera(Runnable launchAction) {
        if (launchAction == null) return;

        MiraFileLogger.log(TAG, "📷 Preparing for camera...");

        stopScan(() -> mainHandler.postDelayed(() -> {
            try {
                MiraFileLogger.log(TAG,
                        "📷 UART released — launching camera");
                launchAction.run();
            } catch (Exception e) {
                MiraFileLogger.logError(TAG,
                        "prepareForCamera launch error", e);
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
            return reader instanceof
                    com.example.uhf.MiraRfid.readers.SimulatorReader;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ✅ [v2.0] نوع الجهاز النشط (للتشخيص والاختبار)
     * @return "vanch_fixed" | "default" | null
     */
    public String getActiveDeviceType() {
        return activeDeviceType;
    }

    /**
     * ✅ إغلاق نظيف
     */
    public void shutdown() {
        if (!isReleased.compareAndSet(false, true)) return;

        MiraFileLogger.log(TAG, "🛑 Shutting down GateRfidBridge");

        try { doStopScan(); } catch (Exception ignored) {}

        // ✅ تنظيف VANCH (احتياط إضافي)
        if (vanchClient != null) {
            try { vanchClient.disconnect(); } catch (Exception ignored) {}
            vanchClient = null;
        }

        try { hardwareExecutor.shutdownNow(); } catch (Exception ignored) {}

        currentListener = null;
        activeDeviceType = null;
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
