package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MIRA Settings Manager v2.1
 * 
 * ✅ [v2.1] إضافة:
 *   - getDeviceGateId() / setDeviceGateId()
 *   - KEY_DEVICE_GATE_ID — gate_id المثبت على الجهاز
 *   - يُستخدم من MiraSecureGateFragment لتسجيل gate_id دقيق في السجلات
 */
public class MiraGatesSettings {

    private static final String TAG = "MiraGatesSettings";
    private static final String PREFS_NAME = "MIRA_APP_SETTINGS";

    private static MiraGatesSettings instance;
    private final SharedPreferences prefs;
    private final Context appContext;

    // ═══════════════════════════════════════════════════════════
    // 🔑 مفاتيح الإعدادات
    // ═══════════════════════════════════════════════════════════
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

    private static final String KEY_OPERATION_MODE = "operation_mode";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_CONTINUOUS_SCAN = "continuous_scan";

    private static final String KEY_SIMULATE_EXIT_ON_SALE = "simulate_exit_on_sale";

    /** ✅ [v2.1] gate_id المثبت على هذا الجهاز */
    private static final String KEY_DEVICE_GATE_ID = "device_gate_id";

    // ═══════════════════════════════════════════════════════════
    // 🎯 Operation Mode Enum
    // ═══════════════════════════════════════════════════════════
    public enum OperationMode {
        MANUAL,
        SEMI_AUTO,
        FULL_AUTO
    }

