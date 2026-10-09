package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.activity.BaseTabFragmentActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.rscja.deviceapi.RFIDWithUHFUART;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ✅ MiraReaderControlFragment — لوحة التحكم المركزية بقارئ RFID
 *
 * ⚠️ المصدر الوحيد للتحكم بإعدادات القارئ داخل MIRA.
 *    أي تعديل هنا يُحفظ في MiraReaderSettings ويُطبَّق فوراً.
 */
public class MiraReaderControlFragment extends Fragment {

    private static final String TAG = "MiraReaderControl";

    private MiraReaderSettings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isUpdatingUi = new AtomicBoolean(false);
    private boolean isArabic = true;

    // Views
    private SeekBar seekPower;
    private TextView tvPowerValue, tvSummary;
    private Spinner spFrequency, spSession, spTarget, spQValue;
    private MaterialSwitch swFastId, swTagFocus, swDynamicPower;
    private MaterialSwitch swBeep, swVibration;
    private MaterialButton btnApplyNow, btnReset, btnReadCurrent;
    private View loading;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_mira_reader_control, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
        super.onViewCreated(v, saved);
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        settings = MiraReaderSettings.getInstance(requireContext());

        bindViews(v);
        setupSpinners();
        setupListeners();
        loadSettingsToUi();
        updateSummary();
    }

    private void bindViews(View v) {
        seekPower        = v.findViewById(R.id.seekPower);
        tvPowerValue     = v.findViewById(R.id.tvPowerValue);
        tvSummary        = v.findViewById(R.id.tvSettingsSummary);
        spFrequency      = v.findViewById(R.id.spFrequency);
        spSession        = v.findViewById(R.id.spSession);
        spTarget         = v.findViewById(R.id.spTarget);
        spQValue         = v.findViewById(R.id.spQValue);
        swFastId         = v.findViewById(R.id.swFastId);
        swTagFocus       = v.findViewById(R.id.swTagFocus);
        swDynamicPower   = v.findViewById(R.id.swDynamicPower);
        swBeep           = v.findViewById(R.id.swBeep);
        swVibration      = v.findViewById(R.id.swVibration);
        btnApplyNow      = v.findViewById(R.id.btnApplyNow);
        btnReset         = v.findViewById(R.id.btnResetDefaults);
        btnReadCurrent   = v.findViewById(R.id.btnReadCurrent);
        loading          = v.findViewById(R.id.readerLoading);
    }

    private void setupSpinners() {
        String[] freqs = {"FCC (902-928)", "FCC-A (902-928)",
                "ETSI (865-868)", "China (920-925)", "Japan (916-921)"};
        spFrequency.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, freqs));

        String[] sessions = {"S0", "S1", "S2", "S3"};
        spSession.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, sessions));

        String[] targets = {"A", "B", "A/B"};
        spTarget.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, targets));

        String[] qValues = new String[16];
        for (int i = 0; i < 16; i++) qValues[i] = String.valueOf(i);
        spQValue.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, qValues));
    }

    private void setupListeners() {
        seekPower.setMax(30);
        seekPower.setMin(5);
        seekPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (isUpdatingUi.get()) return;
                tvPowerValue.setText(p + " dBm");
                settings.setPowerDbm(p);
                updateSummary();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        spFrequency.setOnItemSelectedListener(spinnerListener(pos -> settings.setFrequencyMode(pos)));
        spSession.setOnItemSelectedListener(spinnerListener(pos -> settings.setSession(pos)));
        spTarget.setOnItemSelectedListener(spinnerListener(pos -> settings.setTarget(pos)));
        spQValue.setOnItemSelectedListener(spinnerListener(pos -> settings.setQValue(pos)));

        swFastId.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setFastId(c); updateSummary();
        });
        swTagFocus.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setTagFocus(c); updateSummary();
        });
        swDynamicPower.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setDynamicPower(c); updateSummary();
        });
        swBeep.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setBeepMode(c ? 1 : 0);
        });
        swVibration.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setVibrationMode(c ? 1 : 0);
        });

        btnApplyNow.setOnClickListener(x -> applyNow());
        btnReset.setOnClickListener(x -> confirmReset());
        btnReadCurrent.setOnClickListener(x -> readCurrent());
    }

    private AdapterView.OnItemSelectedListener spinnerListener(IntConsumer onChange) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (isUpdatingUi.get()) return;
                onChange.accept(pos);
                updateSummary();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
    }

    private interface IntConsumer { void accept(int value); }

    private void loadSettingsToUi() {
        isUpdatingUi.set(true);
        try {
            seekPower.setProgress(settings.getPowerDbm());
            tvPowerValue.setText(settings.getPowerDbm() + " dBm");
            spFrequency.setSelection(settings.getFrequencyMode());
            spSession.setSelection(settings.getSession());
            spTarget.setSelection(settings.getTarget());
            spQValue.setSelection(settings.getQValue());
            swFastId.setChecked(settings.isFastId());
            swTagFocus.setChecked(settings.isTagFocus());
            swDynamicPower.setChecked(settings.isDynamicPower());
            swBeep.setChecked(settings.getBeepMode() > 0);
            swVibration.setChecked(settings.getVibrationMode() > 0);
        } finally {
            isUpdatingUi.set(false);
        }
    }

    private void updateSummary() {
        if (tvSummary != null) tvSummary.setText(settings.getSummary());
    }

    private void applyNow() {
        RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
        if (reader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل — سيُطبَّق عند الاتصال"
                           : "⚠️ Reader not connected — will apply on connect");
            return;
        }

        showLoading(true);
        new Thread(() -> {
            MiraReaderSettings.ApplyResult r = settings.applyToReader(reader);
            ui.post(() -> {
                showLoading(false);
                String msg = r.isSuccess()
                    ? (isArabic ? "✅ تم تطبيق " + r.applied + " إعداد"
                                : "✅ Applied " + r.applied + " settings")
                    : (isArabic ? "⚠️ نجح " + r.applied + " / فشل " + r.failed
                                : "⚠️ OK " + r.applied + " / Failed " + r.failed);
                toast(msg);
            });
        }, "ApplyMiraSettings").start();
    }

    private void readCurrent() {
        RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
        if (reader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل" : "⚠️ Reader not connected");
            return;
        }
        StringBuilder sb = new StringBuilder();
        try { sb.append("Power: ").append(reader.getPower()).append(" dBm\n"); }
        catch (Exception e) { sb.append("Power: ?\n"); }
        sb.append("Frequency: ").append(MiraReaderSettings.getFrequencyLabel(settings.getFrequencyMode())).append("\n");
        sb.append("Session: S").append(settings.getSession()).append("\n");
        sb.append("Target: ").append(MiraReaderSettings.getTargetLabel(settings.getTarget())).append("\n");
        sb.append("Q: ").append(settings.getQValue()).append("\n");

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📖 الحالة الحالية" : "📖 Current State")
            .setMessage(sb.toString())
            .setPositiveButton(isArabic ? "موافق" : "OK", null)
            .show();
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "🔄 إعادة تعيين" : "🔄 Reset")
            .setMessage(isArabic
                ? "سيتم إرجاع كل الإعدادات للقيم الافتراضية.\nهل أنت متأكد؟"
                : "All settings will reset to defaults.\nContinue?")
            .setPositiveButton(isArabic ? "نعم" : "Yes", (d, w) -> {
                settings.resetToDefaults();
                loadSettingsToUi();
                updateSummary();
                applyNow();
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    private void showLoading(boolean show) {
        if (loading != null) loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }
}
