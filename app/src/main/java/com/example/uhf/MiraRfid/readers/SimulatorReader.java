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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ محاكي متقدم - مع إدارة آمنة للخيوط للوقاية من الـ Crashes
 */
public class SimulatorReader implements MiraIRFIDReader {
    
    private static final String TAG = "SimulatorReader";
    private static final String PREFS_NAME = "mira_rfid_dynamic_settings";
    
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
    
    private String currentEpc = "3008ABCDEF0123456789ABCD";
    private String simulatedTid = "";
    private boolean isTagLocked = false;
    private String filterData = "";
    private int filterBank = -1;
    private boolean filterActive = false;
    
    public SimulatorReader(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            this.apiClient = MiraApiClient.getInstance(this.context);
            this.branchesManager = MiraBranchesManager.getInstance(this.context);
            loadSettings();
            initFallbackTags();
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
    
    public void resetForNewTag() {
        simulatedTid = generateRealisticTid();
        currentEpc = "";
        isTagLocked = false;
        filterActive = false;
        filterData = "";
    }
    
    private void initFallbackTags() {
        fallbackTags.clear();
        for (int i = 0; i < 10; i++) {
            fallbackTags.add("E28068940000501A2B3C4D5E7" + i);
        }
    }
    
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
                            String epc = obj.optString("gtin13", 
                                obj.optString("serial_number", 
                                obj.optString("epc", ""))).trim();
                            if (!epc.isEmpty()) {
                                databaseTags.add(epc.toUpperCase());
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
    }
    
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
    
    @Override
    public boolean startScan() {
        if (!isConnected) connect();
        if (isScanning) return true;
        
        isScanning = true;
        mainHandler.post(() -> {
            if (listener != null) listener.onScanStarted();
        });
        
        ensureExecutor();
        scanExecutor.execute(this::simulationLoop);
        return true;
    }
    
    private void simulationLoop() {
        int index = 0;
        
        while (isScanning && !Thread.currentThread().isInterrupted()) {
            try {
                if (databaseTags.isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }
                
                String epc = databaseTags.get(index % databaseTags.size());
                int rssi = simulateRssi();
                
                if (rssi >= rssiThreshold) {
                    final String finalEpc = epc;
                    final int finalRssi = rssi;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onTagRead(finalEpc, finalRssi);
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
    
    @Override
    public void setPower(int powerDbm) { this.powerDbm = powerDbm; saveSettings(); }
    
    @Override
    public void setRssiThreshold(int threshold) { this.rssiThreshold = threshold; saveSettings(); }
    
    @Override
    public void setScanInterval(int intervalMs) { this.scanIntervalMs = intervalMs; saveSettings(); }
    
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    
    @Override
    public String getDeviceName() { return "Simulator (Database Mode)"; }
    
    @Override
    public String getFirmwareVersion() { return "2.0.0-sim-db"; }
    
    @Override
    public int getBatteryLevel() { return 100; }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }
    
    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (isTagLocked) return false;
        if (data != null && !data.isEmpty()) currentEpc = data;
        return true;
    }
    
    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
        return (bank == 2) ? simulatedTid : currentEpc;
    }
    
    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        if (isTagLocked) return false;
        if (newEpc != null && !newEpc.isEmpty()) currentEpc = newEpc;
        return true;
    }
    
    @Override
    public String readTid() { return simulatedTid; }
    
    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        isTagLocked = shouldLock;
        return true;
    }
    
    @Override
    public boolean lockTag(String accessPassword) { isTagLocked = true; return true; }
    
    @Override
    public boolean unlockTag(String accessPassword) { isTagLocked = false; return true; }
    
    @Override
    public boolean killTag(String accessPassword) { currentEpc = ""; return true; }
    
    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        this.filterBank = bank;
        this.filterData = filterData;
        this.filterActive = true;
        return true;
    }
    
    @Override
    public void clearFilter() { this.filterActive = false; this.filterData = ""; }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) { return !isTagLocked; }
    
    @Override
    public String readUserMemory(int address, int length) { return "00000000"; }
}
