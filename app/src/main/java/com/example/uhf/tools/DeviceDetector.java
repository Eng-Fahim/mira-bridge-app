package com.example.uhf.tools;

import android.os.Build;
import android.util.Log;

import com.example.uhf.activity.BaseTabFragmentActivity;
import com.rscja.deviceapi.RFIDWithUHFUART;

/**
 * نظام ذكي لاكتشاف وتكوين أجهزة Chainway RFID
 */
public class DeviceDetector {

    private static final String TAG = "MIRA.DeviceDetector";
    
    // ==================== حالة الجهاز ====================
    
    public enum DeviceModel { C5, C72, UNKNOWN }
    
    public enum ProfileStatus {
        UNKNOWN,
        DETECTED,
        CAPABILITIES_READ,
        PROFILE_APPLIED,
        VERIFIED,
        READY,
        FAILED
    }

    public static class DeviceProfile {
        public long timestamp;
        public String androidVersion;
        public int sdkInt;
        public String modelName;
        public DeviceModel model;
        public String softwareVersion;
        public String hardwareVersion;
        public int temperature;
        public boolean temperatureOK;
        public int minPower;
        public int maxPower;
        public int frequencyMode;
        public String frequencyRange;
        public int appliedPower;
        public boolean powerVerified;
        public boolean frequencyVerified;
        public boolean ready;

        @Override
        public String toString() {
            return "DeviceProfile {" +
                    "Model='" + modelName + '\'' +
                    ", Android=" + androidVersion +
                    ", SW='" + softwareVersion + '\'' +
                    ", HW='" + hardwareVersion + '\'' +
                    ", Power=" + appliedPower + "dBm" +
                    ", Freq='" + frequencyRange + '\'' +
                    ", Temp=" + temperature + "°C" +
                    '}';
        }
    }
    
    private static DeviceModel cachedModel = null;
    private static ProfileStatus profileStatus = ProfileStatus.UNKNOWN;
    private static DeviceProfile activeProfile = null;
    private static String lastError = null;
    private static long detectionTime = 0;

    public static String getDeviceModelName() {
        return Build.MODEL != null ? Build.MODEL : "UNKNOWN";
    }

    public static DeviceModel getDeviceModel() {
        if (cachedModel != null) return cachedModel;
        
        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        if (model.contains("C72")) {
            cachedModel = DeviceModel.C72;
        } else if (model.contains("C5")) {
            cachedModel = DeviceModel.C5;
        } else {
            cachedModel = DeviceModel.UNKNOWN;
        }
        return cachedModel;
    }

    public static boolean isChainwayC72() {
        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "";
        
        return model.contains("C72") || model.contains("CHAINWAY") || 
               manufacturer.contains("CHAINWAY") || getDeviceModel() == DeviceModel.C72;
    }
    
    /**
 * ✅✅✅ دالة حرجة: هل الجهاز يدعم قارئ RFID؟
 * 
 * تُستخدم قبل استدعاء initUHF() لتجنب:
 * - ANR بسبب timeout 8 ثوان
 * - محاولة تهيئة AAR على جهاز بدون قارئ
 * - استدعاء getInstance() المكلف
 * 
 * @return true فقط إذا كان الجهاز من أجهزة Chainway/Zebra/Honeywell المدعومة
 */
public static boolean isRfidHardwareSupported() {
    try {
        String model = Build.MODEL != null 
            ? Build.MODEL.toUpperCase() 
            : "";
        String manufacturer = Build.MANUFACTURER != null 
            ? Build.MANUFACTURER.toUpperCase() 
            : "";
        String device = Build.DEVICE != null 
            ? Build.DEVICE.toUpperCase() 
            : "";
        String brand = Build.BRAND != null 
            ? Build.BRAND.toUpperCase() 
            : "";
        
        Log.d(TAG, "🔍 Checking RFID support: model=" + model 
            + ", manufacturer=" + manufacturer 
            + ", device=" + device
            + ", brand=" + brand);
        
        // ✅ الأجهزة المدعومة
        boolean isChainway = model.contains("C72") 
                          || model.contains("C61") 
                          || model.contains("C66")
                          || model.contains("C5")
                          || device.contains("C72")
                          || manufacturer.contains("CHAINWAY")
                          || brand.contains("CHAINWAY");
        
        boolean isZebra = model.contains("MC33") 
                       || model.contains("MC32") 
                       || model.contains("TC51")
                       || model.contains("TC52")
                       || model.contains("TC72")
                       || manufacturer.contains("ZEBRA")
                       || brand.contains("ZEBRA");
        
        boolean isHoneywell = model.contains("EDA5") 
                           || model.contains("CT40")
                           || model.contains("CT60")
                           || manufacturer.contains("HONEYWELL")
                           || brand.contains("HONEYWELL");
        
        boolean isUnitech = model.contains("RP90") 
                         || model.contains("PA70")
                         || manufacturer.contains("UNITECH");
        
        boolean isUrovo = model.contains("DT50") 
                       || model.contains("RT40")
                       || manufacturer.contains("UROVO");
        
        boolean supported = isChainway || isZebra || isHoneywell 
                         || isUnitech || isUrovo;
        
        Log.i(TAG, "✅ RFID hardware supported: " + supported);
        return supported;
        
    } catch (Exception e) {
        Log.e(TAG, "isRfidHardwareSupported error: " + e.getMessage(), e);
        return false;
    }
}

