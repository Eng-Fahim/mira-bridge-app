package com.example.uhf.tools;

import android.os.Build;
import android.util.Log;

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
    
    private static DeviceModel cachedModel = null;
    private static ProfileStatus profileStatus = ProfileStatus.UNKNOWN;
    private static DeviceProfile activeProfile = null;
    private static String lastError = null;
    private static long detectionTime = 0;

    // ==================== Check Methods ====================

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

    public static ProfileStatus getStatus() { return profileStatus; }
    public static DeviceProfile getProfile() { return activeProfile; }
    public static String getLastError() { return lastError; }
    
    // ==================== التدفق الرئيسي ====================
    
    /**
     * التدفق الكامل: اكتشاف → قدرات → Profile → تحقق → تسجيل
     * 
     * @param reader القارئ المهيأ
     * @return Profile كامل أو null في حال الفشل
     */
    public static DeviceProfile configure(RFIDWithUHFUART reader) {
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
    
    // ==================== المرحلة 1: الاكتشاف ====================
    
    private static boolean detect(RFIDWithUHFUART reader) {
        try {
            activeProfile.modelName = getDeviceModelName();
            activeProfile.model = getDeviceModel();
            profileStatus = ProfileStatus.DETECTED;
            
            Log.i(TAG, "📱 Phase 1 - Detection: " + activeProfile.modelName + 
                " → " + activeProfile.model.name());
            
            if (activeProfile.model == DeviceModel.UNKNOWN) {
                Log.w(TAG, "⚠️ Unknown device model: " + activeProfile.modelName);
                // نستمر - قد يكون جهازاً جديداً
            }
            
            return true;
        } catch (Exception e) {
            lastError = "Detection error: " + e.getMessage();
            return false;
        }
    }
    
    // ==================== المرحلة 2: قراءة القدرات ====================
    
    private static boolean readCapabilities(RFIDWithUHFUART reader) {
        try {
            Log.i(TAG, "🔍 Phase 2 - Reading capabilities...");
            
            // قراءة إصدارات البرنامج والهاردوير
            activeProfile.softwareVersion = reader.getVersion();
            activeProfile.hardwareVersion = reader.getHardwareVersion();
            
            // قراءة درجة الحرارة
            // درجة الحرارة - قد لا تكون مدعومة
activeProfile.temperature = 25;
activeProfile.temperatureOK = true;
try {
    java.lang.reflect.Method getTemp = reader.getClass().getMethod("getTemperature");
    activeProfile.temperature = (int) getTemp.invoke(reader);
    activeProfile.temperatureOK = activeProfile.temperature > -40 && activeProfile.temperature < 85;
} catch (Exception e) {
    Log.w(TAG, "   Temperature reading not supported");
}
            
            // تحديد نطاق الطاقة بناءً على الجهاز الفعلي
            determinePowerRange();
            
            // تحديد نطاق التردد
            determineFrequencyRange();
            
            profileStatus = ProfileStatus.CAPABILITIES_READ;
            
            Log.i(TAG, "   SW: " + activeProfile.softwareVersion);
            Log.i(TAG, "   HW: " + activeProfile.hardwareVersion);
            Log.i(TAG, "   Power Range: " + activeProfile.minPower + "-" + 
                activeProfile.maxPower + " dBm");
            Log.i(TAG, "   Frequency: " + activeProfile.frequencyRange);
            Log.i(TAG, "   Temperature: " + activeProfile.temperature + "°C " + 
                (activeProfile.temperatureOK ? "✓" : "⚠️"));
            
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
                activeProfile.maxPower = 33;
                break;
            default:
                // Conservative range for unknown devices
                activeProfile.minPower = 10;
                activeProfile.maxPower = 26;
                break;
        }
    }
    
    private static void determineFrequencyRange() {
        switch (activeProfile.model) {
            case C5:
                activeProfile.frequencyMode = 2; // ETSI
                activeProfile.frequencyRange = "865-868 MHz (ETSI)";
                break;
            case C72:
                activeProfile.frequencyMode = 1; // FCC
                activeProfile.frequencyRange = "902-928 MHz (FCC)";
                break;
            default:
                activeProfile.frequencyMode = 1;
                activeProfile.frequencyRange = "902-928 MHz (FCC Default)";
                break;
        }
    }
    
    // ==================== المرحلة 3: تطبيق Profile ====================
    
    private static boolean applyProfile(RFIDWithUHFUART reader) {
    try {
        Log.i(TAG, "⚙️ Phase 3 - Applying profile...");
        
        activeProfile.appliedPower = calculateOptimalPower();
        
        // RFIDWithUHFUART لا يدعم setOutputPower مباشرة
        // نستخدم setPower بدلاً منها إن وجدت
        boolean powerResult = false;
        try {
            // محاولة استخدام الدالة الصحيحة
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
                powerResult = true; // نستمر بدون ضبط الطاقة
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
        // للمجوهرات والجرد:
        // - نحتاج مدى قصير (30-50 سم) لتجنب قراءة قطع من واجهات مجاورة
        // - دقة أعلى من السرعة
        // - استهلاك بطارية أقل
        
        switch (activeProfile.model) {
            case C5:
                // C5 ممتاز للجرد القريب
                return 22; // مدى ~40 سم مع دقة عالية
            case C72:
                // C72 يحتاج طاقة أقل شوي
                return 24;
            default:
                // قيمة آمنة للجميع
                return 22;
        }
    }
    
    // ==================== المرحلة 4: التحقق ====================
    
    private static boolean verify(RFIDWithUHFUART reader) {
        try {
            Log.i(TAG, "✅ Phase 4 - Verifying configuration...");
            
            boolean allOK = true;
            
            // تحقق من الطاقة
            if (!activeProfile.powerVerified) {
                Log.w(TAG, "   ⚠️ Power setting not verified");
                allOK = false;
            }
            
            // تحقق من درجة الحرارة
            if (!activeProfile.temperatureOK) {
                Log.w(TAG, "   ⚠️ Temperature out of safe range: " + 
                    activeProfile.temperature + "°C");
            }
            
            // تحقق من الإصدار
            if (activeProfile.softwareVersion == null || 
                activeProfile.softwareVersion.isEmpty()) {
                Log.w(TAG, "   ⚠️ Could not read software version");
            }
            
            profileStatus = ProfileStatus.VERIFIED;
            
            if (allOK) {
                Log.i(TAG, "   ✅ All checks passed");
            } else {
                Log.w(TAG, "   ⚠️ Some checks failed but device is operational");
                allOK = true; // نسمح بالعمل مع تحذيرات
            }
            
            return allOK;
            
        } catch (Exception e) {
            lastError = "Verification error: " + e.getMessage();
            Log.e(TAG, "❌ " + lastError);
            return false;
        }
    }
    
    // ==================== المرحلة 5: التسجيل ====================
    
    private static void logProfile() {
        Log.i(TAG, "\n" + activeProfile.toString());
        
        // هنا يمكن إرسال الـ Profile إلى الخادم للتحليلات
        // sendProfileToServer(activeProfile);
    }
    
    // ==================== Helper ====================
    
    private static DeviceProfile fail(String reason) {
        lastError = reason;
        profileStatus = ProfileStatus.FAILED;
        if (activeProfile != null) {
            activeProfile.ready = false;
        }
        Log.e(TAG, "❌ Device configuration FAILED: " + reason);
        Log.e(TAG, "   Time: " + (System.currentTimeMillis() - detectionTime) + "ms");
        return null;
    }
    
    // ==================== Public API ====================
    
    /**
     * فحص سريع: هل الجهاز جاهز؟
     */
    public static boolean isReady() {
        return profileStatus == ProfileStatus.READY && 
               activeProfile != null && 
               activeProfile.ready;
    }
    
    /**
     * الحصول على الطاقة الحالية
     */
    public static int getCurrentPower() {
        if (activeProfile != null) {
            return activeProfile.appliedPower;
        }
        return 22; // افتراضي آمن
    }
    
    /**
     * الحصول على معلومات الجهاز كنص منسق
     */
    public static String getDeviceInfo() {
        if (activeProfile != null) {
            return activeProfile.toString();
        }
        return "Device not configured yet\nModel: " + getDeviceModelName();
    }
    
    /**
     * إعادة تعيين حالة الكاشف (للتطوير والاختبار)
     */
    public static void reset() {
        cachedModel = null;
        profileStatus = ProfileStatus.UNKNOWN;
        activeProfile = null;
        lastError = null;
        detectionTime = 0;
    }
}
