package com.example.uhf.MiraRfid;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;
import com.example.uhf.api.MiraApiClient;
import com.rscja.deviceapi.RFIDWithUHFUART;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * خدمة RFID موحدة لربط القارئات وواجهات التطبيق
 * 
 * ✅ الإصدار 2.0 — تصحيحات جوهرية:
 * - توحيد كلمة المرور الافتراضية "00000000" على مستوى الخدمة
 * - normalizePwd() لحماية من null في كل دوال الذاكرة
 * - تصحيح ptr من Bit(32) إلى Word(2) لـ EPC
 * - استخدام ثوابت Bank_* بدل الأرقام السحرية
 * - Logging تفصيلي لكل عملية
 */
public class MiraRfidService {
    private static final String TAG = "MiraRfidService";
    private static volatile MiraRfidService instance;
    
    // ✅ كلمة المرور الافتراضية للشرائح الخام
    private static final String DEFAULT_PWD = "00000000";
    
    // ✅ Word pointer حيث يبدأ EPC (Word 2 = Bit 32)
    private static final int EPC_START_WORD = 2;
    private static final int EPC_READ_LENGTH = 6;  // 6 words = 96 bits = 24 hex chars
    
    private final Context context;
    private MiraIRFIDReader activeReader;
    private volatile boolean isSimulationMode = false;
    private volatile String lastReadEpc = "";
    private volatile String lastReadTid = "";
    
    private volatile OperationState currentState = OperationState.IDLE;
    
    public enum OperationState {
        IDLE, READING, WRITING, VERIFYING, REGISTERING, COMPLETED, FAILED
    }
    
    private MiraRfidService(Context context) {
        // ✅ حماية الـ Memory Leak باستخدام ApplicationContext
        this.context = context.getApplicationContext();
        detectAndInitializeReader();
    }
    
    public static MiraRfidService getInstance(Context context) {
        if (instance == null) {
            synchronized (MiraRfidService.class) {
                if (instance == null) {
                    instance = new MiraRfidService(context);
                }
            }
        }
        return instance;
    }

    private void detectAndInitializeReader() {
        // ✅ استخدام خيط منفصل للاتصال مع التحكم بالأولوية
        Thread detectThread = new Thread(() -> {
            ChainwayReader chainwayReader = null;
            try {
                chainwayReader = new ChainwayReader(context);
                if (chainwayReader.initialize() && chainwayReader.connect()) {
                    activeReader = chainwayReader;
                    isSimulationMode = false;
                    Log.i(TAG, "✅ تم الاتصال بالقارئ الحقيقي: Chainway");
                    return;
                } else {
                    chainwayReader.release();
                    chainwayReader = null;
                }
            } catch (Exception e) {
                Log.w(TAG, "فشل الاتصال بالقارئ الحقيقي: " + e.getMessage());
                if (chainwayReader != null) {
                    try { chainwayReader.release(); } catch (Exception ignored) {}
                }
            }
            
            // ✅ التوجه للمحاكاة
            SimulatorReader simulatorReader = new SimulatorReader(context);
            simulatorReader.initialize();
            simulatorReader.connect();
            activeReader = simulatorReader;
            isSimulationMode = true;
            Log.i(TAG, "🔬 تم التفعيل في وضع المحاكاة");
        });
        
        detectThread.setName("MiraRFID-Detector");
        detectThread.setDaemon(true);
        detectThread.start();
    }

    public synchronized void release() {
        if (activeReader != null) {
            try {
                activeReader.disconnect();
                activeReader.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing active reader: " + e.getMessage());
            }
            activeReader = null;
        }
        instance = null; // 👈 تفريغ الـ Singleton عند الخروج بنجاح
    }

    public boolean isSimulationMode() { return isSimulationMode; }
    public MiraIRFIDReader getActiveReader() { return activeReader; }
    public OperationState getCurrentState() { return currentState; }
    public String getLastReadEpc() { return lastReadEpc; }
    public String getLastReadTid() { return lastReadTid; }

    // =========================================================================
    // 🔐 دالة مساعدة: توحيد كلمة المرور
    // =========================================================================

    /**
     * ✅ توحيد كلمة المرور: null/empty → "00000000"
     * 
     * ملاحظة: هذا الحماية على مستوى الخدمة تعمل كطبقة ثانية (Defense in Depth)
     * بغض النظر عن القارئ النشط (Chainway / Simulator / مستقبلي).
     */
    private String normalizePwd(String pwdStr) {
        if (pwdStr == null || pwdStr.trim().isEmpty()) {
            return DEFAULT_PWD;
        }
        return pwdStr.trim().toUpperCase();
    }

    // =========================================================================
    // 🛠️ دوال الوصول للذاكرة والفلترة
    // =========================================================================

