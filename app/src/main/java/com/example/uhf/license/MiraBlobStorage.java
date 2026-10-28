package com.example.uhf.license

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * MiraBlobStorage — تخزين مشفّر لبيانات الـ License Blob
 * 
 * يستخدم EncryptedSharedPreferences (AES-256-GCM عبر Android Keystore)
 */
object MiraBlobStorage {

    private const val TAG = "MiraBlobStorage"
    private const val PREFS_NAME = "mira_license_blob_secure"

    // Keys
    private const val KEY_BLOB_JSON = "blob_json"
    private const val KEY_SIGNATURE = "signature_b64"
    private const val KEY_PUBLIC_KEY = "public_key_b64"
    private const val KEY_FINGERPRINT = "fingerprint_b64"
    private const val KEY_VERSION = "blob_version"
    private const val KEY_KEY_ID = "key_id"
    private const val KEY_LICENSE_ID = "license_id"
    private const val KEY_STORED_AT = "stored_at"
    private const val KEY_MAX_USERS = "max_users"
    private const val KEY_MAX_DEVICES = "max_devices"
    private const val KEY_VALID_UNTIL = "valid_until"
    private const val KEY_LAST_VERIFIED = "last_verified_at"

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences failed, using fallback", e)
            // fallback (لا يُنصح به في الإنتاج)
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    data class BlobData(
        val blobJson: String,
        val signatureB64: String,
        val publicKeyB64: String,
        val fingerprintB64: String,
        val version: Int,
        val keyId: String,
        val licenseId: Int,
        val maxUsers: Int,
        val maxDevices: Int,
        val validUntil: String,
        val storedAt: Long
    )

    /**
     * حفظ الـ blob في التخزين المشفّر
     */
    fun save(context: Context, data: BlobData): Boolean {
        return try {
            prefs(context).edit()
                .putString(KEY_BLOB_JSON, data.blobJson)
                .putString(KEY_SIGNATURE, data.signatureB64)
                .putString(KEY_PUBLIC_KEY, data.publicKeyB64)
                .putString(KEY_FINGERPRINT, data.fingerprintB64)
                .putInt(KEY_VERSION, data.version)
                .putString(KEY_KEY_ID, data.keyId)
                .putInt(KEY_LICENSE_ID, data.licenseId)
                .putInt(KEY_MAX_USERS, data.maxUsers)
                .putInt(KEY_MAX_DEVICES, data.maxDevices)
                .putString(KEY_VALID_UNTIL, data.validUntil)
                .putLong(KEY_STORED_AT, data.storedAt)
                .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                .commit()
                .also { ok ->
                    if (ok) {
                        Log.d(TAG, "✅ Blob saved (v${data.version}, max_users=${data.maxUsers})")
                    } else {
                        Log.e(TAG, "🚨 Blob commit FAILED")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "save error: ${e.message}", e)
            false
        }
    }

    /**
     * قراءة الـ blob المخزّن
     */
    fun load(context: Context): BlobData? {
        return try {
            val p = prefs(context)
            val blobJson = p.getString(KEY_BLOB_JSON, null)
            val signature = p.getString(KEY_SIGNATURE, null)
            val publicKey = p.getString(KEY_PUBLIC_KEY, null)

            if (blobJson.isNullOrEmpty() || signature.isNullOrEmpty() || publicKey.isNullOrEmpty()) {
                return null
            }

            BlobData(
                blobJson = blobJson,
                signatureB64 = signature,
                publicKeyB64 = publicKey,
                fingerprintB64 = p.getString(KEY_FINGERPRINT, "") ?: "",
                version = p.getInt(KEY_VERSION, 0),
                keyId = p.getString(KEY_KEY_ID, "") ?: "",
                licenseId = p.getInt(KEY_LICENSE_ID, 0),
                maxUsers = p.getInt(KEY_MAX_USERS, 0),
                maxDevices = p.getInt(KEY_MAX_DEVICES, 0),
                validUntil = p.getString(KEY_VALID_UNTIL, "") ?: "",
                storedAt = p.getLong(KEY_STORED_AT, 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "load error: ${e.message}", e)
            null
        }
    }

    /**
     * هل يوجد blob مخزّن؟
     */
    fun hasBlob(context: Context): Boolean {
        return load(context) != null
    }

    /**
     * تحديث آخر وقت للتحقق الناجح
     */
    fun updateLastVerified(context: Context) {
        try {
            prefs(context).edit()
                .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "updateLastVerified error: ${e.message}")
        }
    }

    /**
     * حذف البيانات (عند إعادة التفعيل)
     */
    fun clear(context: Context) {
        try {
            prefs(context).edit().clear().apply()
            Log.d(TAG, "🗑️ Blob storage cleared")
        } catch (e: Exception) {
            Log.e(TAG, "clear error: ${e.message}")
        }
    }

    /**
     * معلومات تشخيصية
     */
    fun getStatus(context: Context): String {
        val data = load(context) ?: return "No blob stored"
        return """
            Version: ${data.version}
            License: #${data.licenseId}
            Max Users: ${data.maxUsers}
            Max Devices: ${data.maxDevices}
            Valid Until: ${data.validUntil}
            Key ID: ${data.keyId}
            Stored: ${java.util.Date(data.storedAt)}
        """.trimIndent()
    }
}
