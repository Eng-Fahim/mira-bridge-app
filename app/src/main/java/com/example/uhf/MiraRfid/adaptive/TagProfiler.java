package com.example.uhf.MiraRfid.adaptive;

import android.util.Log;

import java.util.Locale;

/**
 * ✅ TagProfiler — وصف ديناميكي لخصائص الوسم
 * 
 * يستنتج من TID:
 *   - الشركة المصنعة
 *   - الجيل (Gen2 دائماً في UHF)
 *   - النطاق المدعوم
 *   - الحساسية
 */
public class TagProfiler {

    private static final String TAG = "TagProfiler";

    // ==================== الحقول ====================

    public enum TagVendor {
        IMPINJ,      // E280... (الأشهر في الذهب والمجوهرات)
        NXP,         // E2xx... 
        ALIEN,       // E200...
        AVERY,       // E200...
        EM,          // EM4325, EM4425
        FUJITSU,     // بطاريات ليثيوم
        UNKNOWN
    }

    public final String tid;
    public final int rssi;
    public final long detectedAtMs;

    public TagVendor vendor;
    public int generation = 2;              // Gen2 دائماً في UHF
    public String memorySize;
    public String frequencySupport;         // "FCC", "ETSI", "Both"
    public int recommendedPower;            // طاقة القراءة المثلى
    public int recommendedSession;          // S0/S1/S2/S3
    public int recommendedQ;                // Q-value

    // ==================== Constructor ====================

    public TagProfiler(String tid, int rssi) {
        this.tid = tid != null ? tid.toUpperCase(Locale.US).trim() : "";
        this.rssi = rssi;
        this.detectedAtMs = System.currentTimeMillis();

        profile();
    }

    // ==================== Profiling ====================

    private void profile() {
        if (tid.isEmpty()) {
            vendor = TagVendor.UNKNOWN;
            return;
        }

        // B1: كشف الشركة المصنعة من البادئة
        // TID يبدأ عادة بـ 2-3 bytes تعرّف الشركة
        String prefix = tid.length() >= 4 ? tid.substring(0, 4) : tid;
        String prefix6 = tid.length() >= 6 ? tid.substring(0, 6) : tid;

        // Impinj: E280 1160... / E280 1170...
        if (prefix6.startsWith("E28011") || prefix6.startsWith("E28010")) {
            vendor = TagVendor.IMPINJ;
            memorySize = "Monza R6 / R6-P";
            frequencySupport = "Both (FCC+ETSI)";
            recommendedPower = 26;
            recommendedSession = 1;
            recommendedQ = 4;
        }
        // NXP UCODE 7/8
        else if (prefix6.startsWith("E28068") || prefix6.startsWith("E28069")
                || prefix6.startsWith("E2806")) {
            vendor = TagVendor.NXP;
            memorySize = "UCODE 7/8";
            frequencySupport = "Both";
            recommendedPower = 26;
            recommendedSession = 1;
            recommendedQ = 4;
        }
        // Alien Higgs
        else if (prefix6.startsWith("E20034") || prefix6.startsWith("E2004")) {
            vendor = TagVendor.ALIEN;
            memorySize = "Higgs 3/4";
            frequencySupport = "Both";
            recommendedPower = 27;
            recommendedSession = 1;
            recommendedQ = 4;
        }
        // Avery Dennison
        else if (prefix6.startsWith("E20020") || prefix6.startsWith("E2002")) {
            vendor = TagVendor.AVERY;
            memorySize = "N95 / AD-220";
            frequencySupport = "FCC";  // Avery تركز على FCC
            recommendedPower = 28;
            recommendedSession = 1;
            recommendedQ = 4;
        }
        // EM Microelectronic
        else if (prefix6.startsWith("E280A0") || prefix6.startsWith("E280A")) {
            vendor = TagVendor.EM;
            memorySize = "EM4325 / EM4425";
            frequencySupport = "Both";
            recommendedPower = 25;      // EM حساس
            recommendedSession = 2;
            recommendedQ = 3;
        }
        // افتراضي
        else {
            vendor = TagVendor.UNKNOWN;
            memorySize = "Unknown";
            frequencySupport = "Unknown";
            recommendedPower = 27;
            recommendedSession = 1;
            recommendedQ = 4;
        }

        // C: تصحيح الجلسة حسب RSSI
        // إذا الإشارة ضعيفة، قلل Q لتسريع القراءة
        if (rssi < -70) {
            recommendedQ = 3;
        } else if (rssi > -50) {
            recommendedQ = 5;
        }

        Log.i(TAG, "🏷️ Tag profiled: " + vendor 
                + " (" + memorySize + ")"
                + " | RSSI=" + rssi + " dBm"
                + " | Power=" + recommendedPower + "dBm"
                + " | Session=S" + recommendedSession
                + " | Q=" + recommendedQ);
    }

    /**
     * ✅ الحصول على النطاق المفضل
     */
    public String getPreferredRegion() {
        if (frequencySupport.contains("FCC") && !frequencySupport.contains("ETSI")) {
            return "FCC";
        }
        if (frequencySupport.contains("ETSI") && !frequencySupport.contains("FCC")) {
            return "ETSI";
        }
        return "BOTH";
    }

    /**
     * ✅ هل هذا وسم مخصص للمعادن؟
     */
    public boolean isMetalTag() {
        // وسوم المعادن عادة تعمل على 866 أو 915 MHz
        // ولا نستطيع التأكد من TID فقط
        // نعتمد على RSSI ومعدل النجاح
        return rssi < -55;
    }

    @Override
    public String toString() {
        return "TagProfiler{" +
                "tid='" + tid + '\'' +
                ", vendor=" + vendor +
                ", rssi=" + rssi +
                '}';
    }
}
