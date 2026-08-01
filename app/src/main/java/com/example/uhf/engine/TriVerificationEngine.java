package com.example.uhf.engine;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 🟢 محرك المطابقة الثلاثية الهجين (MIRA ID Tri-Verification Engine)
 */
public class TriVerificationEngine {

    private static final String TAG = "TriVerification";
    private static final String BASE_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1";
    private static final String API_KEY = "mira_gate_test071234567890abcdefghijklmnop";
    
    // ⚖️ هامش السماحية المقبول للوزن (±0.2 غرام)
    private static final double WEIGHT_TOLERANCE = 0.2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private VerificationListener listener;
    private final Map<String, VerificationResult> cache = new HashMap<>();

    public enum VerificationLevel {
        TRIPLE_VERIFIED(100, "✅✅✅ مطابقة ثلاثية كاملة"),
        DOUBLE_VERIFIED_RFID_GS1(90, "✅✅ مطابقة RFID + GS1"),
        DOUBLE_VERIFIED_RFID_WEIGHT(85, "✅✅ مطابقة RFID + وزن"),
        DOUBLE_VERIFIED_GS1_WEIGHT(80, "✅✅ مطابقة GS1 + وزن"),
        SINGLE_VERIFIED_RFID(60, "✅ مطابقة RFID فقط"),
        SINGLE_VERIFIED_GS1(55, "✅ مطابقة GS1 فقط"),
        WEIGHT_VERIFIED(50, "⚖️ تطابق الوزن فقط"),
        MISMATCH(0, "🚨 عدم تطابق - تحقق يدوياً"),
        UNKNOWN(-1, "❓ غير معروف");

        public final int score;
        public final String label;

        VerificationLevel(int score, String label) {
            this.score = score;
            this.label = label;
        }
    }

    public static class VerificationResult {
        public VerificationLevel level;
        public String epc = "";
        public String gtin = "";
        public String serial = "";
        public String title = "غير معروف";
        public String karat = "-";
        public double expectedWeight = 0.0;
        public double actualWeight = 0.0;
        public int rssi = 0;
        public String barcodeData = "";
        public boolean rfidOk = false;
        public boolean gs1Ok = false;
        public boolean weightOk = false;
        public long timestamp;
        public String message;

        public VerificationResult(VerificationLevel level) {
            this.level = level;
            this.timestamp = System.currentTimeMillis();
            this.message = level.label;
        }

        public String getVerificationIcon() {
            if (level.score >= 90) return "✅✅✅";
            if (level.score >= 80) return "✅✅";
            if (level.score >= 50) return "✅";
            if (level.score == 0) return "🚨";
            return "❓";
        }

        public String getColor() {
            if (level.score >= 90) return "#22C55E";
            if (level.score >= 50) return "#F59E0B";
            return "#EF4444";
        }
    }

    public interface VerificationListener {
        void onVerificationComplete(VerificationResult result);
        void onVerificationError(String error);
    }

    public void setListener(VerificationListener listener) {
        this.listener = listener;
    }

    public void verifyFull(String epc, String gs1Data, double actualWeight, int rssi) {
        if (epc == null || epc.trim().isEmpty()) {
            notifyError("رمز EPC غير صالحة");
            return;
        }

        queryMiraCloud(epc, (itemData) -> {
            if (itemData == null) {
                VerificationResult unknownResult = new VerificationResult(VerificationLevel.UNKNOWN);
                unknownResult.epc = epc;
                unknownResult.actualWeight = actualWeight;
                unknownResult.rssi = rssi;
                notifyResult(unknownResult);
                return;
            }

            VerificationResult result = new VerificationResult(VerificationLevel.UNKNOWN);
            result.epc = epc;
            result.actualWeight = actualWeight;
            result.rssi = rssi;
            result.barcodeData = gs1Data != null ? gs1Data : "";
            result.title = itemData.title;
            result.karat = itemData.karat;
            result.expectedWeight = itemData.weight;
            result.gtin = itemData.gtin;
            result.serial = itemData.serial;

            // 1️⃣ تحقق RFID
            result.rfidOk = true;

            // 2️⃣ تحقق GS1
            if (gs1Data != null && !gs1Data.trim().isEmpty()) {
                String extractedGtin = extractGtinFromGS1(gs1Data);
                result.gs1Ok = (itemData.gtin != null && itemData.gtin.equalsIgnoreCase(extractedGtin)) ||
                               (itemData.serial != null && itemData.serial.equalsIgnoreCase(extractedGtin));
            }

            // 3️⃣ تحقق الوزن
            if (actualWeight > 0 && itemData.weight > 0) {
                result.weightOk = Math.abs(itemData.weight - actualWeight) <= WEIGHT_TOLERANCE;
            }

            // 4️⃣ تحديد المستوى النهائي
            result.level = determineLevel(result);
            result.message = result.level.label;

            // تخزين مؤقت
            cache.put(epc, result);

            notifyResult(result);
        });
    }

