package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraReaderSettings — المصدر الوحيد للحقيقة لكل إعدادات قارئ RFID
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  ⚠️ قاعدة ذهبية:
 *    هذا الـ Singleton هو الوحيد الذي يحق له تعديل إعدادات القارئ.
 *    كل التعديلات عبر MiraReaderControlFragment تُحفظ هنا.
 *    RFIDManager.startScan() يستدعي applyToReader() قبل كل قراءة.
 *
 *  ⚠️ حل مشكلة "UHF الرسمي":
 *    بما أن كل scan يُعيد تطبيق الإعدادات، لا يمكن لأي تطبيق خارجي
 *    أن يُبقي إعداداته فعّالة داخل MIRA.
 */
public class MiraReaderSettings {

    private static final String TAG = "MiraReaderSettings";
    private static final String PREFS = "mira_reader_settings_v1";

    // ═══════════════════════════════════════════════════════════════
    //  القيم الافتراضية (آمنة للمعادن والذهب)
    // ═══════════════════════════════════════════════════════════════
    public static final int  DEF_POWER_DBM       = 27;   // آمن للوائح
    public static final int  DEF_FREQUENCY_MODE  = 2;    // ETSI (865-868)
    public static final int  DEF_SESSION         = 1;    // S1 للمعادن
    public static final int  DEF_TARGET          = 2;    // A/B
    public static final int  DEF_Q_VALUE         = 4;    // متوازن
    public static final int  DEF_RSSI_THRESHOLD  = -75;
    public static final int  DEF_SCAN_INTERVAL   = 100;
    public static final int  DEF_BEEP_MODE       = 1;
    public static final int  DEF_VIBRATION_MODE  = 1;
    public static final boolean DEF_FAST_ID      = false;
    public static final boolean DEF_TAG_FOCUS    = false;
    public static final boolean DEF_DYNAMIC_POWER = true;

    // ═══════════════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════════════
    private static volatile MiraReaderSettings instance;
    private final Context context;
    private final SharedPreferences prefs;

    private MiraReaderSettings(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Log.i(TAG, "✅ MiraReaderSettings initialized");
    }

