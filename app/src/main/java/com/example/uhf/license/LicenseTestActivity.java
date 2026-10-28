package com.example.uhf.license;

import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.tools.SessionManager;

/**
 * ⚠️ TEST ONLY — احذف هذا الملف بعد التحقق
 */
public class LicenseTestActivity extends AppCompatActivity {

    private static final String TAG = "LicenseTest";
    private TextView textView;
    private StringBuilder logBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        logBuffer = new StringBuilder();
        textView = new TextView(this);
        textView.setPadding(32, 32, 32, 32);
        textView.setTextSize(11f);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(textView);
        setContentView(scroll);

        runTests();
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        logBuffer.append(msg).append("\n");
        textView.setText(logBuffer.toString());
    }

    private void runTests() {
        log("═══════════════════════════════════════");
        log("   MIRA License — Android Test (Java)");
        log("═══════════════════════════════════════");
        log("");

        final android.content.Context ctx = getApplicationContext();

        // ─────────────────────────────────
        // [1] Server URL
        // ─────────────────────────────────
        SessionManager session = new SessionManager(ctx);
        String serverUrl = session.getServerUrl();
        log("[1] Server URL");
        log("    " + (serverUrl != null && !serverUrl.isEmpty() ? serverUrl : "❌ غير محدد"));
        log("");

        if (serverUrl == null || serverUrl.isEmpty()) {
            log("❌ توقف: لم يتم تحديد الخادم");
            return;
        }

        // ─────────────────────────────────
        // [2] Fetch
        // ─────────────────────────────────
        log("[2] Fetch Blob");
        MiraBlobFetcher.FetchResult fetch = MiraBlobFetcher.fetch(ctx);

        if (!fetch.success) {
            log("    ❌ " + fetch.code + ": " + fetch.message);
            return;
        }

        MiraBlobStorage.BlobData data = fetch.blobData;
        log("    ✅ Fetched");
        log("    version: " + data.version);
        log("    license_id: " + data.licenseId);
        log("    max_users: " + data.maxUsers);
        log("    max_devices: " + data.maxDevices);
        log("    valid_until: " + data.validUntil);
        log("    signature_len: " + data.signatureB64.length());
        log("    pubkey_len: " + data.publicKeyB64.length());
        log("");

        // ─────────────────────────────────
        // [3] Canonical JSON
        // ─────────────────────────────────
        log("[3] Canonical JSON");
        String canonical = MiraBlobVerifier.toCanonicalJson(data.blobJson);
        if (canonical != null) {
            log("    ✅ " + canonical.length() + " chars");
            log("    " + (canonical.length() > 120 ? canonical.substring(0, 120) + "..." : canonical));
        } else {
            log("    ❌ Failed");
        }
        log("");

        // ─────────────────────────────────
        // [4] Signature Verify
        // ─────────────────────────────────
        log("[4] Signature Verify");
        MiraBlobVerifier.VerifyResult verify =
                MiraBlobVerifier.verify(data.blobJson, data.signatureB64, data.publicKeyB64);
        log("    valid: " + verify.valid);
        log("    code: " + verify.code);
        log("    msg: " + verify.message);
        log("");

        if (!verify.valid) {
            log("❌ توقف: التوقيع غير صحيح");
            log("");
            log("Canonical (للمقارنة مع PHP):");
            log(canonical != null ? canonical : "null");
            return;
        }

        // ─────────────────────────────────
        // [5] Full Verify
        // ─────────────────────────────────
        log("[5] Full Verify (anti-rollback)");
        MiraBlobStorage.BlobData current = MiraBlobStorage.load(ctx);
        MiraBlobVerifier.VerifyResult full =
                MiraBlobVerifier.verifyAndValidate(data, current);
        log("    valid: " + full.valid);
        log("    code: " + full.code);
        log("    msg: " + full.message);
        log("");

        // ─────────────────────────────────
        // [6] Save
        // ─────────────────────────────────
        log("[6] Save to EncryptedStorage");
        boolean saved = MiraBlobStorage.save(ctx, data);
        log("    saved: " + (saved ? "✅" : "❌"));
        log("");

        // ─────────────────────────────────
        // [7] Reload
        // ─────────────────────────────────
        log("[7] Reload from Storage");
        MiraBlobStorage.BlobData reloaded = MiraBlobStorage.load(ctx);
        if (reloaded != null) {
            log("    ✅ version=" + reloaded.version + ", max_users=" + reloaded.maxUsers);
        } else {
            log("    ❌ Failed");
        }
        log("");

        // ─────────────────────────────────
        // [8] Tamper Detection
        // ─────────────────────────────────
        log("[8] Tamper Detection");
        String tamperedJson = data.blobJson.replace(
                "\"max_users\":" + data.maxUsers,
                "\"max_users\":999");
        if (!tamperedJson.equals(data.blobJson)) {
            MiraBlobVerifier.VerifyResult tampered =
                    MiraBlobVerifier.verify(tamperedJson, data.signatureB64, data.publicKeyB64);
            log("    valid: " + tampered.valid);
            log("    " + (!tampered.valid ? "✅ رُفض (correct)" : "❌ قبل (WRONG!)"));
        } else {
            log("    ⚠️ لم نتمكن من تعديل JSON");
        }
        log("");

        // ─────────────────────────────────
        // [9] End-to-End
        // ─────────────────────────────────
        log("[9] End-to-End fetchVerifyAndStore");
        boolean e2e = MiraBlobFetcher.fetchVerifyAndStore(ctx);
        log("    result: " + (e2e ? "✅ Success" : "❌ Failed"));
        log("");

        log("═══════════════════════════════════════");
        log("   ✅ All Tests Completed");
        log("═══════════════════════════════════════");
    }
}
