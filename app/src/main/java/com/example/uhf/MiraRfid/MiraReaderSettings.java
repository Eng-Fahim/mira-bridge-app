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
 *  MiraReaderSettings v5.2 — Balanced for Jewelry Inventory
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * [v5.2] إصلاحات توافق Miller/LinkFreq/RFLink:
 *   ✅ Miller = 2 (Miller 4 — توازن سرعة/دقة للمعادن)
 *   ✅ LinkFreq = 3 (256 kHz — قريب من 250 kHz الفعلي)
 *   ✅ RFLink = 1 (PR-ASK / Miller4 / 250KHz — متوافق تماماً)
 *   ✅ Power = 30 dBm (أقصى تغطية للجرد)
 *   ✅ Q Value = 5 (32 slot — يعمل مع 5-30 شريحة)
 *
 * [v5.1] تحسينات الأداء:
 *   ✅ كل setter يستخدم .apply() بدل .commit()
 *   ✅ notifyChanged() مع Debounce (200ms)
 *   ✅ Handler على Main Thread
 *
 * [v5.0] الأساس:
 *   ✅ Trigger Mode, Tone Preview, Yemen defaults
 *   ✅ POS Power, Advanced Reading
 *   ✅ Sound & Vibration
 */
public class MiraReaderSettings {

    private static final String TAG = "MiraReaderSettings";
    private static final String PREFS = "mira_reader_settings_v2";

    // ═══════════════════════════════════════════════════════════════
    //  📡 مناطق العمل
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
    //  🎯 القيم الافتراضية المحسّنة [v5.2]
    // ═══════════════════════════════════════════════════════════════

    // ⚡ Power: 30 dBm — أقصى تغطية (كان 24)
    public static final int  DEF_POWER_DBM       = 30;

    // 🌍 ETSI (865-868 MHz) — القانوني لليمن
    public static final int  DEF_FREQ_MODE       = FREQ_ETSI;

    // 🔄 قراءة مستمرة
    public static final int  DEF_READ_MODE       = READ_MODE_AUTO;

    // 📊 Session S1
    public static final int  DEF_SESSION         = 1;

    // 🎯 Target A
    public static final int  DEF_TARGET          = 0;

    // 🔢 Q Mode: ثابت
    public static final int  DEF_Q_MODE          = 0;

    // 🔢 Q Value: 5 (32 slot) — كان 4 (16 slot)
    public static final int  DEF_Q_VALUE         = 5;

    public static final int  DEF_MIN_Q           = 0;
    public static final int  DEF_MAX_Q           = 15;
    public static final int  DEF_DR              = 0;

    // 🎵 Miller: 2 = Miller 4 (كان 3 = Miller 8 — بطيء جداً!)
    public static final int  DEF_MILLER          = 2;

    // 🔗 LinkFreq: 3 = 256 kHz (كان 1 = 160 kHz)
    public static final int  DEF_LINK_FREQ       = 3;

    // 🔗 RFLink: 1 = PR-ASK / Miller4 / 250KHz (متوافق مع Miller=2, LinkFreq=3)
    public static final int  DEF_RFLINK          = 1;

    public static final boolean DEF_FAST_ID      = false;
    public static final boolean DEF_TAG_FOCUS    = true;
    public static final boolean DEF_FAST_INV     = false;

    public static final int  DEF_RSSI_THRESHOLD  = -75;
    public static final int  DEF_SCAN_INTERVAL   = 100;

    // ⚡ POS Power
    public static final int  DEF_POS_POWER_DBM = 7;
    public static final int  MIN_POS_POWER_DBM = 5;
    public static final int  MAX_POS_POWER_DBM = 30;

    // 🔊 الصوت والاهتزاز
    public static final boolean DEF_SOUND_ENABLED     = true;
    public static final int     DEF_SOUND_VOLUME      = 85;
    public static final boolean DEF_VIBRATE_ENABLED   = true;
    public static final int     DEF_VIBRATE_MS        = 45;

