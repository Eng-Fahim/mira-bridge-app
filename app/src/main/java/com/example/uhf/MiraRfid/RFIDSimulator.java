package com.example.uhf.MiraRfid;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * محاكي RFID للاختبار والتطوير
 * يحاكي سلوك قارئ RFID حقيقي مع شرائح افتراضية
 */
public class RFIDSimulator {
    private static final String TAG = "RFIDSimulator";
    private static volatile RFIDSimulator instance;
    
    // استخدام ConcurrentHashMap لمنع أخطاء المزامنة أثناء التعديل والقراءة المتزامنة
    private final Map<String, SimulatedTag> simulatedTags = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    
    // حالة المحاكي
    private boolean isScanning = false;
    private boolean isConnected = false;
    private MiraIRFIDReader.OnTagReadListener listener;
    
    private static final String[] DEMO_EPCS = {
        "300833B2DDD9014000000001",
        "300833B2DDD9014000000002",
        "300833B2DDD9014000000003",
        "300833B2DDD9014000000004",
        "300833B2DDD9014000000005",
        "076000000000000000000001",
        "076000000000000000000002",
        "079999999999000000000001"
    };
    
    private RFIDSimulator() {
        initializeDemoTags();
    }
    
    public static RFIDSimulator getInstance() {
        if (instance == null) {
            synchronized (RFIDSimulator.class) {
                if (instance == null) {
                    instance = new RFIDSimulator();
                }
            }
        }
        return instance;
    }
    
    private void initializeDemoTags() {
        for (int i = 0; i < DEMO_EPCS.length; i++) {
            SimulatedTag tag = new SimulatedTag();
            tag.epc = DEMO_EPCS[i];
            tag.tid = "E2806895000050" + String.format("%02d", i + 10);
            tag.rssi = -40 - random.nextInt(20);
            tag.userMemory = "MIRA-JWL-" + String.format("%06d", 1000 + i);
            tag.isLocked = false;
            simulatedTags.put(tag.epc, tag);
        }
        Log.i(TAG, "✅ تم تهيئة " + simulatedTags.size() + " شريحة افتراضية");
    }
    
    public boolean connect() {
        isConnected = true;
        if (listener != null) {
            listener.onConnectionChanged(true);
        }
        Log.i(TAG, "✅ تم الاتصال بالقارئ المحاكي");
        return true;
    }
    
    public void disconnect() {
        isConnected = false;
        stopScan();
        if (listener != null) {
            listener.onConnectionChanged(false);
        }
        Log.i(TAG, "❌ تم قطع الاتصال بالقارئ المحاكي");
    }
    
    public void startScan() {
        if (!isConnected) {
            connect();
        }
        
        if (isScanning) return; // منع التكرار إذا كان المسح يعمل بالفعل
        
        isScanning = true;
        if (listener != null) {
            listener.onScanStarted();
        }
        
        startSimulationLoop();
        Log.i(TAG, "📡 بدأ المسح المحاكي");
    }
    
    public void stopScan() {
        isScanning = false;
        if (simulationRunnable != null) {
            handler.removeCallbacks(simulationRunnable);
            simulationRunnable = null;
        }
        if (listener != null) {
            listener.onScanStopped();
        }
        Log.i(TAG, "📡 توقف المسح المحاكي");
    }
    
    private void startSimulationLoop() {
        if (!isScanning) return;

        simulationRunnable = () -> {
            if (isScanning) {
                readRandomTag();
                // جدولة القراءة التالية بأمان
                if (isScanning && handler != null) {
                    handler.postDelayed(simulationRunnable, 500 + random.nextInt(400));
                }
            }
        };
        handler.post(simulationRunnable);
    }
    
