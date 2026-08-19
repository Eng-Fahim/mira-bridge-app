package com.example.uhf.MiraRfid;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.readers.SimulatorReader;
import com.example.uhf.manager.MiraSettingsManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * خدمة RFID موحدة
 * تتعامل مع القارئ الحقيقي أو المحاكي تلقائياً
 * وتدير تسجيل الربط مع MIRA API
 */
public class MiraRfidService {
    private static final String TAG = "MiraRfidService";
    private static MiraRfidService instance;
    
    private Context context;
    private MiraIRFIDReader activeReader;
    private boolean isSimulationMode = false;
    private String lastReadEpc = "";
    private String lastReadTid = "";
    
    // حالة العملية الحالية
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
    
    /**
     * اكتشاف القارئ المتاح
     */
    private void detectAndInitializeReader() {
        try {
            // محاولة تهيئة القارئ الحقيقي
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
        
        // إذا فشل، استخدم المحاكي
        SimulatorReader simulatorReader = new SimulatorReader(context);
        simulatorReader.initialize();
        simulatorReader.connect();
        activeReader = simulatorReader;
        isSimulationMode = true;
        Log.i(TAG, "🔬 تم التفعيل في وضع المحاكاة");
    }
    
    public boolean isSimulationMode() {
        return isSimulationMode;
    }
    
    public MiraIRFIDReader getActiveReader() {
        return activeReader;
    }
    
    public OperationState getCurrentState() {
        return currentState;
    }
    
    public String getLastReadEpc() {
        return lastReadEpc;
    }
    
    public String getLastReadTid() {
        return lastReadTid;
    }

    // =========================================================================
    // 🛠️ الدوال المطلوبة من قبل MiraUHFReadWriteFragment (Low-Level Access)
    // =========================================================================

    /**
     * كتابة بيانات عامة إلى بنك محدد في الشريحة
     */
    public boolean writeData(String pwdStr, int bank, int ptr, String data) {
        currentState = OperationState.WRITING;
        if (activeReader == null) {
            currentState = OperationState.FAILED;
            return false;
        }

        try {
            boolean success = false;
            if (activeReader instanceof ChainwayReader) {
                success = ((ChainwayReader) activeReader).writeData(pwdStr, bank, ptr, data);
            } else if (activeReader instanceof SimulatorReader) {
                success = ((SimulatorReader) activeReader).writeData(pwdStr, bank, ptr, data);
            }

            currentState = success ? OperationState.COMPLETED : OperationState.FAILED;
            return success;
        } catch (Exception e) {
            Log.e(TAG, "writeData error: " + e.getMessage());
            currentState = OperationState.FAILED;
            return false;
        }
    }

    /**
     * قراءة بيانات عامة من بنك محدد في الشريحة
     */
    public String readData(String pwdStr, int bank, int ptr, int len) {
        currentState = OperationState.READING;
        if (activeReader == null) {
            currentState = OperationState.FAILED;
            return null;
        }

        try {
            String data = null;
            if (activeReader instanceof ChainwayReader) {
                data = ((ChainwayReader) activeReader).readData(pwdStr, bank, ptr, len);
            } else if (activeReader instanceof SimulatorReader) {
                data = ((SimulatorReader) activeReader).readData(pwdStr, bank, ptr, len);
            }

            if (data != null && !data.isEmpty()) {
                currentState = OperationState.COMPLETED;
                return data;
            }
        } catch (Exception e) {
            Log.e(TAG, "readData error: " + e.getMessage());
        }

        currentState = OperationState.FAILED;
        return null;
    }

    /**
     * تغيير حالة القفل للشريحة (Lock Tag State)
     */
    public boolean setTagLockState(String pwdStr, int bank, boolean shouldLock) {
        if (activeReader == null) return false;

        try {
            if (activeReader instanceof ChainwayReader) {
                return ((ChainwayReader) activeReader).setTagLockState(pwdStr, bank, shouldLock);
            } else if (activeReader instanceof SimulatorReader) {
                return ((SimulatorReader) activeReader).setTagLockState(pwdStr, bank, shouldLock);
            }
        } catch (Exception e) {
            Log.e(TAG, "setTagLockState error: " + e.getMessage());
        }
        return isSimulationMode; // إرجاع نجاح تلقائي في المحاكاة
    }

    /**
     * إلغاء تفكيك القناع والفلترة
     */
    public void clearFilter() {
        if (activeReader == null) return;

        try {
            if (activeReader instanceof ChainwayReader) {
                ((ChainwayReader) activeReader).clearFilter();
            } else if (activeReader instanceof SimulatorReader) {
                ((SimulatorReader) activeReader).clearFilter();
            }
        } catch (Exception e) {
            Log.e(TAG, "clearFilter error: " + e.getMessage());
        }
    }

    // =========================================================================
    // 🟢 العمليات المرتفعة المستوى (High-Level Operations)
    // =========================================================================
    
    /**
     * قراءة EPC من الشريحة
     */
    public String readEpc(String accessPassword) {
        currentState = OperationState.READING;
        
        if (activeReader == null) {
            currentState = OperationState.FAILED;
            return null;
        }
        
        try {
            String epc = null;
            if (activeReader instanceof ChainwayReader) {
                epc = ((ChainwayReader) activeReader).readEpcData(accessPassword);
            } else if (activeReader instanceof SimulatorReader) {
                epc = ((SimulatorReader) activeReader).readEpcData(accessPassword);
            }
            
            if (epc != null && !epc.isEmpty()) {
                lastReadEpc = epc;
                currentState = OperationState.COMPLETED;
                return epc;
            }
        } catch (Exception e) {
            Log.e(TAG, "readEpc error: " + e.getMessage());
        }
        
        currentState = OperationState.FAILED;
        return null;
    }
    
    /**
     * قراءة TID من الشريحة
     */
    public String readTid(String accessPassword) {
        currentState = OperationState.READING;
        
        if (activeReader == null) {
            currentState = OperationState.FAILED;
            return null;
        }
        
        try {
            String tid = null;
            if (activeReader instanceof ChainwayReader) {
                tid = ((ChainwayReader) activeReader).readTidData(accessPassword);
            } else if (activeReader instanceof SimulatorReader) {
                tid = ((SimulatorReader) activeReader).readTidData(accessPassword);
            }
            
            if (tid != null && !tid.isEmpty()) {
                lastReadTid = tid;
                currentState = OperationState.COMPLETED;
                return tid;
            }
        } catch (Exception e) {
            Log.e(TAG, "readTid error: " + e.getMessage());
        }
        
        currentState = OperationState.FAILED;
        return null;
    }
    
    /**
     * كتابة EPC على الشريحة
     */
    public boolean writeEpc(String newEpc, String accessPassword) {
        currentState = OperationState.WRITING;
        
        if (activeReader == null) {
            currentState = OperationState.FAILED;
            return false;
        }
        
        try {
            boolean success = false;
            if (activeReader instanceof ChainwayReader) {
                success = ((ChainwayReader) activeReader).writeEpcData(newEpc, accessPassword);
            } else if (activeReader instanceof SimulatorReader) {
                success = ((SimulatorReader) activeReader).writeEpcData(newEpc, accessPassword);
            }
            
            if (success) {
                lastReadEpc = newEpc;
                currentState = OperationState.COMPLETED;
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "writeEpc error: " + e.getMessage());
        }
        
        currentState = OperationState.FAILED;
        return false;
    }
    
    /**
     * التحقق من الكتابة - قراءة نفس الشريحة
     */
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
     * تسجيل الربط في MIRA API
     */
    public boolean registerRfidBinding(String miraId, String epc, String tid) {
        currentState = OperationState.REGISTERING;
        
        try {
            MiraSettingsManager settingsManager = MiraSettingsManager.getInstance(context);
            String apiUrl = settingsManager.getString("mira_api_url",
                "https://ams.ibreg.org/wp-json/mira-gate/v1/register_rfid");
            String apiKey = settingsManager.getString("mira_api_key",
                "mira_gate_test071234567890abcdefghijklmnop");
            
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            
            JSONObject payload = new JSONObject();
            payload.put("mira_id", miraId);
            payload.put("epc", epc);
            payload.put("tid", tid);
            payload.put("gate_id", "handheld_c72");
            payload.put("action", "register_rfid_binding");
            payload.put("device_type", isSimulationMode ? "simulator" : "chainway_c72");
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes("utf-8"));
            }
            
            int responseCode = conn.getResponseCode();
            
            if (responseCode >= 200 && responseCode < 300) {
                currentState = OperationState.COMPLETED;
                return true;
            }
            
            // في وضع المحاكاة، نقبل العملية حتى لو فشل الاتصال
            if (isSimulationMode) {
                currentState = OperationState.COMPLETED;
                return true;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "registerRfidBinding error: " + e.getMessage());
            
            // في وضع المحاكاة، نقبل العملية حتى لو فشل الاتصال
            if (isSimulationMode) {
                currentState = OperationState.COMPLETED;
                return true;
            }
        }
        
