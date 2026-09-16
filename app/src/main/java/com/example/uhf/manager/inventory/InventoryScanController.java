package com.example.uhf.manager.inventory;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.utils.RFIDCodeUtils;
import com.example.uhf.utils.SoundManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ✅ InventoryScanController v2.0 — النسخة النهائية
 *
 * المسؤول الوحيد عن كل منطق المسح:
 * - استقبال onTagRead من RFID Reader
 * - Normalize + Debounce
 * - تمرير إلى Engine
 * - تشغيل المحاكاة
 * - إدارة generation counter
 *
 * Fragment = UI فقط، يستقبل callbacks.
 */
public class InventoryScanController implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "InventoryScanCtrl";

    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long SIMULATION_MAX_ADDITIONAL_DELAY_MS = 350;
    private static final long TAG_READ_DEBOUNCE_MS = 500;
    private static final int MAX_CACHE_ITEMS = 5000;

    private final Context context;
    private final boolean isArabic;
    private final ExecutorService processingExecutor;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler;
    private final Handler simulationHandler;

    // ✅ المكونات
    private RFIDManager rfidManager;
    private SoundManager soundManager;
    private MiraInventoryEngine inventoryEngine;
    private Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();

    // ✅ حالة ذرية
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicInteger scanGeneration = new AtomicInteger(0);
    private volatile long scanStartTime = 0;
    private volatile int currentPowerDbm = 26;

    // ✅ Simulation
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010"
    };

    // ✅ Caches
    private final Set<String> catalogEpcs =
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> recentTagReads = new ConcurrentHashMap<>();

    // ==================== Callbacks ====================

    public interface ScanCallback {
        void onScanStarted();
        void onScanStopped();
        void onTagRead(String normalizedEpc, int rssi);
        void onDuplicateTag(String epc);
        void onError(String error);
        void onConnectionChanged(boolean isConnected);
        void onTagDebounced(String epc);
        void onExpectedCountChanged(int expectedCount);
    }

    private ScanCallback callback;

    // ==================== Constructor ====================

    public InventoryScanController(Context context,
                                    boolean isArabic,
                                    ExecutorService processingExecutor,
                                    ExecutorService ioExecutor) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
        this.processingExecutor = processingExecutor;
        this.ioExecutor = ioExecutor;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.simulationHandler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(ScanCallback callback) { this.callback = callback; }
    public void setSoundManager(SoundManager sm) { this.soundManager = sm; }
    public void setInventoryEngine(MiraInventoryEngine engine) { this.inventoryEngine = engine; }
    public void setBarcodeToEpcMap(Map<String, String> map) {
        this.barcodeToEpcMap = map != null ? map : new ConcurrentHashMap<>();
    }

    /**
     * ✅ ربط RFID Manager + تسجيل الـ listener هنا
     */
    public void attachRfidManager(RFIDManager rfidManager) {
        this.rfidManager = rfidManager;
        if (rfidManager != null) {
            rfidManager.setListener(this);
            Log.d(TAG, "✅ RFID listener attached to ScanController");
        }
    }

    public void detachRfidManager() {
        if (rfidManager != null) {
            rfidManager.setListener(null);
            rfidManager = null;
        }
    }

    // ==================== Getters ====================

    public boolean isScanning() { return isScanning.get(); }
    public long getScanStartTime() { return scanStartTime; }
    public int getCurrentPowerDbm() { return currentPowerDbm; }
    public void setCurrentPowerDbm(int p) { this.currentPowerDbm = p; }

    // ==================== Start ====================

    public boolean startScanning(Map<String, InventoryItem> catalog) {
        if (!isScanning.compareAndSet(false, true)) {
            Log.w(TAG, "startScanning ignored: already scanning");
            return false;
        }

        final int generation = scanGeneration.incrementAndGet();
        Log.d(TAG, "▶️ Start scanning [gen=" + generation + "]");

        try {
            scanStartTime = SystemClock.elapsedRealtime();
            recentTagReads.clear();

            catalogEpcs.clear();
            if (catalog != null && !catalog.isEmpty()) {
                catalogEpcs.addAll(catalog.keySet());
            }

            if (inventoryEngine != null) {
                inventoryEngine.startSession(catalog);
            }

            startReadingSource(generation);

            if (callback != null) {
                mainHandler.post(() -> {
                    if (generation == scanGeneration.get()) {
                        callback.onScanStarted();
                        callback.onExpectedCountChanged(catalogEpcs.size());
                    }
                });
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, "startScanning error: " + e.getMessage(), e);
            isScanning.set(false);
            return false;
        }
    }

    // ==================== Stop ====================

    public void stopScanning() {
        if (!isScanning.compareAndSet(true, false)) return;

        final int generation = scanGeneration.incrementAndGet();
        Log.d(TAG, "⏹️ Stop scanning [gen=" + generation + "]");

        try {
            stopSimulationLoop();

            if (rfidManager != null) {
                try { rfidManager.stopScan(); }
                catch (Exception e) { Log.e(TAG, "stopScan error", e); }
            }

            if (inventoryEngine != null && processingExecutor != null
                    && !processingExecutor.isShutdown()) {
                processingExecutor.execute(() -> {
                    try {
                        inventoryEngine.processSessionClose();
                    } catch (Exception e) {
                        Log.e(TAG, "processSessionClose error", e);
                    }
                });
            }

            if (callback != null) {
                mainHandler.post(() -> {
                    if (generation == scanGeneration.get()) {
                        callback.onScanStopped();
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "stopScanning error: " + e.getMessage(), e);
        }
    }

    // ==================== Reading Source ====================

    private void startReadingSource(int generation) {
        boolean hardwareStarted = false;

        if (rfidManager != null) {
            try {
                if (rfidManager.getActiveDevice() != null) {
                    MiraIRFIDReader.ReaderType type =
                            rfidManager.getActiveDevice().getType();
                    if (type != MiraIRFIDReader.ReaderType.SIMULATOR) {
                        hardwareStarted = rfidManager.startScan();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "hardware start error", e);
            }
        }

        if (!hardwareStarted) {
            startSimulationLoop(generation);
            Log.d(TAG, "🎯 Simulation mode started");
        }

        if (soundManager != null) {
            soundManager.playBeep(SoundManager.FeedbackType.KNOWN_ITEM);
        }
    }

    // ==================== RFID Listener Overrides ====================

    /**
     * ✅ يُستدعى من RFID Reader عند قراءة وسم
     */
    @Override
    public void onTagRead(String rawEpc, int rssi) {
        if (!isScanning.get() || rawEpc == null || rawEpc.isEmpty()) return;

        final int generation = scanGeneration.get();

        if (processingExecutor == null || processingExecutor.isShutdown()) return;

        processingExecutor.execute(() -> {
            if (generation != scanGeneration.get()) return;

            final String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(
                    rawEpc, barcodeToEpcMap);

            // ✅ فلترة الفئة
            if (!catalogEpcs.isEmpty() && !catalogEpcs.contains(normalizedEpc)) {
                return;
            }

            // ✅ Debounce
            long now = System.currentTimeMillis();
            Long last = recentTagReads.get(normalizedEpc);
            if (last != null && (now - last) < TAG_READ_DEBOUNCE_MS) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        if (generation == scanGeneration.get()) {
                            callback.onTagDebounced(normalizedEpc);
                        }
                    });
                }
                return;
            }
            recentTagReads.put(normalizedEpc, now);

            if (recentTagReads.size() > MAX_CACHE_ITEMS) {
                cleanOldTagReads(now);
            }

            // ✅ إلى Engine
            try {
                if (inventoryEngine != null) {
                    inventoryEngine.onTagScanned(normalizedEpc, rssi);
                }
            } catch (Exception e) {
                Log.e(TAG, "engine error", e);
            }

            // ✅ Callback للـ UI
            if (callback != null) {
                final int finalRssi = rssi;
                mainHandler.post(() -> {
                    if (generation == scanGeneration.get()) {
                        callback.onTagRead(normalizedEpc, finalRssi);
                    }
                });
            }
        });
    }

    /**
     * ✅ يُستدعى من RFID Reader عند حدوث خطأ
     */
    @Override
    public void onError(String error) {
        Log.e(TAG, "RFID Error: " + error);
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.onError(error);
                } catch (Exception e) {
                    Log.e(TAG, "callback.onError error: " + e.getMessage());
                }
            });
        }
    }

    private void cleanOldTagReads(long now) {
        try {
            java.util.Iterator<Map.Entry<String, Long>> it =
                    recentTagReads.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > TAG_READ_DEBOUNCE_MS * 2) it.remove();
            }
        } catch (Exception ignored) {}
    }

    // ==================== Simulation ====================

    private void startSimulationLoop(int generation) {
        simulationIndex = 0;

        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (generation != scanGeneration.get() || !isScanning.get()) return;

                String epc;
                if (!catalogEpcs.isEmpty()) {
                    List<String> list = new ArrayList<>(catalogEpcs);
                    epc = list.get(simulationIndex % list.size());
                } else {
                    epc = simulationTags[simulationIndex % simulationTags.length];
                }

                int rssi = -40 - (int) (Math.random() * 40);

                // ✅ نمرر عبر onTagRead المعياري
                onTagRead(epc, rssi);

                simulationIndex++;
                long delay = SIMULATION_MIN_DELAY_MS +
                        (long) (Math.random() * SIMULATION_MAX_ADDITIONAL_DELAY_MS);

                if (isScanning.get() && generation == scanGeneration.get()) {
                    simulationHandler.postDelayed(this, delay);
                }
            }
        };

        simulationHandler.postDelayed(simulationRunnable, SIMULATION_INITIAL_DELAY_MS);
    }

    private void stopSimulationLoop() {
        try {
            if (simulationHandler != null && simulationRunnable != null) {
                simulationHandler.removeCallbacks(simulationRunnable);
                simulationRunnable = null;
            }
        } catch (Exception ignored) {}
    }

    // ==================== Notifications ====================

    public void notifyConnectionChanged(boolean isConnected) {
        if (callback != null) {
            mainHandler.post(() -> callback.onConnectionChanged(isConnected));
        }
    }

    public void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }

    // ==================== Cleanup ====================

    public void cleanup() {
        try {
            stopScanning();
            detachRfidManager();
            if (simulationHandler != null) simulationHandler.removeCallbacksAndMessages(null);
            if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
            recentTagReads.clear();
            catalogEpcs.clear();
        } catch (Exception e) {
            Log.e(TAG, "cleanup error: " + e.getMessage());
        }
    }
}
