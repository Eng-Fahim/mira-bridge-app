package com.example.uhf.license

import android.util.Base64
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.json.JSONObject
import java.security.MessageDigest

/**
 * MiraBlobVerifier — التحقق من توقيع الـ License Blob بـ Ed25519
 * 
 * ⚠️ حرج:
 *   - يستخدم canonical JSON يطابق تماماً ما ينتجه PHP
 *   - أي اختلاف في الترتيب/المسافات = فشل التحقق
 *   - يُنتج نتائج ثابتة (deterministic)
 */
object MiraBlobVerifier {

    private const val TAG = "MiraBlobVerifier"

    // Gson instance — يُهيَّأ مرة واحدة
    private val gson = GsonBuilder()
        .disableHtmlEscaping()  // لا escape لـ <> & =
        .serializeNulls()       // للتوافق مع PHP
        .create()

    // ═══════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════

    data class VerifyResult(
        val valid: Boolean,
        val code: String,
        val message: String,
        val details: Map<String, Any> = emptyMap()
    )

    /**
     * التحقق من توقيع blob
     * 
     * @param blobJson JSON string للـ blob (كما ورد من الخادم)
     * @param signatureB64 توقيع Ed25519 في Base64
     * @param publicKeyB64 المفتاح العام Ed25519 في Base64 (32 bytes)
     * @return VerifyResult
     */
    fun verify(blobJson: String, signatureB64: String, publicKeyB64: String): VerifyResult {
        try {
            // [1] فحص المدخلات
            if (blobJson.isEmpty() || signatureB64.isEmpty() || publicKeyB64.isEmpty()) {
                return VerifyResult(
                    valid = false,
                    code = "missing_input",
                    message = "بيانات ناقصة للتحقق"
                )
            }

            // [2] Canonical JSON
            val canonical = toCanonicalJson(blobJson)
            if (canonical == null) {
                return VerifyResult(
                    valid = false,
                    code = "canonical_failed",
                    message = "فشل تحويل JSON إلى صيغة canonical"
                )
            }

            // [3] فك Base64
            val signature = try {
                Base64.decode(signatureB64, Base64.DEFAULT)
            } catch (e: Exception) {
                return VerifyResult(
                    valid = false,
                    code = "invalid_signature_b64",
                    message = "التوقيع ليس Base64 صالحاً"
                )
            }

            val publicKey = try {
                Base64.decode(publicKeyB64, Base64.DEFAULT)
            } catch (e: Exception) {
                return VerifyResult(
                    valid = false,
                    code = "invalid_pubkey_b64",
                    message = "المفتاح العام ليس Base64 صالحاً"
                )
            }

            // [4] فحص الأحجام
            if (signature.size != 64) {
                return VerifyResult(
                    valid = false,
                    code = "invalid_signature_size",
                    message = "حجم التوقيع غير صحيح (${signature.size} != 64)"
                )
            }
            if (publicKey.size != 32) {
                return VerifyResult(
                    valid = false,
                    code = "invalid_pubkey_size",
                    message = "حجم المفتاح العام غير صحيح (${publicKey.size} != 32)"
                )
            }

            // [5] التحقق باستخدام Ed25519
            val verified = try {
                verifyEd25519(canonical.toByteArray(Charsets.UTF_8), signature, publicKey)
            } catch (e: Exception) {
                Log.e(TAG, "Ed25519 verify exception", e)
                false
            }

            if (!verified) {
                Log.w(TAG, "❌ Signature verification FAILED")
                Log.w(TAG, "   Canonical: $canonical")
                Log.w(TAG, "   Signature: $signatureB64")
                Log.w(TAG, "   PublicKey: $publicKeyB64")
                return VerifyResult(
                    valid = false,
                    code = "invalid_signature",
                    message = "التوقيع غير صحيح — قد يكون الـ blob مُعدَّلاً"
                )
            }

            // [6] التحقق من الـ fingerprint
            val computedFp = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKey),
                Base64.NO_WRAP
            )

            Log.d(TAG, "✅ Signature verified (fingerprint=$computedFp)")

