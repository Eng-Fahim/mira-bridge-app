package com.example.uhf.MiraRfid.readers;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * محاكي قارئ RFID كامل
 * يحاكي شرائح حقيقية في الذاكرة
 */
public class SimulatorReader implements MiraIRFIDReader {
    private static final String TAG = "SimulatorReader";
    
    private Context context;
    private OnTagReadListener listener;
    private boolean isScanning = false;
    private boolean isConnected = false;
    
    // تخزين الشرائح الافتراضية
    private final Map<String, SimulatedTag> tagsMap = new HashMap<>();
    private String currentSelectedEpc = "";
    
    // ✅ Constructor يقبل Context
    public SimulatorReader(Context context) {
        this.context = context;
        initializeDemoTags();
    }
    
    // ✅ Constructor بدون معاملات (للتغلب على مشكلة الـ Factory)
    public SimulatorReader() {
        this(null);
    }
    
    private void initializeDemoTags() {
        addTag("300833B2DDD9014000000001", "E2806895000050A1B2C3D4", "MIRA-JWL-001001");
        addTag("300833B2DDD9014000000002", "E2806895000050A1B2C3D5", "MIRA-JWL-001002");
        addTag("300833B2DDD9014000000003", "E2806895000050A1B2C3D6", "MIRA-JWL-001003");
        addTag("076000000000000000000001", "E2806895000050A1B2C3D7", "MIRA-JWL-001004");
        addTag("076000000000000000000002", "E2806895000050A1B2C3D8", "MIRA-JWL-001005");
        Log.i(TAG, "✅ تم تهيئة " + tagsMap.size() + " شريحة افتراضية");
    }
    
    private void addTag(String epc, String tid, String userMemory) {
        SimulatedTag tag = new SimulatedTag();
        tag.epc = epc;
        tag.tid = tid;
        tag.userMemory = userMemory;
        tag.isLocked = false;
        tagsMap.put(epc, tag);
    }
    
    public String readEpcData(String accessPassword) {
        if (tagsMap.isEmpty()) return null;
        
        if (!currentSelectedEpc.isEmpty() && tagsMap.containsKey(currentSelectedEpc)) {
            return currentSelectedEpc;
        }
        
        return tagsMap.keySet().iterator().next();
    }
    
    public String readTidData(String accessPassword) {
        String epc = readEpcData(accessPassword);
        if (epc != null) {
            SimulatedTag tag = tagsMap.get(epc);
            if (tag != null) {
                currentSelectedEpc = epc;
                return tag.tid;
            }
        }
        return null;
    }
    
    public boolean writeEpcData(String newEpc, String accessPassword) {
        String oldEpc = currentSelectedEpc;
        if (oldEpc.isEmpty()) {
            oldEpc = readEpcData(accessPassword);
        }
        
        if (oldEpc != null && tagsMap.containsKey(oldEpc)) {
            SimulatedTag tag = tagsMap.get(oldEpc);
            if (tag.isLocked) {
                Log.e(TAG, "❌ الشريحة مقفلة");
                return false;
            }
            
            tagsMap.remove(oldEpc);
            tag.epc = newEpc;
            tagsMap.put(newEpc, tag);
            currentSelectedEpc = newEpc;
            Log.i(TAG, "✅ تم كتابة EPC: " + newEpc);
            return true;
        }
        
        // إنشاء شريحة جديدة
        SimulatedTag newTag = new SimulatedTag();
        newTag.epc = newEpc;
        newTag.tid = generateTid();
        newTag.userMemory = "";
        newTag.isLocked = false;
        tagsMap.put(newEpc, newTag);
        currentSelectedEpc = newEpc;
        Log.i(TAG, "✅ تم إنشاء شريحة جديدة: " + newEpc);
        return true;
    }
    
    private String generateTid() {
        StringBuilder tid = new StringBuilder("E2806895");
        Random random = new Random();
        String hexChars = "0123456789ABCDEF";
        for (int i = 0; i < 8; i++) {
            tid.append(hexChars.charAt(random.nextInt(16)));
        }
        return tid.toString();
    }
    
    // ============ باقي دوال الواجهة ============
    
    @Override
    public boolean initialize() {
        return true;
    }
    
    @Override
    public boolean connect() {
        isConnected = true;
        if (listener != null) listener.onConnectionChanged(true);
        return true;
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        if (listener != null) listener.onConnectionChanged(false);
    }
    
    @Override
    public void release() {
        disconnect();
        tagsMap.clear();
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (!isConnected) connect();
        isScanning = true;
        if (listener != null) listener.onScanStarted();
        return true;
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        if (listener != null) listener.onScanStopped();
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    @Override
    public void setPower(int powerDbm) {}
    
    @Override
    public void setRssiThreshold(int threshold) {}
    
    @Override
    public void setScanInterval(int intervalMs) {}
    
    @Override
    public String getDeviceName() {
        return "محاكي RFID";
    }
    
    @Override
    public String getFirmwareVersion() {
        return "1.0.0-SIM";
    }
    
    @Override
    public int getBatteryLevel() {
        return 100;
    }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }
    
    @Override
    public boolean writeEpc(String newEpc, String accessPassword) {
        return writeEpcData(newEpc, accessPassword);
    }
    
    @Override
    public boolean writeUserMemory(int address, String data, String accessPassword) {
        return true;
    }
    
    @Override
    public String readTid() {
        return readTidData("00000000");
    }
    
    @Override
    public String readUserMemory(int address, int length) {
        return "MIRA-JWL-001001";
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
    
    private static class SimulatedTag {
        String epc;
        String tid;
        String userMemory;
        boolean isLocked;
    }
}
