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
import com.example.uhf.manager.GateSecurityManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
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
    
    // ✅ تعريف واحد فقط للـ executor
    private ExecutorService executor;
    private static final Object EXECUTOR_LOCK = new Object();

    private RecyclerView rvGates;
    private Spinner spSoundPass, spSoundDeny, spSoundAlarm;
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
        permissionManager = PermissionManager.getInstance(ctx);
        branchesManager = MiraBranchesManager.getInstance(ctx);
        isArabic = java.util.Locale.getDefault().getLanguage().equals("ar");

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

    // ✅ دالة موحدة للحصول على executor
    private ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown() || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor();
            }
            return executor;
        }
    }

    // ✅ دالة موحدة لتنفيذ المهام بأمان
    private void executeTask(Runnable task) {
        try {
            ExecutorService exec = getExecutor();
            if (!exec.isShutdown() && !exec.isTerminated()) {
                exec.execute(task);
            } else {
                Log.w(TAG, "Executor is not available");
            }
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Failed to execute task: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error executing task: " + e.getMessage());
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

        rvGates.setLayoutManager(new LinearLayoutManager(getContext()));
        gateAdapter = new GateAdapter(gatesList, this::removeGate, this::toggleGateStatus);
        rvGates.setAdapter(gateAdapter);
    }

    private void applyPermissions() {
        boolean canManageGates = permissionManager.isAdmin() || 
                                  permissionManager.isBranchManager() ||
                                  permissionManager.canEdit("gates");
        
        if (btnAddGate != null) btnAddGate.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
        if (btnSaveSounds != null) btnSaveSounds.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
        if (btnSaveAll != null) btnSaveAll.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
    }

    private void loadBranches() {
        branchesList.clear();
        
        // ✅ استخدام executeTask بدلاً من executor مباشرة
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
                                if (isAdded() && getContext() != null) {
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
        
        if (branchesList.isEmpty()) {
            int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
            String activeBranchName = branchesManager != null ? branchesManager.getActiveBranchName() : "الفرع الرئيسي";
            branchesList.add(new BranchItem(activeBranchId, activeBranchName));
            branchesList.add(new BranchItem(1, "الفرع الرئيسي"));
        }
    }

    private boolean isArabic() {
        return java.util.Locale.getDefault().getLanguage().equals("ar");
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
        // ✅ استخدام executeTask
        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;

                MiraApiClient.ApiResponse response = client.getGatesStats();
                
                if (response != null && response.isSuccess && response.data != null) {
                    mainHandler.post(() -> {
                        if (isAdded() && getContext() != null) {
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
        if (btnAddGate != null) {
            btnAddGate.setOnClickListener(v -> {
                if (permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager()) {
                    showAddGateDialog();
                } else {
                    Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnTestSound != null) {
            btnTestSound.setOnClickListener(v -> {
                String selectedSound = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[spSoundAlarm.getSelectedItemPosition()];
                SoundGenerator.playSound(selectedSound);
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "🔊 تشغيل: " + getSoundDisplayName(selectedSound), Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnRefreshStatus != null) {
            btnRefreshStatus.setOnClickListener(v -> loadGateStats());
        }

        if (btnSaveSounds != null) {
            btnSaveSounds.setOnClickListener(v -> {
                if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
                    Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                String soundPass = MiraGatesSettings.SoundOptions.PASS_SOUNDS[spSoundPass.getSelectedItemPosition()];
                String soundDeny = MiraGatesSettings.SoundOptions.DENY_SOUNDS[spSoundDeny.getSelectedItemPosition()];
                String soundAlarm = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[spSoundAlarm.getSelectedItemPosition()];

                settings.setSoundPass(soundPass);
                settings.setSoundDeny(soundDeny);
                settings.setSoundAlarm(soundAlarm);

                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "✅ تم حفظ الأصوات", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (seekRfidPower != null) {
            seekRfidPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvRfidPowerValue.setText(progress + " dBm");
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (btnSaveAll != null) {
            btnSaveAll.setOnClickListener(v -> {
                if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
                    Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
                    return;
                }
                
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

                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "✅ تم حفظ جميع الإعدادات", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void loadGatesFromLocal() {
        gatesList.clear();
        gatesList.addAll(settings.getGates());
        if (gateAdapter != null) {
            gateAdapter.notifyDataSetChanged();
        }
    }

    

    private void showAddGateDialog() {
        if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
            Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            return;
        }
        
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

        TextView tvBranchLabel = new TextView(requireContext());
        tvBranchLabel.setText("🏢 الفرع التابع له:");
        tvBranchLabel.setTextSize(14);
        tvBranchLabel.setPadding(0, 20, 0, 5);
        layout.addView(tvBranchLabel);

        final Spinner spBranch = new Spinner(requireContext());
        ArrayAdapter<BranchItem> branchAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, branchesList);
        branchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBranch.setAdapter(branchAdapter);
        layout.addView(spBranch);

        final EditText etIpAddress = new EditText(requireContext());
        etIpAddress.setHint("عنوان IP (اختياري)");
        etIpAddress.setSingleLine(true);
        layout.addView(etIpAddress);

        TextView tvRfidLabel = new TextView(requireContext());
        tvRfidLabel.setText("📡 قوة RFID:");
        tvRfidLabel.setTextSize(14);
        tvRfidLabel.setPadding(0, 20, 0, 5);
        layout.addView(tvRfidLabel);

        final SeekBar seekGateRfidPower = new SeekBar(requireContext());
        seekGateRfidPower.setMax(30);
        seekGateRfidPower.setProgress(settings.getRfidPower());
        layout.addView(seekGateRfidPower);

        final TextView tvGateRfidValue = new TextView(requireContext());
        tvGateRfidValue.setText(settings.getRfidPower() + " dBm");
        tvGateRfidValue.setGravity(android.view.Gravity.CENTER);
        layout.addView(tvGateRfidValue);

        seekGateRfidPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvGateRfidValue.setText(progress + " dBm");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        final SwitchMaterial swGateVibration = new SwitchMaterial(requireContext());
        swGateVibration.setText("📳 الاهتزاز");
        swGateVibration.setChecked(settings.isVibrationEnabled());
        layout.addView(swGateVibration);

        final SwitchMaterial swGateFlash = new SwitchMaterial(requireContext());
        swGateFlash.setText("💡 وميض الشاشة");
        swGateFlash.setChecked(settings.isFlashEnabled());
        layout.addView(swGateFlash);

        builder.setView(layout);
        builder.setPositiveButton("إضافة", (dialog, which) -> {
            String gateName = etGateName.getText().toString().trim();
            String location = etLocation.getText().toString().trim();
            String ipAddress = etIpAddress.getText().toString().trim();
            int rfidPower = seekGateRfidPower.getProgress();
            boolean vibration = swGateVibration.isChecked();
            boolean flash = swGateFlash.isChecked();
            
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
                
                gate.rfidPower = rfidPower;
                gate.vibrationEnabled = vibration;
                gate.flashEnabled = flash;
                gate.soundPass = settings.getSoundPass();
                gate.soundDeny = settings.getSoundDeny();
                gate.soundAlarm = settings.getSoundAlarm();
                gate.alarmDuration = settings.getAlarmDuration();

                settings.addGate(gate);
                
                settings.saveGateSettings(gate.gateId, 
                    gate.soundPass, gate.soundDeny, gate.soundAlarm,
                    vibration, flash, gate.alarmDuration, rfidPower);
                
                loadGatesFromLocal();
                syncGateToServer(gate, branchId);

                Toast.makeText(getContext(), 
                    "✅ تمت إضافة البوابة لفرع: " + branchName, 
                    Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "⚠️ اسم البوابة مطلوب", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    private void syncGateToServer(MiraGatesSettings.GateDevice gate, int branchId) {
        // ✅ استخدام executeTask
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
                    if (isAdded() && getContext() != null) {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), "✅ تمت المزامنة مع السيرفر", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), "⚠️ حفظ محلي فقط", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "syncGateToServer error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (isAdded() && getContext() != null) {
                        Toast.makeText(getContext(), "💾 حفظ محلي فقط", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void removeGate(int position) {
        if (!(permissionManager.canDelete("gates") || permissionManager.isAdmin())) {
            Toast.makeText(getContext(), "⛔ غير مصرح بالحذف", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (position >= 0 && position < gatesList.size()) {
            MiraGatesSettings.GateDevice gate = gatesList.get(position);
            settings.removeGate(gate.gateId);
            loadGatesFromLocal();
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "🗑 تم حذف: " + gate.gateName, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ==================== 1. إصلاح toggleGateStatus ====================
private void toggleGateStatus(int position, boolean open) {
    if (!permissionManager.isAdmin() && !permissionManager.isBranchManager()) {
        Toast.makeText(getContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
        return;
    }
    
    if (position >= 0 && position < gatesList.size()) {
        MiraGatesSettings.GateDevice gate = gatesList.get(position);
        
        // ✅ إرسال الأمر للسيرفر
        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject payload = new JSONObject();
                payload.put("gate_id", gate.gateId);
                payload.put("command", open ? "ARM" : "DISARM");
                payload.put("branch_id", gate.branchId);
                
                MiraApiClient.ApiResponse response = client.postWithAuth("/gate-command", payload);
                
                mainHandler.post(() -> {
                    if (isAdded()) {
                        if (response != null && response.isSuccess) {
                            gate.status = open ? "armed" : "offline";
                            if (gateAdapter != null) gateAdapter.notifyDataSetChanged();
                            
                            Toast.makeText(getContext(), 
                                open ? "🚪 تم تشغيل مراقبة: " + gate.gateName : 
                                "🔒 تم إيقاف مراقبة: " + gate.gateName, 
                                Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), 
                                "❌ فشل تغيير حالة البوابة", 
                                Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(getContext(), 
                            "❌ خطأ: " + e.getMessage(), 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
}

// ==================== 2. إضافة دالة تعديل البوابة ====================
private void showEditGateDialog(int position) {
    if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
        Toast.makeText(getContext(), "⛔ غير مصرح بالتعديل", Toast.LENGTH_SHORT).show();
        return;
    }
    
    if (position < 0 || position >= gatesList.size()) return;
    
    MiraGatesSettings.GateDevice existingGate = gatesList.get(position);
    
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setTitle("✏️ تعديل بوابة: " + existingGate.gateName);

    LinearLayout layout = new LinearLayout(requireContext());
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(40, 20, 40, 10);

    final EditText etGateName = new EditText(requireContext());
    etGateName.setHint("اسم البوابة");
    etGateName.setSingleLine(true);
    etGateName.setText(existingGate.gateName);
    layout.addView(etGateName);

    final EditText etLocation = new EditText(requireContext());
    etLocation.setHint("الموقع");
    etLocation.setSingleLine(true);
    etLocation.setText(existingGate.location);
    layout.addView(etLocation);

    TextView tvBranchLabel = new TextView(requireContext());
    tvBranchLabel.setText("🏢 الفرع التابع له:");
    tvBranchLabel.setTextSize(14);
    tvBranchLabel.setPadding(0, 20, 0, 5);
    layout.addView(tvBranchLabel);

    final Spinner spBranch = new Spinner(requireContext());
    ArrayAdapter<BranchItem> branchAdapter = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, branchesList);
    branchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spBranch.setAdapter(branchAdapter);
    
    // ✅ تحديد الفرع الحالي
    for (int i = 0; i < branchesList.size(); i++) {
        if (branchesList.get(i).id == existingGate.branchId) {
            spBranch.setSelection(i);
            break;
        }
    }
    layout.addView(spBranch);

    final EditText etIpAddress = new EditText(requireContext());
    etIpAddress.setHint("عنوان IP");
    etIpAddress.setSingleLine(true);
    etIpAddress.setText(existingGate.ipAddress);
    layout.addView(etIpAddress);

    // ✅ قوة RFID
    TextView tvRfidLabel = new TextView(requireContext());
    tvRfidLabel.setText("📡 قوة RFID:");
    tvRfidLabel.setTextSize(14);
    tvRfidLabel.setPadding(0, 20, 0, 5);
    layout.addView(tvRfidLabel);

    final SeekBar seekGateRfidPower = new SeekBar(requireContext());
    seekGateRfidPower.setMax(30);
    seekGateRfidPower.setProgress(existingGate.rfidPower);
    layout.addView(seekGateRfidPower);

    final TextView tvGateRfidValue = new TextView(requireContext());
    tvGateRfidValue.setText(existingGate.rfidPower + " dBm");
    tvGateRfidValue.setGravity(android.view.Gravity.CENTER);
    layout.addView(tvGateRfidValue);

    seekGateRfidPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            tvGateRfidValue.setText(progress + " dBm");
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    });

    final SwitchMaterial swGateVibration = new SwitchMaterial(requireContext());
    swGateVibration.setText("📳 الاهتزاز");
    swGateVibration.setChecked(existingGate.vibrationEnabled);
    layout.addView(swGateVibration);

    final SwitchMaterial swGateFlash = new SwitchMaterial(requireContext());
    swGateFlash.setText("💡 وميض الشاشة");
    swGateFlash.setChecked(existingGate.flashEnabled);
    layout.addView(swGateFlash);

    builder.setView(layout);
    builder.setPositiveButton("حفظ", (dialog, which) -> {
        String gateName = etGateName.getText().toString().trim();
        String location = etLocation.getText().toString().trim();
        String ipAddress = etIpAddress.getText().toString().trim();
        int rfidPower = seekGateRfidPower.getProgress();
        boolean vibration = swGateVibration.isChecked();
        boolean flash = swGateFlash.isChecked();
        
        BranchItem selectedBranch = (BranchItem) spBranch.getSelectedItem();
        int branchId = selectedBranch != null ? selectedBranch.id : existingGate.branchId;
        String branchName = selectedBranch != null ? selectedBranch.name : existingGate.branchName;

        if (!gateName.isEmpty()) {
            // ✅ تحديث الكائن
            existingGate.gateName = gateName;
            existingGate.location = location;
            existingGate.ipAddress = ipAddress;
            existingGate.rfidPower = rfidPower;
            existingGate.vibrationEnabled = vibration;
            existingGate.flashEnabled = flash;
            existingGate.branchId = branchId;
            existingGate.branchName = branchName;
            
            // ✅ حفظ محلياً
            settings.updateGate(existingGate);
            settings.saveGateSettings(existingGate.gateId, 
                existingGate.soundPass, existingGate.soundDeny, existingGate.soundAlarm,
                vibration, flash, existingGate.alarmDuration, rfidPower);
            
            loadGatesFromLocal();
            
            // ✅ مزامنة مع السيرفر
            updateGateOnServer(existingGate);

            Toast.makeText(getContext(), 
                "✅ تم تحديث البوابة", 
                Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "⚠️ اسم البوابة مطلوب", Toast.LENGTH_SHORT).show();
        }
    });
    builder.setNegativeButton("إلغاء", null);
    builder.show();
}

// ✅ دالة تحديث البوابة على السيرفر
private void updateGateOnServer(MiraGatesSettings.GateDevice gate) {
    executeTask(() -> {
        try {
            Context context = getContext();
            if (context == null) return;

            MiraApiClient client = MiraApiClient.getInstance(context);
            
            JSONObject payload = new JSONObject();
            payload.put("gate_id", gate.gateId);
            payload.put("gate_name", gate.gateName);
            payload.put("gate_location", gate.location);
            payload.put("device_type", gate.deviceType);
            payload.put("ip_address", gate.ipAddress);
            payload.put("port", gate.port);
            payload.put("branch_id", gate.branchId);
            payload.put("rfid_power", gate.rfidPower);
            payload.put("vibration_enabled", gate.vibrationEnabled);
            payload.put("flash_enabled", gate.flashEnabled);
            payload.put("sound_pass", gate.soundPass);
            payload.put("sound_deny", gate.soundDeny);
            payload.put("sound_alarm", gate.soundAlarm);
            payload.put("alarm_duration", gate.alarmDuration);

            MiraApiClient.ApiResponse response = client.updateGate(gate.gateId, payload);

            mainHandler.post(() -> {
                if (isAdded()) {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), "✅ تمت المزامنة مع السيرفر", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "⚠️ حفظ محلي فقط", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "updateGateOnServer error: " + e.getMessage());
        }
    });
}

// ==================== 3. إصلاح updateGateStats ====================
private void updateGateStats(JSONObject data) {
    try {
        JSONObject statsObj = data.optJSONObject("data");
        if (statsObj == null) statsObj = data;
        
        // ✅ البحث عن الإحصائيات في أماكن مختلفة
        JSONObject stats = statsObj.optJSONObject("stats");
        if (stats == null) stats = statsObj.optJSONObject("summary");
        if (stats == null) stats = statsObj;
        
        int total = stats.optInt("total_gates", stats.optInt("total", 0));
        int online = stats.optInt("online_gates", stats.optInt("online", 0));
        int offline = stats.optInt("offline_gates", stats.optInt("offline", 0));
        
        // ✅ إذا لم توجد إحصائيات مباشرة، احسبها من القائمة
        if (total == 0 && !gatesList.isEmpty()) {
            total = gatesList.size();
            online = 0;
            offline = 0;
            for (MiraGatesSettings.GateDevice gate : gatesList) {
                if ("online".equals(gate.status) || "armed".equals(gate.status)) {
                    online++;
                } else {
                    offline++;
                }
            }
        }
        
        if (tvTotalGates != null) tvTotalGates.setText(String.valueOf(total));
        if (tvOnlineGates != null) tvOnlineGates.setText(String.valueOf(online));
        if (tvOfflineGates != null) tvOfflineGates.setText(String.valueOf(offline));
        
        // ✅ تحديث حالات البوابات
        JSONArray gatesArray = statsObj.optJSONArray("gates");
        if (gatesArray == null) gatesArray = stats.optJSONArray("gates");
        if (gatesArray == null) gatesArray = statsObj.optJSONArray("items");
        
        if (gatesArray != null) {
            for (int i = 0; i < gatesArray.length(); i++) {
                JSONObject gateJson = gatesArray.optJSONObject(i);
                if (gateJson != null) {
                    String gateId = gateJson.optString("gate_id", gateJson.optString("id", ""));
                    String status = gateJson.optString("status", "offline");
                    String lastEvent = gateJson.optString("last_event", gateJson.optString("last_heartbeat", ""));
                    int passCount = gateJson.optInt("pass_count", gateJson.optInt("scan_count", 0));
                    int denyCount = gateJson.optInt("deny_count", 0);
                    
                    for (MiraGatesSettings.GateDevice gate : gatesList) {
                        if (gate.gateId.equals(gateId) || gate.gateName.equals(gateJson.optString("gate_name", ""))) {
                            gate.status = status;
                            gate.lastEvent = lastEvent;
                            gate.passCount = passCount;
                            gate.denyCount = denyCount;
                            break;
                        }
                    }
                }
            }
            if (gateAdapter != null) {
                gateAdapter.notifyDataSetChanged();
            }
        }
        
        if (tvLastEvent != null) {
            tvLastEvent.setText(statsObj.optString("last_event", "-"));
        }
        
    } catch (Exception e) {
        Log.e(TAG, "updateGateStats error: " + e.getMessage());
    }
}

// ==================== 4. إصلاح loadGatesFromServer للمدراء ====================
private void loadGatesFromServer() {
    if (permissionManager == null) return;
    
    boolean isGlobalAdmin = permissionManager.isSystemAdmin() || permissionManager.isOrgAdmin();
    boolean isBranchManager = permissionManager.isBranchManager();
    boolean canViewGates = permissionManager.canView("gates");
    
    if (!canViewGates && !permissionManager.isAdmin() && !isBranchManager && 
        !permissionManager.isSecurity() && !permissionManager.isGateSecurity()) {
        return;
    }
    
    executeTask(() -> {
        try {
            Context context = getContext();
            if (context == null) return;

            MiraApiClient client = MiraApiClient.getInstance(context);
            if (client == null) return;
            
            int currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
            
            String endpoint = "/gates";
            
            // ✅ المدراء العامون يرون جميع البوابات
            if (isGlobalAdmin) {
                // بدون فلتر فرع - كل البوابات
                endpoint = "/gates?all=true";
            } else if (currentBranchId > 0) {
                endpoint += "?branch_id=" + currentBranchId;
            }
            
            MiraApiClient.ApiResponse response = client.getWithAuth(endpoint);

            if (response != null && response.isSuccess && response.data != null) {
                JSONArray gatesArray = response.data.optJSONArray("gates");
                if (gatesArray == null) {
                    JSONObject dataObj = response.data.optJSONObject("data");
                    if (dataObj != null) {
                        gatesArray = dataObj.optJSONArray("gates");
                    }
                }
                
                // ✅ إذا لم توجد gates، جرب items
                if (gatesArray == null) {
                    gatesArray = response.data.optJSONArray("items");
                }

                if (gatesArray != null && gatesArray.length() > 0) {
                    List<MiraGatesSettings.GateDevice> serverGates = new ArrayList<>();
                    
                    for (int i = 0; i < gatesArray.length(); i++) {
                        JSONObject gateJson = gatesArray.optJSONObject(i);
                        if (gateJson != null) {
                            int gateBranchId = gateJson.optInt("branch_id", 0);
                            
                            // ✅ المدراء العامون يرون كل شيء
                            if (!isGlobalAdmin && currentBranchId > 0 && gateBranchId > 0 && gateBranchId != currentBranchId) {
                                continue;
                            }
                            
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
                            gate.branchId = gateBranchId;
                            gate.branchName = gateJson.optString("branch_name", "");
                            serverGates.add(gate);
                        }
                    }

                    if (!serverGates.isEmpty()) {
                        settings.saveGates(serverGates);
                        mainHandler.post(() -> {
                            if (isAdded() && getContext() != null) {
                                loadGatesFromLocal();
                                String msg = isGlobalAdmin ? 
                                    "✅ تم تحميل " + serverGates.size() + " بوابة (كل الفروع)" : 
                                    "✅ تم تحميل " + serverGates.size() + " بوابة لفرعك";
                                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
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
        
        // ✅ إغلاق آمن للـ executor
        shutdownExecutorSafely();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        
        // ✅ ضمان إغلاق الـ executor
        shutdownExecutorSafely();
    }

    // ✅ دالة الإغلاق الآمن
    private void shutdownExecutorSafely() {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                try {
                    // انتظر حتى 3 ثواني للمهام الحالية للانتهاء
                    if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                        // انتظر مرة أخرى للمهام التي تم إلغاؤها
                        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                            Log.e(TAG, "Executor did not terminate");
                        }
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

    private class GateAdapter extends RecyclerView.Adapter<GateAdapter.GateViewHolder> {

        private final List<MiraGatesSettings.GateDevice> gates;
        private final OnGateRemoveListener removeListener;
        private final OnGateToggleListener toggleListener;

        GateAdapter(List<MiraGatesSettings.GateDevice> gates, OnGateRemoveListener removeListener, OnGateToggleListener toggleListener) {
            this.gates = gates;
            this.removeListener = removeListener;
            this.toggleListener = toggleListener;
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
            
            if (holder.tvGateBranch != null) {
                holder.tvGateBranch.setText("🏢 " + (gate.branchName != null && !gate.branchName.isEmpty() ? gate.branchName : "غير محدد"));
            }
            
            if ("online".equals(gate.status)) {
                holder.tvGateStatus.setText("🟢 متصل");
                holder.tvGateStatus.setTextColor(0xFF2E7D32);
            } else {
                holder.tvGateStatus.setText("⚪ غير متصل");
                holder.tvGateStatus.setTextColor(0xFF9E9E9E);
            }

            if (holder.tvGateStats != null) {
                holder.tvGateStats.setText("✅ " + gate.passCount + " | ❌ " + gate.denyCount);
            }

            if (holder.tvGateLastEvent != null) {
                holder.tvGateLastEvent.setText(gate.lastEvent != null && !gate.lastEvent.isEmpty() ? 
                    gate.lastEvent : "لا أحداث");
            }

            if (holder.btnToggleGate != null) {
                holder.btnToggleGate.setText("online".equals(gate.status) ? "🔒" : "🔓");
                holder.btnToggleGate.setOnClickListener(v -> {
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        toggleListener.onToggle(pos, !"online".equals(gate.status));
                    }
                });
            }

            if (holder.btnRemove != null) {
                holder.btnRemove.setOnClickListener(v -> {
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        removeListener.onRemove(pos);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return gates.size();
        }

        class GateViewHolder extends RecyclerView.ViewHolder {
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
                }
