package com.example.uhf.MiraRfid;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;
import com.example.uhf.manager.MiraSettingsManager;

import org.json.JSONArray;   // ✅ هذا مفقود
import org.json.JSONObject;
import com.example.uhf.api.MiraApiClient;

/**
 * خدمة RFID موحدة لربط القارئات وواجهات التطبيق
 */
public class MiraRfidService {
    private static final String TAG = "MiraRfidService";
    private static MiraRfidService instance;
    
    private Context context;
    private MiraIRFIDReader activeReader;
    private boolean isSimulationMode = false;
    private String lastReadEpc = "";
    private String lastReadTid = "";
    
    private OperationState currentState = OperationState.IDLE;
    
    public enum OperationState {
        IDLE, READING, WRITING, VERIFYING, REGISTERING, COMPLETED, FAILED
    }
    
    private MiraRfidService(Context context) {
        this.context = context;
        detectAndInitializeReader();
    }
    
    public static synchronized MiraRfidService getInstance(Context context) {
        if (instance == null) {
            instance = new MiraRfidService(context);
        }
        return instance;
    }
    
    private void detectAndInitializeReader() {
        try {
            ChainwayReader chainwayReader = new ChainwayReader(context);
            if (chainwayReader.initialize() && chainwayReader.connect()) {
                activeReader = chainwayReader;
                isSimulationMode = false;
                Log.i(TAG, "✅ تم الاتصال بالقارئ الحقيقي: Chainway");
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "فشل الاتصال بالقارئ الحقيقي: " + e.getMessage());
        }
        
        SimulatorReader simulatorReader = new SimulatorReader(context);
        simulatorReader.initialize();
        simulatorReader.connect();
        activeReader = simulatorReader;
        isSimulationMode = true;
        Log.i(TAG, "🔬 تم التفعيل في وضع المحاكاة");
    }
    
    public boolean isSimulationMode() { return isSimulationMode; }
    public MiraIRFIDReader getActiveReader() { return activeReader; }
    public OperationState getCurrentState() { return currentState; }
    public String getLastReadEpc() { return lastReadEpc; }
    public String getLastReadTid() { return lastReadTid; }

