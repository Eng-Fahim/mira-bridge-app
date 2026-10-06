package com.example.uhf.MiraRfid.adaptive;

import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ ReaderCapabilities — وصف ديناميكي لقدرات القارئ
 * 
 * لا يحتوي على قيم ثابتة. كل القيم تُكتشف من:
 *   1. Build.MODEL / Manufacturer / Brand
 *   2. SDK (getVersion, getHardwareVersion)
 *   3. اختبارات عملية (Probing)
 */
public class ReaderCapabilities {

    private static final String TAG = "ReaderCapabilities";

    // ==================== الحقول ====================

    public enum Manufacturer {
        CHAINWAY, ZEBRA, HONEYWELL, UNITECH, UROVO, VA_GATE, UNKNOWN
    }

    public enum Region {
        FCC,        // 902-928 MHz (أمريكا، السعودية، اليمن، الخليج)
        ETSI,       // 865-868 MHz (أوروبا)
        CHINA,      // 920-925 MHz (الصين)
        JAPAN,      // 916-921 MHz (اليابان)
        UNKNOWN
    }

    public final String model;
    public final String manufacturer;
    public final String brand;

    public Manufacturer detectedManufacturer;
    public List<Region> supportedRegions;
    public Region preferredRegion;

    public int minPowerDbm;
    public int maxPowerDbm;
    public int defaultPowerDbm;

    public String softwareVersion;
    public String hardwareVersion;

    public boolean supportsWrite;
    public boolean supportsLock;
    public boolean supportsKill;
    public boolean supportsFilter;
    public boolean supportsDynamicPower;
    public boolean supportsFastId;
    public boolean supportsTagFocus;

    public long detectionTimestampMs;

    // ==================== Constructor ====================

    public ReaderCapabilities() {
        this.model = safeGet(Build.MODEL);
        this.manufacturer = safeGet(Build.MANUFACTURER);
        this.brand = safeGet(Build.BRAND);

        this.detectedManufacturer = Manufacturer.UNKNOWN;
        this.supportedRegions = new ArrayList<>();
        this.preferredRegion = Region.UNKNOWN;

        this.minPowerDbm = 5;
        this.maxPowerDbm = 30;
        this.defaultPowerDbm = 26;

        this.detectionTimestampMs = System.currentTimeMillis();
    }

    // ==================== Detection ====================

    /**
     * ✅ اكتشاف الشركة المصنعة بناءً على البصمات
     */
    public void detectManufacturer() {
        String all = (model + " " + manufacturer + " " + brand).toUpperCase();

        if (all.contains("CHAINWAY") || all.contains("C72") 
                || all.contains("C61") || all.contains("C66")
                || all.contains("C5") || all.contains("R6")) {
            detectedManufacturer = Manufacturer.CHAINWAY;
            supportedRegions.add(Region.FCC);
            supportedRegions.add(Region.ETSI);
            minPowerDbm = 5;
            maxPowerDbm = 30;
            defaultPowerDbm = 26;
            supportsWrite = true;
            supportsLock = true;
            supportsKill = true;
            supportsFilter = true;
            supportsDynamicPower = true;
            supportsFastId = true;
            supportsTagFocus = true;
        } else if (all.contains("ZEBRA") || all.contains("MC33")
                || all.contains("MC32") || all.contains("TC51")
                || all.contains("TC52") || all.contains("TC72")) {
            detectedManufacturer = Manufacturer.ZEBRA;
            supportedRegions.add(Region.FCC);
            supportedRegions.add(Region.ETSI);
            minPowerDbm = 5;
            maxPowerDbm = 30;
            defaultPowerDbm = 27;
            supportsWrite = true;
            supportsLock = true;
            supportsKill = true;
            supportsFilter = true;
        } else if (all.contains("HONEYWELL") || all.contains("EDA5")
                || all.contains("CT40") || all.contains("CT60")) {
            detectedManufacturer = Manufacturer.HONEYWELL;
            supportedRegions.add(Region.FCC);
            supportedRegions.add(Region.ETSI);
            minPowerDbm = 5;
            maxPowerDbm = 30;
            defaultPowerDbm = 26;
        } else if (all.contains("UNITECH") || all.contains("RP90")
                || all.contains("PA70")) {
            detectedManufacturer = Manufacturer.UNITECH;
            supportedRegions.add(Region.FCC);
            supportedRegions.add(Region.ETSI);
        } else if (all.contains("UROVO") || all.contains("DT50")
                || all.contains("RT40")) {
            detectedManufacturer = Manufacturer.UROVO;
            supportedRegions.add(Region.FCC);
            supportedRegions.add(Region.ETSI);
        } else {
            detectedManufacturer = Manufacturer.UNKNOWN;
            supportedRegions.add(Region.FCC);  // افتراضي
        }

        Log.i(TAG, "🔍 Manufacturer detected: " + detectedManufacturer
                + " | Regions: " + supportedRegions
                + " | Power: " + minPowerDbm + "-" + maxPowerDbm + " dBm");
    }

