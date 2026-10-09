package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.example.uhf.MiraRfid.model.RFIDDevice;
import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.example.uhf.activity.BaseTabFragmentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import com.rscja.deviceapi.RFIDWithUHFUART;

/**
 * ✅ RFIDManager v3.2 — إضافة دعم VH-C83
 * 
 * التغييرات في v3.2:
 * - ✅ إضافة اكتشاف VH-C83 (يستخدم نفس SDK Chainway)
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
     * على VH-C83 → CHAINWAY_C72 (نفس SDK)
     */
    public MiraIRFIDReader.ReaderType detectDeviceType() {
        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        String manufacturer = Build.MANUFACTURER != null ? 
            Build.MANUFACTURER.toUpperCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toUpperCase() : "";
        String device = Build.DEVICE != null ? Build.DEVICE.toUpperCase() : "";
        
        Log.d(TAG, "🔍 Detecting device: model=" + model 
                + " manufacturer=" + manufacturer 
                + " brand=" + brand
                + " device=" + device);
        
        // ✅ Chainway — اكتشاف صارم من MODEL
        if (model.contains("C72")) return MiraIRFIDReader.ReaderType.CHAINWAY_C72;
        if (model.contains("C61")) return MiraIRFIDReader.ReaderType.CHAINWAY_C61;
        if (model.contains("C66")) return MiraIRFIDReader.ReaderType.CHAINWAY_C66;
        if (model.contains("C71")) return MiraIRFIDReader.ReaderType.CHAINWAY_C71;
        
        // ✅ [جديد] VH-C83 — يستخدم نفس SDK Chainway
        if (model.contains("VH-C83") || model.contains("C83")
                || device.contains("C83")) {
            Log.i(TAG, "✅ VH-C83 detected — using Chainway SDK (same family)");
            return MiraIRFIDReader.ReaderType.CHAINWAY_C72;
        }
        
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
        
        // ✅ Vanch/Vanquan بالمصنّع
        if (manufacturer.contains("VANCH") || brand.contains("VANCH")
                || manufacturer.contains("VANQUAN") || brand.contains("VANQUAN")) {
            Log.i(TAG, "⚠️ Vanch device detected — returning CHAINWAY_C72 for compatibility");
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
     * ✅ getOrCreateActiveDevice — النسخة الآمنة (v2)
     */
    private RFIDDevice getOrCreateActiveDevice() {
        if (activeDevice != null) return activeDevice;

        // ═══════════════════════════════════════════════════════
        // 1) اكتشف العتاد الحقيقي أولاً
        // ═══════════════════════════════════════════════════════
        MiraIRFIDReader.ReaderType detected = detectDeviceType();
        boolean hasRealHardware = (detected != MiraIRFIDReader.ReaderType.SIMULATOR);

        if (hasRealHardware) {
            Log.i(TAG, "✅ Real hardware detected: " + detected);

            List<RFIDDevice> devices = getAllDevices();
            for (RFIDDevice d : devices) {
                if (d.getType() == detected) {
                    activeDevice = d;
                    Log.i(TAG, "✅ Using saved matching device: " + detected);
                    return activeDevice;
                }
            }

            activeDevice = new RFIDDevice(detected, "");
            activeDevice.setDefault(true);
            activeDevice.setPowerDbm(DEFAULT_POWER_DBM);
            activeDevice.setRssiThreshold(DEFAULT_RSSI_THRESHOLD);
            activeDevice.setScanIntervalMs(DEFAULT_SCAN_INTERVAL_MS);
            saveDevice(activeDevice);
            Log.i(TAG, "✅ Auto-created real device: " + detected);
            return activeDevice;
        }

        // ═══════════════════════════════════════════════════════
        // 2) لا يوجد عتاد حقيقي — ابحث عن جهاز محفوظ افتراضي
        // ═══════════════════════════════════════════════════════
        List<RFIDDevice> devices = getAllDevices();
        for (RFIDDevice d : devices) {
            if (!d.isDefault()) continue;
            if (d.getType() == MiraIRFIDReader.ReaderType.SIMULATOR) continue;

            boolean canWork = canDeviceTypeWorkOnThisHardware(d.getType());
            if (canWork) {
                activeDevice = d;
                Log.i(TAG, "✅ Using saved external device: " + d.getType());
                return activeDevice;
            } else {
                Log.w(TAG, "⚠️ Saved device " + d.getType() 
                        + " cannot work on this hardware — skipping");
            }
        }

        // ═══════════════════════════════════════════════════════
        // 3) لا عتاد حقيقي + لا جهاز محفوظ: SIMULATOR (ذاكرة فقط)
        // ═══════════════════════════════════════════════════════
        if (com.example.uhf.BuildConfig.ALLOW_SIMULATOR) {
            Log.w(TAG, "🧪 No real hardware — using in-memory SIMULATOR");

            activeDevice = new RFIDDevice(
                    MiraIRFIDReader.ReaderType.SIMULATOR, "");
            activeDevice.setDefault(true);
            activeDevice.setPowerDbm(DEFAULT_POWER_DBM);
            activeDevice.setRssiThreshold(DEFAULT_RSSI_THRESHOLD);
            activeDevice.setScanIntervalMs(DEFAULT_SCAN_INTERVAL_MS);

            // ⚠️ لا saveDevice() — SIMULATOR لا يُحفَظ
            return activeDevice;
        }

        Log.e(TAG, "❌ No reader available");
        return null;
    }

    /**
     * ✅ هل يمكن لنوع الجهاز المحفوظ أن يعمل على هذا العتاد؟
     */
    private boolean canDeviceTypeWorkOnThisHardware(MiraIRFIDReader.ReaderType type) {
        if (type == null) return false;

        if (type == MiraIRFIDReader.ReaderType.VANCH_FIXED
                || type == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH
                || type == MiraIRFIDReader.ReaderType.SIMULATOR) {
            return true;
        }

        return BaseTabFragmentActivity.isRfidDevicePresent();
    }
    
    /**
     * ✅ getActiveReader — لا يُرجع Simulator بصمت
     */
    public MiraIRFIDReader getActiveReader() {
        if (activeReader != null && activeReader.isConnected()) {
            return activeReader;
        }

        RFIDDevice device = getOrCreateActiveDevice();

        boolean isExternalReader = (device != null) &&
                (device.getType() == MiraIRFIDReader.ReaderType.VANCH_FIXED
              || device.getType() == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH
              || (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR
                  && com.example.uhf.BuildConfig.ALLOW_SIMULATOR));

        if (!BaseTabFragmentActivity.isRfidDevicePresent() && !isExternalReader) {
            Log.w(TAG, "⚠️ RFID device not present — returning null");
            return null;
        }

        if (device == null) {
            Log.w(TAG, "⚠️ No active device — returning null");
            return null;
        }

        if (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR
                && !com.example.uhf.BuildConfig.ALLOW_SIMULATOR) {
            Log.w(TAG, "⚠️ Active device is SIMULATOR — returning null");
            return null;
        }

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

        RFIDDevice device = getOrCreateActiveDevice();

        boolean isExternalReader = (device != null) &&
                (device.getType() == MiraIRFIDReader.ReaderType.VANCH_FIXED
              || device.getType() == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH
              || (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR
                  && com.example.uhf.BuildConfig.ALLOW_SIMULATOR));

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
     * ✅ release
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

    public boolean setPower(int dbm) {
    try {
        if (dbm < 5 || dbm > 30) {
            Log.w(TAG, "⚠️ Power out of range: " + dbm);
            return false;
        }

        // ✅ احفظ في المصدر الموحد أولاً
        MiraReaderSettings.getInstance(context).setPowerDbm(dbm);

        MiraIRFIDReader reader = getActiveReader();
        if (reader != null && reader.isConnected()) {
            reader.setPower(dbm);
            Log.i(TAG, "✅ Power set to " + dbm + " dBm (saved to MiraReaderSettings)");
        } else {
            Log.i(TAG, "ℹ️ Power saved to MiraReaderSettings — will apply on connect");
        }

        if (activeDevice != null) {
            activeDevice.setPowerDbm(dbm);
            saveDevice(activeDevice);
        }
        return true;

    } catch (Exception e) {
        Log.e(TAG, "❌ setPower exception", e);
        return false;
    }
}

/**
 * ✅ يطبّق كل إعدادات MiraReaderSettings على القارئ النشط.
 * يُستدعى تلقائياً من ChainwayReader.startScan() — لا حاجة لاستدعاء يدوي.
 */
public void applyMiraReaderSettings() {
    try {
        MiraIRFIDReader reader = getActiveReader();
        if (reader == null || !reader.isConnected()) {
            Log.d(TAG, "applyMiraReaderSettings: reader not ready");
            return;
        }
        RFIDWithUHFUART sdkReader = BaseTabFragmentActivity.getSharedReader();
        if (sdkReader != null) {
            MiraReaderSettings.getInstance(context).applyToReader(sdkReader);
        }
    } catch (Exception e) {
        Log.e(TAG, "applyMiraReaderSettings error", e);
    }
}

    public int getPower() {
        try {
            if (activeDevice != null) {
                return activeDevice.getPowerDbm();
            }
            return DEFAULT_POWER_DBM;
        } catch (Exception e) {
            Log.e(TAG, "getPower error: " + e.getMessage());
            return DEFAULT_POWER_DBM;
        }
    }

    /**
     * ✅ فحص جهوزية القارئ
     */
    public boolean isRealReaderConnected() {
        try {
            RFIDDevice device = getOrCreateActiveDevice();
            if (device == null) return false;
            if (device.getType() == MiraIRFIDReader.ReaderType.SIMULATOR) {
                return false;
            }

            boolean isExternalReader =
                    (device.getType() == MiraIRFIDReader.ReaderType.VANCH_FIXED
                  || device.getType() == MiraIRFIDReader.ReaderType.GENERIC_BLUETOOTH);

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
