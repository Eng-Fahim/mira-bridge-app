package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import com.google.android.material.appbar.MaterialToolbar;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.R;
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.example.uhf.activity.BaseTabFragmentActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.rscja.deviceapi.RFIDWithUHFUART;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraReaderControlFragment v4.0 — لوحة التحكم الكاملة
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * [v4.0] إضافات:
 *   ✅ 🔊 الأصوات والاهتزاز (Sound & Vibration)
 *   ✅ 🚫 فلترة التكرار (Advanced Reading)
 *   ✅ 🌐 إعدادات عالمية (LED, Screen, Toast, Log)
 *   ✅ 🎯 وضع الزناد (Trigger Mode — Hold/Toggle)
 *   ✅ إصلاح مشكلة عدم حفظ الإعدادات (ربط كامل)
 *
 * يعرض الإعدادات الحقيقية من توثيق Chainway SDK الرسمي:
 *   - 19 منطقة تردد (HEX)
 *   - RFLink بـ 12 وضعاً
 *   - Session / Target / Q
 *   - FastID / TagFocus / Fast Inventory
 *   - 📦 بيانات الوسم (EPC / TID / USER)
 *   - 📡 اختبار القراءة المباشر
 */
public class MiraReaderControlFragment extends Fragment {

    private static final String TAG = "MiraReaderControl";

    private MiraReaderSettings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isUpdatingUi = new AtomicBoolean(false);
    private boolean isArabic = true;
    private MaterialToolbar toolbar;

    // ═══════════════ Views — Main ═══════════════
    private SeekBar seekPower;
    private TextView tvPowerValue, tvSummary;
    private Spinner spFrequency, spRfLink, spSession, spTarget, spQValue;
    private MaterialSwitch swFastId, swTagFocus, swFastInventory;
    private Spinner spReadMode;
    private TextView tvReadModeHint;
    private MaterialButton btnApplyNow, btnReset, btnReadCurrent;
    private View loading;

    // ═══════════════ 🔊 Sound & Vibration [v4.0] ═══════════════
    private MaterialSwitch swSoundEnabled, swVibrateEnabled;
    private SeekBar        seekVolume, seekVibrateMs, seekDupFilter;
    private Spinner        spToneNew, spToneDuplicate, spToneError;
    private MaterialSwitch swSoundOnPos, swSoundOnInventory;

    // ═══════════════ 🚫 Advanced Reading [v4.0] ═══════════════
    private MaterialSwitch swFilterDuplicates;
    private TextView       tvVolumeValue, tvVibrateMsValue, tvDupFilterValue;

    // ═══════════════ 🌐 Global [v4.0] ═══════════════
    private MaterialSwitch swKeepScreenOn, swLedFlash, swToastEnabled, swAutoSaveLog;

    // ═══════════════ 🎯 Trigger Mode [v4.1] ═══════════════
    private Spinner  spTriggerMode;
    private TextView tvTriggerModeHint;

    // ═══════════════ Live Test ═══════════════
    private TextView tvLiveReadCount, tvLiveRate, tvLiveLastEpc, tvLiveRssi;
    private MaterialButton btnLiveTestToggle;

    private volatile boolean isLiveTesting = false;
    private volatile int liveReadCount = 0;
    private volatile long liveTestStartTime = 0;
    private volatile String liveLastEpc = null;
    private volatile int liveLastRssi = -100;
    private Thread liveReadThread;
    private final Handler liveUiHandler = new Handler(Looper.getMainLooper());
    private Runnable liveRateUpdater;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  Bind Views
    // ═══════════════════════════════════════════════════════════

    private void bindViews(View v) {

        // ═══ Toolbar ═══
        toolbar = v.findViewById(R.id.toolbarReaderControl);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(x -> {
                Log.d(TAG, "🔙 Back pressed");
                handleBack();
            });
        }

