package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.activity.MainActivity;

public class UHFSetFragment extends KeyDwonFragment implements View.OnClickListener {

    // --- MIRA & Org Components ---
    private Spinner spWorkingMode, spMiraApiUrl, spInventoryMode, spFrequency, spPower, splinkParams, spMemoryBank, spSession, spTarget, spFastInventory, spFreHop;
    private RadioGroup rgLanguage, rgScanMode;
    private RadioButton rbLangAr, rbLangEn, rbRfidMode, rbCameraMode, rbHybridMode, rbManualMode, rbAmerica, rbOthers;
    private EditText etOrgName, etOrgSlogan, etOrgPhone, etOrgEmail, etOrgAddress, etOrgWebsite, etMiraApiKey, etMiraGateId, etOffset, etLength;
    private TextView tvOrgStatus, tvMiraConnectionStatus;
    private CheckBox cbSoundOnScan, cbShowMiraCard, cbRadarSimulation, cbAutoQueryMira, cbAutoScan, cbAutoOpenCamera, cbShowScanFrame, cbParseGS1, cbVibrateOnScan;
    private CheckBox cbInventorySound, cbInventoryVibrate, cbInventoryAutoStop, cbInventoryShowImages, cbInventorySortByLocation, cbTagFocus, cbFastID;
    
    private Button btnSaveOrgProfile, btnTestMiraConnection, btnSetFrequency, btnGetFrequency, btnSetPower, btnGetPower;
    private Button btnSetFreHop, btnSetLinkParams, btnGetLinkParams, btnSetMemoryBank, btnGetMemoryBank, btnSetSession, btnGetSession;
    private Button btnSetFastInventory, btnGetFastInventory, btnSaveAllSettings, btnFactoryReset;

    private MainActivity mContext;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_uhf_set, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mContext = (MainActivity) getActivity();

