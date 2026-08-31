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
 * 
 * الوضع الحالي: DEBUG MODE
 * - يعرض بصمة الشهادة الفعلية
 * - لا يمنع أي اتصال
 * 
 * بعد الحصول على البصمة:
 * 1. انسخ القيمة
 * 2. ضعها في EXPECTED_PIN
 * 3. احذف PinListener من LoginActivity
 */
public class CertificatePinningManager {

    private static final String TAG = "CertificatePinningManager";
    
    // ✅ فارغة حالياً - سنضع البصمة الحقيقية هنا لاحقاً
    private static final String EXPECTED_PIN = "";
    
    private static SSLContext sslContext;
    private static String lastActualPin = null;
    
    // ✅ مستمع لعرض البصمة
    public interface PinListener {
        void onPinCaptured(String pin);
    }
    
    private static PinListener pinListener;
    private static boolean pinShown = false; // لمنع التكرار

    /**
     * تطبيق Certificate Pinning على اتصال HttpsURLConnection
     */
    public static void applyPinning(HttpsURLConnection connection) {
        try {
            SSLContext context = getPinnedSSLContext();
            connection.setSSLSocketFactory(context.getSocketFactory());
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
                    
                    // ✅ الحصول على البصمة الفعلية
                    String actualPin = getPin(chain[0]);
                    lastActualPin = actualPin;
                    
                    // ✅ DEBUG: عرض البصمة مرة واحدة فقط
                    if (!pinShown && pinListener != null) {
                        pinShown = true;
                        pinListener.onPinCaptured(actualPin);
                    }
                    
                    // ✅ إذا كانت القيمة فارغة، تخطى الفحص (وضع التطوير)
                    if (EXPECTED_PIN == null || EXPECTED_PIN.isEmpty()) {
                        return;
                    }
                    
                    // ✅ التحقق من بصمة الشهادة (وضع الإنتاج)
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
     * الحصول على آخر بصمة تم التقاطها
     */
    public static String getLastActualPin() {
        return lastActualPin;
    }

    /**
     * تعيين مستمع لالتقاط البصمة
     */
    public static void setPinListener(PinListener listener) {
        pinListener = listener;
        pinShown = false;
    }
}