    public void verifyRfidGs1(String epc, String gs1Data, int rssi) {
        verifyFull(epc, gs1Data, 0, rssi);
    }

    public void verifyRfidWeight(String epc, double actualWeight, int rssi) {
        verifyFull(epc, null, actualWeight, rssi);
    }

    public void verifyRfidOnly(String epc, int rssi) {
        verifyFull(epc, null, 0, rssi);
    }

    private VerificationLevel determineLevel(VerificationResult r) {
        boolean rfid = r.rfidOk;
        boolean gs1 = r.gs1Ok;
        boolean weight = r.weightOk;

        if (rfid && gs1 && weight) return VerificationLevel.TRIPLE_VERIFIED;
        if (rfid && gs1) return VerificationLevel.DOUBLE_VERIFIED_RFID_GS1;
        if (rfid && weight) return VerificationLevel.DOUBLE_VERIFIED_RFID_WEIGHT;
        if (gs1 && weight) return VerificationLevel.DOUBLE_VERIFIED_GS1_WEIGHT;
        if (rfid) return VerificationLevel.SINGLE_VERIFIED_RFID;
        if (gs1) return VerificationLevel.SINGLE_VERIFIED_GS1;
        if (weight) return VerificationLevel.WEIGHT_VERIFIED;
        return VerificationLevel.MISMATCH;
    }

    private String extractGtinFromGS1(String gs1Data) {
        if (gs1Data == null) return "";
        
        // تحليل معيار GS1 (01)
        if (gs1Data.contains("(01)")) {
            int start = gs1Data.indexOf("(01)") + 4;
            int end = gs1Data.indexOf("(", start);
            if (end == -1) end = gs1Data.length();
            String extracted = gs1Data.substring(start, Math.min(start + 14, end)).trim();
            return cleanBarcode(extracted);
        }
        
        return cleanBarcode(gs1Data.trim());
    }

    private String cleanBarcode(String code) {
        return code.replaceAll("[^0-9a-zA-Z]", "");
    }

    private void queryMiraCloud(String code, ItemDataCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/authorize");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                JSONObject body = new JSONObject();
                body.put("epc", code);
                body.put("gate_id", "handheld_c72");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject item = json.optJSONObject("item");

                    if (item != null) {
                        ItemData data = new ItemData();
                        data.title = item.optString("title", "قطعة غير معنونة");
                        data.gtin = item.optString("gtin", "");
                        data.serial = item.optString("serial", "");
                        data.karat = item.optString("karat", "-");
                        data.weight = item.optDouble("weight", 0.0);
                        data.status = item.optString("status", "");
                        data.location = item.optString("location", "");
                        callback.onResult(data);
                        return;
                    }
                }
                callback.onResult(null);
            } catch (Exception e) {
                Log.e(TAG, "API Error: " + e.getMessage());
                callback.onResult(null);
            }
        }).start();
    }

    private void notifyResult(VerificationResult result) {
        handler.post(() -> {
            if (listener != null) {
                listener.onVerificationComplete(result);
            }
        });
    }

    private void notifyError(String errorMsg) {
        handler.post(() -> {
            if (listener != null) {
                listener.onVerificationError(errorMsg);
            }
        });
    }

    interface ItemDataCallback {
        void onResult(ItemData data);
    }

    static class ItemData {
        String title, gtin, serial, karat, status, location;
        double weight;
    }
}