    private void readRandomTag() {
        if (simulatedTags.isEmpty() || listener == null) return;
        
        try {
            // أخذ نسخة آمنة من العناصر لتفادي التداخل المتزامن
            List<SimulatedTag> tagList = new ArrayList<>(simulatedTags.values());
            if (tagList.isEmpty()) return;

            SimulatedTag tag = tagList.get(random.nextInt(tagList.size()));
            if (tag != null) {
                tag.rssi = -40 - random.nextInt(20);
                listener.onTagRead(tag.epc, tag.rssi);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during simulated tag read: " + e.getMessage());
        }
    }
    
    public String readSpecificTag(String filterData, int filterBank) {
        if (simulatedTags.isEmpty() || filterData == null) return null;
        
        for (SimulatedTag tag : simulatedTags.values()) {
            if (filterBank == 1) { // EPC
                if (tag.epc != null && (tag.epc.equalsIgnoreCase(filterData) || tag.epc.contains(filterData))) {
                    return tag.epc;
                }
            } else if (filterBank == 2) { // TID
                if (tag.tid != null && (tag.tid.equalsIgnoreCase(filterData) || tag.tid.contains(filterData))) {
                    return tag.epc;
                }
            }
        }
        return null;
    }
    
    public boolean writeEpc(String oldEpc, String newEpc, String password) {
        if (newEpc == null || newEpc.isEmpty()) return false;

        if (oldEpc == null || oldEpc.isEmpty()) {
            SimulatedTag newTag = new SimulatedTag();
            newTag.epc = newEpc;
            newTag.tid = generateRandomTid();
            newTag.rssi = -45;
            newTag.userMemory = "";
            newTag.isLocked = false;
            simulatedTags.put(newEpc, newTag);
            Log.i(TAG, "✅ تم إنشاء شريحة جديدة: " + newEpc);
            return true;
        }
        
        SimulatedTag tag = simulatedTags.get(oldEpc);
        if (tag != null) {
            if (tag.isLocked) {
                Log.e(TAG, "❌ الشريحة مقفلة - لا يمكن الكتابة");
                return false;
            }
            
            simulatedTags.remove(oldEpc);
            tag.epc = newEpc;
            simulatedTags.put(newEpc, tag);
            Log.i(TAG, "✅ تم تحديث EPC: " + oldEpc + " -> " + newEpc);
            return true;
        }
        
        Log.e(TAG, "❌ الشريحة غير موجودة: " + oldEpc);
        return false;
    }
    
    public boolean writeUserMemory(String epc, String data, String password) {
        if (epc == null) return false;
        SimulatedTag tag = simulatedTags.get(epc);
        if (tag != null) {
            if (tag.isLocked) {
                Log.e(TAG, "❌ الشريحة مقفلة - لا يمكن الكتابة");
                return false;
            }
            
            tag.userMemory = data;
            Log.i(TAG, "✅ تم كتابة User Memory: " + data);
            return true;
        }
        return false;
    }
    
    public String readTid(String epc) {
        if (epc == null) return null;
        SimulatedTag tag = simulatedTags.get(epc);
        return tag != null ? tag.tid : null;
    }
    
    public String readUserMemory(String epc) {
        if (epc == null) return null;
        SimulatedTag tag = simulatedTags.get(epc);
        return tag != null ? tag.userMemory : null;
    }
    
    public boolean lockTag(String epc, String password) {
        if (epc == null) return false;
        SimulatedTag tag = simulatedTags.get(epc);
        if (tag != null) {
            tag.isLocked = true;
            Log.i(TAG, "🔐 تم قفل الشريحة: " + epc);
            return true;
        }
        return false;
    }
    
    private String generateRandomTid() {
        StringBuilder tid = new StringBuilder("E2806895");
        String hexChars = "0123456789ABCDEF";
        for (int i = 0; i < 8; i++) {
            tid.append(hexChars.charAt(random.nextInt(16)));
        }
        return tid.toString();
    }
    
    public void addDemoTag(String epc, String tid, String userMemory) {
        if (epc == null) return;
        SimulatedTag tag = new SimulatedTag();
        tag.epc = epc;
        tag.tid = tid != null ? tid : generateRandomTid();
        tag.rssi = -45;
        tag.userMemory = userMemory != null ? userMemory : "";
        tag.isLocked = false;
        simulatedTags.put(epc, tag);
    }
    
    public int getTagCount() {
        return simulatedTags.size();
    }
    
    public void setListener(MiraIRFIDReader.OnTagReadListener listener) {
        this.listener = listener;
    }

    public void removeListener() {
        this.listener = null;
    }
    
    public boolean isConnected() {
        return isConnected;
    }
    
    public boolean isScanning() {
        return isScanning;
    }
    
    private static class SimulatedTag {
        String epc;
        String tid;
        int rssi;
        String userMemory;
        boolean isLocked;
    }
}
