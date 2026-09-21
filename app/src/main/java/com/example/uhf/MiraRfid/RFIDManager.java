package com.example.uhf.MiraRfid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.MiraRfid.model.RFIDDevice;
import com.example.uhf.MiraRfid.readers.SimulatorReader;
import com.example.uhf.activity.BaseTabFragmentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ RFIDManager v3.0 — النسخة النهائية
 * 
 * التغييرات:
 * - autoConnectAndBind() يعيد boolean
 * - getActiveReader() لا يتحول لـ Simulator بدون سبب
 * - startScan() يتحقق من isRfidDevicePresent()
 */
public class RFIDManager {
    
    private static final String TAG = "RFIDManager";
    private static final String PREFS_NAME = "mira_rfid_devices";
    private static final String KEY_DEVICES = "saved_devices";
    private static final String KEY_ACTIVE_ID = "active_device_id";
    
    private static RFIDManager instance;
    private final Context context;
    private MiraIRFIDReader activeReader;
    private RFIDDevice activeDevice;
    private MiraIRFIDReader.OnTagReadListener listener;
    
    private BroadcastReceiver keyReceiver;
    private boolean isReceiverRegistered = false;
    
    private RFIDManager(Context context) {
        this.context = context.getApplicationContext();
    }
    
    public static synchronized RFIDManager getInstance(Context context) {
        if (instance == null) {
            instance = new RFIDManager(context);
            RFIDReaderFactory.init(context);
        }
        return instance;
    }

