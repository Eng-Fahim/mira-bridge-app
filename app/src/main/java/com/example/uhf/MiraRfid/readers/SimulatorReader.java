package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * قارئ محاكي متقدم - v3.0
 * 
 * ✅ التحسينات:
 * - TID واقعي (24 hex chars مع CRC-16)
 * - EPC فريد لكل شريحة
 * - resetForNewTag() لتوليد شريحة جديدة
 * - databaseTags تحتوي على EPC صحيح (rfid_epc أولاً)
 * - readData تفرّق بين Bank 1 (EPC) و Bank 2 (TID)
 * - Simulation Loop ترسل TID (المعرّف الأساسي)
 */
public class SimulatorReader implements MiraIRFIDReader {
    
    private static final String TAG = "SimulatorReader";
    private static final String PREFS_NAME = "mira_rfid_dynamic_settings";
    
    // ═══════════════════════════════════════════════════════
    // ✅ حالة الشريحة الحالية (فريدة لكل قراءة)
    // ═══════════════════════════════════════════════════════
    private volatile String currentTagTid = null;   // TID الشريحة الحالية
    private volatile String currentTagEpc = null;   // EPC الشريحة الحالية
    private volatile boolean isTagLocked = false;
    
    // ✅ عداد لتوليد EPC فريد
    private static final AtomicInteger epcCounter = new AtomicInteger(1);
    
    private Context context;
    private OnTagReadListener listener;
    private volatile boolean isScanning = false;
    private volatile boolean isConnected = false;
    
    private int powerDbm = 28;
    private int rssiThreshold = -70;
    private int scanIntervalMs = 200;
    private int session = 0;
    private int target = 0;
    private int qValue = 4;
    
    private ExecutorService scanExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private final Random random = new Random();
    
    private final List<String> databaseTags = new CopyOnWriteArrayList<>();
    private final List<String> fallbackTags = new ArrayList<>();
    
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    
    private String filterData = "";
    private int filterBank = -1;
    private boolean filterActive = false;
    
    // ==================== Constructor ====================
    
