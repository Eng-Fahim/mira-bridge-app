package com.example.uhf.license

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.uhf.tools.SessionManager

/**
 * ⚠️ TEST ONLY — احذف هذا الملف بعد التحقق
 */
class LicenseTestActivity : AppCompatActivity() {

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(textView) })

        runTests()
    }

    private fun runTests() {
        val sb = StringBuilder()
        val log = { msg: String ->
            Log.d("LicenseTest", msg)
            sb.appendLine(msg)
            textView.text = sb.toString()
        }

        log("═══════════════════════════════════")
        log("   MIRA License — Android Test")
        log("═══════════════════════════════════")
        log("")

        val ctx = applicationContext

        // ─────────────────────────────────
        // [1] Session URL
        // ─────────────────────────────────
        val session = SessionManager(ctx)
        val serverUrl = session.serverUrl
        log("[1] Server URL")
        log("    ${serverUrl ?: "❌ غير محدد"}")
        log("")

        if (serverUrl.isNullOrEmpty()) {
            log("❌ توقف: لم يتم تحديد الخادم")
            return
        }

        // ─────────────────────────────────
        // [2] Fetch Blob
        // ─────────────────────────────────
        log("[2] Fetch Blob")
        val fetch = MiraBlobFetcher.fetch(ctx)

        when (fetch) {
            is MiraBlobFetcher.FetchResult.Error -> {
                log("    ❌ ${fetch.code}: ${fetch.message}")
                return
            }
            is MiraBlobFetcher.FetchResult.Success -> {
                val data = fetch.blobData
                log("    ✅ Fetched")
                log("    version: ${data.version}")
                log("    license_id: ${data.licenseId}")
                log("    max_users: ${data.maxUsers}")
                log("    max_devices: ${data.maxDevices}")
                log("    valid_until: ${data.validUntil}")
                log("    signature_len: ${data.signatureB64.length}")
                log("    pubkey_len: ${data.publicKeyB64.length}")
                log("")

                // ─────────────────────────────────
                // [3] Canonical JSON test
                // ─────────────────────────────────
                log("[3] Canonical JSON")
                val canonical = MiraBlobVerifier.toCanonicalJson(data.blobJson)
                if (canonical != null) {
                    log("    ✅ Generated (${canonical.length} chars)")
                    log("    ${canonical.take(120)}...")
                } else {
                    log("    ❌ Failed")
                }
                log("")

                // ─────────────────────────────────
                // [4] Signature Verify
                // ─────────────────────────────────
                log("[4] Signature Verify")
                val verify = MiraBlobVerifier.verify(
                    data.blobJson, data.signatureB64, data.publicKeyB64
                )
                log("    valid: ${verify.valid}")
                log("    code: ${verify.code}")
                log("    msg: ${verify.message}")
                log("")

                if (!verify.valid) {
                    log("❌ توقف: التوقيع غير صحيح")
                    return
                }

                // ─────────────────────────────────
                // [5] Full Verify + Validate
                // ─────────────────────────────────
                log("[5] Full Verify (with anti-rollback)")
                val current = MiraBlobStorage.load(ctx)
                val full = MiraBlobVerifier.verifyAndValidate(data, current)
                log("    valid: ${full.valid}")
                log("    code: ${full.code}")
                log("    msg: ${full.message}")
                log("")

                // ─────────────────────────────────
                // [6] Save
                // ─────────────────────────────────
                log("[6] Save to EncryptedStorage")
                val saved = MiraBlobStorage.save(ctx, data)
                log("    saved: ${if (saved) "✅" else "❌"}")
                log("")

                // ─────────────────────────────────
                // [7] Reload from Storage
                // ─────────────────────────────────
                log("[7] Reload from Storage")
                val reloaded = MiraBlobStorage.load(ctx)
                if (reloaded != null) {
                    log("    ✅ Loaded")
                    log("    version: ${reloaded.version}")
                    log("    max_users: ${reloaded.maxUsers}")
                } else {
                    log("    ❌ Failed")
                }
                log("")

                // ─────────────────────────────────
                // [8] Tamper Test
                // ─────────────────────────────────
                log("[8] Tamper Detection")
                val tamperedJson = data.blobJson.replace(
                    "\"max_users\":${data.maxUsers}",
                    "\"max_users\":999"
                )
                if (tamperedJson != data.blobJson) {
                    val tamperedResult = MiraBlobVerifier.verify(
                        tamperedJson, data.signatureB64, data.publicKeyB64
                    )
                    log("    valid: ${tamperedResult.valid}")
                    log("    ${if (!tamperedResult.valid) "✅ رُفض (correct)" else "❌ قبل (WRONG!)"}")
                } else {
                    log("    ⚠️ لم نتمكن من تعديل JSON (البحث فشل)")
                }
                log("")

                // ─────────────────────────────────
                // [9] End-to-End
                // ─────────────────────────────────
                log("[9] End-to-End fetchVerifyAndStore")
                val e2e = MiraBlobFetcher.fetchVerifyAndStore(ctx)
                log("    result: ${if (e2e) "✅ Success" else "❌ Failed"}")
                log("")

                log("═══════════════════════════════════")
                log("   ✅ All Tests Completed")
                log("═══════════════════════════════════")
            }
        }
    }
}