        currentState = OperationState.FAILED;
        return false;
    }
    
    /**
     * البحث عن قطعة في MIRA API
     */
    public JSONObject searchInMira(String identifier, String identifierType) {
        try {
            MiraSettingsManager settingsManager = MiraSettingsManager.getInstance(context);
            String apiUrl = settingsManager.getString("mira_api_url",
                "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize");
            String apiKey = settingsManager.getString("mira_api_key",
                "mira_gate_test071234567890abcdefghijklmnop");
            
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            
            JSONObject payload = new JSONObject();
            payload.put("identifier", identifier);
            payload.put("identifier_type", identifierType);
            payload.put("gate_id", "handheld_c72");
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes("utf-8"));
            }
            
            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            
            return new JSONObject(response.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "searchInMira error: " + e.getMessage());
            
            // في وضع المحاكاة، نرجع بيانات وهمية
            if (isSimulationMode) {
                return createSimulatedSearchResult(identifier);
            }
            
            return null;
        }
    }
    
    /**
     * إنشاء نتيجة بحث وهمية للمحاكاة
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
            item.put("mira_id", "MIRA-JWL-001001");
            
            JSONObject barcode = new JSONObject();
            barcode.put("gtin", "0701234567895");
            barcode.put("serial_no", "MIRA-JWL-001001");
            
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
            
            return result;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    /**
     * الحصول على اسم الجهاز الحالي
     */
    public String getDeviceName() {
        if (activeReader == null) return "لا يوجد جهاز";
        return activeReader.getDeviceName() + (isSimulationMode ? " (محاكي)" : "");
    }
    
    /**
     * الحصول على وصف الحالة
     */
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
}
