package com.example.uhf.license;

import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * MiraBlobVerifier — التحقق من توقيع الـ License Blob بـ Ed25519
 *
 * ⚠️ حرج: canonical JSON يجب أن يطابق تماماً ما ينتجه PHP.
 */
public final class MiraBlobVerifier {

    private static final String TAG = "MiraBlobVerifier";

    private static final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private MiraBlobVerifier() {}

    // ═══════════════════════════════════════════════════════
    //  Result classes
    // ═══════════════════════════════════════════════════════
    public static class VerifyResult {
        public final boolean valid;
        public final String code;
        public final String message;
        public final Map<String, Object> details;

        public VerifyResult(boolean valid, String code, String message) {
            this(valid, code, message, new HashMap<String, Object>());
        }

        public VerifyResult(boolean valid, String code, String message, Map<String, Object> details) {
            this.valid = valid;
            this.code = code;
            this.message = message;
            this.details = details;
        }
    }

    public static class BlobValues {
        public final int version;
        public final int licenseId;
        public final int maxUsers;
        public final int maxDevices;
        public final String validUntil;
        public final String validFrom;

        public BlobValues(int version, int licenseId, int maxUsers, int maxDevices,
                          String validUntil, String validFrom) {
            this.version    = version;
            this.licenseId  = licenseId;
            this.maxUsers   = maxUsers;
            this.maxDevices = maxDevices;
            this.validUntil = validUntil;
            this.validFrom  = validFrom;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Core: Verify Signature
    // ═══════════════════════════════════════════════════════
    public static VerifyResult verify(String blobJson, String signatureB64, String publicKeyB64) {
        try {
            if (isEmpty(blobJson) || isEmpty(signatureB64) || isEmpty(publicKeyB64)) {
                return new VerifyResult(false, "missing_input", "بيانات ناقصة للتحقق");
            }

            String canonical = toCanonicalJson(blobJson);
            if (canonical == null) {
                return new VerifyResult(false, "canonical_failed", "فشل تحويل JSON إلى canonical");
            }

            byte[] signature;
            byte[] publicKey;
            try {
                signature = Base64.decode(signatureB64, Base64.DEFAULT);
                publicKey = Base64.decode(publicKeyB64, Base64.DEFAULT);
            } catch (Exception e) {
                return new VerifyResult(false, "invalid_b64", "Base64 غير صالح");
            }

            if (signature.length != 64) {
                return new VerifyResult(false, "invalid_signature_size",
                        "حجم التوقيع غير صحيح (" + signature.length + " != 64)");
            }
            if (publicKey.length != 32) {
                return new VerifyResult(false, "invalid_pubkey_size",
                        "حجم المفتاح العام غير صحيح (" + publicKey.length + " != 32)");
            }

            boolean verified;
            try {
                verified = verifyEd25519(canonical.getBytes("UTF-8"), signature, publicKey);
            } catch (Exception e) {
                Log.e(TAG, "Ed25519 verify exception: " + e.getMessage(), e);
                verified = false;
            }

            if (!verified) {
                Log.w(TAG, "❌ Signature verification FAILED");
                Log.w(TAG, "   Canonical: " + canonical);
                return new VerifyResult(false, "invalid_signature",
                        "التوقيع غير صحيح — قد يكون الـ blob مُعدَّلاً");
            }

            String computedFp = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(publicKey),
                    Base64.NO_WRAP);

            Log.d(TAG, "✅ Signature verified (fp=" + computedFp + ")");

            Map<String, Object> details = new HashMap<>();
            details.put("canonical", canonical);
            details.put("fingerprint", computedFp);

            return new VerifyResult(true, "ok", "التوقيع صحيح", details);

        } catch (Exception e) {
            Log.e(TAG, "verify error: " + e.getMessage(), e);
            return new VerifyResult(false, "exception", "خطأ داخلي: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Full Verify + Validate (Anti-rollback + Time)
    // ═══════════════════════════════════════════════════════
    public static VerifyResult verifyAndValidate(
            MiraBlobStorage.BlobData newBlob,
            MiraBlobStorage.BlobData currentBlob) {
        return verifyAndValidate(newBlob, currentBlob, today());
    }

    public static VerifyResult verifyAndValidate(
            MiraBlobStorage.BlobData newBlob,
            MiraBlobStorage.BlobData currentBlob,
            String currentDate) {

        // [1] التوقيع
        VerifyResult sig = verify(newBlob.blobJson, newBlob.signatureB64, newBlob.publicKeyB64);
        if (!sig.valid) return sig;

        // [2] Fingerprint (إذا كان لدينا blob سابق)
        if (currentBlob != null && !isEmpty(currentBlob.fingerprintB64)) {
            String newFp = (String) sig.details.get("fingerprint");
            if (!isEmpty(newFp) && !constantTimeEquals(newFp, currentBlob.fingerprintB64)) {
                Log.w(TAG, "⚠️ Fingerprint changed: " + currentBlob.fingerprintB64
                        + " → " + newFp);
                if (newBlob.version <= currentBlob.version) {
                    return new VerifyResult(false, "fingerprint_mismatch",
                            "المفتاح العام تغيّر بدون إصدار جديد — مشتبه");
                }
            }
        }

        // [3] Anti-rollback
        if (currentBlob != null && newBlob.version < currentBlob.version) {
            return new VerifyResult(false, "version_rollback",
                    "الإصدار " + newBlob.version + " أقل من " + currentBlob.version);
        }

        // [4] الصلاحية الزمنية
        if (!isEmpty(newBlob.validUntil) && currentDate.compareTo(newBlob.validUntil) > 0) {
            return new VerifyResult(false, "expired",
                    "انتهت صلاحية الترخيص (" + newBlob.validUntil + ")");
        }

        return new VerifyResult(true, "ok", "التحقق الكامل نجح");
    }

    // ═══════════════════════════════════════════════════════
    //  Canonical JSON — مطابق PHP
    // ═══════════════════════════════════════════════════════
    public static String toCanonicalJson(String blobJson) {
        try {
            JsonElement element = JsonParser.parseString(blobJson);
            JsonElement sorted  = sortRecursive(element);
            return gson.toJson(sorted);
        } catch (Exception e) {
            Log.e(TAG, "toCanonicalJson error: " + e.getMessage(), e);
            return null;
        }
    }

    private static JsonElement sortRecursive(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                sorted.put(entry.getKey(), sortRecursive(entry.getValue()));
            }
            JsonObject sortedObj = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                sortedObj.add(entry.getKey(), entry.getValue());
            }
            return sortedObj;
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            JsonArray sortedArr = new JsonArray();
            for (JsonElement item : arr) {
                sortedArr.add(sortRecursive(item));
            }
            return sortedArr;
        }
        return element;
    }