    /**
 * ✅ الاتصال التلقائي مع التحقق الفعلي
 * @return true إذا نجح الاتصال
 */
public synchronized boolean autoConnectAndBind(Context hostContext) {
    try {
        Log.i(TAG, "🔗 autoConnectAndBind() called");
        
        // ═══════════════════════════════════════════════════
        // ✅ 1. فحص دعم العتاد أولاً (أهم خطوة!)
        // ═══════════════════════════════════════════════════
        if (!com.example.uhf.tools.DeviceDetector.isRfidHardwareSupported()) {
            Log.w(TAG, "⚠️ RFID hardware NOT supported — skipping auto-connect");
            return false;
        }
        
        // ✅ 2. تحقق من العتاد (الفكرة الديناميكية)
        if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
            Log.w(TAG, "⚠️ RFID device not present yet");
            return false;
        }
        
        // ✅ 3. احصل على القارئ المشترك
        com.rscja.deviceapi.RFIDWithUHFUART sharedReader = 
            BaseTabFragmentActivity.getSharedReader();
        
        if (sharedReader == null) {
            Log.w(TAG, "⚠️ Shared reader is null");
            return false;
        }
        
        Log.i(TAG, "✅ Shared reader available");
        
        // ✅ 4. احصل على الجهاز النشط
        RFIDDevice device = getActiveDevice();
        if (device == null) {
            Log.e(TAG, "❌ No active device");
            return false;
        }
        
        // ✅ 5. إذا كان SIMULATOR — لا تتصل
        if (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR) {
            Log.w(TAG, "⚠️ Active device is SIMULATOR");
            return false;
        }
        
        // ✅ 6. نظّف القارئ القديم
        if (activeReader != null && 
            !(activeReader instanceof com.example.uhf.MiraRfid.readers.ChainwayReader)) {
            try {
                activeReader.stopScan();
                activeReader.disconnect();
                activeReader.release();
            } catch (Exception ignored) {}
            activeReader = null;
        }
        
        // ✅ 7. أنشئ القارئ إذا لزم
        if (activeReader == null) {
            activeReader = RFIDReaderFactory.create(device.getType(), 
                device.getAddress());
            if (activeReader == null) {
                Log.e(TAG, "❌ Failed to create reader: " + device.getType());
                return false;
            }
        }
        
        // ✅ 8. ربط المستمع
        if (listener != null) {
            activeReader.setOnTagReadListener(listener);
        }
        
        // ✅ 9. تهيئة + اتصال
        if (!activeReader.isConnected()) {
            if (!activeReader.initialize()) {
                Log.e(TAG, "❌ initialize() failed");
                return false;
            }
            
            if (!activeReader.connect()) {
                Log.e(TAG, "❌ connect() failed");
                return false;
            }
        }
        
        // ✅ 10. تسجيل مستقبل الزناد
        registerTriggerKeyReceiver();
        
        Log.i(TAG, "✅ autoConnectAndBind SUCCESS: " + device.getType());
        return true;
        
    } catch (Exception e) {
        Log.e(TAG, "❌ autoConnectAndBind exception", e);
        return false;
    }
}

    public void registerTriggerKeyReceiver() {
        if (isReceiverRegistered) return;
        
        try {
            keyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if ("android.intent.action.FUN_KEY".equals(intent.getAction())) {
                        int keyCode = intent.getIntExtra("keyCode", 0);
                        boolean keyDown = intent.getBooleanExtra("keydown", false);

                        if (keyCode == 139 || keyCode == 280 || keyCode == 293) {
                            if (keyDown) {
                                startScan();
                            } else {
                                stopScan();
                            }
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter("android.intent.action.FUN_KEY");
            context.registerReceiver(keyReceiver, filter);
            isReceiverRegistered = true;
            Log.i(TAG, "🔑 Trigger receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register trigger receiver: " + e.getMessage());
        }
    }

    public void unregisterTriggerKeyReceiver() {
        if (isReceiverRegistered && keyReceiver != null) {
            try {
                context.unregisterReceiver(keyReceiver);
            } catch (Exception ignored) {}
            isReceiverRegistered = false;
        }
    }
    
    public MiraIRFIDReader.ReaderType detectDeviceType() {
        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        String manufacturer = Build.MANUFACTURER != null ? 
            Build.MANUFACTURER.toUpperCase() : "";
        
        if (model.contains("C72")) return MiraIRFIDReader.ReaderType.CHAINWAY_C72;
        if (model.contains("C61")) return MiraIRFIDReader.ReaderType.CHAINWAY_C61;
        if (model.contains("MC33") || model.contains("MC32")) 
            return MiraIRFIDReader.ReaderType.ZEBRA_MC3300;
        if (model.contains("EDA5") || model.contains("CT40")) 
            return MiraIRFIDReader.ReaderType.HONEYWELL_EDA50;
        if (model.contains("RP90")) return MiraIRFIDReader.ReaderType.UNITECH_RP902;
        
        return MiraIRFIDReader.ReaderType.SIMULATOR;
    }
    
    public boolean saveDevice(RFIDDevice device) {
        try {
            List<RFIDDevice> devices = getAllDevices();
            
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
            
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 
                Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_DEVICES, arr.toString()).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveDevices error: " + e.getMessage());
            return false;
        }
    }
    
    public List<RFIDDevice> getAllDevices() {
        List<RFIDDevice> devices = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 
                Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_DEVICES, "[]");
            JSONArray arr = new JSONArray(json);
            
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                RFIDDevice device = new RFIDDevice();
                
                String typeName = obj.optString("type", "");
                if (!typeName.isEmpty()) {
                    try {
                        device.setType(MiraIRFIDReader.ReaderType.valueOf(typeName));
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
    
    public void deleteDevice(RFIDDevice device) {
        List<RFIDDevice> devices = getAllDevices();
        devices.removeIf(d -> d == device || d.toString().equals(device.toString()));
        saveDevices(devices);
    }
    
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
    
    public RFIDDevice getActiveDevice() {
        if (activeDevice == null) {
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
     * ✅ الحصول على القارئ النشط (بدون تحول صامت إلى Simulator)
     */
    public MiraIRFIDReader getActiveReader() {
        if (activeReader != null && activeReader.isConnected()) {
            return activeReader;
        }
        
        // ✅ تحقق من الحالة الديناميكية
        if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
            Log.w(TAG, "⚠️ RFID device not present — falling back to simulator");
            if (activeReader == null) {
                activeReader = RFIDReaderFactory.create(
                    MiraIRFIDReader.ReaderType.SIMULATOR, "");
            }
            return activeReader;
        }
        
        // ✅ جهاز موجود — أنشئ القارئ الصحيح
        RFIDDevice device = getActiveDevice();
        if (device == null || device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR) {
            Log.w(TAG, "⚠️ No real device configured — using simulator");
            if (activeReader == null) {
                activeReader = RFIDReaderFactory.create(
                    MiraIRFIDReader.ReaderType.SIMULATOR, "");
            }
            return activeReader;
        }
        
        // ✅ إنشاء القارئ الحقيقي
        if (activeReader == null) {
            activeReader = RFIDReaderFactory.create(device.getType(), device.getAddress());
        }
        
        return activeReader;
    }
    
    public void setListener(MiraIRFIDReader.OnTagReadListener listener) {
        this.listener = listener;
        if (activeReader != null) {
            activeReader.setOnTagReadListener(listener);
        }
    }
    
    /**
     * ✅ بدء المسح - يتطلب جهاز حقيقي
     */
    public boolean startScan() {
        Log.i(TAG, "🚀 RFIDManager.startScan() called");
        
        if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
            Log.e(TAG, "❌ Cannot start scan — RFID device not present");
            return false;
        }
        
        MiraIRFIDReader reader = getActiveReader();
        if (reader == null) {
            Log.e(TAG, "❌ getActiveReader() returned null");
            return false;
        }
        
        if (!reader.isConnected()) {
            Log.d(TAG, "Reader not connected — connecting...");
            if (!reader.initialize()) {
                Log.e(TAG, "❌ initialize() failed");
                return false;
            }
            if (!reader.connect()) {
                Log.e(TAG, "❌ connect() failed");
                return false;
            }
        }
        
        boolean result = reader.startScan();
        Log.i(TAG, "startScan result: " + result);
        return result;
    }
    
    public void stopScan() {
        if (activeReader != null) {
            activeReader.stopScan();
        }
    }
    
    public void release() {
        unregisterTriggerKeyReceiver();
        if (activeReader != null) {
            try {
                activeReader.stopScan();
                activeReader.disconnect();
                activeReader.release();
            } catch (Exception e) {
                Log.e(TAG, "release error: " + e.getMessage());
            }
            activeReader = null;
        }
        activeDevice = null;
        instance = null;
    }
    
    public boolean testConnection(RFIDDevice device) {
        MiraIRFIDReader reader = RFIDReaderFactory.create(device.getType(), 
            device.getAddress());
        boolean initSuccess = reader.initialize();
        if (!initSuccess) return false;
        
        boolean connectSuccess = reader.connect();
        reader.release();
        return connectSuccess;
    }
}
