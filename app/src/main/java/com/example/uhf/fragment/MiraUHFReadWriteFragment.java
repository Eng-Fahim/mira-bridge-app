package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.MiraRfid.MiraRfidService;
import com.example.uhf.manager.MiraSettingsManager;
import com.rscja.deviceapi.RFIDWithUHFUART;

import org.json.JSONObject;

import java.util.Random;

/**
 * MIRA RFID Programmer Module
 * 
 * يدعم:
 * - وضع بسيط للقراءة/الكتابة
 * - وضع متقدم للتحكم الكامل
 * - البحث في MIRA API
 * - برمجة كاملة مع تسجيل الربط
 * - محاكاة كاملة عند عدم وجود قارئ
 */
public class MiraUHFReadWriteFragment extends KeyDwonFragment implements View.OnClickListener {
    private static final String TAG = "MiraUHFReadWriteFragment";
    private UHFMainActivity mContext;
    private MiraRfidService rfidService;

    Spinner SpinnerBank;
    EditText EtPtr, EtLen, EtAccessPwd, EtData;
    Button BtRead, BtWrite, BtReadTid, BtLock, BtGenerateEpc, BtProgramTag;
    CheckBox cbVerifyAfterWrite, cbAdvancedMode;

    CheckBox cb_filter;
    EditText etPtr_filter, etData_filter, etLen_filter;
    RadioButton rbEPC_filter, rbTID_filter, rbUser_filter;

    private EditText etProgrammerSearch;
    private Button btnProgrammerSearch;
    private LinearLayout layoutProgrammerItem, layoutAdvancedSettings;
    private TextView tvProgrammerStatus, tvProgrammerItemTitle, tvProgrammerItemDetails, tvCurrentTid;

    private String currentMiraId = "";
    private String currentGtin = "";
    private String currentSerial = "";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_uhf_write, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        mContext.currentFragment = this;
        
        // ✅ استخدام الخدمة الموحدة
        rfidService = MiraRfidService.getInstance(mContext);

        initViews();
        setupListeners();
        setupTextWatchers();
        toggleAdvancedMode(false);
        