    public static final int     DEF_TONE_NEW_TAG      = TONE_BEEP_DOUBLE;
    public static final int     DEF_TONE_DUPLICATE    = TONE_CLICK;
    public static final int     DEF_TONE_ERROR        = TONE_BEEP_LONG;
    public static final int     DEF_TONE_SUCCESS      = TONE_SUCCESS;
    public static final int     DEF_TONE_UNKNOWN_ITEM = TONE_UNKNOWN_ITEM;

    public static final boolean DEF_SOUND_ON_POS      = true;
    public static final boolean DEF_SOUND_ON_INVENTORY= true;

    public static final int     DEF_SINGLE_TIMEOUT_MS = 1500;
    public static final int     DEF_AUTO_STOP_MS      = 0;
    public static final int     DEF_MAX_TAGS_SESSION  = 0;
    public static final boolean DEF_FILTER_DUPLICATES = true;
    public static final int     DEF_DUPLICATE_FILTER_MS = 2500;

    public static final boolean DEF_LED_FLASH         = false;
    public static final boolean DEF_KEEP_SCREEN_ON    = true;
    public static final boolean DEF_TOAST_ENABLED     = true;
    public static final boolean DEF_AUTO_SAVE_LOG     = false;
    public static final int     DEF_LANGUAGE          = 0;

    public static final int DEF_TRIGGER_MODE = TRIGGER_MODE_TOGGLE;

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
        Log.i(TAG, "✅ MiraReaderSettings v5.2 initialized");
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
    //  🎯 v5.2 — تطبيق الإعدادات المثالية لليمن/المجوهرات
    // ═══════════════════════════════════════════════════════════════

    public void applyYemenJewelryDefaults() {
        Log.i(TAG, "🎯 Applying Yemen/Jewelry optimal defaults (v5.2)");

        SharedPreferences.Editor e = prefs.edit();

        // ⚡ القارئ — قيم متوافقة مع Miller 4
        e.putInt("power",         DEF_POWER_DBM);        // 30 dBm
        e.putInt("freq",          DEF_FREQ_MODE);        // ETSI
        e.putInt("rflink",        DEF_RFLINK);           // 1 = PR-ASK/Miller4/250
        e.putInt("miller",        DEF_MILLER);           // 2 = Miller 4
        e.putInt("link_freq",     DEF_LINK_FREQ);        // 3 = 256 kHz
        e.putInt("sess",          DEF_SESSION);          // S1
        e.putInt("target",        DEF_TARGET);           // A
        e.putInt("q_mode",        DEF_Q_MODE);           // Fixed
        e.putInt("q_val",         DEF_Q_VALUE);          // 5 = 32 slot
        e.putBoolean("tag_focus", DEF_TAG_FOCUS);        // ON
        e.putBoolean("fast_id",   DEF_FAST_ID);          // OFF
        e.putBoolean("fast_inv",  DEF_FAST_INV);         // OFF

        // 🎯 القراءة المتقدمة
        e.putBoolean("flt_dup", DEF_FILTER_DUPLICATES);  // ON
        e.putInt("dup_flt",     DEF_DUPLICATE_FILTER_MS);// 2500ms
        e.putInt("max_tags",    DEF_MAX_TAGS_SESSION);   // 0
        e.putInt("auto_stop",   DEF_AUTO_STOP_MS);       // 0

        // 🌐 عام
        e.putBoolean("keep_screen", DEF_KEEP_SCREEN_ON);

        // 🎯 وضع الزناد
        e.putInt("trigger_mode", DEF_TRIGGER_MODE);

        // ⚡ POS
        e.putInt("pos_power", DEF_POS_POWER_DBM);

        e.commit();
        notifyChanged();

        Log.i(TAG, "✅ Yemen/Jewelry defaults v5.2 applied successfully");
    }

    // ═══════════════════════════════════════════════════════════════
    //  📡 Get — الإعدادات الأساسية
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
    public int     getPosPowerDbm()   { return prefs.getInt("pos_power", DEF_POS_POWER_DBM); }
    public int     getTriggerMode()   { return prefs.getInt("trigger_mode", DEF_TRIGGER_MODE); }

