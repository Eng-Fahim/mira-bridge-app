package com.example.uhf.license;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * MiraBlobStorage — تخزين مشفّر لبيانات الـ License Blob
 * يستخدم EncryptedSharedPreferences (AES-256-GCM)
 */
public final class MiraBlobStorage {

    private static final String TAG = "MiraBlobStorage";
    private static final String PREFS_NAME = "mira_license_blob_secure";

    private static final String KEY_BLOB_JSON     = "blob_json";
    private static final String KEY_SIGNATURE     = "signature_b64";
    private static final String KEY_PUBLIC_KEY    = "public_key_b64";
    private static final String KEY_FINGERPRINT   = "fingerprint_b64";
    private static final String KEY_VERSION       = "blob_version";
    private static final String KEY_KEY_ID        = "key_id";
    private static final String KEY_LICENSE_ID    = "license_id";
    private static final String KEY_STORED_AT     = "stored_at";
    private static final String KEY_MAX_USERS     = "max_users";
    private static final String KEY_MAX_DEVICES   = "max_devices";
    private static final String KEY_VALID_UNTIL   = "valid_until";
    private static final String KEY_LAST_VERIFIED = "last_verified_at";

    private MiraBlobStorage() {}

    // ═══════════════════════════════════════════════════════
    //  Data class
    // ═══════════════════════════════════════════════════════
    public static class BlobData {
    public final String blobJson;
    public final String signatureB64;
    public final String publicKeyB64;
    public final String fingerprintB64;
    public final int    version;
    public final String keyId;
    public final int    licenseId;
    public final int    maxUsers;
    public final int    maxDevices;
    public final String validUntil;
    public final long   storedAt;
    // ⚡ جديد
    public final int    activeUsers;
    public final int    activeDevices;

    public BlobData(String blobJson, String signatureB64, String publicKeyB64,
                    String fingerprintB64, int version, String keyId, int licenseId,
                    int maxUsers, int maxDevices, String validUntil, long storedAt,
                    int activeUsers, int activeDevices) {
        this.blobJson       = blobJson;
        this.signatureB64   = signatureB64;
        this.publicKeyB64   = publicKeyB64;
        this.fingerprintB64 = fingerprintB64;
        this.version        = version;
        this.keyId          = keyId;
        this.licenseId      = licenseId;
        this.maxUsers       = maxUsers;
        this.maxDevices     = maxDevices;
        this.validUntil     = validUntil;
        this.storedAt       = storedAt;
        this.activeUsers    = activeUsers;
        this.activeDevices  = activeDevices;
    }
}

    // ═══════════════════════════════════════════════════════
    //  SharedPreferences
    // ═══════════════════════════════════════════════════════
    private static SharedPreferences prefs(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    ctx,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences failed, using fallback", e);
            return ctx.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Save
    // ═══════════════════════════════════════════════════════
    public static boolean save(Context ctx, BlobData data) {
    try {
        boolean ok = prefs(ctx).edit()
                .putString(KEY_BLOB_JSON,    data.blobJson)
                .putString(KEY_SIGNATURE,    data.signatureB64)
                .putString(KEY_PUBLIC_KEY,   data.publicKeyB64)
                .putString(KEY_FINGERPRINT,  data.fingerprintB64)
                .putInt   (KEY_VERSION,      data.version)
                .putString(KEY_KEY_ID,       data.keyId)
                .putInt   (KEY_LICENSE_ID,   data.licenseId)
                .putInt   (KEY_MAX_USERS,    data.maxUsers)
                .putInt   (KEY_MAX_DEVICES,  data.maxDevices)
                .putString(KEY_VALID_UNTIL,  data.validUntil)
                .putLong  (KEY_STORED_AT,    data.storedAt)
                .putLong  (KEY_LAST_VERIFIED, System.currentTimeMillis())
                .putInt   ("active_users",   data.activeUsers)      // ← جديد
                .putInt   ("active_devices", data.activeDevices)    // ← جديد
                .commit();

        if (ok) {
            Log.d(TAG, "✅ Blob saved (v" + data.version 
                    + ", users=" + data.activeUsers + "/" + data.maxUsers + ")");
        } else {
            Log.e(TAG, "🚨 Blob commit FAILED");
        }
        return ok;
    } catch (Exception e) {
        Log.e(TAG, "save error: " + e.getMessage(), e);
        return false;
    }
}

    // ═══════════════════════════════════════════════════════
    //  Load
    // ═══════════════════════════════════════════════════════
    public static BlobData load(Context ctx) {
    try {
        SharedPreferences p = prefs(ctx);
        String blobJson  = p.getString(KEY_BLOB_JSON,  null);
        String signature = p.getString(KEY_SIGNATURE,  null);
        String publicKey = p.getString(KEY_PUBLIC_KEY, null);

        if (blobJson == null || blobJson.isEmpty()
                || signature == null || signature.isEmpty()
                || publicKey == null || publicKey.isEmpty()) {
            return null;
        }

        return new BlobData(
                blobJson,
                signature,
                publicKey,
                safeString(p.getString(KEY_FINGERPRINT, "")),
                p.getInt(KEY_VERSION, 0),
                safeString(p.getString(KEY_KEY_ID, "")),
                p.getInt(KEY_LICENSE_ID, 0),
                p.getInt(KEY_MAX_USERS, 0),
                p.getInt(KEY_MAX_DEVICES, 0),
                safeString(p.getString(KEY_VALID_UNTIL, "")),
                p.getLong(KEY_STORED_AT, 0L),
                p.getInt("active_users", 0),     // ← جديد
                p.getInt("active_devices", 0)    // ← جديد
        );
    } catch (Exception e) {
        Log.e(TAG, "load error: " + e.getMessage(), e);
        return null;
    }
}

    public static boolean hasBlob(Context ctx) {
        return load(ctx) != null;
    }

    public static void updateLastVerified(Context ctx) {
        try {
            prefs(ctx).edit()
                    .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "updateLastVerified error: " + e.getMessage());
        }
    }

    public static void clear(Context ctx) {
        try {
            prefs(ctx).edit().clear().apply();
            Log.d(TAG, "🗑️ Blob storage cleared");
        } catch (Exception e) {
            Log.e(TAG, "clear error: " + e.getMessage());
        }
    }

    public static String getStatus(Context ctx) {
        BlobData data = load(ctx);
        if (data == null) return "No blob stored";
        return "Version: " + data.version + "\n"
                + "License: #" + data.licenseId + "\n"
                + "Max Users: " + data.maxUsers + "\n"
                + "Max Devices: " + data.maxDevices + "\n"
                + "Valid Until: " + data.validUntil + "\n"
                + "Key ID: " + data.keyId + "\n"
                + "Stored: " + new java.util.Date(data.storedAt);
    }

    private static String safeString(String s) {
        return s != null ? s : "";
    }
}
