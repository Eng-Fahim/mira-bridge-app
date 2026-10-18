package com.example.uhf.MiraRfid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.utils.MiraFileLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  GateRfidBridge v3.0 — Multi-Gate Router
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  v3.0:
 *  ─────
 *  ✅ N VANCH clients متزامن (Map<gateId, Client>)
 *  ✅ onTagRead(gateId, epc, rssi) — يعرف المصدر
 *  ✅ قارئ افتراضي واحد (Chainway/Simulator) fallback
 *  ✅ reloadGates() — إعادة تحميل أثناء التشغيل
 *  ✅ stopAllGates / shutdown نظيف
 *
 *  v2.0 محفوظ:
 *  ─────────────
 *  ✅ getLinkedGates() بدلاً من getActiveDeviceGate()
 *  ✅ Threading آمن (executor أحادي)
 *  ✅ onTagRead عبر main thread
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class GateRfidBridge {

    private static final String TAG = "GateRfidBridge";

    private static final long CAMERA_PORT_RELEASE_MS = 150;
    private static final int MIN_POWER_DBM = 5;
    private static final int MAX_POWER_DBM = 30;

    // ═══════════════════════════════════════════════════════════
    //  واجهة المستمع
    // ═══════════════════════════════════════════════════════════

    public interface GateTagListener {
        void onTagRead(String gateId, String epc, int rssi);
        void onScanStarted(String gateId);
        void onScanStopped(String gateId);
        void onError(String gateId, String error);
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

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isReleased = new AtomicBoolean(false);

    private volatile GateTagListener currentListener;
    private volatile int currentPowerDbm = MAX_POWER_DBM;

    // ✅ [v3.0] عملاء VANCH متعددون
    private final Map<String, VanchNetworkClient> vanchClients
            = new ConcurrentHashMap<>();

    // ✅ قارئ افتراضي واحد
    private volatile MiraIRFIDReader defaultReader;
    private volatile boolean defaultReaderActive = false;

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
        MiraFileLogger.log(TAG, "✅ GateRfidBridge v3.0 initialized");
    }

    // ═══════════════════════════════════════════════════════════
    //  Default Reader Listener Adapter
    // ═══════════════════════════════════════════════════════════

    private final MiraIRFIDReader.OnTagReadListener defaultReaderListener =
            new MiraIRFIDReader.OnTagReadListener() {

        @Override
        public void onTagRead(String epc, int rssi) {
            if (epc == null || epc.trim().isEmpty()) return;
            final String cleanEpc = epc.trim().toUpperCase();
            final String gateId = "__default__";
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onTagRead(gateId, cleanEpc, rssi);
                } catch (Exception e) {
                    MiraFileLogger.logError(TAG, "default onTagRead error", e);
                }
            });
        }

        @Override
        public void onScanStarted() {
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onScanStarted("__default__");
                } catch (Exception ignored) {}
            });
        }

        @Override
        public void onScanStopped() {
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onScanStopped("__default__");
                } catch (Exception ignored) {}
            });
        }

        @Override
        public void onConnectionChanged(boolean connected) {
            MiraFileLogger.log(TAG, "🔌 Default reader connected=" + connected);
        }

        @Override
        public void onError(String error) {
            final String msg = (error != null) ? error : "Unknown";
            mainHandler.post(() -> {
                try {
                    GateTagListener l = currentListener;
                    if (l != null) l.onError("__default__", msg);
                } catch (Exception ignored) {}
            });
        }
    };

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ [v3.0] بدء جميع البوابات المربوطة
     */
    public boolean startAllGates(GateTagListener listener, int powerDbm) {
        if (isReleased.get()) {
            MiraFileLogger.logWarn(TAG, "❌ Bridge released");
            return false;
        }
        if (listener == null) {
            MiraFileLogger.logWarn(TAG, "❌ null listener");
            return false;
        }

        currentListener = listener;
        currentPowerDbm = clampPower(powerDbm);

        try {
            hardwareExecutor.execute(this::doStartAllGates);
            return true;
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startAllGates dispatch error", e);
            return false;
        }
    }

    private void doStartAllGates() {
        if (!isRunning.compareAndSet(false, true)) {
            MiraFileLogger.log(TAG, "⏭️ Already running — skip start");
            return;
        }

        try {
            MiraGatesSettings settings = MiraGatesSettings.getInstance(appContext);
            List<MiraGatesSettings.GateDevice> linked = settings.getLinkedGates();

            if (linked.isEmpty()) {
                MiraFileLogger.log(TAG, "⚠️ No linked gates — fallback to default");
                startDefaultReader();
                return;
            }

            MiraFileLogger.log(TAG,
                    "🎯 Starting " + linked.size() + " linked gate(s)");

            for (MiraGatesSettings.GateDevice gate : linked) {
                if (gate == null || gate.gateId == null) continue;
                if ("vanch_fixed".equals(gate.deviceType)) {
                    startVanchClient(gate);
                } else {
                    if (!defaultReaderActive) {
                        startDefaultReader();
                    }
                }
            }

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStartAllGates exception", e);
            notifyError("__all__", "doStartAllGates: " + e.getMessage());
        }
    }

    private void startVanchClient(MiraGatesSettings.GateDevice gate) {
        try {
            String endpoint = gate.ipAddress;
            String gateId = gate.gateId;
            String gateName = gate.gateName != null ? gate.gateName : gateId;

            if (endpoint == null || endpoint.trim().isEmpty()) {
                notifyError(gateId, "No IP for VANCH: " + gateName);
                return;
            }

            if (vanchClients.containsKey(gateId)) {
                MiraFileLogger.log(TAG, "⏭️ VANCH already active: " + gateName);
                return;
            }

            MiraFileLogger.log(TAG,
                    "🚀 VANCH [" + gateName + "] → " + endpoint);

            VanchNetworkClient client = new VanchNetworkClient(
                    endpoint, gateId,
                    createVanchListener(gateId, gateName));

            vanchClients.put(gateId, client);
            client.connect();

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startVanchClient error", e);
            notifyError(gate.gateId, "VANCH start: " + e.getMessage());
        }
    }

    private VanchNetworkClient.VanchEventListener createVanchListener(
            final String gateId, final String gateName) {
        return new VanchNetworkClient.VanchEventListener() {
            @Override
            public void onConnected() {
                MiraFileLogger.log(TAG, "✅ VANCH connected: " + gateName);
                mainHandler.post(() -> {
                    GateTagListener l = currentListener;
                    if (l != null) l.onScanStarted(gateId);
                });
            }

            @Override
            public void onDisconnected(String reason) {
                MiraFileLogger.log(TAG,
                        "🔌 VANCH [" + gateName + "] disconnected: " + reason);
            }

            @Override
            public void onTagRead(String epc, int rssi) {
                if (epc == null || epc.trim().isEmpty()) return;
                final String cleanEpc = epc.trim().toUpperCase();
                mainHandler.post(() -> {
                    try {
                        GateTagListener l = currentListener;
                        if (l != null) l.onTagRead(gateId, cleanEpc, rssi);
                    } catch (Exception e) {
                        MiraFileLogger.logError(TAG,
                                "VANCH onTagRead error [" + gateName + "]", e);
                    }
                });
            }

            @Override
            public void onError(String error) {
                MiraFileLogger.logError(TAG,
                        "VANCH error [" + gateName + "]: " + error);
                notifyError(gateId, "VANCH: " + error);
            }
        };
    }

    private void startDefaultReader() {
        try {
            RFIDManager manager = RFIDManager.getInstance(appContext);
            MiraIRFIDReader reader = manager.getActiveReader();

            if (reader == null) {
                notifyError("__default__", "No default reader available");
                return;
            }

            defaultReader = reader;
            reader.setOnTagReadListener(defaultReaderListener);

            try {
                reader.setPower(currentPowerDbm);
            } catch (Exception e) {
                MiraFileLogger.logWarn(TAG, "setPower failed: " + e.getMessage());
            }

            if (!reader.isConnected()) {
                if (!reader.initialize() || !reader.connect()) {
                    notifyError("__default__", "Default reader connect failed");
                    return;
                }
            }

            boolean ok = reader.startScan();
            defaultReaderActive = ok;

            if (ok) {
                MiraFileLogger.log(TAG, "🚀 Default reader scanning ["
                        + reader.getClass().getSimpleName() + "]");
                mainHandler.post(() -> {
                    GateTagListener l = currentListener;
                    if (l != null) l.onScanStarted("__default__");
                });
            } else {
                notifyError("__default__", "Default startScan returned false");
            }

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "startDefaultReader error", e);
            notifyError("__default__", "startDefaultReader: " + e.getMessage());
        }
    }

    /**
     * ✅ [v3.0] إيقاف جميع البوابات
     */
    public void stopAllGates(Runnable onComplete) {
        try {
            hardwareExecutor.execute(() -> {
                try {
                    doStopAllGates();
                } finally {
                    if (onComplete != null) mainHandler.post(onComplete);
                }
            });
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "stopAllGates dispatch error", e);
            if (onComplete != null) mainHandler.post(onComplete);
        }
    }

    private void doStopAllGates() {
        if (!isRunning.getAndSet(false)) {
            MiraFileLogger.log(TAG, "⏹️ Already stopped");
            return;
        }

        try {
            for (Map.Entry<String, VanchNetworkClient> entry :
                    vanchClients.entrySet()) {
                try {
                    entry.getValue().disconnect();
                    MiraFileLogger.log(TAG,
                            "⏹️ VANCH stopped: " + entry.getKey());
                } catch (Exception e) {
                    MiraFileLogger.logError(TAG,
                            "VANCH disconnect error " + entry.getKey(), e);
                }
            }
            vanchClients.clear();

            if (defaultReaderActive && defaultReader != null) {
                try {
                    defaultReader.stopScan();
                    defaultReader.setOnTagReadListener(null);
                    MiraFileLogger.log(TAG, "⏹️ Default reader stopped");
                } catch (Exception e) {
                    MiraFileLogger.logError(TAG,
                            "Default reader stop error", e);
                }
            }
            defaultReaderActive = false;

            MiraFileLogger.log(TAG, "⏹️ All gates stopped");

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "doStopAllGates error", e);
        }
    }

    /**
     * ✅ [v3.0] إعادة تحميل قائمة البوابات أثناء التشغيل
     */
    public void reloadGates() {
        MiraFileLogger.log(TAG, "🔄 Reloading gates");
        stopAllGates(() -> {
            try {
                hardwareExecutor.execute(this::doStartAllGates);
            } catch (Exception e) {
                MiraFileLogger.logError(TAG, "reloadGates error", e);
            }
        });
    }

    /** ✅ توافق عكسي — يستدعي startAllGates */
    public boolean startScan(GateTagListener listener, int powerDbm) {
        return startAllGates(listener, powerDbm);
    }

    /** ✅ توافق عكسي — يستدعي stopAllGates */
    public void stopScan(Runnable onComplete) {
        stopAllGates(onComplete);
    }

    /** ✅ التمهيد للكاميرا */
    public void prepareForCamera(Runnable launchAction) {
        if (launchAction == null) return;
        MiraFileLogger.log(TAG, "📷 Preparing for camera...");
        stopAllGates(() -> mainHandler.postDelayed(() -> {
            try {
                launchAction.run();
            } catch (Exception e) {
                MiraFileLogger.logError(TAG, "camera launch error", e);
            }
        }, CAMERA_PORT_RELEASE_MS));
    }

    public boolean isScanning() {
        return isRunning.get();
    }

    public int getActiveVanchCount() {
        return vanchClients.size();
    }

    public boolean isDefaultReaderActive() {
        return defaultReaderActive;
    }

    public boolean isReaderReady() {
        if (!vanchClients.isEmpty() || defaultReaderActive) return true;
        try {
            MiraGatesSettings settings = MiraGatesSettings.getInstance(appContext);
            return !settings.getLinkedGates().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

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

    public void shutdown() {
        if (!isReleased.compareAndSet(false, true)) return;
        MiraFileLogger.log(TAG, "🛑 Shutting down GateRfidBridge v3.0");

        try { doStopAllGates(); } catch (Exception ignored) {}
        vanchClients.clear();

        try { hardwareExecutor.shutdownNow(); } catch (Exception ignored) {}

        currentListener = null;
        defaultReader = null;
        defaultReaderActive = false;
        instance = null;
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void notifyError(String gateId, String error) {
        final String gid = (gateId != null) ? gateId : "__all__";
        final String msg = (error != null) ? error : "Unknown";
        mainHandler.post(() -> {
            try {
                GateTagListener l = currentListener;
                if (l != null) l.onError(gid, msg);
            } catch (Exception ignored) {}
        });
    }

    private int clampPower(int powerDbm) {
        if (powerDbm < MIN_POWER_DBM) return MIN_POWER_DBM;
        if (powerDbm > MAX_POWER_DBM) return MAX_POWER_DBM;
        return powerDbm;
    }
}
