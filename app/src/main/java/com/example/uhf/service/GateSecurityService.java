package com.example.uhf.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraGatesSettings;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🚪 خدمة البوابة الأمنية - تعمل في الخلفية للمراقبة المستمرة
 */
public class GateSecurityService extends Service {

    private static final String TAG = "GateSecurityService";
    private static final long HEARTBEAT_INTERVAL = 5000; // 5 ثواني
    private static final long SCAN_CHECK_INTERVAL = 1000; // 1 ثانية

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    private RFIDManager rfidManager;
    private MiraApiClient apiClient;
    private MiraGatesSettings gatesSettings;
    
    private boolean isGateActive = false;
    private String currentGateId = "";
    private int passCount = 0;
    private int denyCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        rfidManager = RFIDManager.getInstance(this);
        apiClient = MiraApiClient.getInstance(this);
        gatesSettings = MiraGatesSettings.getInstance(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        currentGateId = intent != null ? intent.getStringExtra("gate_id") : "";
        startGateMonitoring();
        return START_STICKY;
    }

    /**
     * ✅ بدء مراقبة البوابة
     */
    private void startGateMonitoring() {
        isGateActive = true;
        
        // بدء RFID
        rfidManager.startScan();
        
        // بدء Heartbeat
        startHeartbeat();
        
        // بدء معالجة المسح
        startScanProcessing();
        
        Log.i(TAG, "🚪 Gate monitoring started: " + currentGateId);
    }

    /**
     * ✅ إرسال Heartbeat دوري
     */
    private void startHeartbeat() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isGateActive) return;
                
                executor.execute(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("gate_id", currentGateId);
                        payload.put("status", "online");
                        payload.put("battery", getBatteryLevel());
                        apiClient.heartbeat("online", getBatteryLevel());
                    } catch (Exception e) {
                        Log.e(TAG, "Heartbeat error: " + e.getMessage());
                    }
                });
                
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        }, HEARTBEAT_INTERVAL);
    }

    /**
     * ✅ معالجة المسح الضوئي
     */
    private void startScanProcessing() {
        rfidManager.setListener(new com.example.uhf.MiraRfid.MiraIRFIDReader.OnTagReadListener() {
            @Override
            public void onTagRead(String epc, int rssi) {
                processScannedEpc(epc, rssi);
            }
            
            @Override public void onScanStarted() {}
            @Override public void onScanStopped() {}
            
            @Override
            public void onConnectionChanged(boolean connected) {
                Log.i(TAG, "Gate RFID: " + (connected ? "Connected" : "Disconnected"));
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Gate RFID Error: " + error);
            }
        });
    }

    /**
     * ✅ معالجة EPC الممسوح
     */
    private void processScannedEpc(String epc, int rssi) {
        executor.execute(() -> {
            try {
                // فحص القطعة في قاعدة البيانات
                JSONObject extraParams = new JSONObject();
                extraParams.put("context", "gate_security");
                extraParams.put("gate_id", currentGateId);
                
                MiraApiClient.ApiResponse response = apiClient.authorize(epc, "gate", extraParams);
                
                boolean allowed = response != null && response.isSuccess;
                
                mainHandler.post(() -> {
                    if (allowed) {
                        passCount++;
                        playPassSound();
                    } else {
                        denyCount++;
                        playAlarmSound();
                        triggerAlarm(epc);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Process EPC error: " + e.getMessage());
            }
        });
    }

    /**
     * ✅ تشغيل صوت النجاح
     */
    private void playPassSound() {
        String soundKey = gatesSettings.getSoundPass();
        com.example.uhf.tools.SoundGenerator.playSound(soundKey);
    }

    /**
     * ✅ تشغيل صوت الإنذار
     */
    private void playAlarmSound() {
        String soundKey = gatesSettings.getSoundAlarm();
        com.example.uhf.tools.SoundGenerator.playSound(soundKey);
    }

    /**
     * ✅ تفعيل الإنذار
     */
    private void triggerAlarm(String epc) {
        // اهتزاز
        if (gatesSettings.isVibrationEnabled()) {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                    gatesSettings.getAlarmDuration() * 1000L, 
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        }
        
        // تسجيل في قاعدة البيانات
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("epc", epc);
                payload.put("gate_id", currentGateId);
                payload.put("allowed", 0);
                payload.put("action_taken", "ALARM");
                apiClient.submitInventoryAuditReport(payload);
            } catch (Exception e) {
                Log.e(TAG, "Alarm log error: " + e.getMessage());
            }
        });
    }

    private int getBatteryLevel() {
        android.content.IntentFilter filter = new android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED);
        android.content.Intent batteryIntent = registerReceiver(null, filter);
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                return (level * 100) / scale;
            }
        }
        return 100;
    }

    public void stopGateMonitoring() {
        isGateActive = false;
        rfidManager.stopScan();
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        stopGateMonitoring();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
