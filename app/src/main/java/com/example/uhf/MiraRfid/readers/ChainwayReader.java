package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ Chainway حقيقي - محسّن ومُحصّن ضد تسريب الذاكرة وأخطاء المزامنة
 */
public class ChainwayReader implements MiraIRFIDReader {
    
    private static final String TAG = "ChainwayReader";
    private static final String PREFS_NAME = "mira_rfid_dynamic_settings";
    
    private Context context;
    private RFIDWithUHFUART reader;
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
    
    private ExecutorService scanExecutor;
    private SharedPreferences prefs;
    
    public ChainwayReader(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            loadSettings();
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
    
    @Override
    public boolean initialize() {
        if (context == null) return false;
        try {
            if (reader == null) {
                reader = RFIDWithUHFUART.getInstance();
            }
            boolean success = reader.init(context);
            
            if (success) {
                applySupportedSettings();
                Log.i(TAG, "✅ Chainway initialized successfully");
            } else {
                Log.e(TAG, "❌ Chainway init failed");
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Init error", e);
            return false;
        }
    }
    
    private void applySupportedSettings() {
        if (reader == null) return;
        try {
            reader.setPower(powerDbm);
        } catch (Exception e) {
            Log.e(TAG, "Error applying settings", e);
        }
    }
    
    @Override
    public boolean connect() {
        try {
            if (reader == null) {
                initialize();
            }
            isConnected = (reader != null);
            if (isConnected) {
                applySupportedSettings();
                if (listener != null) {
                    listener.onConnectionChanged(true);
                }
            }
            return isConnected;
        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            return false;
        }
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        if (listener != null) {
            listener.onConnectionChanged(false);
        }
    }
    
    @Override
    public synchronized void release() {
        disconnect();
        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        try {
            if (reader != null) {
                reader.free();
                reader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Release error", e);
        }
        listener = null;
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (reader == null && !connect()) return false;
        if (isScanning) return true;
        
        try {
            applySupportedSettings();
            boolean success = reader.startInventoryTag();
            
            if (success) {
                isScanning = true;
                if (listener != null) listener.onScanStarted();
                ensureExecutor();
                scanExecutor.execute(this::scanLoop);
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "startScan error", e);
            return false;
        }
    }
    
    private void scanLoop() {
        while (isScanning && !Thread.currentThread().isInterrupted()) {
            try {
                UHFTAGInfo tag;
                if (reader != null) {
                    while (isScanning && (tag = reader.readTagFromBuffer()) != null) {
                        processTag(tag);
                    }
                }
                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                Log.d(TAG, "Scan loop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (isScanning && listener != null) {
                    listener.onError(e.getMessage());
                }
                Log.e(TAG, "Scan loop error", e);
                break;
            }
        }
    }
    
    private void processTag(UHFTAGInfo tag) {
        if (tag == null) return;
        try {
            String epc = tag.getEPC();
            String rssiStr = tag.getRssi();
            int rssi = parseRssi(rssiStr);
            
            if (rssi < rssiThreshold) {
                return;
            }
            
            if (epc != null && !epc.isEmpty() && listener != null) {
                listener.onTagRead(epc, rssi);
            }
        } catch (Exception e) {
            Log.e(TAG, "Process tag error", e);
        }
    }
    
    private int parseRssi(String rssiStr) {
        if (rssiStr == null || rssiStr.isEmpty()) return -50;
        try {
            String cleaned = rssiStr.replace("dBm", "").replace("dbm", "").trim();
            int rssi = Integer.parseInt(cleaned);
            return rssi > 0 ? -rssi : rssi;
        } catch (NumberFormatException e) {
            return -50;
        }
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        try {
            if (reader != null) {
                reader.stopInventory();
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop scan error", e);
        }
        if (listener != null) listener.onScanStopped();
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = powerDbm;
        saveSettings();
        try {
            if (reader != null) {
                reader.setPower(powerDbm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Set power error", e);
        }
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = threshold;
        saveSettings();
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        this.scanIntervalMs = Math.max(50, intervalMs);
        saveSettings();
    }
    
    public void setBeepMode(int mode) { this.beepMode = mode; saveSettings(); }
    public void setVibrationMode(int mode) { this.vibrationMode = mode; saveSettings(); }
    public void setContinuousMode(int mode) { this.continuousMode = mode; saveSettings(); }
    public void setSession(int session) { this.session = session; saveSettings(); }
    public void setTarget(int target) { this.target = target; saveSettings(); }
    public void setQValue(int qValue) { this.qValue = qValue; saveSettings(); }
    
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    public int getBeepMode() { return beepMode; }
    public int getVibrationMode() { return vibrationMode; }
    public int getContinuousMode() { return continuousMode; }
    public int getSession() { return session; }
    public int getTarget() { return target; }
    public int getQValue() { return qValue; }
    
    @Override
    public String getDeviceName() { return "Chainway " + Build.MODEL + " / UHF Terminal"; }
    
    @Override
    public String getFirmwareVersion() {
        try {
            if (reader != null) return reader.getVersion();
        } catch (Exception ignored) {}
        return "Unknown";
    }
    
    @Override
    public int getBatteryLevel() { return -1; }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (reader == null) return false;
        try {
            int writeLen = (int) Math.ceil(data.length() / 4.0);
            return reader.writeData(pwdStr, bank, ptr, writeLen, data);
        } catch (Exception e) {
            Log.e(TAG, "writeData error", e);
            return false;
        }
    }

    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
        if (reader == null) return null;
        try {
            return reader.readData(pwdStr, bank, ptr, len);
        } catch (Exception e) {
            Log.e(TAG, "readData error", e);
            return null;
        }
    }

    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        if (reader == null) return false;
        try {
            String lockCode = shouldLock ? "0002" : "0001";
            return reader.lockMem(pwdStr, lockCode);
        } catch (Exception e) {
            Log.e(TAG, "Lock error", e);
            return false;
        }
    }

    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        if (reader == null) return false;
        try {
            int len = filterData.length() * 4;
            return reader.setFilter(bank, ptr, len, filterData);
        } catch (Exception e) {
            Log.e(TAG, "setFilter error", e);
            return false;
        }
    }

    @Override
    public void clearFilter() {
        if (reader != null) {
            try {
                reader.setFilter(1, 0, 0, "");
            } catch (Exception e) {
                Log.e(TAG, "Clear filter error", e);
            }
        }
    }

    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        return writeData(accessPassword, RFIDWithUHFUART.Bank_EPC, 32, newEpc);
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return writeData(accessPassword, RFIDWithUHFUART.Bank_USER, address, data);
    }
    
    @Override
    public String readTid() {
        return readData("00000000", RFIDWithUHFUART.Bank_TID, 0, 6);
    }
    
    @Override
    public String readUserMemory(int address, int length) {
        return readData("00000000", RFIDWithUHFUART.Bank_USER, address, length);
    }
    
    @Override
    public boolean lockTag(String accessPassword) {
        return setTagLockState(accessPassword, RFIDWithUHFUART.Bank_EPC, true);
    }
    
    @Override
    public boolean unlockTag(String accessPassword) {
        return setTagLockState(accessPassword, RFIDWithUHFUART.Bank_EPC, false);
    }
    
    @Override
    public boolean killTag(String accessPassword) {
        if (reader == null) return false;
        try {
            return reader.killTag(accessPassword);
        } catch (Exception e) {
            Log.e(TAG, "killTag error", e);
            return false;
        }
    }
}
