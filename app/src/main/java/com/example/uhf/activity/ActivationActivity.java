package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;
import com.example.uhf.license.LicenseManager;
import com.example.uhf.license.MiraBlobFetcher;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.regex.Pattern;

public class ActivationActivity extends AppCompatActivity {

    private static final String TAG = "ActivationActivity";
    private static final Pattern LICENSE_KEY_PATTERN =
            Pattern.compile("^MIRA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$");

    private static final String DEFAULT_SERVER_URL = "https://id.ibreg.org/wp-json";

    // ═══ Views ═══
    private TextInputLayout tilServerUrl, tilLicenseKey;
    private TextInputEditText etServerUrl, etLicenseKey;
    private MaterialButton btnActivate, btnRetry;
    private ProgressBar progressBar;
    private TextView tvError, tvStatus, tvActivationTitle, tvActivationSubtitle;

    private SessionManager sessionManager;
    private boolean isArabic = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // حماية الشاشة
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );

        setContentView(R.layout.activity_activation);

        sessionManager = new SessionManager(getApplicationContext());
        isArabic = "ar".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());

        // ═══ ربط Views ═══
        tilServerUrl       = findViewById(R.id.tilServerUrl);
        tilLicenseKey      = findViewById(R.id.tilLicenseKey);
        etServerUrl        = findViewById(R.id.etServerUrl);
        etLicenseKey       = findViewById(R.id.etLicenseKey);
        btnActivate        = findViewById(R.id.btnActivate);
        btnRetry           = findViewById(R.id.btnRetry);
        progressBar        = findViewById(R.id.progressBar);
        tvError            = findViewById(R.id.tvError);
        tvStatus           = findViewById(R.id.tvStatus);
        tvActivationTitle  = findViewById(R.id.tvActivationTitle);
        tvActivationSubtitle = findViewById(R.id.tvActivationSubtitle);

        // ═══ تحميل server_url المحفوظ ═══
        String savedUrl = sessionManager.getServerUrl();
        if (savedUrl == null || savedUrl.isEmpty()) {
            savedUrl = DEFAULT_SERVER_URL;
            sessionManager.setServerUrl(savedUrl);
        }
        etServerUrl.setText(savedUrl);
        // ⚡ placeholder للشفافية
if (etServerUrl != null) {
    etServerUrl.setHint(isArabic 
            ? "سيُكتشف تلقائياً من الترخيص"
            : "Auto-detected from license");
}

        // إذا لم يوجد server_url محفوظ، أظهر الحقل
        // ⚡ دائماً مُخفي — سيُكتشف تلقائياً من المركز
if (tilServerUrl != null) {
    tilServerUrl.setVisibility(View.GONE);
}

        // ═══ الأحداث ═══
        btnActivate.setOnClickListener(v -> attemptActivation());
        btnRetry.setOnClickListener(v -> attemptActivation());

        etLicenseKey.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String input = s.toString().toUpperCase().trim();
                // تنسيق تلقائي: أضف شرطات
                if (!input.startsWith("MIRA-") && input.length() >= 4) {
                    // لا نفرض التنسيق، نتحقق فقط
                }
                tilLicenseKey.setError(null);
                hideError();
                updateButtonState();
            }
        });

        updateButtonState();
    }

    private void attemptActivation() {
    String licenseKey = getText(etLicenseKey).toUpperCase();

    // ═══════════════════════════════════════════════════════════════
    //  ⚡ Validation — الترخيص فقط مطلوب
    //  (server_url يُكتشف تلقائياً من المركز)
    // ═══════════════════════════════════════════════════════════════
    if (licenseKey.isEmpty()) {
        if (tilLicenseKey != null) {
            tilLicenseKey.setError(
                    isArabic ? "أدخل رقم الترخيص" : "Enter license key");
        }
        return;
    }

    if (!LICENSE_KEY_PATTERN.matcher(licenseKey).matches()) {
        if (tilLicenseKey != null) {
            tilLicenseKey.setError(
                    isArabic ? "الصيغة: MIRA-XXXX-XXXX-XXXX" : "Format: MIRA-XXXX-XXXX-XXXX");
        }
        return;
    }

    showLoading(true);
    hideError();

    if (tvStatus != null) {
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(isArabic 
                ? "جارٍ التحقق من الترخيص..." 
                : "Verifying license...");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ⚡ الخطوة 1: Lookup server_url من المركز (في الخلفية)
    // ═══════════════════════════════════════════════════════════════
    new Thread(() -> {
        String centralUrl = getCentralUrl();
        Log.d(TAG, "🔍 Lookup: central=" + centralUrl + ", key=" + licenseKey);

        MiraBlobFetcher.LookupResult lookup = MiraBlobFetcher.lookupServerUrl(
                getApplicationContext(),
                centralUrl,
                licenseKey
        );

        runOnUiThread(() -> handleLookupResult(lookup, licenseKey));

    }, "Activation-Lookup").start();
}

/**
 * ⚡ معالجة نتيجة الـ Lookup (على main thread)
 */
private void handleLookupResult(MiraBlobFetcher.LookupResult lookup, String licenseKey) {
    // ═══ فشل الـ Lookup ═══
    if (!lookup.success) {
        showLoading(false);

        String err = lookup.error != null
                ? lookup.error
                : (isArabic ? "فشل التحقق من الترخيص" : "License lookup failed");

        showError(err);
        Log.e(TAG, "❌ Lookup failed: " + err);
        return;
    }

    // ═══ الخادم غير جاهز ═══
    if (!lookup.serverReady) {
        showLoading(false);

        String msg = isArabic
                ? "خادم المنشأة غير جاهز للتفعيل.\n\n"
                  + "قد يكون:"
                  + "\n• تحت الصيانة"
                  + "\n• لم يُثبَّت بعد"
                  + "\n\nتواصل مع ميرا تكنولوجي."
                : "Customer server not ready.\n\n"
                  + "It might be:"
                  + "\n• Under maintenance"
                  + "\n• Not installed yet"
                  + "\n\nContact MIRA Technology.";

        showError(msg);
        Log.e(TAG, "❌ Server not ready: " + lookup.serverUrl);
        return;
    }

    // ═══ نجاح الـ Lookup ═══
    Log.d(TAG, "✅ Lookup OK: server=" + lookup.serverUrl
            + ", customer=" + lookup.customerName);

    // احفظ server_url تلقائياً
    sessionManager.setServerUrl(lookup.serverUrl);

    // اعرض للعميل (شفافية — يرى أين يتصل)
    if (etServerUrl != null) {
        etServerUrl.setText(lookup.serverUrl);
        etServerUrl.setEnabled(false);
    }

    // ═══ عرض معلومات العميل ═══
    if (tvStatus != null) {
        String customerInfo = lookup.customerName != null 
                && !lookup.customerName.isEmpty()
                ? lookup.customerName
                : (isArabic ? "منشأتك" : "Your Facility");
        tvStatus.setText(isArabic
                ? "تم التعرف على: " + customerInfo + "\nجارٍ التفعيل..."
                : "Identified: " + customerInfo + "\nActivating...");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ⚡ الخطوة 2: Activation على الخادم المكتشف
    // ═══════════════════════════════════════════════════════════════
    LicenseManager.activate(
            getApplicationContext(),
            lookup.serverUrl,
            licenseKey,
            new LicenseManager.Callback() {
                @Override
                public void onSuccess(JSONObject response) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        Log.d(TAG, "✅ Activation successful on " + lookup.serverUrl);
                        showSuccessAndGoToLogin(response);
                    });
                }

                @Override
                public void onError(String code, String message, int httpCode) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        showError(message);
                        Log.e(TAG, "❌ Activation failed: " + code + " | " + message);

                        // في حالة الفشل، أعطِ فرصة للعميل لإعادة المحاولة
                        if (tilLicenseKey != null) {
                            tilLicenseKey.setError(isArabic 
                                    ? "فشل التفعيل — حاول مرة أخرى"
                                    : "Activation failed — try again");
                        }
                    });
                }
            }
    );
}

