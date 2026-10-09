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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  InventoryScanController v4.0 — Async-first, non-blocking
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * التغيير الجوهري عن v3.1:
 *
 *   ❌ v3.1: startReadingSource() كان يستدعي
 *            rfidManager.waitForReaderReady(3000) — وهي دالة
 *            تحجب الخيط الحالي (Thread.sleep) لمدة تصل إلى 3 ثوانٍ.
 *            وبما أن startReadingSource() تُستدعى من startScanning()
 *            التي تُستدعى من UI thread → UI مجمّد + ANR محتمل.
 *
 *   ✅ v4.0: startReadingSource() يستدعي
 *            rfidManager.startScan(callback) — وهي دالة
 *            غير حاجبة، تُفرّغ الطابور عبر mainHandler.post()
 *            بعد اكتمال ensureReady في initExecutor.
 *
 * كل ما عدا ذلك محفوظ حرفياً من v3.1:
 *   ✅ ScanCallback interface بلا تغيير
 *   ✅ startScanning/stopScanning بلا تغيير في التوقيع
 *   ✅ Debounce + generation tracking بلا تغيير
 *   ✅ OnTagReadListener implementations بلا تغيير
 *   ✅ cleanup() بلا تغيير
 */
public class InventoryScanController implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "InventoryScanCtrl";

    private static final long TAG_READ_DEBOUNCE_MS = 500;
    private static final int MAX_CACHE_ITEMS = 5000;

    private final Context context;
    private final boolean isArabic;
    private final ExecutorService processingExecutor;
    private final ExecutorService ioExecutor;
    private final Handler mainHandler;

    // ✅ المكونات
    private RFIDManager rfidManager;
    private SoundManager soundManager;
    private MiraInventoryEngine inventoryEngine;
    private Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();

    // ✅ حالة ذرية
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicInteger scanGeneration = new AtomicInteger(0);
    private volatile long scanStartTime = 0;
    private volatile int currentPowerDbm = 27;

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
    }

    public void setCallback(ScanCallback callback) { this.callback = callback; }
    public void setSoundManager(SoundManager sm) { this.soundManager = sm; }
    public void setInventoryEngine(MiraInventoryEngine engine) { this.inventoryEngine = engine; }
    public void setBarcodeToEpcMap(Map<String, String> map) {
        this.barcodeToEpcMap = map != null ? map : new ConcurrentHashMap<>();
    }

    /**
     * ✅ ربط RFIDManager + تسجيل هذا الـ Controller كـ listener
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

    /**
     * ✅ بدء جلسة المسح.
     *
     * ملاحظة على قيمة الإرجاع:
     *   - true  = الطلب قُبل وسيبدأ المسح لا حقاً (عبر callback.onScanStarted).
     *   - false = فشل فوري (مسح جارٍ مسبقاً، أو rfidManager غير مُرفق).
     *
     * النجاح/الفشل الفعلي للعتاد يأتي عبر:
     *   - callback.onScanStarted()  → نجاح
     *   - callback.onError(...)     → فشل
     */
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

            // ✅ إشعار المستمع فوراً بأن الجلسة بدأت (منطقياً)
            if (callback != null) {
                mainHandler.post(() -> {
                    if (generation == scanGeneration.get()) {
                        callback.onScanStarted();
                        callback.onExpectedCountChanged(catalogEpcs.size());
                    }
                });
            }

            // ✅ بدء العتاد — غير حاجب
            startReadingSource(generation);

            return true;

        } catch (Exception e) {
            Log.e(TAG, "startScanning error: " + e.getMessage(), e);
            isScanning.set(false);
            notifyErrorAsync(generation, e.getMessage() != null
                    ? e.getMessage()
                    : "startScanning exception");
            return false;
        }
    }

    // ==================== Stop ====================

    public void stopScanning() {
        if (!isScanning.compareAndSet(true, false)) return;

        final int generation = scanGeneration.incrementAndGet();
        Log.d(TAG, "⏹️ Stop scanning [gen=" + generation + "]");

        try {
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

    // ================================================================
    //  ⚙️ Reading Source — الإصلاح الجوهري
    // ================================================================

    /**
     * ✅ [v4.0] بدء العتاد بشكل غير حاجب (non-blocking).
     *
     * التسلسل:
     *   1. rfidManager.startScan(cb) — تُفوّض المهمة لـ RFIDManager
     *   2. RFIDManager يستدعي ensureReady(cb) داخلياً
     *   3. ensureReady:
     *      - إذا كان جاهزاً → mainHandler.post(cb.onReady)
     *      - إذا كان يُهيّأ → تُضاف cb للطابور
     *      - إذا كان UNINITIALIZED/ERROR → تبدأ تهيئة على initExecutor
     *   4. عندما يصبح القارئ جاهزاً، cb.onReady() يُستدعى
     *   5. cb.onReady يستدعي reader.startScan()
     *
     * لا يوجد Thread.sleep()، لا حجب على UI thread.
     */
    private void startReadingSource(int generation) {
        if (rfidManager == null) {
            Log.e(TAG, "❌ rfidManager is null — cannot start");
            notifyErrorAsync(generation, isArabic
                    ? "مدير القارئ غير متاح"
                    : "RFID manager unavailable");
            return;
        }

        Log.d(TAG, "⏳ Calling rfidManager.startScan (non-blocking)...");

        rfidManager.startScan(new RFIDManager.ReadyCallback() {
            @Override
            public void onReady(MiraIRFIDReader reader) {
                // تم بدء العتاد بنجاح
                if (generation != scanGeneration.get()) {
                    Log.d(TAG, "⏭️ Ignoring onReady for stale generation="
                            + generation + " (current=" + scanGeneration.get() + ")");
                    return;
                }

                Log.i(TAG, "✅ Hardware scan started [gen=" + generation + "]");

                if (soundManager != null) {
                    try {
                        soundManager.playBeep(SoundManager.FeedbackType.KNOWN_ITEM);
                    } catch (Exception e) {
                        Log.w(TAG, "playBeep error", e);
                    }
                }
            }

            @Override
            public void onError(String reason) {
                // فشل بدء العتاد
                if (generation != scanGeneration.get()) {
                    Log.d(TAG, "⏭️ Ignoring onError for stale generation="
                            + generation);
                    return;
                }

                Log.e(TAG, "❌ Hardware start failed [gen=" + generation + "]: "
                        + reason);
                notifyErrorAsync(generation, reason);
            }
        });
    }

    /**
     * ✅ إشعار الفشل — يضبط isScanning = false ويُرسل onError + onScanStopped
     */
    private void notifyErrorAsync(int generation, String reason) {
        isScanning.set(false);

        final String safeReason = (reason != null && !reason.isEmpty())
                ? reason
                : (isArabic ? "فشل بدء القارئ — تحقق من الاتصال"
                            : "Reader failed to start — check connection");

        if (callback != null) {
            mainHandler.post(() -> {
                if (generation != scanGeneration.get()) return;
                try {
                    callback.onError(safeReason);
                    callback.onScanStopped();
                } catch (Exception e) {
                    Log.e(TAG, "callback error notification failed", e);
                }
            });
        }
    }

    // ============================================================
    // ✅ RFID Listener Overrides — كل الدوال الخمس (بلا تغيير)
    // ============================================================

    @Override
    public void onTagRead(String rawEpc, int rssi) {
        if (!isScanning.get() || rawEpc == null || rawEpc.isEmpty()) return;

        final int generation = scanGeneration.get();

        if (processingExecutor == null || processingExecutor.isShutdown()) return;

        processingExecutor.execute(() -> {
            if (generation != scanGeneration.get()) return;

            final String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(
                    rawEpc, barcodeToEpcMap);

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

            try {
                if (inventoryEngine != null) {
                    inventoryEngine.onTagScanned(normalizedEpc, rssi);
                }
            } catch (Exception e) {
                Log.e(TAG, "engine error", e);
            }

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

    @Override
    public void onScanStarted() {
        Log.d(TAG, "📡 RFID onScanStarted");
        // لا حاجة لإشعار callback — تم عبر startScanning() عند قبول الطلب
    }

    @Override
    public void onScanStopped() {
        Log.d(TAG, "📡 RFID onScanStopped");
        // لا حاجة لإشعار callback — تم عبر stopScanning() أو notifyErrorAsync()
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        Log.d(TAG, "📡 RFID Connection: " + connected);
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    callback.onConnectionChanged(connected);
                } catch (Exception e) {
                    Log.e(TAG, "callback.onConnectionChanged error: " + e.getMessage());
                }
            });
        }
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "📡 RFID Error: " + error);
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

    // ==================== Helper ====================

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
            if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
            recentTagReads.clear();
            catalogEpcs.clear();
        } catch (Exception e) {
            Log.e(TAG, "cleanup error: " + e.getMessage());
        }
    }
}
