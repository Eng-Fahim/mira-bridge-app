package com.example.uhf.MiraRfid;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * محاكي RFID للاختبار والتطوير
 * يحاكي سلوك قارئ RFID حقيقي مع شرائح افتراضية
 */
public class RFIDSimulator {
    private static final String TAG = "RFIDSimulator";
    private static RFIDSimulator instance;
    
    // تخزين الشرائح الافتراضية
    private final Map<String, SimulatedTag> simulatedTags = new HashMap<>();
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    // حالة المحاكي
    private boolean isScanning = false;
    private boolean isConnected = false;
    private MiraIRFIDReader.OnTagReadListener listener;
    
    // الشرائح الافتراضية للاختبار
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
    
    public static synchronized RFIDSimulator getInstance() {
        if (instance == null) {
            instance = new RFIDSimulator();
        }
        return instance;
    }
    
    /**
     * تهيئة شرائح افتراضية للاختبار
     */
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
    
    /**
     * الاتصال بالقارئ المحاكي
     */
    public boolean connect() {
        isConnected = true;
        if (listener != null) {
            listener.onConnectionChanged(true);
        }
        Log.i(TAG, "✅ تم الاتصال بالقارئ المحاكي");
        return true;
    }
    
    /**
     * قطع الاتصال
     */
    public void disconnect() {
        isConnected = false;
        stopScan();
        if (listener != null) {
            listener.onConnectionChanged(false);
        }
        Log.i(TAG, "❌ تم قطع الاتصال بالقارئ المحاكي");
    }
    
    /**
     * بدء المسح
     */
    public void startScan() {
        if (!isConnected) {
            connect();
        }
        
        isScanning = true;
        if (listener != null) {
            listener.onScanStarted();
        }
        
        // محاكاة قراءة الشرائح
        startSimulationLoop();
        Log.i(TAG, "📡 بدأ المسح المحاكي");
    }
    
    /**
     * إيقاف المسح
     */
    public void stopScan() {
        isScanning = false;
        handler.removeCallbacksAndMessages(null);
        if (listener != null) {
            listener.onScanStopped();
        }
        Log.i(TAG, "📡 توقف المسح المحاكي");
    }
    
    /**
     * حلقة المحاكاة - قراءة شرائح بشكل متتابع
     */
    private void startSimulationLoop() {
        if (!isScanning) return;
        
        // قراءة شريحة عشوائية
        readRandomTag();
        
        // جدولة القراءة التالية
        handler.postDelayed(() -> {
            if (isScanning) {
                startSimulationLoop();
            }
        }, 800 + random.nextInt(500));
    }
    
    /**
     * قراءة شريحة عشوائية
     */
    private void readRandomTag() {
        if (simulatedTags.isEmpty() || listener == null) return;
        
        // اختيار شريحة عشوائية
        Object[] tags = simulatedTags.values().toArray();
        SimulatedTag tag = (SimulatedTag) tags[random.nextInt(tags.length)];
        
        // تحديث RSSI
        tag.rssi = -40 - random.nextInt(20);
        
        // إرسال البيانات للمستمع
        listener.onTagRead(tag.epc, tag.rssi);
    }
    
    /**
     * قراءة شريحة محددة (للفلترة)
     */
    public String readSpecificTag(String filterData, int filterBank) {
        if (simulatedTags.isEmpty()) return null;
        
        for (SimulatedTag tag : simulatedTags.values()) {
            if (filterBank == 1) { // EPC Bank
                if (tag.epc.equalsIgnoreCase(filterData) || tag.epc.contains(filterData)) {
                    return tag.epc;
                }
            } else if (filterBank == 2) { // TID Bank
                if (tag.tid.equalsIgnoreCase(filterData) || tag.tid.contains(filterData)) {
                    return tag.epc;
                }
            }
        }
        return null;
    }
    
    /**
     * كتابة EPC جديد
     */
    public boolean writeEpc(String oldEpc, String newEpc, String password) {
        if (oldEpc == null || oldEpc.isEmpty()) {
            // كتابة شريحة جديدة
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
        
        // تحديث شريحة موجودة
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
    
    /**
     * كتابة User Memory
     */
    public boolean writeUserMemory(String epc, String data, String password) {
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
        
        Log.e(TAG, "❌ الشريحة غير موجودة: " + epc);
        return false;
    }
    
    /**
     * قراءة TID
     */
    public String readTid(String epc) {
        SimulatedTag tag = simulatedTags.get(epc);
        if (tag != null) {
            return tag.tid;
        }
        return null;
    }
    
    /**
     * قراءة User Memory
     */
    public String readUserMemory(String epc) {
        SimulatedTag tag = simulatedTags.get(epc);
        if (tag != null) {
            return tag.userMemory;
        }
        return null;
    }
    
    /**
     * قفل الشريحة
     */
    public boolean lockTag(String epc, String password) {
        SimulatedTag tag = simulatedTags.get(epc);
        if (tag != null) {
            tag.isLocked = true;
            Log.i(TAG, "🔐 تم قفل الشريحة: " + epc);
            return true;
        }
        return false;
    }
    
    /**
     * توليد TID عشوائي
     */
    private String generateRandomTid() {
        StringBuilder tid = new StringBuilder("E2806895");
        String hexChars = "0123456789ABCDEF";
        for (int i = 0; i < 8; i++) {
            tid.append(hexChars.charAt(random.nextInt(16)));
        }
        return tid.toString();
    }
    
    /**
     * إضافة شريحة افتراضية جديدة
     */
    public void addDemoTag(String epc, String tid, String userMemory) {
        SimulatedTag tag = new SimulatedTag();
        tag.epc = epc;
        tag.tid = tid;
        tag.rssi = -45;
        tag.userMemory = userMemory;
        tag.isLocked = false;
        simulatedTags.put(epc, tag);
    }
    
    /**
     * الحصول على عدد الشرائح
     */
    public int getTagCount() {
        return simulatedTags.size();
    }
    
    /**
     * تعيين المستمع
     */
    public void setListener(MiraIRFIDReader.OnTagReadListener listener) {
        this.listener = listener;
    }
    
    /**
     * التحقق من حالة الاتصال
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * التحقق من حالة المسح
     */
    public boolean isScanning() {
        return isScanning;
    }
    
    /**
     * كلاس يمثل شريحة افتراضية
     */
    private static class SimulatedTag {
        String epc;
        String tid;
        int rssi;
        String userMemory;
        boolean isLocked;
    }

    
}