        // ═══ Main Controls ═══
        seekPower         = v.findViewById(R.id.seekPower);
        tvPowerValue      = v.findViewById(R.id.tvPowerValue);
        tvSummary         = v.findViewById(R.id.tvSettingsSummary);
        spFrequency       = v.findViewById(R.id.spFrequency);
        spRfLink          = v.findViewById(R.id.spRfLink);
        spSession         = v.findViewById(R.id.spSession);
        spTarget          = v.findViewById(R.id.spTarget);
        spQValue          = v.findViewById(R.id.spQValue);
        swFastId          = v.findViewById(R.id.swFastId);
        swTagFocus        = v.findViewById(R.id.swTagFocus);
        swFastInventory   = v.findViewById(R.id.swFastInventory);
        btnApplyNow       = v.findViewById(R.id.btnApplyNow);
        btnReset          = v.findViewById(R.id.btnResetDefaults);
        btnReadCurrent    = v.findViewById(R.id.btnReadCurrent);
        loading           = v.findViewById(R.id.readerLoading);

        spReadMode        = v.findViewById(R.id.spReadMode);
        tvReadModeHint    = v.findViewById(R.id.tvReadModeHint);

        // ═══ 🔊 Sound & Vibration [v4.0] ═══
        swSoundEnabled      = v.findViewById(R.id.swSoundEnabled);
        seekVolume          = v.findViewById(R.id.seekVolume);
        tvVolumeValue       = v.findViewById(R.id.tvVolumeValue);
        swVibrateEnabled    = v.findViewById(R.id.swVibrateEnabled);
        seekVibrateMs       = v.findViewById(R.id.seekVibrateMs);
        tvVibrateMsValue    = v.findViewById(R.id.tvVibrateMsValue);

        spToneNew           = v.findViewById(R.id.spToneNew);
        spToneDuplicate     = v.findViewById(R.id.spToneDuplicate);
        spToneError         = v.findViewById(R.id.spToneError);

        swSoundOnPos        = v.findViewById(R.id.swSoundOnPos);
        swSoundOnInventory  = v.findViewById(R.id.swSoundOnInventory);

        // ═══ 🚫 Advanced Reading [v4.0] ═══
        swFilterDuplicates  = v.findViewById(R.id.swFilterDuplicates);
        seekDupFilter       = v.findViewById(R.id.seekDupFilter);
        tvDupFilterValue    = v.findViewById(R.id.tvDupFilterValue);

        // ═══ 🌐 Global [v4.0] ═══
        swKeepScreenOn      = v.findViewById(R.id.swKeepScreenOn);
        swLedFlash          = v.findViewById(R.id.swLedFlash);
        swToastEnabled      = v.findViewById(R.id.swToastEnabled);
        swAutoSaveLog       = v.findViewById(R.id.swAutoSaveLog);

        // ═══ 🎯 Trigger Mode [v4.1] ═══
        spTriggerMode       = v.findViewById(R.id.spTriggerMode);
        tvTriggerModeHint   = v.findViewById(R.id.tvTriggerModeHint);

