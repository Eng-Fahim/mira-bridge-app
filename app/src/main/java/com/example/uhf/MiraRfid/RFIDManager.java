package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.MiraRfid.model.RFIDDevice;
import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.activity.BaseTabFragmentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ RFIDManager v3.1 — النسخة النهائية النظيفة
 * 
 * التغييرات في v3.1:
 * - ❌ حذف TriggerKeyReceiver بالكامل (تعارض الزر)
 * - ❌ حذف الحقول: keyReceiver، isReceiverRegistered
 * - ❌ حذف الدوال: registerTriggerKeyReceiver()، unregisterTriggerKeyReceiver()
 * - ✅ getActiveReader() لا يُرجع Simulator بصمت
 * - ✅ getActiveDevice() لا يُنشئ Simulator بصمت
 * - ✅ getPower() يُرجع 30 dBm افتراضياً
 * - ✅ autoConnectAndBind() يعيد boolean
 */
public class RFIDManager {
    
    private static final String TAG = "RFIDManager";
    private static final String PREFS_NAME = "mira_rfid_devices";
    private static final String KEY_DEVICES = "saved_devices";
    
    // ═══════════════════════════════════════════════════════════════
    //  ✅ القيم الافتراضية الموحّدة (مطابقة لـ ChainwayReader)
    // ═══════════════════════════════════════════════════════════════
    private static final int DEFAULT_POWER_DBM = 30;
    private static final int DEFAULT_RSSI_THRESHOLD = -75;
    private static final int DEFAULT_SCAN_INTERVAL_MS = 100;
    
    private static RFIDManager instance;
    private final Context context;
    private MiraIRFIDReader activeReader;
    private RFIDDevice activeDevice;
    private MiraIRFIDReader.OnTagReadListener listener;
    
    // ❌ لا BroadcastReceiver هنا — الزر يُلتقط في BaseTabFragmentActivity
    
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

        // ✅ 1. احصل على الجهاز النشط أولاً
        RFIDDevice device = getOrCreateActiveDevice();
        if (device == null) {
            Log.w(TAG, "⚠️ No active device configured");
            return false;
        }

        boolean isExternalReader =
                (device.getType() == MiraIRFIDReader.ReaderType.VANCH_FIXED
              || device.getType() == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH);

        // ✅ 2. فحص دعم العتاد المدمج (فقط للقارئات المدمجة)
        if (!isExternalReader) {
            if (!com.example.uhf.tools.DeviceDetector.isRfidHardwareSupported()) {
                Log.w(TAG, "⚠️ RFID hardware NOT supported — skipping auto-connect");
                return false;
            }
            if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
                Log.w(TAG, "⚠️ RFID device not present yet");
                return false;
            }
        }

        // ✅ 3. القارئ المشترك مطلوب فقط للقارئات المدمجة
        if (!isExternalReader) {
            com.rscja.deviceapi.RFIDWithUHFUART sharedReader =
                    BaseTabFragmentActivity.getSharedReader();
            if (sharedReader == null) {
                Log.w(TAG, "⚠️ Shared reader is null");
                return false;
            }
        }

        Log.i(TAG, "✅ Device type: " + device.getType()
                + " | external=" + isExternalReader);

        // 4. إذا كان SIMULATOR — لا تتصل
        if (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR) {
            Log.w(TAG, "⚠️ Active device is SIMULATOR — skipping real connect");
            return false;
        }

        // 5. نظّف القارئ القديم إذا كان من نوع مختلف
        if (activeReader != null && !isExternalReader
                && !(activeReader instanceof ChainwayReader)) {
            try {
                activeReader.stopScan();
                activeReader.disconnect();
                activeReader.release();
            } catch (Exception ignored) {}
            activeReader = null;
        }

        // 6. أنشئ القارئ إذا لزم
        if (activeReader == null) {
            activeReader = RFIDReaderFactory.create(
                    device.getType(), device.getAddress());
            if (activeReader == null) {
                Log.e(TAG, "❌ Failed to create reader: " + device.getType());
                return false;
            }
        }

        // 7. ربط المستمع
        if (listener != null) {
            activeReader.setOnTagReadListener(listener);
        }

        // 8. تهيئة + اتصال
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

        Log.i(TAG, "✅ autoConnectAndBind SUCCESS: " + device.getType());
        return true;

    } catch (Exception e) {
        Log.e(TAG, "❌ autoConnectAndBind exception", e);
        return false;
    }
}
    
/**
 * ✅ كشف نوع الجهاز الفعلي — صارم ودقيق
 * 
 * على Samsung/هواتف عادية → SIMULATOR (لا يدّعي وجود C72)
 * على C72 حقيقي → CHAINWAY_C72
 */
