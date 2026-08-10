package com.example.uhf.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class UHFSetFragment extends KeyDwonFragment implements View.OnClickListener {

    private static final String PREFS_NAME = "MIRA_SETTINGS";
    private static final String ORG_PREFS = "MIRA_ORG_PROFILE";

    private UHFMainActivity mContext;
    private SharedPreferences prefs;
    private SharedPreferences orgPrefs;

    // 1. Language & System Mode
    private RadioGroup rgLanguage;
    private RadioButton rbLangAr, rbLangEn;
    private Spinner spWorkingMode;

    // 2. Organization Profile
    private EditText etOrgNameAr, etOrgNameEn;
    private EditText etOrgSloganAr, etOrgSloganEn;
    private EditText etOrgPhone, etOrgEmail, etOrgAddress, etOrgWebsite;
    private ImageView ivOrgLogo;
    private TextView tvOrgStatus;
    private Button btnSaveOrgProfile, btnPickLogo;

    // 3. MIRA Bridge API
    private Spinner spMiraApiUrl;
    private EditText etMiraApiKey, etMiraGateId;
    private Button btnTestMiraConnection;
    private TextView tvMiraConnectionStatus;

    // 4. Scan & Identification Mode
    private RadioGroup rgScanMode;
    private RadioButton rbRfidMode, rbCameraMode, rbHybridMode, rbManualMode;
    private CheckBox cbParseGS1, cbVibrateOnScan, cbInventorySound;

    // 5. UHF Hardware & RF Parameters (المعادة بالكامل)
    private Spinner spFrequency, spPower;
    private Spinner spMemoryBank, spSession, spTarget, spLinkParams, spInventoryMode;
    private CheckBox cbFastId, cbTagFocus, cbDynamicPower;
    private Button btnSetFrequency, btnGetFrequency, btnSetPower, btnGetPower;
    private Button btnSetRfParams, btnGetRfParams;

    // Actions
    private Button btnSaveAllSettings, btnFactoryReset;

    private ActivityResultLauncher<Intent> logoPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        logoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri imageUri = result.getData().getData();
                        if (imageUri != null) {
                            processAndSaveLogo(imageUri);
                        }
                    }
                }
        );
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_uhf_set, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
        }
        if (mContext == null) return;

        prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        orgPrefs = mContext.getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE);

        initViews(view);
        initListeners();
        loadAllSettings();
    }

    private void initViews(View view) {
        // Language & System Mode
        rgLanguage = view.findViewById(R.id.rgLanguage);
        rbLangAr = view.findViewById(R.id.rbLangAr);
        rbLangEn = view.findViewById(R.id.rbLangEn);
        spWorkingMode = view.findViewById(R.id.spWorkingMode);

        // Organization
        etOrgNameAr = view.findViewById(R.id.etOrgNameAr);
        etOrgNameEn = view.findViewById(R.id.etOrgNameEn);
        etOrgSloganAr = view.findViewById(R.id.etOrgSloganAr);
        etOrgSloganEn = view.findViewById(R.id.etOrgSloganEn);
        etOrgPhone = view.findViewById(R.id.etOrgPhone);
        etOrgEmail = view.findViewById(R.id.etOrgEmail);
        etOrgAddress = view.findViewById(R.id.etOrgAddress);
        etOrgWebsite = view.findViewById(R.id.etOrgWebsite);
        ivOrgLogo = view.findViewById(R.id.ivOrgLogo);
        tvOrgStatus = view.findViewById(R.id.tvOrgStatus);
        btnSaveOrgProfile = view.findViewById(R.id.btnSaveOrgProfile);
        btnPickLogo = view.findViewById(R.id.btnPickLogo);

        // MIRA Bridge
        spMiraApiUrl = view.findViewById(R.id.spMiraApiUrl);
        etMiraApiKey = view.findViewById(R.id.etMiraApiKey);
        etMiraGateId = view.findViewById(R.id.etMiraGateId);
        btnTestMiraConnection = view.findViewById(R.id.btnTestMiraConnection);
        tvMiraConnectionStatus = view.findViewById(R.id.tvMiraConnectionStatus);

        // Scan Mode
        rgScanMode = view.findViewById(R.id.rgScanMode);
        rbRfidMode = view.findViewById(R.id.rbRfidMode);
        rbCameraMode = view.findViewById(R.id.rbCameraMode);
        rbHybridMode = view.findViewById(R.id.rbHybridMode);
        rbManualMode = view.findViewById(R.id.rbManualMode);
        cbParseGS1 = view.findViewById(R.id.cbParseGS1);
        cbVibrateOnScan = view.findViewById(R.id.cbVibrateOnScan);
        cbInventorySound = view.findViewById(R.id.cbInventorySound);

        // UHF Hardware Settings (إعادة تعيين كافة المعرفات)
        spFrequency = view.findViewById(R.id.spFrequency);
        spPower = view.findViewById(R.id.spPower);
        spMemoryBank = view.findViewById(R.id.spMemoryBank);
        spSession = view.findViewById(R.id.spSession);
        spTarget = view.findViewById(R.id.spTarget);
        spLinkParams = view.findViewById(R.id.spLinkParams);
        spInventoryMode = view.findViewById(R.id.spInventoryMode);

        cbFastId = view.findViewById(R.id.cbFastId);
        cbTagFocus = view.findViewById(R.id.cbTagFocus);
        cbDynamicPower = view.findViewById(R.id.cbDynamicPower);

        btnSetFrequency = view.findViewById(R.id.btnSetFrequency);
        btnGetFrequency = view.findViewById(R.id.btnGetFrequency);
        btnSetPower = view.findViewById(R.id.btnSetPower);
        btnGetPower = view.findViewById(R.id.btnGetPower);
        btnSetRfParams = view.findViewById(R.id.btnSetRfParams);
        btnGetRfParams = view.findViewById(R.id.btnGetRfParams);

        // Actions
        btnSaveAllSettings = view.findViewById(R.id.btnSaveAllSettings);
        btnFactoryReset = view.findViewById(R.id.btnFactoryReset);
    }

    private void initListeners() {
        if (btnSaveOrgProfile != null) btnSaveOrgProfile.setOnClickListener(this);
        if (btnPickLogo != null) btnPickLogo.setOnClickListener(v -> pickLogo());
        if (btnSaveAllSettings != null) btnSaveAllSettings.setOnClickListener(this);
        if (btnFactoryReset != null) btnFactoryReset.setOnClickListener(this);
        if (btnTestMiraConnection != null) btnTestMiraConnection.setOnClickListener(v -> testBridgeConnection());

        if (btnSetPower != null) btnSetPower.setOnClickListener(v -> setRfidPower());
        if (btnGetPower != null) btnGetPower.setOnClickListener(v -> getRfidPower());
        if (btnSetFrequency != null) btnSetFrequency.setOnClickListener(v -> setRfidFrequency());
        if (btnGetFrequency != null) btnGetFrequency.setOnClickListener(v -> getRfidFrequency());

        if (btnSetRfParams != null) btnSetRfParams.setOnClickListener(v -> setRfParameters());
        if (btnGetRfParams != null) btnGetRfParams.setOnClickListener(v -> getRfParameters());
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnSaveOrgProfile) saveOrgProfile();
        else if (id == R.id.btnSaveAllSettings) saveAllSettings();
        else if (id == R.id.btnFactoryReset) confirmFactoryReset();
    }

    private void saveOrgProfile() {
        if (orgPrefs == null) return;
        orgPrefs.edit()
                .putString("org_name_ar", getText(etOrgNameAr))
                .putString("org_name_en", getText(etOrgNameEn))
                .putString("org_slogan_ar", getText(etOrgSloganAr))
                .putString("org_slogan_en", getText(etOrgSloganEn))
                .putString("org_phone", getText(etOrgPhone))
                .putString("org_email", getText(etOrgEmail))
                .putString("org_address", getText(etOrgAddress))
                .putString("org_website", getText(etOrgWebsite))
                .apply();

        showStatus(tvOrgStatus, "✅ تم حفظ بيانات المنشأة بنجاح", 0xFF4CAF50);
    }

    private void pickLogo() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            logoPickerLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(mContext, "❌ لا يوجد تطبيق لمعالجة الصور", Toast.LENGTH_SHORT).show();
        }
    }

    private void processAndSaveLogo(Uri uri) {
        new Thread(() -> {
            try (InputStream is = mContext.getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                if (bitmap == null) return;

                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.PNG, 85, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                orgPrefs.edit().putString("org_logo", base64).apply();

                mContext.runOnUiThread(() -> {
                    if (ivOrgLogo != null) ivOrgLogo.setImageBitmap(scaled);
                    showStatus(tvOrgStatus, "✅ تم تحديث الشعار المؤسسي", 0xFF4CAF50);
                });
            } catch (Exception e) {
                mContext.runOnUiThread(() -> showStatus(tvOrgStatus, "❌ فشل معالجة الشعار", 0xFFEF4444));
            }
        }).start();
    }

    private void testBridgeConnection() {
        if (tvMiraConnectionStatus != null) {
            tvMiraConnectionStatus.setText("⌛ جاري الاختبار...");
            tvMiraConnectionStatus.setTextColor(0xFFFF9800);
        }

        new Thread(() -> {
            try {
                String selectedUrl = (spMiraApiUrl != null && spMiraApiUrl.getSelectedItem() != null) 
                        ? spMiraApiUrl.getSelectedItem().toString() : "";
                
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                boolean success = client.pingApi(selectedUrl, getText(etMiraApiKey));

                mContext.runOnUiThread(() -> {
                    if (tvMiraConnectionStatus != null) {
                        tvMiraConnectionStatus.setText(success ? "🟢 متصل وآمن" : "🔴 فشل الاتصال");
                        tvMiraConnectionStatus.setTextColor(success ? 0xFF4CAF50 : 0xFFEF4444);
                    }
                });
            } catch (Exception e) {
                mContext.runOnUiThread(() -> {
                    if (tvMiraConnectionStatus != null) {
                        tvMiraConnectionStatus.setText("🔴 خطأ في الاتصال");
                        tvMiraConnectionStatus.setTextColor(0xFFEF4444);
                    }
                });
            }
        }).start();
    }

    // --- UHF Hardware Operations ---

    private void setRfidPower() {
        if (mContext == null || mContext.mReader == null) {
            Toast.makeText(mContext, "⚠️ القارئ غير متصل", Toast.LENGTH_SHORT).show();
            return;
        }
        int power = (spPower != null) ? spPower.getSelectedItemPosition() + 5 : 5;
        if (mContext.mReader.setPower(power)) {
            Toast.makeText(mContext, "✅ تم ضبط الطاقة: " + power + " dBm", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(mContext, "❌ فشل ضبط الطاقة", Toast.LENGTH_SHORT).show();
        }
    }

    private void getRfidPower() {
        if (mContext == null || mContext.mReader == null) return;
        int power = mContext.mReader.getPower();
        if (power > 0 && spPower != null) {
            int index = Math.max(0, power - 5);
            if (index < spPower.getCount()) spPower.setSelection(index);
            Toast.makeText(mContext, "📡 طاقة القارئ: " + power + " dBm", Toast.LENGTH_SHORT).show();
        }
    }

    private void setRfidFrequency() {
        if (mContext == null || mContext.mReader == null) return;
        int mode = (spFrequency != null) ? spFrequency.getSelectedItemPosition() : 0;
        if (mContext.mReader.setFrequencyMode((byte) mode)) {
            Toast.makeText(mContext, "✅ تم ضبط التردد", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(mContext, "❌ فشل ضبط التردد", Toast.LENGTH_SHORT).show();
        }
    }

    private void getRfidFrequency() {
        if (mContext == null || mContext.mReader == null) return;
        int mode = mContext.mReader.getFrequencyMode();
        if (mode >= 0 && spFrequency != null && mode < spFrequency.getCount()) {
            spFrequency.setSelection(mode);
            Toast.makeText(mContext, "📡 التردد مضبوط", Toast.LENGTH_SHORT).show();
        }
    }

    private void setRfParameters() {
        if (mContext == null || mContext.mReader == null) return;
        
        try {
            int session = (spSession != null) ? spSession.getSelectedItemPosition() : 0;
            int target = (spTarget != null) ? spTarget.getSelectedItemPosition() : 0;
            
            // ضبط جلسة القراءة (Session / Target)
            mContext.mReader.setGen2(session, target, 0);
            Toast.makeText(mContext, "✅ تم تطبيق إعدادات RF البرتوكول", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(mContext, "❌ تعذر ضبط معلمات RF", Toast.LENGTH_SHORT).show();
        }
    }

    private void getRfParameters() {
        Toast.makeText(mContext, "📡 جلب المعلمات من قارئ UHF...", Toast.LENGTH_SHORT).show();
    }

    // --- Save & Load Configurations ---

    private void saveAllSettings() {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();

        String selectedLang = (rbLangAr != null && rbLangAr.isChecked()) ? "ar" : "en";
        String currentLang = prefs.getString("language", "ar");
        editor.putString("language", selectedLang);

        if (spMiraApiUrl != null) editor.putInt("mira_api_url_idx", spMiraApiUrl.getSelectedItemPosition());
        editor.putString("mira_api_key", getText(etMiraApiKey));
        editor.putString("mira_gate_id", getText(etMiraGateId));

        if (spWorkingMode != null) editor.putInt("working_mode", spWorkingMode.getSelectedItemPosition());

        editor.putString("scan_mode", getScanMode());
        editor.putBoolean("parse_gs1", isChecked(cbParseGS1));
        editor.putBoolean("vibrate_on_scan", isChecked(cbVibrateOnScan));
        editor.putBoolean("inventory_sound", isChecked(cbInventorySound));

        // حفظ خيارات UHF التفصيلية
        if (spMemoryBank != null) editor.putInt("memory_bank", spMemoryBank.getSelectedItemPosition());
        if (spSession != null) editor.putInt("session_idx", spSession.getSelectedItemPosition());
        if (spTarget != null) editor.putInt("target_idx", spTarget.getSelectedItemPosition());
        if (spLinkParams != null) editor.putInt("link_params_idx", spLinkParams.getSelectedItemPosition());
        if (spInventoryMode != null) editor.putInt("inventory_mode_idx", spInventoryMode.getSelectedItemPosition());

        editor.putBoolean("fast_id", isChecked(cbFastId));
        editor.putBoolean("tag_focus", isChecked(cbTagFocus));
        editor.putBoolean("dynamic_power", isChecked(cbDynamicPower));

        editor.apply();

        Toast.makeText(mContext, "✅ تم حفظ التكوين كاملاً بنجاح", Toast.LENGTH_SHORT).show();

        if (!selectedLang.equals(currentLang)) {
            applyLanguageChange(selectedLang);
        }
    }

    private void loadAllSettings() {
        if (prefs == null) return;
        
        String lang = prefs.getString("language", "ar");
        if (rbLangAr != null && rbLangEn != null) {
            if ("ar".equals(lang)) rbLangAr.setChecked(true);
            else rbLangEn.setChecked(true);
        }

        if (spMiraApiUrl != null) spMiraApiUrl.setSelection(prefs.getInt("mira_api_url_idx", 0));
        if (etMiraApiKey != null) etMiraApiKey.setText(prefs.getString("mira_api_key", ""));
        if (etMiraGateId != null) etMiraGateId.setText(prefs.getString("mira_gate_id", "handheld_c72"));

        if (spWorkingMode != null) spWorkingMode.setSelection(prefs.getInt("working_mode", 0));

        setScanMode(prefs.getString("scan_mode", "rfid"));
        if (cbParseGS1 != null) cbParseGS1.setChecked(prefs.getBoolean("parse_gs1", true));
        if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(prefs.getBoolean("vibrate_on_scan", true));
        if (cbInventorySound != null) cbInventorySound.setChecked(prefs.getBoolean("inventory_sound", true));

        // تحميل خيارات UHF التفصيلية
        if (spMemoryBank != null) spMemoryBank.setSelection(prefs.getInt("memory_bank", 0));
        if (spSession != null) spSession.setSelection(prefs.getInt("session_idx", 0));
        if (spTarget != null) spTarget.setSelection(prefs.getInt("target_idx", 0));
        if (spLinkParams != null) spLinkParams.setSelection(prefs.getInt("link_params_idx", 0));
        if (spInventoryMode != null) spInventoryMode.setSelection(prefs.getInt("inventory_mode_idx", 0));

        if (cbFastId != null) cbFastId.setChecked(prefs.getBoolean("fast_id", false));
        if (cbTagFocus != null) cbTagFocus.setChecked(prefs.getBoolean("tag_focus", false));
        if (cbDynamicPower != null) cbDynamicPower.setChecked(prefs.getBoolean("dynamic_power", false));

        loadOrgProfile();
    }

    private void loadOrgProfile() {
        if (orgPrefs == null) return;
        if (etOrgNameAr != null) etOrgNameAr.setText(orgPrefs.getString("org_name_ar", "MIRA Jewelry"));
        if (etOrgNameEn != null) etOrgNameEn.setText(orgPrefs.getString("org_name_en", "MIRA Jewelry"));
        if (etOrgSloganAr != null) etOrgSloganAr.setText(orgPrefs.getString("org_slogan_ar", "منصة مجوهرات المؤسسة"));
        if (etOrgSloganEn != null) etOrgSloganEn.setText(orgPrefs.getString("org_slogan_en", "Jewelry Enterprise Platform"));
        if (etOrgPhone != null) etOrgPhone.setText(orgPrefs.getString("org_phone", ""));
        if (etOrgEmail != null) etOrgEmail.setText(orgPrefs.getString("org_email", ""));
        if (etOrgAddress != null) etOrgAddress.setText(orgPrefs.getString("org_address", ""));
        if (etOrgWebsite != null) etOrgWebsite.setText(orgPrefs.getString("org_website", ""));

        String logoBase64 = orgPrefs.getString("org_logo", "");
        if (!logoBase64.isEmpty() && ivOrgLogo != null) {
            try {
                byte[] bytes = Base64.decode(logoBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                ivOrgLogo.setImageBitmap(bitmap);
            } catch (Exception ignored) {}
        }
    }

    private void applyLanguageChange(String langCode) {
        if (mContext == null) return;
        Locale locale = new Locale(langCode);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        mContext.getResources().updateConfiguration(config, mContext.getResources().getDisplayMetrics());
        mContext.recreate();
    }

    private void confirmFactoryReset() {
        if (mContext == null) return;
        new AlertDialog.Builder(mContext)
                .setTitle("⚠️ إعادة ضبط المصنع")
                .setMessage("هل أنت تأكد من مسح جميع الإعدادات المحلية؟")
                .setPositiveButton("تأكيد", (dialog, which) -> {
                    if (prefs != null) prefs.edit().clear().apply();
                    if (orgPrefs != null) orgPrefs.edit().clear().apply();
                    loadAllSettings();
                    Toast.makeText(mContext, "🔄 تم إرجاع الإعدادات الافتراضية", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private String getScanMode() {
        if (rbCameraMode != null && rbCameraMode.isChecked()) return "camera";
        if (rbHybridMode != null && rbHybridMode.isChecked()) return "hybrid";
        if (rbManualMode != null && rbManualMode.isChecked()) return "manual";
        return "rfid";
    }

    private void setScanMode(String mode) {
        if (mode == null) mode = "rfid";
        switch (mode) {
            case "camera": if (rbCameraMode != null) rbCameraMode.setChecked(true); break;
            case "hybrid": if (rbHybridMode != null) rbHybridMode.setChecked(true); break;
            case "manual": if (rbManualMode != null) rbManualMode.setChecked(true); break;
            default: if (rbRfidMode != null) rbRfidMode.setChecked(true);
        }
    }

    private boolean isChecked(CheckBox cb) { return cb != null && cb.isChecked(); }
    private String getText(EditText et) { return et != null ? et.getText().toString().trim() : ""; }

    private void showStatus(TextView tv, String text, int color) {
        if (tv != null) {
            tv.setText(text);
            tv.setTextColor(color);
            tv.postDelayed(() -> tv.setText(""), 3500);
        }
    }
}
