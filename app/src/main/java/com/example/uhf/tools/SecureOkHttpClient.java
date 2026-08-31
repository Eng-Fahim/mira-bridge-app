package com.example.uhf.tools;

import android.util.Log;

import com.example.uhf.BuildConfig;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * SecureOkHttpClient - عميل HTTP آمن مع Certificate Pinning
 */
public class SecureOkHttpClient {

    private static final String TAG = "SecureOkHttpClient";
    
    // ✅ بصمات الشهادات (SHA-256)
    // يجب استبدال هذه القيم بالبصمات الفعلية لشهادة الخادم
    private static final String SERVER_HOST = "sa.mirabarcode.com";
    private static final String SERVER_PIN_SHA256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; // ← استبدل بالبصمة الفعلية
    
    private static volatile OkHttpClient instance;

    private SecureOkHttpClient() {
        // منع الإنشاء المباشر
    }

    /**
     * الحصول على OkHttpClient الآمن
     */
    public static OkHttpClient getInstance() {
        if (instance == null) {
            synchronized (SecureOkHttpClient.class) {
                if (instance == null) {
                    instance = buildSecureClient();
                }
            }
        }
        return instance;
    }

    /**
     * بناء العميل الآمن
     */
    private static OkHttpClient buildSecureClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);

        // ✅ Certificate Pinning
        CertificatePinner certificatePinner = new CertificatePinner.Builder()
                .add(SERVER_HOST, SERVER_PIN_SHA256)
                .build();
        builder.certificatePinner(certificatePinner);

        // ✅ سجلات HTTP (للتطوير فقط)
        if (BuildConfig.ENABLE_LOGGING) {
            HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder.addInterceptor(loggingInterceptor);
        }

        return builder.build();
    }

    /**
     * الحصول على البصمة الفعلية للشهادة
     * استخدم هذه الدالة مرة واحدة للحصول على البصمة الصحيحة
     */
    public static String getCertificatePin(X509Certificate certificate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(certificate.getPublicKey().getEncoded());
            return "sha256/" + bytesToHex(hash);
        } catch (Exception e) {
            Log.e(TAG, "Error getting certificate pin", e);
            return null;
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
     * إعادة تعيين العميل (عند تغيير الخادم)
     */
    public static void reset() {
        instance = null;
    }
}