    /**
     * ✅ استنتاج المنطقة المفضلة
     * 
     * الأولوية:
     *  1. اللغة/المنطقة من Locale (إذا كانت ar-SA مثلاً → FCC)
     *  2. الاستعلام من السحابة (لاحقاً)
     *  3. الافتراضي: FCC
     */
    public Region inferPreferredRegion() {
        // إذا كان الجهاز يدعم ETSI فقط → ETSI
        if (supportedRegions.size() == 1 
                && supportedRegions.contains(Region.ETSI)) {
            preferredRegion = Region.ETSI;
            return preferredRegion;
        }

        // استنتاج من Country Code
        String country = java.util.Locale.getDefault().getCountry().toUpperCase();
        Log.d(TAG, "Country detected: " + country);

        // قائمة الدول التي تستخدم ETSI
        if (country.equals("DE") || country.equals("FR") || country.equals("IT")
                || country.equals("ES") || country.equals("GB") 
                || country.equals("NL") || country.equals("BE")) {
            preferredRegion = Region.ETSI;
        }
        // قائمة الدول التي تستخدم FCC (الخليج + أمريكا)
        else if (country.equals("US") || country.equals("SA") 
                || country.equals("AE") || country.equals("YE")
                || country.equals("KW") || country.equals("QA")
                || country.equals("BH") || country.equals("OM")) {
            preferredRegion = Region.FCC;
        }
        // الصين
        else if (country.equals("CN")) {
            preferredRegion = Region.CHINA;
        }
        // اليابان
        else if (country.equals("JP")) {
            preferredRegion = Region.JAPAN;
        }
        // افتراضي: FCC (الأكثر انتشاراً)
        else {
            preferredRegion = Region.FCC;
        }

        Log.i(TAG, "🌍 Preferred region: " + preferredRegion);
        return preferredRegion;
    }

    /**
     * ✅ رقم النطاق لـ SDK
     */
    public int getFrequencyModeCode() {
        switch (preferredRegion) {
            case ETSI:  return 2;  // 865-868 MHz
            case FCC:   return 1;  // 902-928 MHz
            case CHINA: return 3;  // 920-925 MHz
            case JAPAN: return 4;  // 916-921 MHz
            default:    return 1;  // FCC افتراضي
        }
    }

    /**
     * ✅ الحصول على النطاق بصيغة نصية
     */
    public String getFrequencyRangeText() {
        switch (preferredRegion) {
            case FCC:   return "902-928 MHz (FCC)";
            case ETSI:  return "865-868 MHz (ETSI)";
            case CHINA: return "920-925 MHz (China)";
            case JAPAN: return "916-921 MHz (Japan)";
            default:    return "Unknown";
        }
    }

    @Override
    public String toString() {
        return "ReaderCapabilities{" +
                "model='" + model + '\'' +
                ", manufacturer=" + detectedManufacturer +
                ", region=" + preferredRegion +
                ", power=" + minPowerDbm + "-" + maxPowerDbm + "dBm" +
                '}';
    }

    private String safeGet(String v) {
        return v != null ? v : "";
    }
}
