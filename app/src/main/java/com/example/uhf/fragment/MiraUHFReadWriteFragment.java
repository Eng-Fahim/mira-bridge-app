package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
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
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.MiraRfid.MiraRfidService;
import com.rscja.deviceapi.RFIDWithUHFUART;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class MiraUHFReadWriteFragment extends KeyDwonFragment implements View.OnClickListener {
    private static final String TAG = "MiraUHFReadWriteFragment";
    private UHFMainActivity mContext;
    private MiraRfidService rfidService;

    private Spinner SpinnerBank;
    private EditText EtPtr, EtLen, EtAccessPwd, EtData;
    private Button BtRead, BtWrite, BtReadTid, BtLock, BtUnlock, BtGenerateEpc, BtProgramTag;
    private CheckBox cbVerifyAfterWrite, cbAdvancedMode;

    private CheckBox cb_filter;
    private EditText etPtr_filter, etData_filter, etLen_filter;
    private RadioButton rbEPC_filter, rbTID_filter, rbUser_filter;

    private EditText etProgrammerSearch;
    private Button btnProgrammerSearch;
    private LinearLayout layoutProgrammerItem, layoutAdvancedSettings;
    private TextView tvProgrammerStatus, tvProgrammerItemTitle, tvProgrammerItemDetails, tvCurrentTid;
    private TextView tvDeviceStatus, tvOperationLog;
    private ProgressBar pbWriteProgress;

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
        rfidService = MiraRfidService.getInstance(mContext);

        initViews();
        setupListeners();
        setupTextWatchers();
        toggleAdvancedMode(false);
        displayDeviceStatus();
    }

    private void initViews() {
        View v = getView();
        if (v == null) return;

        etProgrammerSearch = v.findViewById(R.id.etProgrammerSearch);
        btnProgrammerSearch = v.findViewById(R.id.btnProgrammerSearch);
        layoutProgrammerItem = v.findViewById(R.id.layoutProgrammerItem);
        tvProgrammerStatus = v.findViewById(R.id.tvProgrammerStatus);
        tvProgrammerItemTitle = v.findViewById(R.id.tvProgrammerItemTitle);
        tvProgrammerItemDetails = v.findViewById(R.id.tvProgrammerItemDetails);
        tvCurrentTid = v.findViewById(R.id.tvCurrentTid);
        tvDeviceStatus = v.findViewById(R.id.tvDeviceStatus);
        tvOperationLog = v.findViewById(R.id.tvOperationLog);
        pbWriteProgress = v.findViewById(R.id.pbWriteProgress);

        SpinnerBank = v.findViewById(R.id.SpinnerBank);
        EtPtr = v.findViewById(R.id.EtPtr);
        EtLen = v.findViewById(R.id.EtLen);
        EtAccessPwd = v.findViewById(R.id.EtAccessPwd);
        EtData = v.findViewById(R.id.EtData);
        etLen_filter = v.findViewById(R.id.etLen_filter);

        cb_filter = v.findViewById(R.id.cb_filter);
        etPtr_filter = v.findViewById(R.id.etPtr_filter);
        etData_filter = v.findViewById(R.id.etData_filter);
        rbEPC_filter = v.findViewById(R.id.rbEPC_filter);
        rbTID_filter = v.findViewById(R.id.rbTID_filter);
        rbUser_filter = v.findViewById(R.id.rbUser_filter);

        BtRead = v.findViewById(R.id.BtRead);
        BtWrite = v.findViewById(R.id.BtWrite);
        BtReadTid = v.findViewById(R.id.BtReadTid);
        BtLock = v.findViewById(R.id.BtLock);
        
        // ✅ زر فتح القفل - إضافة ديناميكية
        int unlockResId = v.getResources().getIdentifier("BtUnlock", "id", v.getContext().getPackageName());
        BtUnlock = unlockResId != 0 ? v.findViewById(unlockResId) : null;

        BtGenerateEpc = v.findViewById(R.id.BtGenerateEpc);
        BtProgramTag = v.findViewById(R.id.BtProgramTag);
        cbVerifyAfterWrite = v.findViewById(R.id.cbVerifyAfterWrite);
        cbAdvancedMode = v.findViewById(R.id.cbAdvancedMode);
        layoutAdvancedSettings = v.findViewById(R.id.layoutAdvancedSettings);

        if (EtAccessPwd != null) EtAccessPwd.setText("00000000");
        if (EtPtr != null) EtPtr.setText("32");
        if (EtLen != null) EtLen.setText("6");

        // ✅ تهيئة سجل العمليات
        if (tvOperationLog != null) tvOperationLog.setText("—");
    }

    /**
     * ✅ عرض حالة الجهاز
     */
    private void displayDeviceStatus() {
        String deviceName = rfidService.getDeviceName();
        String mode = rfidService.isSimulationMode() ? " (وضع المحاكاة)" : " (جهاز حقيقي)";
        
        if (tvProgrammerStatus != null) {
            tvProgrammerStatus.setText("📡 " + deviceName + mode);
            tvProgrammerStatus.setTextColor(
                rfidService.isSimulationMode() ? Color.parseColor("#FF9800") : Color.parseColor("#4CAF50")
            );
        }
        
        if (tvDeviceStatus != null) {
            tvDeviceStatus.setText("📡 " + deviceName + mode);
            tvDeviceStatus.setTextColor(
                rfidService.isSimulationMode() ? Color.parseColor("#FF9800") : Color.parseColor("#4CAF50")
            );
        }
        
        logOperation("تم تهيئة الجهاز: " + deviceName + mode);
    }

    /**
     * ✅ تسجيل العمليات
     */
    private void logOperation(String message) {
        if (tvOperationLog == null) return;
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String current = tvOperationLog.getText().toString();
        if (current.equals("—")) current = "";
        String newLog = "[" + timestamp + "] " + message;
        tvOperationLog.setText(newLog + (current.isEmpty() ? "" : "\n" + current));
    }

    /**
     * ✅ إظهار/إخفاء شريط التقدم
     */
    private void showProgress(boolean show) {
        if (pbWriteProgress != null) pbWriteProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setupListeners() {
        if (BtRead != null) BtRead.setOnClickListener(v -> read());
        if (BtWrite != null) BtWrite.setOnClickListener(v -> {
            if (cbVerifyAfterWrite != null && cbVerifyAfterWrite.isChecked()) {
                writeAndVerify();
            } else {
                write();
            }
        });
        if (BtReadTid != null) BtReadTid.setOnClickListener(v -> readTid());
        if (BtLock != null) BtLock.setOnClickListener(v -> lockTag(true));
        if (BtUnlock != null) BtUnlock.setOnClickListener(v -> lockTag(false));
        if (BtGenerateEpc != null) BtGenerateEpc.setOnClickListener(v -> generateEpcFromMira());
        if (BtProgramTag != null) BtProgramTag.setOnClickListener(v -> programTagFromMira());
        
        if (cbAdvancedMode != null) {
            cbAdvancedMode.setOnCheckedChangeListener((btn, isChecked) -> toggleAdvancedMode(isChecked));
        }
        
        if (rbEPC_filter != null) rbEPC_filter.setOnClickListener(this);
        if (rbTID_filter != null) rbTID_filter.setOnClickListener(this);
        if (rbUser_filter != null) rbUser_filter.setOnClickListener(this);

        if (btnProgrammerSearch != null) {
            btnProgrammerSearch.setOnClickListener(v -> {
                String code = etProgrammerSearch.getText().toString().trim();
                if (TextUtils.isEmpty(code)) {
                    Toast.makeText(mContext, "أدخل Serial أو GTIN-13 أو EPC", Toast.LENGTH_SHORT).show();
                    return;
                }
                searchMiraItem(code);
            });
        }

        if (cb_filter != null) {
            cb_filter.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    String data = etData_filter.getText().toString().trim();
                    if (data.isEmpty() || !data.matches("[\\da-fA-F]*")) {
                        mContext.showToast(getString(R.string.uhf_msg_filter_data_must_hex));
                        cb_filter.setChecked(false);
                    } else {
                        applyFilterToHardware();
                        logOperation("تم تفعيل الفلتر: " + data);
                    }
                } else {
                    rfidService.clearFilter();
                    logOperation("تم إلغاء الفلتر");
                }
            });
        }
    }

    private void setupTextWatchers() {
        if (EtData != null) {
            EtData.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    String data = s.toString().trim();
                    if (!data.isEmpty() && EtLen != null) {
                        EtLen.setText(String.valueOf((int) Math.ceil(data.length() / 4.0)));
                    }
                }
            });
        }
    }

    private void toggleAdvancedMode(boolean isAdvanced) {
        if (layoutAdvancedSettings != null) layoutAdvancedSettings.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        if (BtReadTid != null) BtReadTid.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        if (BtLock != null) BtLock.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        if (BtUnlock != null) BtUnlock.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        if (BtGenerateEpc != null) BtGenerateEpc.setVisibility(isAdvanced ? View.VISIBLE : View.GONE);
        logOperation(isAdvanced ? "تم تفعيل الوضع المتقدم" : "تم إغلاق الوضع المتقدم");
    }

    private void applyFilterToHardware() {
        if (cb_filter != null && cb_filter.isChecked()) {
            String filterData = etData_filter.getText().toString().trim();
            int ptr = parseInteger(etPtr_filter.getText().toString().trim(), 32);
            int bank = getFilterBank();
            boolean success = rfidService.setFilter(bank, ptr, filterData);
            logOperation("تطبيق فلتر: bank=" + bank + " ptr=" + ptr + " data=" + filterData + " → " + (success ? "✅" : "❌"));
        } else {
            rfidService.clearFilter();
        }
    }

    // ============ البحث في MIRA ============
    private void searchMiraItem(String code) {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog("🔍 جاري البحث...", "البحث في قاعدة بيانات MIRA");
        showProgress(true);
        setButtonsEnabled(false);
        logOperation("بحث في MIRA: " + code);

        new Thread(() -> {
            try {
                String identifierType = detectIdentifierType(code);
                JSONObject json = rfidService.searchInMira(code, identifierType);

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);

                    if (json != null) {
                        JSONObject item = json.optJSONObject("item");
                        JSONObject barcode = json.optJSONObject("barcode");
                        JSONObject stock = json.optJSONObject("stock");
                        JSONObject location = json.optJSONObject("location");

                        if (item != null || barcode != null) {
                            if (layoutProgrammerItem != null) layoutProgrammerItem.setVisibility(View.VISIBLE);
                            currentMiraId = item != null ? item.optString("mira_id", "") : "";
                            currentGtin = barcode != null ? barcode.optString("gtin", "") : "";
                            currentSerial = barcode != null ? barcode.optString("serial_no", "") : "";

                            StringBuilder details = new StringBuilder();
                            if (item != null) {
                                if (tvProgrammerItemTitle != null) tvProgrammerItemTitle.setText("📦 " + item.optString("title", "غير معروف"));
                                details.append("🏷️ ").append(item.optString("serial", code));
                                if (!item.optString("karat", "").isEmpty()) details.append(" | 💎 ").append(item.optString("karat"));
                                if (!item.optString("weight", "").isEmpty()) details.append(" | ⚖️ ").append(item.optString("weight")).append("g");
                            }
                            if (stock != null && !stock.optString("branch_name", "").isEmpty()) {
                                details.append("\n🏪 ").append(stock.optString("branch_name"));
                            }
                            if (location != null && !location.optString("zone_name", "").isEmpty()) {
                                details.append(" | 📍 ").append(location.optString("zone_name"));
                            }
                            if (tvProgrammerItemDetails != null) tvProgrammerItemDetails.setText(details.toString());
                            if (tvProgrammerStatus != null) {
                                tvProgrammerStatus.setText("✅ تم العثور على القطعة");
                                tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                            }
                            logOperation("✅ تم العثور على القطعة: " + currentMiraId + " GTIN: " + currentGtin);
                            mContext.playSound(1);
                        } else {
                            if (tvProgrammerStatus != null) {
                                tvProgrammerStatus.setText("❌ القطعة غير موجودة في MIRA");
                                tvProgrammerStatus.setTextColor(Color.RED);
                            }
                            logOperation("❌ القطعة غير موجودة: " + code);
                            mContext.playSound(2);
                        }
                    } else {
                        if (tvProgrammerStatus != null) {
                            tvProgrammerStatus.setText("❌ خطأ في الاتصال");
                            tvProgrammerStatus.setTextColor(Color.RED);
                        }
                        logOperation("❌ خطأ في الاتصال بالسيرفر");
                        showRetryDialog("❌ فشل البحث", "تعذر الاتصال بالسيرفر", () -> searchMiraItem(code));
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    logOperation("❌ خطأ: " + e.getMessage());
                    showRetryDialog("❌ خطأ", e.getMessage(), () -> searchMiraItem(code));
                });
            }
        }).start();
    }

    private String detectIdentifierType(String code) {
        if (code.matches("^[0-9]{13}$")) return "gtin";
        if (code.startsWith("MIRA-")) return "mira_id";
        if (code.matches("^[0-9]{14}$")) return "serial";
        return "epc";
    }

    // ============ توليد EPC ============
    private void generateEpcFromMira() {
        if (currentMiraId.isEmpty() && currentGtin.isEmpty()) {
            generateRandomEpc();
            logOperation("توليد EPC عشوائي (لا توجد بيانات MIRA)");
            return;
        }
        StringBuilder epc = new StringBuilder("3008");
        if (!currentGtin.isEmpty()) {
            String cleanGtin = currentGtin.replaceAll("[^0-9]", "");
            if (cleanGtin.length() >= 12) {
                epc.append(cleanGtin.substring(cleanGtin.length() - 12));
            }
        }
        String serialPart = !currentMiraId.isEmpty() ? currentMiraId : currentSerial;
        String cleanSerial = serialPart.replaceAll("[^0-9A-Fa-f]", "");
        while (cleanSerial.length() < 8) cleanSerial = "0" + cleanSerial;
        epc.append(cleanSerial.substring(Math.max(0, cleanSerial.length() - 8)));

        while (epc.length() < 24) epc.append("0");
        String finalEpc = epc.substring(0, 24).toUpperCase();

        if (EtData != null) EtData.setText(finalEpc);
        if (EtLen != null) EtLen.setText("6");
        if (tvProgrammerStatus != null) {
            tvProgrammerStatus.setText("🎯 تم توليد EPC: " + finalEpc);
            tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
        }
        logOperation("✅ تم توليد EPC: " + finalEpc);
    }

    private void generateRandomEpc() {
        StringBuilder epc = new StringBuilder("3008");
        String hexChars = "0123456789ABCDEF";
        Random random = new Random();
        for (int i = 0; i < 20; i++) {
            epc.append(hexChars.charAt(random.nextInt(16)));
        }
        if (EtData != null) EtData.setText(epc.toString());
        if (EtLen != null) EtLen.setText("6");
        logOperation("تم توليد EPC عشوائي: " + epc.toString());
    }

    // ============ البرمجة الكاملة ============
    private void programTagFromMira() {
        if (currentMiraId.isEmpty() && currentGtin.isEmpty()) {
            Toast.makeText(mContext, "ابحث عن القطعة في MIRA أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        final AlertDialog loadingDialog = showProfessionalLoadingDialog("🔄 جاري البرمجة...", "قراءة TID، كتابة EPC، التحقق والتسجيل");
        showProgress(true);
        setButtonsEnabled(false);
        logOperation("🚀 بدء البرمجة الكاملة للقطعة: " + (currentMiraId.isEmpty() ? currentGtin : currentMiraId));

        new Thread(() -> {
            try {
                applyFilterToHardware();
                generateEpcFromMira();

                String pwdStr = getAccessPassword();
                logOperation("قراءة TID...");
                String tidData = rfidService.readTid(pwdStr);

                if (TextUtils.isEmpty(tidData)) {
                    throw new Exception("تعذر قراءة TID الشريحة، تأكد من محاذاة الشريحة مع الهوائي");
                }
                logOperation("✅ TID: " + tidData);

                String newEpc = EtData.getText().toString().trim();
                logOperation("كتابة EPC: " + newEpc);
                boolean writeSuccess = rfidService.writeEpc(newEpc, pwdStr);
                
                boolean verifySuccess = false;
                if (writeSuccess) {
                    Thread.sleep(150);
                    logOperation("التحقق من الكتابة...");
                    verifySuccess = rfidService.verifyWrite(newEpc, pwdStr);
                    logOperation("نتيجة التحقق: " + (verifySuccess ? "✅ نجح" : "❌ فشل"));
                }

                final String miraId = currentMiraId.isEmpty() ? currentGtin : currentMiraId;
                boolean registered = false;

                if (writeSuccess && verifySuccess) {
                    logOperation("تسجيل الربط في MIRA...");
                    registered = rfidService.registerRfidBinding(miraId, newEpc, tidData);
                    logOperation("نتيجة التسجيل: " + (registered ? "✅ نجح" : "❌ فشل"));
                }

                final boolean fWrite = writeSuccess;
                final boolean fVerify = verifySuccess;
                final boolean fReg = registered;
                final String fEpc = newEpc;
                final String fTid = tidData;

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);

                    if (fWrite && fVerify && fReg) {
                        tvProgrammerStatus.setText("✅ تمت البرمجة والتسجيل بنجاح\nMIRA ID: " + miraId + "\nEPC: " + fEpc + "\nTID: " + fTid);
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        logOperation("✅✅✅ البرمجة الكاملة نجحت بنسبة 100%");
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText("⚠️ فشلت البرمجة الكاملة:\nكتابة: " + (fWrite ? "✅" : "❌") + " | تحقق: " + (fVerify ? "✅" : "❌") + " | تسجيل: " + (fReg ? "✅" : "❌"));
                        tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
                        logOperation("⚠️ فشل جزئي: كتابة=" + (fWrite ? "✅" : "❌") + " تحقق=" + (fVerify ? "✅" : "❌") + " تسجيل=" + (fReg ? "✅" : "❌"));
                        mContext.playSound(2);
                        showRetryDialog("⚠️ فشل البرمجة", "تعذر استكمال الربط، أعد المحاولة", this::programTagFromMira);
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    logOperation("❌ خطأ: " + e.getMessage());
                    showRetryDialog("❌ خطأ أثناء البرمجة", e.getMessage(), this::programTagFromMira);
                });
            }
        }).start();
    }

    // ============ عمليات القراءة والكتابة ============
    private void read() {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog("🔍 جاري القراءة...", "قراءة الشريحة RFID");
        showProgress(true);
        logOperation("بدء القراءة...");

        new Thread(() -> {
            try {
                applyFilterToHardware();
                String pwdStr = getAccessPassword();
                int bank = getSelectedBank();
                int ptr = parseInteger(EtPtr.getText().toString().trim(), 32);
                int len = parseInteger(EtLen.getText().toString().trim(), 6);

                String data = rfidService.readData(pwdStr, bank, ptr, len);

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    if (!TextUtils.isEmpty(data)) {
                        EtData.setText(data);
                        tvProgrammerStatus.setText("✅ قراءة ناجحة: " + data);
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        logOperation("✅ قراءة: bank=" + bank + " ptr=" + ptr + " len=" + len + " → " + data);
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText("❌ فشل القراءة");
                        tvProgrammerStatus.setTextColor(Color.RED);
                        logOperation("❌ فشل القراءة");
                        mContext.playSound(2);
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    logOperation("❌ خطأ: " + e.getMessage());
                    showRetryDialog("❌ خطأ", e.getMessage(), this::read);
                });
            }
        }).start();
    }

    private void write() {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog("✍️ جاري الكتابة...", "كتابة البيانات على الشريحة");
        showProgress(true);
        setButtonsEnabled(false);
        String data = EtData != null ? EtData.getText().toString().trim() : "";
        logOperation("بدء الكتابة: " + data);

        new Thread(() -> {
            try {
                applyFilterToHardware();
                String pwdStr = getAccessPassword();
                int bank = getSelectedBank();
                int ptr = parseInteger(EtPtr.getText().toString().trim(), 32);

                boolean success = rfidService.writeData(pwdStr, bank, ptr, data);

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    if (success) {
                        tvProgrammerStatus.setText("✅ كتابة ناجحة");
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        logOperation("✅ كتابة ناجحة: " + data);
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText("❌ فشل الكتابة");
                        tvProgrammerStatus.setTextColor(Color.RED);
                        logOperation("❌ فشل الكتابة");
                        mContext.playSound(2);
                        showRetryDialog("❌ فشل", "تعذر الكتابة على الشريحة", this::write);
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    logOperation("❌ خطأ: " + e.getMessage());
                    showRetryDialog("❌ خطأ", e.getMessage(), this::write);
                });
            }
        }).start();
    }

    private void writeAndVerify() {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog("✍️ كتابة مع تحقق...", "كتابة ثم مطابقة البيانات");
        showProgress(true);
        setButtonsEnabled(false);
        String data = EtData != null ? EtData.getText().toString().trim() : "";
        logOperation("بدء كتابة مع تحقق: " + data);

        new Thread(() -> {
            try {
                applyFilterToHardware();
                String pwdStr = getAccessPassword();
                int bank = getSelectedBank();
                int ptr = parseInteger(EtPtr.getText().toString().trim(), 32);

                boolean writeSuccess = rfidService.writeData(pwdStr, bank, ptr, data);
                Thread.sleep(150);
                String readBack = rfidService.readData(pwdStr, bank, ptr, (int) Math.ceil(data.length() / 4.0));
                boolean verifySuccess = data.equalsIgnoreCase(readBack);

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    if (writeSuccess && verifySuccess) {
                        tvProgrammerStatus.setText("✅ كتابة وتحقق ناجح بنسبة 100%");
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        logOperation("✅ كتابة وتحقق ناجح: " + data);
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText("⚠️ فشل التحقق (كتابة: " + (writeSuccess ? "✅" : "❌") + " | مطابقة: " + (verifySuccess ? "✅" : "❌") + ")");
                        tvProgrammerStatus.setTextColor(Color.parseColor("#FF9800"));
                        logOperation("⚠️ فشل التحقق: كتابة=" + (writeSuccess ? "✅" : "❌") + " مطابقة=" + (verifySuccess ? "✅" : "❌"));
                        mContext.playSound(2);
                        showRetryDialog("⚠️ فشل", "البيانات المكتوبة لا تطابق المقروءة", this::writeAndVerify);
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    logOperation("❌ خطأ: " + e.getMessage());
                    showRetryDialog("❌ خطأ", e.getMessage(), this::writeAndVerify);
                });
            }
        }).start();
    }

    private void readTid() {
        final AlertDialog loadingDialog = showProfessionalLoadingDialog("📋 جاري قراءة TID...", "قراءة المعرف الفريد للمصنع");
        showProgress(true);
        logOperation("بدء قراءة TID...");

        new Thread(() -> {
            try {
                applyFilterToHardware();
                String pwdStr = getAccessPassword();
                String tid = rfidService.readTid(pwdStr);

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    if (!TextUtils.isEmpty(tid)) {
                        if (tvCurrentTid != null) {
                            tvCurrentTid.setText("TID: " + tid);
                            tvCurrentTid.setVisibility(View.VISIBLE);
                        }
                        tvProgrammerStatus.setText("✅ TID: " + tid);
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        logOperation("✅ TID: " + tid);
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText("❌ فشل قراءة TID");
                        tvProgrammerStatus.setTextColor(Color.RED);
                        logOperation("❌ فشل قراءة TID");
                        mContext.playSound(2);
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    logOperation("❌ خطأ: " + e.getMessage());
                    showRetryDialog("❌ خطأ", e.getMessage(), this::readTid);
                });
            }
        }).start();
    }

    private void lockTag(boolean shouldLock) {
        String actionName = shouldLock ? "🔒 جاري قفل الشريحة..." : "🔓 جاري فتح قفل الشريحة...";
        final AlertDialog loadingDialog = showProfessionalLoadingDialog(actionName, "تعديل صلاحيات الوصول");
        showProgress(true);
        setButtonsEnabled(false);
        logOperation(shouldLock ? "🔒 بدء قفل الشريحة" : "🔓 بدء فتح الشريحة");

        new Thread(() -> {
            try {
                applyFilterToHardware();
                String pwdStr = getAccessPassword();
                int bank = getSelectedBank();
                boolean success = rfidService.setTagLockState(pwdStr, bank, shouldLock);

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    if (success) {
                        tvProgrammerStatus.setText(shouldLock ? "✅ تم قفل الشريحة بنجاح" : "✅ تم فتح قفل الشريحة بنجاح");
                        tvProgrammerStatus.setTextColor(Color.parseColor("#4CAF50"));
                        logOperation(shouldLock ? "✅ تم القفل" : "✅ تم الفتح");
                        mContext.playSound(1);
                    } else {
                        tvProgrammerStatus.setText(shouldLock ? "❌ فشلت عملية القفل" : "❌ فشلت عملية الفتح");
                        tvProgrammerStatus.setTextColor(Color.RED);
                        logOperation(shouldLock ? "❌ فشل القفل" : "❌ فشل الفتح");
                        mContext.playSound(2);
                    }
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showProgress(false);
                    setButtonsEnabled(true);
                    logOperation("❌ خطأ: " + e.getMessage());
                });
            }
        }).start();
    }

    // ============ دوال مساعدة ============
    private String getAccessPassword() {
        String pwd = EtAccessPwd != null ? EtAccessPwd.getText().toString().trim() : "";
        return TextUtils.isEmpty(pwd) ? "00000000" : pwd;
    }

    private int getSelectedBank() {
        if (SpinnerBank == null) return RFIDWithUHFUART.Bank_EPC;
        int pos = SpinnerBank.getSelectedItemPosition();
        switch (pos) {
            case 0: return RFIDWithUHFUART.Bank_RESERVED;
            case 1: return RFIDWithUHFUART.Bank_EPC;
            case 2: return RFIDWithUHFUART.Bank_TID;
            case 3: return RFIDWithUHFUART.Bank_USER;
            default: return RFIDWithUHFUART.Bank_EPC;
        }
    }

    private int getFilterBank() {
        if (rbTID_filter != null && rbTID_filter.isChecked()) return RFIDWithUHFUART.Bank_TID;
        if (rbUser_filter != null && rbUser_filter.isChecked()) return RFIDWithUHFUART.Bank_USER;
        return RFIDWithUHFUART.Bank_EPC;
    }

    private int parseInteger(String val, int def) {
        try { return Integer.parseInt(val); } catch (Exception e) { return def; }
    }

    public void myOnKeyDwon() {
        read();
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.rbEPC_filter) {
            if (etPtr_filter != null) etPtr_filter.setText("32");
        } else if (id == R.id.rbTID_filter || id == R.id.rbUser_filter) {
            if (etPtr_filter != null) etPtr_filter.setText("0");
        }
    }

    private AlertDialog showProfessionalLoadingDialog(String title, String message) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return null;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);
        layout.setGravity(android.view.Gravity.CENTER);

        ProgressBar progressBar = new ProgressBar(ctx);
        progressBar.setIndeterminate(true);
        progressBar.setScaleX(1.3f);
        progressBar.setScaleY(1.3f);
        layout.addView(progressBar);

        TextView tvLoading = new TextView(ctx);
        tvLoading.setText(title);
        tvLoading.setTextSize(16);
        tvLoading.setTypeface(null, android.graphics.Typeface.BOLD);
        tvLoading.setGravity(android.view.Gravity.CENTER);
        tvLoading.setPadding(0, 20, 0, 8);
        layout.addView(tvLoading);

        TextView tvSubtext = new TextView(ctx);
        tvSubtext.setText(message);
        tvSubtext.setTextSize(12);
        tvSubtext.setTextColor(Color.GRAY);
        tvSubtext.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvSubtext);

        builder.setView(layout);
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();
        return dialog;
    }

    private void showRetryDialog(String title, String message, Runnable retryAction) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(title).setMessage(message).setCancelable(false);
        if (retryAction != null) {
            builder.setPositiveButton("🔄 إعادة المحاولة", (d, w) -> {
                d.dismiss();
                retryAction.run();
            });
        }
        builder.setNegativeButton("إغلاق", (d, w) -> d.dismiss());
        builder.show();
    }

    private void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception e) {
            Log.e(TAG, "dismissDialog error: " + e.getMessage());
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        if (BtRead != null) BtRead.setEnabled(enabled);
        if (BtWrite != null) BtWrite.setEnabled(enabled);
        if (BtProgramTag != null) BtProgramTag.setEnabled(enabled);
        if (btnProgrammerSearch != null) btnProgrammerSearch.setEnabled(enabled);
        if (BtGenerateEpc != null) BtGenerateEpc.setEnabled(enabled);
        if (BtLock != null) BtLock.setEnabled(enabled);
        if (BtUnlock != null) BtUnlock.setEnabled(enabled);
    }

    private void safeRun(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (isAdded()) r.run();
            });
        }
    }

    @Override
    public void onDestroyView() {
        if (rfidService != null) {
            rfidService.clearFilter();
        }
        super.onDestroyView();
    }
                                                                }
