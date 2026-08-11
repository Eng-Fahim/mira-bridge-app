package com.example.uhf.tools;

import android.os.Build;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;

/**
 * نظام ذكي لاكتشاف وتكوين أجهزة Chainway RFID
 */
public class DeviceDetector {

    private static final String TAG = "MIRA.DeviceDetector";
    
    // ==================== حالة والجهاز ====================
    
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
    
    private static DeviceModel cachedModel = null;
    private static ProfileStatus profileStatus = ProfileStatus.UNKNOWN;
    private static DeviceProfile activeProfile = null;
    private static String lastError = null;
    private static long detectionTime = 0;

    // ==================== Device Profile Class ====================

    public static class DeviceProfile {
        public DeviceModel model = DeviceModel.UNKNOWN;
        public String modelName = "UNKNOWN";
        public boolean isConfigured = false;
        public int defaultPower = 30;

        public DeviceProfile(DeviceModel model, String modelName) {
            this.model = model;
            this.modelName = modelName;
        }
    }

    // ==================== Check & Get Methods ====================

    /**
     * التحقق الشامل هل الجهاز هو Chainway C72 سواء من الاسم، المصنّع، أو الـ Model
     */
    public static boolean isChainwayC72() {
        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "";
        
        return model.contains("C72") || model.contains("CHAINWAY") || manufacturer.contains("CHAINWAY") || getDeviceModel() == DeviceModel.C72;
    }

    public static boolean isC5() { return getDeviceModel() == DeviceModel.C5; }
    public static boolean isC72() { return isChainwayC72(); }

    public static DeviceModel getDeviceModel() {
        if (cachedModel != null) {
            return cachedModel;
        }

        String model = Build.MODEL != null ? Build.MODEL.toUpperCase() : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "";

        if (model.contains("C72") || model.contains("CHAINWAY") || manufacturer.contains("CHAINWAY")) {
            cachedModel = DeviceModel.C72;
        } else if (model.contains("C5")) {
            cachedModel = DeviceModel.C5;
        } else {
            cachedModel = DeviceModel.UNKNOWN;
        }

        return cachedModel;
    }

    public static String getDeviceModelName() {
        DeviceModel model = getDeviceModel();
        switch (model) {
            case C72:
                return "Chainway C72";
            case C5:
                return "Chainway C5";
            default:
                return "Standard/Unknown Device (" + Build.MODEL + ")";
        }
    }

    // ==================== Configuration Method ====================

    public static DeviceProfile configure(RFIDWithUHFUART rfidReader) {
        DeviceModel model = getDeviceModel();
        activeProfile = new DeviceProfile(model, getDeviceModelName());

        try {
            if (rfidReader != null) {
                // ضبط الإعدادات الافتراضية للـ UHF Reader
                rfidReader.setPower(30);
                activeProfile.isConfigured = true;
                profileStatus = ProfileStatus.READY;
                Log.d(TAG, "Successfully configured RFID Reader for: " + activeProfile.modelName);
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            profileStatus = ProfileStatus.FAILED;
            Log.e(TAG, "Failed to configure device: " + e.getMessage(), e);
        }

        return activeProfile;
    }

    public static DeviceProfile getActiveProfile() {
        return activeProfile;
    }

    public static ProfileStatus getProfileStatus() {
        return profileStatus;
    }

    public static String getLastError() {
        return lastError;
    }
}
