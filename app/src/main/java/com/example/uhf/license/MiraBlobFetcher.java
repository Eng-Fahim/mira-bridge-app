package com.example.uhf.license

import android.content.Context
import android.util.Log
import com.example.uhf.tools.CertificatePinningManager
import com.example.uhf.tools.SessionManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * MiraBlobFetcher — يجلب الـ blob من خادم العميل ويخزّنه
 */
object MiraBlobFetcher {

    private const val TAG = "MiraBlobFetcher"
    private const val TIMEOUT_MS = 15_000

    sealed class FetchResult {
        data class Success(val blobData: MiraBlobStorage.BlobData) : FetchResult()
        data class Error(val code: String, val message: String) : FetchResult()
    }

    /**
     * جلب الـ blob من /wp-json/mira-license/v1/client-blob
     */
    fun fetch(context: Context): FetchResult {
        var conn: HttpURLConnection? = null
        try {
            val session = SessionManager(context)
            val serverUrl = session.serverUrl?.trimEnd('/') ?: return FetchResult.Error(
                "no_server_url", "عنوان الخادم غير محدد"
            )

            val fullUrl = "$serverUrl/wp-json/mira-license/v1/client-blob"
            Log.d(TAG, "Fetching: $fullUrl")

            val url = URL(fullUrl)
            if (!"https".equals(url.protocol, ignoreCase = true)) {
                return FetchResult.Error("insecure", "يُشترط HTTPS")
            }

            val https = url.openConnection() as HttpsURLConnection
            CertificatePinningManager.applyPinning(https)
            conn = https

            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MIRA-Android/${appVersion(context)}")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream

            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r ->
                    r.readText()
                }
            } ?: ""

            Log.d(TAG, "HTTP $code | ${body.take(200)}")

            if (code !in 200..299) {
                return try {
                    val err = JSONObject(body)
                    FetchResult.Error(
                        err.optString("code", "http_$code"),
                        err.optString("message", "HTTP $code")
                    )
                } catch (e: Exception) {
                    FetchResult.Error("http_$code", "HTTP $code: ${body.take(100)}")
                }
            }

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                return FetchResult.Error(
                    json.optString("code", "failed"),
                    json.optString("message", "Unknown error")
                )
            }

            val blobObj = json.optJSONObject("blob")
                ?: return FetchResult.Error("no_blob", "Blob مفقود في الاستجابة")

            val blobJson = blobObj.toString()
            val values = MiraBlobVerifier.extractBlobValues(blobJson)
                ?: return FetchResult.Error("invalid_blob", "Blob JSON غير صالح")

            val data = MiraBlobStorage.BlobData(
                blobJson = blobJson,
                signatureB64 = json.optString("signature"),
                publicKeyB64 = json.optString("public_key"),
                fingerprintB64 = json.optString("fingerprint"),
                version = json.optInt("version", values.version),
                keyId = json.optString("key_id"),
                licenseId = json.optInt("license_id", values.licenseId),
                maxUsers = values.maxUsers,
                maxDevices = values.maxDevices,
                validUntil = values.validUntil,
                storedAt = System.currentTimeMillis()
            )

            return FetchResult.Success(data)

        } catch (e: Exception) {
            Log.e(TAG, "fetch error: ${e.message}", e)
            return FetchResult.Error("network", e.message ?: "فشل الاتصال")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * جلب + تحقق + تخزين
     * 
     * هذه هي الدالة التي تُستدعى من أماكن متعددة
     */
    fun fetchVerifyAndStore(context: Context): Boolean {
        Log.d(TAG, "🔄 fetchVerifyAndStore started")

        // [1] جلب
        val fetchResult = fetch(context)
        val newBlob = when (fetchResult) {
            is FetchResult.Error -> {
                Log.w(TAG, "❌ Fetch failed: ${fetchResult.code} — ${fetchResult.message}")
                return false
            }
            is FetchResult.Success -> fetchResult.blobData
        }

        // [2] قراءة blob الحالي (للـ anti-rollback)
        val currentBlob = MiraBlobStorage.load(context)

        // [3] تحقق شامل
        val verifyResult = MiraBlobVerifier.verifyAndValidate(newBlob, currentBlob)
        if (!verifyResult.valid) {
            Log.e(TAG, "❌ Verify failed: ${verifyResult.code} — ${verifyResult.message}")
            return false
        }

        // [4] تخزين
        val saved = MiraBlobStorage.save(context, newBlob)
        if (!saved) {
            Log.e(TAG, "❌ Storage failed")
            return false
        }

        Log.d(TAG, "✅ Blob v${newBlob.version} verified and stored " +
                "(max_users=${newBlob.maxUsers})")
        return true
    }

    private fun appVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}
