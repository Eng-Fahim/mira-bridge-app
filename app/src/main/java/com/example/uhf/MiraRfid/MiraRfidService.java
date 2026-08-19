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
    // 🛠️ دوال الوصول للذاكرة المباشرة المطلوب استدعاؤها في الـ Fragment
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

    // =========================================================================
    // 🟢 العمليات العالية المستوى
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
    
    public boolean registerRfidBinding(String miraId, String epc, String tid) {
        currentState = OperationState.REGISTERING;
        try {
            MiraSettingsManager settingsManager = MiraSettingsManager.getInstance(context);
            String apiUrl = settingsManager.getString("mira_api_url", "https://ams.ibreg.org/wp-json/mira-gate/v1/register_rfid");
            String apiKey = settingsManager.getString("mira_api_key", "mira_gate_test071234567890abcdefghijklmnop");
            
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
            
            if (isSimulationMode) {
                currentState = OperationState.COMPLETED;
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "registerRfidBinding error: " + e.getMessage());
            if (isSimulationMode) {
                currentState = OperationState.COMPLETED;
                return true;
            }
        }
        
        currentState = OperationState.FAILED;
        return false;
    }
    
    public JSONObject searchInMira(String identifier, String identifierType) {
        try {
            MiraSettingsManager settingsManager = MiraSettingsManager.getInstance(context);
            String apiUrl = settingsManager.getString("mira_api_url", "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize");
            String apiKey = settingsManager.getString("mira_api_key", "mira_gate_test071234567890abcdefghijklmnop");
            
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
            InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            
            return new JSONObject(response.toString());
        } catch (Exception e) {
            Log.e(TAG, "searchInMira error: " + e.getMessage());
            if (isSimulationMode) {
                return createSimulatedSearchResult(identifier);
            }
            return null;
        }
    }
    
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
}
