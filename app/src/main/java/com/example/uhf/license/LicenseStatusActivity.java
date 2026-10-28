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
 * LicenseStatusActivity — v3.0 (نهائي)
 *
 * يشمل:
 *   - Auto-refresh كل 30 ثانية (عند فتح الشاشة)
 *   - زر الرجوع (Header) + زر الإغلاق (أسفل) + Back الفيزيائي
 *   - عرض عدد المستخدمين والأجهزة الفعلي (live)
 *   - تحديث لحظي لجميع الحقول (لأن الخادم يعيد توليد blob عند أي تغيير)
 *   - احترام الثيم (Light/Dark)
 */
public class LicenseStatusActivity extends AppCompatActivity {

    private static final String TAG = "LicenseStatus";

    // ═══ Auto-Refresh ═══
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L;
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
    private android.widget.Button btnRefresh, btnShowDetails, btnClose;
    private ImageButton btnRefreshHeader, btnBackHeader;

    // ═══ State ═══
    private boolean isArabic = true;
    private boolean detailsVisible = false;

    // ═══════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_license_status);

        isArabic = getResources().getConfiguration()
                .getLocales().get(0).getLanguage().equals("ar");

        initViews();
        attachListeners();
        // loadLicenseStatus() ← في onResume
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "▶️ onResume — starting auto-refresh");
        loadLicenseStatus();
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

    @Override
    public void onBackPressed() {
        // إذا كانت التفاصيل مفتوحة → أغلقها أولاً
        if (detailsVisible) {
            detailsVisible = false;
            if (layoutTechnicalDetails != null) {
                layoutTechnicalDetails.setVisibility(View.GONE);
            }
            if (btnShowDetails != null) {
                btnShowDetails.setText("🔍  تفاصيل");
            }
            Log.d(TAG, "Back: closed technical details");
        } else {
            Log.d(TAG, "Back: closing activity");
            super.onBackPressed();
        }
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
        btnClose                = findViewById(R.id.btnClose);
        btnRefreshHeader        = findViewById(R.id.btnRefreshHeader);
        btnBackHeader           = findViewById(R.id.btnBackHeader);
    }

    private void attachListeners() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                animateButton(v);
                loadLicenseStatus();
            });
        }

        if (btnRefreshHeader != null) {
            btnRefreshHeader.setOnClickListener(v -> loadLicenseStatus());
        }

        if (btnBackHeader != null) {
            btnBackHeader.setOnClickListener(v -> finish());
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        if (btnShowDetails != null) {
            btnShowDetails.setOnClickListener(v -> {
                detailsVisible = !detailsVisible;
                if (layoutTechnicalDetails != null) {
                    layoutTechnicalDetails.setVisibility(detailsVisible ? View.VISIBLE : View.GONE);
                    if (detailsVisible) {
                        layoutTechnicalDetails.setAlpha(0f);
                        layoutTechnicalDetails.animate().alpha(1f).setDuration(200).start();
                    }
                }
                btnShowDetails.setText(detailsVisible ? "🔍  إخفاء" : "🔍  تفاصيل");
            });
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Load License Status
    // ═══════════════════════════════════════════════════════

    private void loadLicenseStatus() {
        showLoading();

        new Thread(() -> {
            try {
                MiraBlobFetcher.FetchResult fetch = MiraBlobFetcher.fetch(this);

                if (!fetch.success) {
                    runOnUiThread(() -> showErrorState(fetch.code, fetch.message));
                    return;
                }

                MiraBlobStorage.BlobData data = fetch.blobData;
                MiraBlobStorage.BlobData current = MiraBlobStorage.load(this);

                MiraBlobVerifier.VerifyResult verify =
                        MiraBlobVerifier.verifyAndValidate(data, current);

                if (verify.valid) {
                    MiraBlobStorage.save(this, data);
                }

                runOnUiThread(() -> {
                    if (verify.valid) {
                        showValidState(data);
                    } else {
                        showInvalidState(verify.code, verify.message, data);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "loadLicenseStatus error", e);
                runOnUiThread(() -> showErrorState("exception",
                        e.getMessage() != null ? e.getMessage() : "خطأ غير متوقع"));
            }
        }, "LicenseStatusLoader").start();
    }

    // ═══════════════════════════════════════════════════════
    //  Show States
    // ═══════════════════════════════════════════════════════

    private void showValidState(MiraBlobStorage.BlobData data) {
        hideLoading();

        if (statusCard != null) {
            statusCard.setBackgroundResource(R.drawable.bg_status_valid);
        }
        tvStatusIcon.setText("✅");
        tvStatusTitle.setText(isArabic ? "الترخيص سليم" : "License Valid");
        tvStatusTitle.setTextColor(Color.parseColor("#1B5E20"));
        tvStatusSubtitle.setText(isArabic ? "جميع الفحوصات ناجحة" : "All checks passed");
        tvStatusSubtitle.setTextColor(Color.parseColor("#2E7D32"));

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

        int daysLeft = calculateDaysLeft(data.validUntil);
        tvDaysValue.setText(String.valueOf(Math.max(0, daysLeft)));
        tvDaysValue.setTextColor(daysLeft > 30
                ? Color.parseColor("#1A237E")
                : Color.parseColor("#E65100"));

        tvValidUntil.setText(formatDate(data.validUntil));
        tvTechnicalDetails.setText(buildTechnicalDetails(data));

        animateStatusCard();
    }

    private void showInvalidState(String code, String message, MiraBlobStorage.BlobData data) {
        hideLoading();

        boolean isExpired = "expired".equals(code);
        boolean isTampered = "invalid_signature".equals(code)
                || "fingerprint_mismatch".equals(code)
                || "version_rollback".equals(code);

        if (isExpired) {
            if (statusCard != null) statusCard.setBackgroundResource(R.drawable.bg_status_warning);
            tvStatusIcon.setText("⏰");
            tvStatusTitle.setText(isArabic ? "انتهى الترخيص" : "License Expired");
            tvStatusTitle.setTextColor(Color.parseColor("#E65100"));
            tvStatusSubtitle.setText(isArabic ? "تواصل مع ميرا للتجديد" : "Contact MIRA to renew");
            tvStatusSubtitle.setTextColor(Color.parseColor("#EF6C00"));
        } else if (isTampered) {
            if (statusCard != null) statusCard.setBackgroundResource(R.drawable.bg_status_invalid);
            tvStatusIcon.setText("⚠️");
            tvStatusTitle.setText(isArabic ? "ترخيص مُعدَّل" : "License Tampered");
            tvStatusTitle.setTextColor(Color.parseColor("#B71C1C"));
            tvStatusSubtitle.setText(isArabic
                    ? "التوقيع الرقمي غير صحيح — قد يكون الملف مُعدَّلاً"
                    : "Invalid signature — file may be tampered");
            tvStatusSubtitle.setTextColor(Color.parseColor("#C62828"));
        } else {
            if (statusCard != null) statusCard.setBackgroundResource(R.drawable.bg_status_invalid);
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

    private void showErrorState(String code, String message) {
        hideLoading();

        if (statusCard != null) statusCard.setBackgroundResource(R.drawable.bg_status_warning);
        tvStatusIcon.setText("📡");
        tvStatusTitle.setText(isArabic ? "فشل الاتصال" : "Connection Failed");
        tvStatusTitle.setTextColor(Color.parseColor("#E65100"));

        String friendlyMessage;
        switch (code) {
            case "dns":
            case "network":
                friendlyMessage = isArabic ? "تحقق من اتصالك بالإنترنت" : "Check your internet connection";
                break;
            case "timeout":
                friendlyMessage = isArabic ? "انتهت مهلة الاتصال — حاول مرة أخرى" : "Connection timed out — try again";
                break;
            case "ssl":
                friendlyMessage = isArabic ? "فشل الاتصال الآمن" : "Secure connection failed";
                break;
            default:
                friendlyMessage = isArabic ? "تعذر الاتصال بالخادم" : "Could not connect to server";
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
    //  Loading
    // ═══════════════════════════════════════════════════════

    private void showLoading() {
        if (progressLoading != null) progressLoading.setVisibility(View.VISIBLE);
        if (tvStatusIcon != null) tvStatusIcon.setVisibility(View.GONE);
        tvStatusTitle.setText(isArabic ? "جارٍ التحقق..." : "Verifying...");
        tvStatusSubtitle.setText(isArabic ? "يتم الاتصال بالخادم" : "Connecting to server");
        if (btnRefresh != null) btnRefresh.setEnabled(false);
    }

    private void hideLoading() {
        if (progressLoading != null) progressLoading.setVisibility(View.GONE);
        if (tvStatusIcon != null) tvStatusIcon.setVisibility(View.VISIBLE);
        if (btnRefresh != null) btnRefresh.setEnabled(true);
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
        if (statusCard == null) return;
        statusCard.setAlpha(0f);
        statusCard.setScaleX(0.95f);
        statusCard.setScaleY(0.95f);
        statusCard.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateButton(View v) {
        if (v == null) return;
        ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.95f, 1f).setDuration(150).start();
        ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.95f, 1f).setDuration(150).start();
    }
}
