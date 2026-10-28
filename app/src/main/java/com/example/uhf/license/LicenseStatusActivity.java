package com.example.uhf.license;

import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * LicenseStatusActivity — v2.0
 * 
 * إصلاحات v2.0:
 *   - onResume() يبدأ auto-refresh كل 30 ثانية
 *   - onPause() يوقف auto-refresh (يوفر البطارية)
 *   - onDestroy() ينظف handler
 *   - عرض عدد المستخدمين والأجهزة الفعلي (activeUsers/activeDevices)
 *   - loadLicenseStatus() لا يعمل في onCreate (يعمل في onResume)
 */
public class LicenseStatusActivity extends AppCompatActivity {

    private static final String TAG = "LicenseStatus";

    // ═══ Auto-Refresh ═══
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L; // 30 ثانية
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "⏰ Auto-refresh tick");
            loadLicenseStatus();
            refreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
        }
    };

    // ═══ Views ═══
    private LinearLayout statusCard;
    private ProgressBar progressLoading;
    private TextView tvStatusIcon, tvStatusTitle, tvStatusSubtitle;
    private TextView tvUsersValue, tvDevicesValue, tvTypeValue, tvDaysValue;
    private TextView tvValidUntil, tvTechnicalDetails;
    private LinearLayout layoutTechnicalDetails;
    private android.widget.Button btnRefresh, btnShowDetails;
    private ImageButton btnRefreshHeader;

    // ═══ State ═══
    private boolean isArabic = true;
    private boolean detailsVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_license_status);

        isArabic = getResources().getConfiguration()
                .getLocales().get(0).getLanguage().equals("ar");

        initViews();
        attachListeners();
        // ⚠️ لا نُحمّل هنا — سنُحمّل في onResume
    }

    // ═══════════════════════════════════════════════════════
    //  Lifecycle — Auto-refresh يُدار هنا
    // ═══════════════════════════════════════════════════════
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "▶️ onResume — starting auto-refresh");

        // 1) تحديث فوري عند العودة للشاشة
        loadLicenseStatus();

        // 2) ثم تحديث تلقائي كل 30 ثانية
        refreshHandler.removeCallbacks(autoRefreshRunnable);
        refreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "⏸️ onPause — stopping auto-refresh");
        refreshHandler.removeCallbacks(autoRefreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacksAndMessages(null);
    }

    // ═══════════════════════════════════════════════════════
    //  Init
    // ═══════════════════════════════════════════════════════
    private void initViews() {
        statusCard              = findViewById(R.id.statusCard);
        progressLoading         = findViewById(R.id.progressLoading);
        tvStatusIcon            = findViewById(R.id.tvStatusIcon);
        tvStatusTitle           = findViewById(R.id.tvStatusTitle);
        tvStatusSubtitle        = findViewById(R.id.tvStatusSubtitle);
        tvUsersValue            = findViewById(R.id.tvUsersValue);
        tvDevicesValue          = findViewById(R.id.tvDevicesValue);
        tvTypeValue             = findViewById(R.id.tvTypeValue);
        tvDaysValue             = findViewById(R.id.tvDaysValue);
        tvValidUntil            = findViewById(R.id.tvValidUntil);
        tvTechnicalDetails      = findViewById(R.id.tvTechnicalDetails);
        layoutTechnicalDetails  = findViewById(R.id.layoutTechnicalDetails);
        btnRefresh              = findViewById(R.id.btnRefresh);
        btnShowDetails          = findViewById(R.id.btnShowDetails);
        btnRefreshHeader        = findViewById(R.id.btnRefreshHeader);
    }

    private void attachListeners() {
        btnRefresh.setOnClickListener(v -> {
            animateButton(v);
            loadLicenseStatus();
        });

        btnRefreshHeader.setOnClickListener(v -> loadLicenseStatus());

        btnShowDetails.setOnClickListener(v -> {
            detailsVisible = !detailsVisible;
            layoutTechnicalDetails.setVisibility(detailsVisible ? View.VISIBLE : View.GONE);
            if (detailsVisible) {
                layoutTechnicalDetails.setAlpha(0f);
                layoutTechnicalDetails.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .start();
            }
            btnShowDetails.setText(detailsVisible
                    ? "🔍  إخفاء"
                    : "🔍  تفاصيل");
        });
    }

    // ═══════════════════════════════════════════════════════
    //  Load License Status (background thread)
    // ═══════════════════════════════════════════════════════
    private void loadLicenseStatus() {
        showLoading();

        new Thread(() -> {
            try {
                // [1] جلب blob من الخادم
                MiraBlobFetcher.FetchResult fetch = MiraBlobFetcher.fetch(this);

                if (!fetch.success) {
                    runOnUiThread(() -> showErrorState(fetch.code, fetch.message));
                    return;
                }

                MiraBlobStorage.BlobData data = fetch.blobData;

                // [2] قراءة blob الحالي (للـ anti-rollback)
                MiraBlobStorage.BlobData current = MiraBlobStorage.load(this);

                // [3] تحقق شامل
                MiraBlobVerifier.VerifyResult verify =
                        MiraBlobVerifier.verifyAndValidate(data, current);

                // [4] حفظ إذا نجح
                if (verify.valid) {
                    MiraBlobStorage.save(this, data);
                }

                // [5] تحديث الواجهة
                runOnUiThread(() -> {
                    if (verify.valid) {
                        showValidState(data);
                    } else {
                        showInvalidState(verify.code, verify.message, data);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "loadLicenseStatus error", e);
                runOnUiThread(() ->
                        showErrorState("exception", e.getMessage() != null
                                ? e.getMessage()
                                : "خطأ غير متوقع"));
            }
        }, "LicenseStatusLoader").start();
    }

    // ═══════════════════════════════════════════════════════
    //  Show Valid
    // ═══════════════════════════════════════════════════════
    private void showValidState(MiraBlobStorage.BlobData data) {
        hideLoading();

        statusCard.setBackgroundResource(R.drawable.bg_status_valid);
        tvStatusIcon.setText("✅");
        tvStatusTitle.setText(isArabic ? "الترخيص سليم" : "License Valid");
        tvStatusTitle.setTextColor(Color.parseColor("#1B5E20"));
        tvStatusSubtitle.setText(isArabic
                ? "جميع الفحوصات ناجحة"
                : "All checks passed");
        tvStatusSubtitle.setTextColor(Color.parseColor("#2E7D32"));

        // ═══ Info cards — عرض الأعداد الفعلية ═══
        // المستخدمون
        if (data.activeUsers >= 0 && data.maxUsers > 0) {
            tvUsersValue.setText(data.activeUsers + "/" + data.maxUsers);
        } else if (data.maxUsers > 0) {
            tvUsersValue.setText("—/" + data.maxUsers);
        } else {
            tvUsersValue.setText("—");
        }

        // الأجهزة
        if (data.activeDevices >= 0 && data.maxDevices > 0) {
            tvDevicesValue.setText(data.activeDevices + "/" + data.maxDevices);
        } else if (data.maxDevices > 0) {
            tvDevicesValue.setText("—/" + data.maxDevices);
        } else {
            tvDevicesValue.setText("—");
        }

        tvTypeValue.setText(formatLicenseType(data));

        // Days left
        int daysLeft = calculateDaysLeft(data.validUntil);
        tvDaysValue.setText(String.valueOf(Math.max(0, daysLeft)));
        tvDaysValue.setTextColor(daysLeft > 30
                ? Color.parseColor("#1A237E")
                : Color.parseColor("#E65100"));

        // Valid until
        tvValidUntil.setText(formatDate(data.validUntil));

        // Technical details
        tvTechnicalDetails.setText(buildTechnicalDetails(data));

        animateStatusCard();
    }

    // ═══════════════════════════════════════════════════════
    //  Show Invalid
    // ═══════════════════════════════════════════════════════
    private void showInvalidState(String code, String message, MiraBlobStorage.BlobData data) {
        hideLoading();

        boolean isExpired = "expired".equals(code);
        boolean isTampered = "invalid_signature".equals(code)
                || "fingerprint_mismatch".equals(code)
                || "version_rollback".equals(code);

        if (isExpired) {
            statusCard.setBackgroundResource(R.drawable.bg_status_warning);
            tvStatusIcon.setText("⏰");
            tvStatusTitle.setText(isArabic ? "انتهى الترخيص" : "License Expired");
            tvStatusTitle.setTextColor(Color.parseColor("#E65100"));
            tvStatusSubtitle.setText(isArabic
                    ? "تواصل مع ميرا للتجديد"
                    : "Contact MIRA to renew");
            tvStatusSubtitle.setTextColor(Color.parseColor("#EF6C00"));
        } else if (isTampered) {
            statusCard.setBackgroundResource(R.drawable.bg_status_invalid);
            tvStatusIcon.setText("⚠️");
            tvStatusTitle.setText(isArabic ? "ترخيص مُعدَّل" : "License Tampered");
            tvStatusTitle.setTextColor(Color.parseColor("#B71C1C"));
            tvStatusSubtitle.setText(isArabic
                    ? "التوقيع الرقمي غير صحيح — قد يكون الملف مُعدَّلاً"
                    : "Invalid signature — file may be tampered");
            tvStatusSubtitle.setTextColor(Color.parseColor("#C62828"));
        } else {
            statusCard.setBackgroundResource(R.drawable.bg_status_invalid);
            tvStatusIcon.setText("❌");
            tvStatusTitle.setText(isArabic ? "ترخيص غير صالح" : "License Invalid");
            tvStatusTitle.setTextColor(Color.parseColor("#B71C1C"));
            tvStatusSubtitle.setText(message);
            tvStatusSubtitle.setTextColor(Color.parseColor("#C62828"));
        }

        if (data != null) {
            tvUsersValue.setText((data.activeUsers >= 0 && data.maxUsers > 0)
                    ? data.activeUsers + "/" + data.maxUsers
                    : (data.maxUsers > 0 ? "—/" + data.maxUsers : "—"));
            tvDevicesValue.setText((data.activeDevices >= 0 && data.maxDevices > 0)
                    ? data.activeDevices + "/" + data.maxDevices
                    : (data.maxDevices > 0 ? "—/" + data.maxDevices : "—"));
            tvTypeValue.setText(formatLicenseType(data));
            tvValidUntil.setText(formatDate(data.validUntil));
            int daysLeft = calculateDaysLeft(data.validUntil);
            tvDaysValue.setText(String.valueOf(Math.max(0, daysLeft)));
            tvTechnicalDetails.setText(buildTechnicalDetails(data)
                    + "\n\n❌ سبب الرفض: " + code + " — " + message);
        } else {
            tvUsersValue.setText("—");
            tvDevicesValue.setText("—");
            tvTypeValue.setText("—");
            tvDaysValue.setText("—");
            tvValidUntil.setText("—");
            tvTechnicalDetails.setText("Error: " + code + " — " + message);
        }

        animateStatusCard();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ═══════════════════════════════════════════════════════
    //  Show Error (network)
    // ═══════════════════════════════════════════════════════
    private void showErrorState(String code, String message) {
        hideLoading();

        statusCard.setBackgroundResource(R.drawable.bg_status_warning);
        tvStatusIcon.setText("📡");
        tvStatusTitle.setText(isArabic ? "فشل الاتصال" : "Connection Failed");
        tvStatusTitle.setTextColor(Color.parseColor("#E65100"));

        String friendlyMessage;
        switch (code) {
            case "dns":
            case "network":
                friendlyMessage = isArabic
                        ? "تحقق من اتصالك بالإنترنت"
                        : "Check your internet connection";
                break;
            case "timeout":
                friendlyMessage = isArabic
                        ? "انتهت مهلة الاتصال — حاول مرة أخرى"
                        : "Connection timed out — try again";
                break;
            case "ssl":
                friendlyMessage = isArabic
                        ? "فشل الاتصال الآمن — قد تكون الشهادة منتهية"
                        : "Secure connection failed — certificate issue";
                break;
            default:
                friendlyMessage = isArabic
                        ? "تعذر الاتصال بالخادم"
                        : "Could not connect to server";
        }

        tvStatusSubtitle.setText(friendlyMessage);
        tvStatusSubtitle.setTextColor(Color.parseColor("#EF6C00"));

        tvUsersValue.setText("—");
        tvDevicesValue.setText("—");
        tvTypeValue.setText("—");
        tvDaysValue.setText("—");
        tvValidUntil.setText("—");
        tvTechnicalDetails.setText("Error: " + code + "\n" + message);

        animateStatusCard();
    }

    // ═══════════════════════════════════════════════════════
    //  Loading state
    // ═══════════════════════════════════════════════════════
    private void showLoading() {
        progressLoading.setVisibility(View.VISIBLE);
        tvStatusIcon.setVisibility(View.GONE);
        tvStatusTitle.setText(isArabic ? "جارٍ التحقق..." : "Verifying...");
        tvStatusSubtitle.setText(isArabic ? "يتم الاتصال بالخادم" : "Connecting to server");
        btnRefresh.setEnabled(false);
    }

    private void hideLoading() {
        progressLoading.setVisibility(View.GONE);
        tvStatusIcon.setVisibility(View.VISIBLE);
        btnRefresh.setEnabled(true);
    }

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════
    private String formatLicenseType(MiraBlobStorage.BlobData data) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(data.blobJson);
            String type = obj.optString("license_type", "custom");
            switch (type.toLowerCase()) {
                case "trial":        return isArabic ? "تجريبي"  : "Trial";
                case "starter":      return isArabic ? "أساسي"   : "Starter";
                case "professional": return isArabic ? "احترافي" : "Professional";
                case "enterprise":   return isArabic ? "مؤسسي"   : "Enterprise";
                case "founder":      return isArabic ? "مؤسس"    : "Founder";
                default:             return isArabic ? "مخصص"    : "Custom";
            }
        } catch (Exception e) {
            return "—";
        }
    }

    private int calculateDaysLeft(String validUntil) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date expiry = sdf.parse(validUntil);
            if (expiry == null) return 0;
            long diff = expiry.getTime() - System.currentTimeMillis();
            return (int) (diff / (24 * 60 * 60 * 1000));
        } catch (ParseException e) {
            return 0;
        }
    }

    private String formatDate(String date) {
        if (date == null || date.isEmpty()) return "—";
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat output = new SimpleDateFormat(
                    isArabic ? "dd MMMM yyyy" : "MMM dd, yyyy",
                    new Locale(isArabic ? "ar" : "en"));
            Date d = input.parse(date);
            return d != null ? output.format(d) : date;
        } catch (Exception e) {
            return date;
        }
    }

    private String buildTechnicalDetails(MiraBlobStorage.BlobData data) {
        return "License ID:  #" + data.licenseId + "\n"
                + "Blob Version: v" + data.version + "\n"
                + "Key ID:       " + data.keyId + "\n"
                + "Fingerprint:  " + truncate(data.fingerprintB64, 24) + "\n"
                + "Signature:    " + truncate(data.signatureB64, 24) + "\n"
                + "Stored At:    " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(data.storedAt));
    }

    private String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ═══════════════════════════════════════════════════════
    //  Animations
    // ═══════════════════════════════════════════════════════
    private void animateStatusCard() {
        statusCard.setAlpha(0f);
        statusCard.setScaleX(0.95f);
        statusCard.setScaleY(0.95f);

        statusCard.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateButton(View v) {
        ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.95f, 1f)
                .setDuration(150).start();
        ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.95f, 1f)
                .setDuration(150).start();
    }
}