            return VerifyResult(
                valid = true,
                code = "ok",
                message = "التوقيع صحيح",
                details = mapOf(
                    "canonical" to canonical,
                    "fingerprint" to computedFp
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "verify error: ${e.message}", e)
            return VerifyResult(
                valid = false,
                code = "exception",
                message = "خطأ داخلي: ${e.message}"
            )
        }
    }

    /**
     * التحقق الكامل من blob + فحص الصلاحية الزمنية + anti-rollback
     * 
     * @param currentBlob الـ blob المخزّن حالياً (للـ anti-rollback)
     * @param newBlob الـ blob الجديد المراد التحقق منه
     */
    fun verifyAndValidate(
        newBlob: MiraBlobStorage.BlobData,
        currentBlob: MiraBlobStorage.BlobData? = null,
        currentDate: String = today()
    ): VerifyResult {
        // [1] التحقق من التوقيع
        val sigResult = verify(newBlob.blobJson, newBlob.signatureB64, newBlob.publicKeyB64)
        if (!sigResult.valid) {
            return sigResult
        }

        // [2] فحص fingerprint إذا كان لدينا blob سابق
        if (currentBlob != null && currentBlob.fingerprintB64.isNotEmpty()) {
            val newFp = sigResult.details["fingerprint"] as? String ?: ""
            if (newFp.isNotEmpty() && !constantTimeEquals(newFp, currentBlob.fingerprintB64)) {
                Log.w(TAG, "⚠️ Fingerprint changed: ${currentBlob.fingerprintB64} → $newFp")
                // هذا مقبول فقط إذا version أعلى (key rotation)
                if (newBlob.version <= currentBlob.version) {
                    return VerifyResult(
                        valid = false,
                        code = "fingerprint_mismatch",
                        message = "المفتاح العام تغيّر بدون إصدار جديد — مشتبه"
                    )
                }
            }
        }

        // [3] Anti-rollback
        if (currentBlob != null && newBlob.version < currentBlob.version) {
            return VerifyResult(
                valid = false,
                code = "version_rollback",
                message = "الإصدار ${newBlob.version} أقل من ${currentBlob.version}"
            )
        }

        // [4] الصلاحية الزمنية
        if (newBlob.validUntil.isNotEmpty() && currentDate > newBlob.validUntil) {
            return VerifyResult(
                valid = false,
                code = "expired",
                message = "انتهت صلاحية الترخيص (${newBlob.validUntil})"
            )
        }

        return VerifyResult(
            valid = true,
            code = "ok",
            message = "التحقق الكامل نجح"
        )
    }

    // ═══════════════════════════════════════════════════════
    //  Canonical JSON — مطابق تماماً لـ PHP
    // ═══════════════════════════════════════════════════════

    /**
     * تحويل JSON إلى canonical:
     *   - فرز المفاتيح alphabetically (recursive)
     *   - لا مسافات
     *   - لا unicode escaping
     *   - لا HTML escaping
     * 
     * يجب أن يطابق PHP's canonical_json تماماً byte-for-byte
     */
    fun toCanonicalJson(blobJson: String): String? {
        return try {
            val element = JsonParser.parseString(blobJson)
            val sorted = sortRecursive(element)
            val result = gson.toJson(sorted)

            // فحص: لا مسافات في الأماكن غير المتوقعة
            // (Gson لا يضع مسافات خارج السلاسل النصية)
            result

        } catch (e: Exception) {
            Log.e(TAG, "toCanonicalJson error: ${e.message}", e)
            null
        }
    }

    /**
     * فرز المفاتيح بشكل recursive
     */
    private fun sortRecursive(element: JsonElement): JsonElement {
        return when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val sortedObj = JsonObject()
                obj.entrySet()
                    .sortedBy { it.key }
                    .forEach { (key, value) ->
                        sortedObj.add(key, sortRecursive(value))
                    }
                sortedObj
            }
            element.isJsonArray -> {
                val arr = element.asJsonArray
                val sortedArr = JsonArray()
                arr.forEach { sortedArr.add(sortRecursive(it)) }
                sortedArr
            }
            else -> element
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Ed25519 Core
    // ═══════════════════════════════════════════════════════

    private fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        val params = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val pubKeySpec = EdDSAPublicKeySpec(publicKey, params)
        val pubKey = EdDSAPublicKey(pubKeySpec)

        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initVerify(pubKey)
        engine.update(message)

        return engine.verify(signature)
    }

    // ═══════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun today(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    /**
     * استخراج قيم من blob JSON (للتخزين)
     */
    fun extractBlobValues(blobJson: String): BlobValues? {
        return try {
            val obj = JSONObject(blobJson)
            BlobValues(
                version = obj.optInt("ver", 0),
                licenseId = obj.optInt("license_id", 0),
                maxUsers = obj.optInt("max_users", 0),
                maxDevices = obj.optInt("max_devices", 0),
                validUntil = obj.optString("valid_until", ""),
                validFrom = obj.optString("valid_from", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "extractBlobValues error: ${e.message}")
            null
        }
    }

    data class BlobValues(
        val version: Int,
        val licenseId: Int,
        val maxUsers: Int,
        val maxDevices: Int,
        val validUntil: String,
        val validFrom: String
    )
}