    // ═══════════════════════════════════════════════════════
    //  Ed25519 Core
    // ═══════════════════════════════════════════════════════
    private static boolean verifyEd25519(byte[] message, byte[] signature, byte[] publicKey)
            throws Exception {
        EdDSANamedCurveTable.ED_25519.equals("");
        EdDSAPublicKeySpec spec = new EdDSAPublicKeySpec(
                publicKey,
                EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519));
        EdDSAPublicKey pubKey = new EdDSAPublicKey(spec);

        EdDSAEngine engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
        engine.initVerify(pubKey);
        engine.update(message);
        return engine.verify(signature);
    }

    // ═══════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════
    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String today() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return sdf.format(new Date());
    }

    // ═══════════════════════════════════════════════════════
    //  Extract values from blob JSON
    // ═══════════════════════════════════════════════════════
    public static BlobValues extractBlobValues(String blobJson) {
        try {
            JSONObject obj = new JSONObject(blobJson);
            return new BlobValues(
                    obj.optInt("ver", 0),
                    obj.optInt("license_id", 0),
                    obj.optInt("max_users", 0),
                    obj.optInt("max_devices", 0),
                    obj.optString("valid_until", ""),
                    obj.optString("valid_from", "")
            );
        } catch (Exception e) {
            Log.e(TAG, "extractBlobValues error: " + e.getMessage());
            return null;
        }
    }
}
