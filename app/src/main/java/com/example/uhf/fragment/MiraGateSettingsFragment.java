package com.example.uhf.fragment;

import android.content.Context;
import android.media.SoundPool;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.MiraGatesSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

public class MiraGateSettingsFragment extends Fragment {

    private MiraGatesSettings settings;
    private SoundPool soundPool;
    private int currentSoundId = -1;

    // UI
    private RecyclerView rvGates;
    private Spinner spSoundPass, spSoundDeny, spSoundAlarm;
    private SwitchMaterial switchVibration, switchFlash;
    private EditText etAlarmDuration;
    private SeekBar seekRfidPower;
    private TextView tvRfidPowerValue;
    private MaterialButton btnAddGate, btnTestSound, btnSaveSounds, btnSaveAll;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_gate_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = requireContext();
        settings = MiraGatesSettings.getInstance(ctx);

        soundPool = new SoundPool.Builder().setMaxStreams(3).build();

        initViews(view);
        setupSpinners();
        loadSettings();
        setupListeners();
    }

    private void initViews(View view) {
        rvGates = view.findViewById(R.id.rvGates);
        spSoundPass = view.findViewById(R.id.spSoundPass);
        spSoundDeny = view.findViewById(R.id.spSoundDeny);
        spSoundAlarm = view.findViewById(R.id.spSoundAlarm);
        switchVibration = view.findViewById(R.id.switchVibration);
        switchFlash = view.findViewById(R.id.switchFlash);
        etAlarmDuration = view.findViewById(R.id.etAlarmDuration);
        seekRfidPower = view.findViewById(R.id.seekRfidPower);
        tvRfidPowerValue = view.findViewById(R.id.tvRfidPowerValue);
        btnAddGate = view.findViewById(R.id.btnAddGate);
        btnTestSound = view.findViewById(R.id.btnTestSound);
        btnSaveSounds = view.findViewById(R.id.btnSaveSounds);
        btnSaveAll = view.findViewById(R.id.btnSaveAll);

        // إعداد قائمة البوابات
        rvGates.setLayoutManager(new LinearLayoutManager(getContext()));
    }

    private void setupSpinners() {
        // صوت النجاح
        ArrayAdapter<String> passAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, getSoundNames(MiraGatesSettings.SoundOptions.PASS_SOUNDS));
        passAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSoundPass.setAdapter(passAdapter);

        // صوت الرفض
        ArrayAdapter<String> denyAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, getSoundNames(MiraGatesSettings.SoundOptions.DENY_SOUNDS));
        denyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSoundDeny.setAdapter(denyAdapter);

        // صوت الإنذار
        ArrayAdapter<String> alarmAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, getSoundNames(MiraGatesSettings.SoundOptions.ALARM_SOUNDS));
        alarmAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSoundAlarm.setAdapter(alarmAdapter);
    }

    private String[] getSoundNames(String[] soundKeys) {
        String[] names = new String[soundKeys.length];
        for (int i = 0; i < soundKeys.length; i++) {
            names[i] = getSoundDisplayName(soundKeys[i]);
        }
        return names;
    }

    private String getSoundDisplayName(String soundKey) {
        switch (soundKey) {
            case "beep_success": return "🔔 نجاح قصير";
            case "beep_soft": return "🔔 ناعم";
            case "beep_double": return "🔔 مزدوج";
            case "chime_elegant": return "🎵 نغمة أنيقة";
            case "silent": return "🔇 بدون صوت";
            case "beep_warning": return "⚠️ تحذير";
            case "beep_error": return "❌ خطأ";
            case "alarm_soft": return "🚨 إنذار خفيف";
            case "buzz_double": return "📳 طنين مزدوج";
            case "alarm_sold_item": return "🚨 إنذار مباع";
            case "alarm_breach_security": return "🚨 اختراق أمني";
            case "alarm_siren_strong": return "🚨 صفارة قوية";
            case "alarm_police": return "🚨 صفارة شرطة";
            case "alarm_buzzer_loud": return "🚨 طنين عالي";
            case "alarm_klaxon": return "🚨 كلوكسون";
            default: return soundKey;
        }
    }

    private void loadSettings() {
        // تحميل الأصوات
        String soundPass = settings.getSoundPass();
        String soundDeny = settings.getSoundDeny();
        String soundAlarm = settings.getSoundAlarm();

        spSoundPass.setSelection(findIndex(MiraGatesSettings.SoundOptions.PASS_SOUNDS, soundPass));
        spSoundDeny.setSelection(findIndex(MiraGatesSettings.SoundOptions.DENY_SOUNDS, soundDeny));
        spSoundAlarm.setSelection(findIndex(MiraGatesSettings.SoundOptions.ALARM_SOUNDS, soundAlarm));

        // تحميل الإعدادات الأخرى
        switchVibration.setChecked(settings.isVibrationEnabled());
        switchFlash.setChecked(settings.isFlashEnabled());
        etAlarmDuration.setText(String.valueOf(settings.getAlarmDuration()));
        seekRfidPower.setProgress(settings.getRfidPower());
        tvRfidPowerValue.setText(settings.getRfidPower() + " dBm");
    }

    private int findIndex(String[] array, String value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) return i;
        }
        return 0;
    }

    private void setupListeners() {
        btnAddGate.setOnClickListener(v -> showAddGateDialog());
        
        btnTestSound.setOnClickListener(v -> {
            String selectedSound = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[spSoundAlarm.getSelectedItemPosition()];
            playSound(selectedSound);
        });

        btnSaveSounds.setOnClickListener(v -> {
            String soundPass = MiraGatesSettings.SoundOptions.PASS_SOUNDS[spSoundPass.getSelectedItemPosition()];
            String soundDeny = MiraGatesSettings.SoundOptions.DENY_SOUNDS[spSoundDeny.getSelectedItemPosition()];
            String soundAlarm = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[spSoundAlarm.getSelectedItemPosition()];

            settings.setSoundPass(soundPass);
            settings.setSoundDeny(soundDeny);
            settings.setSoundAlarm(soundAlarm);

            Toast.makeText(getContext(), "✅ تم حفظ الأصوات", Toast.LENGTH_SHORT).show();
        });

        seekRfidPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRfidPowerValue.setText(progress + " dBm");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnSaveAll.setOnClickListener(v -> {
            settings.setVibrationEnabled(switchVibration.isChecked());
            settings.setFlashEnabled(switchFlash.isChecked());
            settings.setAlarmDuration(Integer.parseInt(etAlarmDuration.getText().toString()));
            settings.setRfidPower(seekRfidPower.getProgress());

            Toast.makeText(getContext(), "✅ تم حفظ جميع الإعدادات", Toast.LENGTH_SHORT).show();
        });
    }

    private void playSound(String soundName) {
        int soundId = MiraGatesSettings.SoundOptions.getSoundResourceId(requireContext(), soundName);
        if (soundId > 0) {
            currentSoundId = soundPool.load(requireContext(), soundId, 1);
            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) {
                    pool.play(sampleId, 1.0f, 1.0f, 1, 0, 1.0f);
                }
            });
        } else {
            Toast.makeText(getContext(), "🔇 بدون صوت", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAddGateDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("➕ إضافة بوابة جديدة");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        final EditText etGateName = new EditText(requireContext());
        etGateName.setHint("اسم البوابة (مثال: البوابة الرئيسية)");
        layout.addView(etGateName);

        final EditText etLocation = new EditText(requireContext());
        etLocation.setHint("الموقع (مثال: المدخل الرئيسي)");
        layout.addView(etLocation);

        builder.setView(layout);
        builder.setPositiveButton("إضافة", (dialog, which) -> {
            String gateName = etGateName.getText().toString().trim();
            String location = etLocation.getText().toString().trim();

            if (!gateName.isEmpty()) {
                MiraGatesSettings.GateDevice gate = new MiraGatesSettings.GateDevice();
                gate.gateId = "gate_" + System.currentTimeMillis();
                gate.gateName = gateName;
                gate.location = location;
                gate.deviceType = "chainway_c72";
                gate.status = "offline";

                settings.addGate(gate);
                Toast.makeText(getContext(), "✅ تمت إضافة البوابة", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}
