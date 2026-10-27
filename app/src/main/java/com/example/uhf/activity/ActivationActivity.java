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

        // إذا لم يوجد server_url محفوظ، أظهر الحقل
        boolean hasServerUrl = sessionManager.hasServerUrl();
        tilServerUrl.setVisibility(hasServerUrl ? View.GONE : View.VISIBLE);

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
        String serverUrl = getText(etServerUrl);
        String licenseKey = getText(etLicenseKey).toUpperCase();

        // ═══ Validation ═══
        if (serverUrl.isEmpty()) {
            tilServerUrl.setError(isArabic ? "أدخل عنوان الخادم" : "Enter server URL");
            return;
        }
        if (!serverUrl.startsWith("https://")) {
            tilServerUrl.setError(isArabic ? "يجب أن يبدأ بـ https://" : "Must start with https://");
            return;
        }
        if (licenseKey.isEmpty()) {
            tilLicenseKey.setError(isArabic ? "أدخل رقم الترخيص" : "Enter license key");
            return;
        }
        if (!LICENSE_KEY_PATTERN.matcher(licenseKey).matches()) {
            tilLicenseKey.setError(
                    isArabic ? "الصيغة: MIRA-XXXX-XXXX-XXXX" : "Format: MIRA-XXXX-XXXX-XXXX");
            return;
        }

        // ═══ حفظ server_url قبل التفعيل ═══
        sessionManager.setServerUrl(serverUrl);

        showLoading(true);
        hideError();

        // ═══ استدعاء API ═══
        LicenseManager.activate(
                getApplicationContext(),
                serverUrl,
                licenseKey,
                new LicenseManager.Callback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            showLoading(false);
                            showSuccessAndGoToLogin(response);
                        });
                    }

                    @Override
                    public void onError(String code, String message, int httpCode) {
                        runOnUiThread(() -> {
                            showLoading(false);
                            showError(message);
                            Log.e(TAG, "Activation failed: " + code + " | " + message);
                        });
                    }
                }
        );
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
