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
import com.example.uhf.utils.SoundManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * ✅ InventoryScanController
 * 
 * مسؤول عن:
 * - بدء/إيقاف المسح العادي
 * - بدء/إيقاف الجرد الأعمى
 * - بدء/إيقاف الجرد الفئوي
 * - تشغيل/إيقاف Simulation Loop
 * - معالجة قراءة الوسم (onTagRead)
 * 
 * لا يحتوي على مراجع للـ UI → آمن من التسريبات
 * يستخدم Callbacks لتحديث الـ UI
 */
public class InventoryScanController {

    private static final String TAG = "InventoryScanCtrl";

    // ✅ ثوابت الأداء
    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long SIMULATION_MAX_ADDITIONAL_DELAY_MS = 350;
    private static final long TAG_READ_DEBOUNCE_MS = 500;
    private static final int MAX_CACHE_ITEMS = 5000;

    private final Context context;
    private final boolean isArabic;
    private final ExecutorService backgroundExecutor;
    private final Handler mainHandler;
    private final Handler simulationHandler;

    // ✅ المكونات
    private RFIDManager rfidManager;
    private SoundManager soundManager;
    private MiraInventoryEngine inventoryEngine;

    // ✅ الحالة
    private volatile boolean isScanning = false;
    private long scanStartTime = 0;
    private int currentPowerDbm = 26;

