package com.example.uhf.fragment;

import android.content.Context;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SoundGenerator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class MiraGateSettingsFragment extends Fragment {

    private static final String TAG = "MiraGateSettingsFragment";
    private static final long STATUS_REFRESH_INTERVAL = 10000; // 10 ثواني

    private MiraGatesSettings settings;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;
    private SoundPool soundPool;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private ExecutorService executor;
    private static final Object EXECUTOR_LOCK = new Object();

    private RecyclerView rvGates;
    private AutoCompleteTextView spSoundPass, spSoundDeny, spSoundAlarm;
    private SwitchMaterial switchVibration, switchFlash;
    private EditText etAlarmDuration;
    private SeekBar seekRfidPower;
    private TextView tvRfidPowerValue;
    private TextView tvTotalGates, tvOnlineGates, tvOfflineGates, tvLastEvent;
    private MaterialButton btnAddGate, btnTestSound, btnSaveSounds, btnSaveAll, btnRefreshStatus;

    private GateAdapter gateAdapter;
    private final List<MiraGatesSettings.GateDevice> gatesList = new ArrayList<>();
    private final List<BranchItem> branchesList = new ArrayList<>();
    private Runnable statusRefreshRunnable;
    private boolean isArabic = true;

    // متغيّرات الاحتفاظ بالقيم المختارة للأصوات
    private int selectedPassPos = 0;
    private int selectedDenyPos = 0;
    private int selectedAlarmPos = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_gate_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = getContext();
        if (ctx == null) return;

        settings = MiraGatesSettings.getInstance(ctx);
        permissionManager = PermissionManager.getInstance(ctx);
        branchesManager = MiraBranchesManager.getInstance(ctx);
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        soundPool = new SoundPool.Builder().setMaxStreams(3).build();

        initViews(view);
        setupSpinners();
        loadSettings();
        setupListeners();
        applyPermissions();
        loadBranches();
        loadGatesFromLocal();
        loadGatesFromServer();
        loadGateStats();
        startStatusRefresh();
    }

    private ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown() || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor();
            }
            return executor;
        }
    }

    private void executeTask(Runnable task) {
        try {
            ExecutorService exec = getExecutor();
            if (!exec.isShutdown() && !exec.isTerminated()) {
                exec.execute(task);
            }
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Failed to execute task: " + e.getMessage());
        }
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
        btnRefreshStatus = view.findViewById(R.id.btnRefreshStatus);
        tvTotalGates = view.findViewById(R.id.tvTotalGates);
        tvOnlineGates = view.findViewById(R.id.tvOnlineGates);
        tvOfflineGates = view.findViewById(R.id.tvOfflineGates);
        tvLastEvent = view.findViewById(R.id.tvLastEvent);

        if (rvGates != null) {
            rvGates.setLayoutManager(new LinearLayoutManager(getContext()));
            gateAdapter = new GateAdapter(gatesList, this::removeGate, this::toggleGateStatus, this::showEditGateDialog);
            rvGates.setAdapter(gateAdapter);
        }
    }

    private void applyPermissions() {
        if (permissionManager == null) return;

        boolean canManageGates = permissionManager.isAdmin() || 
                                  permissionManager.isBranchManager() ||
                                  permissionManager.canEdit("gates");
        
        if (btnAddGate != null) btnAddGate.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
        if (btnSaveSounds != null) btnSaveSounds.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
        if (btnSaveAll != null) btnSaveAll.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
    }

    private void loadBranches() {
        int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
        String activeBranchName = branchesManager != null ? branchesManager.getActiveBranchName() : "الفرع الرئيسي";
        
        branchesList.clear();
        branchesList.add(new BranchItem(activeBranchId, activeBranchName));

        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;
                
                MiraApiClient.ApiResponse response = client.getBranches();
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("branches");
                    if (arr != null && arr.length() > 0) {
                        List<BranchItem> serverBranches = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject b = arr.optJSONObject(i);
                            if (b != null) {
                                int id = b.optInt("id", 0);
                                String name = b.optString("branch_name_ar", b.optString("branch_name", ""));
                                if (id > 0 && !name.isEmpty()) {
                                    serverBranches.add(new BranchItem(id, name));
                                }
                            }
                        }
                        
                        if (!serverBranches.isEmpty()) {
                            mainHandler.post(() -> {
                                if (isAdded()) {
                                    branchesList.clear();
                                    branchesList.addAll(serverBranches);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadBranches error: " + e.getMessage());
            }
        });
    }

    private void startStatusRefresh() {
        statusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded()) {
                    loadGateStats();
                    mainHandler.postDelayed(this, STATUS_REFRESH_INTERVAL);
                }
            }
        };
        mainHandler.postDelayed(statusRefreshRunnable, STATUS_REFRESH_INTERVAL);
    }

    private void loadGateStats() {
        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;

                MiraApiClient.ApiResponse response = client.getGatesStats();
                
                if (response != null && response.isSuccess && response.data != null) {
                    mainHandler.post(() -> {
                        if (isAdded()) {
                            updateGateStats(response.data);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "loadGateStats error: " + e.getMessage());
            }
        });
    }

    private void setupSpinners() {
        Context context = getContext();
        if (context == null) return;

        if (spSoundPass != null) {
            String[] passNames = getSoundNames(MiraGatesSettings.SoundOptions.PASS_SOUNDS);
            ArrayAdapter<String> passAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_dropdown_item_1line, passNames);
            spSoundPass.setAdapter(passAdapter);
            spSoundPass.setOnItemClickListener((parent, view, position, id) -> selectedPassPos = position);
        }

        if (spSoundDeny != null) {
            String[] denyNames = getSoundNames(MiraGatesSettings.SoundOptions.DENY_SOUNDS);
            ArrayAdapter<String> denyAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_dropdown_item_1line, denyNames);
            spSoundDeny.setAdapter(denyAdapter);
            spSoundDeny.setOnItemClickListener((parent, view, position, id) -> selectedDenyPos = position);
        }

        if (spSoundAlarm != null) {
            String[] alarmNames = getSoundNames(MiraGatesSettings.SoundOptions.ALARM_SOUNDS);
            ArrayAdapter<String> alarmAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_dropdown_item_1line, alarmNames);
            spSoundAlarm.setAdapter(alarmAdapter);
            spSoundAlarm.setOnItemClickListener((parent, view, position, id) -> selectedAlarmPos = position);
        }
    }

    private String[] getSoundNames(String[] soundKeys) {
        if (soundKeys == null) return new String[0];
        String[] names = new String[soundKeys.length];
        for (int i = 0; i < soundKeys.length; i++) {
            names[i] = getSoundDisplayName(soundKeys[i]);
        }
        return names;
    }

    private String getSoundDisplayName(String soundKey) {
        if (soundKey == null) return "";
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
        if (settings == null) return;

        String soundPass = settings.getSoundPass();
        String soundDeny = settings.getSoundDeny();
        String soundAlarm = settings.getSoundAlarm();

        selectedPassPos = findIndex(MiraGatesSettings.SoundOptions.PASS_SOUNDS, soundPass);
        selectedDenyPos = findIndex(MiraGatesSettings.SoundOptions.DENY_SOUNDS, soundDeny);
        selectedAlarmPos = findIndex(MiraGatesSettings.SoundOptions.ALARM_SOUNDS, soundAlarm);

        if (spSoundPass != null) {
            spSoundPass.setText(getSoundDisplayName(soundPass), false);
        }
        if (spSoundDeny != null) {
            spSoundDeny.setText(getSoundDisplayName(soundDeny), false);
        }
        if (spSoundAlarm != null) {
            spSoundAlarm.setText(getSoundDisplayName(soundAlarm), false);
        }

        if (switchVibration != null) switchVibration.setChecked(settings.isVibrationEnabled());
        if (switchFlash != null) switchFlash.setChecked(settings.isFlashEnabled());
        if (etAlarmDuration != null) etAlarmDuration.setText(String.valueOf(settings.getAlarmDuration()));
        if (seekRfidPower != null) seekRfidPower.setProgress(settings.getRfidPower());
        if (tvRfidPowerValue != null) tvRfidPowerValue.setText(settings.getRfidPower() + " dBm");
    }

    private int findIndex(String[] array, String value) {
        if (array == null || value == null) return 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) return i;
        }
        return 0;
    }

    private void setupListeners() {
        if (btnAddGate != null) {
            btnAddGate.setOnClickListener(v -> showAddGateDialog());
        }

        if (btnTestSound != null) {
            btnTestSound.setOnClickListener(v -> {
                if (selectedAlarmPos >= 0 && selectedAlarmPos < MiraGatesSettings.SoundOptions.ALARM_SOUNDS.length) {
                    String selectedSound = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[selectedAlarmPos];
                    SoundGenerator.playSound(selectedSound);
                    if (isAdded()) {
                        Toast.makeText(getContext(), "🔊 تشغيل: " + getSoundDisplayName(selectedSound), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnRefreshStatus != null) {
            btnRefreshStatus.setOnClickListener(v -> loadGateStats());
        }

        if (btnSaveSounds != null) {
            btnSaveSounds.setOnClickListener(v -> {
                if (settings == null) return;
                
                if (selectedPassPos >= 0 && selectedPassPos < MiraGatesSettings.SoundOptions.PASS_SOUNDS.length) {
                    settings.setSoundPass(MiraGatesSettings.SoundOptions.PASS_SOUNDS[selectedPassPos]);
                }
                if (selectedDenyPos >= 0 && selectedDenyPos < MiraGatesSettings.SoundOptions.DENY_SOUNDS.length) {
                    settings.setSoundDeny(MiraGatesSettings.SoundOptions.DENY_SOUNDS[selectedDenyPos]);
                }
                if (selectedAlarmPos >= 0 && selectedAlarmPos < MiraGatesSettings.SoundOptions.ALARM_SOUNDS.length) {
                    settings.setSoundAlarm(MiraGatesSettings.SoundOptions.ALARM_SOUNDS[selectedAlarmPos]);
                }

                if (isAdded()) {
                    Toast.makeText(getContext(), "✅ تم حفظ الأصوات", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (seekRfidPower != null) {
            seekRfidPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (tvRfidPowerValue != null) {
                        tvRfidPowerValue.setText(progress + " dBm");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (btnSaveAll != null) {
            btnSaveAll.setOnClickListener(v -> {
                if (settings == null) return;

                if (switchVibration != null) settings.setVibrationEnabled(switchVibration.isChecked());
                if (switchFlash != null) settings.setFlashEnabled(switchFlash.isChecked());
                
                if (etAlarmDuration != null) {
                    String durationInput = etAlarmDuration.getText().toString().trim();
                    int duration = 10;
                    try {
                        if (!durationInput.isEmpty()) duration = Integer.parseInt(durationInput);
                    } catch (NumberFormatException ignored) {}
                    settings.setAlarmDuration(duration);
                }
                
                if (seekRfidPower != null) {
                    settings.setRfidPower(seekRfidPower.getProgress());
                }

                if (isAdded()) {
                    Toast.makeText(getContext(), "✅ تم حفظ جميع الإعدادات العامة", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void loadGatesFromLocal() {
        if (settings == null) return;
        gatesList.clear();
        gatesList.addAll(settings.getGates());
        if (gateAdapter != null) {
            gateAdapter.notifyDataSetChanged();
        }
    }

    private void showAddGateDialog() {
        Context context = getContext();
        if (context == null) return;

        if (permissionManager != null && !(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
            Toast.makeText(context, "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            return;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("➕ إضافة بوابة جديدة");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        final EditText etGateName = new EditText(context);
        etGateName.setHint("اسم البوابة (مثال: البوابة الرئيسية)");
        etGateName.setSingleLine(true);
        layout.addView(etGateName);

        final EditText etLocation = new EditText(context);
        etLocation.setHint("الموقع (مثال: المدخل الرئيسي)");
        etLocation.setSingleLine(true);
        layout.addView(etLocation);

        TextView tvBranchLabel = new TextView(context);
        tvBranchLabel.setText("🏢 الفرع التابع له:");
        tvBranchLabel.setTextSize(14);
        tvBranchLabel.setPadding(0, 20, 0, 5);
        layout.addView(tvBranchLabel);

        final Spinner spBranch = new Spinner(context);
        ArrayAdapter<BranchItem> branchAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, branchesList);
        branchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBranch.setAdapter(branchAdapter);
        layout.addView(spBranch);

        final EditText etIpAddress = new EditText(context);
        etIpAddress.setHint("عنوان IP (اختياري)");
        etIpAddress.setSingleLine(true);
        layout.addView(etIpAddress);

        builder.setView(layout);
        builder.setPositiveButton("إضافة", (dialog, which) -> {
            String gateName = etGateName.getText().toString().trim();
            String location = etLocation.getText().toString().trim();
            String ipAddress = etIpAddress.getText().toString().trim();
            
            BranchItem selectedBranch = (BranchItem) spBranch.getSelectedItem();
            int branchId = selectedBranch != null ? selectedBranch.id : 1;
            String branchName = selectedBranch != null ? selectedBranch.name : "الفرع الرئيسي";

            if (!gateName.isEmpty()) {
                MiraGatesSettings.GateDevice gate = new MiraGatesSettings.GateDevice();
                gate.gateId = "gate_" + System.currentTimeMillis();
                gate.gateName = gateName;
                gate.location = location;
                gate.deviceType = "chainway_c72";
                gate.status = "offline";
                gate.ipAddress = ipAddress;
                gate.port = 8080;
                gate.branchId = branchId;
                gate.branchName = branchName;
                
                if (settings != null) {
                    gate.rfidPower = settings.getRfidPower();
                    gate.vibrationEnabled = settings.isVibrationEnabled();
                    gate.flashEnabled = settings.isFlashEnabled();
                    gate.soundPass = settings.getSoundPass();
                    gate.soundDeny = settings.getSoundDeny();
                    gate.soundAlarm = settings.getSoundAlarm();
                    gate.alarmDuration = settings.getAlarmDuration();

                    settings.addGate(gate);
                }
                
                loadGatesFromLocal();
                syncGateToServer(gate, branchId);

                if (isAdded()) {
                    Toast.makeText(getContext(), "✅ تمت إضافة البوابة لفرع: " + branchName, Toast.LENGTH_SHORT).show();
                }
            } else {
                if (isAdded()) {
                    Toast.makeText(getContext(), "⚠️ اسم البوابة مطلوب", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    private void showEditGateDialog(int position) {
        Context context = getContext();
        if (context == null) return;

        if (permissionManager != null && !(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
            Toast.makeText(context, "⛔ غير مصرح بالتعديل", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (position < 0 || position >= gatesList.size()) return;
        MiraGatesSettings.GateDevice existingGate = gatesList.get(position);
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("✏️ تعديل بوابة: " + existingGate.gateName);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        final EditText etGateName = new EditText(context);
        etGateName.setHint("اسم البوابة");
        etGateName.setSingleLine(true);
        etGateName.setText(existingGate.gateName);
        layout.addView(etGateName);

        final EditText etLocation = new EditText(context);
        etLocation.setHint("الموقع");
        etLocation.setSingleLine(true);
        etLocation.setText(existingGate.location);
        layout.addView(etLocation);

        TextView tvBranchLabel = new TextView(context);
        tvBranchLabel.setText("🏢 الفرع التابع له:");
        tvBranchLabel.setTextSize(14);
        tvBranchLabel.setPadding(0, 20, 0, 5);
        layout.addView(tvBranchLabel);

        final Spinner spBranch = new Spinner(context);
        ArrayAdapter<BranchItem> branchAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, branchesList);
        branchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBranch.setAdapter(branchAdapter);
        
        for (int i = 0; i < branchesList.size(); i++) {
            if (branchesList.get(i).id == existingGate.branchId) {
                spBranch.setSelection(i);
                break;
            }
        }
        layout.addView(spBranch);

        final EditText etIpAddress = new EditText(context);
        etIpAddress.setHint("عنوان IP");
        etIpAddress.setSingleLine(true);
        etIpAddress.setText(existingGate.ipAddress);
        layout.addView(etIpAddress);

        builder.setView(layout);
        builder.setPositiveButton("حفظ", (dialog, which) -> {
            String gateName = etGateName.getText().toString().trim();
            String location = etLocation.getText().toString().trim();
            String ipAddress = etIpAddress.getText().toString().trim();
            
            BranchItem selectedBranch = (BranchItem) spBranch.getSelectedItem();
            int branchId = selectedBranch != null ? selectedBranch.id : existingGate.branchId;
            String branchName = selectedBranch != null ? selectedBranch.name : existingGate.branchName;

            if (!gateName.isEmpty()) {
                existingGate.gateName = gateName;
                existingGate.location = location;
                existingGate.ipAddress = ipAddress;
                existingGate.branchId = branchId;
                existingGate.branchName = branchName;
                
                if (settings != null) {
                    settings.updateGate(existingGate.gateId, existingGate);
                }
                
                loadGatesFromLocal();
                updateGateOnServer(existingGate);

                if (isAdded()) {
                    Toast.makeText(getContext(), "✅ تم تحديث البوابة", Toast.LENGTH_SHORT).show();
                }
            } else {
                if (isAdded()) {
                    Toast.makeText(getContext(), "⚠️ اسم البوابة مطلوب", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    private void syncGateToServer(MiraGatesSettings.GateDevice gate, int branchId) {
        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;

                JSONObject payload = new JSONObject();
                payload.put("gate_id", gate.gateId);
                payload.put("gate_name", gate.gateName);
                payload.put("gate_location", gate.location);
                payload.put("device_type", gate.deviceType);
                payload.put("ip_address", gate.ipAddress);
                payload.put("port", gate.port);
                payload.put("branch_id", branchId);
                payload.put("rfid_power", gate.rfidPower);
                payload.put("vibration_enabled", gate.vibrationEnabled);
                payload.put("flash_enabled", gate.flashEnabled);
                payload.put("sound_pass", gate.soundPass);
                payload.put("sound_deny", gate.soundDeny);
                payload.put("sound_alarm", gate.soundAlarm);
                payload.put("alarm_duration", gate.alarmDuration);

                MiraApiClient.ApiResponse response = client.createGate(payload);

                mainHandler.post(() -> {
                    if (isAdded()) {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), "✅ تمت المزامنة مع السيرفر", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), "⚠️ تم الحفظ محلياً", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "syncGateToServer error: " + e.getMessage());
            }
        });
    }

    private void updateGateOnServer(MiraGatesSettings.GateDevice gate) {
        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;
                
                JSONObject payload = new JSONObject();
                payload.put("gate_id", gate.gateId);
                payload.put("gate_name", gate.gateName);
                payload.put("gate_location", gate.location);
                payload.put("device_type", gate.deviceType);
                payload.put("ip_address", gate.ipAddress);
                payload.put("port", gate.port);
                payload.put("branch_id", gate.branchId);

                MiraApiClient.ApiResponse response = client.postWithAuth("/gates/update", payload);

                mainHandler.post(() -> {
                    if (isAdded()) {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), "✅ تمت مزامنة التعديل", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateGateOnServer error: " + e.getMessage());
            }
        });
    }

    private void removeGate(int position) {
        if (permissionManager != null && !(permissionManager.canDelete("gates") || permissionManager.isAdmin())) {
            if (isAdded()) {
                Toast.makeText(getContext(), "⛔ غير مصرح بالحذف", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        
        if (position >= 0 && position < gatesList.size()) {
            MiraGatesSettings.GateDevice gate = gatesList.get(position);
            if (settings != null) {
                settings.removeGate(gate.gateId);
            }
            loadGatesFromLocal();
            if (isAdded()) {
                Toast.makeText(getContext(), "🗑 تم حذف: " + gate.gateName, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void toggleGateStatus(int position, boolean open) {
        if (permissionManager != null && !permissionManager.isAdmin() && !permissionManager.isBranchManager()) {
            if (isAdded()) {
                Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        
        if (position >= 0 && position < gatesList.size()) {
            MiraGatesSettings.GateDevice gate = gatesList.get(position);
            
            executeTask(() -> {
                try {
                    Context context = getContext();
                    if (context == null) return;
                    
                    MiraApiClient client = MiraApiClient.getInstance(context);
                    if (client == null) return;

                    JSONObject payload = new JSONObject();
                    payload.put("gate_id", gate.gateId);
                    payload.put("command", open ? "ARM" : "DISARM");
                    payload.put("branch_id", gate.branchId);
                    
                    MiraApiClient.ApiResponse response = client.postWithAuth("/gate-command", payload);
                    
                    mainHandler.post(() -> {
                        if (isAdded()) {
                            if (response != null && response.isSuccess) {
                                gate.status = open ? "online" : "offline";
                                if (gateAdapter != null) gateAdapter.notifyItemChanged(position);
                                
                                Toast.makeText(getContext(), 
                                    open ? "🚪 تم تفعيل: " + gate.gateName : "🔒 تم إيقاف: " + gate.gateName, 
                                    Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(getContext(), "❌ فشل تغيير حالة البوابة", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (isAdded()) {
                            Toast.makeText(getContext(), "❌ خطأ في الاتصال", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        }
    }

    private void updateGateStats(JSONObject data) {
        try {
            if (data == null) return;

            JSONObject statsObj = data.optJSONObject("data");
            if (statsObj == null) statsObj = data;
            
            JSONObject stats = statsObj.optJSONObject("stats");
            if (stats == null) stats = statsObj.optJSONObject("summary");
            if (stats == null) stats = statsObj;
            
            int total = stats.optInt("total_gates", stats.optInt("total", gatesList.size()));
            int online = stats.optInt("online_gates", stats.optInt("online", 0));
            int offline = stats.optInt("offline_gates", stats.optInt("offline", 0));
            
            if (tvTotalGates != null) tvTotalGates.setText(String.valueOf(total));
            if (tvOnlineGates != null) tvOnlineGates.setText(String.valueOf(online));
            if (tvOfflineGates != null) tvOfflineGates.setText(String.valueOf(offline));
            
            JSONArray gatesArray = statsObj.optJSONArray("gates");
            if (gatesArray == null) gatesArray = stats.optJSONArray("gates");
            if (gatesArray == null) gatesArray = statsObj.optJSONArray("items");
            
            if (gatesArray != null) {
                for (int i = 0; i < gatesArray.length(); i++) {
                    JSONObject gateJson = gatesArray.optJSONObject(i);
                    if (gateJson != null) {
                        String gateId = gateJson.optString("gate_id", gateJson.optString("id", ""));
                        String status = gateJson.optString("status", "offline");
                        
                        for (MiraGatesSettings.GateDevice gate : gatesList) {
                            if (gate.gateId != null && gate.gateId.equals(gateId)) {
                                gate.status = status;
                                gate.passCount = gateJson.optInt("pass_count", gateJson.optInt("scan_count", 0));
                                gate.denyCount = gateJson.optInt("deny_count", 0);
                                gate.lastEvent = gateJson.optString("last_event", gateJson.optString("last_heartbeat", ""));
                                break;
                            }
                        }
                    }
                }
                if (gateAdapter != null) gateAdapter.notifyDataSetChanged();
            }
            
            if (tvLastEvent != null) {
                tvLastEvent.setText(statsObj.optString("last_event", "-"));
            }
        } catch (Exception e) {
            Log.e(TAG, "updateGateStats error: " + e.getMessage());
        }
    }

    private void loadGatesFromServer() {
        if (permissionManager == null) return;
        
        boolean isGlobalAdmin = permissionManager.isSystemAdmin() || permissionManager.isOrgAdmin();
        int currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
        
        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;
                
                String endpoint = isGlobalAdmin ? "/gates?all=true" : "/gates?branch_id=" + currentBranchId;
                MiraApiClient.ApiResponse response = client.getWithAuth(endpoint);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray gatesArray = response.data.optJSONArray("gates");
                    if (gatesArray == null) {
                        JSONObject dataObj = response.data.optJSONObject("data");
                        if (dataObj != null) gatesArray = dataObj.optJSONArray("gates");
                    }
                    if (gatesArray == null) gatesArray = response.data.optJSONArray("items");

                    if (gatesArray != null && gatesArray.length() > 0) {
                        List<MiraGatesSettings.GateDevice> serverGates = new ArrayList<>();
                        for (int i = 0; i < gatesArray.length(); i++) {
                            JSONObject gateJson = gatesArray.optJSONObject(i);
                            if (gateJson != null) {
                                MiraGatesSettings.GateDevice gate = new MiraGatesSettings.GateDevice();
                                gate.gateId = gateJson.optString("gate_id", gateJson.optString("id", ""));
                                gate.gateName = gateJson.optString("gate_name", gateJson.optString("name", ""));
                                gate.location = gateJson.optString("gate_location", gateJson.optString("location", ""));
                                gate.deviceType = gateJson.optString("device_type", "chainway_c72");
                                gate.status = gateJson.optString("status", "offline");
                                gate.ipAddress = gateJson.optString("ip_address", "");
                                gate.port = gateJson.optInt("port", 8080);
                                gate.passCount = gateJson.optInt("scan_count", gateJson.optInt("pass_count", 0));
                                gate.denyCount = gateJson.optInt("deny_count", 0);
                                gate.lastEvent = gateJson.optString("last_heartbeat", gateJson.optString("last_event", ""));
                                gate.branchId = gateJson.optInt("branch_id", currentBranchId);
                                gate.branchName = gateJson.optString("branch_name", "");
                                serverGates.add(gate);
                            }
                        }

                        if (!serverGates.isEmpty()) {
                            if (settings != null) {
                                settings.saveGates(serverGates);
                            }
                            mainHandler.post(() -> {
                                if (isAdded()) {
                                    loadGatesFromLocal();
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadGatesFromServer error: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (statusRefreshRunnable != null) {
            mainHandler.removeCallbacks(statusRefreshRunnable);
        }
        shutdownExecutorSafely();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        shutdownExecutorSafely();
    }

    private void shutdownExecutorSafely() {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                } finally {
                    executor = null;
                }
            }
        }
    }

    private static class BranchItem {
        int id;
        String name;
        
        BranchItem(int id, String name) {
            this.id = id;
            this.name = name;
        }
        
        @Override
        public String toString() {
            return name + " (ID: " + id + ")";
        }
    }

    private static class GateAdapter extends RecyclerView.Adapter<GateAdapter.GateViewHolder> {

        private final List<MiraGatesSettings.GateDevice> gates;
        private final OnGateRemoveListener removeListener;
        private final OnGateToggleListener toggleListener;
        private final OnGateEditListener editListener;

        GateAdapter(List<MiraGatesSettings.GateDevice> gates, 
                    OnGateRemoveListener removeListener, 
                    OnGateToggleListener toggleListener,
                    OnGateEditListener editListener) {
            this.gates = gates;
            this.removeListener = removeListener;
            this.toggleListener = toggleListener;
            this.editListener = editListener;
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

            if (holder.tvGateName != null) {
                holder.tvGateName.setText(gate.gateName != null ? gate.gateName : "");
            }
            
            if (holder.tvGateLocation != null) {
                holder.tvGateLocation.setText("📍 " + (gate.location != null && !gate.location.isEmpty() ? gate.location : "غير محدد"));
            }
            
            if (holder.tvGateBranch != null) {
                holder.tvGateBranch.setText("🏢 " + (gate.branchName != null && !gate.branchName.isEmpty() ? gate.branchName : "غير محدد"));
            }
            
            boolean isOnline = "online".equals(gate.status) || "armed".equals(gate.status);
            if (holder.tvGateStatus != null) {
                if (isOnline) {
                    holder.tvGateStatus.setText("🟢 متصل");
                    holder.tvGateStatus.setTextColor(0xFF2E7D32);
                } else {
                    holder.tvGateStatus.setText("⚪ غير متصل");
                    holder.tvGateStatus.setTextColor(0xFF9E9E9E);
                }
            }

            if (holder.tvGateStats != null) {
                holder.tvGateStats.setText("✅ " + gate.passCount + " | ❌ " + gate.denyCount);
            }

            if (holder.tvGateLastEvent != null) {
                holder.tvGateLastEvent.setText(gate.lastEvent != null && !gate.lastEvent.isEmpty() ? gate.lastEvent : "لا أحداث");
            }

            if (holder.btnToggleGate != null) {
                holder.btnToggleGate.setText(isOnline ? "🔒" : "🔓");
                holder.btnToggleGate.setOnClickListener(v -> {
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && toggleListener != null) {
                        toggleListener.onToggle(pos, !isOnline);
                    }
                });
            }

            if (holder.btnRemove != null) {
                holder.btnRemove.setOnClickListener(v -> {
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && removeListener != null) {
                        removeListener.onRemove(pos);
                    }
                });
            }

            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && editListener != null) {
                    editListener.onEdit(pos);
                }
            });
        }

        @Override
        public int getItemCount() {
            return gates != null ? gates.size() : 0;
        }

        static class GateViewHolder extends RecyclerView.ViewHolder {
            TextView tvGateName, tvGateLocation, tvGateStatus, tvGateStats, tvGateLastEvent, tvGateBranch;
            View btnRemove;
            MaterialButton btnToggleGate;

            GateViewHolder(@NonNull View itemView) {
                super(itemView);
                tvGateName = itemView.findViewById(R.id.tvGateName);
                tvGateLocation = itemView.findViewById(R.id.tvGateLocation);
                tvGateStatus = itemView.findViewById(R.id.tvGateStatus);
                tvGateStats = itemView.findViewById(R.id.tvGateStats);
                tvGateLastEvent = itemView.findViewById(R.id.tvGateLastEvent);
                tvGateBranch = itemView.findViewById(R.id.tvGateBranch);
                btnRemove = itemView.findViewById(R.id.btnRemoveGate);
                btnToggleGate = itemView.findViewById(R.id.btnToggleGate);
            }
        }
    }

    interface OnGateRemoveListener {
        void onRemove(int position);
    }
    
    interface OnGateToggleListener {
        void onToggle(int position, boolean open);
    }

    interface OnGateEditListener {
        void onEdit(int position);
    }
}
