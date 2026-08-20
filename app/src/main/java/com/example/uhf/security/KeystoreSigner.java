package com.example.uhf.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;

public class KeystoreSigner {

    private static final String TAG = "MIRA_KeystoreSigner";
    private static final String KEY_ALIAS = "MIRA_INVENTORY_SIGNING_KEY";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    public static void generateHardwareKeyIfNeeded() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER
                );

                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build();

                keyPairGenerator.initialize(spec);
                keyPairGenerator.generateKeyPair();
                Log.i(TAG, "MIRA hardware signing key successfully initialized.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Keystore initialization error", e);
        }
    }

    public static String signData(String data) {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);

            PrivateKey privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
            if (privateKey == null) {
                Log.e(TAG, "Signing key missing in Keystore.");
                return "";
            }

            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));

            byte[] signatureBytes = signature.sign();
            return Base64.encodeToString(signatureBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Data signing execution failed", e);
            return "";
        }
    }
}