public MiraIRFIDReader.ReaderType detectDeviceType() {
    String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
    String manufacturer = Build.MANUFACTURER != null ? 
        Build.MANUFACTURER.toUpperCase() : "";
    String brand = Build.BRAND != null ? Build.BRAND.toUpperCase() : "";
    
    Log.d(TAG, "🔍 Detecting device: model=" + model 
            + " manufacturer=" + manufacturer + " brand=" + brand);
    
    // ✅ Chainway — اكتشاف صارم من MODEL
    if (model.contains("C72")) return MiraIRFIDReader.ReaderType.CHAINWAY_C72;
    if (model.contains("C61")) return MiraIRFIDReader.ReaderType.CHAINWAY_C61;
    if (model.contains("C66")) return MiraIRFIDReader.ReaderType.CHAINWAY_C66;
    if (model.contains("C71")) return MiraIRFIDReader.ReaderType.CHAINWAY_C71;
    
    // ✅ Zebra
    if (model.contains("MC33") || model.contains("MC32") 
            || model.contains("TC51") || model.contains("TC52")) 
        return MiraIRFIDReader.ReaderType.ZEBRA_MC3300;
    
    // ✅ Honeywell
    if (model.contains("EDA5") || model.contains("CT40") 
            || model.contains("CT60")) 
        return MiraIRFIDReader.ReaderType.HONEYWELL_EDA50;
    
    // ✅ Unitech
    if (model.contains("RP90") || model.contains("PA70")) 
        return MiraIRFIDReader.ReaderType.UNITECH_RP902;
    
    // ✅ Chainway بالمصنّع فقط (احتياط)
    if (manufacturer.contains("CHAINWAY") || brand.contains("CHAINWAY")) {
        return MiraIRFIDReader.ReaderType.CHAINWAY_C72;
    }
    
    // ❌ جهاز غير معروف — لا ندّعي أنه C72
    Log.w(TAG, "⚠️ Unknown device: " + model + " → SIMULATOR");
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
                // ✅ القيم الافتراضية الموحّدة (30 dBm)
                device.setPowerDbm(obj.optInt("power", DEFAULT_POWER_DBM));
                device.setRssiThreshold(obj.optInt("rssi", DEFAULT_RSSI_THRESHOLD));
                device.setScanIntervalMs(obj.optInt("interval", DEFAULT_SCAN_INTERVAL_MS));
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
    
    /**
     * ✅ getActiveDevice — بدون إنشاء صامت
     */
    public RFIDDevice getActiveDevice() {
        return getOrCreateActiveDevice();
    }
    
    /**
     * ✅ getOrCreateActiveDevice — النسخة الآمنة
     * - يبحث عن جهاز محفوظ أولاً
     * - إذا لم يوجد، يكتشف النوع الفعلي من Build.MODEL
     * - لا يُنشئ SIMULATOR بصمت (يستخدم C72 كافتراضي على Chainway)
     */
    private RFIDDevice getOrCreateActiveDevice() {
    if (activeDevice != null) return activeDevice;

    // 1) ابحث عن جهاز محفوظ
    List<RFIDDevice> devices = getAllDevices();
    for (RFIDDevice d : devices) {
        if (d.isDefault()) {
            activeDevice = d;
            Log.i(TAG, "✅ Using saved default device: " + d.getType());
            return activeDevice;
        }
    }

    // 2) لا يوجد جهاز محفوظ — اكتشف النوع الفعلي
    MiraIRFIDReader.ReaderType detected = detectDeviceType();

    // ✅ [جديد] VANCH و Bluetooth لا يحتاجان كشفاً — يُستخدمان فقط عند الحفظ
    if (detected == MiraIRFIDReader.ReaderType.SIMULATOR) {
        Log.e(TAG, "❌ Cannot detect real device type — " +
                "not creating a silent SIMULATOR");
        return null;
    }

    activeDevice = new RFIDDevice(detected, "");
    activeDevice.setDefault(true);
    activeDevice.setPowerDbm(DEFAULT_POWER_DBM);
    activeDevice.setRssiThreshold(DEFAULT_RSSI_THRESHOLD);
    activeDevice.setScanIntervalMs(DEFAULT_SCAN_INTERVAL_MS);

    saveDevice(activeDevice);

    Log.i(TAG, "✅ Created default device: " + detected);
    return activeDevice;
}
    
    /**
     * ✅ getActiveReader — لا يُرجع Simulator بصمت
     * 
     * @return القارئ النشط أو null إذا لم يكن متاحاً
     */
    public MiraIRFIDReader getActiveReader() {
    // 1) إذا كان القارئ النشط متصلاً بالفعل — أعده
    if (activeReader != null && activeReader.isConnected()) {
        return activeReader;
    }

    // 2) إذا كان الجهاز النشط من نوع لا يحتاج عتاداً مدمجاً (VANCH/Bluetooth)
    RFIDDevice device = getOrCreateActiveDevice();
    boolean isExternalReader = (device != null) &&
            (device.getType() == MiraIRFIDReader.ReaderType.VANCH_FIXED
          || device.getType() == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH);

    // 3) إذا لم يوجد عتاد مدمج AND ليس قارئاً خارجياً → null
    if (!BaseTabFragmentActivity.isRfidDevicePresent() && !isExternalReader) {
        Log.w(TAG, "⚠️ RFID device not present — returning null");
        return null;
    }

    // 4) لا يوجد جهاز نشط
    if (device == null) {
        Log.w(TAG, "⚠️ No active device — returning null");
        return null;
    }

    // 5) SIMULATOR لا يُستخدم في الوضع الحقيقي
    if (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR
            && !com.example.uhf.BuildConfig.ALLOW_SIMULATOR) {
        Log.w(TAG, "⚠️ Active device is SIMULATOR — returning null");
        return null;
    }

    // 6) إنشاء القارئ الحقيقي (VANCH أو غيره)
    if (activeReader == null) {
        activeReader = RFIDReaderFactory.create(
                device.getType(), device.getAddress());
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
     * ✅ بدء المسح - يتطلب جهازاً حقيقياً
     */
    public boolean startScan() {
    Log.i(TAG, "🚀 RFIDManager.startScan() called");

    // ✅ اسمح بالقارئات الخارجية (VANCH)
    RFIDDevice device = getOrCreateActiveDevice();
    boolean isExternalReader = (device != null) &&
            (device.getType() == MiraIRFIDReader.ReaderType.VANCH_FIXED
          || device.getType() == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH);

    if (!BaseTabFragmentActivity.isRfidDevicePresent() && !isExternalReader) {
        Log.e(TAG, "❌ Cannot start scan — RFID device not present");
        return false;
    }

    MiraIRFIDReader reader = getActiveReader();
    if (reader == null) {
        Log.e(TAG, "❌ getActiveReader() returned null — cannot scan");
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
    
    /**
     * ✅ release — بدون unregisterTriggerKeyReceiver (محذوفة)
     */
    public void release() {
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
        listener = null;
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

    // ==================== ⚡ Power Control ====================

    /**
     * ✅ ضبط قدرة الهوائي
     */
    public boolean setPower(int dbm) {
        try {
            if (dbm < 5 || dbm > 30) {
                Log.w(TAG, "⚠️ Power out of range: " + dbm + " (allowed: 5-30)");
                return false;
            }

            MiraIRFIDReader reader = getActiveReader();
            if (reader == null) {
                Log.e(TAG, "❌ setPower: no active reader");
                return false;
            }

            if (!reader.isConnected()) {
                Log.e(TAG, "❌ setPower: reader not connected");
                return false;
            }

            reader.setPower(dbm);
            Log.i(TAG, "✅ Power set to " + dbm + " dBm");

            if (activeDevice != null) {
                activeDevice.setPowerDbm(dbm);
                saveDevice(activeDevice);
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ setPower exception: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * ✅ الحصول على قدرة الهوائي الحالية
     */
    public int getPower() {
        try {
            if (activeDevice != null) {
                return activeDevice.getPowerDbm();
            }
            return DEFAULT_POWER_DBM;  // ✅ 30 dBm (كان 26)
        } catch (Exception e) {
            Log.e(TAG, "getPower error: " + e.getMessage());
            return DEFAULT_POWER_DBM;
        }
    }

    /**
 * ✅ فحص جهوزية القارئ (يدعم القارئات الخارجية VANCH/Bluetooth)
 */
public boolean isRealReaderConnected() {
    try {
        RFIDDevice device = getOrCreateActiveDevice();
        if (device == null) return false;
        if (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR) {
            return false;
        }

        // ✅ القارئات الخارجية لا تحتاج عتاداً مدمجاً
        boolean isExternalReader =
                (device.getType() == MiraIRFIDReader.ReaderType.VANCH_FIXED
              || device.getType() == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH);

        // ✅ للقارئات المدمجة: يجب وجود العتاد
        if (!isExternalReader && !BaseTabFragmentActivity.isRfidDevicePresent()) {
            return false;
        }

        MiraIRFIDReader reader = getActiveReader();
        if (reader == null) return false;

        return reader.isConnected();

    } catch (Exception e) {
        Log.e(TAG, "isRealReaderConnected error: " + e.getMessage());
        return false;
    }
}

    /**
     * ✅ انتظار جهوزية القارئ (مع timeout)
     */
    public boolean waitForReaderReady(long timeoutMs) {
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                if (isRealReaderConnected()) {
                    Log.d(TAG, "✅ Reader ready after " +
                            (System.currentTimeMillis() - start) + "ms");
                    return true;
                }
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        Log.w(TAG, "⚠️ Reader not ready after " + timeoutMs + "ms");
        return false;
    }
}
