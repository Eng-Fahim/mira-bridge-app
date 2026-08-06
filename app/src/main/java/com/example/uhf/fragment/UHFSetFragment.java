package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraSettingsManager;
import com.example.uhf.tools.StringUtils;
import com.lidroid.xutils.ViewUtils;
import com.lidroid.xutils.view.annotation.ViewInject;
import com.lidroid.xutils.view.annotation.event.OnClick;
import com.rscja.deviceapi.entity.FastInventoryEntity;
import com.rscja.deviceapi.entity.Gen2Entity;
import com.rscja.deviceapi.entity.InventoryModeEntity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MIRA Settings Fragment
 * 
 * يجمع بين:
 * - نمط العمل (Working Modes)
 * - اختيار اللغة (عربي / إنجليزي)
 * - بيانات الجهة (Organization Profile)
 * - إعدادات التطبيق والمسح والجرد
 * - إعدادات قارئ RFID الأصلية (محفوظة بالكامل)
 * - حفظ مركزي عبر MiraSettingsManager
 * - API مخفي - مثبت في MiraApiClient
 */
public class UHFSetFragment extends KeyDwonFragment implements View.OnClickListener {

    private static final String TAG = "UHFSetFragment";
    private UHFMainActivity mContext;

    // ==================== Language Settings ====================
    private RadioGroup rgLanguage;
    private RadioButton rbLangAr, rbLangEn;

    // ==================== Working Mode ====================
    private Spinner spWorkingMode;

    // ==================== Organization Profile ====================
    private EditText etOrgName, etOrgSlogan, etOrgPhone, etOrgEmail, etOrgAddress, etOrgWebsite;
    private Button btnSaveOrgProfile;
    private TextView tvOrgStatus;

    // ==================== App Settings ====================
    private CheckBox cbSoundOnScan, cbShowMiraCard, cbRadarSimulation, cbAutoQueryMira;
    private Button btnSaveAllSettings;

    // ==================== Scan Settings ====================
    private RadioGroup rgScanMode;
    private RadioButton rbRfidMode, rbCameraMode, rbHybridMode, rbManualMode;
    private CheckBox cbAutoScan, cbAutoOpenCamera, cbShowScanFrame, cbParseGS1, cbVibrateOnScan;

    // ==================== Inventory Settings ====================
    private CheckBox cbInventorySound, cbInventoryVibrate, cbInventoryAutoStop;
    private CheckBox cbInventoryShowImages, cbInventorySortByLocation;
    private Spinner spInventoryMode;

    // ==================== RFID Elements (ViewInject) ====================
    @ViewInject(R.id.ll_freHop)
    private LinearLayout ll_freHop;

    @ViewInject(R.id.spPower)
    private Spinner spPower;

    @ViewInject(R.id.spFreHop)
    private Spinner spFreHop;
    
    @ViewInject(R.id.btnSetFreHop)
    private Button btnSetFreHop;

    @ViewInject(R.id.btnSetLinkParams)
    private Button btnSetLinkParams;
    
    @ViewInject(R.id.btnGetLinkParams)
    private Button btnGetLinkParams;
    
    @ViewInject(R.id.splinkParams)
    private Spinner splinkParams;

    @ViewInject(R.id.spMemoryBank)
    private Spinner spMemoryBank;
    
    @ViewInject(R.id.llMemoryBankParams)
    private LinearLayout llMemoryBankParams;
    
    @ViewInject(R.id.etOffset)
    private EditText etOffset;
    
    @ViewInject(R.id.etLength)
    private EditText etLength;
    
    private int[] arrayMemoryBankValue;
    
    @ViewInject(R.id.btnSetMemoryBank)
    Button btnSetMemoryBank;
    
    @ViewInject(R.id.btnGetMemoryBank)
    Button btnGetMemoryBank;

    @ViewInject(R.id.cbTagFocus)
    private CheckBox cbTagFocus;
    
    @ViewInject(R.id.cbFastID)
    private CheckBox cbFastID;

    @ViewInject(R.id.rb_America)
    private RadioButton rb_America;
    
    @ViewInject(R.id.rb_Others)
    private RadioButton rb_Others;
    
    private ArrayAdapter adapter;

    @ViewInject(R.id.spFastInventory)
    private Spinner spFastInventory;
    
    @ViewInject(R.id.btnSetFastInventory)
    private Button btnSetFastInventory;
    
    @ViewInject(R.id.btnGetFastInventory)
    private Button btnGetFastInventory;

    @ViewInject(R.id.btnFactoryReset)
    private Button btnFactoryReset;

    // ==================== RFID Manual Bindings ====================
    private Button btnSetFre;
    private Button btnGetFre;
    private Spinner spFrequency;
    Spinner spSessionID, spInventoried;
    Button btnSetSession, btnGetSession;
    Button btnGetPower, btnSetPower;

    // ==================== Internal Variables ====================
    private DisplayMetrics metrics;
    private AlertDialog dialog;
    private Handler mHandler = new Handler();
    private int arrPow;
    private String[] arrayMode;
    private List<Integer> arrayLinkValue;
    private MiraSettingsManager settingsManager;
    private boolean isArabic;

