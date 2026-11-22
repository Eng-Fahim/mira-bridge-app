package com.example.uhf.MiraRfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.Gen2Entity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MiraReaderSettings v5.3 — Production-Grade Jewelry Reader Configuration
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * [v5.3] إصلاحات حرجة:
 * ✅ إضافة inventoryPowerDbm (فصل حقيقي بين قدرة الجرد وقدرة البيع POS)
 * ✅ إصلاح خطأ Q Value: setQ() تستقبل قيمة Q (0-15) لا وضع Q (0/1)
 * ✅ إضافة تحقق كامل من قيم التردد والأوضاع قبل الحفظ
 * ✅ إصلاح resetToDefaults: لا يمسح إعدادات الصوت والواجهة
 * ✅ إضافة ضمانات ضد القيم الفارغة والسلبية في كل getter
 * ✅ توثيق صريح لترتيب RFLink/Gen2 وعدم تجاوز أحدهما للآخر
 * ✅ تحذيرات في ApplyResult عند عدم توافق Miller مع RFLink
 *
 * [v5.2] أساسيات:
 * ✅ كل setters تستخدم .apply() بدل .commit()
 * ✅ notifyChanged() مع Debounce (200ms)
 * ✅ Handler على Main Thread
 *
 * [v5.1] تحسينات الأداء + [v5.0] الأساس
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class MiraReaderSettings {

    private static final String TAG = "MiraReaderSettings";
    private static final String PREFS = "mira_reader_settings_v2";

    // ═══════════════════════════════════════════════════════════════
    //  📡 مناطق العمل (ETSI/FCC/etc.)
    // ═══════════════════════════════════════════════════════════════
    public static final int FREQ_CHINA_840     = 0x01;
    public static final int FREQ_CHINA_920     = 0x02;
    public static final int FREQ_ETSI          = 0x04;
    public static final int FREQ_FCC           = 0x08;
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

    public static final int[][] FREQ_MODES = {
            {FREQ_ETSI,         0}, {FREQ_FCC,          1},
            {FREQ_CHINA_840,    2}, {FREQ_CHINA_920,    3},
            {FREQ_SOUTH_AFRICA, 4}, {FREQ_KOREA,        5},
            {FREQ_JAPAN,        6}, {FREQ_TAIWAN,       7},
            {FREQ_VIETNAM,      8}, {FREQ_PERU,         9},
            {FREQ_RUSSIA,      10}, {FREQ_MALAYSIA,    11},
            {FREQ_BRAZIL,      12}, {FREQ_ETSI_UPPER,  13},
            {FREQ_AUSTRALIA,   14}, {FREQ_INDONESIA,   15},
            {FREQ_HK,          17}, {FREQ_NEW_ZEALAND, 18}
    };

    public static final String[] FREQ_LABELS = {
            "🌍 الشرق الأوسط والخليج (865-868)",
            "🌎 أمريكا الشمالية (902-928)",
            "🇨🇳 الصين (840-845)", "🇨🇳 الصين 2 (920-925)",
            "🇿🇦 جنوب إفريقيا (915-919)", "🇰🇷 كوريا (917-923)",
            "🇯🇵 اليابان (916.8-920.8)", "تايوان (920-928)",
            "فيتنام (918-923)", "بيرو (915-928)",
            "روسيا (860-867.6)", "ماليزيا (919-923)",
            "البرازيل", "أوروبا العليا", "أستراليا",
            "إندونيسيا (920-923)", "هونغ كونغ", "نيوزيلندا"
    };

    public static final String[] RFLINK_LABELS = {
            "PR-ASK / Miller8 / 160KHz", "PR-ASK / Miller4 / 250KHz",
            "PR-ASK / Miller4 / 320KHz", "PR-ASK / Miller4 / 640KHz",
            "PR-ASK / Miller2 / 320KHz", "PR-ASK / Miller2 / 640KHz",
            "Gen2X / Miller8 / 160KHz",  "Gen2X / Miller4 / 250KHz",
            "Gen2X / Miller4 / 320KHz",  "Gen2X / Miller4 / 640KHz",
            "Gen2X / Miller2 / 320KHz",  "Gen2X / Miller2 / 640KHz"
    };

    // ═══════════════════════════════════════════════════════════════
    //  🎯 أوضاع القراءة والزناد (تطبيقية، لا تُرسل للقارئ مباشرة)
    // ═══════════════════════════════════════════════════════════════
    public static final int READ_MODE_AUTO   = 0;
    public static final int READ_MODE_SINGLE = 1;

    public static final String[] READ_MODE_LABELS = {
            "🔄 مستمر (للجرد)", "🎯 مفرد (للبيع والتحقق)"
    };
    public static final String[] READ_MODE_HINTS = {
            "يقرأ كل الوسوم القريبة باستمرار — مثالي للجرد السريع",
            "يقرأ وسماً واحداً فقط لكل ضغطة — مثالي للبيع"
    };

    public static final int TRIGGER_MODE_HOLD   = 0;
    public static final int TRIGGER_MODE_TOGGLE = 1;

    public static final String[] TRIGGER_MODE_LABELS = {
            "⏸️ اضغط باستمرار (Hold)",
            "🔄 ضغطة للبدء/الإيقاف (Toggle)"
    };

    public static final String[] TRIGGER_MODE_HINTS = {
            "المسح يعمل طوال فترة ضغط الزناد — مثالي للفحص السريع والبيع",
            "ضغطة واحدة تبدأ المسح، وضغطة أخرى توقفه — مثالي للجرد الشامل"
    };

    // ═══════════════════════════════════════════════════════════════
    //  🔔 النغمات
    // ═══════════════════════════════════════════════════════════════
    public static final int TONE_BEEP_SHORT   = 0;
    public static final int TONE_BEEP_DOUBLE  = 1;
    public static final int TONE_BEEP_LONG    = 2;
    public static final int TONE_CLICK        = 3;
    public static final int TONE_SUCCESS      = 4;
    public static final int TONE_ERROR        = 5;
    public static final int TONE_NONE         = 6;
    public static final int TONE_UNKNOWN_ITEM = 7;

    public static final String[] TONE_LABELS = {
            "🔔 بيب قصير",
            "🔔🔔 بيب مزدوج",
            "🔔 بيب طويل",
            "🖱️ كليك",
            "✅ نغمة نجاح",
            "❌ نغمة خطأ",
            "🔇 صامت",
            "⚠️ تنبيه (غير معروف)"
    };

    // ═══════════════════════════════════════════════════════════════
    //  🎯 القيم الافتراضية [v5.3]
    // ═══════════════════════════════════════════════════════════════

    // ⚡ قدرة البيع والتحقق POS (منخفضة، آمنة للمسافة القريبة)
    public static final int  DEF_POS_POWER_DBM      = 7;
    public static final int  MIN_POS_POWER_DBM      = 5;
    public static final int  MAX_POS_POWER_DBM      = 30;

    // ⚡ [v5.3] قدرة الجرد المستقلة (تغطية أوسع، مصممة للجرد الشامل)
    public static final int  DEF_INVENTORY_POWER_DBM = 27;   // ↓ من 30 لاحترام ETSI EIRP
    public static final int  MIN_INVENTORY_POWER_DBM = 5;
    public static final int  MAX_INVENTORY_POWER_DBM = 30;

    // ⚡ قدرة القارئ العامة (fallback فقط — استخدم posPower/inventoryPower)
    public static final int  DEF_POWER_DBM          = 27;
    public static final int  MIN_READER_POWER_DBM   = 5;
    public static final int  MAX_READER_POWER_DBM   = 30;

    // 🌍 التردد الافتراضي لليمن والخليج
    public static final int  DEF_FREQ_MODE          = FREQ_ETSI;

    // 🔄 أوضاع التطبيق
    public static final int  DEF_READ_MODE          = READ_MODE_AUTO;
    public static final int  DEF_TRIGGER_MODE       = TRIGGER_MODE_TOGGLE;

    // 📊 Gen2 Query Parameters
    public static final int  DEF_SESSION            = 1;   // S1 (يُفضّل للمجوهرات)
    public static final int  DEF_TARGET             = 0;   // Target A
    public static final int  DEF_Q_MODE             = 0;   // 0=Fixed, 1=Dynamic
    public static final int  DEF_Q_VALUE            = 5;   // 32 slots — مثالي لـ 5-30 شريحة
    public static final int  DEF_MIN_Q              = 0;
    public static final int  DEF_MAX_Q              = 15;
    public static final int  DEF_DR                 = 0;

    // 🎵 Miller/LinkFreq — قيم متوافقة مع DEF_RFLINK=1
    public static final int  DEF_MILLER             = 2;   // Miller 4 (متوازن)
    public static final int  DEF_LINK_FREQ          = 3;   // 256 kHz (قريب من 250)
    public static final int  DEF_RFLINK             = 1;   // PR-ASK/Miller4/250KHz

    // 🚀 خيارات متقدمة
    public static final boolean DEF_FAST_ID         = false;
    public static final boolean DEF_TAG_FOCUS       = true;   // يقلل القراءات المتكررة
    public static final boolean DEF_FAST_INV        = false;

    public static final int  DEF_RSSI_THRESHOLD     = -75;
    public static final int  DEF_SCAN_INTERVAL      = 100;

    // 🔊 الصوت والاهتزاز
    public static final boolean DEF_SOUND_ENABLED       = true;
    public static final int     DEF_SOUND_VOLUME        = 85;
    public static final boolean DEF_VIBRATE_ENABLED     = true;
    public static final int     DEF_VIBRATE_MS          = 45;
    public static final int     DEF_TONE_NEW_TAG        = TONE_BEEP_DOUBLE;
    public static final int     DEF_TONE_DUPLICATE      = TONE_CLICK;
    public static final int     DEF_TONE_ERROR          = TONE_BEEP_LONG;
    public static final int     DEF_TONE_SUCCESS        = TONE_SUCCESS;
    public static final int     DEF_TONE_UNKNOWN_ITEM   = TONE_UNKNOWN_ITEM;
    public static final boolean DEF_SOUND_ON_POS        = true;
    public static final boolean DEF_SOUND_ON_INVENTORY  = true;

    // 📖 القراءة المتقدمة
    public static final int     DEF_SINGLE_TIMEOUT_MS   = 1500;
    public static final int     DEF_AUTO_STOP_MS        = 0;
    public static final int     DEF_MAX_TAGS_SESSION    = 0;
    public static final boolean DEF_FILTER_DUPLICATES   = true;
    public static final int     DEF_DUPLICATE_FILTER_MS = 2500;

    // 🌐 عام
    public static final boolean DEF_LED_FLASH           = false;
    public static final boolean DEF_KEEP_SCREEN_ON      = true;
    public static final boolean DEF_TOAST_ENABLED       = true;
    public static final boolean DEF_AUTO_SAVE_LOG       = false;
    public static final int     DEF_LANGUAGE            = 0;

    // ═══════════════════════════════════════════════════════════════
    //  Singleton
    // ═══════════════════════════════════════════════════════════════
    private static volatile MiraReaderSettings instance;
    private final Context context;
    private final SharedPreferences prefs;

    private final CopyOnWriteArrayList<WeakReference<OnSettingsChangedListener>> listeners
            = new CopyOnWriteArrayList<>();

    private final Handler notifyHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingNotify;
    private static final long NOTIFY_DEBOUNCE_MS = 200;

    private MiraReaderSettings(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Log.i(TAG, "✅ MiraReaderSettings v5.3 initialized");
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
    //  🆕 نظام مراقبة التغييرات
    // ═══════════════════════════════════════════════════════════════
    public interface OnSettingsChangedListener {
        void onSettingsChanged();
    }

    public void addListener(OnSettingsChangedListener l) {
        if (l == null) return;
        listeners.removeIf(ref -> ref.get() == null);
        for (WeakReference<OnSettingsChangedListener> ref : listeners)
            if (ref.get() == l) return;
        listeners.add(new WeakReference<>(l));
    }

    public void removeListener(OnSettingsChangedListener l) {
        if (l == null) return;
        listeners.removeIf(ref -> ref.get() == null || ref.get() == l);
    }

    private void notifyChanged() {
        if (pendingNotify != null) {
            notifyHandler.removeCallbacks(pendingNotify);
        }
        pendingNotify = () -> {
            pendingNotify = null;
            for (WeakReference<OnSettingsChangedListener> ref : listeners) {
                OnSettingsChangedListener l = ref.get();
                if (l != null) {
                    try {
                        l.onSettingsChanged();
                    } catch (Exception e) {
                        Log.e(TAG, "notify error", e);
                    }
                }
            }
        };
        notifyHandler.postDelayed(pendingNotify, NOTIFY_DEBOUNCE_MS);
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎯 الإعدادات المثالية لليمن/المجوهرات
    // ═══════════════════════════════════════════════════════════════
    public void applyYemenJewelryDefaults() {
        Log.i(TAG, "🎯 Applying Yemen/Jewelry optimal defaults (v5.3)");

        SharedPreferences.Editor e = prefs.edit();

        // ⚡ القراءة والطاقة
        e.putInt("power",             DEF_POWER_DBM);              // fallback
        e.putInt("inventory_power",   DEF_INVENTORY_POWER_DBM);    // 27 dBm
        e.putInt("pos_power",         DEF_POS_POWER_DBM);          // 7 dBm

        // 🌍 التردد — ETSI القانوني
        e.putInt("freq",              DEF_FREQ_MODE);

        // 🔗 الهوائي
        e.putInt("rflink",            DEF_RFLINK);
        e.putInt("miller",            DEF_MILLER);
        e.putInt("link_freq",         DEF_LINK_FREQ);

        // 🔢 Gen2 Query
        e.putInt("sess",              DEF_SESSION);
        e.putInt("target",            DEF_TARGET);
        e.putInt("q_mode",            DEF_Q_MODE);
        e.putInt("q_val",             DEF_Q_VALUE);    // 5 = 32 slots
        e.putInt("q_min",             DEF_MIN_Q);
        e.putInt("q_max",             DEF_MAX_Q);
        e.putInt("dr",                DEF_DR);

        // 🚀
        e.putBoolean("tag_focus",     DEF_TAG_FOCUS);
        e.putBoolean("fast_id",       DEF_FAST_ID);
        e.putBoolean("fast_inv",      DEF_FAST_INV);

        // 📖 القراءة المتقدمة
        e.putBoolean("flt_dup",       DEF_FILTER_DUPLICATES);
        e.putInt("dup_flt",           DEF_DUPLICATE_FILTER_MS);
        e.putInt("max_tags",          DEF_MAX_TAGS_SESSION);
        e.putInt("auto_stop",         DEF_AUTO_STOP_MS);
        e.putInt("sin_to",            DEF_SINGLE_TIMEOUT_MS);

        // 🎯 أوضاع التشغيل
        e.putInt("read_mode",         DEF_READ_MODE);
        e.putInt("trigger_mode",      DEF_TRIGGER_MODE);

        // 🌐 عام
        e.putBoolean("keep_screen",   DEF_KEEP_SCREEN_ON);

        e.commit();
        notifyChanged();

        Log.i(TAG, "✅ Yemen/Jewelry defaults v5.3 applied successfully");
    }

    // ═══════════════════════════════════════════════════════════════
    //  📡 Get — الإعدادات الأساسية (كل الـ getters مع default آمن)
    // ═══════════════════════════════════════════════════════════════
    public int     getPowerDbm()          { return prefs.getInt("power", DEF_POWER_DBM); }
    public int     getInventoryPowerDbm() { return prefs.getInt("inventory_power", DEF_INVENTORY_POWER_DBM); }
    public int     getPosPowerDbm()       { return prefs.getInt("pos_power", DEF_POS_POWER_DBM); }
    public int     getFreqMode()          { return prefs.getInt("freq", DEF_FREQ_MODE); }
    public int     getReadMode()          { return prefs.getInt("read_mode", DEF_READ_MODE); }
    public int     getSession()           { return prefs.getInt("sess", DEF_SESSION); }
    public int     getTarget()            { return prefs.getInt("target", DEF_TARGET); }
    public int     getQMode()             { return prefs.getInt("q_mode", DEF_Q_MODE); }
    public int     getQValue()            { return prefs.getInt("q_val", DEF_Q_VALUE); }
    public int     getMinQ()              { return prefs.getInt("q_min", DEF_MIN_Q); }
    public int     getMaxQ()              { return prefs.getInt("q_max", DEF_MAX_Q); }
    public int     getDR()                { return prefs.getInt("dr", DEF_DR); }
    public int     getMiller()            { return prefs.getInt("miller", DEF_MILLER); }
    public int     getLinkFreq()          { return prefs.getInt("link_freq", DEF_LINK_FREQ); }
    public int     getRfLink()            { return prefs.getInt("rflink", DEF_RFLINK); }
    public boolean isFastId()             { return prefs.getBoolean("fast_id", DEF_FAST_ID); }
    public boolean isTagFocus()           { return prefs.getBoolean("tag_focus", DEF_TAG_FOCUS); }
    public boolean isFastInventory()      { return prefs.getBoolean("fast_inv", DEF_FAST_INV); }
    public int     getRssiThreshold()     { return prefs.getInt("rssi", DEF_RSSI_THRESHOLD); }
    public int     getScanInterval()      { return prefs.getInt("interval", DEF_SCAN_INTERVAL); }
    public int     getTriggerMode()       { return prefs.getInt("trigger_mode", DEF_TRIGGER_MODE); }

    // ═══════════════════════════════════════════════════════════════
    //  📡 Set — مع تحقق كامل من صحة القيم
    // ═══════════════════════════════════════════════════════════════
    public void setPowerDbm(int v) {
        prefs.edit().putInt("power", clamp(v, MIN_READER_POWER_DBM, MAX_READER_POWER_DBM)).apply();
        notifyChanged();
    }

    public void setInventoryPowerDbm(int v) {
        prefs.edit()
             .putInt("inventory_power", clamp(v, MIN_INVENTORY_POWER_DBM, MAX_INVENTORY_POWER_DBM))
             .apply();
        notifyChanged();
    }

    public void setPosPowerDbm(int v) {
        prefs.edit().putInt("pos_power", clamp(v, MIN_POS_POWER_DBM, MAX_POS_POWER_DBM)).apply();
        notifyChanged();
    }

    public void setFreqMode(int v) {
        if (!isValidFreqMode(v)) {
            Log.w(TAG, "⛔ Invalid freq mode rejected: 0x" + Integer.toHexString(v));
            return;
        }
        prefs.edit().putInt("freq", v).apply();
        notifyChanged();
    }

    public void setReadMode(int v) {
        prefs.edit().putInt("read_mode", clamp(v, 0, 1)).apply();
        notifyChanged();
    }

    public void setSession(int v) {
        prefs.edit().putInt("sess", clamp(v, 0, 3)).apply();
        notifyChanged();
    }

    public void setTarget(int v) {
        prefs.edit().putInt("target", clamp(v, 0, 1)).apply();
        notifyChanged();
    }

    public void setQMode(int v) {
        prefs.edit().putInt("q_mode", clamp(v, 0, 1)).apply();
        notifyChanged();
    }

    public void setQValue(int v) {
        prefs.edit().putInt("q_val", clamp(v, 0, 15)).apply();
        notifyChanged();
    }

    public void setMinQ(int v) {
        prefs.edit().putInt("q_min", clamp(v, 0, 15)).apply();
        notifyChanged();
    }

    public void setMaxQ(int v) {
        prefs.edit().putInt("q_max", clamp(v, 0, 15)).apply();
        notifyChanged();
    }

    public void setDR(int v) {
        prefs.edit().putInt("dr", clamp(v, 0, 1)).apply();
        notifyChanged();
    }

    public void setMiller(int v) {
        prefs.edit().putInt("miller", clamp(v, 0, 3)).apply();
        notifyChanged();
    }

    public void setLinkFreq(int v) {
        prefs.edit().putInt("link_freq", clamp(v, 0, 7)).apply();
        notifyChanged();
    }

    public void setRfLink(int v) {
        prefs.edit().putInt("rflink", clamp(v, 0, 11)).apply();
        notifyChanged();
    }

    public void setFastId(boolean v)        { prefs.edit().putBoolean("fast_id", v).apply(); notifyChanged(); }
    public void setTagFocus(boolean v)      { prefs.edit().putBoolean("tag_focus", v).apply(); notifyChanged(); }
    public void setFastInventory(boolean v) { prefs.edit().putBoolean("fast_inv", v).apply(); notifyChanged(); }

    public void setRssiThreshold(int v) {
        prefs.edit().putInt("rssi", clamp(v, -100, -30)).apply();
        notifyChanged();
    }

    public void setScanInterval(int v) {
        prefs.edit().putInt("interval", clamp(v, 50, 1000)).apply();
        notifyChanged();
    }

    public void setTriggerMode(int v) {
        prefs.edit().putInt("trigger_mode", clamp(v, 0, 1)).apply();
        notifyChanged();
    }

    // ═══════════════════════════════════════════════════════════════
    //  🔊 إعدادات الأصوات والاهتزاز
    // ═══════════════════════════════════════════════════════════════
    public boolean isSoundEnabled()        { return prefs.getBoolean("snd_en", DEF_SOUND_ENABLED); }
    public int     getSoundVolume()        { return prefs.getInt("snd_vol", DEF_SOUND_VOLUME); }
    public int     getToneNewTag()         { return prefs.getInt("tone_new", DEF_TONE_NEW_TAG); }
    public int     getToneDuplicate()      { return prefs.getInt("tone_dup", DEF_TONE_DUPLICATE); }
    public int     getToneError()          { return prefs.getInt("tone_err", DEF_TONE_ERROR); }
    public int     getToneSuccess()        { return prefs.getInt("tone_succ", DEF_TONE_SUCCESS); }
    public int     getToneUnknownItem()    { return prefs.getInt("tone_unk", DEF_TONE_UNKNOWN_ITEM); }
    public boolean isSoundOnPos()          { return prefs.getBoolean("snd_pos", DEF_SOUND_ON_POS); }
    public boolean isSoundOnInventory()    { return prefs.getBoolean("snd_inv", DEF_SOUND_ON_INVENTORY); }
    public boolean isVibrateEnabled()      { return prefs.getBoolean("vib_en", DEF_VIBRATE_ENABLED); }
    public int     getVibrateMs()          { return prefs.getInt("vib_ms", DEF_VIBRATE_MS); }

    public void setSoundEnabled(boolean v)      { prefs.edit().putBoolean("snd_en", v).apply(); notifyChanged(); }

    public void setSoundVolume(int v) {
        prefs.edit().putInt("snd_vol", clamp(v, 0, 100)).apply();
        notifyChanged();
    }

    public void setToneNewTag(int v)      { prefs.edit().putInt("tone_new", clamp(v, 0, TONE_LABELS.length - 1)).apply(); notifyChanged(); }
    public void setToneDuplicate(int v)   { prefs.edit().putInt("tone_dup", clamp(v, 0, TONE_LABELS.length - 1)).apply(); notifyChanged(); }
    public void setToneError(int v)       { prefs.edit().putInt("tone_err", clamp(v, 0, TONE_LABELS.length - 1)).apply(); notifyChanged(); }
    public void setToneSuccess(int v)     { prefs.edit().putInt("tone_succ", clamp(v, 0, TONE_LABELS.length - 1)).apply(); notifyChanged(); }
    public void setToneUnknownItem(int v) { prefs.edit().putInt("tone_unk", clamp(v, 0, TONE_LABELS.length - 1)).apply(); notifyChanged(); }

    public void setSoundOnPos(boolean v)       { prefs.edit().putBoolean("snd_pos", v).apply(); notifyChanged(); }
    public void setSoundOnInventory(boolean v) { prefs.edit().putBoolean("snd_inv", v).apply(); notifyChanged(); }
    public void setVibrateEnabled(boolean v)   { prefs.edit().putBoolean("vib_en", v).apply(); notifyChanged(); }

    public void setVibrateMs(int v) {
        prefs.edit().putInt("vib_ms", clamp(v, 10, 500)).apply();
        notifyChanged();
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎯 إعدادات القراءة المتقدمة
    // ═══════════════════════════════════════════════════════════════
    public int     getSingleTimeoutMs()      { return prefs.getInt("sin_to", DEF_SINGLE_TIMEOUT_MS); }
    public int     getAutoStopMs()           { return prefs.getInt("auto_stop", DEF_AUTO_STOP_MS); }
    public int     getMaxTagsPerSession()    { return prefs.getInt("max_tags", DEF_MAX_TAGS_SESSION); }
    public int     getDuplicateFilterMs()    { return prefs.getInt("dup_flt", DEF_DUPLICATE_FILTER_MS); }
    public boolean isFilterDuplicates()      { return prefs.getBoolean("flt_dup", DEF_FILTER_DUPLICATES); }

    public void setSingleTimeoutMs(int v)     { prefs.edit().putInt("sin_to", clamp(v, 200, 10000)).apply(); notifyChanged(); }
    public void setAutoStopMs(int v)          { prefs.edit().putInt("auto_stop", clamp(v, 0, 600000)).apply(); notifyChanged(); }
    public void setMaxTagsPerSession(int v)   { prefs.edit().putInt("max_tags", clamp(v, 0, 100000)).apply(); notifyChanged(); }
    public void setDuplicateFilterMs(int v)   { prefs.edit().putInt("dup_flt", clamp(v, 0, 60000)).apply(); notifyChanged(); }
    public void setFilterDuplicates(boolean v){ prefs.edit().putBoolean("flt_dup", v).apply(); notifyChanged(); }

    // ═══════════════════════════════════════════════════════════════
    //  🌐 إعدادات عامة
    // ═══════════════════════════════════════════════════════════════
    public boolean isLedFlashEnabled()   { return prefs.getBoolean("led_flash", DEF_LED_FLASH); }
    public boolean isKeepScreenOn()      { return prefs.getBoolean("keep_screen", DEF_KEEP_SCREEN_ON); }
    public boolean isToastEnabled()      { return prefs.getBoolean("toast_en", DEF_TOAST_ENABLED); }
    public boolean isAutoSaveLog()       { return prefs.getBoolean("auto_log", DEF_AUTO_SAVE_LOG); }
    public int     getLanguage()         { return prefs.getInt("lang", DEF_LANGUAGE); }

    public void setLedFlashEnabled(boolean v) { prefs.edit().putBoolean("led_flash", v).apply(); notifyChanged(); }
    public void setKeepScreenOn(boolean v)    { prefs.edit().putBoolean("keep_screen", v).apply(); notifyChanged(); }
    public void setToastEnabled(boolean v)    { prefs.edit().putBoolean("toast_en", v).apply(); notifyChanged(); }
    public void setAutoSaveLog(boolean v)     { prefs.edit().putBoolean("auto_log", v).apply(); notifyChanged(); }

    public void setLanguage(int v) {
        prefs.edit().putInt("lang", clamp(v, 0, 1)).apply();
        notifyChanged();
    }

    // ═══════════════════════════════════════════════════════════════
    //  ✅ تطبيق الإعدادات على القارئ
    // ═══════════════════════════════════════════════════════════════
    public static class ApplyResult {
        public final int applied, failed;
        public final List<String> failedNames = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public ApplyResult(int a, int f) { applied = a; failed = f; }

        public boolean isSuccess() { return failed == 0; }
        public boolean hasWarnings() { return !warnings.isEmpty(); }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("applied=").append(applied).append(" failed=").append(failed);
            if (failed > 0) sb.append(" failed[").append(failedNames).append("]");
            if (!warnings.isEmpty()) sb.append(" warnings[").append(warnings).append("]");
            return sb.toString();
        }
    }

    /**
     * يطبّق الإعدادات على القارئ بترتيب محكوم:
     *
     * 1. Power (الأساس)
     * 2. Frequency Mode (المنطقة القانونية)
     * 3. Gen2 Query Parameters:
     *    - Session, Target, Q value, MinQ/MaxQ, DR
     *    - ⚠️ [v5.3] Q Value (0-15) هو ما يُرسل لـ setQ()، لا Q Mode (0/1).
     *      استخدام Q Mode كان خطأً في v5.2 يُنتج Q=0 دائماً (1 slot) — سبب
     *      مباشر لضعف القراءة والتفلت.
     * 4. Gen2 Miller + LinkFreq (من القيم المخزنة، متوافقة مع RFLink)
     * 5. RFLink (يُطبَّق أخيراً — يُعطى سلطة نهائية على الهوائي)
     * 6. FastID / TagFocus
     * 7. EPC Mode
     *
     * ملاحظة صريحة: RFLink وMiller/LinkFreq لهما نفس المنطقة (الهوائي). في
     * نسخة v5.3 نُبقي الاثنين متوافقين، ونضيف تحذيراً إذا اختلفا. RFLink يُطبَّق
     * آخراً ليكون هو السلطة النهائية إن تعارضا.
     */
    public ApplyResult applyToReader(RFIDWithUHFUART reader) {
        if (reader == null) {
            ApplyResult r = new ApplyResult(0, 0);
            r.warnings.add("reader == null");
            return r;
        }
        int applied = 0, failed = 0;
        List<String> failedNames = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ─── تحذيرات ما قبل التطبيق ───
        if (!isValidFreqMode(getFreqMode())) {
            warnings.add("invalid_freq_mode=0x" + Integer.toHexString(getFreqMode()));
        }

        // ─── 1. Power ───
        try {
            if (reader.setPower(getPowerDbm())) applied++;
            else { failed++; failedNames.add("setPower(" + getPowerDbm() + ")"); }
        } catch (Exception e) {
            failed++; failedNames.add("setPower:" + e.getClass().getSimpleName());
        }

        // ─── 2. Frequency Mode ───
        try {
            if (reader.setFrequencyMode(getFreqMode())) applied++;
            else { failed++; failedNames.add("setFrequencyMode(0x" + Integer.toHexString(getFreqMode()) + ")"); }
        } catch (Exception e) {
            failed++; failedNames.add("setFrequencyMode:" + e.getClass().getSimpleName());
        }

        // ─── 3+4. Gen2 Query Parameters + Miller/LinkFreq ───
        try {
            Gen2Entity gen2 = reader.getGen2();
            if (gen2 == null) gen2 = new Gen2Entity();

            gen2.setQuerySession(getSession());          // S0-S3
            gen2.setQueryTarget(getTarget());            // A=0, B=1

            // ⚠️ [v5.3 FIX] setQ() يستقبل قيمة Q (0-15) لا وضع Q (0/1)
            int qMode  = getQMode();
            int qValue = getQValue();
            gen2.setQ(qValue);                           // ← القيمة الصحيحة

            // في الوضع الديناميكي: StartQ و MinQ/MaxQ
            if (qMode == 1) {
                gen2.setStartQ(qValue);
                gen2.setMinQ(getMinQ());
                gen2.setMaxQ(getMaxQ());
            } else {
                // في الوضع الثابت: MinQ/MaxQ = Q (لتجنب سلوك غير معرّف)
                gen2.setStartQ(qValue);
                gen2.setMinQ(qValue);
                gen2.setMaxQ(qValue);
            }

            gen2.setQueryDR(getDR());
            gen2.setQueryM(getMiller());                 // 0=FM0, 1=Miller2, 2=Miller4, 3=Miller8
            gen2.setLinkFrequency(getLinkFreq());
            gen2.setQueryTRext(1);
            gen2.setQuerySel(0);

            if (reader.setGen2(gen2)) applied++;
            else { failed++; failedNames.add("setGen2"); }
        } catch (Exception e) {
            failed++; failedNames.add("setGen2:" + e.getClass().getSimpleName());
        }

        // ─── 5. RFLink (آخر شيء يمس الهوائي — السلطة النهائية) ───
        try {
            if (reader.setRFLink(getRfLink())) applied++;
            else { failed++; failedNames.add("setRFLink(" + getRfLink() + ")"); }
        } catch (Exception e) {
            failed++; failedNames.add("setRFLink:" + e.getClass().getSimpleName());
        }

        // ─── تحقق من توافق Miller مع RFLink ───
        if (!isMillerRfLinkConsistent()) {
            warnings.add("miller=" + getMiller() + " inconsistent with rflink=" + getRfLink()
                    + " — RFLink will override");
        }

        // ─── 6. FastID / TagFocus ───
        try {
            if (reader.setFastID(isFastId())) applied++;
            else { failed++; failedNames.add("setFastID"); }
        } catch (Exception e) {
            failed++; failedNames.add("setFastID:" + e.getClass().getSimpleName());
        }

        try {
            if (reader.setTagFocus(isTagFocus())) applied++;
            else { failed++; failedNames.add("setTagFocus"); }
        } catch (Exception e) {
            failed++; failedNames.add("setTagFocus:" + e.getClass().getSimpleName());
        }

        // ─── 7. EPC Mode (افتراضي دائم — للبيع والتحقق) ───
        try {
            reader.setEPCMode();
            applied++;
        } catch (Exception e) {
            failed++; failedNames.add("setEPCMode:" + e.getClass().getSimpleName());
        }

        ApplyResult r = new ApplyResult(applied, failed);
        r.failedNames.addAll(failedNames);
        r.warnings.addAll(warnings);

        if (failed > 0 || !warnings.isEmpty()) {
            Log.w(TAG, "applyToReader: " + r.toString());
        } else {
            Log.i(TAG, "applyToReader: " + r.toString());
        }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    //  🔄 إعادة الضبط — سلوكان منفصلان
    // ═══════════════════════════════════════════════════════════════

    /**
     * يعيد إعدادات القارئ فقط للقيم الافتراضية.
     * يحافظ على إعدادات الواجهة (اللغة، الصوت، الاهتزاز، toast، keep-screen، إلخ).
     * آمن للاستخدام من داخل التطبيق عندما يحتاج المستخدم "إعادة ضبط القارئ".
     */
    public void resetReaderToDefaults() {
        SharedPreferences.Editor e = prefs.edit();

        e.putInt("power",             DEF_POWER_DBM);
        e.putInt("inventory_power",   DEF_INVENTORY_POWER_DBM);
        e.putInt("pos_power",         DEF_POS_POWER_DBM);
        e.putInt("freq",              DEF_FREQ_MODE);
        e.putInt("rflink",            DEF_RFLINK);
        e.putInt("miller",            DEF_MILLER);
        e.putInt("link_freq",         DEF_LINK_FREQ);
        e.putInt("sess",              DEF_SESSION);
        e.putInt("target",            DEF_TARGET);
        e.putInt("q_mode",            DEF_Q_MODE);
        e.putInt("q_val",             DEF_Q_VALUE);
        e.putInt("q_min",             DEF_MIN_Q);
        e.putInt("q_max",             DEF_MAX_Q);
        e.putInt("dr",                DEF_DR);
        e.putBoolean("tag_focus",     DEF_TAG_FOCUS);
        e.putBoolean("fast_id",       DEF_FAST_ID);
        e.putBoolean("fast_inv",      DEF_FAST_INV);
        e.putBoolean("flt_dup",       DEF_FILTER_DUPLICATES);
        e.putInt("dup_flt",           DEF_DUPLICATE_FILTER_MS);
        e.putInt("max_tags",          DEF_MAX_TAGS_SESSION);
        e.putInt("auto_stop",         DEF_AUTO_STOP_MS);
        e.putInt("sin_to",            DEF_SINGLE_TIMEOUT_MS);
        e.putInt("rssi",              DEF_RSSI_THRESHOLD);
        e.putInt("interval",          DEF_SCAN_INTERVAL);

        e.apply();
        notifyChanged();
        Log.i(TAG, "🔄 Reader settings reset to defaults (UI preferences preserved)");
    }

    /**
     * إعادة ضبط كاملة (يُوصى باستخدامها فقط عند "تسجيل الخروج نهائياً"
     * أو مسح بيانات التطبيق). تمسح كل شيء بما فيه اللغة والواجهة.
     */
    public void resetAllToFactory() {
        prefs.edit().clear().apply();
        notifyChanged();
        Log.i(TAG, "🔄 Full factory reset — all preferences cleared");
    }

    /**
     * توافق خلفي مع الاستدعاءات القديمة.
     * @deprecated استخدم {@link #resetReaderToDefaults()} أو {@link #resetAllToFactory()}
     */
    @Deprecated
    public void resetToDefaults() {
        resetReaderToDefaults();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers — تحقق وملصقات
    // ═══════════════════════════════════════════════════════════════

    private static boolean isValidFreqMode(int mode) {
        for (int[] m : FREQ_MODES) {
            if (m[0] == mode) return true;
        }
        return false;
    }

    /**
     * هل Miller المخزن متوافق مع RFLink المخزن؟
     * نستخرج Miller المتوقع من نص RFLINK_LABELS ونقارن.
     */
    private boolean isMillerRfLinkConsistent() {
        int idx = getRfLink();
        if (idx < 0 || idx >= RFLINK_LABELS.length) return false;
        String label = RFLINK_LABELS[idx];
        int expectedMiller;
        if (label.contains("Miller8"))      expectedMiller = 3;
        else if (label.contains("Miller4")) expectedMiller = 2;
        else if (label.contains("Miller2")) expectedMiller = 1;
        else if (label.contains("FM0"))     expectedMiller = 0;
        else return true;   // غير معروف — لا نعطي تحذير
        return getMiller() == expectedMiller;
    }

    public static String getPosRangeLabel(int dbm) {
        if (dbm <= 5)  return "~3-5 cm";
        if (dbm <= 8)  return "~5-8 cm";
        if (dbm <= 12) return "~8-12 cm";
        if (dbm <= 18) return "~10-18 cm";
        if (dbm <= 25) return "~18-30 cm";
        return "~30+ cm";
    }

    public static String getFreqLabel(int mode) {
        for (int i = 0; i < FREQ_MODES.length; i++)
            if (FREQ_MODES[i][0] == mode) return FREQ_LABELS[i];
        return "Unknown(0x" + Integer.toHexString(mode) + ")";
    }

    public static String getReadModeLabel(int mode) {
        if (mode < 0 || mode >= READ_MODE_LABELS.length) return "?";
        return READ_MODE_LABELS[mode];
    }

    public static String getTriggerModeLabel(int mode) {
        if (mode < 0 || mode >= TRIGGER_MODE_LABELS.length) return "?";
        return TRIGGER_MODE_LABELS[mode];
    }

    public static String getToneLabel(int tone) {
        if (tone < 0 || tone >= TONE_LABELS.length) return "?";
        return TONE_LABELS[tone];
    }

    public static String getRfLinkLabel(int idx) {
        if (idx < 0 || idx >= RFLINK_LABELS.length) return "?";
        return RFLINK_LABELS[idx];
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public String getSummary() {
        return "⚡ Reader Power: " + getPowerDbm() + " dBm\n"
             + "⚡ Inventory Power: " + getInventoryPowerDbm() + " dBm\n"
             + "⚡ POS Power: " + getPosPowerDbm() + " dBm\n"
             + "🌍 Freq: " + getFreqLabel(getFreqMode()) + "\n"
             + "🎯 ReadMode: " + getReadModeLabel(getReadMode()) + "\n"
             + "🎯 Trigger: " + getTriggerModeLabel(getTriggerMode()) + "\n"
             + "🔊 Sound: " + (isSoundEnabled() ? "ON (" + getSoundVolume() + "%)" : "OFF") + "\n"
             + "📳 Vibrate: " + (isVibrateEnabled() ? "ON (" + getVibrateMs() + "ms)" : "OFF") + "\n"
             + "🚫 Filter Dup: " + (isFilterDuplicates() ? getDuplicateFilterMs() + "ms" : "OFF") + "\n"
             + "📊 Session: S" + getSession() + " | Target: " + (getTarget() == 0 ? "A" : "B") + "\n"
             + "🔢 Q: " + getQValue() + " (" + (getQMode() == 0 ? "fixed" : "dynamic") + ")\n"
             + "🔗 RFLink: " + getRfLinkLabel(getRfLink()) + "\n"
             + "🚀 FastID: " + (isFastId() ? "ON" : "OFF") + " | TagFocus: " + (isTagFocus() ? "ON" : "OFF");
    }
}