        // ═══ Live Test ═══
        tvLiveReadCount   = v.findViewById(R.id.tvLiveReadCount);
        tvLiveRate        = v.findViewById(R.id.tvLiveRate);
        tvLiveLastEpc     = v.findViewById(R.id.tvLiveLastEpc);
        tvLiveRssi        = v.findViewById(R.id.tvLiveRssi);
        btnLiveTestToggle = v.findViewById(R.id.btnLiveTestToggle);
    }

    // ═══════════════════════════════════════════════════════════
    //  Setup Spinners
    // ═══════════════════════════════════════════════════════════

    private void setupSpinners() {
        // ✅ 19 منطقة تردد
        spFrequency.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MiraReaderSettings.FREQ_LABELS));

        // ✅ RFLink — 12 وضعاً
        spRfLink.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MiraReaderSettings.RFLINK_LABELS));

        // ✅ Session
        String[] sessions = {"S0", "S1", "S2", "S3"};
        spSession.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, sessions));

        // ✅ Target
        String[] targets = {"A", "B"};
        spTarget.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, targets));

        // ✅ Q Value (0-15)
        String[] qValues = new String[16];
        for (int i = 0; i < 16; i++) qValues[i] = String.valueOf(i);
        spQValue.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, qValues));

        // ✅ Read Mode
        spReadMode.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MiraReaderSettings.READ_MODE_LABELS));

        // ═══════════════════════════════════════════════════════
        //  🔊 نغمات — نفس المحول للثلاثة [v4.0]
        // ═══════════════════════════════════════════════════════
        ArrayAdapter<String> toneAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MiraReaderSettings.TONE_LABELS);

        if (spToneNew != null)       spToneNew.setAdapter(toneAdapter);
        if (spToneDuplicate != null) spToneDuplicate.setAdapter(toneAdapter);
        if (spToneError != null)     spToneError.setAdapter(toneAdapter);

        // ═══════════════════════════════════════════════════════
        //  🎯 Trigger Mode [v4.1]
        // ═══════════════════════════════════════════════════════
        if (spTriggerMode != null) {
            spTriggerMode.setAdapter(new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    MiraReaderSettings.TRIGGER_MODE_LABELS));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Setup Listeners
    // ═══════════════════════════════════════════════════════════

    private void setupListeners() {

        // ═══════════════════════════════════════════════════════
        //  Power
        // ═══════════════════════════════════════════════════════
        seekPower.setMax(33);
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

        // ═══════════════════════════════════════════════════════
        //  Frequency
        // ═══════════════════════════════════════════════════════
        spFrequency.setOnItemSelectedListener(spinnerListener(pos -> {
            int mode = MiraReaderSettings.FREQ_MODES[pos][0];
            settings.setFreqMode(mode);
        }));

        // ═══════════════════════════════════════════════════════
        //  RFLink / Session / Target / Q
        // ═══════════════════════════════════════════════════════
        spRfLink.setOnItemSelectedListener(spinnerListener(pos -> settings.setRfLink(pos)));
        spSession.setOnItemSelectedListener(spinnerListener(pos -> settings.setSession(pos)));
        spTarget.setOnItemSelectedListener(spinnerListener(pos -> settings.setTarget(pos)));
        spQValue.setOnItemSelectedListener(spinnerListener(pos -> settings.setQValue(pos)));

        // ═══════════════════════════════════════════════════════
        //  Switches — Main
        // ═══════════════════════════════════════════════════════
        swFastId.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setFastId(c); updateSummary();
        });
        swTagFocus.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setTagFocus(c); updateSummary();
        });
        swFastInventory.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setFastInventory(c); updateSummary();
        });

        // ═══════════════════════════════════════════════════════
        //  Read Mode
        // ═══════════════════════════════════════════════════════
        spReadMode.setOnItemSelectedListener(spinnerListener(pos -> {
            settings.setReadMode(pos);
            if (tvReadModeHint != null) {
                tvReadModeHint.setText(MiraReaderSettings.READ_MODE_HINTS[pos]);
            }
        }));

        // ═══════════════════════════════════════════════════════
        //  Main Buttons
        // ═══════════════════════════════════════════════════════
        btnApplyNow.setOnClickListener(x -> applyNow());
        btnReset.setOnClickListener(x -> confirmReset());
        btnReadCurrent.setOnClickListener(x -> readCurrent());

        // ═══════════════════════════════════════════════════════
        //  Live Test
        // ═══════════════════════════════════════════════════════
        btnLiveTestToggle.setOnClickListener(x -> toggleLiveTest());

        // ═══════════════════════════════════════════════════════
        //  🔊 Sound & Vibration [v4.0]
        // ═══════════════════════════════════════════════════════
        if (swSoundEnabled != null) {
            swSoundEnabled.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setSoundEnabled(c);
            });
        }

        if (seekVolume != null) {
            seekVolume.setMax(100);
            seekVolume.setOnSeekBarChangeListener(simpleSeek((sb, p) -> {
                settings.setSoundVolume(p);
                if (tvVolumeValue != null) tvVolumeValue.setText(p + "%");
            }));
        }

        if (swVibrateEnabled != null) {
            swVibrateEnabled.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setVibrateEnabled(c);
            });
        }

        if (seekVibrateMs != null) {
            seekVibrateMs.setMax(500);
            seekVibrateMs.setOnSeekBarChangeListener(simpleSeek((sb, p) -> {
                settings.setVibrateMs(p);
                if (tvVibrateMsValue != null) tvVibrateMsValue.setText(p + " ms");
            }));
        }

        if (spToneNew != null) {
            spToneNew.setOnItemSelectedListener(spinnerListener(pos ->
                    settings.setToneNewTag(pos)));
        }
        if (spToneDuplicate != null) {
            spToneDuplicate.setOnItemSelectedListener(spinnerListener(pos ->
                    settings.setToneDuplicate(pos)));
        }
        if (spToneError != null) {
            spToneError.setOnItemSelectedListener(spinnerListener(pos ->
                    settings.setToneError(pos)));
        }

        if (swSoundOnPos != null) {
            swSoundOnPos.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setSoundOnPos(c);
            });
        }
        if (swSoundOnInventory != null) {
            swSoundOnInventory.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setSoundOnInventory(c);
            });
        }

        // ═══════════════════════════════════════════════════════
        //  🚫 Advanced Reading [v4.0]
        // ═══════════════════════════════════════════════════════
        if (swFilterDuplicates != null) {
            swFilterDuplicates.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setFilterDuplicates(c);
            });
        }

        if (seekDupFilter != null) {
            seekDupFilter.setMax(10000);
            seekDupFilter.setOnSeekBarChangeListener(simpleSeek((sb, p) -> {
                settings.setDuplicateFilterMs(p);
                if (tvDupFilterValue != null) tvDupFilterValue.setText(p + " ms");
            }));
        }

        // ═══════════════════════════════════════════════════════
        //  🌐 Global [v4.0]
        // ═══════════════════════════════════════════════════════
        if (swKeepScreenOn != null) {
            swKeepScreenOn.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setKeepScreenOn(c);
            });
        }
        if (swLedFlash != null) {
            swLedFlash.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setLedFlashEnabled(c);
            });
        }
        if (swToastEnabled != null) {
            swToastEnabled.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setToastEnabled(c);
            });
        }
        if (swAutoSaveLog != null) {
            swAutoSaveLog.setOnCheckedChangeListener((b, c) -> {
                if (isUpdatingUi.get()) return;
                settings.setAutoSaveLog(c);
            });
        }

        // ═══════════════════════════════════════════════════════
        //  🎯 Trigger Mode [v4.1]
        // ═══════════════════════════════════════════════════════
        if (spTriggerMode != null) {
            spTriggerMode.setOnItemSelectedListener(spinnerListener(pos -> {
                settings.setTriggerMode(pos);
                if (tvTriggerModeHint != null) {
                    tvTriggerModeHint.setText(
                            MiraReaderSettings.TRIGGER_MODE_HINTS[pos]);
                }
            }));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Listener Helpers
    // ═══════════════════════════════════════════════════════════

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

    /**
     * ✅ Listener مبسّط للـ SeekBar — يُستدعى فقط من المستخدم.
     */
    private SeekBar.OnSeekBarChangeListener simpleSeek(BiIntConsumer onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser || isUpdatingUi.get()) return;
                onChange.accept(s, p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
    }

    private interface IntConsumer { void accept(int value); }
    private interface BiIntConsumer { void accept(SeekBar s, int value); }

    // ═══════════════════════════════════════════════════════════
    //  ✅ Load Settings → UI (الإصلاح الحرِج)
    // ═══════════════════════════════════════════════════════════

    private void loadSettingsToUi() {
        isUpdatingUi.set(true);
        try {
            // ═══════════════════════════════════════════════════
            //  Main
            // ═══════════════════════════════════════════════════
            seekPower.setProgress(settings.getPowerDbm());
            tvPowerValue.setText(settings.getPowerDbm() + " dBm");

            int freqIdx = 0;
            for (int i = 0; i < MiraReaderSettings.FREQ_MODES.length; i++) {
                if (MiraReaderSettings.FREQ_MODES[i][0] == settings.getFreqMode()) {
                    freqIdx = i;
                    break;
                }
            }
            spFrequency.setSelection(freqIdx);

            spRfLink.setSelection(Math.min(settings.getRfLink(),
                    MiraReaderSettings.RFLINK_LABELS.length - 1));
            spSession.setSelection(settings.getSession());
            spTarget.setSelection(settings.getTarget());
            spQValue.setSelection(settings.getQValue());

            swFastId.setChecked(settings.isFastId());
            swTagFocus.setChecked(settings.isTagFocus());
            swFastInventory.setChecked(settings.isFastInventory());

            spReadMode.setSelection(settings.getReadMode());
            if (tvReadModeHint != null) {
                tvReadModeHint.setText(
                        MiraReaderSettings.READ_MODE_HINTS[settings.getReadMode()]);
            }

            // ═══════════════════════════════════════════════════
            //  🔊 Sound & Vibration [v4.0]
            // ═══════════════════════════════════════════════════
            if (swSoundEnabled != null) {
                swSoundEnabled.setChecked(settings.isSoundEnabled());
            }
            if (seekVolume != null) {
                int vol = settings.getSoundVolume();
                seekVolume.setProgress(vol);
                if (tvVolumeValue != null) tvVolumeValue.setText(vol + "%");
            }
            if (swVibrateEnabled != null) {
                swVibrateEnabled.setChecked(settings.isVibrateEnabled());
            }
            if (seekVibrateMs != null) {
                int vib = settings.getVibrateMs();
                seekVibrateMs.setProgress(vib);
                if (tvVibrateMsValue != null) tvVibrateMsValue.setText(vib + " ms");
            }

            if (spToneNew != null)       spToneNew.setSelection(settings.getToneNewTag());
            if (spToneDuplicate != null) spToneDuplicate.setSelection(settings.getToneDuplicate());
            if (spToneError != null)     spToneError.setSelection(settings.getToneError());

            if (swSoundOnPos != null)       swSoundOnPos.setChecked(settings.isSoundOnPos());
            if (swSoundOnInventory != null) swSoundOnInventory.setChecked(settings.isSoundOnInventory());

            // ═══════════════════════════════════════════════════
            //  🚫 Advanced Reading [v4.0]
            // ═══════════════════════════════════════════════════
            if (swFilterDuplicates != null) {
                swFilterDuplicates.setChecked(settings.isFilterDuplicates());
            }
            if (seekDupFilter != null) {
                int flt = settings.getDuplicateFilterMs();
                seekDupFilter.setProgress(flt);
                if (tvDupFilterValue != null) tvDupFilterValue.setText(flt + " ms");
            }

            // ═══════════════════════════════════════════════════
            //  🌐 Global [v4.0]
            // ═══════════════════════════════════════════════════
            if (swKeepScreenOn != null) swKeepScreenOn.setChecked(settings.isKeepScreenOn());
            if (swLedFlash != null)     swLedFlash.setChecked(settings.isLedFlashEnabled());
            if (swToastEnabled != null) swToastEnabled.setChecked(settings.isToastEnabled());
            if (swAutoSaveLog != null)  swAutoSaveLog.setChecked(settings.isAutoSaveLog());

            // ═══════════════════════════════════════════════════
            //  🎯 Trigger Mode [v4.1]
            // ═══════════════════════════════════════════════════
            if (spTriggerMode != null) {
                int tm = settings.getTriggerMode();
                spTriggerMode.setSelection(tm);
                if (tvTriggerModeHint != null) {
                    tvTriggerModeHint.setText(MiraReaderSettings.TRIGGER_MODE_HINTS[tm]);
                }
            }

        } finally {
            isUpdatingUi.set(false);
        }
    }

    private void updateSummary() {
        if (tvSummary != null) tvSummary.setText(settings.getSummary());
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ تطبيق على القارئ
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  ✅ قراءة الحالة الحقيقية من العتاد
    // ═══════════════════════════════════════════════════════════

    private void readCurrent() {
        RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
        if (reader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل" : "⚠️ Reader not connected");
            return;
        }

        StringBuilder sb = new StringBuilder();

        try {
            int p = reader.getPower();
            sb.append("Power (real): ").append(p).append(" dBm\n");
        } catch (Exception e) {
            sb.append("Power (real): ?\n");
        }

        try {
            int fm = reader.getFrequencyMode();
            sb.append("Frequency (real): ").append(MiraReaderSettings.getFreqLabel(fm))
              .append("  [0x").append(Integer.toHexString(fm)).append("]\n");
        } catch (Exception e) {
            sb.append("Frequency (real): ?\n");
        }

        try {
            int rl = reader.getRFLink();
            String lbl = (rl >= 0 && rl < MiraReaderSettings.RFLINK_LABELS.length)
                    ? MiraReaderSettings.RFLINK_LABELS[rl] : "?";
            sb.append("RFLink (real): ").append(lbl).append("  [").append(rl).append("]\n");
        } catch (Exception e) {
            sb.append("RFLink (real): ?\n");
        }

        sb.append("\n── Saved in MIRA ──\n");
        sb.append("Power (saved): ").append(settings.getPowerDbm()).append(" dBm\n");
        sb.append("Frequency (saved): ")
          .append(MiraReaderSettings.getFreqLabel(settings.getFreqMode())).append("\n");
        sb.append("Session (saved): S").append(settings.getSession()).append("\n");
        sb.append("Target (saved): ")
          .append(settings.getTarget() == 0 ? "A" : "B").append("\n");
        sb.append("Q (saved): ").append(settings.getQValue()).append("\n");
        sb.append("FastID (saved): ").append(settings.isFastId() ? "ON" : "OFF").append("\n");
        sb.append("TagFocus (saved): ")
          .append(settings.isTagFocus() ? "ON" : "OFF").append("\n");

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

    // ═══════════════════════════════════════════════════════════
    //  📡 Live Test Operations
    // ═══════════════════════════════════════════════════════════

    private void toggleLiveTest() {
        if (isLiveTesting) {
            stopLiveTest();
        } else {
            startLiveTest();
        }
    }

    private void startLiveTest() {
        RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
        if (reader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل" : "⚠️ Reader not connected");
            return;
        }

        // ═══ 1) طبّق الإعدادات الحالية ═══
        try {
            MiraReaderSettings.ApplyResult r = settings.applyToReader(reader);
            Log.i(TAG, "Live test — settings applied: " + r);
        } catch (Exception e) {
            Log.e(TAG, "applyToReader failed", e);
        }

        // ═══ 2) ابدأ inventory ═══
        try {
            if (!reader.startInventoryTag()) {
                toast(isArabic ? "❌ فشل بدء القراءة" : "❌ Failed to start");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "startInventoryTag failed", e);
            toast(isArabic ? "❌ خطأ في القارئ" : "❌ Reader error");
            return;
        }

        // ═══ 3) حدّث الحالة ═══
        isLiveTesting = true;
        liveReadCount = 0;
        liveLastEpc = null;
        liveLastRssi = -100;
        liveTestStartTime = System.currentTimeMillis();

        if (btnLiveTestToggle != null) {
            btnLiveTestToggle.setText(isArabic ? "⏹️ إيقاف الاختبار" : "⏹️ Stop Test");
            btnLiveTestToggle.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFDC2626));
        }

        // ═══ 4) شغّل حلقة القراءة ═══
        liveReadThread = new Thread(this::liveReadLoop, "LiveTest");
        liveReadThread.setDaemon(true);
        liveReadThread.start();

        // ═══ 5) شغّل مُحدّث الواجهة ═══
        startLiveRateUpdater();

        Log.i(TAG, "✅ Live test started");
    }

    private void liveReadLoop() {
        while (isLiveTesting && !Thread.currentThread().isInterrupted()) {
            try {
                RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
                if (reader == null) { Thread.sleep(200); continue; }

                com.rscja.deviceapi.entity.UHFTAGInfo tag =
                        reader.readTagFromBuffer();

                if (tag != null) {
                    String epc = tag.getEPC();
                    String rssiStr = tag.getRssi();
                    int rssi = parseRssi(rssiStr);

                    if (epc != null && !epc.isEmpty()) {
                        liveReadCount++;
                        liveLastEpc = epc;
                        liveLastRssi = rssi;
                    }
                } else {
                    Thread.sleep(40);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "liveReadLoop error: " + e.getMessage());
                try { Thread.sleep(100); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Log.d(TAG, "Live read loop ended");
    }

    private int parseRssi(String rssiStr) {
        if (rssiStr == null || rssiStr.isEmpty()) return -50;
        try {
            String cleaned = rssiStr.replace("dBm", "")
                    .replace("dbm", "").replace("dB", "").trim();
            int rssi = Integer.parseInt(cleaned);
            return rssi > 0 ? -rssi : rssi;
        } catch (NumberFormatException e) {
            return -50;
        }
    }

    private void startLiveRateUpdater() {
        liveRateUpdater = new Runnable() {
            @Override
            public void run() {
                if (!isLiveTesting) return;
                updateLiveUi();
                liveUiHandler.postDelayed(this, 250);
            }
        };
        liveUiHandler.post(liveRateUpdater);
    }

    private void updateLiveUi() {
        if (!isAdded()) return;
        try {
            long elapsed = System.currentTimeMillis() - liveTestStartTime;
            double rate = elapsed > 0 ? (liveReadCount * 1000.0 / elapsed) : 0.0;

            if (tvLiveReadCount != null)
                tvLiveReadCount.setText(String.valueOf(liveReadCount));

            if (tvLiveRate != null)
                tvLiveRate.setText(String.format(Locale.US, "%.1f/s", rate));

            if (tvLiveLastEpc != null)
                tvLiveLastEpc.setText(liveLastEpc != null ? liveLastEpc : "—");

            if (tvLiveRssi != null) {
                if (liveLastEpc != null) {
                    tvLiveRssi.setText(liveLastRssi + " dBm");

                    int color;
                    if (liveLastRssi >= -45) color = 0xFF16A34A;
                    else if (liveLastRssi >= -65) color = 0xFFF59E0B;
                    else color = 0xFFDC2626;
                    tvLiveRssi.setTextColor(color);
                } else {
                    tvLiveRssi.setText("— dBm");
                    tvLiveRssi.setTextColor(0xFF6B7280);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "updateLiveUi error: " + e.getMessage());
        }
    }

    private void stopLiveTest() {
        if (!isLiveTesting) return;
        isLiveTesting = false;

        if (liveReadThread != null) {
            liveReadThread.interrupt();
            liveReadThread = null;
        }

        if (liveUiHandler != null && liveRateUpdater != null) {
            liveUiHandler.removeCallbacks(liveRateUpdater);
            liveRateUpdater = null;
        }

        // ✅ شغّل SDK في خيط خلفي — لا يوقف UI
        new Thread(() -> {
            try {
                RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
                if (reader != null) reader.stopInventory();
            } catch (Exception e) {
                Log.w(TAG, "stopInventory error: " + e.getMessage());
            }
        }, "StopLiveTest").start();

        if (btnLiveTestToggle != null) {
            btnLiveTestToggle.setText(isArabic
                    ? "▶️ ابدأ اختبار القراءة"
                    : "▶️ Start Test");
            btnLiveTestToggle.setBackgroundTintList(null);
        }

        Log.i(TAG, "🛑 Live test stopped. Total reads: " + liveReadCount);
        toast(isArabic
                ? "🛑 تم إيقاف الاختبار — " + liveReadCount + " قراءة"
                : "🛑 Test stopped — " + liveReadCount + " reads");
    }

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle — BottomNav / FAB
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onStart() {
        super.onStart();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                activity.setBottomNavVisible(false);

                View fab = activity.findViewById(R.id.fabActionCenter);
                if (fab != null) fab.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.w(TAG, "onStart error: " + e.getMessage());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                activity.setBottomNavVisible(true);

                View fab = activity.findViewById(R.id.fabActionCenter);
                if (fab != null) fab.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            Log.w(TAG, "onStop error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        if (isLiveTesting) {
            stopLiveTest();
        }
        super.onDestroyView();
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void showLoading(boolean show) {
        if (loading != null) loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * ✅ زر الرجوع — يعود لـ Dashboard
     */
    private void handleBack() {
        try {
            if (isLiveTesting) {
                stopLiveTest();
            }

            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                activity.setBottomNavVisible(true);

                if (activity.getNavigationManager() != null) {
                    activity.getNavigationManager().goToDashboard();
                } else {
                    activity.onBackPressed();
                }
            } else if (getActivity() != null) {
                getActivity().onBackPressed();
            }
        } catch (Exception e) {
            Log.e(TAG, "handleBack error: " + e.getMessage());
        }
    }
}
