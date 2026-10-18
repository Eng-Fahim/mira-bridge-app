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
 *  GateRfidBridge v2.0 — VANCH Fixed Support
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  إصلاحات v2.0:
 *   ✅ اكتشاف تلقائي لنوع الجهاز (chainway_c72 vs vanch_fixed)
 *   ✅ VANCH Fixed يعمل عبر VanchNetworkClient (TCP)
 *   ✅ Chainway (C72) يعمل كما هو — لا تغيير
 *   ✅ VANCH: بدء/إيقاف فوري + auto-reconnect في الخلفية
 *   ✅ isScanning يعكس النية (لا يتأثر بانقطاع TCP مؤقت)
 *   ✅ دعم IP:port من MiraGatesSettings.GateDevice
 *
 *  التدفق:
 *  ────────
 *  MiraSecureGateFragment.startScanning()
 *      ↓
 *  GateRfidBridge.startScan(listener, power)
 *      ↓
 *  if (deviceType == "vanch_fixed")  →  VanchNetworkClient (TCP)
 *  else                              →  RFIDManager.getActiveReader()
 */
public class GateRfidBridge {

    private static final String TAG = "GateRfidBridge";
    private static final long CAMERA_PORT_RELEASE_MS = 150;
    private static final int MIN_POWER_DBM = 5;
    private static final int MAX_POWER_DBM = 30;

    public interface GateTagListener {
        void onTagRead(String epc, int rssi);
        void onScanStarted();
        void onScanStopped();
        void onError(String error);
    }

    // Singleton

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

    // Fields

    private final Context appContext;
    private final ExecutorService hardwareExecutor;
    private final Handler mainHandler;

    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean isReleased = new AtomicBoolean(false);

    private volatile GateTagListener currentListener;
    private volatile int currentPowerDbm = MAX_POWER_DBM;

    // VANCH state
    private volatile VanchNetworkClient vanchClient;
    private volatile boolean usingVanch = false;
    private volatile String vanchEndpoint = null;