        // عرض حالة الجهاز
        displayDeviceStatus();
    }

    private void displayDeviceStatus() {
        String deviceName = rfidService.getDeviceName();
        String mode = rfidService.isSimulationMode() ? " (وضع المحاكاة)" : " (جهاز حقيقي)";
        
        tvProgrammerStatus.setText("📡 " + deviceName + mode);
        tvProgrammerStatus.setTextColor(
            rfidService.isSimulationMode() ? Color.parseColor("#FF9800") : Color.parseColor("#4CAF50")
        );
        
        if (rfidService.isSimulationMode()) {
            Toast.makeText(mContext, "وضع المحاكاة مفعل - الشرائح افتراضية", Toast.LENGTH_LONG).show();
        }
    }

    private void initViews() {
        etProgrammerSearch = getView().findViewById(R.id.etProgrammerSearch);
        btnProgrammerSearch = getView().findViewById(R.id.btnProgrammerSearch);
        layoutProgrammerItem = getView().findViewById(R.id.layoutProgrammerItem);
        tvProgrammerStatus = getView().findViewById(R.id.tvProgrammerStatus);
        tvProgrammerItemTitle = getView().findViewById(R.id.tvProgrammerItemTitle);
        tvProgrammerItemDetails = getView().findViewById(R.id.tvProgrammerItemDetails);
        tvCurrentTid = getView().findViewById(R.id.tvCurrentTid);

        SpinnerBank = getView().findViewById(R.id.SpinnerBank);
        EtPtr = getView().findViewById(R.id.EtPtr);
        EtLen = getView().findViewById(R.id.EtLen);
        EtAccessPwd = getView().findViewById(R.id.EtAccessPwd);
        EtData = getView().findViewById(R.id.EtData);
        etLen_filter = getView().findViewById(R.id.etLen_filter);

        cb_filter = getView().findViewById(R.id.cb_filter);
        etPtr_filter = getView().findViewById(R.id.etPtr_filter);
        etData_filter = getView().findViewById(R.id.etData_filter);
        rbEPC_filter = getView().findViewById(R.id.rbEPC_filter);
        rbTID_filter = getView().findViewById(R.id.rbTID_filter);
        rbUser_filter = getView().findViewById(R.id.rbUser_filter);

        BtRead = getView().findViewById(R.id.BtRead);
        BtWrite = getView().findViewById(R.id.BtWrite);
        BtReadTid = getView().findViewById(R.id.BtReadTid);
        BtLock = getView().findViewById(R.id.BtLock);
        BtGenerateEpc = getView().findViewById(R.id.BtGenerateEpc);
        BtProgramTag = getView().findViewById(R.id.BtProgramTag);
        cbVerifyAfterWrite = getView().findViewById(R.id.cbVerifyAfterWrite);
        cbAdvancedMode = getView().findViewById(R.id.cbAdvancedMode);
        layoutAdvancedSettings = getView().findViewById(R.id.layoutAdvancedSettings);

        if (EtAccessPwd != null) EtAccessPwd.setText("00000000");
        if (EtPtr != null) EtPtr.setText("32");
        if (EtLen != null) EtLen.setText("6");
    }

    private void setupListeners() {
        BtRead.setOnClickListener(v -> read());
        BtWrite.setOnClickListener(v -> {
            if (cbVerifyAfterWrite != null && cbVerifyAfterWrite.isChecked()) {
                writeAndVerify();
            } else {
                write();
            }
        });

        if (BtReadTid != null) BtReadTid.setOnClickListener(v -> readTid());
        if (BtLock != null) BtLock.setOnClickListener(v -> lockTag());
        if (BtGenerateEpc != null) BtGenerateEpc.setOnClickListener(v -> generateEpcFromMira());
        if (BtProgramTag != null) BtProgramTag.setOnClickListener(v -> programTagFromMira());
        
        if (cbAdvancedMode != null) {
            cbAdvancedMode.setOnCheckedChangeListener((btn, isChecked) -> toggleAdvancedMode(isChecked));
        }

        rbEPC_filter.setOnClickListener(this);
        rbTID_filter.setOnClickListener(this);
        rbUser_filter.setOnClickListener(this);

        btnProgrammerSearch.setOnClickListener(v -> {
            String code = etProgrammerSearch.getText().toString().trim();
            if (TextUtils.isEmpty(code)) {
                Toast.makeText(mContext, "أدخل Serial أو GTIN-13 أو EPC", Toast.LENGTH_SHORT).show();
                return;
            }
            searchMiraItem(code);
        });

        cb_filter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                String data = etData_filter.getText().toString().trim();
                String rex = "[\\da-fA-F]*";
                if (data.isEmpty() || !data.matches(rex)) {
                    mContext.showToast(getString(R.string.uhf_msg_filter_data_must_hex));
                    cb_filter.setChecked(false);
                }
            }
        });
    }

    private void setupTextWatchers() {
        EtData.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String data = s.toString().trim();
                if (!data.isEmpty()) {
                    EtLen.setText(String.valueOf(data.length() / 4));
                }
            }
        });
    }

    private void toggleAdvancedMode(boolean isAdvanced) {
        if (layoutAdvancedSettings != null) {
            layoutAdvancedSettings.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        }
        if (BtReadTid != null) BtReadTid.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        if (BtLock != null) BtLock.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        if (BtGenerateEpc != null) BtGenerateEpc.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
    }

    // ============ البحث في MIRA ============
    private void searchMiraItem(String code) {
        tvProgrammerStatus.setText("🔍 جاري البحث...");
        tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
        layoutProgrammerItem.setVisibility(View.GONE);

        new Thread(() -> {
            String identifierType = detectIdentifierType(code);
            JSONObject json = rfidService.searchInMira(code, identifierType);

            getActivity().runOnUiThread(() -> {
                if (json != null) {
                    JSONObject item = json.optJSONObject("item");
                    JSONObject barcode = json.optJSONObject("barcode");
                    JSONObject stock = json.optJSONObject("stock");
                    JSONObject location = json.optJSONObject("location");

                    if (item != null || barcode != null) {
                        layoutProgrammerItem.setVisibility(View.VISIBLE);
                        
                        currentMiraId = item != null ? item.optString("mira_id", "") : "";
                        currentGtin = barcode != null ? barcode.optString("gtin", "") : "";
                        currentSerial = barcode != null ? barcode.optString("serial_no", "") : "";

                        StringBuilder details = new StringBuilder();
                        if (item != null) {
                            tvProgrammerItemTitle.setText("📦 " + item.optString("title", "غير معروف"));
                            details.append("🏷️ ").append(item.optString("serial", code));
                            if (!item.optString("karat", "").isEmpty()) details.append(" | 💎 ").append(item.optString("karat"));
                            if (!item.optString("weight", "").isEmpty()) details.append(" | ⚖️ ").append(item.optString("weight")).append("g");
                        }
                        if (stock != null) {
                            if (!stock.optString("branch_name", "").isEmpty()) details.append("\n🏪 ").append(stock.optString("branch_name"));
                        }
                        if (location != null) {
                            if (!location.optString("zone_name", "").isEmpty()) details.append(" | 📍 ").append(location.optString("zone_name"));
                        }
                        tvProgrammerItemDetails.setText(details.toString());

                        tvProgrammerStatus.setText("✅ تم العثور على القطعة");
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText("❌ القطعة غير موجودة في MIRA");
                        tvProgrammerStatus.setTextColor(Color.RED);
                        mContext.playSound(2);
                    }
                } else {
                    tvProgrammerStatus.setText("❌ خطأ في الاتصال");
                    tvProgrammerStatus.setTextColor(Color.RED);
                }
            });
        }).start();
    }

    private String detectIdentifierType(String code) {
        if (code.matches("[0-9]{13}")) return "gtin";
        if (code.startsWith("MIRA-")) return "mira_id";
        if (code.matches("[0-9]{14}")) return "serial";
        return "epc";
    }

    // ============ توليد EPC ============
    private void generateEpcFromMira() {
        if (currentMiraId.isEmpty() && currentGtin.isEmpty()) {
            generateRandomEpc();
            return;
        }

        StringBuilder epc = new StringBuilder();
        if (!currentGtin.isEmpty()) {
            epc.append("30");
            String gtin = currentGtin;
            epc.append(gtin.substring(1));
            if (!currentMiraId.isEmpty()) {
                String serialPart = currentMiraId.replaceAll("[^0-9]", "");
                while (serialPart.length() < 8) serialPart = "0" + serialPart;
                epc.append(serialPart.substring(serialPart.length() - 8));
            }
        } else {
            String serial = currentSerial.isEmpty() ? currentMiraId : currentSerial;
            epc.append("30");
            String hexSerial = serial.replaceAll("[^0-9A-Fa-f]", "");
            while (hexSerial.length() < 20) hexSerial += "0";
            epc.append(hexSerial.substring(0, 20));
        }

        String finalEpc = epc.toString().substring(0, Math.min(epc.length(), 24));
        EtData.setText(finalEpc);
        EtLen.setText(String.valueOf(finalEpc.length() / 4));
        tvProgrammerStatus.setText("🎯 تم توليد EPC من بيانات MIRA");
        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
    }

    private void generateRandomEpc() {
        StringBuilder epc = new StringBuilder();
        String hexChars = "0123456789ABCDEF";
        Random random = new Random();
        epc.append("3008");
        for (int i = 0; i < 20; i++) {
            epc.append(hexChars.charAt(random.nextInt(16)));
        }
        EtData.setText(epc.toString());
        EtLen.setText("6");
        tvProgrammerStatus.setText("🎲 تم توليد EPC عشوائي");
    }

    // ============ البرمجة الكاملة ============
    private void programTagFromMira() {
        if (currentMiraId.isEmpty() && currentGtin.isEmpty()) {
            Toast.makeText(mContext, "ابحث عن القطعة في MIRA أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        generateEpcFromMira();
        
        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (TextUtils.isEmpty(pwdStr)) pwdStr = "00000000";
        
        // قراءة TID
        String tidData = rfidService.readTid(pwdStr);
        
        // كتابة EPC
        String newEpc = EtData.getText().toString().trim();
        boolean writeSuccess = rfidService.writeEpc(newEpc, pwdStr);
        
        // تحقق
        boolean verifySuccess = rfidService.verifyWrite(newEpc, pwdStr);
        
        // تسجيل الربط
        final String miraId = currentMiraId.isEmpty() ? currentGtin : currentMiraId;
        final String finalNewEpc = newEpc;
        final String finalTidData = tidData;
        final boolean finalWriteSuccess = writeSuccess;
        final boolean finalVerifySuccess = verifySuccess;
        
        new Thread(() -> {
            boolean registered = rfidService.registerRfidBinding(miraId, finalNewEpc, finalTidData);
            
            getActivity().runOnUiThread(() -> {
                if (finalWriteSuccess && finalVerifySuccess && registered) {
                    tvProgrammerStatus.setText("✅ تمت البرمجة والتسجيل بنجاح\n" +
                        "MIRA ID: " + miraId + "\n" +
                        "EPC: " + finalNewEpc + "\n" +
                        "TID: " + (finalTidData != null ? finalTidData : "غير متوفر") + "\n" +
                        (rfidService.isSimulationMode() ? "🔬 (وضع المحاكاة)" : ""));
                    tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                    mContext.playSound(1);
                } else {
                    tvProgrammerStatus.setText("⚠️ فشلت العملية\n" +
                        "كتابة: " + (finalWriteSuccess ? "✅" : "❌") + "\n" +
                        "تحقق: " + (finalVerifySuccess ? "✅" : "❌") + "\n" +
                        "تسجيل: " + (registered ? "✅" : "❌"));
                    tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
                    mContext.playSound(2);
                }
            });
        }).start();
    }

    // ============ قراءة ============
    private void read() {
        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (TextUtils.isEmpty(pwdStr)) pwdStr = "00000000";
        
        String epc = rfidService.readEpc(pwdStr);
        
        if (epc != null && !epc.isEmpty()) {
            EtData.setText(epc);
            EtLen.setText(String.valueOf(epc.length() / 4));
            tvProgrammerStatus.setText("✅ قراءة ناجحة" + (rfidService.isSimulationMode() ? " (محاكاة)" : ""));
            tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
            mContext.playSound(1);
        } else {
            tvProgrammerStatus.setText("❌ فشل القراءة");
            tvProgrammerStatus.setTextColor(Color.RED);
            mContext.playSound(2);
        }
    }

    // ============ كتابة ============
    private void write() {
        String newEpc = EtData.getText().toString().trim();
        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (TextUtils.isEmpty(pwdStr)) pwdStr = "00000000";
        
        boolean success = rfidService.writeEpc(newEpc, pwdStr);
        
        if (success) {
            tvProgrammerStatus.setText("✅ كتابة ناجحة" + (rfidService.isSimulationMode() ? " (محاكاة)" : ""));
            tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
            mContext.playSound(1);
        } else {
            tvProgrammerStatus.setText("❌ فشل الكتابة");
            tvProgrammerStatus.setTextColor(Color.RED);
            mContext.playSound(2);
        }
    }

    // ============ كتابة مع تحقق ============
    private void writeAndVerify() {
        String newEpc = EtData.getText().toString().trim();
        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (TextUtils.isEmpty(pwdStr)) pwdStr = "00000000";
        
        final String finalNewEpc = newEpc;
        final String finalPwdStr = pwdStr;
        
        boolean writeSuccess = rfidService.writeEpc(finalNewEpc, finalPwdStr);
        
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean verifySuccess = rfidService.verifyWrite(finalNewEpc, finalPwdStr);
            
            if (writeSuccess && verifySuccess) {
                tvProgrammerStatus.setText("✅ كتابة وتحقق ناجح" + (rfidService.isSimulationMode() ? " (محاكاة)" : ""));
                tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                mContext.playSound(1);
            } else {
                tvProgrammerStatus.setText("⚠️ فشل التحقق\nكتابة: " + (writeSuccess ? "✅" : "❌") + 
                    " | تحقق: " + (verifySuccess ? "✅" : "❌"));
                tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
                mContext.playSound(2);
            }
        }, 500);
    }

    // ============ قراءة TID ============
    private void readTid() {
        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (TextUtils.isEmpty(pwdStr)) pwdStr = "00000000";
        
        String tid = rfidService.readTid(pwdStr);
        
        if (tid != null && !tid.isEmpty()) {
            tvCurrentTid.setText("TID: " + tid);
            tvCurrentTid.setVisibility(View.VISIBLE);
            tvProgrammerStatus.setText("✅ TID: " + tid);
            tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
            mContext.playSound(1);
        } else {
            tvProgrammerStatus.setText("❌ فشل قراءة TID");
            tvProgrammerStatus.setTextColor(Color.RED);
            mContext.playSound(2);
        }
    }

    private void lockTag() {
        tvProgrammerStatus.setText("⚠️ وظيفة القفل غير متاحة في هذا الإصدار");
        tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
        mContext.playSound(2);
    }

    private int getFilterBank() {
        if (rbTID_filter.isChecked()) return RFIDWithUHFUART.Bank_TID;
        if (rbUser_filter.isChecked()) return RFIDWithUHFUART.Bank_USER;
        return RFIDWithUHFUART.Bank_EPC;
    }

    public void myOnKeyDwon() {
        read();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.rbEPC_filter: etPtr_filter.setText("32"); break;
            case R.id.rbTID_filter: etPtr_filter.setText("0"); break;
            case R.id.rbUser_filter: etPtr_filter.setText("0"); break;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}