    // =========================================================================
    // 🛠️ دوال الوصول للذاكرة والفلترة
    // =========================================================================

    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        if (activeReader == null) return false;
        return activeReader.writeData(pwdStr, bank, ptr, data);
    }

    public String readData(String pwdStr, int bank, int ptr, int len) {
        if (activeReader == null) return null;
        return activeReader.readData(pwdStr, bank, ptr, len);
    }

    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        if (activeReader == null) return false;
        return activeReader.setTagLockState(pwdStr, bank, shouldLock);
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
    
    public String readEpc(String accessPassword) {
        currentState = OperationState.READING;
        String epc = readData(accessPassword, 1, 32, 6);
        if (epc != null && !epc.isEmpty()) {
            lastReadEpc = epc;
            currentState = OperationState.COMPLETED;
            return epc;
        }
        currentState = OperationState.FAILED;
        return null;
    }
    
    public String readTid(String accessPassword) {
        currentState = OperationState.READING;
        String tid = readData(accessPassword, 2, 0, 6);
        if (tid != null && !tid.isEmpty()) {
            lastReadTid = tid;
            currentState = OperationState.COMPLETED;
            return tid;
        }
        currentState = OperationState.FAILED;
        return null;
    }
    
    public boolean writeEpc(String newEpc, String accessPassword) {
        currentState = OperationState.WRITING;
        boolean success = writeData(accessPassword, 1, 32, newEpc);
        if (success) {
            lastReadEpc = newEpc;
            currentState = OperationState.COMPLETED;
            return true;
        }
        currentState = OperationState.FAILED;
        return false;
    }
    
    public boolean verifyWrite(String expectedEpc, String accessPassword) {
        currentState = OperationState.VERIFYING;
        String readBack = readEpc(accessPassword);
        if (readBack != null && readBack.equalsIgnoreCase(expectedEpc)) {
            currentState = OperationState.COMPLETED;
            return true;
        }
        currentState = OperationState.FAILED;
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
            
            if (response != null) {
                Log.d(TAG, "Response Code: " + response.code);
                Log.d(TAG, "Response Success: " + response.isSuccess);
                Log.d(TAG, "Response Body: " + response.getBody());
            }
            
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
            
            if (response != null) {
                Log.d(TAG, "Response Code: " + response.code);
                Log.d(TAG, "Response Body: " + response.getBody());
            }
            
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
            
            // ✅ استخدام identifier الفعلي
            item.put("title", "قطعة تجريبية - " + identifier);
            item.put("serial", identifier);
            item.put("karat", "21K");
            item.put("weight", "5.24");
            item.put("category", "Rings");
            item.put("status", "available");
            item.put("mira_id", identifier);  // ✅ المعرف الفعلي
            
            JSONObject barcode = new JSONObject();
            String gtin = generateGtinFromIdentifier(identifier);
            barcode.put("gtin", gtin);
            barcode.put("serial_no", identifier);  // ✅ المعرف الفعلي
            
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
    
    /**
     * ✅ توليد GTIN فريد من المعرف
     */
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
     * ✅ توليد EPC فريد من معرف القطعة
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

/**
 * ✅ التحقق من أن الشريحة معتمدة (في القائمة البيضاء)
 */
public boolean verifyTagAllowed(String tid) {
    try {
        Log.d(TAG, "🔍 التحقق من الشريحة: " + tid);
        
        MiraApiClient apiClient = MiraApiClient.getInstance(context);
        MiraApiClient.ApiResponse response = apiClient.getWithAuth("/rfid-inventory/verify?tid=" + tid);
        
        if (response != null && response.isSuccess && response.data != null) {
            boolean approved = response.data.optBoolean("approved", false);
            Log.d(TAG, "Tag approved: " + approved);
            return approved;
        }
        
        // ✅ في وضع المحاكاة - السماح للاختبار
        if (isSimulationMode) {
            Log.w(TAG, "⚠️ وضع المحاكاة - السماح بالشريحة");
            return true;
        }
        
        return false;
    } catch (Exception e) {
        Log.e(TAG, "verifyTagAllowed error: " + e.getMessage());
        return isSimulationMode;
    }
}

/**
 * ✅ تحديث حالة الشريحة بعد البرمجة
 */
public boolean markTagProgrammed(String tid, String miraId, String epc) {
    try {
        Log.d(TAG, "📝 تحديث حالة الشريحة: " + tid + " → " + miraId);
        
        MiraApiClient apiClient = MiraApiClient.getInstance(context);
        
        JSONObject payload = new JSONObject();
        payload.put("tid", tid);
        payload.put("mira_id", miraId);
        payload.put("epc", epc);
        
        MiraApiClient.ApiResponse response = apiClient.postWithAuth("/rfid-inventory/programmed", payload);
        
        if (response != null && response.isSuccess) {
            Log.d(TAG, "✅ تم تحديث حالة الشريحة");
            return true;
        }
        
        if (isSimulationMode) {
            return true;
        }
        
        return false;
    } catch (Exception e) {
        Log.e(TAG, "markTagProgrammed error: " + e.getMessage());
        return isSimulationMode;
    }
}

/**
 * ✅ استيراد دفعة شرائح من SunlandRFID
 */
public boolean importBatch(String batchNumber, JSONArray tags) {
    try {
        Log.d(TAG, "📦 استيراد دفعة: " + batchNumber + " (" + tags.length() + " شريحة)");
        
        MiraApiClient apiClient = MiraApiClient.getInstance(context);
        
        JSONObject payload = new JSONObject();
        payload.put("batch_number", batchNumber);
        payload.put("tags", tags);
        
        MiraApiClient.ApiResponse response = apiClient.postWithAuth("/rfid-inventory/import", payload);
        
        if (response != null && response.isSuccess) {
            int imported = response.data != null ? response.data.optInt("imported", 0) : 0;
            Log.d(TAG, "✅ تم استيراد " + imported + " شريحة");
            return true;
        }
        
        return false;
    } catch (Exception e) {
        Log.e(TAG, "importBatch error: " + e.getMessage());
        return false;
    }
}

/**
 * ✅ جلب إحصائيات مخزون الشرائح
 */
public JSONObject getRfidInventoryStats() {
    try {
        MiraApiClient apiClient = MiraApiClient.getInstance(context);
        MiraApiClient.ApiResponse response = apiClient.getWithAuth("/rfid-inventory/stats");
        
        if (response != null && response.isSuccess && response.data != null) {
            return response.data;
        }
        
        // ✅ إحصائيات محاكاة
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
