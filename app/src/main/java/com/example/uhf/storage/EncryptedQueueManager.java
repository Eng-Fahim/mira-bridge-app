package com.example.uhf.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

public class EncryptedQueueManager {

    private static final String TAG = "MIRA_EncryptedQueue";
    private static final String FILE_NAME = "mira_encrypted_audits_queue";
    private static final String KEY_PENDING = "pending_payloads";

    private SharedPreferences encryptedPrefs;

    public EncryptedQueueManager(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SKEY,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Fallback to standard SharedPreferences", e);
            encryptedPrefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    public synchronized void enqueuePayload(JSONObject payload) {
        if (encryptedPrefs == null) return;
        try {
            String rawJson = encryptedPrefs.getString(KEY_PENDING, "[]");
            JSONArray array = new JSONArray(rawJson);
            array.put(payload);

            encryptedPrefs.edit().putString(KEY_PENDING, array.toString()).apply();
            Log.i(TAG, "Payload stored successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to enqueue payload", e);
        }
    }

    public synchronized JSONArray popPendingQueue() {
        if (encryptedPrefs == null) return new JSONArray();
        try {
            String rawJson = encryptedPrefs.getString(KEY_PENDING, "[]");
            JSONArray array = new JSONArray(rawJson);

            encryptedPrefs.edit().putString(KEY_PENDING, "[]").apply();
            return array;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read pending queue", e);
            return new JSONArray();
        }
    }
}
