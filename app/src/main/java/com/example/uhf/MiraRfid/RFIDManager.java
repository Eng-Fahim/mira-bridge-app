package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.MiraRfid.model.RFIDDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * المدير الرئيسي لقارئات RFID
 * - يكتشف الأجهزة
 * - يحفظ الإعدادات
 * - يدير الاتصال
 */
public class RFIDManager {
    
    private static final String TAG = "RFIDManager";
    private static final String PREFS_NAME = "mira_rfid_devices";
    private static final String KEY_DEVICES = "saved_devices";
    private static final String KEY_ACTIVE_ID = "active_device_id";
    
    private static RFIDManager instance;
    private final Context context;
    private IRFIDReader activeReader;
    private RFIDDevice activeDevice;
    private IRFIDReader.OnTagReadListener listener;
    
    private RFIDManager(Context context) {
        this.context = context.getApplicationContext();
    }
    
    public static synchronized RFIDManager getInstance(Context context) {
        if (instance == null) {
            instance = new RFIDManager(context);
        }
        return instance;
    }
    
    /**
     * اكتشاف تلقائي لنوع الجهاز
     */
    public IRFIDReader.ReaderType detectDeviceType() {
        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "";
        
        Log.i(TAG, "Detecting device: " + manufacturer + " " + model);
        
        if (model.contains("C72")) return IRFIDReader.ReaderType.CHAINWAY_C72;
        if (model.contains("C61")) return IRFIDReader.ReaderType.CHAINWAY_C61;
        if (model.contains("MC33") || model.contains("MC32")) return IRFIDReader.ReaderType.ZEBRA_MC3300;
        if (model.contains("EDA5") || model.contains("CT40")) return IRFIDReader.ReaderType.HONEYWELL_EDA50;
        if (model.contains("RP90")) return IRFIDReader.ReaderType.UNITECH_RP902;
        
        return IRFIDReader.ReaderType.SIMULATOR;
    }
    
    /**
     * حفظ جهاز جديد
     */
    public boolean saveDevice(RFIDDevice device) {
        try {
            List<RFIDDevice> devices = getAllDevices();
            
            // إزالة القديم إذا كان نفس النوع والعنوان
            devices.removeIf(d -> 
                d.getType() == device.getType() && 
                (device.getAddress() == null || device.getAddress().equals(d.getAddress()))
            );
            
            devices.add(0, device);
            
            return saveDevices(devices);
        } catch (Exception e) {
            Log.e(TAG, "saveDevice error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * حفظ قائمة الأجهزة
     */
    private boolean saveDevices(List<RFIDDevice> devices) {
        try {
            JSONArray arr = new JSONArray();
            for (RFIDDevice d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("type", d.getType() != null ? d.getType().name() : "");
                obj.put("address", d.getAddress() != null ? d.getAddress() : "");
                obj.put("custom_name", d.getCustomName() != null ? d.getCustomName() : "");
                obj.put("power", d.getPowerDbm());
                obj.put("rssi", d.getRssiThreshold());
                obj.put("interval", d.getScanIntervalMs());
                obj.put("is_default", d.isDefault());
                obj.put("last_used", d.getLastUsedAt());
                arr.put(obj);
            }
            
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_DEVICES, arr.toString()).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveDevices error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * الحصول على جميع الأجهزة المحفوظة
     */
    public List<RFIDDevice> getAllDevices() {
        List<RFIDDevice> devices = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_DEVICES, "[]");
            JSONArray arr = new JSONArray(json);
            
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                RFIDDevice device = new RFIDDevice();
                
                String typeName = obj.optString("type", "");
                if (!typeName.isEmpty()) {
                    try {
                        device.setType(IRFIDReader.ReaderType.valueOf(typeName));
                    } catch (Exception ignored) {}
                }
                
                device.setAddress(obj.optString("address", ""));
                device.setCustomName(obj.optString("custom_name", ""));
                device.setPowerDbm(obj.optInt("power", 28));
                device.setRssiThreshold(obj.optInt("rssi", -70));
                device.setScanIntervalMs(obj.optInt("interval", 200));
                device.setDefault(obj.optBoolean("is_default", false));
                device.setLastUsedAt(obj.optLong("last_used", 0));
                
                devices.add(device);
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllDevices error: " + e.getMessage());
        }
        return devices;
    }
    
    /**
     * حذف جهاز
     */
    public void deleteDevice(RFIDDevice device) {
        List<RFIDDevice> devices = getAllDevices();
        devices.removeIf(d -> d == device || d.toString().equals(device.toString()));
        saveDevices(devices);
    }
    
    /**
     * تعيين الجهاز النشط
     */
    public boolean setActiveDevice(RFIDDevice device) {
        this.activeDevice = device;
        device.setLastUsedAt(System.currentTimeMillis());
        saveDevice(device);
        
        if (activeReader != null) {
            activeReader.release();
            activeReader = null;
        }
        
        activeReader = RFIDReaderFactory.create(device.getType(), device.getAddress());
        
        if (listener != null) {
            activeReader.setOnTagReadListener(listener);
        }
        
        return activeReader.initialize();
    }
    
    /**
     * الحصول على الجهاز النشط
     */
    public RFIDDevice getActiveDevice() {
        if (activeDevice == null) {
            // استخدام أول جهاز افتراضي أو المحاكي
            List<RFIDDevice> devices = getAllDevices();
            for (RFIDDevice d : devices) {
                if (d.isDefault()) {
                    activeDevice = d;
                    break;
                }
            }
            if (activeDevice == null) {
                activeDevice = new RFIDDevice(detectDeviceType(), "");
                activeDevice.setDefault(true);
            }
        }
        return activeDevice;
    }
    
    /**
     * الحصول على القارئ النشط
     */
    public IRFIDReader getActiveReader() {
        if (activeReader == null) {
            setActiveDevice(getActiveDevice());
        }
        return activeReader;
    }
    
    /**
     * تعيين المستمع
     */
    public void setListener(IRFIDReader.OnTagReadListener listener) {
        this.listener = listener;
        if (activeReader != null) {
            activeReader.setOnTagReadListener(listener);
        }
    }
    
    /**
     * بدء المسح
     */
    public boolean startScan() {
        IRFIDReader reader = getActiveReader();
        if (reader == null) return false;
        if (!reader.isConnected()) {
            reader.connect();
        }
        return reader.startScan();
    }
    
    /**
     * إيقاف المسح
     */
    public void stopScan() {
        if (activeReader != null) {
            activeReader.stopScan();
        }
    }
    
    /**
     * تحرير الموارد
     */
    public void release() {
        if (activeReader != null) {
            activeReader.release();
            activeReader = null;
        }
    }
    
    /**
     * اختبار الاتصال
     */
    public boolean testConnection(RFIDDevice device) {
        IRFIDReader reader = RFIDReaderFactory.create(device.getType(), device.getAddress());
        boolean initSuccess = reader.initialize();
        if (!initSuccess) return false;
        
        boolean connectSuccess = reader.connect();
        reader.release();
        return connectSuccess;
    }
}
