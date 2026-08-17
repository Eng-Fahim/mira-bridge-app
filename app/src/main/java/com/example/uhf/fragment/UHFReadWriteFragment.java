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
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraSettingsManager;
import com.example.uhf.tools.StringUtils;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.utility.StringUtility;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * MIRA Programmer Fragment
 * 
 * قراءة وكتابة بيانات تاقات RFID مع تكامل MIRA ID
 * - بحث عن القطعة في MIRA
 * - قراءة EPC و User Memory
 * - كتابة بيانات جديدة
 * - قراءة TID
 * - قفل الشريحة
 * - توليد EPC جديد
 */
public class UHFReadWriteFragment extends KeyDwonFragment implements OnClickListener {
    private static final String TAG = "UHFReadWriteFragment";
    private UHFMainActivity mContext;
    private MiraSettingsManager settingsManager;

    Spinner SpinnerBank;
    EditText EtPtr, EtLen, EtAccessPwd, EtData;
    Button BtRead, BtWrite, BtReadTid, BtLock, BtGenerateEpc;
    CheckBox cbVerifyAfterWrite;

    CheckBox cb_filter;
    EditText etPtr_filter, etData_filter, etLen_filter;
    RadioButton rbEPC_filter, rbTID_filter, rbUser_filter;

    // 🟢 عناصر MIRA Programmer
    private EditText etProgrammerSearch;
    private Button btnProgrammerSearch;
    private LinearLayout layoutProgrammerItem;
    private TextView tvProgrammerStatus, tvProgrammerItemTitle, tvProgrammerItemDetails;
    private TextView tvCurrentTid;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.uhf_read_write_fragment, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        mContext.currentFragment = this;
        settingsManager = MiraSettingsManager.getInstance(mContext);