    // ==================== Lifecycle ====================

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_uhf_set, container, false);
        ViewUtils.inject(this, root);

        // تحديد اللغة الحالية
        isArabic = "ar".equals(Locale.getDefault().getLanguage());

        // 🌐 ربط عناصر اللغة
        rgLanguage = root.findViewById(R.id.rgLanguage);
        rbLangAr = root.findViewById(R.id.rbLangAr);
        rbLangEn = root.findViewById(R.id.rbLangEn);
        if (rgLanguage != null) {
            setupLanguageSelector();
        }

        // 🏢 ربط بيانات الجهة
        etOrgName = root.findViewById(R.id.etOrgName);
        etOrgSlogan = root.findViewById(R.id.etOrgSlogan);
        etOrgPhone = root.findViewById(R.id.etOrgPhone);
        etOrgEmail = root.findViewById(R.id.etOrgEmail);
        etOrgAddress = root.findViewById(R.id.etOrgAddress);
        etOrgWebsite = root.findViewById(R.id.etOrgWebsite);
        btnSaveOrgProfile = root.findViewById(R.id.btnSaveOrgProfile);
        tvOrgStatus = root.findViewById(R.id.tvOrgStatus);
        if (btnSaveOrgProfile != null) {
            btnSaveOrgProfile.setOnClickListener(v -> saveOrgProfile());
        }

        // ربط RFID
        spSessionID = root.findViewById(R.id.spSession);
        spInventoried = root.findViewById(R.id.spTarget);
        btnGetSession = root.findViewById(R.id.btnGetSession);
        btnSetSession = root.findViewById(R.id.btnSetSession);
        btnGetPower = root.findViewById(R.id.btnGetPower);
        btnSetPower = root.findViewById(R.id.btnSetPower);
        btnSetFastInventory = root.findViewById(R.id.btnSetFastInventory);
        btnGetFastInventory = root.findViewById(R.id.btnGetFastInventory);

        // ربط عام
        spWorkingMode = root.findViewById(R.id.spWorkingMode);
        cbSoundOnScan = root.findViewById(R.id.cbSoundOnScan);
        cbShowMiraCard = root.findViewById(R.id.cbShowMiraCard);
        cbRadarSimulation = root.findViewById(R.id.cbRadarSimulation);
        cbAutoQueryMira = root.findViewById(R.id.cbAutoQueryMira);
        btnSaveAllSettings = root.findViewById(R.id.btnSaveAllSettings);

        // ربط إعدادات المسح
        rgScanMode = root.findViewById(R.id.rgScanMode);
        rbRfidMode = root.findViewById(R.id.rbRfidMode);
        rbCameraMode = root.findViewById(R.id.rbCameraMode);
        rbHybridMode = root.findViewById(R.id.rbHybridMode);
        rbManualMode = root.findViewById(R.id.rbManualMode);
        cbAutoScan = root.findViewById(R.id.cbAutoScan);
        cbAutoOpenCamera = root.findViewById(R.id.cbAutoOpenCamera);
        cbShowScanFrame = root.findViewById(R.id.cbShowScanFrame);
        cbParseGS1 = root.findViewById(R.id.cbParseGS1);
        cbVibrateOnScan = root.findViewById(R.id.cbVibrateOnScan);

        // ربط إعدادات الجرد
        cbInventorySound = root.findViewById(R.id.cbInventorySound);
        cbInventoryVibrate = root.findViewById(R.id.cbInventoryVibrate);
        cbInventoryAutoStop = root.findViewById(R.id.cbInventoryAutoStop);
        cbInventoryShowImages = root.findViewById(R.id.cbInventoryShowImages);
        cbInventorySortByLocation = root.findViewById(R.id.cbInventorySortByLocation);
        spInventoryMode = root.findViewById(R.id.spInventoryMode);

        // إعداد Memory Bank
        llMemoryBankParams.setVisibility(View.GONE);
        spMemoryBank.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                llMemoryBankParams.setVisibility(position == 2 || position == 3 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        return root;
    }

    // =============================================
    // 🌐 إدارة اللغة
    // =============================================

    private void setupLanguageSelector() {
        String currentLang = Locale.getDefault().getLanguage();
        if ("ar".equalsIgnoreCase(currentLang)) {
            if (rbLangAr != null) rbLangAr.setChecked(true);
        } else {
            if (rbLangEn != null) rbLangEn.setChecked(true);
        }

        rgLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            String selectedLang = (checkedId == R.id.rbLangAr) ? "ar" : "en";
            if (!selectedLang.equalsIgnoreCase(Locale.getDefault().getLanguage())) {
                changeAppLanguage(selectedLang);
            }
        });
    }

    private void changeAppLanguage(String langCode) {
        LocaleListCompat appLocales = LocaleListCompat.forLanguageTags(langCode);
        AppCompatDelegate.setApplicationLocales(appLocales);
        
        SharedPreferences prefs = mContext.getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        prefs.edit().putString("app_language", langCode).apply();
        
        mContext.runOnUiThread(() -> {
            mContext.recreate();
            Toast.makeText(mContext, 
                "ar".equals(langCode) ? "تم تغيير اللغة إلى العربية" : "Language changed to English", 
                Toast.LENGTH_SHORT).show();
        });
    }

    // =============================================
    // 🏢 بيانات الجهة
    // =============================================

    private void saveOrgProfile() {
        SharedPreferences prefs = mContext.getSharedPreferences("MIRA_ORG_PROFILE", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putString("org_name", etOrgName.getText().toString().trim());
        editor.putString("org_slogan", etOrgSlogan.getText().toString().trim());
        editor.putString("org_phone", etOrgPhone.getText().toString().trim());
        editor.putString("org_email", etOrgEmail.getText().toString().trim());
        editor.putString("org_address", etOrgAddress.getText().toString().trim());
        editor.putString("org_website", etOrgWebsite.getText().toString().trim());
        editor.apply();
        
        // تحديث الواجهة الرئيسية
        if (mContext != null && mContext.getSupportActionBar() != null) {
            String orgName = etOrgName.getText().toString().trim();
            if (!orgName.isEmpty()) {
                mContext.getSupportActionBar().setTitle(orgName + " - MIRA");
            }
        }
        
        // حفظ سحابي
        saveOrgProfileToCloud();
        
        if (tvOrgStatus != null) {
            tvOrgStatus.setText(isArabic ? "✅ تم حفظ بيانات الجهة بنجاح" : "✅ Organization profile saved");
            tvOrgStatus.setTextColor(Color.parseColor("#4CAF50"));
            
            new Handler().postDelayed(() -> {
                if (tvOrgStatus != null) tvOrgStatus.setText("");
            }, 3000);
        }
        
        Toast.makeText(mContext, isArabic ? "✅ تم حفظ بيانات الجهة وتعميمها" : "✅ Organization profile saved & applied", Toast.LENGTH_SHORT).show();
    }

    private void loadOrgProfile() {
        SharedPreferences prefs = mContext.getSharedPreferences("MIRA_ORG_PROFILE", Context.MODE_PRIVATE);
        
        if (etOrgName != null) etOrgName.setText(prefs.getString("org_name", "MIRA Jewelry"));
        if (etOrgSlogan != null) etOrgSlogan.setText(prefs.getString("org_slogan", "Jewelry Enterprise Platform"));
        if (etOrgPhone != null) etOrgPhone.setText(prefs.getString("org_phone", ""));
        if (etOrgEmail != null) etOrgEmail.setText(prefs.getString("org_email", ""));
        if (etOrgAddress != null) etOrgAddress.setText(prefs.getString("org_address", ""));
        if (etOrgWebsite != null) etOrgWebsite.setText(prefs.getString("org_website", ""));
    }

    private void saveOrgProfileToCloud() {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("org_name", etOrgName.getText().toString().trim());
                payload.put("org_slogan", etOrgSlogan.getText().toString().trim());
                payload.put("org_phone", etOrgPhone.getText().toString().trim());
                payload.put("org_email", etOrgEmail.getText().toString().trim());
                payload.put("org_address", etOrgAddress.getText().toString().trim());
                payload.put("org_website", etOrgWebsite.getText().toString().trim());
                
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                MiraApiClient.ApiResponse response = client.saveOrgProfile(payload);
                
                mHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Log.d(TAG, "✅ Org profile saved to cloud");
                    } else {
                        Log.w(TAG, "⚠️ Cloud save failed, saved locally only");
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Cloud org save error: " + e.getMessage());
            }
        }).start();
    }

    // =============================================
    // 🟢 التهيئة الرئيسية
    // =============================================

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        if (mContext == null) return;
        mContext.currentFragment = this;

        // تهيئة مدير الإعدادات
        settingsManager = MiraSettingsManager.getInstance(mContext);

        // تهيئة مصفوفات RFID
        arrayMode = getResources().getStringArray(R.array.arrayMode);
        int[] arrayLink = getResources().getIntArray(R.array.arrayLinkValue);
        if (BuildConfig.Test) {
            arrayLink = getResources().getIntArray(R.array.arrayLinkValueTest);
            String[] arrayLinkTest = getResources().getStringArray(R.array.arrayLinkTest);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, arrayLinkTest);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            splinkParams.setAdapter(adapter);
        }

        arrayLinkValue = new ArrayList<>();
        for (int j : arrayLink) {
            arrayLinkValue.add(j);
        }
        arrayMemoryBankValue = getResources().getIntArray(R.array.arrayMemoryBankValue);

        // ربط أزرار RFID
        btnSetFre = getView().findViewById(R.id.btnSetFrequency);
        btnGetFre = getView().findViewById(R.id.btnGetFrequency);
        spFrequency = getView().findViewById(R.id.spFrequency);
        spFrequency.setOnItemSelectedListener(new MyOnTouchListener());

        btnSetFre.setOnClickListener(new SetFreOnclickListener());
        btnGetFre.setOnClickListener(new GetFreOnclickListener());
        btnSetFreHop.setOnClickListener(this);
        btnSetLinkParams.setOnClickListener(this);
        btnGetLinkParams.setOnClickListener(this);
        btnSetMemoryBank.setOnClickListener(this);
        btnGetMemoryBank.setOnClickListener(this);
        btnGetSession.setOnClickListener(this);
        btnSetSession.setOnClickListener(this);
        btnGetPower.setOnClickListener(v -> getPower(true));
        btnSetPower.setOnClickListener(v -> setPower());
        btnGetFastInventory.setOnClickListener(v -> getFastInventory(true));

        cbTagFocus.setOnCheckedChangeListener(new OnMyCheckedChangedListener());
        cbFastID.setOnCheckedChangeListener(new OnMyCheckedChangedListener());

        // مستمعات عامة
        btnSaveAllSettings.setOnClickListener(v -> saveAllSettings());

        // مستمع نمط العمل
        if (spWorkingMode != null) {
            spWorkingMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    switch (position) {
                        case 0: applyMiraYemenMode(); break;
                        case 1: applyMiraStandardMode(); break;
                        case 2: applyRfidOnlyMode(); break;
                        case 3: applyDevMode(); break;
                    }
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // تهيئة Spinner الطاقة
        arrPow = R.array.arrayPower;
        ArrayAdapter powerAdapter = ArrayAdapter.createFromResource(mContext, arrPow, android.R.layout.simple_spinner_item);
        powerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPower.setAdapter(powerAdapter);

        // تحميل الإعدادات
        loadAllSettings();
        loadOrgProfile();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mContext == null || mContext.mReader == null) return;
        new Thread(() -> {
            getFre(false);
            getLinkParams(false);
            getPower(false);
            getMomoryBank(false);
            getSession();
            getFastInventory(false);
        }).start();
    }

    // =============================================
    // 🟢 أنماط العمل (Working Modes)
    // =============================================

    private void applyMiraYemenMode() {
        if (mContext != null && mContext.mReader != null) {
            mContext.mReader.setFrequencyMode((byte) 0x01);
            mContext.mReader.setPower(26);
            mContext.mReader.setRFLink(0);
        }
        
        if (cbSoundOnScan != null) cbSoundOnScan.setChecked(true);
        if (cbShowMiraCard != null) cbShowMiraCard.setChecked(true);
        if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(true);
        if (cbRadarSimulation != null) cbRadarSimulation.setChecked(false);
        if (rbRfidMode != null) rbRfidMode.setChecked(true);
        if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(true);
        if (cbParseGS1 != null) cbParseGS1.setChecked(true);
        if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(true);
        
        settingsManager.saveSetting("working_mode", "mira_yemen");
        saveAllSettings();
        
        Toast.makeText(mContext, isArabic ? "🇾🇪 تم تطبيق نمط MIRA Yemen" : "🇾🇪 MIRA Yemen Mode Applied", Toast.LENGTH_SHORT).show();
    }

    private void applyMiraStandardMode() {
        if (mContext != null && mContext.mReader != null) {
            mContext.mReader.setFrequencyMode((byte) 0x08);
            mContext.mReader.setPower(22);
        }
        
        if (cbSoundOnScan != null) cbSoundOnScan.setChecked(true);
        if (cbShowMiraCard != null) cbShowMiraCard.setChecked(true);
        if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(true);
        if (cbRadarSimulation != null) cbRadarSimulation.setChecked(true);
        if (rbHybridMode != null) rbHybridMode.setChecked(true);
        
        settingsManager.saveSetting("working_mode", "mira_standard");
        saveAllSettings();
        
        Toast.makeText(mContext, isArabic ? "🌍 تم تطبيق النمط العالمي" : "🌍 Global Standard Mode Applied", Toast.LENGTH_SHORT).show();
    }

    private void applyRfidOnlyMode() {
        if (cbSoundOnScan != null) cbSoundOnScan.setChecked(true);
        if (cbShowMiraCard != null) cbShowMiraCard.setChecked(false);
        if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(false);
        if (cbRadarSimulation != null) cbRadarSimulation.setChecked(false);
        if (rbRfidMode != null) rbRfidMode.setChecked(true);
        if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(false);
        
        settingsManager.saveSetting("working_mode", "rfid_only");
        saveAllSettings();
        
        Toast.makeText(mContext, isArabic ? "📡 نمط RFID فقط" : "📡 RFID Only Mode", Toast.LENGTH_SHORT).show();
    }

    private void applyDevMode() {
        if (cbRadarSimulation != null) cbRadarSimulation.setChecked(true);
        if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(false);
        if (rbHybridMode != null) rbHybridMode.setChecked(true);
        
        settingsManager.saveSetting("working_mode", "dev");
        saveAllSettings();
        
        Toast.makeText(mContext, isArabic ? "🛠️ نمط المطور" : "🛠️ Developer Mode", Toast.LENGTH_SHORT).show();
    }

    // =============================================
    // 🟢 إعدادات المسح
    // =============================================

    private String getSelectedScanMode() {
        if (rbCameraMode != null && rbCameraMode.isChecked()) return "camera";
        if (rbHybridMode != null && rbHybridMode.isChecked()) return "hybrid";
        if (rbManualMode != null && rbManualMode.isChecked()) return "manual";
        return "rfid";
    }

    private void setSelectedScanMode(String mode) {
        if (mode == null) mode = "rfid";
        switch (mode) {
            case "camera":
                if (rbCameraMode != null) rbCameraMode.setChecked(true);
                break;
            case "hybrid":
                if (rbHybridMode != null) rbHybridMode.setChecked(true);
                break;
            case "manual":
                if (rbManualMode != null) rbManualMode.setChecked(true);
                break;
            default:
                if (rbRfidMode != null) rbRfidMode.setChecked(true);
                break;
        }
    }

    // =============================================
    // 🟢 حفظ وتحميل الإعدادات
    // =============================================

    private void saveAllSettings() {
        if (settingsManager == null) return;
        
        settingsManager.saveSetting("sound_on_scan", cbSoundOnScan != null && cbSoundOnScan.isChecked());
        settingsManager.saveSetting("show_mira_card", cbShowMiraCard != null && cbShowMiraCard.isChecked());
        settingsManager.saveSetting("radar_simulation", cbRadarSimulation != null && cbRadarSimulation.isChecked());
        settingsManager.saveSetting("auto_query_mira", cbAutoQueryMira != null && cbAutoQueryMira.isChecked());
        settingsManager.saveSetting("scan_mode", getSelectedScanMode());
        if (cbAutoScan != null) settingsManager.saveSetting("auto_scan", cbAutoScan.isChecked());
        if (cbAutoOpenCamera != null) settingsManager.saveSetting("auto_open_camera", cbAutoOpenCamera.isChecked());
        if (cbShowScanFrame != null) settingsManager.saveSetting("show_scan_frame", cbShowScanFrame.isChecked());
        if (cbParseGS1 != null) settingsManager.saveSetting("parse_gs1", cbParseGS1.isChecked());
        if (cbVibrateOnScan != null) settingsManager.saveSetting("vibrate_on_scan", cbVibrateOnScan.isChecked());
        if (spInventoryMode != null) settingsManager.saveSetting("inventory_mode", spInventoryMode.getSelectedItem().toString());
        if (cbInventorySound != null) settingsManager.saveSetting("inventory_sound", cbInventorySound.isChecked());
        if (cbInventoryVibrate != null) settingsManager.saveSetting("inventory_vibrate", cbInventoryVibrate.isChecked());
        if (cbInventoryAutoStop != null) settingsManager.saveSetting("inventory_auto_stop", cbInventoryAutoStop.isChecked());
        if (cbInventoryShowImages != null) settingsManager.saveSetting("inventory_show_images", cbInventoryShowImages.isChecked());
        if (cbInventorySortByLocation != null) settingsManager.saveSetting("inventory_sort_location", cbInventorySortByLocation.isChecked());

        mContext.showToast(isArabic ? "✅ تم حفظ جميع الإعدادات" : "✅ All settings saved successfully");
    }

    private void loadAllSettings() {
        if (settingsManager == null) return;
        
        if (cbSoundOnScan != null) cbSoundOnScan.setChecked(settingsManager.getBoolean("sound_on_scan", true));
        if (cbShowMiraCard != null) cbShowMiraCard.setChecked(settingsManager.getBoolean("show_mira_card", true));
        if (cbRadarSimulation != null) cbRadarSimulation.setChecked(settingsManager.getBoolean("radar_simulation", true));
        if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(settingsManager.getBoolean("auto_query_mira", true));

        setSelectedScanMode(settingsManager.getSetting("scan_mode", "rfid"));
        if (cbAutoScan != null) cbAutoScan.setChecked(settingsManager.getBoolean("auto_scan", false));
        if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(settingsManager.getBoolean("auto_open_camera", true));
        if (cbShowScanFrame != null) cbShowScanFrame.setChecked(settingsManager.getBoolean("show_scan_frame", true));
        if (cbParseGS1 != null) cbParseGS1.setChecked(settingsManager.getBoolean("parse_gs1", true));
        if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(settingsManager.getBoolean("vibrate_on_scan", true));

        if (spInventoryMode != null) {
            String invMode = settingsManager.getSetting("inventory_mode", "Full");
            for (int i = 0; i < spInventoryMode.getCount(); i++) {
                if (spInventoryMode.getItemAtPosition(i).toString().equals(invMode)) {
                    spInventoryMode.setSelection(i); break;
                }
            }
        }
        if (cbInventorySound != null) cbInventorySound.setChecked(settingsManager.getBoolean("inventory_sound", true));
        if (cbInventoryVibrate != null) cbInventoryVibrate.setChecked(settingsManager.getBoolean("inventory_vibrate", true));
        if (cbInventoryAutoStop != null) cbInventoryAutoStop.setChecked(settingsManager.getBoolean("inventory_auto_stop", false));
        if (cbInventoryShowImages != null) cbInventoryShowImages.setChecked(settingsManager.getBoolean("inventory_show_images", false));
        if (cbInventorySortByLocation != null) cbInventorySortByLocation.setChecked(settingsManager.getBoolean("inventory_sort_location", true));

        String workingMode = settingsManager.getSetting("working_mode", "mira_yemen");
        if (spWorkingMode != null) {
            switch (workingMode) {
                case "mira_yemen": spWorkingMode.setSelection(0); break;
                case "mira_standard": spWorkingMode.setSelection(1); break;
                case "rfid_only": spWorkingMode.setSelection(2); break;
                case "dev": spWorkingMode.setSelection(3); break;
            }
        }
    }

    // =============================================
    // 🟢 دوال RFID الأصلية (محفوظة بالكامل)
    // =============================================

    public class MyOnTouchListener implements AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (spFrequency.getSelectedItem().toString().equals(getString(R.string.United_States_Standard))) {
                rb_America.setChecked(true);
            } else if (position != 3) {
                ll_freHop.setVisibility(View.GONE);
            }
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    }

    public class SetFreOnclickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (mContext == null || mContext.mReader == null) return;
            String strMode = spFrequency.getSelectedItem().toString();
            int mode = getMode(strMode);
            if (mContext.mReader.setFrequencyMode((byte) mode)) {
                mContext.showToast(R.string.uhf_msg_set_frequency_succ);
            } else {
                mContext.showToast(R.string.uhf_msg_set_frequency_fail);
            }
        }
    }

    public void getFre(boolean showToast) {
        if (mContext == null || mContext.mReader == null) return;
        int mode = mContext.mReader.getFrequencyMode();
        mHandler.post(() -> {
            if (mode != -1) {
                int count = spFrequency.getCount();
                int idx = getModeIndex(mode);
                spFrequency.setSelection(Math.min(idx, count - 1));
                if (showToast) mContext.showToast(R.string.uhf_msg_read_frequency_succ);
            } else {
                if (showToast) mContext.showToast(R.string.uhf_msg_read_frequency_fail);
            }
        });
    }

    public void getLinkParams(boolean showToast) {
        if (mContext == null || mContext.mReader == null) return;
        int link = mContext.mReader.getRFLink();
        mHandler.post(() -> {
            if (link == -1) {
                if (showToast) mContext.showToast(R.string.uhf_msg_get_para_fail);
                return;
            }
            if (arrayLinkValue.contains(link)) {
                int index = arrayLinkValue.indexOf(link);
                if (index < getResources().getStringArray(R.array.arrayLink).length) {
                    splinkParams.setSelection(index);
                    if (showToast) mContext.showToast(R.string.uhf_msg_get_para_succ);
                    return;
                }
            }
            if (showToast) mContext.showToast("RFLink = " + link);
        });
    }

    private int getMode(String modeName) {
        if (modeName.equals(getString(R.string.China_Standard_840_845MHz))) return 0x01;
        else if (modeName.equals(getString(R.string.China_Standard_920_925MHz))) return 0x02;
        else if (modeName.equals(getString(R.string.ETSI_Standard))) return 0x04;
        else if (modeName.equals(getString(R.string.United_States_Standard))) return 0x08;
        else if (modeName.equals(getString(R.string.Korea))) return 0x16;
        else if (modeName.equals(getString(R.string.Japan))) return 0x32;
        else if (modeName.equals(getString(R.string.South_Africa_915_919MHz))) return 0x33;
        else if (modeName.equals(getString(R.string.New_Zealand))) return 0x34;
        else if (modeName.equals(getString(R.string.Morocco))) return 0x80;
        return 0x08;
    }

    private String getModeName(int mode) {
        switch (mode) {
            case 0x01: return getString(R.string.China_Standard_840_845MHz);
            case 0x02: return getString(R.string.China_Standard_920_925MHz);
            case 0x04: return getString(R.string.ETSI_Standard);
            case 0x08: return getString(R.string.United_States_Standard);
            case 0x16: return getString(R.string.Korea);
            case 0x32: return getString(R.string.Japan);
            case 0x33: return getString(R.string.South_Africa_915_919MHz);
            case 0x34: return getString(R.string.New_Zealand);
            case 0x80: return getString(R.string.Morocco);
            default: return getString(R.string.United_States_Standard);
        }
    }

    private int getModeIndex(String modeName) {
        for (int i = 0; i < arrayMode.length; i++) {
            if (arrayMode[i].equals(modeName)) return i;
        }
        return 0;
    }

    private int getModeIndex(int mode) {
        return getModeIndex(getModeName(mode));
    }

    public class GetFreOnclickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            getFre(true);
        }
    }

    public class OnMyCheckedChangedListener implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (mContext == null || mContext.mReader == null) return;
            switch (buttonView.getId()) {
                case R.id.cbTagFocus:
                    if (mContext.mReader.setTagFocus(isChecked)) {
                        cbTagFocus.setText(isChecked ? R.string.tagFocus_off : R.string.tagFocus);
                        mContext.showToast(R.string.uhf_msg_set_succ);
                    } else {
                        mContext.showToast(R.string.uhf_msg_set_fail);
                    }
                    break;
                case R.id.cbFastID:
                    if (mContext.mReader.setFastID(isChecked)) {
                        cbFastID.setText(isChecked ? R.string.fastID_off : R.string.fastID);
                        mContext.showToast(R.string.uhf_msg_set_succ);
                    } else {
                        mContext.showToast(R.string.uhf_msg_set_fail);
                    }
                    break;
            }
        }
    }

    public void getPower(boolean showToast) {
        if (mContext == null || mContext.mReader == null) return;
        int iPower = mContext.mReader.getPower();
        mHandler.post(() -> {
            if (iPower > -1) {
                int position = iPower - 1;
                int count = spPower.getCount();
                spPower.setSelection(Math.min(position, count - 1));
                if (showToast) mContext.showToast(R.string.uhf_msg_read_power_succ);
            } else {
                if (showToast) mContext.showToast(R.string.uhf_msg_read_power_fail);
            }
        });
    }

    public void setPower() {
        if (mContext == null || mContext.mReader == null) return;
        int iPower = spPower.getSelectedItemPosition() + 1;
        if (mContext.mReader.setPower(iPower)) {
            mContext.showToast(R.string.uhf_msg_set_power_succ);
        } else {
            mContext.showToast(R.string.uhf_msg_set_power_fail);
        }
    }

    private boolean setFreHop(float value) {
        if (mContext == null || mContext.mReader == null) return false;
        boolean result = mContext.mReader.setFreHop(value);
        if (result) {
            mContext.showToast(R.string.uhf_msg_set_frehop_succ);
        } else {
            mContext.showToast(R.string.uhf_msg_set_frehop_fail);
        }
        return result;
    }

    @Override
    public void onClick(View v) {
        if (mContext == null || mContext.mReader == null) return;
        switch (v.getId()) {
            case R.id.btnSetFreHop:
                View view = spFreHop.getSelectedView();
                if (view instanceof TextView) {
                    String freHop = ((TextView) view).getText().toString().trim();
                    setFreHop(Float.valueOf(freHop));
                }
                break;
            case R.id.btnSetLinkParams:
                int index = splinkParams.getSelectedItemPosition();
                int link = arrayLinkValue.get(index);
                if (mContext.mReader.setRFLink(link)) {
                    mContext.showToast(R.string.uhf_msg_set_succ);
                } else {
                    mContext.showToast(R.string.uhf_msg_set_fail);
                }
                break;
            case R.id.btnGetLinkParams:
                getLinkParams(true);
                break;
            case R.id.rbEPC:
                llMemoryBankParams.setVisibility(View.GONE);
                break;
            case R.id.btnSetMemoryBank:
                setMemoryBank();
                break;
            case R.id.btnGetMemoryBank:
                getMomoryBank(true);
                break;
            case R.id.btnGetSession:
                if (getSession()) {
                    mContext.showToast(R.string.uhf_msg_get_para_succ);
                } else {
                    mContext.showToast(R.string.uhf_msg_get_para_fail);
                }
                break;
            case R.id.btnSetSession:
                setSession();
                break;
            default:
                break;
        }
    }

    private boolean getSession() {
        if (mContext == null || mContext.mReader == null) return false;
        Gen2Entity entity = mContext.mReader.getGen2();
        if (entity != null) {
            mHandler.post(() -> {
                spSessionID.setSelection(entity.getQuerySession());
                spInventoried.setSelection(entity.getQueryTarget());
            });
            return true;
        }
        return false;
    }

    private void setSession() {
        if (mContext == null || mContext.mReader == null) return;
        int sessionId = spSessionID.getSelectedItemPosition();
        int inventoried = spInventoried.getSelectedItemPosition();
        if (sessionId < 0 || inventoried < 0) return;
        
        Gen2Entity p = mContext.mReader.getGen2();
        if (p != null) {
            p.setQueryTarget(inventoried);
            p.setQuerySession(sessionId);
            if (mContext.mReader.setGen2(p)) {
                mContext.showToast(R.string.uhf_msg_set_succ);
            } else {
                mContext.showToast(R.string.uhf_msg_set_fail);
            }
        } else {
            mContext.showToast(R.string.uhf_msg_set_fail);
        }
    }

    public int getWindowWidth() {
        if (metrics == null) {
            metrics = new DisplayMetrics();
            getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        }
        return metrics.widthPixels;
    }

    public int getWindowHeight() {
        if (metrics == null) {
            metrics = new DisplayMetrics();
            getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        }
        return metrics.heightPixels;
    }

    @OnClick(R.id.rb_America)
    public void onClick_rbAmerica(View view) {
        adapter = ArrayAdapter.createFromResource(mContext, R.array.arrayFreHop_us, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFreHop.setAdapter(adapter);
    }

    @OnClick(R.id.rb_Others)
    public void onClick_rbOthers(View view) {
        adapter = ArrayAdapter.createFromResource(mContext, R.array.arrayFreHop, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFreHop.setAdapter(adapter);
    }

    private void getMomoryBank(boolean isToast) {
        if (mContext == null || mContext.mReader == null) return;
        InventoryModeEntity mode = mContext.mReader.getEPCAndTIDUserMode();
        if (mode == null) {
            if (isToast) mContext.showToast(R.string.get_succ);
            return;
        }
        int bank = mode.getMode();
        boolean result = false;
        for (int i = 0; i < arrayMemoryBankValue.length; i++) {
            if (bank == arrayMemoryBankValue[i]) {
                final int index = i;
                mHandler.postDelayed(() -> {
                    spMemoryBank.setSelection(index);
                    if (bank == InventoryModeEntity.MODE_EPC_TID_USER) {
                        etOffset.setText(String.valueOf(mode.getUserOffset()));
                        etLength.setText(String.valueOf(mode.getUserLength()));
                    } else if (bank == InventoryModeEntity.MODE_EPC_RESERVED) {
                        etOffset.setText(String.valueOf(mode.getReservedOffset()));
                        etLength.setText(String.valueOf(mode.getReservedLength()));
                    }
                }, 50);
                result = true;
                break;
            }
        }
        if (isToast) {
            mContext.showToast(result ? getString(R.string.get_succ) : getString(R.string.get_fail) + " mode=" + mode.getMode());
        }
    }

    private void setMemoryBank() {
        if (mContext == null || mContext.mReader == null) return;
        if ((spMemoryBank.getSelectedItemPosition() == 2 || spMemoryBank.getSelectedItemPosition() == 3)) {
            if (StringUtils.toInt(etOffset.getText().toString().trim(), Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                mContext.showToast(R.string.uhf_msg_offset_error);
                return;
            }
            if (StringUtils.toInt(etLength.getText().toString().trim(), Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                mContext.showToast(R.string.uhf_msg_length_error);
                return;
            }
        }
        int position = spMemoryBank.getSelectedItemPosition();
        boolean result = false;
        int offset = 0, length = 6;
        
        if (position == 0) {
            result = mContext.mReader.setEPCMode();
        } else if (position == 1) {
            result = mContext.mReader.setEPCAndTIDMode();
        } else if (position == 2) {
            offset = StringUtils.toInt(etOffset.getText().toString().trim(), 0);
            length = StringUtils.toInt(etLength.getText().toString().trim(), 6);
            result = mContext.mReader.setEPCAndTIDUserMode(offset, length);
        } else if (position == 3) {
            offset = StringUtils.toInt(etOffset.getText().toString().trim(), 0);
            length = StringUtils.toInt(etLength.getText().toString().trim(), 4);
            InventoryModeEntity entity = new InventoryModeEntity.Builder()
                    .setMode(InventoryModeEntity.MODE_EPC_RESERVED)
                    .setReservedOffset(offset)
                    .setReservedLength(length)
                    .build();
            result = mContext.mReader.setEPCAndTIDUserMode(entity);
        } else if (position == 4) {
            InventoryModeEntity entity = new InventoryModeEntity.Builder()
                    .setMode(InventoryModeEntity.MODE_LED_TAG)
                    .build();
            result = mContext.mReader.setEPCAndTIDUserMode(entity);
        } else if (position == 5) {
            result = mContext.mReader.setEPCAndTIDUserMode(
                new InventoryModeEntity.Builder()
                    .setMode(InventoryModeEntity.MODE_EPC_TID_M775AUTHENTICATION)
                    .build()
            );
        }

        mContext.showToast(result ? R.string.setting_succ : R.string.setting_fail);
    }

    private void getFastInventory(boolean showToast) {
        if (mContext == null || mContext.mReader == null) return;
        FastInventoryEntity entity = mContext.mReader.getFastInventoryMode();
        mHandler.post(() -> {
            if (entity == null || entity.getCr() < 0) {
                if (showToast) mContext.showToast(R.string.get_fail);
                return;
            }
            if (entity.getCr() < spFastInventory.getCount()) {
                spFastInventory.setSelection(entity.getCr());
                if (showToast) mContext.showToast(R.string.get_succ);
            } else {
                if (showToast) mContext.showToast("Cr = " + entity.getCr());
            }
        });
    }

    @OnClick(R.id.btnSetFastInventory)
    public void setFastInventory(View view) {
        if (mContext == null || mContext.mReader == null) return;
        FastInventoryEntity entity = new FastInventoryEntity(spFastInventory.getSelectedItemPosition());
        boolean flag = mContext.mReader.setFastInventoryMode(entity);
        mContext.showToast(flag ? R.string.setting_succ : R.string.setting_fail);
    }

    @OnClick(R.id.btnFactoryReset)
    public void btnFactoryResetClick(View view) {
        String title = isArabic ? "⚠️ تأكيد إعادة الضبط" : "⚠️ Factory Reset";
        String message = isArabic ? 
            "سيتم:\n• إعادة ضبط إعدادات القارئ\n• مسح إعدادات MIRA المحلية\n• استعادة الإعدادات الافتراضية\n\nهل أنت متأكد؟" :
            "This will:\n• Reset reader settings\n• Clear local MIRA settings\n• Restore defaults\n\nAre you sure?";
        String posBtn = isArabic ? "نعم، إعادة الضبط" : "Yes, Reset";
        String negBtn = isArabic ? "إلغاء" : "Cancel";

        new AlertDialog.Builder(mContext)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(posBtn, (dialog, which) -> {
                boolean rfidReset = (mContext.mReader != null) && mContext.mReader.factoryReset();
                
                SharedPreferences prefs = mContext.getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
                prefs.edit().clear().apply();
                
                if (rfidReset || mContext.mReader == null) {
                    mContext.showToast(isArabic ? "✅ تمت إعادة الضبط بنجاح" : "✅ Reset successfully");
                    
                    new Thread(() -> {
                        getFre(false); getLinkParams(false); getPower(false);
                        getMomoryBank(false); getSession(); getFastInventory(false);
                    }).start();
                    
                    if (cbSoundOnScan != null) cbSoundOnScan.setChecked(true);
                    if (cbShowMiraCard != null) cbShowMiraCard.setChecked(true);
                    if (cbRadarSimulation != null) cbRadarSimulation.setChecked(true);
                    if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(true);
                    if (spWorkingMode != null) spWorkingMode.setSelection(0);
                    if (rbRfidMode != null) rbRfidMode.setChecked(true);
                    if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(true);
                    if (cbParseGS1 != null) cbParseGS1.setChecked(true);
                    if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(true);
                } else {
                    mContext.showToast(isArabic ? "❌ فشلت إعادة ضبط القارئ" : "❌ Reader reset failed");
                }
            })
            .setNegativeButton(negBtn, null)
            .show();
    }
                      }
