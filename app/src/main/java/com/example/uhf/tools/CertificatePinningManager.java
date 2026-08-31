package com.example.uhf.tools;

import android.util.Log;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * CertificatePinningManager - إدارة تثبيت الشهادات
 */
public class CertificatePinningManager {

    private static final String TAG = "CertificatePinningManager";
    
    // ✅ بصمة الشهادة الفعلية (SHA-256)
    // يجب استبدال هذه القيمة بالبصمة الحقيقية لشهادة الخادم
    private static final String EXPECTED_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // ← استبدل
    
    private static SSLContext sslContext;

    /**
     * تطبيق Certificate Pinning على اتصال HttpsURLConnection
     */
    public static void applyPinning(HttpsURLConnection connection) {
        try {
            SSLContext context = getPinnedSSLContext();
            connection.setSSLSocketFactory(context.getSocketFactory());
            connection.setHostnameVerifier((hostname, session) -> {
                // ✅ التحقق من اسم المضيف
                return "sa.mirabarcode.com".equals(hostname) || 
                       hostname.endsWith(".mirabarcode.com");
            });
        } catch (Exception e) {
            Log.e(TAG, "Error applying certificate pinning", e);
        }
    }

    /**
     * الحصول على SSLContext مع تثبيت الشهادة
     */
    private static SSLContext getPinnedSSLContext() throws Exception {
        if (sslContext != null) {
            return sslContext;
        }

        TrustManager[] trustManagers = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) 
                        throws CertificateException {
                    // لا نتحقق من العميل
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) 
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("No certificate chain");
                    }
                    
                    // ✅ التحقق من بصمة الشهادة
                    String actualPin = getPin(chain[0]);
                    if (!EXPECTED_PIN.equals(actualPin)) {
                        throw new CertificateException(
                            "Certificate pin mismatch! Expected: " + EXPECTED_PIN + 
                            " Actual: " + actualPin);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };

        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new java.security.SecureRandom());
        return sslContext;
    }

    /**
     * الحصول على بصمة الشهادة
     */
    private static String getPin(X509Certificate certificate) throws CertificateException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(certificate.getPublicKey().getEncoded());
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new CertificateException("Error computing certificate pin", e);
        }
    }

    /**
     * تحويل البايتات إلى Hex
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * دالة مساعدة للحصول على البصمة الفعلية للشهادة
     * استخدمها مرة واحدة للتطوير فقط
     */
    public static String getCertificatePinForDebug(X509Certificate certificate) {
        try {
            return getPin(certificate);
        } catch (Exception e) {
            return null;
        }
    }
}
