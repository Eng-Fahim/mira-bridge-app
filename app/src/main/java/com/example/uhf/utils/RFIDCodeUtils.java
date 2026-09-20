package com.example.uhf.utils;

import java.util.Map;

/**
 * ✅ RFIDCodeUtils — إصلاح دعم EPC prefixes متنوعة
 * 
 * إصلاحات:
 * - دعم E200 (NXP UCODE)
 * - دعم 3000 (Monza 4)
 * - دعم E280 (Impinj Monza)
 * - التحقق من أن الكود hex صحيح
 */
public class RFIDCodeUtils {

    private static final int EAN13_LENGTH = 13;
    private static final int UPC12_LENGTH = 12;
    private static final int MIN_EPC_LENGTH = 16;

    /**
     * ✅ تحويل أي كود (EPC، GTIN، Serial) إلى EPC معياري
     * 
     * @param code الكود الخام من القارئ
     * @param barcodeToEpcMap خريطة GTIN/Serial → EPC
     * @return EPC معياري (UPPERCASE، بدون فراغات)
     */
    public static String normalizeCodeToEpc(String code, Map<String, String> barcodeToEpcMap) {
        if (code == null || code.isEmpty()) return "";

        String normalized = code.trim().toUpperCase()
                .replace(" ", "")
                .replace(":", "")
                .replace("-", "");

        if (normalized.isEmpty()) return "";

        // ✅ 1. EPC معياري — يدعم كل البادئات الشائعة
        if (isLikelyEpc(normalized)) {
            // ✅ حاول إيجاد تعيين في الخريطة أولاً
            if (barcodeToEpcMap != null) {
                String mapped = barcodeToEpcMap.get(normalized);
                if (mapped != null) return mapped;
            }
            return normalized;
        }

        // ✅ 2. EAN-13
        if (normalized.length() == EAN13_LENGTH && normalized.matches("\\d{13}")) {
            if (barcodeToEpcMap != null) {
                String mapped = barcodeToEpcMap.get(normalized);
                if (mapped != null) return mapped;
            }
            return "EAN13:" + normalized;
        }

        // ✅ 3. UPC-12 → EAN-13
        if (normalized.length() == UPC12_LENGTH && normalized.matches("\\d{12}")) {
            String ean13 = "0" + normalized;
            if (barcodeToEpcMap != null) {
                String mapped = barcodeToEpcMap.get(ean13);
                if (mapped != null) return mapped;
                mapped = barcodeToEpcMap.get(normalized);
                if (mapped != null) return mapped;
            }
            return "EAN13:" + ean13;
        }

        // ✅ 4. بحث في الخريطة
        if (barcodeToEpcMap != null) {
            String mapped = barcodeToEpcMap.get(normalized);
            if (mapped != null) return mapped;
        }

        // ✅ 5. fallback
        return normalized;
    }

    /**
     * ✅ فحص إذا كان الكود يشبه EPC معياري
     * 
     * EPC معايير شائعة:
     * - E200... (NXP UCODE) — طول 24
     * - E280... (Impinj Monza) — طول 24
     * - 3000... (Monza 4) — طول 24
     * - E2003... (UCODE DNA) — طول 24
     * - 30... (SGTIN-96) — طول 24
     * - أو أي hex بطول 16-24 لا يحتوي على أحرف غير hex
     */
    private static boolean isLikelyEpc(String code) {
        if (code.length() < MIN_EPC_LENGTH || code.length() > 40) return false;
        if (!code.matches("[0-9A-F]+")) return false;

        // ✅ بادئات EPC المعروفة
        return code.startsWith("E2")     // E200, E280, E2003
            || code.startsWith("30")     // 3000, SGTIN-96
            || code.startsWith("E0")     // E0xx
            || code.startsWith("AD")     // AD-xxx
            || code.startsWith("B0");    // B0xx
    }
}