    private MiraGatesSettings(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraGatesSettings getInstance(Context context) {
        if (instance == null) {
            instance = new MiraGatesSettings(context);
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ [v2.1] Device Gate ID — للاستخدام في السجلات الأمنية
    // ═══════════════════════════════════════════════════════════

    /**
     * gate_id المثبت على هذا الجهاز (يُضبط عند login).
     * 
     * يُستخدم من MiraSecureGateFragment.getCurrentGateId()
     * لتسجيل قرار البوابة تحت gate_id الصحيح (وليس أول قائمة).
     * 
     * @return gate_id أو "" إذا لم يُضبط بعد
     */
    public String getDeviceGateId() {
        return prefs.getString(KEY_DEVICE_GATE_ID, "");
    }
    
    
/**
 * ✅ [v2.2] يُعيد البوابة النشطة لهذا الجهاز.
 * 
 * منطق:
 *   1. اقرأ device_gate_id (gate_id المثبت من login)
 *   2. ابحث في القائمة عن البوابة المطابقة
 *   3. أرجعها أو null
 * 
 * تُستخدم من GateRfidBridge لمعرفة نوع الجهاز
 * (vanch_fixed vs chainway_c72) قبل بدء المسح.
 * 
 * @return GateDevice أو null
 */
public GateDevice getActiveDeviceGate() {
    try {
        String deviceGateId = getDeviceGateId();
        if (deviceGateId == null || deviceGateId.trim().isEmpty()) {
            return null;
        }

        List<GateDevice> gates = getGates();
        if (gates == null || gates.isEmpty()) return null;

        for (GateDevice gate : gates) {
            if (gate == null) continue;
            if (gate.gateId != null && gate.gateId.equals(deviceGateId)) {
                return gate;
            }
        }
    } catch (Exception e) {
        Log.w(TAG, "getActiveDeviceGate error: " + e.getMessage());
    }
    return null;
}
    /**
     * اضبط gate_id لهذا الجهاز.
     * يُستدعى من MiraApiClient.login() بعد نجاح الدخول.
     */
    public void setDeviceGateId(String gateId) {
        if (gateId == null) gateId = "";
        String trimmed = gateId.trim();
        prefs.edit().putString(KEY_DEVICE_GATE_ID, trimmed).apply();
        Log.i(TAG, "✅ Device gate_id set: " + trimmed);
    }

    // ═══════════════════════════════════════════════════════════
    // 🎯 Operation Mode
    // ═══════════════════════════════════════════════════════════

    public OperationMode getOperationMode() {
        try {
            String mode = prefs.getString(KEY_OPERATION_MODE,
                    OperationMode.SEMI_AUTO.name());
            return OperationMode.valueOf(mode);
        } catch (Exception e) {
            Log.e(TAG, "getOperationMode error: " + e.getMessage());
            return OperationMode.SEMI_AUTO;
        }
    }

    public void setOperationMode(OperationMode mode) {
        if (mode == null) mode = OperationMode.SEMI_AUTO;
        prefs.edit().putString(KEY_OPERATION_MODE, mode.name()).apply();
        Log.i(TAG, "✅ Operation mode set: " + mode.name());
    }

    public boolean isAutoScanMode() {
        OperationMode mode = getOperationMode();
        return mode == OperationMode.SEMI_AUTO || mode == OperationMode.FULL_AUTO;
    }

    public boolean isManualMode() {
        return getOperationMode() == OperationMode.MANUAL;
    }

    public String getModeDisplayName(boolean isArabic) {
        switch (getOperationMode()) {
            case MANUAL:
                return isArabic ? "✋ يدوي" : "✋ Manual";
            case SEMI_AUTO:
                return isArabic ? "🔄 شبه تلقائي" : "🔄 Semi-Auto";
            case FULL_AUTO:
                return isArabic ? "⚡ تلقائي كامل" : "⚡ Full Auto";
            default:
                return "?";
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ⚙️ إعدادات إضافية
    // ═══════════════════════════════════════════════════════════

    public boolean isKeepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean value) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply();
    }

    public boolean shouldKeepScreenOn() {
        return isKeepScreenOn() && !isManualMode();
    }

    public boolean isContinuousScanEnabled() {
        return prefs.getBoolean(KEY_CONTINUOUS_SCAN, true);
    }

    public void setContinuousScanEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_CONTINUOUS_SCAN, value).apply();
    }

    // ═══════════════════════════════════════════════════════════
    // 🎬 محاكاة الخروج عند البيع
    // ═══════════════════════════════════════════════════════════

    public boolean isSimulateExitOnSale() {
        return prefs.getBoolean(KEY_SIMULATE_EXIT_ON_SALE, false);
    }

    public void setSimulateExitOnSale(boolean value) {
        prefs.edit().putBoolean(KEY_SIMULATE_EXIT_ON_SALE, value).apply();
        Log.i(TAG, "🎬 Simulate exit on sale: " + value);
    }

    // ═══════════════════════════════════════════════════════════
    // 🏢 إدارة البوابات
    // ═══════════════════════════════════════════════════════════

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
        public String soundPass = "beep_success";
        public String soundDeny = "beep_warning";
        public String soundAlarm = "alarm_breach_security";
        public boolean vibrationEnabled = true;
        public boolean flashEnabled = true;
        public int alarmDuration = 10;

        public int passCount = 0;
        public int denyCount = 0;
        public String lastEvent = "";

        public int branchId = 0;
        public String branchName = "";

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
                json.put("vibration_enabled", vibrationEnabled);
                json.put("flash_enabled", flashEnabled);
                json.put("alarm_duration", alarmDuration);
                json.put("pass_count", passCount);
                json.put("deny_count", denyCount);
                json.put("last_event", lastEvent);
                json.put("branch_id", branchId);
                json.put("branch_name", branchName);
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
            gate.soundAlarm = json.optString("sound_alarm", "alarm_breach_security");
            gate.vibrationEnabled = json.optBoolean("vibration_enabled", true);
            gate.flashEnabled = json.optBoolean("flash_enabled", true);
            gate.alarmDuration = json.optInt("alarm_duration", 10);
            gate.passCount = json.optInt("pass_count", 0);
            gate.denyCount = json.optInt("deny_count", 0);
            gate.lastEvent = json.optString("last_event", "");
            gate.branchId = json.optInt("branch_id", 0);
            gate.branchName = json.optString("branch_name", "");
            return gate;
        }
    }

    public void saveGates(List<GateDevice> gates) {
        JSONArray array = new JSONArray();
        for (GateDevice gate : gates) {
            array.put(gate.toJson());
        }
        prefs.edit().putString(KEY_GATES_LIST, array.toString()).apply();
    }

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

    public void addGate(GateDevice gate) {
        List<GateDevice> gates = getGates();
        gates.add(gate);
        saveGates(gates);
    }

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

    public void saveGateSettings(String gateId, String soundPass, String soundDeny,
                                  String soundAlarm, boolean vibration, boolean flash,
                                  int alarmDuration, int rfidPower) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("gate_sound_pass_" + gateId, soundPass);
        editor.putString("gate_sound_deny_" + gateId, soundDeny);
        editor.putString("gate_sound_alarm_" + gateId, soundAlarm);
        editor.putBoolean("gate_vibration_" + gateId, vibration);
        editor.putBoolean("gate_flash_" + gateId, flash);
        editor.putInt("gate_alarm_duration_" + gateId, alarmDuration);
        editor.putInt("gate_rfid_power_" + gateId, rfidPower);
        editor.apply();
    }

    public GateSettings getGateSettings(String gateId) {
        GateSettings gs = new GateSettings();
        gs.soundPass = prefs.getString("gate_sound_pass_" + gateId, getSoundPass());
        gs.soundDeny = prefs.getString("gate_sound_deny_" + gateId, getSoundDeny());
        gs.soundAlarm = prefs.getString("gate_sound_alarm_" + gateId, getSoundAlarm());
        gs.vibrationEnabled = prefs.getBoolean("gate_vibration_" + gateId, isVibrationEnabled());
        gs.flashEnabled = prefs.getBoolean("gate_flash_" + gateId, isFlashEnabled());
        gs.alarmDuration = prefs.getInt("gate_alarm_duration_" + gateId, getAlarmDuration());
        gs.rfidPower = prefs.getInt("gate_rfid_power_" + gateId, getRfidPower());
        return gs;
    }

    public GateDevice getPrimaryGate() {
        List<GateDevice> gates = getGates();
        for (GateDevice gate : gates) {
            if (gate.isPrimary) return gate;
        }
        return gates.isEmpty() ? null : gates.get(0);
    }

    public void setPrimaryGate(String gateId) {
        List<GateDevice> gates = getGates();
        for (GateDevice gate : gates) {
            gate.isPrimary = gate.gateId.equals(gateId);
        }
        saveGates(gates);
    }

    // ═══════════════════════════════════════════════════════════
    // 🔊 الأصوات
    // ═══════════════════════════════════════════════════════════

    public static class SoundOptions {
        public static final String[] PASS_SOUNDS = {
                "beep_success", "beep_soft", "beep_double", "chime_elegant", "silent"
        };

        public static final String[] DENY_SOUNDS = {
                "beep_warning", "beep_error", "alarm_soft", "buzz_double", "silent"
        };

        public static final String[] ALARM_SOUNDS = {
                "alarm_sold_item", "alarm_breach_security", "alarm_siren_strong",
                "alarm_police", "alarm_buzzer_loud", "alarm_klaxon", "silent"
        };

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

    public void setSoundPass(String soundName) {
        prefs.edit().putString(KEY_SOUND_PASS, soundName).apply();
    }

    public String getSoundPass() {
        return prefs.getString(KEY_SOUND_PASS, "beep_success");
    }

    public void setSoundDeny(String soundName) {
        prefs.edit().putString(KEY_SOUND_DENY, soundName).apply();
    }

    public String getSoundDeny() {
        return prefs.getString(KEY_SOUND_DENY, "beep_warning");
    }

    public void setSoundAlarm(String soundName) {
        prefs.edit().putString(KEY_SOUND_ALARM, soundName).apply();
    }

    public String getSoundAlarm() {
        return prefs.getString(KEY_SOUND_ALARM, "alarm_breach_security");
    }

    // ═══════════════════════════════════════════════════════════
    // ⚙️ إعدادات إضافية
    // ═══════════════════════════════════════════════════════════

    public void setVibrationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply();
    }

    public boolean isVibrationEnabled() {
        return prefs.getBoolean(KEY_VIBRATION_ENABLED, true);
    }

    public void setFlashEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FLASH_ENABLED, enabled).apply();
    }

    public boolean isFlashEnabled() {
        return prefs.getBoolean(KEY_FLASH_ENABLED, true);
    }

    public void setAlarmDuration(int seconds) {
        prefs.edit().putInt(KEY_ALARM_DURATION, seconds).apply();
    }

    public int getAlarmDuration() {
        return prefs.getInt(KEY_ALARM_DURATION, 10);
    }

    public void setAlarmInterval(int milliseconds) {
        prefs.edit().putInt(KEY_ALARM_INTERVAL, milliseconds).apply();
    }

    public int getAlarmInterval() {
        return prefs.getInt(KEY_ALARM_INTERVAL, 2200);
    }

    public void setRfidPower(int power) {
        prefs.edit().putInt(KEY_RFID_POWER, power).apply();
    }

    public int getRfidPower() {
        return prefs.getInt(KEY_RFID_POWER, 28);
    }

    public void setScanCooldown(long milliseconds) {
        prefs.edit().putLong(KEY_SCAN_COOLDOWN, milliseconds).apply();
    }

    public long getScanCooldown() {
        return prefs.getLong(KEY_SCAN_COOLDOWN, 2500);
    }

    // ═══════════════════════════════════════════════════════════
    // 🏢 Chainway C72
    // ═══════════════════════════════════════════════════════════

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

    public void saveChainwaySettings(ChainwaySettings settings) {
        prefs.edit().putString("chainway_settings", settings.toJson().toString()).apply();
    }

    public ChainwaySettings getChainwaySettings() {
        String json = prefs.getString("chainway_settings", "{}");
        try {
            return ChainwaySettings.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return new ChainwaySettings();
        }
    }

    public static class GateSettings {
        public String soundPass = "beep_success";
        public String soundDeny = "beep_warning";
        public String soundAlarm = "alarm_breach_security";
        public boolean vibrationEnabled = true;
        public boolean flashEnabled = true;
        public int alarmDuration = 10;
        public int rfidPower = 28;
    }
}