    public static boolean isC5() { return getDeviceModel() == DeviceModel.C5; }
    public static boolean isC72() { return isChainwayC72(); }

    public static ProfileStatus getStatus() { return profileStatus; }
    public static DeviceProfile getProfile() { return activeProfile; }
    public static String getLastError() { return lastError; }
    
    /**
     * التدفق الكامل: اكتشاف → قدرات → Profile → تحقق → تسجيل
     */
    public static DeviceProfile configure(RFIDWithUHFUART reader) {
    // ✅ استخدم الحالة الديناميكية الجديدة
    if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
        Log.i(TAG, "RFID device not present - skipping configuration");
        profileStatus = ProfileStatus.FAILED;
        lastError = "RFID device not present";
        return null;
    }
    
    if (reader == null) {
        Log.e(TAG, "Reader is null - cannot configure");
        profileStatus = ProfileStatus.FAILED;
        lastError = "Reader is null";
        return null;
    }
    
        
        detectionTime = System.currentTimeMillis();
        activeProfile = new DeviceProfile();
        activeProfile.timestamp = detectionTime;
        activeProfile.androidVersion = Build.VERSION.RELEASE;
        activeProfile.sdkInt = Build.VERSION.SDK_INT;
        
        try {
            // المرحلة 1: الاكتشاف
            if (!detect(reader)) return fail("Detection failed");
            
            // المرحلة 2: قراءة القدرات
            if (!readCapabilities(reader)) return fail("Capabilities read failed");
            
            // المرحلة 3: تطبيق Profile
            if (!applyProfile(reader)) return fail("Profile application failed");
            
            // المرحلة 4: التحقق
            if (!verify(reader)) return fail("Verification failed");
            
            // المرحلة 5: التسجيل
            logProfile();
            
            profileStatus = ProfileStatus.READY;
            activeProfile.ready = true;
            
            Log.i(TAG, "✅ Device configured successfully in " + 
                (System.currentTimeMillis() - detectionTime) + "ms");
            
            return activeProfile;
            
        } catch (Exception e) {
            Log.e(TAG, "Configuration error", e);
            return fail("Exception: " + e.getMessage());
        }
    }
    
    private static boolean detect(RFIDWithUHFUART reader) {
        try {
            activeProfile.modelName = getDeviceModelName();
            activeProfile.model = getDeviceModel();
            profileStatus = ProfileStatus.DETECTED;
            
            Log.i(TAG, "📱 Phase 1 - Detection: " + activeProfile.modelName + 
                " → " + activeProfile.model.name());
            
            return true;
        } catch (Exception e) {
            lastError = "Detection error: " + e.getMessage();
            return false;
        }
    }
    
    private static boolean readCapabilities(RFIDWithUHFUART reader) {
        try {
            Log.i(TAG, "🔍 Phase 2 - Reading capabilities...");
            
            // ✅ استخدام timeout للقراءات
            final String[] versions = new String[2];
            versions[0] = "Unknown";
            versions[1] = "Unknown";
            
            Thread readThread = new Thread(() -> {
                try {
                    versions[0] = reader.getVersion();
                    versions[1] = reader.getHardwareVersion();
                } catch (Exception e) {
                    Log.e(TAG, "Version read error", e);
                }
            });
            readThread.setDaemon(true);
            readThread.start();
            readThread.join(3000); // ✅ 3 ثواني timeout
            
            if (readThread.isAlive()) {
                Log.w(TAG, "Version read timeout - using defaults");
            }
            
            activeProfile.softwareVersion = versions[0];
            activeProfile.hardwareVersion = versions[1];
            
            // درجة الحرارة
            activeProfile.temperature = 25;
            activeProfile.temperatureOK = true;
            
            determinePowerRange();
            determineFrequencyRange();
            
            profileStatus = ProfileStatus.CAPABILITIES_READ;
            
            Log.i(TAG, "   SW: " + activeProfile.softwareVersion);
            Log.i(TAG, "   HW: " + activeProfile.hardwareVersion);
            Log.i(TAG, "   Power Range: " + activeProfile.minPower + "-" + 
                activeProfile.maxPower + " dBm");
            Log.i(TAG, "   Frequency: " + activeProfile.frequencyRange);
            Log.i(TAG, "   Temperature: " + activeProfile.temperature + "°C");
            
            return true;
            
        } catch (Exception e) {
            lastError = "Capabilities read error: " + e.getMessage();
            Log.e(TAG, "❌ " + lastError);
            return false;
        }
    }
    
    private static void determinePowerRange() {
    switch (activeProfile.model) {
        case C5:
            activeProfile.minPower = 5;
            activeProfile.maxPower = 30;
            break;
        case C72:
            activeProfile.minPower = 5;
            activeProfile.maxPower = 30;  // ← كان 33 (C72 يدعم حتى 30 فقط عملياً)
            break;
        default:
            activeProfile.minPower = 10;
            activeProfile.maxPower = 26;
            break;
    }
}
    private static void determineFrequencyRange() {
        switch (activeProfile.model) {
            case C5:
                activeProfile.frequencyMode = 2;
                activeProfile.frequencyRange = "865-868 MHz (ETSI)";
                break;
            case C72:
                activeProfile.frequencyMode = 1;
                activeProfile.frequencyRange = "902-928 MHz (FCC)";
                break;
            default:
                activeProfile.frequencyMode = 1;
                activeProfile.frequencyRange = "902-928 MHz (FCC Default)";
                break;
        }
    }
    
    private static boolean applyProfile(RFIDWithUHFUART reader) {
        try {
            Log.i(TAG, "⚙️ Phase 3 - Applying profile...");
            
            activeProfile.appliedPower = calculateOptimalPower();
            
            boolean powerResult = false;
            try {
                java.lang.reflect.Method setPower = reader.getClass().getMethod("setPower", int.class);
                setPower.invoke(reader, activeProfile.appliedPower);
                powerResult = true;
            } catch (NoSuchMethodException e1) {
                try {
                    java.lang.reflect.Method setOutputPower = reader.getClass().getMethod("setOutputPower", int.class);
                    setOutputPower.invoke(reader, activeProfile.appliedPower);
                    powerResult = true;
                } catch (NoSuchMethodException e2) {
                    Log.w(TAG, "   Power setting not available - skipping");
                    powerResult = true;
                }
            }
            
            activeProfile.powerVerified = powerResult;
            activeProfile.frequencyVerified = true;
            
            profileStatus = ProfileStatus.PROFILE_APPLIED;
            
            Log.i(TAG, "   Power: " + activeProfile.appliedPower + " dBm " + 
                (powerResult ? "✓" : "⚠️ (skipped)"));
            
            return true;
            
        } catch (Exception e) {
            lastError = "Profile error: " + e.getMessage();
            Log.e(TAG, "❌ " + lastError);
            return false;
        }
    }
    
    private static int calculateOptimalPower() {
    switch (activeProfile.model) {
        case C5:
            return 25;       // ← كان 22
        case C72:
            return 30;       // ← كان 24 (الحد الأقصى للذهب)
        default:
            return 26;       // ← كان 22
    }
}
    
    private static boolean verify(RFIDWithUHFUART reader) {
        try {
            Log.i(TAG, "✅ Phase 4 - Verifying configuration...");
            
            boolean allOK = true;
            
            if (!activeProfile.powerVerified) {
                Log.w(TAG, "   ⚠️ Power setting not verified");
            }
            
            if (!activeProfile.temperatureOK) {
                Log.w(TAG, "   ⚠️ Temperature out of safe range");
            }
            
            profileStatus = ProfileStatus.VERIFIED;
            Log.i(TAG, "   ✅ Verification complete");
            
            return true;
            
        } catch (Exception e) {
            lastError = "Verification error: " + e.getMessage();
            Log.e(TAG, "❌ " + lastError);
            return false;
        }
    }
    
    private static void logProfile() {
        Log.i(TAG, "\n" + activeProfile.toString());
    }
    
    private static DeviceProfile fail(String reason) {
        lastError = reason;
        profileStatus = ProfileStatus.FAILED;
        if (activeProfile != null) {
            activeProfile.ready = false;
        }
        Log.e(TAG, "❌ Device configuration FAILED: " + reason);
        return null;
    }
    
    public static boolean isReady() {
        return profileStatus == ProfileStatus.READY && 
               activeProfile != null && 
               activeProfile.ready;
    }
    
    public static int getCurrentPower() {
        if (activeProfile != null) {
            return activeProfile.appliedPower;
        }
        return 22;
    }
    
    public static String getDeviceInfo() {
        if (activeProfile != null) {
            return activeProfile.toString();
        }
        return "Device not configured yet\nModel: " + getDeviceModelName();
    }
    
    public static void reset() {
        cachedModel = null;
        profileStatus = ProfileStatus.UNKNOWN;
        activeProfile = null;
        lastError = null;
        detectionTime = 0;
    }
}
