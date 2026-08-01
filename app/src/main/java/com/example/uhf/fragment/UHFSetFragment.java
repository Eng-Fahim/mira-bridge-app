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
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraSettingsManager;
import com.example.uhf.tools.StringUtils;
import com.lidroid.xutils.ViewUtils;
import com.lidroid.xutils.view.annotation.ViewInject;
import com.lidroid.xutils.view.annotation.event.OnClick;
import com.rscja.deviceapi.entity.FastInventoryEntity;
import com.rscja.deviceapi.entity.Gen2Entity;
import com.rscja.deviceapi.entity.InventoryModeEntity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * MIRA Bridge Configuration Fragment
 * 
 * يجمع بين:
 * - إعدادات MIRA Bridge (API, Cloud Sync, Working Modes)
 * - إعدادات المسح (RFID, كاميرا, هجين, يدوي)
 * - إعدادات قارئ RFID الأصلية (محفوظة بالكامل)
 * - حفظ محلي + سحابي في MIRA ID Database
 * - تطبيق فوري للإعدادات على جميع أجزاء التطبيق
 */
public class UHFSetFragment extends KeyDwonFragment implements OnClickListener {
    private static final String TAG = "UHFSetFragment";
    private UHFMainActivity mContext;

    // =============================================
    // 🟢 إعدادات RFID الأصلية
    // =============================================
    private Button btnSetFre;
    private Button btnGetFre;
    private Spinner spFrequency;

    // =============================================
    // 🟢 إعدادات المسح الجديدة
    // =============================================
    private RadioGroup rgScanMode;
    private RadioButton rbRfidMode, rbCameraMode, rbHybridMode, rbManualMode;
    private CheckBox cbAutoScan, cbAutoOpenCamera, cbShowScanFrame, cbParseGS1, cbVibrateOnScan;

    // =============================================
// 🟢 إعدادات الجرد
// =============================================
private CheckBox cbInventorySound, cbInventoryVibrate, cbInventoryAutoStop;
private CheckBox cbInventoryShowImages, cbInventorySortByLocation;
private Spinner spInventoryMode;

    // =============================================
    // 🟢 عناصر RFID المُحقونة بـ ViewInject
    // =============================================
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

    // =============================================
    // 🟢 عناصر MIRA Bridge
    // =============================================
    private Spinner spWorkingMode;
    private Spinner spMiraApiUrl;
    private EditText etMiraApiKey;
    private EditText etMiraGateId;
    private Button btnTestMiraConnection;
    private TextView tvMiraConnectionStatus;
    private CheckBox cbSoundOnScan, cbShowMiraCard, cbRadarSimulation, cbAutoQueryMira;
    private Button btnSaveAllSettings;

    // =============================================
    // 🟢 متغيرات عامة
    // =============================================
    private DisplayMetrics metrics;
    private AlertDialog dialog;
    private Handler mHandler = new Handler();
    private int arrPow;
    private String[] arrayMode;
    private List<Integer> arrayLinkValue;
    private MiraSettingsManager settingsManager;

    Spinner spSessionID, spInventoried;
    Button btnSetSession, btnGetSession;
    Button btnGetPower, btnSetPower;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_uhf_set, container, false);
        ViewUtils.inject(this, root);

        // ربط عناصر RFID
        spSessionID = root.findViewById(R.id.spSession);
        spInventoried = root.findViewById(R.id.spTarget);
        btnGetSession = root.findViewById(R.id.btnGetSession);
        btnSetSession = root.findViewById(R.id.btnSetSession);
        btnGetPower = root.findViewById(R.id.btnGetPower);
        btnSetPower = root.findViewById(R.id.btnSetPower);
        btnSetFastInventory = root.findViewById(R.id.btnSetFastInventory);
        btnGetFastInventory = root.findViewById(R.id.btnGetFastInventory);

        // 🟢 ربط عناصر MIRA Bridge
        spWorkingMode = root.findViewById(R.id.spWorkingMode);
        spMiraApiUrl = root.findViewById(R.id.spMiraApiUrl);
        etMiraApiKey = root.findViewById(R.id.etMiraApiKey);
        etMiraGateId = root.findViewById(R.id.etMiraGateId);
        btnTestMiraConnection = root.findViewById(R.id.btnTestMiraConnection);
        tvMiraConnectionStatus = root.findViewById(R.id.tvMiraConnectionStatus);
        cbSoundOnScan = root.findViewById(R.id.cbSoundOnScan);
        cbShowMiraCard = root.findViewById(R.id.cbShowMiraCard);
        cbRadarSimulation = root.findViewById(R.id.cbRadarSimulation);
        cbAutoQueryMira = root.findViewById(R.id.cbAutoQueryMira);
        btnSaveAllSettings = root.findViewById(R.id.btnSaveAllSettings);

        // 🟢 ربط عناصر إعدادات المسح
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

        // 🟢 ربط عناصر إعدادات الجرد
