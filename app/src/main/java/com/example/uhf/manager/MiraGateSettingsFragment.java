package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MIRA Settings Manager
 * إدارة إعدادات التطبيق الشاملة
 * - إعدادات البوابات الأمنية
 * - إعدادات الأصوات والتنبيهات
 * - إعدادات أجهزة Chainway C72
 */
public class MiraSettings {

    private static final String TAG = "MiraSettings";
    private static final String PREFS_NAME = "MIRA_APP_SETTINGS";
    
    private static MiraSettings instance;
    private final SharedPreferences prefs;
    private final Context appContext;

    // مفاتيح الإعدادات
    private static final String KEY_GATES_LIST = "gates_list";
    private static final String KEY_SOUND_PASS = "sound_pass";
    private static final String KEY_SOUND_DENY = "sound_deny";
    private static final String KEY_SOUND_ALARM = "sound_alarm";
    private static final String KEY_VIBRATION_ENABLED = "vibration_enabled";
    private static final String KEY_FLASH_ENABLED = "flash_enabled";
    private static final String KEY_ALARM_DURATION = "alarm_duration";
    private static final String KEY_ALARM_INTERVAL = "alarm_interval";
    private static final String KEY_RFID_POWER = "rfid_power";
    private static final String KEY_SCAN_COOLDOWN = "scan_cooldown";

    private MiraSettings(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraSettings getInstance(Context context) {
        if (instance == null) {
            instance = new MiraSettings(context);
        }
        return instance;
    }

    // ============================================
    // 🏢 إدارة البوابات الأمنية
    // ============================================

    /**
     * نموذج بوابة أمنية
     */
    public static class GateDevice {
        public String gateId;
        public String gateName;
        public String location;
        public String deviceType = "chainway_c72";
        public String ipAddress;
        public int port = 8080;
        public String macAddress;
        public String firmwareVersion;
        public String status = "offline";
        public boolean isPrimary;
        public int rfidPower = 28;
        public int scanInterval = 500;
        public boolean autoArm;
        public String soundPass;
        public String soundDeny;
        public String soundAlarm;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("gate_id", gateId);
                json.put("gate_name", gateName);
                json.put("location", location);
                json.put("device_type", deviceType);
                json.put("ip_address", ipAddress);
                json.put("port", port);
                json.put("mac_address", macAddress);
                json.put("firmware_version", firmwareVersion);
                json.put("status", status);
                json.put("is_primary", isPrimary);
                json.put("rfid_power", rfidPower);
                json.put("scan_interval", scanInterval);
                json.put("auto_arm", autoArm);
                json.put("sound_pass", soundPass);
                json.put("sound_deny", soundDeny);
                json.put("sound_alarm", soundAlarm);
            } catch (Exception e) {
                Log.e(TAG, "GateDevice toJson error: " + e.getMessage());
            }
            return json;
        }

