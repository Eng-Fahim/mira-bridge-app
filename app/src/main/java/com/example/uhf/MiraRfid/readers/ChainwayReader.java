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
 * قارئ Chainway حقيقي - مع إعدادات ديناميكية كاملة
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
    private int beepMode = 1; // 0=off, 1=on
    private int vibrationMode = 1; // 0=off, 1=on
    private int continuousMode = 1; // 0=single, 1=continuous
    private int session = 0; // S0, S1, S2, S3
    private int target = 0; // A, B
    private int qValue = 4; // 0-15
    
    // للتحكم في دورة القراءة
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    
    public ChainwayReader(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadSettings();
    }
    
    /**
     * تحميل الإعدادات المحفوظة
     */
    private void loadSettings() {
        powerDbm = prefs.getInt("power_dbm", 28);
        rssiThreshold = prefs.getInt("rssi_threshold", -70);
        scanIntervalMs = prefs.getInt("scan_interval_ms", 200);
        beepMode = prefs.getInt("beep_mode", 1);
        vibrationMode = prefs.getInt("vibration_mode", 1);
        continuousMode = prefs.getInt("continuous_mode", 1);
        session = prefs.getInt("session", 0);
        target = prefs.getInt("target", 0);
        qValue = prefs.getInt("q_value", 4);
        
        Log.d(TAG, "Settings loaded: power=" + powerDbm + ", rssi=" + rssiThreshold + 
              ", interval=" + scanIntervalMs + "ms");
    }
    
    /**
     * حفظ الإعدادات
     */
    private void saveSettings() {
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
        try {
            reader = RFIDWithUHFUART.getInstance();
            boolean success = reader.init(context);
            
            if (success) {
                // تطبيق الإعدادات بعد التهيئة
                applyAllSettings();
                
                Log.i(TAG, "✅ Chainway initialized successfully");
                Log.i(TAG, "📌 SDK Version: " + getFirmwareVersion());
                Log.i(TAG, "📌 Device: " + Build.MANUFACTURER + " " + Build.MODEL);
            } else {
                Log.e(TAG, "❌ Chainway init failed");
            }
            
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Init error", e);
            return false;
        }
    }
    
    /**
     * تطبيق جميع الإعدادات على القارئ
     */
    private void applyAllSettings() {
        if (reader == null) return;
        
        try {
            // قوة الإرسال
            reader.setPower(powerDbm);
            Log.d(TAG, "Applied power: " + powerDbm + " dBm");
            
            // تطبيق Query parameters (الجلسة، Target، و Q-Value) عبر الدالة الرسمية لـ Chainway
            try {
                reader.setQueryTagParam(0, session, target, qValue);
                Log.d(TAG, "Applied Query Params: Session S" + session + ", Target " + target + ", Q " + qValue);
            } catch (Exception e) {
                Log.w(TAG, "Query params not supported: " + e.getMessage());
            }
            
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
            isConnected = reader != null;
            if (isConnected) {
                // إعادة تطبيق الإعدادات عند الاتصال
                applyAllSettings();
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
    public void release() {
        disconnect();
        scanExecutor.shutdown();
        try {
            if (reader != null) {
                reader.free();
                reader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Release error", e);
        }
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (reader == null && !connect()) return false;
        
        try {
            // تطبيق الإعدادات قبل البدء
            applyAllSettings();
            
            // بدء الجرد حسب الوضع
            boolean success = reader.startInventoryTag();
            
            if (success) {
                isScanning = true;
                if (listener != null) listener.onScanStarted();
                
                // حلقة القراءة
                scanExecutor.execute(this::scanLoop);
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "startScan error", e);
            return false;
        }
    }
    
    /**
     * حلقة القراءة المستمرة
     */
    private void scanLoop() {
        while (isScanning) {
            try {
                UHFTAGInfo tag;
                while (isScanning && (tag = reader.readTagFromBuffer()) != null) {
                    processTag(tag);
                }
                
                // النوم حسب الفترة المحددة
                Thread.sleep(scanIntervalMs);
                
            } catch (InterruptedException e) {
                Log.d(TAG, "Scan loop interrupted");
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
    
    /**
     * معالجة الوسم المقروء
     */
    private void processTag(UHFTAGInfo tag) {
        try {
            String epc = tag.getEPC();
            String rssiStr = tag.getRssi();
            int rssi = parseRssi(rssiStr);
            
            // تطبيق عتبة RSSI
            if (rssi < rssiThreshold) {
                return; // تجاهل الوسوم الضعيفة
            }
            
            if (epc != null && !epc.isEmpty() && listener != null) {
                listener.onTagRead(epc, rssi);
            }
        } catch (Exception e) {
            Log.e(TAG, "Process tag error", e);
        }
    }
    
    /**
     * تحويل RSSI من سلسلة إلى رقم
     */
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
    
    // ==================== SETTERS مع حفظ الإعدادات ====================
    
    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = powerDbm;
        saveSettings();
        try {
            if (reader != null) {
                reader.setPower(powerDbm);
                Log.d(TAG, "Power updated to: " + powerDbm + " dBm");
            }
        } catch (Exception e) {
            Log.e(TAG, "Set power error", e);
        }
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = threshold;
        saveSettings();
        Log.d(TAG, "RSSI threshold updated to: " + threshold);
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        this.scanIntervalMs = Math.max(50, intervalMs); // حد أدنى 50ms
        saveSettings();
        Log.d(TAG, "Scan interval updated to: " + scanIntervalMs + "ms");
    }
    
    public void setBeepMode(int mode) {
        this.beepMode = mode;
        saveSettings();
    }
    
    public void setVibrationMode(int mode) {
        this.vibrationMode = mode;
        saveSettings();
    }
    
    public void setContinuousMode(int mode) {
        this.continuousMode = mode;
        saveSettings();
    }
    
    public void setSession(int session) {
        this.session = session;
        saveSettings();
        applyAllSettings();
    }
    
    public void setTarget(int target) {
        this.target = target;
        saveSettings();
        applyAllSettings();
    }
    
    public void setQValue(int qValue) {
        this.qValue = qValue;
        saveSettings();
        applyAllSettings();
    }
    
    // Getters
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
    public String getDeviceName() {
        return "Chainway " + Build.MODEL + " / UHF Terminal";
    }
    
    @Override
    public String getFirmwareVersion() {
        try {
            if (reader != null) {
                return reader.getVersion();
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }
    
    @Override
    public int getBatteryLevel() {
        // تم إزالة reader.getBattery() لعدم وجودها في هذا الـ SDK
        return -1;
    }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }

    // ==================== عمليات القراءة والكتابة (بدون تغيير) ====================
    
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

    public String readEpcData(String accessPassword) {
        return readData(accessPassword, RFIDWithUHFUART.Bank_EPC, 32, 6);
    }
    
    public String readTidData(String accessPassword) {
        return readData(accessPassword, RFIDWithUHFUART.Bank_TID, 0, 6);
    }
    
    public boolean writeEpcData(String newEpc, String accessPassword) {
        return writeData(accessPassword, RFIDWithUHFUART.Bank_EPC, 32, newEpc);
    }

    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        return writeEpcData(newEpc, accessPassword);
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return writeData(accessPassword, RFIDWithUHFUART.Bank_USER, address, data);
    }
    
    @Override
    public String readTid() {
        return readTidData("00000000");
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