cbInventorySound = root.findViewById(R.id.cbInventorySound);
cbInventoryVibrate = root.findViewById(R.id.cbInventoryVibrate);
cbInventoryAutoStop = root.findViewById(R.id.cbInventoryAutoStop);
cbInventoryShowImages = root.findViewById(R.id.cbInventoryShowImages);
cbInventorySortByLocation = root.findViewById(R.id.cbInventorySortByLocation);
spInventoryMode = root.findViewById(R.id.spInventoryMode);

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

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        if (mContext == null) return;  
    mContext.currentFragment = this;

        // 🟢 تهيئة مدير الإعدادات
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

        // 🟢 مستمعات MIRA Bridge
        btnTestMiraConnection.setOnClickListener(v -> testMiraConnection());
        btnSaveAllSettings.setOnClickListener(v -> saveAllSettings());

        // 🟢 مستمع نمط العمل
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
        String ver = "";
if (mContext != null && mContext.mReader != null) {
    ver = mContext.mReader.getVersion();
}
        arrPow = R.array.arrayPower;
        ArrayAdapter powerAdapter = ArrayAdapter.createFromResource(mContext, arrPow, android.R.layout.simple_spinner_item);
        powerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPower.setAdapter(powerAdapter);

        // 🟢 تحميل الإعدادات المحفوظة
        loadMiraSettings();
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
        
        // 🟢 تحميل الإعدادات من السحابة
        loadSettingsFromCloud();
    }

    // =============================================
    // 🟢 أنماط العمل (Working Modes)
    // =============================================

    private void applyMiraYemenMode() {
        mContext.mReader.setFrequencyMode((byte) 0x01);
        mContext.mReader.setPower(26);
        mContext.mReader.setRFLink(0);
        
        cbSoundOnScan.setChecked(true);
        cbShowMiraCard.setChecked(true);
        cbAutoQueryMira.setChecked(true);
        cbRadarSimulation.setChecked(false);
        if (rbRfidMode != null) rbRfidMode.setChecked(true);
        if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(true);
        if (cbParseGS1 != null) cbParseGS1.setChecked(true);
        if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(true);
        
        settingsManager.saveSetting("working_mode", "mira_yemen");
        saveAllSettings();
        
        Toast.makeText(mContext, "🇾🇪 تم تطبيق نمط MIRA ID Yemen", Toast.LENGTH_SHORT).show();
    }

    private void applyMiraStandardMode() {
        mContext.mReader.setFrequencyMode((byte) 0x08);
        mContext.mReader.setPower(22);
        
        cbSoundOnScan.setChecked(true);
        cbShowMiraCard.setChecked(true);
        cbAutoQueryMira.setChecked(true);
        cbRadarSimulation.setChecked(true);
        if (rbHybridMode != null) rbHybridMode.setChecked(true);
        
        settingsManager.saveSetting("working_mode", "mira_standard");
        saveAllSettings();
        
        Toast.makeText(mContext, "🌍 تم تطبيق النمط العالمي", Toast.LENGTH_SHORT).show();
    }

    private void applyRfidOnlyMode() {
        cbSoundOnScan.setChecked(true);
        cbShowMiraCard.setChecked(false);
        cbAutoQueryMira.setChecked(false);
        cbRadarSimulation.setChecked(false);
        if (rbRfidMode != null) rbRfidMode.setChecked(true);
        if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(false);
        
        settingsManager.saveSetting("working_mode", "rfid_only");
        saveAllSettings();
        
        Toast.makeText(mContext, "📡 نمط RFID فقط", Toast.LENGTH_SHORT).show();
    }

    private void applyDevMode() {
        cbRadarSimulation.setChecked(true);
        cbAutoQueryMira.setChecked(false);
        if (rbHybridMode != null) rbHybridMode.setChecked(true);
        
        settingsManager.saveSetting("working_mode", "dev");
        saveAllSettings();
        
        Toast.makeText(mContext, "🛠️ نمط المطور", Toast.LENGTH_SHORT).show();
    }

    // =============================================
    // 🟢 دوال إعدادات المسح
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
    // 🟢 دوال MIRA Bridge
    // =============================================

    private void testMiraConnection() {
        tvMiraConnectionStatus.setText("🟡 جاري الاختبار...");
        tvMiraConnectionStatus.setTextColor(Color.parseColor("#FF9800"));

        new Thread(() -> {
            try {
                String apiUrl = spMiraApiUrl.getSelectedItem().toString();
                String apiKey = etMiraApiKey.getText().toString().trim();
                String gateId = etMiraGateId.getText().toString().trim();

                if (apiKey.isEmpty()) {
                    mHandler.post(() -> {
                        tvMiraConnectionStatus.setText("🔴 الرجاء إدخال مفتاح API");
                        tvMiraConnectionStatus.setTextColor(Color.RED);
                    });
                    return;
                }

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("epc", "TEST_CONNECTION");
                json.put("gate_id", gateId);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = json.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                final int code = conn.getResponseCode();
                
                InputStream is = (code >= 200 && code < 300) 
                    ? conn.getInputStream() 
                    : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line.trim());
                }
                
                Log.d(TAG, "MIRA Test Response: " + code + " - " + response.toString());

                mHandler.post(() -> {
                    if (code >= 200 && code < 300) {
                        tvMiraConnectionStatus.setText("🟢 متصل بـ MIRA Server ✓");
                        tvMiraConnectionStatus.setTextColor(Color.parseColor("#4CAF50"));
                        mContext.showToast("✅ تم الاتصال بـ MIRA بنجاح");
                    } else if (code == 401) {
                        tvMiraConnectionStatus.setText("🔴 غير مصرح - تحقق من مفتاح API");
                        tvMiraConnectionStatus.setTextColor(Color.RED);
                        mContext.showToast("❌ مفتاح API غير صحيح");
                    } else {
                        tvMiraConnectionStatus.setText("🔴 فشل الاتصال (كود: " + code + ")");
                        tvMiraConnectionStatus.setTextColor(Color.RED);
                        mContext.showToast("❌ فشل الاتصال - كود: " + code);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "MIRA Connection Test Error: " + e.getMessage());
                mHandler.post(() -> {
                    tvMiraConnectionStatus.setText("🔴 خطأ: " + e.getMessage());
                    tvMiraConnectionStatus.setTextColor(Color.RED);
                    mContext.showToast("❌ خطأ في الاتصال");
                });
            }
        }).start();
    }

    private void saveAllSettings() {
        String apiUrl = spMiraApiUrl.getSelectedItem().toString();
        String apiKey = etMiraApiKey.getText().toString().trim();
        String gateId = etMiraGateId.getText().toString().trim();
        boolean soundOnScan = cbSoundOnScan.isChecked();
        boolean showMiraCard = cbShowMiraCard.isChecked();
        boolean radarSimulation = cbRadarSimulation.isChecked();
        boolean autoQueryMira = cbAutoQueryMira.isChecked();

        // حفظ محلي
        SharedPreferences prefs = mContext.getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("mira_api_url", apiUrl);
        editor.putString("mira_api_key", apiKey);
        editor.putString("mira_gate_id", gateId);
        editor.putBoolean("sound_on_scan", soundOnScan);
        editor.putBoolean("show_mira_card", showMiraCard);
        editor.putBoolean("radar_simulation", radarSimulation);
        editor.putBoolean("auto_query_mira", autoQueryMira);
        editor.putString("scan_mode", getSelectedScanMode());
        editor.putBoolean("auto_scan", cbAutoScan != null && cbAutoScan.isChecked());
        editor.putBoolean("auto_open_camera", cbAutoOpenCamera != null && cbAutoOpenCamera.isChecked());
        editor.putBoolean("show_scan_frame", cbShowScanFrame != null && cbShowScanFrame.isChecked());
        editor.putBoolean("parse_gs1", cbParseGS1 != null && cbParseGS1.isChecked());
        editor.putBoolean("vibrate_on_scan", cbVibrateOnScan != null && cbVibrateOnScan.isChecked());

        // 🟢 حفظ إعدادات الجرد
editor.putString("inventory_mode", spInventoryMode.getSelectedItem().toString());
editor.putString("inventory_sound", cbInventorySound.isChecked() ? "true" : "false");
editor.putString("inventory_vibrate", cbInventoryVibrate.isChecked() ? "true" : "false");
editor.putString("inventory_auto_stop", cbInventoryAutoStop.isChecked() ? "true" : "false");
editor.putString("inventory_show_images", cbInventoryShowImages.isChecked() ? "true" : "false");
editor.putString("inventory_sort_location", cbInventorySortByLocation.isChecked() ? "true" : "false");
        editor.apply();

        // إشعار مدير الإعدادات
        settingsManager.saveSetting("sound_on_scan", soundOnScan);
        settingsManager.saveSetting("show_mira_card", showMiraCard);
        settingsManager.saveSetting("radar_simulation", radarSimulation);
        settingsManager.saveSetting("auto_query_mira", autoQueryMira);
        settingsManager.saveSetting("scan_mode", getSelectedScanMode());
        if (cbAutoScan != null) settingsManager.saveSetting("auto_scan", cbAutoScan.isChecked());
        if (cbAutoOpenCamera != null) settingsManager.saveSetting("auto_open_camera", cbAutoOpenCamera.isChecked());
        if (cbShowScanFrame != null) settingsManager.saveSetting("show_scan_frame", cbShowScanFrame.isChecked());
        if (cbParseGS1 != null) settingsManager.saveSetting("parse_gs1", cbParseGS1.isChecked());
        if (cbVibrateOnScan != null) settingsManager.saveSetting("vibrate_on_scan", cbVibrateOnScan.isChecked());

        // حفظ سحابي
        saveSettingsToCloud();

        mContext.showToast("✅ تم حفظ جميع الإعدادات");
        Log.d(TAG, "MIRA Settings Saved: url=" + apiUrl + ", gate=" + gateId);
    }

    private void saveSettingsToCloud() {
        new Thread(() -> {
            try {
                String apiUrl = "https://ams.ibreg.org/wp-json/mira-gate/v1/settings";
                String apiKey = etMiraApiKey.getText().toString().trim();
                String gateId = etMiraGateId.getText().toString().trim();
                
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                
                JSONObject body = new JSONObject();
                body.put("gate_id", gateId);
                
                JSONObject settings = new JSONObject();
                settings.put("api_url", spMiraApiUrl.getSelectedItem().toString());
                settings.put("api_key", apiKey);
                settings.put("gate_id", gateId);
                settings.put("sound_on_scan", cbSoundOnScan.isChecked());
                settings.put("show_mira_card", cbShowMiraCard.isChecked());
                settings.put("radar_simulation", cbRadarSimulation.isChecked());
                settings.put("auto_query_mira", cbAutoQueryMira.isChecked());
                settings.put("scan_mode", getSelectedScanMode());
                settings.put("auto_scan", cbAutoScan != null && cbAutoScan.isChecked());
                settings.put("auto_open_camera", cbAutoOpenCamera != null && cbAutoOpenCamera.isChecked());
                settings.put("show_scan_frame", cbShowScanFrame != null && cbShowScanFrame.isChecked());
                settings.put("parse_gs1", cbParseGS1 != null && cbParseGS1.isChecked());
                settings.put("vibrate_on_scan", cbVibrateOnScan != null && cbVibrateOnScan.isChecked());
                settings.put("frequency_mode", spFrequency.getSelectedItemPosition());
                settings.put("power", spPower.getSelectedItemPosition() + 1);
                settings.put("link_profile", splinkParams.getSelectedItemPosition());
                
                body.put("settings", settings);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("utf-8"));
                }
                
                final int code = conn.getResponseCode();
                
                mHandler.post(() -> {
                    if (code == 200) {
                        Log.d(TAG, "Cloud save successful");
                    } else {
                        Log.w(TAG, "Cloud save failed with code: " + code);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Cloud save error: " + e.getMessage());
            }
        }).start();
    }

    private void loadMiraSettings() {
        SharedPreferences prefs = mContext.getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        
        String apiUrl = prefs.getString("mira_api_url", "");
        String apiKey = prefs.getString("mira_api_key", "mira_gate_test071234567890abcdefghijklmnop");
        String gateId = prefs.getString("mira_gate_id", "handheld_c72");
        
        if (!apiUrl.isEmpty() && spMiraApiUrl != null) {
            for (int i = 0; i < spMiraApiUrl.getCount(); i++) {
                if (spMiraApiUrl.getItemAtPosition(i).toString().equals(apiUrl)) {
                    spMiraApiUrl.setSelection(i);
                    break;
                }
            }
        }
        
        if (etMiraApiKey != null) etMiraApiKey.setText(apiKey);
        if (etMiraGateId != null) etMiraGateId.setText(gateId);
        
        if (cbSoundOnScan != null) cbSoundOnScan.setChecked(prefs.getBoolean("sound_on_scan", true));
        if (cbShowMiraCard != null) cbShowMiraCard.setChecked(prefs.getBoolean("show_mira_card", true));
        if (cbRadarSimulation != null) cbRadarSimulation.setChecked(prefs.getBoolean("radar_simulation", true));
        if (cbAutoQueryMira != null) cbAutoQueryMira.setChecked(prefs.getBoolean("auto_query_mira", true));

        // 🟢 تحميل إعدادات المسح
        String scanMode = prefs.getString("scan_mode", "rfid");
        setSelectedScanMode(scanMode);
        if (cbAutoScan != null) cbAutoScan.setChecked(prefs.getBoolean("auto_scan", false));
        if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(prefs.getBoolean("auto_open_camera", true));
        if (cbShowScanFrame != null) cbShowScanFrame.setChecked(prefs.getBoolean("show_scan_frame", true));
        if (cbParseGS1 != null) cbParseGS1.setChecked(prefs.getBoolean("parse_gs1", true));
        if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(prefs.getBoolean("vibrate_on_scan", true));

        // 🟢 تحميل إعدادات الجرد
if (spInventoryMode != null) {
    String invMode = prefs.getString("inventory_mode", "Full");
    for (int i = 0; i < spInventoryMode.getCount(); i++) {
        if (spInventoryMode.getItemAtPosition(i).toString().equals(invMode)) {
            spInventoryMode.setSelection(i); break;
        }
    }
}
if (cbInventorySound != null) cbInventorySound.setChecked("true".equals(prefs.getString("inventory_sound", "true")));
if (cbInventoryVibrate != null) cbInventoryVibrate.setChecked("true".equals(prefs.getString("inventory_vibrate", "true")));
if (cbInventoryAutoStop != null) cbInventoryAutoStop.setChecked("true".equals(prefs.getString("inventory_auto_stop", "false")));
if (cbInventoryShowImages != null) cbInventoryShowImages.setChecked("true".equals(prefs.getString("inventory_show_images", "false")));
if (cbInventorySortByLocation != null) cbInventorySortByLocation.setChecked("true".equals(prefs.getString("inventory_sort_location", "true")));
        // 🟢 استعادة نمط العمل
        String workingMode = prefs.getString("working_mode", "mira_yemen");
        if (spWorkingMode != null) {
            switch (workingMode) {
                case "mira_yemen": spWorkingMode.setSelection(0); break;
                case "mira_standard": spWorkingMode.setSelection(1); break;
                case "rfid_only": spWorkingMode.setSelection(2); break;
                case "dev": spWorkingMode.setSelection(3); break;
            }
        }
    }

    private void loadSettingsFromCloud() {
        new Thread(() -> {
            try {
                String gateId = etMiraGateId.getText().toString().trim();
                String apiKey = etMiraApiKey.getText().toString().trim();
                
                if (gateId.isEmpty() || apiKey.isEmpty()) return;
                
                URL url = new URL("https://ams.ibreg.org/wp-json/mira-gate/v1/settings/" + gateId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    
                    JSONObject json = new JSONObject(response.toString());
                    JSONObject cloudSettings = json.getJSONObject("settings");
                    
                    mHandler.post(() -> applyCloudSettings(cloudSettings));
                }
            } catch (Exception e) {
                Log.e(TAG, "Cloud load error: " + e.getMessage());
            }
        }).start();
    }

    private void applyCloudSettings(JSONObject settings) {
        try {
            if (settings.has("sound_on_scan")) 
                cbSoundOnScan.setChecked(settings.getBoolean("sound_on_scan"));
            if (settings.has("show_mira_card")) 
                cbShowMiraCard.setChecked(settings.getBoolean("show_mira_card"));
            if (settings.has("radar_simulation")) 
                cbRadarSimulation.setChecked(settings.getBoolean("radar_simulation"));
            if (settings.has("auto_query_mira")) 
                cbAutoQueryMira.setChecked(settings.getBoolean("auto_query_mira"));
            
            // 🟢 إعدادات المسح من السحابة
            if (settings.has("scan_mode")) setSelectedScanMode(settings.getString("scan_mode"));
            if (settings.has("auto_scan") && cbAutoScan != null) 
                cbAutoScan.setChecked(settings.getBoolean("auto_scan"));
            if (settings.has("auto_open_camera") && cbAutoOpenCamera != null) 
                cbAutoOpenCamera.setChecked(settings.getBoolean("auto_open_camera"));
            if (settings.has("show_scan_frame") && cbShowScanFrame != null) 
                cbShowScanFrame.setChecked(settings.getBoolean("show_scan_frame"));
            if (settings.has("parse_gs1") && cbParseGS1 != null) 
                cbParseGS1.setChecked(settings.getBoolean("parse_gs1"));
            if (settings.has("vibrate_on_scan") && cbVibrateOnScan != null) 
                cbVibrateOnScan.setChecked(settings.getBoolean("vibrate_on_scan"));
            
            // إشعار مدير الإعدادات
            settingsManager.saveSetting("sound_on_scan", cbSoundOnScan.isChecked());
            settingsManager.saveSetting("show_mira_card", cbShowMiraCard.isChecked());
            settingsManager.saveSetting("radar_simulation", cbRadarSimulation.isChecked());
            settingsManager.saveSetting("auto_query_mira", cbAutoQueryMira.isChecked());
            settingsManager.saveSetting("scan_mode", getSelectedScanMode());
            
            Toast.makeText(mContext, "✅ تم تحميل الإعدادات من MIRA Cloud", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Apply settings error: " + e.getMessage());
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

    public class SetFreOnclickListener implements OnClickListener {
        @Override
        public void onClick(View v) {
            String strMode = spFrequency.getSelectedItem().toString();
            int mode = getMode(strMode);
            Log.d(TAG, "setFrequencyMode mode=" + mode);
            if (mContext.mReader.setFrequencyMode((byte) mode)) {
                mContext.showToast(R.string.uhf_msg_set_frequency_succ);
            } else {
                mContext.showToast(R.string.uhf_msg_set_frequency_fail);
            }
        }
    }

    public void getFre(boolean showToast) {
        int mode = mContext.mReader.getFrequencyMode();
        Log.e(TAG, "getFrequencyMode()=" + mode);
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
        int link = mContext.mReader.getRFLink();
        Log.e(TAG, "getLinkParams()=" + link);
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

    public class GetFreOnclickListener implements OnClickListener {
        @Override
        public void onClick(View v) {
            getFre(true);
        }
    }

    public class OnMyCheckedChangedListener implements CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
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
        int iPower = mContext.mReader.getPower();
        Log.i("UHFSetFragment", "OnClick_GetPower() iPower=" + iPower);
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
        int iPower = spPower.getSelectedItemPosition() + 1;
        Log.i("UHFSetFragment", "OnClick_SetPower() iPower=" + iPower);
        if (mContext.mReader.setPower(iPower)) {
            mContext.showToast(R.string.uhf_msg_set_power_succ);
        } else {
            mContext.showToast(R.string.uhf_msg_set_power_fail);
        }
    }

    private boolean setFreHop(float value) {
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

    private void showFrequencyDialog() {
        if (dialog == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            View view = getActivity().getLayoutInflater().inflate(R.layout.uhf_dialog_frequency, null);
            ListView listView = view.findViewById(R.id.listView_frequency);
            ImageView iv = view.findViewById(R.id.iv_dismissDialog);
            iv.setOnClickListener(v -> dialog.dismiss());

            String[] strArr = getResources().getStringArray(R.array.arrayFreHop);
            listView.setAdapter(new ArrayAdapter<String>(getActivity(), R.layout.item_text1, strArr));
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (view instanceof TextView) {
                        TextView tv = (TextView) view;
                        setFreHop(Float.valueOf(tv.getText().toString().trim()));
                        dialog.dismiss();
                    }
                }
            });

            builder.setView(view);
            dialog = builder.create();
            dialog.show();
            dialog.setCanceledOnTouchOutside(false);

            WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = getWindowWidth() - 100;
            params.height = getWindowHeight() - 200;
            dialog.getWindow().setAttributes(params);
        } else {
            dialog.show();
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
        FastInventoryEntity entity = new FastInventoryEntity(spFastInventory.getSelectedItemPosition());
        boolean flag = mContext.mReader.setFastInventoryMode(entity);
        mContext.showToast(flag ? R.string.setting_succ : R.string.setting_fail);
    }

    @OnClick(R.id.btnFactoryReset)
    public void btnFactoryResetClick(View view) {
        new AlertDialog.Builder(mContext)
            .setTitle("⚠️ تأكيد إعادة الضبط")
            .setMessage("سيتم:\n• إعادة ضبط إعدادات القارئ\n• مسح إعدادات MIRA المحلية\n• استعادة الإعدادات الافتراضية\n\nهل أنت متأكد؟")
            .setPositiveButton("نعم، إعادة الضبط", (dialog, which) -> {
                boolean rfidReset = mContext.mReader.factoryReset();
                
                SharedPreferences prefs = mContext.getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
                prefs.edit().clear().apply();
                
                if (rfidReset) {
                    mContext.showToast("✅ تمت إعادة الضبط بنجاح");
                    
                    new Thread(() -> {
                        getFre(false);
                        getLinkParams(false);
                        getPower(false);
                        getMomoryBank(false);
                        getSession();
                        getFastInventory(false);
                    }).start();
                    
                    etMiraApiKey.setText("mira_gate_test071234567890abcdefghijklmnop");
                    etMiraGateId.setText("handheld_c72");
                    cbSoundOnScan.setChecked(true);
                    cbShowMiraCard.setChecked(true);
                    cbRadarSimulation.setChecked(true);
                    cbAutoQueryMira.setChecked(true);
                    if (spWorkingMode != null) spWorkingMode.setSelection(0);
                    if (rbRfidMode != null) rbRfidMode.setChecked(true);
                    if (cbAutoScan != null) cbAutoScan.setChecked(false);
                    if (cbAutoOpenCamera != null) cbAutoOpenCamera.setChecked(true);
                    if (cbShowScanFrame != null) cbShowScanFrame.setChecked(true);
                    if (cbParseGS1 != null) cbParseGS1.setChecked(true);
                    if (cbVibrateOnScan != null) cbVibrateOnScan.setChecked(true);
                    
                } else {
                    mContext.showToast("❌ فشلت إعادة ضبط القارئ");
                }
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }
                    }