/**
 * ⚡ جلب Central URL — BuildConfig أو Fallback
 */
private String getCentralUrl() {
    // حاول BuildConfig أولاً (إن وُجد)
    try {
        java.lang.reflect.Field f = com.example.uhf.BuildConfig.class
                .getField("MIRA_LICENSE_BASE_URL");
        String v = (String) f.get(null);
        if (v != null && !v.isEmpty()) {
            return v;
        }
    } catch (Exception ignored) {
        // BuildConfig لا يحتوي عليه — استخدم الافتراضي
    }
    return DEFAULT_SERVER_URL;
}
    private void showSuccessAndGoToLogin(JSONObject response) {
        String facilityName = "";
        try {
            JSONObject facility = response.optJSONObject("facility");
            if (facility != null) {
                facilityName = facility.optString("name_ar",
                        facility.optString("name", ""));
            }
        } catch (Exception ignored) {}

        String validUntil = "";
        try {
            JSONObject license = response.optJSONObject("license");
            if (license != null) validUntil = license.optString("valid_until", "");
        } catch (Exception ignored) {}

        String msg = (isArabic ? "تم تفعيل النظام بنجاح" : "System activated successfully");
        if (!facilityName.isEmpty()) {
            msg += "\n\n" + (isArabic ? "المنشأة: " : "Facility: ") + facilityName;
        }
        if (!validUntil.isEmpty()) {
            msg += "\n" + (isArabic ? "صالح حتى: " : "Valid until: ") + validUntil;
        }

        new AlertDialog.Builder(this)
                .setTitle("✅ " + (isArabic ? "تم التفعيل" : "Activated"))
                .setMessage(msg)
                .setPositiveButton(isArabic ? "متابعة للدخول" : "Continue to Login",
                        (d, w) -> goToLogin())
                .setCancelable(false)
                .show();
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ═══════════════════════════════════════════════════════
    //  UI Helpers
    // ═══════════════════════════════════════════════════════

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnActivate.setEnabled(!loading);
        btnRetry.setVisibility(View.GONE);
        btnActivate.setVisibility(loading ? View.GONE : View.VISIBLE);
        etLicenseKey.setEnabled(!loading);
        etServerUrl.setEnabled(!loading);
        tvStatus.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.VISIBLE);
        btnActivate.setVisibility(View.GONE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void updateButtonState() {
        String key = getText(etLicenseKey);
        btnActivate.setEnabled(!key.isEmpty());
    }

    private String getText(TextInputEditText et) {
        return (et != null && et.getText() != null) ? et.getText().toString().trim() : "";
    }

    // ═══ TextWatcher مساعد ═══
    private static abstract class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
