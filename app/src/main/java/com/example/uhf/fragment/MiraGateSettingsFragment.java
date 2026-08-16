package com.example.uhf.fragment;

import android.content.Context;
import android.media.SoundPool;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.tools.SoundGenerator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MiraGateSettingsFragment extends Fragment {

    private static final String TAG = "MiraGateSettingsFragment";

    private MiraGatesSettings settings;
    private SoundPool soundPool;

    // UI
    private RecyclerView rvGates;
    private Spinner spSoundPass, spSoundDeny, spSoundAlarm;
    private SwitchMaterial switchVibration, switchFlash;
    private EditText etAlarmDuration;
    private SeekBar seekRfidPower;
    private TextView tvRfidPowerValue;
    private MaterialButton btnAddGate, btnTestSound, btnSaveSounds, btnSaveAll;

    // ✅ محول قائمة البوابات
    private GateAdapter gateAdapter;
    private final List<MiraGatesSettings.GateDevice> gatesList = new ArrayList<>();

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
        loadGatesFromLocal();
        loadGatesFromServer();
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

        rvGates.setLayoutManager(new LinearLayoutManager(getContext()));
        gateAdapter = new GateAdapter(gatesList, this::removeGate);
        rvGates.setAdapter(gateAdapter);
    }

    private void setupSpinners() {
        ArrayAdapter<String> passAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, getSoundNames(MiraGatesSettings.SoundOptions.PASS_SOUNDS));
        passAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSoundPass.setAdapter(passAdapter);

        ArrayAdapter<String> denyAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, getSoundNames(MiraGatesSettings.SoundOptions.DENY_SOUNDS));
        denyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSoundDeny.setAdapter(denyAdapter);

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
        String soundPass = settings.getSoundPass();
        String soundDeny = settings.getSoundDeny();
        String soundAlarm = settings.getSoundAlarm();

        spSoundPass.setSelection(findIndex(MiraGatesSettings.SoundOptions.PASS_SOUNDS, soundPass));
        spSoundDeny.setSelection(findIndex(MiraGatesSettings.SoundOptions.DENY_SOUNDS, soundDeny));
        spSoundAlarm.setSelection(findIndex(MiraGatesSettings.SoundOptions.ALARM_SOUNDS, soundAlarm));

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
            SoundGenerator.playSound(selectedSound);
            if (isAdded()) {
                Toast.makeText(getContext(), "🔊 تشغيل: " + getSoundDisplayName(selectedSound), Toast.LENGTH_SHORT).show();
            }
        });

        btnSaveSounds.setOnClickListener(v -> {
            String soundPass = MiraGatesSettings.SoundOptions.PASS_SOUNDS[spSoundPass.getSelectedItemPosition()];
            String soundDeny = MiraGatesSettings.SoundOptions.DENY_SOUNDS[spSoundDeny.getSelectedItemPosition()];
            String soundAlarm = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[spSoundAlarm.getSelectedItemPosition()];

            settings.setSoundPass(soundPass);
            settings.setSoundDeny(soundDeny);
            settings.setSoundAlarm(soundAlarm);

            if (isAdded()) {
                Toast.makeText(getContext(), "✅ تم حفظ الأصوات", Toast.LENGTH_SHORT).show();
            }
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
            
            String durationInput = etAlarmDuration.getText().toString().trim();
            if (!durationInput.isEmpty()) {
                try {
                    settings.setAlarmDuration(Integer.parseInt(durationInput));
                } catch (NumberFormatException e) {
                    settings.setAlarmDuration(10);
                }
            } else {
                settings.setAlarmDuration(10);
            }
            
            settings.setRfidPower(seekRfidPower.getProgress());

            if (isAdded()) {
                Toast.makeText(getContext(), "✅ تم حفظ جميع الإعدادات", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadGatesFromLocal() {
        gatesList.clear();
        gatesList.addAll(settings.getGates());
        gateAdapter.notifyDataSetChanged();
    }

    private void loadGatesFromServer() {
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = client.getGates();

                if (response.isSuccess && response.data != null) {
                    JSONArray gatesArray = response.data.optJSONArray("gates");
                    if (gatesArray == null) {
                        gatesArray = response.data.optJSONArray("data");
                    }

                    if (gatesArray != null && gatesArray.length() > 0) {
                        List<MiraGatesSettings.GateDevice> serverGates = new ArrayList<>();
                        
                        for (int i = 0; i < gatesArray.length(); i++) {
                            JSONObject gateJson = gatesArray.optJSONObject(i);
                            if (gateJson != null) {
                                MiraGatesSettings.GateDevice gate = new MiraGatesSettings.GateDevice();
                                gate.gateId = gateJson.optString("gate_id", "");
                                gate.gateName = gateJson.optString("name", gateJson.optString("gate_name", ""));
                                gate.location = gateJson.optString("location", "");
                                gate.deviceType = gateJson.optString("device_type", "chainway_c72");
                                gate.status = gateJson.optString("status", "offline");
                                gate.ipAddress = gateJson.optString("ip_address", "");
                                gate.port = gateJson.optInt("port", 8080);
                                serverGates.add(gate);
                            }
                        }

                        if (!serverGates.isEmpty()) {
                            settings.saveGates(serverGates);

                            if (isAdded()) {
                                requireActivity().runOnUiThread(() -> {
                                    loadGatesFromLocal();
                                    Toast.makeText(getContext(), "✅ تم تحميل " + serverGates.size() + " بوابة من السيرفر", Toast.LENGTH_SHORT).show();
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadGatesFromServer error: " + e.getMessage());
            }
        }).start();
    }

    private void showAddGateDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("➕ إضافة بوابة جديدة");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        final EditText etGateName = new EditText(requireContext());
        etGateName.setHint("اسم البوابة (مثال: البوابة الرئيسية)");
        etGateName.setSingleLine(true);
        layout.addView(etGateName);

        final EditText etLocation = new EditText(requireContext());
        etLocation.setHint("الموقع (مثال: المدخل الرئيسي)");
        etLocation.setSingleLine(true);
        layout.addView(etLocation);

        final EditText etIpAddress = new EditText(requireContext());
        etIpAddress.setHint("عنوان IP (اختياري)");
        etIpAddress.setSingleLine(true);
        etIpAddress.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        layout.addView(etIpAddress);

        builder.setView(layout);
        builder.setPositiveButton("إضافة", (dialog, which) -> {
            String gateName = etGateName.getText().toString().trim();
            String location = etLocation.getText().toString().trim();
            String ipAddress = etIpAddress.getText().toString().trim();

            if (!gateName.isEmpty()) {
                MiraGatesSettings.GateDevice gate = new MiraGatesSettings.GateDevice();
                gate.gateId = "gate_" + System.currentTimeMillis();
                gate.gateName = gateName;
                gate.location = location;
                gate.deviceType = "chainway_c72";
                gate.status = "offline";
                gate.ipAddress = ipAddress;
                gate.port = 8080;

                settings.addGate(gate);
                loadGatesFromLocal();

                syncGateToServer(gate);

                Toast.makeText(getContext(), "✅ تمت إضافة البوابة", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "⚠️ اسم البوابة مطلوب", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    private void syncGateToServer(MiraGatesSettings.GateDevice gate) {
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                JSONObject payload = new JSONObject();
                payload.put("gate_id", gate.gateId);
                payload.put("name", gate.gateName);
                payload.put("location", gate.location);
                payload.put("device_type", gate.deviceType);
                if (gate.ipAddress != null && !gate.ipAddress.isEmpty()) {
                    payload.put("ip_address", gate.ipAddress);
                }
                payload.put("port", gate.port);

                MiraApiClient.ApiResponse response = client.createGate(payload);

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (response.isSuccess) {
                            Toast.makeText(getContext(), "✅ تمت المزامنة مع السيرفر", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), "⚠️ حفظ محلي فقط (سيرفر غير متاح)", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "syncGateToServer error: " + e.getMessage());
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "💾 حفظ محلي فقط", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }).start();
    }

    private void removeGate(int position) {
        if (position >= 0 && position < gatesList.size()) {
            MiraGatesSettings.GateDevice gate = gatesList.get(position);
            settings.removeGate(gate.gateId);
            loadGatesFromLocal();
            if (isAdded()) {
                Toast.makeText(getContext(), "🗑 تم حذف: " + gate.gateName, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    private class GateAdapter extends RecyclerView.Adapter<GateAdapter.GateViewHolder> {

        interface OnGateRemoveListener {
            void onRemove(int position);
        }

        private final List<MiraGatesSettings.GateDevice> gates;
        private final OnGateRemoveListener removeListener;

        GateAdapter(List<MiraGatesSettings.GateDevice> gates, OnGateRemoveListener listener) {
            this.gates = gates;
            this.removeListener = listener;
        }

        @NonNull
        @Override
        public GateViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_gate_device, parent, false);
            return new GateViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull GateViewHolder holder, int position) {
            MiraGatesSettings.GateDevice gate = gates.get(position);

            holder.tvGateName.setText(gate.gateName);
            holder.tvGateLocation.setText("📍 " + (gate.location != null && !gate.location.isEmpty() ? gate.location : "غير محدد"));
            
            if ("online".equals(gate.status)) {
                holder.tvGateStatus.setText("🟢 متصل");
                holder.tvGateStatus.setTextColor(0xFF2E7D32);
            } else {
                holder.tvGateStatus.setText("⚪ غير متصل");
                holder.tvGateStatus.setTextColor(0xFF9E9E9E);
            }

            holder.btnRemove.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    removeListener.onRemove(pos);
                }
            });
        }

        @Override
        public int getItemCount() {
            return gates.size();
        }

        class GateViewHolder extends RecyclerView.ViewHolder {
            TextView tvGateName, tvGateLocation, tvGateStatus;
            View btnRemove;

            GateViewHolder(@NonNull View itemView) {
                super(itemView);
                tvGateName = itemView.findViewById(R.id.tvGateName);
                tvGateLocation = itemView.findViewById(R.id.tvGateLocation);
                tvGateStatus = itemView.findViewById(R.id.tvGateStatus);
                btnRemove = itemView.findViewById(R.id.btnRemoveGate);
            }
        }
    }
}
