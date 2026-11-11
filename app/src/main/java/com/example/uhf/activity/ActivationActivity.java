package com.example.uhf.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.license.LicenseManager;
import com.example.uhf.license.MiraBlobFetcher;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 *  ActivationActivity — شاشة تفعيل الترخيص
 * ═══════════════════════════════════════════════════════════════════════════════
 *  التدفق:
 *    1. المستخدم يدخل رقم الترخيص (MIRA-XXXX-XXXX-XXXX)
 *    2. lookupServerUrl() على مركز Laravel: POST {central}/license/lookup
 *    3. إذا نجح، LicenseManager.activate() على {server}/api/v1/license/activate
 *    4. حفظ server_url + token، ثم الانتقال إلى LoginActivity
 *
 *  التغييرات في هذه النسخة:
 *    ✅ DEFAULT_SERVER_URL = BuildConfig.MIRA_LICENSE_BASE_URL (لا hardcoded)
 *    ✅ showLoading() يتحكم في loadingContainer (كان مخفياً دائماً)
 *    ✅ null-safety في كل مكان (لا NPE إذا حُذف أي view من XML)
 *    ✅ تحقق مبكر من وجود الحقول الحرجة
 * ═══════════════════════════════════════════════════════════════════════════════
 */
public class ActivationActivity extends AppCompatActivity {

    private static final String TAG = "ActivationActivity";

    private static final Pattern LICENSE_KEY_PATTERN =
            Pattern.compile("^MIRA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$");

    /** ⚡ مركز الترخيص — الآن على Laravel (BuildConfig) */
    private static final String DEFAULT_SERVER_URL = BuildConfig.MIRA_LICENSE_BASE_URL;

    // ═══ Views (كلها قد تكون null — كل استخدام محمي) ═══
    private TextInputLayout    tilServerUrl, tilLicenseKey;
    private TextInputEditText  etServerUrl, etLicenseKey;
    private LinearLayout       loadingContainer;
    private MaterialButton     btnActivate, btnRetry;
    private ProgressBar        progressBar;
    private TextView           tvError, tvStatus, tvActivationTitle, tvActivationSubtitle;

    private SessionManager sessionManager;
    private boolean        isArabic = true;

