package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraOrgProfileManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class UHFSetFragment extends KeyDwonFragment implements View.OnClickListener {

    private static final int PICK_LOGO = 1001;
    private static final String PREFS_NAME = "MIRA_SETTINGS";
    private static final String ORG_PREFS = "MIRA_ORG_PROFILE";

    private UHFMainActivity mContext;
    private SharedPreferences prefs;

    // Language
    private RadioGroup rgLanguage;
    private RadioButton rbLangAr, rbLangEn;

    // Working Mode
    private Spinner spWorkingMode;

    // Organization Profile
    private EditText etOrgName, etOrgSlogan, etOrgPhone, etOrgEmail, etOrgAddress, etOrgWebsite;
    private ImageView ivOrgLogo;
    private TextView tvOrgStatus;
    private Button btnSaveOrgProfile, btnPickLogo;

    // App Settings
    private CheckBox cbSoundOnScan, cbShowMiraCard, cbRadarSimulation, cbAutoQueryMira;

    // Scan Settings
    private RadioGroup rgScanMode;
    private RadioButton rbRfidMode, rbCameraMode, rbHybridMode, rbManualMode;
    private CheckBox cbAutoScan, cbAutoOpenCamera, cbShowScanFrame, cbParseGS1, cbVibrateOnScan;

    // Inventory Settings
    private Spinner spInventoryMode;
    private CheckBox cbInventorySound, cbInventoryVibrate, cbInventoryAutoStop, cbInventoryShowImages, cbInventorySortByLocation;

    // Action Buttons
    private Button btnSaveAllSettings, btnFactoryReset;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_uhf_set, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViews(view);
        initListeners();
        loadAllSettings();
    }

    private void initViews(View view) {
        // Language
        rgLanguage = view.findViewById(R.id.rgLanguage);
        rbLangAr = view.findViewById(R.id.rbLangAr);
        rbLangEn = view.findViewById(R.id.rbLangEn);

        // Working Mode
        spWorkingMode = view.findViewById(R.id.spWorkingMode);

        // Organization
        etOrgName = view.findViewById(R.id.etOrgName);
        etOrgSlogan = view.findViewById(R.id.etOrgSlogan);
        etOrgPhone = view.findViewById(R.id.etOrgPhone);
        etOrgEmail = view.findViewById(R.id.etOrgEmail);
        etOrgAddress = view.findViewById(R.id.etOrgAddress);
        etOrgWebsite = view.findViewById(R.id.etOrgWebsite);
        ivOrgLogo = view.findViewById(R.id.ivOrgLogo);
        tvOrgStatus = view.findViewById(R.id.tvOrgStatus);
        btnSaveOrgProfile = view.findViewById(R.id.btnSaveOrgProfile);
        btnPickLogo = view.findViewById(R.id.btnPickLogo);

        // App Settings
        cbSoundOnScan = view.findViewById(R.id.cbSoundOnScan);
        cbShowMiraCard = view.findViewById(R.id.cbShowMiraCard);
        cbRadarSimulation = view.findViewById(R.id.cbRadarSimulation);
        cbAutoQueryMira = view.findViewById(R.id.cbAutoQueryMira);

        // Scan Settings
        rgScanMode = view.findViewById(R.id.rgScanMode);
        rbRfidMode = view.findViewById(R.id.rbRfidMode);
        rbCameraMode = view.findViewById(R.id.rbCameraMode);
        rbHybridMode = view.findViewById(R.id.rbHybridMode);
        rbManualMode = view.findViewById(R.id.rbManualMode);
        cbAutoScan = view.findViewById(R.id.cbAutoScan);
        cbAutoOpenCamera = view.findViewById(R.id.cbAutoOpenCamera);
        cbShowScanFrame = view.findViewById(R.id.cbShowScanFrame);
        cbParseGS1 = view.findViewById(R.id.cbParseGS1);
        cbVibrateOnScan = view.findViewById(R.id.cbVibrateOnScan);

        // Inventory
        spInventoryMode = view.findViewById(R.id.spInventoryMode);
        cbInventorySound = view.findViewById(R.id.cbInventorySound);
        cbInventoryVibrate = view.findViewById(R.id.cbInventoryVibrate);
        cbInventoryAutoStop = view.findViewById(R.id.cbInventoryAutoStop);
        cbInventoryShowImages = view.findViewById(R.id.cbInventoryShowImages);
        cbInventorySortByLocation = view.findViewById(R.id.cbInventorySortByLocation);

        // Action Buttons
        btnSaveAllSettings = view.findViewById(R.id.btnSaveAllSettings);
        btnFactoryReset = view.findViewById(R.id.btnFactoryReset);
    }

    private void initListeners() {
        if (btnSaveOrgProfile != null) btnSaveOrgProfile.setOnClickListener(this);
        if (btnPickLogo != null) btnPickLogo.setOnClickListener(v -> pickLogo());
        if (btnSaveAllSettings != null) btnSaveAllSettings.setOnClickListener(this);
        if (btnFactoryReset != null) btnFactoryReset.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnSaveOrgProfile) saveOrgProfile();
        else if (id == R.id.btnSaveAllSettings) saveAllSettings();
        else if (id == R.id.btnFactoryReset) factoryReset();
    }

    // ==================== 🏢 بيانات الجهة ====================

    private void saveOrgProfile() {
        SharedPreferences orgPrefs = mContext.getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE);
        orgPrefs.edit()
            .putString("org_name", getText(etOrgName))
            .putString("org_slogan", getText(etOrgSlogan))
            .putString("org_phone", getText(etOrgPhone))
            .putString("org_email", getText(etOrgEmail))
            .putString("org_address", getText(etOrgAddress))
            .putString("org_website", getText(etOrgWebsite))
            .apply();

        // تطبيق فوري على العنوان
        updateActionBarTitle();

        // حفظ للسحابة
        saveOrgToCloud();

        showStatus(tvOrgStatus, "✅ تم حفظ بيانات الجهة وتطبيقها", 0xFF4CAF50);
    }

    private void updateActionBarTitle() {
        if (mContext != null && mContext.getSupportActionBar() != null) {
            String name = getText(etOrgName);
            if (!name.isEmpty()) {
                mContext.getSupportActionBar().setTitle(name + " - MIRA");
            }
        }
    }

    private void saveOrgToCloud() {
        new Thread(() -> {
            try {
                com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("org_name", getText(etOrgName));
                payload.put("org_slogan", getText(etOrgSlogan));
                payload.put("org_phone", getText(etOrgPhone));
                payload.put("org_email", getText(etOrgEmail));
                payload.put("org_address", getText(etOrgAddress));
                payload.put("org_website", getText(etOrgWebsite));
                client.saveOrgProfile(payload);
            } catch (Exception ignored) {}
        }).start();
    }

    private void pickLogo() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_LOGO);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_LOGO && resultCode == android.app.Activity.RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                InputStream is = mContext.getContentResolver().openInputStream(uri);
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();

                // Resize
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 200, 200, true);
                if (ivOrgLogo != null) ivOrgLogo.setImageBitmap(scaled);

                // Save as Base64
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.PNG, 80, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
                mContext.getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE).edit()
                    .putString("org_logo", base64).apply();

                showStatus(tvOrgStatus, "✅ تم تحميل الشعار", 0xFF4CAF50);
            } catch (Exception e) {
                showStatus(tvOrgStatus, "❌ فشل تحميل الشعار", 0xFFEF4444);
            }
        }
    }

    // ==================== ⚙️ حفظ جميع الإعدادات ====================

    private void saveAllSettings() {
        SharedPreferences.Editor editor = prefs.edit();

        // اللغة
        String lang = (rbLangAr != null && rbLangAr.isChecked()) ? "ar" : "en";
        editor.putString("language", lang);

        // نمط العمل
        if (spWorkingMode != null) editor.putInt("working_mode", spWorkingMode.getSelectedItemPosition());

        // إعدادات التطبيق
        editor.putBoolean("sound_on_scan", isChecked(cbSoundOnScan));
        editor.putBoolean("show_mira_card", isChecked(cbShowMiraCard));
        editor.putBoolean("radar_simulation", isChecked(cbRadarSimulation));
        editor.putBoolean("auto_query_mira", isChecked(cbAutoQueryMira));

        // المسح
        editor.putString("scan_mode", getScanMode());
        editor.putBoolean("auto_scan", isChecked(cbAutoScan));
        editor.putBoolean("auto_open_camera", isChecked(cbAutoOpenCamera));
        editor.putBoolean("show_scan_frame", isChecked(cbShowScanFrame));
        editor.putBoolean("parse_gs1", isChecked(cbParseGS1));
        editor.putBoolean("vibrate_on_scan", isChecked(cbVibrateOnScan));

        // الجرد
        editor.putString("inventory_mode", spInventoryMode != null ? String.valueOf(spInventoryMode.getSelectedItemPosition()) : "0");
        editor.putBoolean("inventory_sound", isChecked(cbInventorySound));
        editor.putBoolean("inventory_vibrate", isChecked(cbInventoryVibrate));
        editor.putBoolean("inventory_auto_stop", isChecked(cbInventoryAutoStop));
        editor.putBoolean("inventory_show_images", isChecked(cbInventoryShowImages));
        editor.putBoolean("inventory_sort_location", isChecked(cbInventorySortByLocation));

        editor.apply();

        // تطبيق اللغة فوراً
        applyLanguageNow(lang);

        Toast.makeText(mContext, "✅ تم حفظ جميع الإعدادات وتطبيقها فوراً", Toast.LENGTH_SHORT).show();
    }

    // ==================== 📥 تحميل الإعدادات ====================

    private void loadAllSettings() {
        // لغة
        String lang = prefs.getString("language", "ar");
        if (rbLangAr != null && rbLangEn != null) {
            if (lang.equals("ar")) rbLangAr.setChecked(true);
            else rbLangEn.setChecked(true);
        }

        // نمط العمل
        if (spWorkingMode != null) spWorkingMode.setSelection(prefs.getInt("working_mode", 0));

        // تطبيق
        if (cbSoundOnScan != null) cbSoundOnScan.setChecked(prefs.getBoolean("sound_on_scan", true));
        if (cbShowMiraCard != null) cbShowMiraCard.setChecked(prefs.getBoolean("show_mira_card", true));
        if (cbRadarSimulation != null) cbRadarSimulation.setChecked(prefs.getBoolean("radar_simulation", true));
        if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(prefs.getBoolean("auto_query_mira", true));

        setScanMode(prefs.getString("scan_mode", "rfid"));
        if (cbAutoScan != null) cbAutoScan.setChecked(prefs.getBoolean("auto_scan", false));
        if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(prefs.getBoolean("auto_open_camera", true));
        if (cbShowScanFrame != null) cbShowScanFrame.setChecked(prefs.getBoolean("show_scan_frame", true));
        if (cbParseGS1 != null) cbParseGS1.setChecked(prefs.getBoolean("parse_gs1", true));
        if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(prefs.getBoolean("vibrate_on_scan", true));

        if (spInventoryMode != null) spInventoryMode.setSelection(Integer.parseInt(prefs.getString("inventory_mode", "0")));
        if (cbInventorySound != null) cbInventorySound.setChecked(prefs.getBoolean("inventory_sound", true));
        if (cbInventoryVibrate != null) cbInventoryVibrate.setChecked(prefs.getBoolean("inventory_vibrate", true));
        if (cbInventoryAutoStop != null) cbInventoryAutoStop.setChecked(prefs.getBoolean("inventory_auto_stop", false));
        if (cbInventoryShowImages != null) cbInventoryShowImages.setChecked(prefs.getBoolean("inventory_show_images", false));
        if (cbInventorySortByLocation != null) cbInventorySortByLocation.setChecked(prefs.getBoolean("inventory_sort_location", true));

        loadOrgProfile();
    }

    private void loadOrgProfile() {
        SharedPreferences orgPrefs = mContext.getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE);
        if (etOrgName != null) etOrgName.setText(orgPrefs.getString("org_name", "MIRA Jewelry"));
        if (etOrgSlogan != null) etOrgSlogan.setText(orgPrefs.getString("org_slogan", "Jewelry Enterprise Platform"));
        if (etOrgPhone != null) etOrgPhone.setText(orgPrefs.getString("org_phone", ""));
        if (etOrgEmail != null) etOrgEmail.setText(orgPrefs.getString("org_email", ""));
        if (etOrgAddress != null) etOrgAddress.setText(orgPrefs.getString("org_address", ""));
        if (etOrgWebsite != null) etOrgWebsite.setText(orgPrefs.getString("org_website", ""));

        // Logo
        String logoBase64 = orgPrefs.getString("org_logo", "");
        if (!logoBase64.isEmpty() && ivOrgLogo != null) {
            try {
                byte[] bytes = Base64.decode(logoBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                ivOrgLogo.setImageBitmap(bitmap);
            } catch (Exception ignored) {}
        }
    }

    // ==================== 🌐 تطبيق اللغة ====================

    private void applyLanguageNow(String lang) {
        String currentLang = prefs.getString("language", "ar");
        if (!lang.equals(currentLang)) return; // لم تتغير

        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        mContext.getResources().updateConfiguration(config, mContext.getResources().getDisplayMetrics());
        mContext.recreate();
    }

    // ==================== 🔄 إعادة ضبط ====================

    private void factoryReset() {
        prefs.edit().clear().apply();
        mContext.getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        loadAllSettings();
        Toast.makeText(mContext, "🔄 تمت إعادة الضبط", Toast.LENGTH_SHORT).show();
    }

    // ==================== Helpers ====================

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
            tv.postDelayed(() -> tv.setText(""), 3000);
        }
    }
}