        public static GateDevice fromJson(JSONObject json) {
            GateDevice gate = new GateDevice();
            gate.gateId = json.optString("gate_id", "");
            gate.gateName = json.optString("gate_name", "");
            gate.location = json.optString("location", "");
            gate.deviceType = json.optString("device_type", "chainway_c72");
            gate.ipAddress = json.optString("ip_address", "");
            gate.port = json.optInt("port", 8080);
            gate.macAddress = json.optString("mac_address", "");
            gate.firmwareVersion = json.optString("firmware_version", "");
            gate.status = json.optString("status", "offline");
            gate.isPrimary = json.optBoolean("is_primary", false);
            gate.rfidPower = json.optInt("rfid_power", 28);
            gate.scanInterval = json.optInt("scan_interval", 500);
            gate.autoArm = json.optBoolean("auto_arm", false);
            gate.soundPass = json.optString("sound_pass", "beep_success");
            gate.soundDeny = json.optString("sound_deny", "beep_warning");
            gate.soundAlarm = json.optString("sound_alarm", "alarm_sold_item");
            return gate;
        }
    }

    /**
     * حفظ قائمة البوابات
     */
    public void saveGates(List<GateDevice> gates) {
        JSONArray array = new JSONArray();
        for (GateDevice gate : gates) {
            array.put(gate.toJson());
        }
        prefs.edit().putString(KEY_GATES_LIST, array.toString()).apply();
    }

    /**
     * جلب قائمة البوابات
     */
    public List<GateDevice> getGates() {
        List<GateDevice> gates = new ArrayList<>();
        String json = prefs.getString(KEY_GATES_LIST, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                gates.add(GateDevice.fromJson(array.optJSONObject(i)));
            }
        } catch (Exception e) {
            Log.e(TAG, "getGates error: " + e.getMessage());
        }
        return gates;
    }

    /**
     * إضافة بوابة جديدة
     */
    public void addGate(GateDevice gate) {
        List<GateDevice> gates = getGates();
        gates.add(gate);
        saveGates(gates);
    }

    /**
     * تحديث بوابة
     */
    public void updateGate(String gateId, GateDevice updatedGate) {
        List<GateDevice> gates = getGates();
        for (int i = 0; i < gates.size(); i++) {
            if (gates.get(i).gateId.equals(gateId)) {
                gates.set(i, updatedGate);
                break;
            }
        }
        saveGates(gates);
    }

    /**
     * حذف بوابة
     */
    public void removeGate(String gateId) {
        List<GateDevice> gates = getGates();
        for (int i = 0; i < gates.size(); i++) {
            if (gates.get(i).gateId.equals(gateId)) {
                gates.remove(i);
                break;
            }
        }
        saveGates(gates);
    }

    /**
     * الحصول على البوابة الأساسية
     */
    public GateDevice getPrimaryGate() {
        List<GateDevice> gates = getGates();
        for (GateDevice gate : gates) {
            if (gate.isPrimary) return gate;
        }
        return gates.isEmpty() ? null : gates.get(0);
    }

    /**
     * تعيين البوابة الأساسية
     */
    public void setPrimaryGate(String gateId) {
        List<GateDevice> gates = getGates();
        for (GateDevice gate : gates) {
            gate.isPrimary = gate.gateId.equals(gateId);
        }
        saveGates(gates);
    }

    // ============================================
    // 🔊 إعدادات الأصوات
    // ============================================

    /**
     * خيارات الأصوات المتاحة
     */
    public static class SoundOptions {
        public static final String[] PASS_SOUNDS = {
            "beep_success",      // صوت نجاح قصير
            "beep_soft",         // صوت ناعم
            "beep_double",       // صوت مزدوج
            "chime_elegant",     // نغمة أنيقة
            "silent"             // بدون صوت
        };

        public static final String[] DENY_SOUNDS = {
            "beep_warning",      // صوت تحذير
            "beep_error",        // صوت خطأ
            "alarm_soft",        // إنذار خفيف
            "buzz_double",       // طنين مزدوج
            "silent"             // بدون صوت
        };

        public static final String[] ALARM_SOUNDS = {
            "alarm_sold_item",       // إنذار البضاعة المباعة (افتراضي)
            "alarm_breach_security", // إنذار اختراق أمني
            "alarm_siren_strong",    // صفارة قوية
            "alarm_police",          // صفارة شرطة
            "alarm_buzzer_loud",     // طنين عالي
            "alarm_klaxon",          // كلوكسون
            "silent"                 // بدون صوت
        };

        /**
         * الحصول على Resource ID للصوت
         */
        public static int getSoundResourceId(Context context, String soundName) {
            if (soundName == null || soundName.equals("silent")) return -1;
            
            switch (soundName) {
                case "beep_success": return context.getResources().getIdentifier("beep_success", "raw", context.getPackageName());
                case "beep_soft": return context.getResources().getIdentifier("beep_soft", "raw", context.getPackageName());
                case "beep_double": return context.getResources().getIdentifier("beep_double", "raw", context.getPackageName());
                case "chime_elegant": return context.getResources().getIdentifier("chime_elegant", "raw", context.getPackageName());
                case "beep_warning": return context.getResources().getIdentifier("beep_warning", "raw", context.getPackageName());
                case "beep_error": return context.getResources().getIdentifier("beep_error", "raw", context.getPackageName());
                case "alarm_soft": return context.getResources().getIdentifier("alarm_soft", "raw", context.getPackageName());
                case "buzz_double": return context.getResources().getIdentifier("buzz_double", "raw", context.getPackageName());
                case "alarm_sold_item": return context.getResources().getIdentifier("alarm_sold_item", "raw", context.getPackageName());
                case "alarm_breach_security": return context.getResources().getIdentifier("alarm_breach_security", "raw", context.getPackageName());
                case "alarm_siren_strong": return context.getResources().getIdentifier("alarm_siren_strong", "raw", context.getPackageName());
                case "alarm_police": return context.getResources().getIdentifier("alarm_police", "raw", context.getPackageName());
                case "alarm_buzzer_loud": return context.getResources().getIdentifier("alarm_buzzer_loud", "raw", context.getPackageName());
                case "alarm_klaxon": return context.getResources().getIdentifier("alarm_klaxon", "raw", context.getPackageName());
                case "barcodebeep": return context.getResources().getIdentifier("barcodebeep", "raw", context.getPackageName());
                default: return -1;
            }
        }
    }

    /**
     * حفظ صوت النجاح
     */
    public void setSoundPass(String soundName) {
        prefs.edit().putString(KEY_SOUND_PASS, soundName).apply();
    }

    /**
     * جلب صوت النجاح
     */
    public String getSoundPass() {
        return prefs.getString(KEY_SOUND_PASS, "beep_success");
    }

    /**
     * حفظ صوت الرفض
     */
    public void setSoundDeny(String soundName) {
        prefs.edit().putString(KEY_SOUND_DENY, soundName).apply();
    }

    /**
     * جلب صوت الرفض
     */
    public String getSoundDeny() {
        return prefs.getString(KEY_SOUND_DENY, "beep_warning");
    }

    /**
     * حفظ صوت الإنذار
     */
    public void setSoundAlarm(String soundName) {
        prefs.edit().putString(KEY_SOUND_ALARM, soundName).apply();
    }

    /**
     * جلب صوت الإنذار
     */
    public String getSoundAlarm() {
        return prefs.getString(KEY_SOUND_ALARM, "alarm_breach_security");
    }

    // ============================================
    // ⚙️ إعدادات إضافية
    // ============================================

    /**
     * تفعيل/تعطيل الاهتزاز
     */
    public void setVibrationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply();
    }

    public boolean isVibrationEnabled() {
        return prefs.getBoolean(KEY_VIBRATION_ENABLED, true);
    }

    /**
     * تفعيل/تعطيل وميض الشاشة
     */
    public void setFlashEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FLASH_ENABLED, enabled).apply();
    }

    public boolean isFlashEnabled() {
        return prefs.getBoolean(KEY_FLASH_ENABLED, true);
    }

    /**
     * مدة الإنذار بالثواني
     */
    public void setAlarmDuration(int seconds) {
        prefs.edit().putInt(KEY_ALARM_DURATION, seconds).apply();
    }

    public int getAlarmDuration() {
        return prefs.getInt(KEY_ALARM_DURATION, 10);
    }

    /**
     * فترة تكرار الإنذار بالمللي ثانية
     */
    public void setAlarmInterval(int milliseconds) {
        prefs.edit().putInt(KEY_ALARM_INTERVAL, milliseconds).apply();
    }

    public int getAlarmInterval() {
        return prefs.getInt(KEY_ALARM_INTERVAL, 2200);
    }

    /**
     * قوة RFID
     */
    public void setRfidPower(int power) {
        prefs.edit().putInt(KEY_RFID_POWER, power).apply();
    }

    public int getRfidPower() {
        return prefs.getInt(KEY_RFID_POWER, 28);
    }

    /**
     * فترة تهدئة التاق المكرر
     */
    public void setScanCooldown(long milliseconds) {
        prefs.edit().putLong(KEY_SCAN_COOLDOWN, milliseconds).apply();
    }

    public long getScanCooldown() {
        return prefs.getLong(KEY_SCAN_COOLDOWN, 2500);
    }

    // ============================================
    // 🏢 إعدادات Chainway C72
    // ============================================

    /**
     * إعدادات جهاز Chainway C72
     */
    public static class ChainwaySettings {
        public int rfidPower = 28;
        public int scanInterval = 500;
        public boolean continuousMode = true;
        public int sessionTimeout = 30000;
        public boolean autoConnect = true;
        public String bluetoothMac = "";
        public String wifiIp = "";
        public int wifiPort = 8080;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("rfid_power", rfidPower);
                json.put("scan_interval", scanInterval);
                json.put("continuous_mode", continuousMode);
                json.put("session_timeout", sessionTimeout);
                json.put("auto_connect", autoConnect);
                json.put("bluetooth_mac", bluetoothMac);
                json.put("wifi_ip", wifiIp);
                json.put("wifi_port", wifiPort);
            } catch (Exception e) {}
            return json;
        }

        public static ChainwaySettings fromJson(JSONObject json) {
            ChainwaySettings settings = new ChainwaySettings();
            settings.rfidPower = json.optInt("rfid_power", 28);
            settings.scanInterval = json.optInt("scan_interval", 500);
            settings.continuousMode = json.optBoolean("continuous_mode", true);
            settings.sessionTimeout = json.optInt("session_timeout", 30000);
            settings.autoConnect = json.optBoolean("auto_connect", true);
            settings.bluetoothMac = json.optString("bluetooth_mac", "");
            settings.wifiIp = json.optString("wifi_ip", "");
            settings.wifiPort = json.optInt("wifi_port", 8080);
            return settings;
        }
    }

    /**
     * حفظ إعدادات Chainway C72
     */
    public void saveChainwaySettings(ChainwaySettings settings) {
        prefs.edit().putString("chainway_settings", settings.toJson().toString()).apply();
    }

    /**
     * جلب إعدادات Chainway C72
     */
    public ChainwaySettings getChainwaySettings() {
        String json = prefs.getString("chainway_settings", "{}");
        try {
            return ChainwaySettings.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return new ChainwaySettings();
        }
    }
}
