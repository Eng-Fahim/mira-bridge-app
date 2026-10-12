package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.Gen2Entity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraReaderSettings v3.2 — المصدر الوحيد لإعدادات قارئ RFID في MIRA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * [v3.1] إضافات:
 *   ✅ POS Power — قوة قراءة خاصة بوضع البيع (يحددها المستخدم)
 *   ✅ getPosRangeLabel() — ترجمة القوة إلى مدى تقريبي
 *
 * [v3.2] إضافات:
 *   ✅ نظام OnSettingsChangedListener — إبلاغ كل القارئات النشطة
 *      بأي تغيير في الإعدادات لتطبيقه فوراً (خاصة الجرد).
 *   ✅ notifyChanged() — تُستدعى من كل setter.
 */
public class MiraReaderSettings {

    private static final String TAG = "MiraReaderSettings";
    private static final String PREFS = "mira_reader_settings_v2";

    // ═══════════════════════════════════════════════════════════════
    //  📡 مناطق العمل (Working Mode) — القيم HEX الرسمية
    // ═══════════════════════════════════════════════════════════════
    public static final int FREQ_CHINA_840     = 0x01;
    public static final int FREQ_CHINA_920     = 0x02;
    public static final int FREQ_ETSI          = 0x04;  // Europe 865-868
    public static final int FREQ_FCC           = 0x08;  // USA 902-928
    public static final int FREQ_KOREA         = 0x16;
    public static final int FREQ_JAPAN         = 0x32;
    public static final int FREQ_SOUTH_AFRICA  = 0x33;
    public static final int FREQ_TAIWAN        = 0x34;
    public static final int FREQ_VIETNAM       = 0x35;
    public static final int FREQ_PERU          = 0x36;
    public static final int FREQ_RUSSIA        = 0x37;
    public static final int FREQ_MALAYSIA      = 0x3B;
    public static final int FREQ_BRAZIL        = 0x3C;
    public static final int FREQ_ETSI_UPPER    = 0x3D;
    public static final int FREQ_AUSTRALIA     = 0x3E;
    public static final int FREQ_INDONESIA     = 0x3F;
    public static final int FREQ_HK            = 0x41;
    public static final int FREQ_NEW_ZEALAND   = 0x42;

    /**
     * ✅ مصفوفة [القيمة HEX][الفهرس]
     *    الترتيب: الشرق الأوسط أولاً (الأكثر استخداماً)
     */
    public static final int[][] FREQ_MODES = {
            {FREQ_ETSI,         0},
            {FREQ_FCC,          1},
            {FREQ_CHINA_840,    2},
            {FREQ_CHINA_920,    3},
            {FREQ_SOUTH_AFRICA, 4},
            {FREQ_KOREA,        5},
            {FREQ_JAPAN,        6},
            {FREQ_TAIWAN,       7},
            {FREQ_VIETNAM,      8},
            {FREQ_PERU,         9},
            {FREQ_RUSSIA,      10},
            {FREQ_MALAYSIA,    11},
            {FREQ_BRAZIL,      12},
            {FREQ_ETSI_UPPER,  13},
            {FREQ_AUSTRALIA,   14},
            {FREQ_INDONESIA,   15},
            {FREQ_HK,          17},
            {FREQ_NEW_ZEALAND, 18}
    };

    /**
     * ✅ تسميات مفهومة للمستخدم — الشرق الأوسط أولاً
     */
    public static final String[] FREQ_LABELS = {
            "🌍 الشرق الأوسط والخليج (اليمن، السعودية، الإمارات) — 865-868",
            "🌎 أمريكا الشمالية (الولايات المتحدة، كندا) — 902-928",
            "🇨🇳 الصين (840-845)",
            "🇨🇳 الصين 2 (920-925)",
            "🇿🇦 جنوب إفريقيا (915-919)",
            "🇰🇷 كوريا (917-923)",
            "🇯🇵 اليابان (916.8-920.8)",
            "تايوان (920-928)",
            "فيتنام (918-923)",
            "بيرو (915-928)",
            "روسيا (860-867.6)",
            "ماليزيا (919-923)",
            "البرازيل",
            "أوروبا العليا (ETSI Upper)",
            "أستراليا",
            "إندونيسيا (920-923)",
            "هونغ كونغ",
            "نيوزيلندا"
    };