    // ═══════════════════════════════════════════════════════════════
    //  📡 Set — الإعدادات الأساسية
    // ═══════════════════════════════════════════════════════════════
    public void setPowerDbm(int v)      { prefs.edit().putInt("power", clamp(v, 5, 33)).apply(); notifyChanged(); }
    public void setFreqMode(int v)      { prefs.edit().putInt("freq", v).apply(); notifyChanged(); }
    public void setReadMode(int v)      { prefs.edit().putInt("read_mode", clamp(v, 0, 1)).apply(); notifyChanged(); }
    public void setSession(int v)       { prefs.edit().putInt("sess", clamp(v, 0, 3)).apply(); notifyChanged(); }
    public void setTarget(int v)        { prefs.edit().putInt("target", clamp(v, 0, 1)).apply(); notifyChanged(); }
    public void setQMode(int v)         { prefs.edit().putInt("q_mode", clamp(v, 0, 1)).apply(); notifyChanged(); }
    public void setQValue(int v)        { prefs.edit().putInt("q_val", clamp(v, 0, 15)).apply(); notifyChanged(); }
    public void setMinQ(int v)          { prefs.edit().putInt("q_min", clamp(v, 0, 15)).apply(); notifyChanged(); }
    public void setMaxQ(int v)          { prefs.edit().putInt("q_max", clamp(v, 0, 15)).apply(); notifyChanged(); }
    public void setDR(int v)            { prefs.edit().putInt("dr", clamp(v, 0, 1)).apply(); notifyChanged(); }
    public void setMiller(int v)        { prefs.edit().putInt("miller", clamp(v, 0, 3)).apply(); notifyChanged(); }
    public void setLinkFreq(int v)      { prefs.edit().putInt("link_freq", clamp(v, 0, 7)).apply(); notifyChanged(); }
    public void setRfLink(int v)        { prefs.edit().putInt("rflink", clamp(v, 0, 11)).apply(); notifyChanged(); }
    public void setFastId(boolean v)    { prefs.edit().putBoolean("fast_id", v).apply(); notifyChanged(); }
    public void setTagFocus(boolean v)  { prefs.edit().putBoolean("tag_focus", v).apply(); notifyChanged(); }
    public void setFastInventory(boolean v) { prefs.edit().putBoolean("fast_inv", v).apply(); notifyChanged(); }
    public void setRssiThreshold(int v) { prefs.edit().putInt("rssi", clamp(v, -100, -30)).apply(); notifyChanged(); }
    public void setScanInterval(int v)  { prefs.edit().putInt("interval", clamp(v, 50, 1000)).apply(); notifyChanged(); }
    public void setPosPowerDbm(int v)   {
        prefs.edit().putInt("pos_power", clamp(v, MIN_POS_POWER_DBM, MAX_POS_POWER_DBM)).apply();
        notifyChanged();
    }
    public void setTriggerMode(int v)   {
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
    public void setSoundVolume(int v)           { prefs.edit().putInt("snd_vol", clamp(v, 0, 100)).apply(); notifyChanged(); }
    public void setToneNewTag(int v)            { prefs.edit().putInt("tone_new", v).apply(); notifyChanged(); }
    public void setToneDuplicate(int v)         { prefs.edit().putInt("tone_dup", v).apply(); notifyChanged(); }
    public void setToneError(int v)             { prefs.edit().putInt("tone_err", v).apply(); notifyChanged(); }
    public void setToneSuccess(int v)           { prefs.edit().putInt("tone_succ", v).apply(); notifyChanged(); }
    public void setToneUnknownItem(int v)       { prefs.edit().putInt("tone_unk", v).apply(); notifyChanged(); }
    public void setSoundOnPos(boolean v)        { prefs.edit().putBoolean("snd_pos", v).apply(); notifyChanged(); }
    public void setSoundOnInventory(boolean v)  { prefs.edit().putBoolean("snd_inv", v).apply(); notifyChanged(); }
    public void setVibrateEnabled(boolean v)    { prefs.edit().putBoolean("vib_en", v).apply(); notifyChanged(); }
    public void setVibrateMs(int v)             { prefs.edit().putInt("vib_ms", clamp(v, 10, 500)).apply(); notifyChanged(); }

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
    public void setLanguage(int v)            { prefs.edit().putInt("lang", clamp(v, 0, 1)).apply(); notifyChanged(); }

    // ═══════════════════════════════════════════════════════════════
    //  ✅ تطبيق على القارئ
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
        int applied = 0, failed = 0;
        List<String> failedNames = new ArrayList<>();

        // ⚡ Power أولاً
        try { if (reader.setPower(getPowerDbm())) applied++; else { failed++; failedNames.add("setPower"); } }
        catch (Exception e) { failed++; failedNames.add("setPower"); }

        // 🌍 Frequency
        try { if (reader.setFrequencyMode(getFreqMode())) applied++; else { failed++; failedNames.add("setFrequencyMode"); } }
        catch (Exception e) { failed++; failedNames.add("setFrequencyMode"); }

        // 🔗 RFLink (يضع Miller/LinkFreq تلقائياً حسب SDK)
        try { if (reader.setRFLink(getRfLink())) applied++; else { failed++; failedNames.add("setRFLink"); } }
        catch (Exception e) { failed++; failedNames.add("setRFLink"); }

        // 🚀 FastID
        try { if (reader.setFastID(isFastId())) applied++; else { failed++; failedNames.add("setFastID"); } }
        catch (Exception e) { failed++; failedNames.add("setFastID"); }

        // 🎯 TagFocus
        try { if (reader.setTagFocus(isTagFocus())) applied++; else { failed++; failedNames.add("setTagFocus"); } }
        catch (Exception e) { failed++; failedNames.add("setTagFocus"); }

        // 🔢 Gen2 (Session, Target, Q, Miller, LinkFreq, DR)
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
            gen2.setQueryM(getMiller());            // Miller 4
            gen2.setQueryTRext(1);
            gen2.setQuerySel(0);
            gen2.setLinkFrequency(getLinkFreq());   // 256 kHz
            if (reader.setGen2(gen2)) applied++; else { failed++; failedNames.add("setGen2"); }
        } catch (Exception e) { failed++; failedNames.add("setGen2"); }

