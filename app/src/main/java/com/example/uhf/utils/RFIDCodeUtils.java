package com.example.uhf.utils;

import java.util.Map;

public class RFIDCodeUtils {

    private static final int EAN13_LENGTH = 13;
    private static final int UPC12_LENGTH = 12;

    public static String normalizeCodeToEpc(String code, Map<String, String> barcodeToEpcMap) {
        if (code == null || code.isEmpty()) return "";

        String normalized = code.trim().toUpperCase();

        if (normalized.startsWith("E280") && normalized.length() >= 24) {
            return normalized;
        }

        if (normalized.length() == EAN13_LENGTH && normalized.matches("\\d{13}")) {
            String mappedEpc = barcodeToEpcMap.get(normalized);
            if (mappedEpc != null) {
                return mappedEpc;
            }
            return "EAN13:" + normalized;
        }

        if (normalized.length() == UPC12_LENGTH && normalized.matches("\\d{12}")) {
            String ean13 = "0" + normalized;
            String mappedEpc = barcodeToEpcMap.get(ean13);
            if (mappedEpc != null) {
                return mappedEpc;
            }
            return "EAN13:" + ean13;
        }

        String mappedEpc = barcodeToEpcMap.get(normalized);
        if (mappedEpc != null) {
            return mappedEpc;
        }

        return normalized;
    }
}