    // ═══════════════════════════════════════════════════════════════
    //  قيم RFLink (Miller/Gen2X)
    // ═══════════════════════════════════════════════════════════════
    public static final String[] RFLINK_LABELS = {
            "PR-ASK / Miller8 / 160KHz",
            "PR-ASK / Miller4 / 250KHz",
            "PR-ASK / Miller4 / 320KHz",
            "PR-ASK / Miller4 / 640KHz",
            "PR-ASK / Miller2 / 320KHz",
            "PR-ASK / Miller2 / 640KHz",
            "Gen2X / Miller8 / 160KHz",
            "Gen2X / Miller4 / 250KHz",
            "Gen2X / Miller4 / 320KHz",
            "Gen2X / Miller4 / 640KHz",
            "Gen2X / Miller2 / 320KHz",
            "Gen2X / Miller2 / 640KHz"
    };

    // ═══════════════════════════════════════════════════════════════
    //  🔄 وضع القراءة (Read Mode)
    // ═══════════════════════════════════════════════════════════════
    public static final int READ_MODE_AUTO   = 0;
    public static final int READ_MODE_SINGLE = 1;

    public static final String[] READ_MODE_LABELS = {
            "🔄 مستمر (للجرد)",
            "🎯 مفرد (للبيع والتحقق)"
    };

    public static final String[] READ_MODE_HINTS = {
            "يقرأ كل الوسوم القريبة باستمرار — مثالي للجرد السريع",
            "يقرأ وسماً واحداً فقط لكل ضغطة — مثالي للبيع"
    };

    // ═══════════════════════════════════════════════════════════════
    //  القيم الافتراضية
    // ═══════════════════════════════════════════════════════════════
    public static final int  DEF_POWER_DBM       = 27;
    public static final int  DEF_FREQ_MODE       = FREQ_ETSI;
    public static final int  DEF_READ_MODE       = READ_MODE_AUTO;
    public static final int  DEF_SESSION         = 1;
    public static final int  DEF_TARGET          = 0;
    public static final int  DEF_Q_MODE          = 0;
    public static final int  DEF_Q_VALUE         = 4;
    public static final int  DEF_MIN_Q           = 0;
    public static final int  DEF_MAX_Q           = 15;
    public static final int  DEF_DR              = 0;
    public static final int  DEF_MILLER          = 1;
    public static final int  DEF_LINK_FREQ       = 1;
    public static final int  DEF_RFLINK          = 1;
    public static final boolean DEF_FAST_ID      = false;
    public static final boolean DEF_TAG_FOCUS    = false;
    public static final boolean DEF_FAST_INV     = false;
    public static final int  DEF_RSSI_THRESHOLD  = -75;
    public static final int  DEF_SCAN_INTERVAL   = 100;

    // ═══════════════════════════════════════════════════════════════
    //  ⚡ POS Power — قوة قراءة خاصة بوضع البيع
    // ═══════════════════════════════════════════════════════════════
    public static final int  DEF_POS_POWER_DBM = 8;
    public static final int  MIN_POS_POWER_DBM = 5;
    public static final int  MAX_POS_POWER_DBM = 30;

    // ═══════════════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════════════
    private static volatile MiraReaderSettings instance;
    private final Context context;
    private final SharedPreferences prefs;

    // 🆕 v3.2 — قائمة المستمعين (WeakReference لمنع التسريبات)
    private final CopyOnWriteArrayList<WeakReference<OnSettingsChangedListener>> listeners
            = new CopyOnWriteArrayList<>();