    // ═══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // حماية الشاشة (screenshot protection)
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );

        setContentView(R.layout.activity_activation);

        sessionManager = new SessionManager(getApplicationContext());
        isArabic = "ar".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());

        bindViews();
        loadSavedServerUrl();
        setupListeners();
        updateButtonState();
        verifyCriticalViews();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Binding
    // ═══════════════════════════════════════════════════════════════════════════

    private void bindViews() {
        tilServerUrl         = findViewById(R.id.tilServerUrl);
        tilLicenseKey        = findViewById(R.id.tilLicenseKey);
        etServerUrl          = findViewById(R.id.etServerUrl);
        etLicenseKey         = findViewById(R.id.etLicenseKey);
        loadingContainer     = findViewById(R.id.loadingContainer);
        btnActivate          = findViewById(R.id.btnActivate);
        btnRetry             = findViewById(R.id.btnRetry);
        progressBar          = findViewById(R.id.progressBar);
        tvError              = findViewById(R.id.tvError);
        tvStatus             = findViewById(R.id.tvStatus);
        tvActivationTitle    = findViewById(R.id.tvActivationTitle);
        tvActivationSubtitle = findViewById(R.id.tvActivationSubtitle);
    }

    private void loadSavedServerUrl() {
        String savedUrl = sessionManager.getServerUrl();
        if (savedUrl == null || savedUrl.isEmpty()) {
            savedUrl = DEFAULT_SERVER_URL;
            sessionManager.setServerUrl(savedUrl);
        }

        if (etServerUrl != null) {
            etServerUrl.setText(savedUrl);
            etServerUrl.setHint(isArabic
                    ? "سيُكتشف تلقائياً من الترخيص"
                    : "Auto-detected from license");
        }
        // حقل السيرفر مخفي — يُكتشف تلقائياً من مركز الترخيص
        if (tilServerUrl != null) {
            tilServerUrl.setVisibility(View.GONE);
        }

        // ⚡ تأكيد ظهور حقل رقم الترخيص (حتى لو أُخفي سهواً في XML)
        if (tilLicenseKey != null) {
            tilLicenseKey.setVisibility(View.VISIBLE);
        }
        if (etLicenseKey != null) {
            etLicenseKey.setVisibility(View.VISIBLE);
        }
    }

    private void setupListeners() {
        if (btnActivate != null) {
            btnActivate.setOnClickListener(v -> attemptActivation());
        }
        if (btnRetry != null) {
            btnRetry.setOnClickListener(v -> attemptActivation());
        }

        if (etLicenseKey != null) {
            etLicenseKey.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (tilLicenseKey != null) tilLicenseKey.setError(null);
                    hideError();
                    updateButtonState();
                }
            });
        }
    }

    private void verifyCriticalViews() {
        if (etLicenseKey == null) {
            Log.e(TAG, "❌ R.id.etLicenseKey missing from activity_activation.xml");
        }
        if (btnActivate == null) {
            Log.e(TAG, "❌ R.id.btnActivate missing from activity_activation.xml");
        }
        if (loadingContainer == null) {
            Log.w(TAG, "⚠️ R.id.loadingContainer missing — مؤشر التحميل لن يظهر");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Activation flow
    // ═══════════════════════════════════════════════════════════════════════════

    private void attemptActivation() {
        String licenseKey = getText(etLicenseKey).toUpperCase();

        // ─── Validation ───
        if (licenseKey.isEmpty()) {
            if (tilLicenseKey != null) {
                tilLicenseKey.setError(isArabic ? "أدخل رقم الترخيص" : "Enter license key");
            } else {
                showError(isArabic ? "أدخل رقم الترخيص" : "Enter license key");
            }
            return;
        }

        if (!LICENSE_KEY_PATTERN.matcher(licenseKey).matches()) {
            String msg = isArabic
                    ? "الصيغة: MIRA-XXXX-XXXX-XXXX"
                    : "Format: MIRA-XXXX-XXXX-XXXX";
            if (tilLicenseKey != null) {
                tilLicenseKey.setError(msg);
            } else {
                showError(msg);
            }
            return;
        }

        showLoading(true);
        hideError();

        if (tvStatus != null) {
            tvStatus.setText(isArabic ? "جارٍ التحقق من الترخيص..." : "Verifying license...");
        }

        // ─── الخطوة 1: Lookup على مركز Laravel ───
        final String key = licenseKey;
        new Thread(() -> {
            String centralUrl = getCentralUrl();
            Log.d(TAG, "🔍 Lookup: central=" + centralUrl + ", key=" + key);

            MiraBlobFetcher.LookupResult lookup = MiraBlobFetcher.lookupServerUrl(
                    getApplicationContext(),
                    centralUrl,
                    key
            );

            runOnUiThread(() -> handleLookupResult(lookup, key));
        }, "Activation-Lookup").start();
    }

    private void handleLookupResult(MiraBlobFetcher.LookupResult lookup, String licenseKey) {
        // ═══ فشل الـ lookup ═══
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

        // ═══ نجاح الـ lookup ═══
        Log.d(TAG, "✅ Lookup OK: server=" + lookup.serverUrl
                + ", customer=" + lookup.customerName);

        sessionManager.setServerUrl(lookup.serverUrl);

        if (etServerUrl != null) {
            etServerUrl.setText(lookup.serverUrl);
            etServerUrl.setEnabled(false);
        }

        if (tvStatus != null) {
            String customerInfo = lookup.customerName != null && !lookup.customerName.isEmpty()
                    ? lookup.customerName
                    : (isArabic ? "منشأتك" : "Your Facility");
            tvStatus.setText(isArabic
                    ? "تم التعرف على: " + customerInfo + "\nجارٍ التفعيل..."
                    : "Identified: " + customerInfo + "\nActivating...");
        }

        // ─── الخطوة 2: Activate على السيرفر المكتشف ───
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helpers — Center URL & Success
    // ═══════════════════════════════════════════════════════════════════════════

    private String getCentralUrl() {
        // BuildConfig هو المصدر الوحيد — لا fallback لـ WP
        String v = BuildConfig.MIRA_LICENSE_BASE_URL;
        if (v != null && !v.isEmpty()) return v;
        return DEFAULT_SERVER_URL;
    }

    private void showSuccessAndGoToLogin(JSONObject response) {
        String facilityName = "";
        String validUntil   = "";
        try {
            JSONObject facility = response.optJSONObject("facility");
            if (facility != null) {
                facilityName = facility.optString("name_ar",
                        facility.optString("name", ""));
            }
            JSONObject license = response.optJSONObject("license");
            if (license != null) validUntil = license.optString("valid_until", "");
        } catch (Exception ignored) {}

        String msg = isArabic ? "تم تفعيل النظام بنجاح" : "System activated successfully";
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  UI helpers (كلها null-safe)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ⚡ تحكم كامل في حالة التحميل — بما فيها loadingContainer
     *    (كانت المشكلة: الحاوية GONE دائماً، فيظهر الزر مخفياً بدون مؤشر)
     */
    private void showLoading(boolean loading) {
        // الحاوية الرئيسية
        if (loadingContainer != null) {
            loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        }

        // زر التفعيل
        if (btnActivate != null) {
            btnActivate.setEnabled(!loading);
            btnActivate.setVisibility(loading ? View.GONE : View.VISIBLE);
        }

        // زر الإعادة — يبقى مخفياً أثناء التحميل
        if (btnRetry != null) {
            btnRetry.setVisibility(View.GONE);
        }

        // حقل الترخيص
        if (etLicenseKey != null) {
            etLicenseKey.setEnabled(!loading);
        }

        // حقل السيرفر (المخفي عادةً)
        if (etServerUrl != null) {
            etServerUrl.setEnabled(!loading);
        }

        // progressBar و tvStatus أبناء الحاوية — لا حاجة للتحكم المنفصل
        // (نتركهما كما في XML، فهما يظهران/يختفيان مع الحاوية)
    }

    private void showError(String message) {
        if (tvError != null) {
            tvError.setText(message);
            tvError.setVisibility(View.VISIBLE);
        }
        if (btnRetry != null)    btnRetry.setVisibility(View.VISIBLE);
        if (btnActivate != null) btnActivate.setVisibility(View.GONE);
    }

    private void hideError() {
        if (tvError != null) tvError.setVisibility(View.GONE);
    }

    private void updateButtonState() {
        if (btnActivate == null) return;
        String key = getText(etLicenseKey);
        btnActivate.setEnabled(!key.isEmpty());
    }

    private String getText(TextInputEditText et) {
        return (et != null && et.getText() != null) ? et.getText().toString().trim() : "";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TextWatcher مساعد
    // ═══════════════════════════════════════════════════════════════════════════

    private static abstract class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