        // 📖 EPC mode
        try { reader.setEPCMode(); applied++; }
        catch (Exception e) { failed++; failedNames.add("setEPCMode"); }

        ApplyResult r = new ApplyResult(applied, failed);
        r.failedNames.addAll(failedNames);
        return r;
    }

    public void resetToDefaults() {
        prefs.edit().clear().commit();
        notifyChanged();
        Log.i(TAG, "🔄 Reset to factory defaults");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════
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

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public String getSummary() {
        return "⚡ Power: " + getPowerDbm() + " dBm\n"
             + "⚡ POS Power: " + getPosPowerDbm() + " dBm\n"
             + "🌍 Freq: " + getFreqLabel(getFreqMode()) + "\n"
             + "🎯 ReadMode: " + getReadModeLabel(getReadMode()) + "\n"
             + "🎯 Trigger: " + getTriggerModeLabel(getTriggerMode()) + "\n"
             + "🔊 Sound: " + (isSoundEnabled() ? "ON (" + getSoundVolume() + "%)" : "OFF") + "\n"
             + "📳 Vibrate: " + (isVibrateEnabled() ? "ON (" + getVibrateMs() + "ms)" : "OFF") + "\n"
             + "🚫 Filter Dup: " + (isFilterDuplicates() ? getDuplicateFilterMs() + "ms" : "OFF") + "\n"
             + "📊 Session: S" + getSession() + " | Target: " + (getTarget() == 0 ? "A" : "B") + "\n"
             + "🔢 Q: " + getQValue() + " (" + (getQMode() == 0 ? "fixed" : "dynamic") + ")\n"
             + "🔗 RFLink: " + (getRfLink() < RFLINK_LABELS.length ? RFLINK_LABELS[getRfLink()] : "?") + "\n"
             + "🚀 FastID: " + (isFastId() ? "ON" : "OFF") + " | TagFocus: " + (isTagFocus() ? "ON" : "OFF");
    }
}
