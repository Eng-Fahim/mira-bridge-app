package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ محاكي - يدعم الإعدادات الديناميكية مثل القارئ الحقيقي
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
    
    // وسوم محاكاة
    private final String[] simulationTags = {
        "E28068940000501A2B3C4D5E6F",
        "E28068940000501A2B3C4D5E70",
        "E28068940000501A2B3C4D5E71",
        "E28068940000501A2B3C4D5E72",
        "E28068940000501A2B3C4D5E73",
        "E28068940000501A2B3C4D5E74",
        "E28068940000501A2B3C4D5E75",
        "E28068940000501A2B3C4D5E76",
        "E28068940000501A2B3C4D5E77",
        "E28068940000501A2B3C4D5E78"
    };
    
    public SimulatorReader(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        if (this.context != null) {
            this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            loadSettings();
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
        Log.i(TAG, "✅ Simulator initialized");
        return true;
    }
    
    @Override
    public boolean connect() {
        isConnected = true;
        if (listener != null) {
            mainHandler.post(() -> listener.onConnectionChanged(true));
        }
        Log.i(TAG, "✅ Simulator connected");
        return true;
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        if (listener != null) {
            mainHandler.post(() -> listener.onConnectionChanged(false));
        }
    }
    
    @Override
    public void release() {
        disconnect();
        scanExecutor.shutdown();
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (!isConnected) {
            connect();
        }
        
        isScanning = true;
        if (listener != null) {
            mainHandler.post(() -> listener.onScanStarted());
        }
        
        scanExecutor.execute(this::simulationLoop);
        
        Log.i(TAG, "📡 Simulation started with settings:");
        Log.i(TAG, "  - Power: " + powerDbm + " dBm");
        Log.i(TAG, "  - RSSI Threshold: " + rssiThreshold);
        Log.i(TAG, "  - Scan Interval: " + scanIntervalMs + "ms");
        Log.i(TAG, "  - Session: S" + session);
        Log.i(TAG, "  - Target: " + (target == 0 ? "A" : "B"));
        Log.i(TAG, "  - Q Value: " + qValue);
        
        return true;
    }
    
    private void simulationLoop() {
        int index = 0;
        
        while (isScanning) {
            try {
                // محاكاة قراءة وسم
                String epc = simulationTags[index % simulationTags.length];
                
                // محاكاة RSSI بناءً على قوة الإرسال
                // كلما زادت القوة، كانت الإشارة أقوى
                int baseRssi = -90 + (powerDbm - 10); // 10dBm → -90, 30dBm → -70
                int rssiVariation = random.nextInt(20) - 10; // -10 إلى +10
                int rssi = Math.max(-100, Math.min(-30, baseRssi + rssiVariation));
                
                // تطبيق عتبة RSSI
                if (rssi >= rssiThreshold && listener != null) {
                    listener.onTagRead(epc, rssi);
                }
                
                index++;
                
                // النوم حسب فترة المسح
                Thread.sleep(scanIntervalMs);
                
            } catch (InterruptedException e) {
                Log.d(TAG, "Simulation loop interrupted");
                break;
            } catch (Exception e) {
                if (isScanning && listener != null) {
                    mainHandler.post(() -> listener.onError(e.getMessage()));
                }
                break;
            }
        }
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        if (listener != null) {
            mainHandler.post(() -> listener.onScanStopped());
        }
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = Math.max(10, Math.min(30, powerDbm));
        saveSettings();
        Log.d(TAG, "Simulator power set to: " + this.powerDbm + " dBm");
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = Math.max(-100, Math.min(-30, threshold));
        saveSettings();
        Log.d(TAG, "Simulator RSSI threshold set to: " + this.rssiThreshold);
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        this.scanIntervalMs = Math.max(50, Math.min(1000, intervalMs));
        saveSettings();
        Log.d(TAG, "Simulator scan interval set to: " + this.scanIntervalMs + "ms");
    }
    
    // Getters
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    public int getSession() { return session; }
    public int getTarget() { return target; }
    public int getQValue() { return qValue; }
    
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
        return "Simulator (Test Mode)";
    }
    
    @Override
    public String getFirmwareVersion() {
        return "1.0.0-sim";
    }
    
    @Override
    public int getBatteryLevel() {
        return 100;
    }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }
    
    // عمليات القراءة والكتابة - محاكاة
    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        Log.d(TAG, "Simulator writeData: bank=" + bank + ", ptr=" + ptr + ", data=" + data);
        return true;
    }
    
    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
        Log.d(TAG, "Simulator readData: bank=" + bank + ", ptr=" + ptr + ", len=" + len);
        return "00000000";
    }
    
    @Override
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        return true;
    }
    
    @Override
    public boolean setFilter(int bank, int ptr, String filterData) {
        return true;
    }
    
    @Override
    public void clearFilter() {
        Log.d(TAG, "Simulator clearFilter");
    }
    
    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        Log.d(TAG, "Simulator writeEpc: " + newEpc);
        return true;
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return true;
    }
    
    @Override
    public String readTid() {
        return "E28068940000501A2B3C4D5E6F";
    }
    
    @Override
    public String readUserMemory(int address, int length) {
        return "00000000";
    }
    
    @Override
    public boolean lockTag(String accessPassword) {
        return true;
    }
    
    @Override
    public boolean unlockTag(String accessPassword) {
        return true;
    }
    
    @Override
    public boolean killTag(String accessPassword) {
        return true;
    }
}
