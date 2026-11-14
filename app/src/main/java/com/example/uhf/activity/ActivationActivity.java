package com.example.uhf.activity

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.uhf.BuildConfig
import com.example.uhf.R
import com.example.uhf.license.LicenseManager
import com.example.uhf.license.MiraBlobFetcher
import com.example.uhf.tools.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 *  ActivationActivity — شاشة تفعيل الترخيص (إصدار المجوهرات الإبداعي)
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class ActivationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ActivationActivity"
        private val LICENSE_KEY_PATTERN =
            Pattern.compile("^MIRA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")
    }

    /** ⚡ مركز الترخيص — Laravel (BuildConfig) */
    private val defaultServerUrl: String = BuildConfig.MIRA_LICENSE_BASE_URL

    // ═══ Views (Nullable لضمان أمان كامل من الـ NPE) ═══
    private var tilServerUrl: TextInputLayout? = null
    private var tilLicenseKey: TextInputLayout? = null
    private var etServerUrl: TextInputEditText? = null
    private var etLicenseKey: TextInputEditText? = null
    private var loadingContainer: LinearLayout? = null
    private var btnActivate: MaterialButton? = null
    private var btnRetry: MaterialButton? = null
    private var progressBar: ProgressBar? = null
    private var tvError: TextView? = null
    private var tvStatus: TextView? = null
    private var tvActivationTitle: TextView? = null
    private var tvActivationSubtitle: TextView? = null

    private lateinit var sessionManager: SessionManager
    private var isArabic: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // حماية الشاشة والأمان ضد التقاط الصور (Screenshot Protection)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_activation)

        sessionManager = SessionManager(applicationContext)
        isArabic = resources.configuration.locales[0].language == "ar"

        bindViews()
        loadSavedServerUrl()
        setupListeners()
        updateButtonState()
        verifyCriticalViews()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Binding
    // ═══════════════════════════════════════════════════════════════════════════

    private fun bindViews() {
        tilServerUrl = findViewById(R.id.tilServerUrl)
        tilLicenseKey = findViewById(R.id.tilLicenseKey)
        etServerUrl = findViewById(R.id.etServerUrl)
        etLicenseKey = findViewById(R.id.etLicenseKey)
        loadingContainer = findViewById(R.id.loadingContainer)
        btnActivate = findViewById(R.id.btnActivate)
        btnRetry = findViewById(R.id.btnRetry)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        tvStatus = findViewById(R.id.tvStatus)
        tvActivationTitle = findViewById(R.id.tvActivationTitle)
        tvActivationSubtitle = findViewById(R.id.tvActivationSubtitle)
    }

    private fun loadSavedServerUrl() {
        val savedUrl = sessionManager.serverUrl

        // ⚡ لا تحفظ شيئاً حتى يُفعَّل الترخيص فعلياً.
        val hasValidSavedUrl = !savedUrl.isNullOrEmpty()
                && !savedUrl.contains("id.ibreg.org")
                && !savedUrl.contains("wp-json")
                && !savedUrl.endsWith("/api/v1")
                && !savedUrl.endsWith("/api/v1/")

        etServerUrl?.let {
            if (hasValidSavedUrl) {
                it.setText(savedUrl)
            } else {
                it.setText("")
                it.hint = if (isArabic) "سيُكتشف تلقائياً من الترخيص" else "Auto-detected from license"
            }
        }
        
        tilServerUrl?.visibility = View.GONE
        tilLicenseKey?.visibility = View.VISIBLE
        etLicenseKey?.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        btnActivate?.setOnClickListener { attemptActivation() }
        btnRetry?.setOnClickListener { attemptActivation() }

        etLicenseKey?.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                tilLicenseKey?.error = null
                hideError()
                updateButtonState()
            }
        })
    }

    private fun verifyCriticalViews() {
        if (etLicenseKey == null) {
            Log.e(TAG, "❌ R.id.etLicenseKey missing from activity_activation.xml")
        }
        if (btnActivate == null) {
            Log.e(TAG, "❌ R.id.btnActivate missing from activity_activation.xml")
        }
        if (loadingContainer == null) {
            Log.w(TAG, "⚠️ R.id.loadingContainer missing — مؤشر التحميل لن يظهر")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Activation Flow
    // ═══════════════════════════════════════════════════════════════════════════

    private fun attemptActivation() {
        val licenseKey = getFieldText(etLicenseKey).uppercase(Locale.ROOT)

        // ─── Validation ───
        if (licenseKey.isEmpty()) {
            val errMsg = if (isArabic) "أدخل رقم الترخيص" else "Enter license key"
            tilLicenseKey?.error = errMsg ?: run {
                showError(errMsg)
                return
            }
            return
        }

        if (!LICENSE_KEY_PATTERN.matcher(licenseKey).matches()) {
            val msg = if (isArabic) "الصيغة: MIRA-XXXX-XXXX-XXXX" else "Format: MIRA-XXXX-XXXX-XXXX"
            tilLicenseKey?.error = msg ?: run {
                showError(msg)
                return
            }
            return
        }

        showLoading(true)
        hideError()

        tvStatus?.text = if (isArabic) "جارٍ التحقق من الترخيص..." else "Verifying license..."

        // ─── الخطوة 1: Lookup على مركز Laravel ───
        Thread({
            val centralUrl = getCentralUrl()
            Log.d(TAG, "🔍 Lookup: central=$centralUrl, key=$licenseKey")

            val lookup = MiraBlobFetcher.lookupServerUrl(
                applicationContext,
                centralUrl,
                licenseKey
            )

            runOnUiThread { handleLookupResult(lookup, licenseKey) }
        }, "Activation-Lookup").start()
    }

    private fun handleLookupResult(lookup: MiraBlobFetcher.LookupResult, licenseKey: String) {
        // ═══ فشل الـ lookup ═══
        if (!lookup.success) {
            showLoading(false)
            val err = lookup.error
                ?: if (isArabic) "فشل التحقق من الترخيص" else "License lookup failed"
            showError(err)
            Log.e(TAG, "❌ Lookup failed: $err")
            return
        }

        // ═══ الخادم غير جاهز ═══
        if (!lookup.serverReady) {
            showLoading(false)
            val msg = if (isArabic) {
                "خادم المنشأة غير جاهز للتفعيل.\n\nقد يكون:\n• تحت الصيانة\n• لم يُثبَّت بعد\n\nتواصل مع ميرا تكنولوجي."
            } else {
                "Customer server not ready.\n\nIt might be:\n• Under maintenance\n• Not installed yet\n\nContact MIRA Technology."
            }
            showError(msg)
            Log.e(TAG, "❌ Server not ready: ${lookup.serverUrl}")
            return
        }

        // ═══ نجاح الـ lookup ═══
        Log.d(TAG, "✅ Lookup OK: server=${lookup.serverUrl}, customer=${lookup.customerName}")

        sessionManager.serverUrl = lookup.serverUrl

        etServerUrl?.apply {
            setText(lookup.serverUrl)
            isEnabled = false
        }

        val customerInfo = if (!lookup.customerName.isNullOrEmpty()) {
            lookup.customerName
        } else {
            if (isArabic) "منشأتك" else "Your Facility"
        }

        tvStatus?.text = if (isArabic) {
            "تم التعرف على: $customerInfo\nجارٍ التفعيل..."
        } else {
            "Identified: $customerInfo\nActivating..."
        }

        // ─── الخطوة 2: Activate على السيرفر المكتشف ───
        LicenseManager.activate(
            applicationContext,
            lookup.serverUrl,
            licenseKey,
            object : LicenseManager.Callback {
                override fun onSuccess(response: JSONObject) {
                    runOnUiThread {
                        showLoading(false)
                        Log.d(TAG, "✅ Activation successful on ${lookup.serverUrl}")
                        showSuccessAndGoToLogin(response)
                    }
                }

                override fun onError(code: String, message: String, httpCode: Int) {
                    runOnUiThread {
                        showLoading(false)
                        showError(message)
                        Log.e(TAG, "❌ Activation failed: $code | $message")

                        tilLicenseKey?.error = if (isArabic) {
                            "فشل التفعيل — حاول مرة أخرى"
                        } else {
                            "Activation failed — try again"
                        }
                    }
                }
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helpers — Center URL & Success
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getCentralUrl(): String {
        val v = BuildConfig.MIRA_LICENSE_BASE_URL
        return if (!v.isNullOrEmpty()) v else defaultServerUrl
    }

    private fun showSuccessAndGoToLogin(response: JSONObject) {
        var facilityName = ""
        var validUntil = ""
        try {
            val facility = response.optJSONObject("facility")
            if (facility != null) {
                facilityName = facility.optString("name_ar", facility.optString("name", ""))
            }
            val license = response.optJSONObject("license")
            if (license != null) {
                validUntil = license.optString("valid_until", "")
            }
        } catch (ignored: Exception) {
        }

        var msg = if (isArabic) "تم تفعيل النظام بنجاح" else "System activated successfully"
        if (facilityName.isNotEmpty()) {
            msg += "\n\n" + (if (isArabic) "المنشأة: " else "Facility: ") + facilityName
        }
        if (validUntil.isNotEmpty()) {
            msg += "\n" + (if (isArabic) "صالح حتى: " else "Valid until: ") + validUntil
        }

        AlertDialog.Builder(this)
            .setTitle("✅ " + if (isArabic) "تم التفعيل" else "Activated")
            .setMessage(msg)
            .setPositiveButton(if (isArabic) "متابعة للدخول" else "Continue to Login") { _, _ ->
                goToLogin()
            }
            .setCancelable(false)
            .show()
    }

    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  UI Helpers (Null-Safe)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showLoading(loading: Boolean) {
        loadingContainer?.visibility = if (loading) View.VISIBLE else View.GONE

        btnActivate?.apply {
            isEnabled = !loading
            visibility = if (loading) View.GONE else View.VISIBLE
        }

        btnRetry?.visibility = View.GONE
        etLicenseKey?.isEnabled = !loading
        etServerUrl?.isEnabled = !loading
    }

    private fun showError(message: String) {
        tvError?.apply {
            text = message
            visibility = View.VISIBLE
        }
        btnRetry?.visibility = View.VISIBLE
        btnActivate?.visibility = View.GONE
    }

    private fun hideError() {
        tvError?.visibility = View.GONE
    }

    private fun updateButtonState() {
        val key = getFieldText(etLicenseKey)
        btnActivate?.isEnabled = key.isNotEmpty()
    }

    private fun getFieldText(et: TextInputEditText?): String {
        return et?.text?.toString()?.trim() ?: ""
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TextWatcher المساعد
    // ═══════════════════════════════════════════════════════════════════════════
    private abstract class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count) {}
    }
}