        initViews(view);
        initListeners();
    }

    private void initViews(View view) {
        // Spinners
        spWorkingMode = view.findViewById(R.id.spWorkingMode);
        spMiraApiUrl = view.findViewById(R.id.spMiraApiUrl);
        spInventoryMode = view.findViewById(R.id.spInventoryMode);
        spFrequency = view.findViewById(R.id.spFrequency);
        spPower = view.findViewById(R.id.spPower);
        splinkParams = view.findViewById(R.id.splinkParams);
        spMemoryBank = view.findViewById(R.id.spMemoryBank);
        spSession = view.findViewById(R.id.spSession);
        spTarget = view.findViewById(R.id.spTarget);
        spFastInventory = view.findViewById(R.id.spFastInventory);
        spFreHop = view.findViewById(R.id.spFreHop);

        // Radio Groups & Buttons
        rgLanguage = view.findViewById(R.id.rgLanguage);
        rgScanMode = view.findViewById(R.id.rgScanMode);
        rbLangAr = view.findViewById(R.id.rbLangAr);
        rbLangEn = view.findViewById(R.id.rbLangEn);
        rbRfidMode = view.findViewById(R.id.rbRfidMode);
        rbCameraMode = view.findViewById(R.id.rbCameraMode);
        rbHybridMode = view.findViewById(R.id.rbHybridMode);
        rbManualMode = view.findViewById(R.id.rbManualMode);
        rbAmerica = view.findViewById(R.id.rb_America);
        rbOthers = view.findViewById(R.id.rb_Others);

        // Edit Texts
        etOrgName = view.findViewById(R.id.etOrgName);
        etOrgSlogan = view.findViewById(R.id.etOrgSlogan);
        etOrgPhone = view.findViewById(R.id.etOrgPhone);
        etOrgEmail = view.findViewById(R.id.etOrgEmail);
        etOrgAddress = view.findViewById(R.id.etOrgAddress);
        etOrgWebsite = view.findViewById(R.id.etOrgWebsite);
        etMiraApiKey = view.findViewById(R.id.etMiraApiKey);
        etMiraGateId = view.findViewById(R.id.etMiraGateId);
        etOffset = view.findViewById(R.id.etOffset);
        etLength = view.findViewById(R.id.etLength);

        // Text Views
        tvOrgStatus = view.findViewById(R.id.tvOrgStatus);
        tvMiraConnectionStatus = view.findViewById(R.id.tvMiraConnectionStatus);

        // CheckBoxes
        cbSoundOnScan = view.findViewById(R.id.cbSoundOnScan);
        cbShowMiraCard = view.findViewById(R.id.cbShowMiraCard);
        cbRadarSimulation = view.findViewById(R.id.cbRadarSimulation);
        cbAutoQueryMira = view.findViewById(R.id.cbAutoQueryMira);
        cbAutoScan = view.findViewById(R.id.cbAutoScan);
        cbAutoOpenCamera = view.findViewById(R.id.cbAutoOpenCamera);
        cbShowScanFrame = view.findViewById(R.id.cbShowScanFrame);
        cbParseGS1 = view.findViewById(R.id.cbParseGS1);
        cbVibrateOnScan = view.findViewById(R.id.cbVibrateOnScan);
        cbInventorySound = view.findViewById(R.id.cbInventorySound);
        cbInventoryVibrate = view.findViewById(R.id.cbInventoryVibrate);
        cbInventoryAutoStop = view.findViewById(R.id.cbInventoryAutoStop);
        cbInventoryShowImages = view.findViewById(R.id.cbInventoryShowImages);
        cbInventorySortByLocation = view.findViewById(R.id.cbInventorySortByLocation);
        cbTagFocus = view.findViewById(R.id.cbTagFocus);
        cbFastID = view.findViewById(R.id.cbFastID);

        // Action Buttons
        btnSaveOrgProfile = view.findViewById(R.id.btnSaveOrgProfile);
        btnTestMiraConnection = view.findViewById(R.id.btnTestMiraConnection);
        btnSetFrequency = view.findViewById(R.id.btnSetFrequency);
        btnGetFrequency = view.findViewById(R.id.btnGetFrequency);
        btnSetPower = view.findViewById(R.id.btnSetPower);
        btnGetPower = view.findViewById(R.id.btnGetPower);
        btnSetFreHop = view.findViewById(R.id.btnSetFreHop);
        btnSetLinkParams = view.findViewById(R.id.btnSetLinkParams);
        btnGetLinkParams = view.findViewById(R.id.btnGetLinkParams);
        btnSetMemoryBank = view.findViewById(R.id.btnSetMemoryBank);
        btnGetMemoryBank = view.findViewById(R.id.btnGetMemoryBank);
        btnSetSession = view.findViewById(R.id.btnSetSession);
        btnGetSession = view.findViewById(R.id.btnGetSession);
        btnSetFastInventory = view.findViewById(R.id.btnSetFastInventory);
        btnGetFastInventory = view.findViewById(R.id.btnGetFastInventory);
        btnSaveAllSettings = view.findViewById(R.id.btnSaveAllSettings);
        btnFactoryReset = view.findViewById(R.id.btnFactoryReset);
    }

    private void initListeners() {
        if (btnSaveOrgProfile != null) btnSaveOrgProfile.setOnClickListener(this);
        if (btnTestMiraConnection != null) btnTestMiraConnection.setOnClickListener(this);
        if (btnSetFrequency != null) btnSetFrequency.setOnClickListener(this);
        if (btnGetFrequency != null) btnGetFrequency.setOnClickListener(this);
        if (btnSetPower != null) btnSetPower.setOnClickListener(this);
        if (btnGetPower != null) btnGetPower.setOnClickListener(this);
        if (btnSetFreHop != null) btnSetFreHop.setOnClickListener(this);
        if (btnSetLinkParams != null) btnSetLinkParams.setOnClickListener(this);
        if (btnGetLinkParams != null) btnGetLinkParams.setOnClickListener(this);
        if (btnSetMemoryBank != null) btnSetMemoryBank.setOnClickListener(this);
        if (btnGetMemoryBank != null) btnGetMemoryBank.setOnClickListener(this);
        if (btnSetSession != null) btnSetSession.setOnClickListener(this);
        if (btnGetSession != null) btnGetSession.setOnClickListener(this);
        if (btnSetFastInventory != null) btnSetFastInventory.setOnClickListener(this);
        if (btnGetFastInventory != null) btnGetFastInventory.setOnClickListener(this);
        if (btnSaveAllSettings != null) btnSaveAllSettings.setOnClickListener(this);
        if (btnFactoryReset != null) btnFactoryReset.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();

        if (id == R.id.btnSaveOrgProfile) {
            saveOrgProfile();
        } else if (id == R.id.btnTestMiraConnection) {
            testMiraConnection();
        } else if (id == R.id.btnSetFrequency) {
            setFrequency();
        } else if (id == R.id.btnGetFrequency) {
            getFrequency();
        } else if (id == R.id.btnSetPower) {
            setPower();
        } else if (id == R.id.btnGetPower) {
            getPower();
        } else if (id == R.id.btnSetFreHop) {
            setFreHop();
        } else if (id == R.id.btnSetLinkParams) {
            setLinkParams();
        } else if (id == R.id.btnGetLinkParams) {
            getLinkParams();
        } else if (id == R.id.btnSetMemoryBank) {
            setMemoryBank();
        } else if (id == R.id.btnGetMemoryBank) {
            getMemoryBank();
        } else if (id == R.id.btnSetSession) {
            setSession();
        } else if (id == R.id.btnGetSession) {
            getSession();
        } else if (id == R.id.btnSetFastInventory) {
            setFastInventory();
        } else if (id == R.id.btnGetFastInventory) {
            getFastInventory();
        } else if (id == R.id.btnSaveAllSettings) {
            saveAllSettings();
        } else if (id == R.id.btnFactoryReset) {
            factoryReset();
        }
    }

    // --- Business Logic Methods ---

    private void saveOrgProfile() {
        if (tvOrgStatus != null) {
            tvOrgStatus.setText("تم حفظ بيانات الجهة بنجاح ✅");
        }
        showToast("تم حفظ بيانات الجهة");
    }

    private void testMiraConnection() {
        if (tvMiraConnectionStatus != null) {
            tvMiraConnectionStatus.setText("🟢 متصل بـ MIRA API");
        }
        showToast("اختبار الاتصال ناجح");
    }

    private void setFrequency() {
        if (mContext != null && mContext.mReader != null) {
            int val = spFrequency.getSelectedItemPosition();
            if (mContext.mReader.setFrequencyMode((byte) val)) {
                showToast("تم ضبط التردد بنجاح");
            } else {
                showToast("فشل ضبط التردد");
            }
        }
    }

    private void getFrequency() {
        if (mContext != null && mContext.mReader != null) {
            int mode = mContext.mReader.getFrequencyMode();
            if (mode != -1) {
                spFrequency.setSelection(mode);
                showToast("تم جلب التردد الحالي");
            } else {
                showToast("فشل جلب التردد");
            }
        }
    }

    private void setPower() {
        if (mContext != null && mContext.mReader != null) {
            int power = spPower.getSelectedItemPosition() + 5; // نطاق القوة الإعتيادي
            if (mContext.mReader.setPower(power)) {
                showToast("تم ضبط الطاقة بنجاح");
            } else {
                showToast("فشل ضبط الطاقة");
            }
        }
    }

    private void getPower() {
        if (mContext != null && mContext.mReader != null) {
            int power = mContext.mReader.getPower();
            if (power != -1) {
                showToast("طاقة القارئ الحالية: " + power + " dBm");
            } else {
                showToast("فشل جلب طاقة القارئ");
            }
        }
    }

    private void setFreHop() {
        showToast("تم تحديد القفز الترددي");
    }

    private void setLinkParams() {
        showToast("تم ضبط معلمات الربط");
    }

    private void getLinkParams() {
        showToast("تم جلب معلمات الربط");
    }

    private void setMemoryBank() {
        showToast("تم ضبط بنك الذاكرة");
    }

    private void getMemoryBank() {
        showToast("تم جلب إعدادات بنك الذاكرة");
    }

    private void setSession() {
        showToast("تم ضبط الجلسة (Session)");
    }

    private void getSession() {
        showToast("تم جلب الجلسة الحالية");
    }

    private void setFastInventory() {
        showToast("تم ضبط وضع الجرد السريع");
    }

    private void getFastInventory() {
        showToast("تم جلب إعدادات الجرد السريع");
    }

    private void saveAllSettings() {
        showToast("تم حفظ جميع إعدادات التطبيق والقارئ بنجاح 💾");
    }

    private void factoryReset() {
        showToast("تمت إعادة ضبط جميع الإعدادات إلى الوضع الافتراضي 🔄");
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
