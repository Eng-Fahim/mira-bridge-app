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
 * - يجلب الوسوم من قاعدة البيانات
 * - يطبق الإعدادات الديناميكية
 * - يحاكي RSSI واقعياً
 * - معالج ضد أخطاء NullPointerException أثناء الـ Thread Handling
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
    
    /**
     * وسوم احتياطية إذا فشل الجلب من قاعدة البيانات
     */
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
    
    /**
     * جلب الوسوم من قاعدة البيانات
     */
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
        
        // إذا فشل الجلب، استخدم الوسوم الاحتياطية
        if (databaseTags.isEmpty()) {
            databaseTags.addAll(fallbackTags);
            Log.w(TAG, "⚠️ Using fallback tags: " + databaseTags.size() + " tags");
        }
    }
    
    @Override
    public boolean initialize() {
        Log.i(TAG, "✅ Simulator initialized");
        return true;
    }
    
    @Override
    public boolean connect() {
        isConnected = true;
        
        // جلب الوسوم من قاعدة البيانات عند الاتصال
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
        if (!isConnected) {
            connect();
        }
        
        isScanning = true;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onScanStarted();
            }
        });
        
        scanExecutor.execute(this::simulationLoop);
        
        Log.i(TAG, "📡 Simulation started:");
        Log.i(TAG, "  - Power: " + powerDbm + " dBm");
        Log.i(TAG, "  - RSSI Threshold: " + rssiThreshold);
        Log.i(TAG, "  - Scan Interval: " + scanIntervalMs + "ms");
        Log.i(TAG, "  - Session: S" + session);
        Log.i(TAG, "  - Target: " + (target == 0 ? "A" : "B"));
        Log.i(TAG, "  - Q Value: " + qValue);
        Log.i(TAG, "  - Tags available: " + databaseTags.size());
        
        return true;
    }
    
    /**
     * حلقة المحاكاة الواقعية
     */
    private void simulationLoop() {
        int index = 0;
        int consecutiveMisses = 0;
        
        while (isScanning) {
            try {
                if (databaseTags.isEmpty()) {
                    // لا توجد وسوم - انتظر
                    Thread.sleep(1000);
                    continue;
                }
                
                // اختيار وسم
                String epc = databaseTags.get(index % databaseTags.size());
                
                // محاكاة RSSI واقعية
                int rssi = simulateRssi();
                
                // تطبيق عتبة RSSI
                if (rssi >= rssiThreshold) {
                    consecutiveMisses = 0;
                    final String finalEpc = epc;
                    final int finalRssi = rssi;
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onTagRead(finalEpc, finalRssi);
                        }
                    });
                } else {
                    consecutiveMisses++;
                    // إذا فشل القراءة عدة مرات، تخطى هذا الوسم
                    if (consecutiveMisses > 3) {
                        consecutiveMisses = 0;
                        index++;
                    }
                }
                
                index++;
                
                // محاكاة تداخل الإشارات - أحياناً يقرأ نفس الوسم مرتين
                if (random.nextInt(100) < 15) { // 15% احتمال تكرار
                    // لا تزيد index لقراءة نفس الوسم مرة أخرى
                } else {
                    index++;
                }
                
                // النوم حسب فترة المسح مع بعض العشوائية
                long actualDelay = scanIntervalMs + random.nextInt(50) - 25;
                Thread.sleep(Math.max(50, actualDelay));
                
            } catch (InterruptedException e) {
                Log.d(TAG, "Simulation loop interrupted");
                break;
            } catch (Exception e) {
                if (isScanning) {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onError(e.getMessage());
                        }
                    });
                }
                Log.e(TAG, "Simulation loop error", e);
                break;
            }
        }
    }
    
    /**
     * محاكاة RSSI واقعية بناءً على:
     * - قوة الإرسال
     * - المسافة (عشوائية)
     * - التداخل (عشوائي)
     * - Q Value (كلما زاد Q، زادت الدقة لكن قل المدى)
     */
    private int simulateRssi() {
        // القوة الأساسية تعتمد على powerDbm
        int baseRssi = -95 + (powerDbm - 10) * 2; // 10dBm → -95, 30dBm → -55
        
        // تأثير Q Value على المدى
        int qPenalty = qValue * 2; // Q=0 → 0, Q=15 → -30
        
        // المسافة العشوائية (0-5 متر)
        int distancePenalty = random.nextInt(30); // 0 إلى -30
        
        // التداخل العشوائي
        int interference = random.nextInt(20) - 10; // -10 إلى +10
        
        // الحساب النهائي
        int rssi = baseRssi - qPenalty - distancePenalty + interference;
        
        // تحديد النطاق
        return Math.max(-100, Math.min(-30, rssi));
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onScanStopped();
            }
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
    
    // عمليات القراءة والكتابة - محاكاة
    @Override
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        Log.d(TAG, "Simulator writeData: bank=" + bank + ", ptr=" + ptr);
        return true;
    }
    
    @Override
    public String readData(String pwdStr, int bank, int ptr, int len) {
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