    private MiraReaderSettings(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Log.i(TAG, "✅ MiraReaderSettings v3.2 initialized");
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
    //  🆕 v3.2 — نظام مراقبة التغييرات
    // ═══════════════════════════════════════════════════════════════

    /**
     * واجهة يستمع بها القارئ (أو أي مكوّن) لتغيّر الإعدادات.
     */
    public interface OnSettingsChangedListener {
        void onSettingsChanged();
    }

    /**
     * ✅ تسجيل مستمع جديد (آمن ضد التسجيل المكرر).
     */
    public void addListener(OnSettingsChangedListener l) {
        if (l == null) return;
        // تنظيف المراجع الميتة أولاً
        listeners.removeIf(ref -> ref.get() == null);
        // منع التسجيل المكرر
        for (WeakReference<OnSettingsChangedListener> ref : listeners) {
            if (ref.get() == l) return;
        }
        listeners.add(new WeakReference<>(l));
        Log.d(TAG, "➕ Listener added (total=" + listeners.size() + ")");
    }

    /**
     * ✅ إلغاء تسجيل مستمع.
     */
    public void removeListener(OnSettingsChangedListener l) {
        if (l == null) return;
        listeners.removeIf(ref -> ref.get() == null || ref.get() == l);
        Log.d(TAG, "➖ Listener removed (total=" + listeners.size() + ")");
    }

    /**
     * ✅ إبلاغ كل المستمعين — تُستدعى من كل setter.
     */
    private void notifyChanged() {
        int count = 0;
        for (WeakReference<OnSettingsChangedListener> ref : listeners) {
            OnSettingsChangedListener l = ref.get();
            if (l != null) {
                try {
                    l.onSettingsChanged();
                    count++;
                } catch (Exception e) {
                    Log.e(TAG, "notify error", e);
                }
            }
        }
        if (count > 0) {
            Log.d(TAG, "🔔 Notified " + count + " listener(s)");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Getters
    // ═══════════════════════════════════════════════════════════════
    public int     getPowerDbm()      { return prefs.getInt("power", DEF_POWER_DBM); }
    public int     getFreqMode()      { return prefs.getInt("freq", DEF_FREQ_MODE); }
    public int     getReadMode()      { return prefs.getInt("read_mode", DEF_READ_MODE); }
    public int     getSession()       { return prefs.getInt("sess", DEF_SESSION); }
    public int     getTarget()        { return prefs.getInt("target", DEF_TARGET); }
    public int     getQMode()         { return prefs.getInt("q_mode", DEF_Q_MODE); }
    public int     getQValue()        { return prefs.getInt("q_val", DEF_Q_VALUE); }
    public int     getMinQ()          { return prefs.getInt("q_min", DEF_MIN_Q); }
    public int     getMaxQ()          { return prefs.getInt("q_max", DEF_MAX_Q); }
    public int     getDR()            { return prefs.getInt("dr", DEF_DR); }
    public int     getMiller()        { return prefs.getInt("miller", DEF_MILLER); }
    public int     getLinkFreq()      { return prefs.getInt("link_freq", DEF_LINK_FREQ); }
    public int     getRfLink()        { return prefs.getInt("rflink", DEF_RFLINK); }
    public boolean isFastId()         { return prefs.getBoolean("fast_id", DEF_FAST_ID); }
    public boolean isTagFocus()       { return prefs.getBoolean("tag_focus", DEF_TAG_FOCUS); }
    public boolean isFastInventory()  { return prefs.getBoolean("fast_inv", DEF_FAST_INV); }
    public int     getRssiThreshold() { return prefs.getInt("rssi", DEF_RSSI_THRESHOLD); }
    public int     getScanInterval()  { return prefs.getInt("interval", DEF_SCAN_INTERVAL); }

    // ═══════════════════════════════════════════════════════════════
    //  Setters — كل واحد يستدعي notifyChanged()
    // ═══════════════════════════════════════════════════════════════
    public void setPowerDbm(int v) {
        prefs.edit().putInt("power", clamp(v, 5, 33)).commit();
        notifyChanged();
    }
    public void setFreqMode(int v) {
        prefs.edit().putInt("freq", v).commit();
        notifyChanged();
    }
    public void setReadMode(int v) {
        prefs.edit().putInt("read_mode", clamp(v, 0, 1)).commit();
        notifyChanged();
    }
    public void setSession(int v) {
        prefs.edit().putInt("sess", clamp(v, 0, 3)).commit();
        notifyChanged();
    }
    public void setTarget(int v) {
        prefs.edit().putInt("target", clamp(v, 0, 1)).commit();
        notifyChanged();
    }
    public void setQMode(int v) {
        prefs.edit().putInt("q_mode", clamp(v, 0, 1)).commit();
        notifyChanged();
    }
    public void setQValue(int v) {
        prefs.edit().putInt("q_val", clamp(v, 0, 15)).commit();
        notifyChanged();
    }
    public void setMinQ(int v) {
        prefs.edit().putInt("q_min", clamp(v, 0, 15)).commit();
        notifyChanged();
    }
    public void setMaxQ(int v) {
        prefs.edit().putInt("q_max", clamp(v, 0, 15)).commit();
        notifyChanged();
    }
    public void setDR(int v) {
        prefs.edit().putInt("dr", clamp(v, 0, 1)).commit();
        notifyChanged();
    }
    public void setMiller(int v) {
        prefs.edit().putInt("miller", clamp(v, 0, 3)).commit();
        notifyChanged();
    }
    public void setLinkFreq(int v) {
        prefs.edit().putInt("link_freq", clamp(v, 0, 7)).commit();
        notifyChanged();
    }
    public void setRfLink(int v) {
        prefs.edit().putInt("rflink", clamp(v, 0, 11)).commit();
        notifyChanged();
    }
    public void setFastId(boolean v) {
        prefs.edit().putBoolean("fast_id", v).commit();
        notifyChanged();
    }
    public void setTagFocus(boolean v) {
        prefs.edit().putBoolean("tag_focus", v).commit();
        notifyChanged();
    }
    public void setFastInventory(boolean v) {
        prefs.edit().putBoolean("fast_inv", v).commit();
        notifyChanged();
    }
    public void setRssiThreshold(int v) {
        prefs.edit().putInt("rssi", clamp(v, -100, -30)).commit();
        notifyChanged();
    }
    public void setScanInterval(int v) {
        prefs.edit().putInt("interval", clamp(v, 50, 1000)).commit();
        notifyChanged();
    }

    // ═══════════════════════════════════════════════════════════════
    //  ⚡ POS Power — Getters / Setters
    // ═══════════════════════════════════════════════════════════════
    public int getPosPowerDbm() {
        return prefs.getInt("pos_power", DEF_POS_POWER_DBM);
    }

    public void setPosPowerDbm(int v) {
        int safe = clamp(v, MIN_POS_POWER_DBM, MAX_POS_POWER_DBM);
        prefs.edit().putInt("pos_power", safe).commit();
        Log.i(TAG, "⚡ POS Power saved: " + safe + " dBm");
        notifyChanged();
    }

    public static String getPosRangeLabel(int dbm) {
        if (dbm <= 5)  return "~3-5 cm";
        if (dbm <= 8)  return "~5-8 cm";
        if (dbm <= 12) return "~8-12 cm";
        if (dbm <= 18) return "~10-18 cm";
        if (dbm <= 25) return "~18-30 cm";
        return "~30+ cm";
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ═══════════════════════════════════════════════════════════════
    //  ✅ تطبيق الإعدادات على القارئ
    // ═══════════════════════════════════════════════════════════════

    public static class ApplyResult {
        public final int applied, failed;
        public final List<String> failedNames = new ArrayList<>();
        public ApplyResult(int a, int f) { applied = a; failed = f; }
        public boolean isSuccess() { return failed == 0; }
        @Override public String toString() {
            return "applied=" + applied + " failed=" + failed
                    + (failed > 0 ? " " + failedNames : "");
        }
    }

    public ApplyResult applyToReader(RFIDWithUHFUART reader) {
        if (reader == null) return new ApplyResult(0, 0);

        Log.i(TAG, "════════ Applying MIRA Settings ════════");
        int applied = 0, failed = 0;
        List<String> failedNames = new ArrayList<>();

        // 1) Power
        try {
            boolean ok = reader.setPower(getPowerDbm());
            if (ok) { applied++; Log.i(TAG, "   ✅ setPower(" + getPowerDbm() + ")"); }
            else { failed++; failedNames.add("setPower"); }
        } catch (Exception e) { failed++; failedNames.add("setPower"); }

        // 2) Frequency Mode
        try {
            boolean ok = reader.setFrequencyMode(getFreqMode());
            if (ok) { applied++; Log.i(TAG, "   ✅ setFrequencyMode(0x" + Integer.toHexString(getFreqMode()) + ")"); }
            else { failed++; failedNames.add("setFrequencyMode"); }
        } catch (Exception e) { failed++; failedNames.add("setFrequencyMode"); }

        // 3) RFLink
        try {
            boolean ok = reader.setRFLink(getRfLink());
            if (ok) { applied++; Log.i(TAG, "   ✅ setRFLink(" + getRfLink() + ")"); }
            else { failed++; failedNames.add("setRFLink"); }
        } catch (Exception e) { failed++; failedNames.add("setRFLink"); }

        // 4) FastID
        try {
            boolean ok = reader.setFastID(isFastId());
            if (ok) { applied++; Log.i(TAG, "   ✅ setFastID(" + isFastId() + ")"); }
            else { failed++; failedNames.add("setFastID"); }
        } catch (Exception e) { failed++; failedNames.add("setFastID"); }

        // 5) TagFocus
        try {
            boolean ok = reader.setTagFocus(isTagFocus());
            if (ok) { applied++; Log.i(TAG, "   ✅ setTagFocus(" + isTagFocus() + ")"); }
            else { failed++; failedNames.add("setTagFocus"); }
        } catch (Exception e) { failed++; failedNames.add("setTagFocus"); }

        // 6) FastInventory — معطّل مؤقتاً (SDK يستخدم FastInventoryEntity)
        Log.i(TAG, "   ⏭️ FastInventoryMode skipped");

        // 7) Gen2
        try {
            Gen2Entity gen2 = reader.getGen2();
            if (gen2 == null) gen2 = new Gen2Entity();

            gen2.setQuerySession(getSession());
            gen2.setQueryTarget(getTarget());
            gen2.setQ(getQMode());
            gen2.setStartQ(getQValue());
            gen2.setMinQ(getMinQ());
            gen2.setMaxQ(getMaxQ());
            gen2.setQueryDR(getDR());
            gen2.setQueryM(getMiller());
            gen2.setQueryTRext(1);
            gen2.setQuerySel(0);
            gen2.setLinkFrequency(getLinkFreq());

            boolean ok = reader.setGen2(gen2);
            if (ok) { applied++; Log.i(TAG, "   ✅ setGen2(S=" + getSession()
                    + " T=" + getTarget() + " Q=" + getQValue() + ")"); }
            else { failed++; failedNames.add("setGen2"); }
        } catch (Exception e) {
            failed++; failedNames.add("setGen2");
            Log.e(TAG, "   ❌ setGen2", e);
        }

        // 8) EPC Mode
        try {
            reader.setEPCMode();
            applied++;
            Log.i(TAG, "   ✅ setEPCMode()");
        } catch (Exception e) { failed++; failedNames.add("setEPCMode"); }

        ApplyResult r = new ApplyResult(applied, failed);
        r.failedNames.addAll(failedNames);
        Log.i(TAG, "✅ " + r.toString());
        return r;
    }

    public void resetToDefaults() {
        prefs.edit().clear().commit();
        Log.i(TAG, "🔄 Reset to defaults");
        notifyChanged();
    }

    // ═══════════════════════════════════════════════════════════════
    //  ملخص للعرض
    // ═══════════════════════════════════════════════════════════════
    public String getSummary() {
        return "⚡ Power: " + getPowerDbm() + " dBm\n"
             + "⚡ POS Power: " + getPosPowerDbm() + " dBm\n"
             + "🌍 Freq: " + getFreqLabel(getFreqMode()) + "\n"
             + "🎯 ReadMode: " + getReadModeLabel(getReadMode()) + "\n"
             + "📊 Session: S" + getSession() + "\n"
             + "🎯 Target: " + (getTarget() == 0 ? "A" : "B") + "\n"
             + "🔢 Q: " + getQValue() + " (" + (getQMode() == 0 ? "fixed" : "dynamic") + ")\n"
             + "📶 RSSI: " + getRssiThreshold() + " dBm\n"
             + "🔗 RFLink: " + (getRfLink() < RFLINK_LABELS.length
                    ? RFLINK_LABELS[getRfLink()] : "?") + "\n"
             + "🚀 FastID: " + (isFastId() ? "ON" : "OFF") + "\n"
             + "🎯 TagFocus: " + (isTagFocus() ? "ON" : "OFF");
    }

    public static String getFreqLabel(int mode) {
        for (int i = 0; i < FREQ_MODES.length; i++) {
            if (FREQ_MODES[i][0] == mode) return FREQ_LABELS[i];
        }
        return "Unknown(0x" + Integer.toHexString(mode) + ")";
    }

    public static String getReadModeLabel(int mode) {
        if (mode < 0 || mode >= READ_MODE_LABELS.length) return "?";
        return READ_MODE_LABELS[mode];
    }
}
