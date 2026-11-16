package com.example.uhf.tools;

import android.util.Log;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * CertificatePinningManager - إدارة تثبيت الشهادات
 *
 * ═══════════════════════════════════════════════════════════════════
 *  v2.0 — Pinning على Intermediate CA + Root CA (لا على Leaf)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  لماذا YE1 + ISRG Root X1 (وليس شهادة السيرفر)؟
 *    - شهادة السيرفر (Let's Encrypt) تتجدد كل 90 يوماً → البصمة تتغير → يتعطل التطبيق.
 *    - YE1 (Intermediate) يتغير كل 3-5 سنوات → صيانة معقولة.
 *    - ISRG Root X1 (Root) لا يتغير لعشرات السنين → لا صيانة.
 *    - الجمع بينهما = أمان عالٍ + صيانة نادرة.
 *
 *  آلية التحقق:
 *    1. Android يتحقق من السلسلة ضد CA store (السلوك الافتراضي).
 *    2. نتحقق إضافياً من أن السلسلة تحتوي على واحدة من البصمات المتوقعة.
 *    3. إن طابقت → نقبل. إن لم تطابق → نرفض (CertificateException).
 *
 *  ملاحظة أمنية:
 *    - البصمات في الكود ليست سرّاً — هي معيار تحقق.
 *    - كشفها لا يُمكّن المهاجم من شيء (لأن السيرفر هو من يتحقق من الطلب).
 *    - وجودها يمنع MITM: أي شهادة غير صادرة من YE1/ISRG Root X1 تُرفض.
 */
public class CertificatePinningManager {

    private static final String TAG = "CertificatePinningManager";

    // ═══════════════════════════════════════════════════════
    //  التفعيل
    // ═══════════════════════════════════════════════════════
    public static final boolean PINNING_ENABLED = true;

    // ═══════════════════════════════════════════════════════
    //  البصمات المتوقعة (SHA-256 لمفتاح الشهادة العام)
    // ═══════════════════════════════════════════════════════
    //
    //  ⚠️ استبدل القيمتين أدناه بالبصمتين الحقيقيتين:
    //
    //    YE1 Intermediate:
    //      openssl s_client -connect jewelry.ibreg.org:443 \
    //        -servername jewelry.ibreg.org -showcerts </dev/null 2>/dev/null \
    //        | awk '/BEGIN CERTIFICATE/{n++} n==2{print}' \
    //        | openssl x509 -pubkey -noout \
    //        | openssl pkey -pubin -outform DER \
    //        | openssl dgst -sha256 -hex
    //
    //    ISRG Root X1:
    //      نفس الأمر مع n==4 (أو n==5 حسب سلسلة السيرفر)
    //
    private static final String[] EXPECTED_PINS = {
        // ✅ Let's Encrypt YE1 Intermediate
        "SHA256:6ebcefb4210b088654a38b03fea3d7d1c711b4fb1",

        // ✅ ISRG Root X1 (احتياطي — لا يتغير)
        "SHA256:E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA49"
    };

    // ═══════════════════════════════════════════════════════
    //  الحالة الداخلية
    // ═══════════════════════════════════════════════════════
    private static volatile SSLContext sslContext;
    private static final Object LOCK = new Object();

    // مستمع لعرض البصمات (للتشخيص فقط — يمكن حذفه في الإنتاج)
    public interface PinListener {
        void onPinsCaptured(List<String> pins);
    }

    private static PinListener pinListener;
    private static boolean pinsLogged = false;

    /**
     * تطبيق Pinning على اتصال HttpsURLConnection.
     *
     * يجب استدعاؤها على كل HttpsURLConnection قبل الاتصال.
     */
    public static void applyPinning(HttpsURLConnection connection) {
        if (!PINNING_ENABLED) {
            Log.d(TAG, "Certificate pinning DISABLED - skipping");
            return;
        }
        if (connection == null) return;

        try {
            connection.setSSLSocketFactory(getPinnedSSLContext().getSocketFactory());
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply certificate pinning", e);
        }
    }

    /**
     * الحصول على SSLContext مع Pinning.
     * يُبنى مرة واحدة ويُعاد استخدامه (thread-safe).
     */
    private static SSLContext getPinnedSSLContext() throws Exception {
        if (sslContext != null) return sslContext;

        synchronized (LOCK) {
            if (sslContext != null) return sslContext;

            // 1) نبدأ بـ TrustManager الافتراضي (يتحقق من CA store).
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            X509TrustManager defaultTm = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    defaultTm = (X509TrustManager) tm;
                    break;
                }
            }

            if (defaultTm == null) {
                throw new IllegalStateException("No default X509TrustManager found");
            }

            final X509TrustManager finalDefaultTm = defaultTm;

            // 2) نغلّفه بطبقة pinning.
            TrustManager[] trustManagers = { new X509TrustManager() {

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    finalDefaultTm.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    // (أ) التحقق الافتراضي: السلسلة صالحة ضد CA store؟
                    //     إن فشل هنا، نرفض مباشرة — لا نكمل.
                    finalDefaultTm.checkServerTrusted(chain, authType);

                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("Empty certificate chain");
                    }

                    // (ب) اجمع بصمات كل الشهادات في السلسلة
                    List<String> chainPins = new ArrayList<>(chain.length);
                    for (X509Certificate cert : chain) {
                        String pin = computePin(cert);
                        if (pin != null) chainPins.add(pin);
                    }

                    // عرض البصمات مرة واحدة (تشخيص)
                    if (!pinsLogged) {
                        pinsLogged = true;
                        Log.d(TAG, "Chain pins: " + chainPins);
                        if (pinListener != null) {
                            try { pinListener.onPinsCaptured(chainPins); }
                            catch (Exception ignored) {}
                        }
                    }

                    // (ج) هل تحتوي السلسلة على واحدة من البصمات المتوقعة؟
                    for (String expected : EXPECTED_PINS) {
                        if (expected == null || expected.isEmpty()) continue;
                        for (String actual : chainPins) {
                            if (expected.equalsIgnoreCase(actual)) {
                                // ✅ مطابقة — اقبل
                                return;
                            }
                        }
                    }

                    // (د) لا مطابقة — ارفض
                    throw new CertificateException(
                            "Certificate pin mismatch. Chain pins: " + chainPins
                            + " | Expected one of: " + java.util.Arrays.toString(EXPECTED_PINS));
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return finalDefaultTm.getAcceptedIssuers();
                }
            }};

            // 3) نبني SSLContext
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustManagers, new java.security.SecureRandom());
            sslContext = ctx;

            Log.d(TAG, "✅ Pinned SSLContext initialized (pins configured: "
                    + countRealPins() + ")");
            return sslContext;
        }
    }

    /**
     * عدد البصمات المضبوطة فعلياً (غير placeholder).
     */
    private static int countRealPins() {
        int count = 0;
        for (String pin : EXPECTED_PINS) {
            if (pin != null && !pin.isEmpty() && !pin.startsWith("PUT_")) count++;
        }
        return count;
    }

    /**
     * حساب بصمة SHA-256 لمفتاح الشهادة العام.
     * الصيغة: SHA256:<hex-uppercase>
     */
    private static String computePin(X509Certificate cert) {
        if (cert == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cert.getPublicKey().getEncoded());
            StringBuilder sb = new StringBuilder("SHA256:");
            for (byte b : hash) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "computePin error: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  للتشخيص فقط — يمكن حذفها في الإنتاج
    // ═══════════════════════════════════════════════════════

    /**
     * تعيين مستمع لالتقاط البصمات عند أول اتصال.
     * مفيد أثناء الإعداد لمعرفة ما يُرسله السيرفر فعلاً.
     */
    public static void setPinListener(PinListener listener) {
        pinListener = listener;
        pinsLogged = false;
    }

    /**
     * يعيد قائمة البصمات المتوقعة (للتشخيص).
     */
    public static String[] getExpectedPins() {
        return EXPECTED_PINS.clone();
    }

    /**
     * إعادة تعيين الحالة (للتشخيص فقط).
     */
    public static void reset() {
        synchronized (LOCK) {
            sslContext = null;
            pinsLogged = false;
        }
    }
}