    public static MiraReaderSettings getInstance(Context ctx) {
        if (instance == null) {
            synchronized (MiraReaderSettings.class) {
                if (instance == null) instance = new MiraReaderSettings(ctx);
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Getters
    // ═══════════════════════════════════════════════════════════════
    public int     getPowerDbm()       { return prefs.getInt("power_dbm", DEF_POWER_DBM); }
    public int     getFrequencyMode()  { return prefs.getInt("freq_mode", DEF_FREQUENCY_MODE); }
    public int     getSession()        { return prefs.getInt("session", DEF_SESSION); }
    public int     getTarget()         { return prefs.getInt("target", DEF_TARGET); }
    public int     getQValue()         { return prefs.getInt("q_value", DEF_Q_VALUE); }
    public int     getRssiThreshold()  { return prefs.getInt("rssi", DEF_RSSI_THRESHOLD); }
    public int     getScanInterval()   { return prefs.getInt("interval", DEF_SCAN_INTERVAL); }
    public int     getBeepMode()       { return prefs.getInt("beep", DEF_BEEP_MODE); }
    public int     getVibrationMode()  { return prefs.getInt("vib", DEF_VIBRATION_MODE); }
    public boolean isFastId()          { return prefs.getBoolean("fast_id", DEF_FAST_ID); }
    public boolean isTagFocus()        { return prefs.getBoolean("tag_focus", DEF_TAG_FOCUS); }
    public boolean isDynamicPower()    { return prefs.getBoolean("dyn_power", DEF_DYNAMIC_POWER); }

    // ═══════════════════════════════════════════════════════════════
    //  Setters (commit() لضمان فورية القيمة)
    // ═══════════════════════════════════════════════════════════════
    public void setPowerDbm(int v)      { prefs.edit().putInt("power_dbm", clamp(v, 5, 30)).commit(); }
    public void setFrequencyMode(int v) { prefs.edit().putInt("freq_mode", clamp(v, 0, 4)).commit(); }
    public void setSession(int v)       { prefs.edit().putInt("session", clamp(v, 0, 3)).commit(); }
    public void setTarget(int v)        { prefs.edit().putInt("target", clamp(v, 0, 2)).commit(); }
    public void setQValue(int v)        { prefs.edit().putInt("q_value", clamp(v, 0, 15)).commit(); }
    public void setRssiThreshold(int v) { prefs.edit().putInt("rssi", clamp(v, -100, -30)).commit(); }
    public void setScanInterval(int v)  { prefs.edit().putInt("interval", clamp(v, 50, 1000)).commit(); }
    public void setBeepMode(int v)      { prefs.edit().putInt("beep", v).commit(); }
    public void setVibrationMode(int v) { prefs.edit().putInt("vib", v).commit(); }
    public void setFastId(boolean v)    { prefs.edit().putBoolean("fast_id", v).commit(); }
    public void setTagFocus(boolean v)  { prefs.edit().putBoolean("tag_focus", v).commit(); }
    public void setDynamicPower(boolean v) { prefs.edit().putBoolean("dyn_power", v).commit(); }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    // ═══════════════════════════════════════════════════════════════
    //  ✅ التطبيق الفعلي على القارئ
    //  يُستدعى من:
    //    1. RFIDManager.startScan() قبل كل قراءة
    //    2. ChainwayReader.connect() عند الاتصال
    //    3. زر "تطبيق الآن" في MiraReaderControlFragment
    // ═══════════════════════════════════════════════════════════════

    public static class ApplyResult {
        public final int applied;
        public final int failed;
        public final List<String> failedMethods = new ArrayList<>();

        public ApplyResult(int applied, int failed) {
            this.applied = applied; this.failed = failed;
        }
        public boolean isSuccess() { return failed == 0; }
        @Override public String toString() {
            return "applied=" + applied + " failed=" + failed
                    + (failed > 0 ? " " + failedMethods : "");
        }
    }

    /**
     * ✅ يطبّق كل الإعدادات على القارئ الفعلي (RFIDWithUHFUART)
     *
     * استراتيجية Reflection:
     *   - لكل إعداد، نُجرّب عدة أسماء دوال محتملة
     *   - نُجرّب كلاً من (int) و (byte) لأن SDK يتفاوت
     *   - الأخطاء لا تُوقف التطبيق — نُكمل الباقي
     */
    public ApplyResult applyToReader(RFIDWithUHFUART reader) {
        if (reader == null) {
            Log.w(TAG, "applyToReader: reader is null");
            return new ApplyResult(0, 0);
        }

        Log.i(TAG, "════════ Applying MIRA Settings ════════");

        int applied = 0, failed = 0;
        List<String> failedNames = new ArrayList<>();

        // 1) Power — الطريقة المباشرة (مضمونة الوجود)
        try {
            reader.setPower(getPowerDbm());
            applied++;
            Log.i(TAG, "   ✅ setPower(" + getPowerDbm() + ")");
        } catch (Exception e) {
            failed++; failedNames.add("setPower");
            Log.e(TAG, "   ❌ setPower", e);
        }

        // 2) Frequency Mode — byte أو int
        if (tryIntOrByte(reader, new String[]{"setFrequencyMode", "setFreqMode", "setRegion"},
                getFrequencyMode(), "Frequency")) applied++;
        else { failed++; failedNames.add("setFrequencyMode"); }

        // 3) Session
        if (tryIntOrByte(reader, new String[]{"setSession", "setSessionMode", "setEPCMode"},
                getSession(), "Session")) applied++;
        else { failed++; failedNames.add("setSession"); }

        // 4) Target
        if (tryIntOrByte(reader, new String[]{"setTarget", "setTargetMode"},
                getTarget(), "Target")) applied++;
        else { failed++; failedNames.add("setTarget"); }

        // 5) Q Value
        if (tryIntOrByte(reader, new String[]{"setQValue", "setQ", "setQueryQ"},
                getQValue(), "QValue")) applied++;
        else { failed++; failedNames.add("setQValue"); }

        // 6) FastID
        if (tryBool(reader, new String[]{"setFastId", "setFastMode", "setFastTID", "setImpinjFastTID"},
                isFastId(), "FastID")) applied++;
        else { failed++; failedNames.add("setFastId"); }

        // 7) TagFocus
        if (tryBool(reader, new String[]{"setTagFocus", "setTagFocusMode"},
                isTagFocus(), "TagFocus")) applied++;
        else { failed++; failedNames.add("setTagFocus"); }

        // 8) DynamicPower
        if (tryBool(reader, new String[]{"setDynamicPower", "setDynamicMode"},
                isDynamicPower(), "DynamicPower")) applied++;
        else { failed++; failedNames.add("setDynamicPower"); }

        // 9) Beep
        if (tryIntOrByte(reader, new String[]{"setBeep", "setBeepMode", "setBeeperMode"},
                getBeepMode(), "Beep")) applied++;
        else { failed++; failedNames.add("setBeep"); }

        // 10) Vibration
        if (tryIntOrByte(reader, new String[]{"setVibration", "setVibrationMode"},
                getVibrationMode(), "Vibration")) applied++;
        else { failed++; failedNames.add("setVibration"); }

        ApplyResult r = new ApplyResult(applied, failed);
        r.failedMethods.addAll(failedNames);
        Log.i(TAG, "✅ " + r.toString());
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    //  أدوات Reflection مع محاولات متعددة
    // ═══════════════════════════════════════════════════════════════

    private boolean tryIntOrByte(Object target, String[] names, int value, String label) {
        // حاول int أولاً
        for (String n : names) {
            if (invokeTyped(target, n, int.class, value)) {
                Log.i(TAG, "   ✅ " + n + "(" + value + ") [int]");
                return true;
            }
        }
        // ثم byte
        for (String n : names) {
            if (invokeTyped(target, n, byte.class, (byte) value)) {
                Log.i(TAG, "   ✅ " + n + "(" + value + ") [byte]");
                return true;
            }
        }
        Log.w(TAG, "   ⚠️ " + label + " — no method matched: " + String.join("/", names));
        return false;
    }

    private boolean tryBool(Object target, String[] names, boolean value, String label) {
        for (String n : names) {
            if (invokeTyped(target, n, boolean.class, value)) {
                Log.i(TAG, "   ✅ " + n + "(" + value + ")");
                return true;
            }
        }
        Log.w(TAG, "   ⚠️ " + label + " — no method matched: " + String.join("/", names));
        return false;
    }

    private boolean invokeTyped(Object target, String name, Class<?> paramType, Object value) {
        try {
            Method m = target.getClass().getMethod(name, paramType);
            m.invoke(target, value);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Exception e) {
            Log.w(TAG, "   ⚠️ " + name + "(" + value + ") — invoke error: " + e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  إعادة تعيين
    // ═══════════════════════════════════════════════════════════════
    public void resetToDefaults() {
        prefs.edit().clear().commit();
        Log.i(TAG, "🔄 Reset to defaults");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ملخص للعرض
    // ═══════════════════════════════════════════════════════════════
    public String getSummary() {
        return "⚡ Power: " + getPowerDbm() + " dBm\n"
             + "🌍 Freq: " + getFrequencyLabel(getFrequencyMode()) + "\n"
             + "📊 Session: S" + getSession() + "\n"
             + "🎯 Target: " + getTargetLabel(getTarget()) + "\n"
             + "🔢 Q: " + getQValue() + "\n"
             + "📶 RSSI: " + getRssiThreshold() + " dBm\n"
             + "⏱️ Interval: " + getScanInterval() + " ms\n"
             + "🚀 FastID: " + (isFastId() ? "ON" : "OFF") + "\n"
             + "🎯 TagFocus: " + (isTagFocus() ? "ON" : "OFF") + "\n"
             + "⚡ DynPower: " + (isDynamicPower() ? "ON" : "OFF");
    }

    public static String getFrequencyLabel(int m) {
        switch (m) {
            case 0: return "FCC (902-928)";
            case 1: return "FCC-A (902-928)";
            case 2: return "ETSI (865-868)";
            case 3: return "CHINA (920-925)";
            case 4: return "JAPAN (916-921)";
            default: return "Unknown";
        }
    }

    public static String getTargetLabel(int t) {
        switch (t) {
            case 0: return "A";
            case 1: return "B";
            case 2: return "A/B";
            default: return "?";
        }
    }
}
