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
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ محاكي متقدم - يحاكي القارئ الحقيقي بالكامل
 */
public class SimulatorReader implements MiraIRFIDReader {
    
    private static final String TAG = "SimulatorReader";
    private static final String PREFS_NAME = "mira_rfid_dynamic_settings";
    
    private Context context;
    private OnTagReadListener listener;
    private volatile boolean isScanning = false;
    private volatile boolean isConnected = false;
    
    // إعدادات ديناميكية
    private int powerDbm = 28;
    private int rssiThreshold = -70;
    private int scanIntervalMs = 200;
    private int beepMode = 1;
    private int vibrationMode = 1;
    private int continuousMode = 1;
    private int session = 0;
    private int target = 0;
    private int qValue = 4;
    
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private final Random random = new Random();
    
    // وسوم من قاعدة البيانات
    private final List<String> databaseTags = new ArrayList<>();
    private final List<String> fallbackTags = new ArrayList<>();
    
    // API للجلب من قاعدة البيانات
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    
    // ✅ متغيرات المحاكاة الواقعية
    private String currentEpc = "3008ABCDEF0123456789ABCD";
    private String lastWrittenEpc = "";
    private String simulatedTid = "";
    private boolean isTagLocked = false;
    private String filterData = "";
    private int filterBank = -1;
    private boolean filterActive = false;
    
    public SimulatorReader(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        if (this.context != null) {
            this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            this.apiClient = MiraApiClient.getInstance(context);
            this.branchesManager = MiraBranchesManager.getInstance(context);
            loadSettings();
            initFallbackTags();
        }
    }
    
    private void loadSettings() {
        if (prefs == null) return;
        powerDbm = prefs.getInt("power_dbm", 28);
        rssiThreshold = prefs.getInt("rssi_threshold", -70);
        scanIntervalMs = prefs.getInt("scan_interval_ms", 200);
        beepMode = prefs.getInt("beep_mode", 1);
        vibrationMode = prefs.getInt("vibration_mode", 1);
        continuousMode = prefs.getInt("continuous_mode", 1);
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
            .putInt("beep_mode", beepMode)
            .putInt("vibration_mode", vibrationMode)
            .putInt("continuous_mode", continuousMode)
            .putInt("session", session)
            .putInt("target", target)
            .putInt("q_value", qValue)
            .apply();
    }
    
    private void initFallbackTags() {
        fallbackTags.clear();
        fallbackTags.add("E28068940000501A2B3C4D5E6F");
        fallbackTags.add("E28068940000501A2B3C4D5E70");
        fallbackTags.add("E28068940000501A2B3C4D5E71");
        fallbackTags.add("E28068940000501A2B3C4D5E72");
        fallbackTags.add("E28068940000501A2B3C4D5E73");
        fallbackTags.add("E28068940000501A2B3C4D5E74");
        fallbackTags.add("E28068940000501A2B3C4D5E75");
        fallbackTags.add("E28068940000501A2B3C4D5E76");
        fallbackTags.add("E28068940000501A2B3C4D5E77");
        fallbackTags.add("E28068940000501A2B3C4D5E78");
    }
    
    private void fetchTagsFromDatabase() {
        databaseTags.clear();
        
        try {
            if (apiClient != null && branchesManager != null) {
                int branchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray items = response.data.optJSONArray("items");
                    if (items == null) {
                        JSONObject dataObj = response.data.optJSONObject("data");
                        if (dataObj != null) {
                            items = dataObj.optJSONArray("items");
                        }
                    }
                    
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject obj = items.getJSONObject(i);
                            String epc = obj.optString("gtin13", 
                                obj.optString("serial_number", 
                                obj.optString("epc", ""))).trim();
                            if (!epc.isEmpty()) {
                                databaseTags.add(epc.toUpperCase());
                            }
                        }
                        Log.i(TAG, "✅ Loaded " + databaseTags.size() + " tags from database");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching tags from database", e);
        }
        
        if (databaseTags.isEmpty()) {
            databaseTags.addAll(fallbackTags);
            Log.w(TAG, "⚠️ Using fallback tags: " + databaseTags.size() + " tags");
        }
    }
    
    /**
     * ✅ توليد TID واقعي
     */
    private String generateRealisticTid() {
        StringBuilder tid = new StringBuilder("E280");
        String hexChars = "0123456789ABCDEF";
        for (int i = 0; i < 16; i++) {
            tid.append(hexChars.charAt(random.nextInt(16)));
        }
        return tid.toString();
    }
    
    @Override
    public boolean initialize() {
        simulatedTid = generateRealisticTid();
        Log.i(TAG, "✅ Simulator initialized with TID: " + simulatedTid);
        return true;
    }
    