    // ✅ Simulation
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010"
    };

    // ✅ Caches
    private final Set<String> catalogEpcs = 
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> recentTagReads = new ConcurrentHashMap<>();

    // ==================== Callbacks ====================

    public interface ScanCallback {
        /**
         * بدأ المسح (UI should update)
         */
        void onScanStarted();
        
        /**
         * توقف المسح
         */
        void onScanStopped();
        
        /**
         * تم قراءة وسم جديد
         */
        void onTagRead(String normalizedEpc, int rssi);
        
        /**
         * تم قراءة وسم مكرر
         */
        void onDuplicateTag(String epc);
        
        /**
         * خطأ في القارئ
         */
        void onError(String error);
        
        /**
         * تغيّرت حالة الاتصال
         */
        void onConnectionChanged(boolean isConnected);
        
        /**
         * تم تجاوز حد الـ debounce
         */
        void onTagDebounced(String epc);
    }

    private ScanCallback callback;

    // ==================== Constructor ====================

    public InventoryScanController(Context context, 
                                    boolean isArabic,
                                    ExecutorService backgroundExecutor) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
        this.backgroundExecutor = backgroundExecutor;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.simulationHandler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(ScanCallback callback) {
        this.callback = callback;
    }

    public void setSoundManager(SoundManager soundManager) {
        this.soundManager = soundManager;
    }

    public void setInventoryEngine(MiraInventoryEngine engine) {
        this.inventoryEngine = engine;
    }

    public void setRfidManager(RFIDManager rfidManager) {
        this.rfidManager = rfidManager;
    }

    public void setCatalogEpcs(Set<String> epcs) {
        catalogEpcs.clear();
        if (epcs != null) {
            catalogEpcs.addAll(epcs);
        }
    }

    // ==================== Getters ====================

    public boolean isScanning() {
        return isScanning;
    }

    public long getScanStartTime() {
        return scanStartTime;
    }

    public int getCurrentPowerDbm() {
        return currentPowerDbm;
    }

    public void setCurrentPowerDbm(int power) {
        this.currentPowerDbm = power;
    }
    
    // ==================== Start Scan ====================
    
    /**
     * ✅ بدء المسح
     * 
     * @param catalog كتالوج القطع المتوقعة
     * @param onReady callback بعد تحميل الكتالوج
     */
    public void startScanning(Map<String, InventoryItem> catalog, Runnable onReady) {
        try {
            if (isScanning) return;
            
            // ✅ إعادة تعيين
            isScanning = true;
            scanStartTime = SystemClock.elapsedRealtime();
            recentTagReads.clear();
            
            // ✅ تعيين الكتالوج
            catalogEpcs.clear();
            if (catalog != null) {
                catalogEpcs.addAll(catalog.keySet());
            }
            
            // ✅ بدء الجلسة في الـ Engine
            if (inventoryEngine != null) {
                inventoryEngine.startSession(catalog);
            }
            
            // ✅ بدء مصدر القراءة
            startReadingSource();
            
            // ✅ Callback
            if (callback != null) {
                mainHandler.post(callback::onScanStarted);
            }
            
            if (onReady != null) {
                mainHandler.post(onReady);
            }
            
            Log.d(TAG, "✅ Scanning started");
            
        } catch (Exception e) {
            Log.e(TAG, "startScanning error: " + e.getMessage(), e);
            isScanning = false;
        }
    }
    
    /**
     * ✅ بدء الجرد الأعمى
     */
    public void startBlindInventory() {
        try {
            if (isScanning) return;
            
            isScanning = true;
            scanStartTime = SystemClock.elapsedRealtime();
            recentTagReads.clear();
            catalogEpcs.clear();
            
            // ✅ بدء جلسة فارغة
            if (inventoryEngine != null) {
                inventoryEngine.startSession(new ConcurrentHashMap<>());
            }
            
            startReadingSource();
            
            if (callback != null) {
                mainHandler.post(callback::onScanStarted);
            }
            
            Log.d(TAG, "✅ Blind inventory started");
            
        } catch (Exception e) {
            Log.e(TAG, "startBlindInventory error: " + e.getMessage(), e);
            isScanning = false;
        }
    }
    
    /**
     * ✅ بدء الجرد الفئوي
     */
    public void startCategoryInventory(Map<String, InventoryItem> filteredCatalog) {
        try {
            if (isScanning) return;
            
            isScanning = true;
            scanStartTime = SystemClock.elapsedRealtime();
            recentTagReads.clear();
            
            catalogEpcs.clear();
            if (filteredCatalog != null) {
                catalogEpcs.addAll(filteredCatalog.keySet());
            }
            
            if (inventoryEngine != null) {
                inventoryEngine.startSession(filteredCatalog);
            }
            
            startReadingSource();
            
            if (callback != null) {
                mainHandler.post(callback::onScanStarted);
            }
            
            Log.d(TAG, "✅ Category inventory started: " + catalogEpcs.size() + " items");
            
        } catch (Exception e) {
            Log.e(TAG, "startCategoryInventory error: " + e.getMessage(), e);
            isScanning = false;
        }
    }
    
    // ==================== Stop Scan ====================
    
    /**
     * ✅ إيقاف المسح
     */
    public void stopScanning() {
        if (!isScanning) return;
        
        try {
            isScanning = false;
            stopSimulationLoop();
            
            // ✅ إيقاف القارئ
            if (rfidManager != null) {
                try {
                    rfidManager.stopScan();
                } catch (Exception e) {
                    Log.e(TAG, "stopScan error: " + e.getMessage());
                }
            }
            
            // ✅ إغلاق الجلسة في الـ Engine
            if (inventoryEngine != null) {
                inventoryEngine.processSessionClose();
            }
            
            if (callback != null) {
                mainHandler.post(callback::onScanStopped);
            }
            
            Log.d(TAG, "✅ Scanning stopped");
            
        } catch (Exception e) {
            Log.e(TAG, "stopScanning error: " + e.getMessage(), e);
        }
    }
    
    // ==================== Reading Source ====================
    
    /**
     * ✅ بدء مصدر القراءة (جهاز أو محاكاة)
     */
    private void startReadingSource() {
        boolean hardwareStarted = false;
        
        if (rfidManager != null) {
            try {
                if (rfidManager.getActiveDevice() != null) {
                    MiraIRFIDReader.ReaderType deviceType = 
                            rfidManager.getActiveDevice().getType();
                    
                    if (deviceType != MiraIRFIDReader.ReaderType.SIMULATOR) {
                        hardwareStarted = rfidManager.startScan();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting hardware scanner", e);
            }
        }
        
        if (!hardwareStarted) {
            startSimulationLoop();
            Log.d(TAG, "✅ Simulation mode started");
        }
        
        if (soundManager != null) {
            soundManager.playBeep(SoundManager.FeedbackType.KNOWN_ITEM);
        }
    }
    
    // ==================== onTagRead ====================
    
    /**
     * ✅ معالجة قراءة الوسم
     * 
     * @param rawEpc   EPC الخام من القارئ
     * @param rssi     قوة الإشارة
     * @param normalizedEpc  EPC بعد التطبيع (من الـ Fragment)
     */
    public void onTagRead(String rawEpc, int rssi, String normalizedEpc) {
        try {
            if (!isScanning || normalizedEpc == null || normalizedEpc.isEmpty()) {
                return;
            }
            
            // ✅ فحص الفئة
            if (!catalogEpcs.isEmpty() && !catalogEpcs.contains(normalizedEpc)) {
                // ليس في الكتالوج — تجاهل بصمت في الجرد الفئوي
                // (لكن اسمح بالجرد الكامل)
            }
            
            // ✅ debounce
            long currentTime = System.currentTimeMillis();
            Long lastReadTime = recentTagReads.get(normalizedEpc);
            
            if (lastReadTime != null && 
                (currentTime - lastReadTime) < TAG_READ_DEBOUNCE_MS) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onTagDebounced(normalizedEpc));
                }
                return;
            }
            
            recentTagReads.put(normalizedEpc, currentTime);
            
            // ✅ تنظيف cache
            if (recentTagReads.size() > MAX_CACHE_ITEMS) {
                cleanOldTagReads(currentTime);
            }
            
            // ✅ معالجة غير متزامنة
            if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
                final String finalEpc = normalizedEpc;
                final int finalRssi = rssi;
                
                backgroundExecutor.execute(() -> {
                    try {
                        if (inventoryEngine != null) {
                            inventoryEngine.onTagScanned(finalEpc, finalRssi);
                        }
                        
                        if (callback != null) {
                            mainHandler.post(() -> 
                                callback.onTagRead(finalEpc, finalRssi));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing tag: " + finalEpc, e);
                    }
                });
            }
            
        } catch (Exception e) {
            Log.e(TAG, "onTagRead error: " + e.getMessage(), e);
        }
    }
    
    /**
     * ✅ تنظيف القراءات القديمة
     */
    private void cleanOldTagReads(long currentTime) {
        try {
            java.util.Iterator<Map.Entry<String, Long>> iterator = 
                    recentTagReads.entrySet().iterator();
            
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                if (currentTime - entry.getValue() > TAG_READ_DEBOUNCE_MS * 2) {
                    iterator.remove();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanOldTagReads error: " + e.getMessage());
        }
    }
    
    // ==================== RFID Callbacks ====================
    
    /**
     * ✅ إشعار بتغيير حالة الاتصال
     */
    public void notifyConnectionChanged(boolean isConnected) {
        if (callback != null) {
            mainHandler.post(() -> callback.onConnectionChanged(isConnected));
        }
    }
    
    /**
     * ✅ إشعار بخطأ
     */
    public void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(error));
        }
    }
    
    // ==================== Simulation ====================
    
    /**
     * ✅ بدء المحاكاة
     */
    private void startSimulationLoop() {
        simulationIndex = 0;
        
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning) return;
                
                String epc;
                
                if (!catalogEpcs.isEmpty()) {
                    List<String> epcList = new ArrayList<>(catalogEpcs);
                    epc = epcList.get(simulationIndex % epcList.size());
                } else {
                    epc = simulationTags[simulationIndex % simulationTags.length];
                }
                
                int rssi = -40 - (int) (Math.random() * 40);
                
                onTagRead(epc, rssi, epc);
                
                simulationIndex++;
                long delay = SIMULATION_MIN_DELAY_MS + 
                        (long) (Math.random() * SIMULATION_MAX_ADDITIONAL_DELAY_MS);
                
                if (isScanning) {
                    simulationHandler.postDelayed(this, delay);
                }
            }
        };
        
        simulationHandler.postDelayed(simulationRunnable, SIMULATION_INITIAL_DELAY_MS);
    }
    
    /**
     * ✅ إيقاف المحاكاة
     */
    private void stopSimulationLoop() {
        try {
            if (simulationHandler != null && simulationRunnable != null) {
                simulationHandler.removeCallbacks(simulationRunnable);
                simulationRunnable = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopSimulationLoop error: " + e.getMessage());
        }
    }
    
    // ==================== Cleanup ====================
    
    /**
     * ✅ تنظيف كامل
     */
    public void cleanup() {
        try {
            stopScanning();
            
            if (simulationHandler != null) {
                simulationHandler.removeCallbacksAndMessages(null);
            }
            
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }
            
            recentTagReads.clear();
            catalogEpcs.clear();
            
            Log.d(TAG, "✅ Cleanup complete");
        } catch (Exception e) {
            Log.e(TAG, "cleanup error: " + e.getMessage());
        }
    }
}