    public SimulatorReader(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            this.apiClient = MiraApiClient.getInstance(this.context);
            this.branchesManager = MiraBranchesManager.getInstance(this.context);
            loadSettings();
            initFallbackTags();
            
            // ✅ تهيئة شريحة أولى
            resetForNewTag();
        }
    }

    private synchronized void ensureExecutor() {
        if (scanExecutor == null || scanExecutor.isShutdown() || scanExecutor.isTerminated()) {
            scanExecutor = Executors.newSingleThreadExecutor();
        }
    }
    
    private void loadSettings() {
        if (prefs == null) return;
        powerDbm = prefs.getInt("power_dbm", 28);
        rssiThreshold = prefs.getInt("rssi_threshold", -70);
        scanIntervalMs = prefs.getInt("scan_interval_ms", 200);
        session = prefs.getInt("session", 0);
        target = prefs.getInt("target", 0);
        qValue = prefs.getInt("q_value", 4);
    }
    
    private void saveSettings() {
        if (prefs == null) return;
        prefs.edit()
            .putInt("power_dbm", powerDbm)
            .putInt("rssi_threshold", rssiThreshold)
            .putInt("scan_interval_ms", scanIntervalMs)
            .putInt("session", session)
            .putInt("target", target)
            .putInt("q_value", qValue)
            .apply();
    }
    
    // ==================== Reset for New Tag ====================
    
    /**
     * ✅ إعادة تهيئة المحاكي لشريحة جديدة
     * - توليد TID فريد
     * - توليد EPC فريد
     * - إعادة تعيين حالة القفل
     */
    public synchronized void resetForNewTag() {
        currentTagTid = generateRealisticTid();
        currentTagEpc = generateRealisticEpc();
        isTagLocked = false;
        filterActive = false;
        filterData = "";
        
        Log.i(TAG, "🆕 New simulated tag:");
        Log.i(TAG, "   TID: " + currentTagTid + " (" + currentTagTid.length() + " chars)");
        Log.i(TAG, "   EPC: " + currentTagEpc + " (" + currentTagEpc.length() + " chars)");
    }
    
    private void initFallbackTags() {
        fallbackTags.clear();
        for (int i = 0; i < 10; i++) {
            fallbackTags.add(generateRealisticEpc());
        }
    }
    
    // ==================== Fetch from Database ====================
    
    /**
     * ✅ جلب EPCs من قاعدة البيانات
     * الأولوية: rfid_epc → epc → gtin13
     */
    private void fetchTagsFromDatabase() {
        databaseTags.clear();
        try {
            if (apiClient != null && branchesManager != null) {
                int branchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray items = response.data.optJSONArray("items");
                    if (items == null && response.data.optJSONObject("data") != null) {
                        items = response.data.optJSONObject("data").optJSONArray("items");
                    }
                    
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject obj = items.getJSONObject(i);
                            
                            // ✅ الأولوية: rfid_epc أولاً
                            String epc = obj.optString("rfid_epc", 
                                obj.optString("epc", 
                                obj.optString("gtin13", ""))).trim();
                            
                            if (!epc.isEmpty()) {
                                databaseTags.add(epc.toUpperCase(Locale.US));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching tags", e);
        }
        
        if (databaseTags.isEmpty()) {
            databaseTags.addAll(fallbackTags);
        }
        
        Log.d(TAG, "📚 Loaded " + databaseTags.size() + " EPCs from database");
    }
    
    // ==================== TID Generation ====================
    
    /**
     * ✅ توليد TID واقعي (24 hex chars, CRC-16)
     * الصيغة: E280 + [Serial 8 bytes = 16 hex] + [CRC 2 bytes = 4 hex]
     */
    private String generateRealisticTid() {
        StringBuilder tid = new StringBuilder("E280");
        String hexChars = "0123456789ABCDEF";
        
        // 16 hex chars (8 bytes serial)
        for (int i = 0; i < 16; i++) {
            tid.append(hexChars.charAt(random.nextInt(16)));
        }
        
        // ✅ CRC-16/CCITT (4 hex chars)
        String crc = calculateCrc16(tid.toString());
        String result = (tid.toString() + crc).toUpperCase(Locale.US);
        
        // ✅ التحقق من الطول
        if (result.length() != 24) {
            Log.e(TAG, "⚠️ TID length error: " + result.length() + " (expected 24)");
        }
        
        return result;
    }
    
    /**
     * ✅ حساب CRC-16/CCITT
     */
    private String calculateCrc16(String hex) {
        try {
            byte[] bytes = hexToBytes(hex);
            int crc = 0xFFFF;
            for (byte b : bytes) {
                crc ^= (b & 0xFF) << 8;
                for (int i = 0; i < 8; i++) {
                    crc = ((crc & 0x8000) != 0) 
                        ? ((crc << 1) ^ 0x1021) & 0xFFFF 
                        : (crc << 1) & 0xFFFF;
                }
            }
            return String.format(Locale.US, "%04X", crc);
        } catch (Exception e) {
            Log.e(TAG, "calculateCrc16 error: " + e.getMessage());
            return "0000";
        }
    }
    
    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
    
    // ==================== EPC Generation ====================
    
    /**
     * ✅ توليد EPC واقعي (24 hex chars)
     * الصيغة: E2 + 34 + 12C07 + [Item 6 hex] + [Serial 10 hex]
     */
    private String generateRealisticEpc() {
        int counter = epcCounter.getAndIncrement();
        
        StringBuilder epc = new StringBuilder("E2");
        epc.append("34");                                              // Filter+Partition
        epc.append("12C07");                                           // Company Prefix
        epc.append(String.format(Locale.US, "%06X", 10000 + counter)); // Item Ref
        
        // Serial 10 hex عشوائي
        String hexChars = "0123456789ABCDEF";
        for (int i = 0; i < 10; i++) {
            epc.append(hexChars.charAt(random.nextInt(16)));
        }
        
        return epc.toString().toUpperCase(Locale.US);
    }
    
    // ==================== Lifecycle ====================
    
    @Override
    public boolean initialize() {
        if (currentTagTid == null) {
            resetForNewTag();
        }
        return true;
    }
    
    @Override
    public boolean connect() {
        isConnected = true;
        fetchTagsFromDatabase();
        mainHandler.post(() -> {
            if (listener != null) listener.onConnectionChanged(true);
        });
        return true;
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onConnectionChanged(false);
        });
    }
    
    @Override
    public synchronized void release() {
        disconnect();
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        databaseTags.clear();
        listener = null;
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    // ==================== Scanning ====================
    
    @Override
    public boolean startScan() {
        if (!isConnected) connect();
        if (isScanning) return true;
        
        // ✅ إذا لم تكن هناك شريحة حالية، أنشئ واحدة
        if (currentTagTid == null || currentTagTid.isEmpty()) {
            resetForNewTag();
        }
        
        isScanning = true;
        mainHandler.post(() -> {
            if (listener != null) listener.onScanStarted();
        });
        
        ensureExecutor();
        scanExecutor.execute(this::simulationLoop);
        return true;
    }
    
    /**
     * ✅ حلقة المحاكاة
     * - تُرسل currentTagTid (المعرّف الأساسي)
     */
    private void simulationLoop() {
        int index = 0;
        
        while (isScanning && !Thread.currentThread().isInterrupted()) {
            try {
                // ✅ إذا لم تكن هناك شريحة، انتظر
                if (currentTagTid == null || currentTagTid.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }
                
                int rssi = simulateRssi();
                
                if (rssi >= rssiThreshold) {
                    final String tidToSend = currentTagTid;
                    final int finalRssi = rssi;
                    
                    mainHandler.post(() -> {
                        if (listener != null) {
                            // ✅ نُرسل TID (وليس EPC)
                            listener.onTagRead(tidToSend, finalRssi);
                        }
                    });
                }
                
                index++;
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (isScanning) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onError(e.getMessage());
                    });
                }
                break;
            }
        }
    }
    
    private int simulateRssi() {
        int baseRssi = -95 + (powerDbm - 10) * 2;
        int noise = random.nextInt(15);
        return Math.max(-100, Math.min(-30, baseRssi - noise));
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onScanStopped();
        });
    }
    
    @Override
    public boolean isScanning() { return isScanning; }
    
    // ==================== Configuration ====================
    
    @Override
    public void setPower(int powerDbm) { this.powerDbm = powerDbm; saveSettings(); }
    
    @Override
    public void setRssiThreshold(int threshold) { this.rssiThreshold = threshold; saveSettings(); }
    
    @Override
    public void setScanInterval(int intervalMs) { this.scanIntervalMs = intervalMs; saveSettings(); }
    
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    
    // ==================== Info ====================
    
    @Override
    public String getDeviceName() { return "Simulator (Realistic Mode)"; }
    
    @Override
    public String getFirmwareVersion() { return "3.0.0-sim-realistic"; }
    
    @Override
    public int getBatteryLevel() { return 100; }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }
    
    // ==================== Memory Access ====================
    
    /**
     * ✅ كتابة بيانات حسب Bank
     * - Bank 1 (EPC) → كتابة EPC
     * - Bank 2 (TID) → read-only (نرفض)
     * - Bank 3 (USER) → كتابة user memory
     */
    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (isTagLocked) {
            Log.w(TAG, "⚠️ Tag is locked");
            return false;
        }
        if (data == null || data.isEmpty()) {
            return false;
        }
        
        // ✅ Bank 1 = EPC
        if (bank == 1) {
            currentTagEpc = data.toUpperCase(Locale.US);
            Log.d(TAG, "✏️ EPC updated: " + currentTagEpc);
            return true;
        }
        
        // ✅ Bank 2 = TID (read-only, نرفض الكتابة)
        if (bank == 2) {
            Log.w(TAG, "⚠️ TID is read-only");
            return false;
        }
        
        // ✅ Bank 3 = User memory
        if (bank == 3) {
            Log.d(TAG, "✏️ User memory updated");
            return true;
        }
        
        return false;
    }
    
    /**
     * ✅ قراءة بيانات حسب Bank
     * - Bank 1 (EPC) → currentTagEpc
     * - Bank 2 (TID) → currentTagTid
     * - Bank 3 (USER) → user memory
     */
    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
        // ✅ Bank 2 = TID
        if (bank == 2) {
            if (currentTagTid == null || currentTagTid.isEmpty()) {
                currentTagTid = generateRealisticTid();
            }
            return currentTagTid;
        }
        
        // ✅ Bank 1 = EPC
        if (bank == 1) {
            if (currentTagEpc == null || currentTagEpc.isEmpty()) {
                currentTagEpc = generateRealisticEpc();
            }
            return currentTagEpc;
        }
        
        // ✅ Bank 3 = User
        if (bank == 3) {
            return "00000000";
        }
        
        return null;
    }
    
    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        if (isTagLocked) return false;
        if (newEpc != null && !newEpc.isEmpty()) {
            currentTagEpc = newEpc.toUpperCase(Locale.US);
            Log.d(TAG, "✏️ writeEpc: " + currentTagEpc);
        }
        return true;
    }
    
    @Override
    public String readTid() {
        if (currentTagTid == null || currentTagTid.isEmpty()) {
            currentTagTid = generateRealisticTid();
        }
        return currentTagTid;
    }
    
    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        // ✅ Lock EPC bank (bank 1)
        if (bank == 1) {
            isTagLocked = shouldLock;
            Log.d(TAG, "🔒 Tag lock state: " + shouldLock);
        }
        return true;
    }
    
    @Override
    public boolean lockTag(String accessPassword) {
        isTagLocked = true;
        return true;
    }
    
    @Override
    public boolean unlockTag(String accessPassword) {
        isTagLocked = false;
        return true;
    }
    
    @Override
    public boolean killTag(String accessPassword) {
        currentTagEpc = "";
        return true;
    }
    
    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        this.filterBank = bank;
        this.filterData = filterData;
        this.filterActive = true;
        return true;
    }
    
    @Override
    public void clearFilter() {
        this.filterActive = false;
        this.filterData = "";
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return !isTagLocked;
    }
    
    @Override
    public String readUserMemory(int address, int length) {
        return "00000000";
    }
}