    // Chainway listener adapter (يُستخدم فقط لـ Chainway)
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
            MiraFileLogger.log(TAG, "🔌 Chainway connection: " + connected);
        }

        @Override
        public void onError(String error) {
            MiraFileLogger.logError(TAG, "Chainway error: " + error);
            final String msg = error != null ? error : "Unknown error";
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onError(msg);
                } catch (Exception ignored) {}
            });
        }
    };

    // Constructor

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

    // Public API

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

    private void doStartScan() {
        try {
            // 1) اكتشف نوع الجهاز
            MiraGatesSettings.GateDevice activeGate = getActiveDeviceGate();

            if (activeGate != null && "vanch_fixed".equals(activeGate.deviceType)) {
                startVanchScan(activeGate);
                return;
            }

            // 2) Chainway (default)
            startChainwayScan();

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStartScan exception", e);
            notifyError("doStartScan: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  VANCH Fixed path
    // ═══════════════════════════════════════════════════════════

    private void startVanchScan(MiraGatesSettings.GateDevice gate) {
        final String ip = gate.ipAddress;
        if (ip == null || ip.trim().isEmpty()) {
            notifyError("VANCH gate: IP address not configured");
            return;
        }

        // إذا كان هناك client يعمل بنفس IP → أعد استخدامه (idempotent)
        if (vanchClient != null && usingVanch
                && ip.equals(vanchEndpoint) && vanchClient.isConnected()) {
            MiraFileLogger.log(TAG, "♻️ VANCH already connected — reuse");
            usingVanch = true;
            isScanning.set(true);
            notifyScanStarted();
            return;
        }

        // أوقف أي client سابق
        stopVanchClient();

        // إنشاء client جديد
        MiraFileLogger.log(TAG, "📡 Starting VANCH scan at " + ip);
        vanchEndpoint = ip;
        usingVanch = true;

        vanchClient = new VanchNetworkClient(ip,
                new VanchNetworkClient.VanchEventListener() {
                    @Override
                    public void onConnected() {
                        MiraFileLogger.log(TAG, "✅ VANCH connected: " + ip);
                        isScanning.set(true);
                        mainHandler.post(() -> {
                            if (currentListener != null) {
                                try { currentListener.onScanStarted(); } catch (Exception ignored) {}
                            }
                        });
                    }

                    @Override
                    public void onDisconnected(String reason) {
                        MiraFileLogger.logWarn(TAG,
                                "⚠️ VANCH disconnected: " + reason
                                + " (auto-reconnect in background)");
                        // لا نُعلِم المستمع — النية ما زالت "مسح"
                        // auto-reconnect يعمل في الخلفية
                    }

                    @Override
                    public void onTagRead(String epc, int rssi) {
                        if (epc == null || epc.length() < 16) return;
                        mainHandler.post(() -> {
                            if (currentListener != null) {
                                try { currentListener.onTagRead(epc, rssi); } catch (Exception ignored) {}
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        MiraFileLogger.logError(TAG, "VANCH error: " + error);
                        // لا نُعلِم المستمع بأخطاء عابرة — فقط سجل
                    }
                });

        vanchClient.connect();

        // نية المسح = true فوراً (قبل تأكيد TCP) — العميل يستعيد الاتصال تلقائياً
        isScanning.set(true);
        notifyScanStarted();
    }

    private void stopVanchClient() {
        VanchNetworkClient c = vanchClient;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) {}
            vanchClient = null;
        }
        vanchEndpoint = null;
    }

    // ═══════════════════════════════════════════════════════════
    //  Chainway path (as v1.0 — no changes)
    // ═══════════════════════════════════════════════════════════

    private void startChainwayScan() {
        try {
            RFIDManager manager = RFIDManager.getInstance(appContext);
            MiraIRFIDReader reader = manager.getActiveReader();

            if (reader == null) {
                MiraFileLogger.logWarn(TAG, "❌ No active reader — cannot start");
                notifyError("No active RFID reader configured");
                return;
            }

            reader.setOnTagReadListener(readerListener);

            try {
                reader.setPower(currentPowerDbm);
                MiraFileLogger.log(TAG, "⚡ Power set: " + currentPowerDbm + " dBm");
            } catch (Exception e) {
                MiraFileLogger.logWarn(TAG, "Failed to set power: " + e.getMessage());
            }

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

            boolean ok = reader.startScan();
            isScanning.set(ok);

            if (ok) {
                MiraFileLogger.log(TAG, "🚀 Chainway scan started");
            } else {
                MiraFileLogger.logWarn(TAG, "❌ Scan start returned false");
                notifyError("Failed to start scan");
            }

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startChainwayScan exception", e);
            notifyError("startChainwayScan: " + e.getMessage());
        }
    }

    // Stop

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
        if (!isScanning.getAndSet(false)) {
            MiraFileLogger.log(TAG, "⏹️ stopScan: already stopped");
            return;
        }

        try {
            if (usingVanch) {
                stopVanchClient();
                usingVanch = false;
                MiraFileLogger.log(TAG, "⏹️ VANCH stopped");
            } else {
                RFIDManager manager = RFIDManager.getInstance(appContext);
                MiraIRFIDReader reader = manager.getActiveReader();
                if (reader != null) {
                    reader.stopScan();
                    reader.setOnTagReadListener(null);
                    MiraFileLogger.log(TAG, "⏹️ Chainway stopped");
                }
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStopScan error", e);
        }

        mainHandler.post(() -> {
            if (currentListener != null) {
                try { currentListener.onScanStopped(); } catch (Exception ignored) {}
            }
        });
    }

    // Helpers

    public void prepareForCamera(Runnable launchAction) {
        if (launchAction == null) return;
        stopScan(() -> mainHandler.postDelayed(() -> {
            try { launchAction.run(); }
            catch (Exception e) { MiraFileLogger.logError(TAG, "camera launch error", e); }
        }, CAMERA_PORT_RELEASE_MS));
    }

    public boolean isScanning() {
        return isScanning.get();
    }

    public boolean isReaderReady() {
        try {
            if (usingVanch) {
                return vanchClient != null && vanchClient.isConnected();
            }
            RFIDManager manager = RFIDManager.getInstance(appContext);
            return manager != null && manager.getActiveReader() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSimulationMode() {
        try {
            if (usingVanch) return false;
            RFIDManager manager = RFIDManager.getInstance(appContext);
            if (manager == null) return false;
            MiraIRFIDReader reader = manager.getActiveReader();
            return reader instanceof com.example.uhf.MiraRfid.readers.SimulatorReader;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * هل الجهاز الحالي بوابة VANCH Fixed؟
     */
    public boolean isVanchMode() {
        return usingVanch;
    }

    public void shutdown() {
        if (!isReleased.compareAndSet(false, true)) return;
        MiraFileLogger.log(TAG, "🛑 Shutting down GateRfidBridge");

        try { doStopScan(); } catch (Exception ignored) {}
        try { hardwareExecutor.shutdownNow(); } catch (Exception ignored) {}

        currentListener = null;
        vanchClient = null;
        usingVanch = false;
        instance = null;
    }

    private void notifyError(String error) {
        final String msg = error != null ? error : "Unknown error";
        mainHandler.post(() -> {
            if (currentListener != null) {
                try { currentListener.onError(msg); } catch (Exception ignored) {}
            }
        });
    }

    private void notifyScanStarted() {
        mainHandler.post(() -> {
            if (currentListener != null) {
                try { currentListener.onScanStarted(); } catch (Exception ignored) {}
            }
        });
    }

    private int clampPower(int powerDbm) {
        if (powerDbm < MIN_POWER_DBM) return MIN_POWER_DBM;
        if (powerDbm > MAX_POWER_DBM) return MAX_POWER_DBM;
        return powerDbm;
    }

    /**
     * اقرأ البوابة النشطة من MiraGatesSettings بناءً على deviceGateId.
     */
    private MiraGatesSettings.GateDevice getActiveDeviceGate() {
        try {
            MiraGatesSettings settings = MiraGatesSettings.getInstance(appContext);
            if (settings == null) return null;

            String deviceGateId = settings.getDeviceGateId();
            if (deviceGateId == null || deviceGateId.trim().isEmpty()) {
                Log.d(TAG, "getActiveDeviceGate: deviceGateId is empty");
                return null;
            }

            java.util.List<MiraGatesSettings.GateDevice> gates = settings.getGates();
            if (gates == null || gates.isEmpty()) return null;

            for (MiraGatesSettings.GateDevice g : gates) {
                if (g == null) continue;
                if (g.gateId != null && g.gateId.equals(deviceGateId)) {
                    return g;
                }
            }

            Log.d(TAG, "getActiveDeviceGate: no gate matches '" + deviceGateId + "'");
            return null;

        } catch (Exception e) {
            Log.w(TAG, "getActiveDeviceGate error: " + e.getMessage());
            return null;
        }
    }
}
