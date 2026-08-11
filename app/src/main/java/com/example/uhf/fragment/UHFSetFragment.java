package com.example.uhf.fragment;
import android.content.Intent;
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
    private EditText etOrgName, etOrgSlogan, etOrgPhone, etOrgEmail, etOrgAddress, etOrgWebsite,etOrgNameEn, etOrgSloganEn, etOrgAddressEn;
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
        etOrgNameEn = view.findViewById(R.id.etOrgNameEn);
        etOrgSloganEn = view.findViewById(R.id.etOrgSloganEn);
        etOrgAddressEn = view.findViewById(R.id.etOrgAddressEn);

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
            payload.put("org_name_en", getText(etOrgNameEn));
            payload.put("org_slogan_en", getText(etOrgSloganEn));
            payload.put("org_address_en", getText(etOrgAddressEn));
            
            // ✅ إرسال الشعار
            SharedPreferences orgPrefs = mContext.getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE);
            String logoBase64 = orgPrefs.getString("org_logo", "");
            if (!logoBase64.isEmpty()) {
                payload.put("org_logo", logoBase64);
            }
            
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

    // 1. القراءة من الواجهة
    String lang = (rbLangAr != null && rbLangAr.isChecked()) ? "ar" : "en";
    int workingMode = spWorkingMode != null ? spWorkingMode.getSelectedItemPosition() : 0;
    
    boolean soundOnScan = isChecked(cbSoundOnScan);
    boolean showMiraCard = isChecked(cbShowMiraCard);
    boolean radarSim = isChecked(cbRadarSimulation);
    boolean autoQueryMira = isChecked(cbAutoQueryMira);

    String scanMode = getScanMode();
    boolean autoScan = isChecked(cbAutoScan);
    boolean autoOpenCamera = isChecked(cbAutoOpenCamera);
    boolean showScanFrame = isChecked(cbShowScanFrame);
    boolean parseGS1 = isChecked(cbParseGS1);
    boolean vibrateOnScan = isChecked(cbVibrateOnScan);

    String inventoryMode = spInventoryMode != null ? String.valueOf(spInventoryMode.getSelectedItemPosition()) : "0";
    boolean inventorySound = isChecked(cbInventorySound);
    boolean inventoryVibrate = isChecked(cbInventoryVibrate);
    boolean inventoryAutoStop = isChecked(cbInventoryAutoStop);
    boolean inventoryShowImages = isChecked(cbInventoryShowImages);
    boolean inventorySortLocation = isChecked(cbInventorySortByLocation);

    // 2. الحفظ المحلي (SharedPreferences)
    editor.putString("language", lang);
    editor.putInt("working_mode", workingMode);
    editor.putBoolean("sound_on_scan", soundOnScan);
    editor.putBoolean("show_mira_card", showMiraCard);
    editor.putBoolean("radar_simulation", radarSim);
    editor.putBoolean("auto_query_mira", autoQueryMira);

    editor.putString("scan_mode", scanMode);
    editor.putBoolean("auto_scan", autoScan);
    editor.putBoolean("auto_open_camera", autoOpenCamera);
    editor.putBoolean("show_scan_frame", showScanFrame);
    editor.putBoolean("parse_gs1", parseGS1);
    editor.putBoolean("vibrate_on_scan", vibrateOnScan);

    editor.putString("inventory_mode", inventoryMode);
    editor.putBoolean("inventory_sound", inventorySound);
    editor.putBoolean("inventory_vibrate", inventoryVibrate);
    editor.putBoolean("inventory_auto_stop", inventoryAutoStop);
    editor.putBoolean("inventory_show_images", inventoryShowImages);
    editor.putBoolean("inventory_sort_location", inventorySortLocation);

    editor.apply();

    // 3. 🟢 إرسال البيانات وإصدار أمر الحفظ للسيرفر
    new Thread(() -> {
        try {
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("language", lang);
            payload.put("working_mode", workingMode);
            payload.put("sound_on_scan", soundOnScan);
            payload.put("show_mira_card", showMiraCard);
            payload.put("radar_simulation", radarSim);
            payload.put("auto_query_mira", autoQueryMira);
            
            payload.put("scan_mode", scanMode);
            payload.put("auto_scan", autoScan);
            payload.put("auto_open_camera", autoOpenCamera);
            payload.put("show_scan_frame", showScanFrame);
            payload.put("parse_gs1", parseGS1);
            payload.put("vibrate_on_scan", vibrateOnScan);

            payload.put("inventory_mode", inventoryMode);
            payload.put("inventory_sound", inventorySound);
            payload.put("inventory_vibrate", inventoryVibrate);
            payload.put("inventory_auto_stop", inventoryAutoStop);
            payload.put("inventory_show_images", inventoryShowImages);
            payload.put("inventory_sort_location", inventorySortLocation);

            com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
            com.example.uhf.api.MiraApiClient.ApiResponse response = client.saveSettings(payload);

            if (mContext != null) {
                mContext.runOnUiThread(() -> {
                    if (response.isSuccess) {
                        Toast.makeText(mContext, "✅ تم الحفظ محلياً وعلى السيرفر بنجاح!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(mContext, "⚠️ تم الحفظ محلياً وفشل الحفظ السحابي", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }).start();

    // تطبيق اللغة فوراً
    applyLanguageNow(lang);
}


    // ==================== 📥 تحميل الإعدادات ====================

    private void loadAllSettings() {
    // أ) التحميل السريع المبدئي من SharedPreferences لعدم تأخير الواجهة
    loadSettingsFromLocal();

    // ب) 🟢 الجلب المباشر من السيرفر لتحديث القيم بأحدث بيانات من قاعدة البيانات
    new Thread(() -> {
        com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(mContext);
        com.example.uhf.api.MiraApiClient.ApiResponse response = client.getSettings();

        if (response.isSuccess && response.data != null) {
            org.json.JSONObject serverSettings = response.data.optJSONObject("settings");
            if (serverSettings != null && mContext != null) {
                mContext.runOnUiThread(() -> {
                    applySettingsToUI(serverSettings);
                });
            }
        }
    }).start();

    loadOrgProfile();
}

// دالة مساعدة لتطبيق البيانات القادمة من السيرفر على الواجهة
private void applySettingsToUI(org.json.JSONObject s) {
    try {
        if (s.has("sound_on_scan") && cbSoundOnScan != null) cbSoundOnScan.setChecked(s.optBoolean("sound_on_scan"));
        if (s.has("show_mira_card") && cbShowMiraCard != null) cbShowMiraCard.setChecked(s.optBoolean("show_mira_card"));
        if (s.has("radar_simulation") && cbRadarSimulation != null) cbRadarSimulation.setChecked(s.optBoolean("radar_simulation"));
        if (s.has("auto_query_mira") && cbAutoQueryMira != null) cbAutoQueryMira.setChecked(s.optBoolean("auto_query_mira"));

        if (s.has("scan_mode")) setScanMode(s.optString("scan_mode"));
        if (s.has("auto_scan") && cbAutoScan != null) cbAutoScan.setChecked(s.optBoolean("auto_scan"));
        if (s.has("auto_open_camera") && cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(s.optBoolean("auto_open_camera"));
        if (s.has("show_scan_frame") && cbShowScanFrame != null) cbShowScanFrame.setChecked(s.optBoolean("show_scan_frame"));
        if (s.has("parse_gs1") && cbParseGS1 != null) cbParseGS1.setChecked(s.optBoolean("parse_gs1"));
        if (s.has("vibrate_on_scan") && cbVibrateOnScan != null) cbVibrateOnScan.setChecked(s.optBoolean("vibrate_on_scan"));

        if (s.has("inventory_mode") && spInventoryMode != null) {
            try {
                spInventoryMode.setSelection(Integer.parseInt(s.optString("inventory_mode", "0")));
            } catch (Exception ignored) {}
        }
        if (s.has("inventory_sound") && cbInventorySound != null) cbInventorySound.setChecked(s.optBoolean("inventory_sound"));
        if (s.has("inventory_vibrate") && cbInventoryVibrate != null) cbInventoryVibrate.setChecked(s.optBoolean("inventory_vibrate"));
        if (s.has("inventory_auto_stop") && cbInventoryAutoStop != null) cbInventoryAutoStop.setChecked(s.optBoolean("inventory_auto_stop"));
        if (s.has("inventory_show_images") && cbInventoryShowImages != null) cbInventoryShowImages.setChecked(s.optBoolean("inventory_show_images"));
        if (s.has("inventory_sort_location") && cbInventorySortByLocation != null) cbInventorySortByLocation.setChecked(s.optBoolean("inventory_sort_location"));

    } catch (Exception e) {
        e.printStackTrace();
    }
}

// دالة التحميل المحلي السريع
private void loadSettingsFromLocal() {
    String lang = prefs.getString("language", "ar");
    if (rbLangAr != null && rbLangEn != null) {
        if (lang.equals("ar")) rbLangAr.setChecked(true);
        else rbLangEn.setChecked(true);
    }

    if (spWorkingMode != null) spWorkingMode.setSelection(prefs.getInt("working_mode", 0));

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
}


    private void loadOrgProfile() {
        SharedPreferences orgPrefs = mContext.getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE);
        if (etOrgName != null) etOrgName.setText(orgPrefs.getString("org_name", "MIRA Jewelry"));
        if (etOrgSlogan != null) etOrgSlogan.setText(orgPrefs.getString("org_slogan", "Jewelry Enterprise Platform"));
        if (etOrgPhone != null) etOrgPhone.setText(orgPrefs.getString("org_phone", ""));
        if (etOrgEmail != null) etOrgEmail.setText(orgPrefs.getString("org_email", ""));
        if (etOrgAddress != null) etOrgAddress.setText(orgPrefs.getString("org_address", ""));
        if (etOrgWebsite != null) etOrgWebsite.setText(orgPrefs.getString("org_website", ""));
        
        if (etOrgNameEn != null) etOrgNameEn.setText(orgPrefs.getString("org_name_en", ""));
        if (etOrgSloganEn != null) etOrgSloganEn.setText(orgPrefs.getString("org_slogan_en", ""));
        if (etOrgAddressEn != null) etOrgAddressEn.setText(orgPrefs.getString("org_address_en", ""));
        
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
