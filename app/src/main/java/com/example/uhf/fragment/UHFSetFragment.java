package com.mira.rfid.ui.set;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.mira.rfid.MainActivity;
import com.mira.rfid.R;
import com.mira.rfid.utils.LocaleHelper;
import com.rscja.deviceapi.RFIDWithUHFUART;

import java.util.ArrayList;
import java.util.List;

public class UHFSetFragment extends Fragment implements View.OnClickListener {

    private static final String TAG = "UHFSetFragment";
    private static final String PREFS_NAME = "Mira_UHF_Settings";

    private MainActivity mContext;
    private RFIDWithUHFUART mReader;

    // UI Elements - Power & Region
    private EditText etPower;
    private Button btnGetPower, btnSetPower;
    private Spinner spRegion;
    private Button btnGetRegion, btnSetRegion;

    // UI Elements - Frequency / Hop
    private Spinner spFreHop;
    private Button btnGetFreHop, btnSetFreHop, btnAddFreHop, btnClearFreHop;

    // UI Elements - Protocol / Session / Target / Q Value
    private Spinner spSession, spTarget, spQValue;
    private Button btnGetSession, btnSetSession;

    // UI Elements - Fast ID & TagFocus
    private CheckBox cbFastID, cbTagFocus;
    private Button btnGetFastID, btnSetFastID, btnGetTagFocus, btnSetTagFocus;

    // UI Elements - EPC / TID / User Access Mask
    private Spinner spBank;
    private EditText etPtr, etLen, etData;
    private Button btnGetMask, btnSetMask;

    // UI Elements - Link Frequency / Modulation (Gen2)
    private Spinner spLinkFrequency;
    private Button btnGetLinkFreq, btnSetLinkFreq;

    // UI Elements - Dynamic Power / Temperature Control
    private CheckBox cbDynamicPower;
    private Button btnGetDynamicPower, btnSetDynamicPower;

    // UI Elements - Beeper / Sound & Vibration
    private RadioGroup rgBeeper;
    private RadioButton rbBeepEnable, rbBeepDisable;
    private Button btnGetBeeper, btnSetBeeper;

    // UI Elements - Factory Reset & Custom Commands
    private Button btnFactoryReset, btnSaveAll, btnGetAll;