    /**
     * ✅ كتابة بيانات في بنك الذاكرة — مع توحيد كلمة المرور
     */
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (activeReader == null) {
            Log.w(TAG, "writeData: activeReader is null");
            return false;
        }
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "writeData: empty data");
            return false;
        }
        
        String safePwd = normalizePwd(pwdStr);
        Log.d(TAG, "📝 writeData: bank=" + bank + " ptr=" + ptr + 
              " len=" + data.length() + " pwd=" + safePwd);
        
        return activeReader.writeData(safePwd, bank, ptr, data);
    }

    /**
     * ✅ قراءة بيانات من بنك الذاكرة — مع توحيد كلمة المرور
     */
    public String readData(String pwdStr, int bank, int ptr, int len) {
        if (activeReader == null) {
            Log.w(TAG, "readData: activeReader is null");
            return null;
        }
        
        String safePwd = normalizePwd(pwdStr);
        Log.d(TAG, "📖 readData: bank=" + bank + " ptr=" + ptr + 
              " len=" + len + " pwd=" + safePwd);
        
        return activeReader.readData(safePwd, bank, ptr, len);
    }

    /**
     * ✅ قفل/فتح بنك الذاكرة — مع توحيد كلمة المرور
     */
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        if (activeReader == null) {
            Log.w(TAG, "setTagLockState: activeReader is null");
            return false;
        }
        
        String safePwd = normalizePwd(pwdStr);
        Log.i(TAG, "🔐 setTagLockState: bank=" + bank + 
              " lock=" + shouldLock + " pwd=" + safePwd);
        
        return activeReader.setTagLockState(safePwd, bank, shouldLock);
    }

    public boolean setFilter(int bank, int ptr, String filterData) {
        if (activeReader == null) return isSimulationMode;
        return activeReader.setFilter(bank, ptr, filterData);
    }

    public void clearFilter() {
        if (activeReader != null) {
            activeReader.clearFilter();
        }
    }

    /**
     * ✅ إعادة تهيئة المحاكي لشريحة جديدة
     */
    public void resetForNewTag() {
        if (activeReader instanceof SimulatorReader) {
            ((SimulatorReader) activeReader).resetForNewTag();
            Log.d(TAG, "✅ تم إعادة تهيئة المحاكي لشريحة جديدة");
        }
    }

    // =========================================================================
    // 🟢 العمليات المتقدمة
    // =========================================================================

    /**
     * ✅ قراءة EPC — مع ptr صحيح (Word 2)
     */
    public String readEpc(String accessPassword) {
        currentState = OperationState.READING;
        String safePwd = normalizePwd(accessPassword);
        
        // ✅ EPC يبدأ من Word 2 في Bank 1
        String epc = readData(safePwd, RFIDWithUHFUART.Bank_EPC, 
                              EPC_START_WORD, EPC_READ_LENGTH);
        
        if (epc != null && !epc.isEmpty()) {
            lastReadEpc = epc;
            currentState = OperationState.COMPLETED;
            Log.i(TAG, "✅ readEpc OK: " + epc);
            return epc;
        }
        currentState = OperationState.FAILED;
        Log.e(TAG, "❌ readEpc FAILED");
        return null;
    }

    /**
     * ✅ قراءة TID — من Bank 2 من Word 0
     */
    public String readTid(String accessPassword) {
        currentState = OperationState.READING;
        String safePwd = normalizePwd(accessPassword);
        
        // ✅ TID يبدأ من Word 0 في Bank 2
        String tid = readData(safePwd, RFIDWithUHFUART.Bank_TID, 0, 6);
        
        if (tid != null && !tid.isEmpty()) {
            lastReadTid = tid;
            currentState = OperationState.COMPLETED;
            Log.i(TAG, "✅ readTid OK: " + tid);
            return tid;
        }
        currentState = OperationState.FAILED;
        Log.e(TAG, "❌ readTid FAILED");
        return null;
    }

    /**
     * ✅ كتابة EPC — مع ptr صحيح (Word 2) + normalizePwd
     */
    public boolean writeEpc(String newEpc, String accessPassword) {
        currentState = OperationState.WRITING;
        
        if (newEpc == null || newEpc.isEmpty()) {
            Log.w(TAG, "writeEpc: empty EPC");
            currentState = OperationState.FAILED;
            return false;
        }
        
        String safePwd = normalizePwd(accessPassword);
        Log.i(TAG, "📝 writeEpc: epc=" + newEpc + " pwd=" + safePwd);
        
        // ✅ EPC يبدأ من Word 2 في Bank 1
        boolean success = writeData(safePwd, RFIDWithUHFUART.Bank_EPC, 
                                    EPC_START_WORD, newEpc);
        
        if (success) {
            lastReadEpc = newEpc;
            currentState = OperationState.COMPLETED;
            Log.i(TAG, "✅ writeEpc OK");
            return true;
        }
        currentState = OperationState.FAILED;
        Log.e(TAG, "❌ writeEpc FAILED");
        return false;
    }

    /**
     * ✅ التحقق من الكتابة — قراءة ومقارنة
     */
    public boolean verifyWrite(String expectedEpc, String accessPassword) {
        currentState = OperationState.VERIFYING;
        String safePwd = normalizePwd(accessPassword);
        
        String readBack = readEpc(safePwd);
        
        if (readBack != null && readBack.equalsIgnoreCase(expectedEpc)) {
            currentState = OperationState.COMPLETED;
            Log.i(TAG, "✅ verifyWrite OK: " + readBack);
            return true;
        }
        
        currentState = OperationState.FAILED;
        Log.e(TAG, "❌ verifyWrite FAILED: expected=" + expectedEpc + 
              " read=" + readBack);
        return false;
    }

    /**
     * ✅ تسجيل ربط RFID في قاعدة البيانات
     */
    public boolean registerRfidBinding(String miraId, String epc, String tid) {
        currentState = OperationState.REGISTERING;
        
        try {
            Log.d(TAG, "📡 تسجيل الربط في MIRA: " + miraId + " → " + epc);
            
            MiraApiClient apiClient = MiraApiClient.getInstance(context);
            
            JSONObject payload = new JSONObject();
            payload.put("mira_id", miraId);
            payload.put("epc", epc);
            payload.put("tid", tid);
            payload.put("gate_id", "handheld_c72");
            payload.put("device_type", isSimulationMode ? "simulator" : "chainway_c72");
            payload.put("operation_type", "register");
            
            MiraApiClient.ApiResponse response = apiClient.postWithAuth("/register_rfid", payload);
            
            if (response != null && response.isSuccess) {
                currentState = OperationState.COMPLETED;
                Log.d(TAG, "✅ تم التسجيل في قاعدة البيانات بنجاح");
                return true;
            }
            
            if (isSimulationMode) {
                Log.w(TAG, "⚠️ فشل الاتصال - نجاح وهمي للمحاكاة");
                currentState = OperationState.COMPLETED;
                return true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "registerRfidBinding error: " + e.getMessage(), e);
            if (isSimulationMode) {
                currentState = OperationState.COMPLETED;
                return true;
            }
        }
        
        currentState = OperationState.FAILED;
        return false;
    }

    /**
     * ✅ البحث عن قطعة في MIRA
     */
    public JSONObject searchInMira(String identifier, String identifierType) {
        try {
            Log.d(TAG, "🔍 البحث في MIRA: " + identifier);
            
            MiraApiClient apiClient = MiraApiClient.getInstance(context);
            
            JSONObject searchPayload = new JSONObject();
            searchPayload.put("identifier", identifier);
            searchPayload.put("identifier_type", identifierType);
            searchPayload.put("gate_id", "handheld_c72");
            
            MiraApiClient.ApiResponse response = apiClient.postWithAuth("/authorize", searchPayload);
            
            if (response != null && response.isSuccess && response.data != null) {
                JSONObject item = response.data.optJSONObject("item");
                
                if (item != null) {
                    JSONObject result = new JSONObject();
                    result.put("item", item);
                    result.put("barcode", response.data.optJSONObject("barcode") != null ? 
                        response.data.optJSONObject("barcode") : new JSONObject());
                    result.put("stock", response.data.optJSONObject("stock") != null ? 
                        response.data.optJSONObject("stock") : new JSONObject());
                    result.put("location", response.data.optJSONObject("location") != null ? 
                        response.data.optJSONObject("location") : new JSONObject());
                    
                    Log.d(TAG, "✅ تم العثور على القطعة");
                    return result;
                }
            }
            
            if (isSimulationMode) {
                return createSimulatedSearchResult(identifier);
            }
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "searchInMira error: " + e.getMessage());
            if (isSimulationMode) {
                return createSimulatedSearchResult(identifier);
            }
            return null;
        }
    }

    /**
     * ✅ إنشاء نتيجة بحث محاكاة - مع قيم فريدة لكل قطعة
     */
    private JSONObject createSimulatedSearchResult(String identifier) {
        try {
            JSONObject result = new JSONObject();
            JSONObject item = new JSONObject();
            
            item.put("title", "قطعة تجريبية - " + identifier);
            item.put("serial", identifier);
            item.put("karat", "21K");
            item.put("weight", "5.24");
            item.put("category", "Rings");
            item.put("status", "available");
            item.put("mira_id", identifier);
            
            JSONObject barcode = new JSONObject();
            String gtin = generateGtinFromIdentifier(identifier);
            barcode.put("gtin", gtin);
            barcode.put("serial_no", identifier);
            
            JSONObject stock = new JSONObject();
            stock.put("status", "متوفر");
            stock.put("branch_name", "الفرع الرئيسي");
            
            JSONObject location = new JSONObject();
            location.put("zone_name", "منطقة الخواتم");
            location.put("shelf_code", "R-A1");
            
            result.put("item", item);
            result.put("barcode", barcode);
            result.put("stock", stock);
            result.put("location", location);
            
            Log.d(TAG, "✅ تم إنشاء بيانات محاكاة لـ: " + identifier);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "createSimulatedSearchResult error: " + e.getMessage());
            return new JSONObject();
        }
    }

    private String generateGtinFromIdentifier(String identifier) {
        String numbers = identifier.replaceAll("[^0-9]", "");
        
        if (numbers.isEmpty()) {
            numbers = String.valueOf(System.currentTimeMillis()).substring(5);
        }
        
        while (numbers.length() < 12) {
            numbers = "0" + numbers;
        }
        numbers = numbers.substring(0, 12);
        
        return numbers;
    }

    /**
     * ✅ توليد EPC من mira_id
     * 
     * الصيغة: "3008" + 12 حرف hex من mira_id + padding = 24 حرف
     */
    public String generateEpcFromMiraId(String miraId) {
        StringBuilder epc = new StringBuilder("3008");
        String cleanId = miraId.replaceAll("[^0-9A-Fa-f]", "");
        
        while (cleanId.length() < 12) {
            cleanId = "0" + cleanId;
        }
        epc.append(cleanId.substring(0, 12));
        
        while (epc.length() < 24) {
            epc.append("0");
        }
        
        return epc.substring(0, 24).toUpperCase();
    }

    public String getDeviceName() {
        if (activeReader == null) return "لا يوجد جهاز";
        return activeReader.getDeviceName() + (isSimulationMode ? " (محاكي)" : "");
    }

    public String getStateDescription() {
        switch (currentState) {
            case IDLE: return "جاهز";
            case READING: return "جاري القراءة...";
            case WRITING: return "جاري الكتابة...";
            case VERIFYING: return "جاري التحقق...";
            case REGISTERING: return "جاري التسجيل...";
            case COMPLETED: return "تمت العملية بنجاح";
            case FAILED: return "فشلت العملية";
            default: return "";
        }
    }

    // =========================================================================
    // 🟢 إدارة مخزون الشرائح من SunlandRFID
    // =========================================================================

    public boolean verifyTagAllowed(String tid) {
        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(context);
            MiraApiClient.ApiResponse response = apiClient.getWithAuth("/rfid-inventory/verify?tid=" + tid);
            
            if (response != null && response.isSuccess && response.data != null) {
                return response.data.optBoolean("approved", false);
            }
            return isSimulationMode;
        } catch (Exception e) {
            Log.e(TAG, "verifyTagAllowed error: " + e.getMessage());
            return isSimulationMode;
        }
    }

    public boolean markTagProgrammed(String tid, String miraId, String epc) {
        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(context);
            JSONObject payload = new JSONObject();
            payload.put("tid", tid);
            payload.put("mira_id", miraId);
            payload.put("epc", epc);
            
            MiraApiClient.ApiResponse response = apiClient.postWithAuth("/rfid-inventory/programmed", payload);
            return (response != null && response.isSuccess) || isSimulationMode;
        } catch (Exception e) {
            Log.e(TAG, "markTagProgrammed error: " + e.getMessage());
            return isSimulationMode;
        }
    }

    public boolean importBatch(String batchNumber, JSONArray tags) {
        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(context);
            JSONObject payload = new JSONObject();
            payload.put("batch_number", batchNumber);
            payload.put("tags", tags);
            
            MiraApiClient.ApiResponse response = apiClient.postWithAuth("/rfid-inventory/import", payload);
            return response != null && response.isSuccess;
        } catch (Exception e) {
            Log.e(TAG, "importBatch error: " + e.getMessage());
            return false;
        }
    }

    public JSONObject getRfidInventoryStats() {
        try {
            MiraApiClient apiClient = MiraApiClient.getInstance(context);
            MiraApiClient.ApiResponse response = apiClient.getWithAuth("/rfid-inventory/stats");
            
            if (response != null && response.isSuccess && response.data != null) {
                return response.data;
            }
            if (isSimulationMode) {
                JSONObject stats = new JSONObject();
                stats.put("total", 100);
                stats.put("available", 80);
                stats.put("programmed", 15);
                stats.put("defective", 3);
                stats.put("lost", 2);
                return stats;
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "getRfidInventoryStats error: " + e.getMessage());
            return null;
        }
    }
}