        initViews();
        setupListeners();
        setupTextWatchers();
    }

    private void initViews() {
        // 🟢 عناصر MIRA
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
        cbVerifyAfterWrite = getView().findViewById(R.id.cbVerifyAfterWrite);

        // تعيين قيم افتراضية
        if (EtAccessPwd != null) {
            EtAccessPwd.setText("00000000");
        }
        if (EtPtr != null) {
            EtPtr.setText("32"); // EPC يبدأ عادة من العنوان 32
        }
        if (EtLen != null) {
            EtLen.setText("6"); // 24 hex = 6 words
        }
    }

    private void setupListeners() {
        // أزرار القراءة والكتابة
        BtRead.setOnClickListener(v -> read());
        BtWrite.setOnClickListener(v -> {
            if (cbVerifyAfterWrite != null && cbVerifyAfterWrite.isChecked()) {
                writeAndVerify();
            } else {
                write();
            }
        });

        // أزرار إضافية
        if (BtReadTid != null) {
            BtReadTid.setOnClickListener(v -> readTid());
        }
        if (BtLock != null) {
            BtLock.setOnClickListener(v -> lockTag());
        }
        if (BtGenerateEpc != null) {
            BtGenerateEpc.setOnClickListener(v -> generateNewEpc());
        }

        // أزرار الفلترة
        rbEPC_filter.setOnClickListener(this);
        rbTID_filter.setOnClickListener(this);
        rbUser_filter.setOnClickListener(this);

        // 🟢 بحث MIRA
        btnProgrammerSearch.setOnClickListener(v -> {
            String code = etProgrammerSearch.getText().toString().trim();
            if (TextUtils.isEmpty(code)) {
                Toast.makeText(mContext, "أدخل Serial أو GTIN-13 أو EPC", Toast.LENGTH_SHORT).show();
                return;
            }
            searchMiraItem(code);
        });

        // فلترة checkbox
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

        etData_filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String data = s.toString().trim();
                if (!data.isEmpty()) {
                    etLen_filter.setText(String.valueOf(data.length() * 4));
                }
            }
        });
    }

    // ============================================
    // 🟢 البحث في MIRA ID
    // ============================================
    private void searchMiraItem(String code) {
        tvProgrammerStatus.setText("🔍 جاري البحث...");
        tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
        layoutProgrammerItem.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                String apiUrl = settingsManager.getString("mira_api_url",
                    "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize");
                String apiKey = settingsManager.getString("mira_api_key",
                    "mira_gate_test071234567890abcdefghijklmnop");

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("epc", code);
                jsonParam.put("gate_id", "handheld_c72");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonParam.toString().getBytes("utf-8"));
                }

                int responseCode = conn.getResponseCode();
                InputStream is = (responseCode >= 200 && responseCode < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                JSONObject json = new JSONObject(response.toString());
                JSONObject item = json.optJSONObject("item");
                JSONObject barcode = json.optJSONObject("barcode");
                JSONObject stock = json.optJSONObject("stock");
                JSONObject location = json.optJSONObject("location");

                getActivity().runOnUiThread(() -> {
                    if (item != null || barcode != null) {
                        layoutProgrammerItem.setVisibility(View.VISIBLE);
                        
                        StringBuilder details = new StringBuilder();
                        
                        if (item != null) {
                            String title = item.optString("title", "غير معروف");
                            String karat = item.optString("karat", "");
                            String weight = item.optString("weight", "");
                            String category = item.optString("category", "");
                            String status = item.optString("status", "");
                            
                            tvProgrammerItemTitle.setText("📦 " + title);
                            
                            details.append("🏷️ ").append(item.optString("serial", code));
                            if (!karat.isEmpty()) details.append(" | 💎 ").append(karat);
                            if (!weight.isEmpty()) details.append(" | ⚖️ ").append(weight).append("g");
                            if (!category.isEmpty()) details.append(" | 📂 ").append(category);
                            if (!status.isEmpty()) details.append(" | 📊 ").append(status);
                        }
                        
                        if (stock != null) {
                            String status = stock.optString("status", "");
                            String branch = stock.optString("branch_name", "");
                            if (!status.isEmpty()) details.append("\n📊 ").append(status);
                            if (!branch.isEmpty()) details.append(" | 🏪 ").append(branch);
                        }
                        
                        if (location != null) {
                            String zone = location.optString("zone_name", "");
                            String shelf = location.optString("shelf_code", "");
                            if (!zone.isEmpty()) details.append("\n📍 ").append(zone);
                            if (!shelf.isEmpty()) details.append(" | 📦 ").append(shelf);
                        }
                        
                        tvProgrammerItemDetails.setText(details.toString());
                        
                        // تعبئة حقل الفلتر تلقائياً
                        if (barcode != null) {
                            String serialNo = barcode.optString("serial_no", "");
                            if (!serialNo.isEmpty()) {
                                etData_filter.setText(serialNo);
                                etLen_filter.setText(String.valueOf(serialNo.length() * 4));
                                cb_filter.setChecked(true);
                            }
                        }

                        tvProgrammerStatus.setText("✅ تم العثور على القطعة");
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText("❌ القطعة غير موجودة في MIRA");
                        tvProgrammerStatus.setTextColor(Color.RED);
                        mContext.playSound(2);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Search error: " + e.getMessage());
                getActivity().runOnUiThread(() -> {
                    tvProgrammerStatus.setText("❌ خطأ في الاتصال: " + e.getMessage());
                    tvProgrammerStatus.setTextColor(Color.RED);
                    mContext.playSound(2);
                });
            }
        }).start();
    }

    // ============================================
    // 🟢 توليد EPC جديد
    // ============================================
    private void generateNewEpc() {
        StringBuilder epc = new StringBuilder();
        String hexChars = "0123456789ABCDEF";
        Random random = new Random();
        
        // بداية ثابتة للشركة (مثال: 3008 = MIRA)
        epc.append("3008");
        
        // 20 خانة عشوائية
        for (int i = 0; i < 20; i++) {
            epc.append(hexChars.charAt(random.nextInt(16)));
        }
        
        EtData.setText(epc.toString());
        EtLen.setText("6"); // 24 hex = 6 words
        tvProgrammerStatus.setText("🎲 تم توليد EPC جديد");
        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
        mContext.showToast("تم توليد EPC جديد");
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.rbEPC_filter: 
                etPtr_filter.setText("32");
                break;
            case R.id.rbTID_filter: 
                etPtr_filter.setText("0");
                break;
            case R.id.rbUser_filter: 
                etPtr_filter.setText("0");
                break;
        }
    }

    // ============================================
    // 🟢 قراءة
    // ============================================
    private void read() {
        String ptrStr = EtPtr.getText().toString().trim();
        if (ptrStr.equals("")) {
            mContext.showToast(R.string.uhf_msg_addr_not_null);
            return;
        } else if (!TextUtils.isDigitsOnly(ptrStr)) {
            mContext.showToast(R.string.uhf_msg_addr_must_decimal);
            return;
        }

        String cntStr = EtLen.getText().toString().trim();
        if (cntStr.equals("")) {
            mContext.showToast(R.string.uhf_msg_len_not_null);
            return;
        } else if (!TextUtils.isDigitsOnly(cntStr)) {
            mContext.showToast(R.string.uhf_msg_len_must_decimal);
            return;
        }

        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (!TextUtils.isEmpty(pwdStr)) {
            if (pwdStr.length() != 8 || !mContext.vailHexInput(pwdStr)) {
                mContext.showToast(R.string.uhf_msg_addr_must_len8);
                return;
            }
        } else {
            pwdStr = "00000000";
        }

        boolean result = false;
        int Bank = SpinnerBank.getSelectedItemPosition();

        if (cb_filter.isChecked()) {
            if (etPtr_filter.getText().toString().isEmpty() || 
                etLen_filter.getText().toString().isEmpty() ||
                etData_filter.getText().toString().isEmpty()) {
                mContext.showToast("أكمل بيانات الفلتر");
                return;
            }

            int filterPtr = Integer.parseInt(etPtr_filter.getText().toString());
            String filterData = etData_filter.getText().toString();
            int filterCnt = Integer.parseInt(etLen_filter.getText().toString());
            int filterBank = getFilterBank();

            String data = mContext.mReader.readData(pwdStr, filterBank, filterPtr, filterCnt, filterData,
                    Bank, Integer.parseInt(ptrStr), Integer.parseInt(cntStr));
            if (data != null && data.length() > 0) {
                result = true;
                EtData.setText(data);
            }
        } else {
            String data = mContext.mReader.readData(pwdStr, Bank, Integer.parseInt(ptrStr), Integer.parseInt(cntStr));
            if (!TextUtils.isEmpty(data)) {
                result = true;
                EtData.setText(data);
            }
        }

        if (result) {
            tvProgrammerStatus.setText("✅ قراءة ناجحة");
            tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
            mContext.playSound(1);
        } else {
            tvProgrammerStatus.setText("❌ فشل القراءة - تأكد من وجود الشريحة");
            tvProgrammerStatus.setTextColor(Color.RED);
            mContext.playSound(2);
        }
    }

    // ============================================
    // 🟢 كتابة
    // ============================================
    private void write() {
        String strPtr = EtPtr.getText().toString().trim();
        if (StringUtils.isEmpty(strPtr) || !StringUtility.isDecimal(strPtr)) {
            mContext.showToast(R.string.uhf_msg_addr_must_decimal);
            return;
        }

        String strPWD = EtAccessPwd.getText().toString().trim();
        if (StringUtils.isNotEmpty(strPWD)) {
            if (strPWD.length() != 8 || !mContext.vailHexInput(strPWD)) {
                mContext.showToast(R.string.uhf_msg_addr_must_len8);
                return;
            }
        } else {
            strPWD = "00000000";
        }

        String strData = EtData.getText().toString().trim();
        if (StringUtils.isEmpty(strData) || !mContext.vailHexInput(strData)) {
            mContext.showToast(R.string.uhf_msg_write_must_not_null);
            return;
        }

        String cntStr = EtLen.getText().toString().trim();
        if (StringUtils.isEmpty(cntStr) || !StringUtility.isDecimal(cntStr)) {
            mContext.showToast(R.string.uhf_msg_len_must_decimal);
            return;
        }

        if (strData.length() % 4 != 0) {
            mContext.showToast(R.string.uhf_msg_write_must_len4x);
            return;
        }

        int writeLen = Integer.parseInt(cntStr);
        int writePtr = Integer.parseInt(strPtr);
        boolean result = false;
        int Bank = SpinnerBank.getSelectedItemPosition();

        if (cb_filter.isChecked()) {
            int filterPtr = Integer.parseInt(etPtr_filter.getText().toString());
            String filterData = etData_filter.getText().toString();
            int filterCnt = Integer.parseInt(etLen_filter.getText().toString());
            int filterBank = getFilterBank();

            result = mContext.mReader.writeData(strPWD, filterBank, filterPtr, filterCnt, filterData,
                    Bank, writePtr, writeLen, strData);
        } else {
            result = mContext.mReader.writeData(strPWD, Bank, writePtr, writeLen, strData);
        }

        if (result) {
            tvProgrammerStatus.setText("✅ كتابة ناجحة");
            tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
            mContext.playSound(1);
        } else {
            tvProgrammerStatus.setText("❌ فشل الكتابة");
            tvProgrammerStatus.setTextColor(Color.RED);
            mContext.playSound(2);
        }
    }

    // ============================================
    // 🟢 كتابة مع تحقق
    // ============================================
    private void writeAndVerify() {
        // حفظ البيانات المكتوبة قبل الكتابة
        String writtenData = EtData.getText().toString().trim();
        
        write();
        
        // انتظر قليلاً ثم اقرأ للتحقق
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            read();
            
            // مقارنة البيانات بعد القراءة
            String readBackData = EtData.getText().toString().trim();
            
            if (writtenData.equalsIgnoreCase(readBackData)) {
                tvProgrammerStatus.setText("✅ كتابة وتحقق ناجح");
                tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                mContext.playSound(1);
            } else {
                tvProgrammerStatus.setText("⚠️ الكتابة تمت لكن التحقق فشل\nالمكتوب: " + writtenData + "\nالمقروء: " + readBackData);
                tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
                mContext.playSound(2);
            }
        }, 800);
    }

    // ============================================
    // 🟢 قراءة TID
    // ============================================
    private void readTid() {
        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (TextUtils.isEmpty(pwdStr)) pwdStr = "00000000";
        
        // TID دائماً في Bank_TID والعنوان 0
        String tidData = mContext.mReader.readData(pwdStr, RFIDWithUHFUART.Bank_TID, 0, 6);
        
        if (!TextUtils.isEmpty(tidData)) {
            if (tvCurrentTid != null) {
                tvCurrentTid.setText("TID: " + tidData);
                tvCurrentTid.setVisibility(View.VISIBLE);
            }
            tvProgrammerStatus.setText("✅ TID: " + tidData);
            tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
            mContext.playSound(1);
        } else {
            tvProgrammerStatus.setText("❌ فشل قراءة TID");
            tvProgrammerStatus.setTextColor(Color.RED);
            mContext.playSound(2);
        }
    }

    // ============================================
    // 🟢 قفل الشريحة
    // ============================================
    private void lockTag() {
        String pwdStr = EtAccessPwd.getText().toString().trim();
        if (TextUtils.isEmpty(pwdStr)) pwdStr = "00000000";
        
        // التحقق من كلمة المرور
        if (pwdStr.length() != 8 || !mContext.vailHexInput(pwdStr)) {
            mContext.showToast(R.string.uhf_msg_addr_must_len8);
            return;
        }
        
        // عرض تحذير قبل القفل
        new android.app.AlertDialog.Builder(mContext)
            .setTitle("⚠️ تحذير")
            .setMessage("هل أنت متأكد من قفل الشريحة؟\nبعد القفل لن تتمكن من الكتابة عليها بدون كلمة المرور الصحيحة.")
            .setPositiveButton("نعم، قفل", (dialog, which) -> {
                // قفل EPC (منع الكتابة)
                boolean result = mContext.mReader.lockTag(pwdStr, 
                    RFIDWithUHFUART.LockType.LOCK_EPC, 
                    RFIDWithUHFUART.LockAction.LOCK_ACTION_LOCK);
                
                if (result) {
                    tvProgrammerStatus.setText("🔐 تم قفل الشريحة بنجاح");
                    tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                    mContext.playSound(1);
                } else {
                    tvProgrammerStatus.setText("❌ فشل القفل");
                    tvProgrammerStatus.setTextColor(Color.RED);
                    mContext.playSound(2);
                }
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }

    // ============================================
    // 🟢 الحصول على بنك الفلتر
    // ============================================
    private int getFilterBank() {
        if (rbTID_filter.isChecked()) return RFIDWithUHFUART.Bank_TID;
        if (rbUser_filter.isChecked()) return RFIDWithUHFUART.Bank_USER;
        return RFIDWithUHFUART.Bank_EPC;
    }

    // ============================================
    // 🟢 معالج زر المسح
    // ============================================
    public void myOnKeyDwon() {
        if (cb_filter.isChecked()) {
            // وضع الفلترة - قراءة محددة
            read();
        } else {
            // وضع المسح السريع - قراءة مباشرة
            read();
        }
    }

    // ============================================
    // 🟢 تنظيف الموارد
    // ============================================
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // إيقاف أي عمليات جارية
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.stopInventoryTag();
            } catch (Exception e) {
                Log.e(TAG, "Cleanup error: " + e.getMessage());
            }
        }
    }
}