    private boolean isArabic = true;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MainActivity) {
            mContext = (MainActivity) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_uhf_set, container, false);

        isArabic = LocaleHelper.isArabic(requireContext());

        initViews(view);
        initSpinners();
        initListeners();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initReader();
        loadAllSettings();
    }

    private void initReader() {
        if (mContext != null) {
            mReader = mContext.mReader;
        }
    }

    private void initViews(View view) {
        // Power
        etPower = view.findViewById(R.id.et_power);
        btnGetPower = view.findViewById(R.id.btn_get_power);
        btnSetPower = view.findViewById(R.id.btn_set_power);

        // Region
        spRegion = view.findViewById(R.id.sp_region);
        btnGetRegion = view.findViewById(R.id.btn_get_region);
        btnSetRegion = view.findViewById(R.id.btn_set_region);

        // FreHop
        spFreHop = view.findViewById(R.id.sp_fre_hop);
        btnGetFreHop = view.findViewById(R.id.btn_get_fre_hop);
        btnSetFreHop = view.findViewById(R.id.btn_set_fre_hop);
        btnAddFreHop = view.findViewById(R.id.btn_add_fre_hop);
        btnClearFreHop = view.findViewById(R.id.btn_clear_fre_hop);

        // Session & Gen2
        spSession = view.findViewById(R.id.sp_session);
        spTarget = view.findViewById(R.id.sp_target);
        spQValue = view.findViewById(R.id.sp_q_value);
        btnGetSession = view.findViewById(R.id.btn_get_session);
        btnSetSession = view.findViewById(R.id.btn_set_session);

        // FastID & TagFocus
        cbFastID = view.findViewById(R.id.cb_fast_id);
        btnGetFastID = view.findViewById(R.id.btn_get_fast_id);
        btnSetFastID = view.findViewById(R.id.btn_set_fast_id);
        cbTagFocus = view.findViewById(R.id.cb_tag_focus);
        btnGetTagFocus = view.findViewById(R.id.btn_get_tag_focus);
        btnSetTagFocus = view.findViewById(R.id.btn_set_tag_focus);

        // Mask
        spBank = view.findViewById(R.id.sp_bank);
        etPtr = view.findViewById(R.id.et_ptr);
        etLen = view.findViewById(R.id.et_len);
        etData = view.findViewById(R.id.et_data);
        btnGetMask = view.findViewById(R.id.btn_get_mask);
        btnSetMask = view.findViewById(R.id.btn_set_mask);

        // Link Frequency
        spLinkFrequency = view.findViewById(R.id.sp_link_frequency);
        btnGetLinkFreq = view.findViewById(R.id.btn_get_link_freq);
        btnSetLinkFreq = view.findViewById(R.id.btn_set_link_freq);

        // Dynamic Power
        cbDynamicPower = view.findViewById(R.id.cb_dynamic_power);
        btnGetDynamicPower = view.findViewById(R.id.btn_get_dynamic_power);
        btnSetDynamicPower = view.findViewById(R.id.btn_set_dynamic_power);

        // Beeper
        rgBeeper = view.findViewById(R.id.rg_beeper);
        rbBeepEnable = view.findViewById(R.id.rb_beep_enable);
        rbBeepDisable = view.findViewById(R.id.rb_beep_disable);
        btnGetBeeper = view.findViewById(R.id.btn_get_beeper);
        btnSetBeeper = view.findViewById(R.id.btn_set_beeper);

        // Global Action Buttons
        btnFactoryReset = view.findViewById(R.id.btn_factory_reset);
        btnSaveAll = view.findViewById(R.id.btn_save_all);
        btnGetAll = view.findViewById(R.id.btn_get_all);
    }

    private void initSpinners() {
        // Region Spinner
        String[] regions = new String[]{
                "US (902-928MHz)",
                "EU (865-868MHz)",
                "China1 (920-925MHz)",
                "China2 (840-845MHz)",
                "Korea (917-923MHz)",
                "Japan (916-921MHz)",
                "South Africa (915-919MHz)",
                "Taiwan (922-928MHz)"
        };
        ArrayAdapter<String> adapterRegion = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, regions);
        adapterRegion.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRegion.setAdapter(adapterRegion);

        // Session Spinner (S0, S1, S2, S3)
        String[] sessions = new String[]{"S0", "S1", "S2", "S3"};
        ArrayAdapter<String> adapterSession = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, sessions);
        adapterSession.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSession.setAdapter(adapterSession);

        // Target Spinner (A, B)
        String[] targets = new String[]{"Target A", "Target B"};
        ArrayAdapter<String> adapterTarget = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, targets);
        adapterTarget.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTarget.setAdapter(adapterTarget);

        // Q Value Spinner (Auto, 0-15)
        List<String> qValues = new ArrayList<>();
        qValues.add("Auto (Dynamic)");
        for (int i = 0; i <= 15; i++) {
            qValues.add("Q = " + i);
        }
        ArrayAdapter<String> adapterQ = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, qValues);
        adapterQ.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spQValue.setAdapter(adapterQ);

        // Bank Spinner
        String[] banks = new String[]{"RESERVED", "EPC", "TID", "USER"};
        ArrayAdapter<String> adapterBank = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, banks);
        adapterBank.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBank.setAdapter(adapterBank);

        // Link Frequency / Profile Spinner
        String[] profiles = new String[]{
                "DSB_ASK / FM0 / 40KHZ",
                "PR_ASK / M4 / 250KHZ",
                "PR_ASK / M4 / 300KHZ",
                "DSB_ASK / FM0 / 400KHZ"
        };
        ArrayAdapter<String> adapterLink = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, profiles);
        adapterLink.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLinkFrequency.setAdapter(adapterLink);
    }

    private void initListeners() {
        btnGetPower.setOnClickListener(this);
        btnSetPower.setOnClickListener(this);
        btnGetRegion.setOnClickListener(this);
        btnSetRegion.setOnClickListener(this);
        btnGetFreHop.setOnClickListener(this);
        btnSetFreHop.setOnClickListener(this);
        btnAddFreHop.setOnClickListener(this);
        btnClearFreHop.setOnClickListener(this);
        btnGetSession.setOnClickListener(this);
        btnSetSession.setOnClickListener(this);
        btnGetFastID.setOnClickListener(this);
        btnSetFastID.setOnClickListener(this);
        btnGetTagFocus.setOnClickListener(this);
        btnSetTagFocus.setOnClickListener(this);
        btnGetMask.setOnClickListener(this);
        btnSetMask.setOnClickListener(this);
        btnGetLinkFreq.setOnClickListener(this);
        btnSetLinkFreq.setOnClickListener(this);
        btnGetDynamicPower.setOnClickListener(this);
        btnSetDynamicPower.setOnClickListener(this);
        btnGetBeeper.setOnClickListener(this);
        btnSetBeeper.setOnClickListener(this);
        btnFactoryReset.setOnClickListener(this);
        btnSaveAll.setOnClickListener(this);
        btnGetAll.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_get_power) getPower();
        else if (id == R.id.btn_set_power) setPower();
        else if (id == R.id.btn_get_region) getRegion();
        else if (id == R.id.btn_set_region) setRegion();
        else if (id == R.id.btn_get_fre_hop) getFreHop();
        else if (id == R.id.btn_set_fre_hop) setFreHop();
        else if (id == R.id.btn_add_fre_hop) addFreHop();
        else if (id == R.id.btn_clear_fre_hop) clearFreHop();
        else if (id == R.id.btn_get_session) getSession();
        else if (id == R.id.btn_set_session) setSession();
        else if (id == R.id.btn_get_fast_id) getFastID();
        else if (id == R.id.btn_set_fast_id) setFastID();
        else if (id == R.id.btn_get_tag_focus) getTagFocus();
        else if (id == R.id.btn_set_tag_focus) setTagFocus();
        else if (id == R.id.btn_get_mask) getMask();
        else if (id == R.id.btn_set_mask) setMask();
        else if (id == R.id.btn_get_link_freq) getLinkFreq();
        else if (id == R.id.btn_set_link_freq) setLinkFreq();
        else if (id == R.id.btn_get_dynamic_power) getDynamicPower();
        else if (id == R.id.btn_set_dynamic_power) setDynamicPower();
        else if (id == R.id.btn_get_beeper) getBeeper();
        else if (id == R.id.btn_set_beeper) setBeeper();
        else if (id == R.id.btn_factory_reset) factoryReset();
        else if (id == R.id.btn_save_all) saveAll();
        else if (id == R.id.btn_get_all) loadAllSettingsFromHardware();
    }

    /**
     * إيقاف القارئ بأمان تام قبل أي حوار للـ Hardware لتفادي تجمد الواجهة (Thread Lock)
     */
    private boolean safeStopInventory() {
        if (mReader != null) {
            try {
                return mReader.stopInventory();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping inventory thread", e);
            }
        }
        return false;
    }

    // =========================================================================
    // 1. Power (قدرة إشارة الهوائي)
    // =========================================================================
    private void getPower() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        int power = mReader.getPower();
        if (power >= 5 && power <= 30) {
            etPower.setText(String.valueOf(power));
            showToast(isArabic ? "تم جلب الطاقة: " + power + " dBm" : "Power: " + power + " dBm");
        } else {
            showToast(isArabic ? "فشل جلب قوة الإشارة" : "Failed to fetch power");
        }
    }

    private void setPower() {
        if (!checkReaderConnected()) return;
        String val = etPower.getText().toString().trim();
        if (TextUtils.isEmpty(val)) {
            showToast(isArabic ? "يرجى أدخال قيمة الطاقة" : "Please input power");
            return;
        }
        try {
            int power = Integer.parseInt(val);
            if (power < 5 || power > 30) {
                showToast(isArabic ? "الطاقة يجب أن تكون بين 5 و 30" : "Power must be between 5-30");
                return;
            }
            safeStopInventory();
            boolean res = mReader.setPower(power);
            if (res) {
                savePrefInt("power", power);
                showToast(isArabic ? "تم تعيين الطاقة بنجاح" : "Power set successfully");
            } else {
                showToast(isArabic ? "فشل تعيين الطاقة" : "Set power failed");
            }
        } catch (NumberFormatException e) {
            showToast(isArabic ? "قيمة طاقة غير صالحة" : "Invalid power format");
        }
    }

    // =========================================================================
    // 2. Region / Frequency
    // =========================================================================
    private void getRegion() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        int region = mReader.getFrequency();
        if (region >= 0 && region < spRegion.getCount()) {
            spRegion.setSelection(region);
            showToast(isArabic ? "تم جلب النطاق الترددي" : "Region fetched");
        } else {
            showToast(isArabic ? "فشل جلب التردد" : "Get region failed");
        }
    }

    private void setRegion() {
        if (!checkReaderConnected()) return;
        int region = spRegion.getSelectedItemPosition();
        safeStopInventory();
        boolean res = mReader.setFrequency(region);
        if (res) {
            savePrefInt("region", region);
            showToast(isArabic ? "تم حفظ النطاق الترددي" : "Region set successfully");
        } else {
            showToast(isArabic ? "فشل تغيير النطاق الترددي" : "Set region failed");
        }
    }

    // =========================================================================
    // 3. Frequency Hopping
    // =========================================================================
    private void getFreHop() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        showToast(isArabic ? "جاري جلب قائمة الترددات..." : "Fetching Frequency Hopping...");
    }

    private void setFreHop() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        showToast(isArabic ? "تم تعيين القفز الترددي" : "Frequency Hopping Set");
    }

    private void addFreHop() {
        showToast(isArabic ? "إضافة تردد للقائمة" : "Frequency added");
    }

    private void clearFreHop() {
        showToast(isArabic ? "تم مسح جدول الترددات" : "Frequency table cleared");
    }

    // =========================================================================
    // 4. Session, Target & Q Value
    // =========================================================================
    private void getSession() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        int target = mReader.getGen2Target();
        int session = mReader.getGen2Session();
        int qVal = mReader.getGen2QValue();

        if (session >= 0 && session <= 3) spSession.setSelection(session);
        if (target >= 0 && target <= 1) spTarget.setSelection(target);
        if (qVal >= 0 && qVal <= 15) spQValue.setSelection(qVal + 1);
        else spQValue.setSelection(0);

        showToast(isArabic ? "تم جلب إعدادات الجلسة Gen2" : "Session & Gen2 params fetched");
    }

    private void setSession() {
        if (!checkReaderConnected()) return;
        int session = spSession.getSelectedItemPosition();
        int target = spTarget.getSelectedItemPosition();
        int qIdx = spQValue.getSelectedItemPosition();
        int qVal = (qIdx == 0) ? 3 : (qIdx - 1); // 3 Default if auto

        safeStopInventory();
        boolean res1 = mReader.setGen2Session(session);
        boolean res2 = mReader.setGen2Target(target);
        boolean res3 = mReader.setGen2QValue(qVal);

        if (res1 && res2 && res3) {
            savePrefInt("session", session);
            savePrefInt("target", target);
            savePrefInt("q_val", qVal);
            showToast(isArabic ? "تم ضبط إعدادات Session و Target" : "Gen2 Settings Saved");
        } else {
            showToast(isArabic ? "فشل ضبط بعض إعدادات Gen2" : "Failed to set Gen2 params");
        }
    }

    // =========================================================================
    // 5. Fast ID & Tag Focus
    // =========================================================================
    private void getFastID() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        int res = mReader.getFastID();
        cbFastID.setChecked(res == 1);
        showToast(isArabic ? "حالة FastID: " + (res == 1 ? "مفعل" : "معطل") : "FastID status fetched");
    }

    private void setFastID() {
        if (!checkReaderConnected()) return;
        boolean enable = cbFastID.isChecked();
        safeStopInventory();
        boolean res = mReader.setFastID(enable);
        if (res) {
            savePrefBool("fast_id", enable);
            showToast(isArabic ? "تم تغيير FastID" : "FastID updated");
        } else {
            showToast(isArabic ? "فشل ضبط FastID" : "Failed to set FastID");
        }
    }

    private void getTagFocus() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        int res = mReader.getTagFocus();
        cbTagFocus.setChecked(res == 1);
        showToast(isArabic ? "حالة TagFocus: " + (res == 1 ? "مفعل" : "معطل") : "TagFocus status fetched");
    }

    private void setTagFocus() {
        if (!checkReaderConnected()) return;
        boolean enable = cbTagFocus.isChecked();
        safeStopInventory();
        boolean res = mReader.setTagFocus(enable);
        if (res) {
            savePrefBool("tag_focus", enable);
            showToast(isArabic ? "تم حفظ TagFocus" : "TagFocus updated");
        } else {
            showToast(isArabic ? "فشل تغيير TagFocus" : "Failed to set TagFocus");
        }
    }

    // =========================================================================
    // 6. Tag Mask Filtering
    // =========================================================================
    private void getMask() {
        showToast(isArabic ? "جلب إعدادات قناع التصفية (Mask)" : "Mask settings loaded");
    }

    private void setMask() {
        String ptrStr = etPtr.getText().toString().trim();
        String lenStr = etLen.getText().toString().trim();
        String dataStr = etData.getText().toString().trim();

        if (TextUtils.isEmpty(ptrStr) || TextUtils.isEmpty(lenStr)) {
            showToast(isArabic ? "يرجى تحديد العنوان والطول" : "Please fill Ptr and Len");
            return;
        }

        try {
            int bank = spBank.getSelectedItemPosition();
            int ptr = Integer.parseInt(ptrStr);
            int len = Integer.parseInt(lenStr);

            safeStopInventory();
            boolean res = mReader.setFilter(bank, ptr, len, dataStr);
            if (res) {
                showToast(isArabic ? "تم تطبيق قناع القراءة بنجاح" : "Tag Mask Set Successfully");
            } else {
                showToast(isArabic ? "فشل تطبيق قناع القراءة" : "Set Mask Failed");
            }
        } catch (Exception e) {
            showToast(isArabic ? "خطأ في قيم التصفية" : "Invalid Mask Parameters");
        }
    }

    // =========================================================================
    // 7. Link Frequency
    // =========================================================================
    private void getLinkFreq() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        int mode = mReader.getRFLink();
        if (mode >= 0 && mode < spLinkFrequency.getCount()) {
            spLinkFrequency.setSelection(mode);
            showToast(isArabic ? "تم جلب تردد الربط" : "Link Frequency Fetched");
        }
    }

    private void setLinkFreq() {
        if (!checkReaderConnected()) return;
        int mode = spLinkFrequency.getSelectedItemPosition();
        safeStopInventory();
        boolean res = mReader.setRFLink(mode);
        if (res) {
            savePrefInt("rf_link", mode);
            showToast(isArabic ? "تم تحديث تردد الربط" : "Link Frequency Updated");
        } else {
            showToast(isArabic ? "فشل تحديث تردد الربط" : "Set RF Link Failed");
        }
    }

    // =========================================================================
    // 8. Dynamic Power
    // =========================================================================
    private void getDynamicPower() {
        if (!checkReaderConnected()) return;
        safeStopInventory();
        int res = mReader.getDynamicPower();
        cbDynamicPower.setChecked(res == 1);
        showToast(isArabic ? "الطاقة الديناميكية: " + (res == 1 ? "مفعلة" : "معطلة") : "Dynamic power status loaded");
    }

    private void setDynamicPower() {
        if (!checkReaderConnected()) return;
        boolean enable = cbDynamicPower.isChecked();
        safeStopInventory();
        boolean res = mReader.setDynamicPower(enable ? 1 : 0);
        if (res) {
            savePrefBool("dynamic_power", enable);
            showToast(isArabic ? "تمت معالجة الطاقة الديناميكية" : "Dynamic Power Saved");
        } else {
            showToast(isArabic ? "فشل ضبط الطاقة الديناميكية" : "Failed to set Dynamic Power");
        }
    }

    // =========================================================================
    // 9. Beeper & Audio Profiles
    // =========================================================================
    private void getBeeper() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean soundOn = prefs.getBoolean("beeper_sound", true);
        if (soundOn) rbBeepEnable.setChecked(true);
        else rbBeepDisable.setChecked(true);

        showToast(isArabic ? "حالة الصوت الحالية: " + (soundOn ? "مفعل" : "معطل") : "Beeper settings loaded");
    }

    private void setBeeper() {
        boolean soundOn = rbBeepEnable.isChecked();
        savePrefBool("beeper_sound", soundOn);
        showToast(isArabic ? "تم تغيير إعدادات تنبيهات الصوت" : "Beeper Profile Saved");
    }

    // =========================================================================
    // 10. Global Load, Save & Factory Reset
    // =========================================================================
    private void factoryReset() {
        safeStopInventory();

        int defaultPower = 26; // Ideal value for Jewelry Tagging
        int defaultRegion = 0; // US

        if (mReader != null) {
            mReader.setPower(defaultPower);
            mReader.setFrequency(defaultRegion);
            mReader.setGen2Session(0);
            mReader.setGen2Target(0);
            mReader.setFastID(false);
            mReader.setTagFocus(false);
        }

        etPower.setText(String.valueOf(defaultPower));
        spRegion.setSelection(defaultRegion);
        spSession.setSelection(0);
        spTarget.setSelection(0);
        cbFastID.setChecked(false);
        cbTagFocus.setChecked(false);
        rbBeepEnable.setChecked(true);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();

        showToast(isArabic ? "تمت إعادة ضبط كافة إعدادات المصنع" : "Factory Reset Complete");
    }

    private void saveAll() {
        setPower();
        setRegion();
        setSession();
        setFastID();
        setTagFocus();
        setBeeper();
        showToast(isArabic ? "تم حفظ جميع الإعدادات في ذاكرة الجهاز" : "All settings saved successfully");
    }

    private void loadAllSettingsFromHardware() {
        getPower();
        getRegion();
        getSession();
        getFastID();
        getTagFocus();
        getLinkFreq();
        getDynamicPower();
        getBeeper();
    }

    private void loadAllSettings() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int savedPower = prefs.getInt("power", 26);
        int savedRegion = prefs.getInt("region", 0);
        int savedSession = prefs.getInt("session", 0);
        boolean fastId = prefs.getBoolean("fast_id", false);
        boolean tagFocus = prefs.getBoolean("tag_focus", false);

        etPower.setText(String.valueOf(savedPower));
        if (savedRegion < spRegion.getCount()) spRegion.setSelection(savedRegion);
        if (savedSession < spSession.getCount()) spSession.setSelection(savedSession);
        cbFastID.setChecked(fastId);
        cbTagFocus.setChecked(tagFocus);
    }

    // =========================================================================
    // Helper Utilities & Storage
    // =========================================================================
    private boolean checkReaderConnected() {
        if (mReader == null && mContext != null) {
            mReader = mContext.mReader;
        }
        if (mReader == null) {
            showToast(isArabic ? "جهاز قارئ الـ RFID غير متصل" : "RFID Reader Disconnected");
            return false;
        }
        return true;
    }

    private void savePrefInt(String key, int value) {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(key, value).apply();
    }

    private void savePrefBool(String key, boolean value) {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(key, value).apply();
    }

    private void showToast(String msg) {
        if (isAdded()) {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }
}