    @Override
    public boolean connect() {
        isConnected = true;
        fetchTagsFromDatabase();
        
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionChanged(true);
            }
        });
        Log.i(TAG, "✅ Simulator connected with " + databaseTags.size() + " tags");
        return true;
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionChanged(false);
            }
        });
    }
    
    @Override
    public void release() {
        disconnect();
        scanExecutor.shutdown();
        databaseTags.clear();
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (!isConnected) connect();
        isScanning = true;
        
        mainHandler.post(() -> {
            if (listener != null) listener.onScanStarted();
        });
        
        scanExecutor.execute(this::simulationLoop);
        return true;
    }
    
    private void simulationLoop() {
        int index = 0;
        int consecutiveMisses = 0;
        
        while (isScanning) {
            try {
                if (databaseTags.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }
                
                String epc = databaseTags.get(index % databaseTags.size());
                int rssi = simulateRssi();
                
                if (rssi >= rssiThreshold) {
                    consecutiveMisses = 0;
                    final String finalEpc = epc;
                    final int finalRssi = rssi;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onTagRead(finalEpc, finalRssi);
                    });
                } else {
                    consecutiveMisses++;
                    if (consecutiveMisses > 3) {
                        consecutiveMisses = 0;
                        index++;
                    }
                }
                
                index++;
                if (random.nextInt(100) >= 15) {
                    index++;
                }
                
                long actualDelay = scanIntervalMs + random.nextInt(50) - 25;
                Thread.sleep(Math.max(50, actualDelay));
                
            } catch (InterruptedException e) {
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
        int qPenalty = qValue * 2;
        int distancePenalty = random.nextInt(30);
        int interference = random.nextInt(20) - 10;
        int rssi = baseRssi - qPenalty - distancePenalty + interference;
        return Math.max(-100, Math.min(-30, rssi));
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onScanStopped();
        });
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = Math.max(10, Math.min(30, powerDbm));
        saveSettings();
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = Math.max(-100, Math.min(-30, threshold));
        saveSettings();
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        this.scanIntervalMs = Math.max(50, Math.min(1000, intervalMs));
        saveSettings();
    }
    
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    public int getSession() { return session; }
    public int getTarget() { return target; }
    public int getQValue() { return qValue; }
    public int getAvailableTagsCount() { return databaseTags.size(); }
    
    public void setSession(int session) {
        this.session = Math.max(0, Math.min(3, session));
        saveSettings();
    }
    
    public void setTarget(int target) {
        this.target = Math.max(0, Math.min(1, target));
        saveSettings();
    }
    
    public void setQValue(int qValue) {
        this.qValue = Math.max(0, Math.min(15, qValue));
        saveSettings();
    }
    
    @Override
    public String getDeviceName() {
        return "Simulator (Database Mode)";
    }
    
    @Override
    public String getFirmwareVersion() {
        return "2.0.0-sim-db";
    }
    
    @Override
    public int getBatteryLevel() {
        return 100;
    }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }
    
    // ==================== ✅ عمليات القراءة/الكتابة المحسنة ====================
    
    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (isTagLocked) {
            Log.w(TAG, "❌ الشريحة مقفلة - لا يمكن الكتابة");
            return false;
        }
        
        if (filterActive && filterBank == bank && !data.startsWith(filterData)) {
            Log.w(TAG, "❌ الشريحة لا تطابق الفلتر");
            return false;
        }
        
        if (data != null && !data.isEmpty()) {
            currentEpc = data;
            lastWrittenEpc = data;
            Log.d(TAG, "✅ Simulator writeData: bank=" + bank + ", data=" + data);
        }
        return true;
    }
    
    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
        switch (bank) {
            case 1: return currentEpc;
            case 2: return simulatedTid;
            default: return "00000000";
        }
    }
    
    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        if (isTagLocked) {
            Log.w(TAG, "❌ الشريحة مقفلة");
            return false;
        }
        if (newEpc != null && !newEpc.isEmpty()) {
            currentEpc = newEpc;
            lastWrittenEpc = newEpc;
            Log.d(TAG, "✅ Simulator writeEpc: " + newEpc);
        }
        return true;
    }
    
    @Override
    public String readTid() {
        return simulatedTid;
    }
    
    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        isTagLocked = shouldLock;
        Log.d(TAG, (shouldLock ? "🔒" : "🔓") + " Tag " + (shouldLock ? "locked" : "unlocked"));
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
        currentEpc = "";
        return true;
    }
    
    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        this.filterBank = bank;
        this.filterData = filterData;
        this.filterActive = true;
        Log.d(TAG, "✅ Filter set: bank=" + bank + ", data=" + filterData);
        return true;
    }
    
    @Override
    public void clearFilter() {
        this.filterActive = false;
        this.filterData = "";
        Log.d(TAG, "✅ Filter cleared");
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        if (isTagLocked) return false;
        return true;
    }
    
    @Override
    public String readUserMemory(int address, int length) {
        return "00000000";
    }
}
