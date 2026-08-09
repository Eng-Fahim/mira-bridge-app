package com.example.uhf.tools;

import android.os.Build;

/**
 * كاشف تلقائي لطراز جهاز Chainway
 * يدعم C5 و C72 مع إعدادات مخصصة لكل جهاز
 */
public class DeviceDetector {
    
    private static final String TAG = "DeviceDetector";
    
    public enum DeviceModel {
        C5, C72, UNKNOWN
    }
    
    private static DeviceModel cachedModel = null;
    
    public static String getDeviceModelName() {
        return Build.MODEL;
    }
    
    public static DeviceModel getDeviceModel() {
        if (cachedModel != null) return cachedModel;
        
        String model = getDeviceModelName().toLowerCase().replace(" ", "");
        
        if (model.contains("c5")) {
            cachedModel = DeviceModel.C5;
        } else if (model.contains("c72")) {
            cachedModel = DeviceModel.C72;
        } else {
            cachedModel = DeviceModel.UNKNOWN;
        }
        
        return cachedModel;
    }
    
    public static boolean isC5() {
        return getDeviceModel() == DeviceModel.C5;
    }
    
    public static boolean isC72() {
        return getDeviceModel() == DeviceModel.C72;
    }
    
    // ==================== إعدادات الطاقة ====================
    
    public static int getDefaultPower() {
        switch (getDeviceModel()) {
            case C5:  return 26; // C5: 5-30 dBm, القيمة المثلى 26
            case C72: return 30; // C72: 5-33 dBm, القيمة الافتراضية 30
            default:  return 26; // آمنة للجميع
        }
    }
    
    public static int getMinPower() {
        switch (getDeviceModel()) {
            case C5:  return 5;
            case C72: return 5;
            default:  return 5;
        }
    }
    
    public static int getMaxPower() {
        switch (getDeviceModel()) {
            case C5:  return 30;
            case C72: return 33;
            default:  return 30;
        }
    }
    
    // ==================== إعدادات التردد ====================
    
    public static int getDefaultFrequencyMode() {
        switch (getDeviceModel()) {
            case C5:  return 2; // ETSI أوروبي
            case C72: return 1; // FCC أمريكي
            default:  return 1;
        }
    }
    
    public static String getFrequencyRange() {
        switch (getDeviceModel()) {
            case C5:  return "865-868 MHz (ETSI)";
            case C72: return "902-928 MHz (FCC)";
            default:  return "902-928 MHz";
        }
    }
    
    // ==================== إعدادات المسح ====================
    
    public static int getInventoryInterval() {
        switch (getDeviceModel()) {
            case C5:  return 50;  // مللي ثانية
            case C72: return 100;
            default:  return 100;
        }
    }
    
    // ==================== KeyCodes ====================
    
    /**
     * أزرار المسح الفعلية للجهاز
     */
    public static int[] getScanKeyCodes() {
        switch (getDeviceModel()) {
            case C5:
                return new int[]{139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596};
            case C72:
                return new int[]{139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596};
            default:
                return new int[]{139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596};
        }
    }
    
    public static boolean isScanKey(int keyCode) {
        for (int code : getScanKeyCodes()) {
            if (code == keyCode) return true;
        }
        return false;
    }
    
    // ==================== معلومات الجهاز ====================
    
    public static String getDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append(getDeviceModelName()).append("\n");
        sb.append("Detected: ").append(getDeviceModel().name()).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Default Power: ").append(getDefaultPower()).append(" dBm\n");
        sb.append("Frequency: ").append(getFrequencyRange()).append("\n");
        return sb.toString();
    }
}
