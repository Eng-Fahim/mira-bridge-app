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
import com.example.uhf.manager.GateSecurityManager;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SoundGenerator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraGateSettingsFragment extends Fragment {

    private static final String TAG = "MiraGateSettingsFragment";
    private static final long STATUS_REFRESH_INTERVAL = 10000; // 10 ثواني

    private MiraGatesSettings settings;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;
    private SoundPool soundPool;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);

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
    private Runnable statusRefreshRunnable;
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_gate_settings, container, false);
        } catch (Exception e) {
            logNonFatal("onCreateView", e);
            return null;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        try {
            Context ctx = requireContext();
            settings = MiraGatesSettings.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            isDestroyed.set(false);

            soundPool = new SoundPool.Builder().setMaxStreams(3).build();

            initViews(view);
            setupSpinners();
            loadSettings();
            setupListeners();
            applyPermissions();
            loadGatesFromLocal();
            loadGatesFromServer();
            loadGateStats();
            startStatusRefresh();
        } catch (Throwable e) {
            logNonFatal("onViewCreated", e);
        }
    }

    private void initViews(View view) {
        try {
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
        } catch (Exception e) {
            logNonFatal("initViews", e);
        }
    }

    private void applyPermissions() {
        try {
            boolean canManageGates = permissionManager.isAdmin() || 
                                      permissionManager.isBranchManager() ||
                                      permissionManager.canEdit("gates");
            
            if (btnAddGate != null) btnAddGate.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
            if (btnSaveSounds != null) btnSaveSounds.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
            if (btnSaveAll != null) btnSaveAll.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
        } catch (Exception e) {
            logNonFatal("applyPermissions", e);
        }
    }

    private void startStatusRefresh() {
        stopStatusRefresh();
        statusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isDestroyed.get() && isAdded()) {
                    loadGateStats();
                    mainHandler.postDelayed(this, STATUS_REFRESH_INTERVAL);
                }
            }
        };
        mainHandler.postDelayed(statusRefreshRunnable, STATUS_REFRESH_INTERVAL);
    }

    private void stopStatusRefresh() {
        if (statusRefreshRunnable != null) {
            mainHandler.removeCallbacks(statusRefreshRunnable);
            statusRefreshRunnable = null;
        }
    }

    /**
     * ✅ جلب إحصائيات البوابات - مع فلترة الفرع
     */
    private void loadGateStats() {
        if (isLoading.get() || isDestroyed.get() || !isAdded()) return;
        
        executor.execute(() -> {
            try {
                Context ctx = getContext();
                if (ctx == null) return;
                
                MiraApiClient client = MiraApiClient.getInstance(ctx);
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                
                // ✅ جلب الإحصائيات مع branch_id
                MiraApiClient.ApiResponse response = client.getGatesStats();
                
                if (response != null && response.isSuccess && response.data != null) {
                    final JSONObject data = response.data;
                    mainHandler.post(() -> {
                        if (!isDestroyed.get() && isAdded()) {
                            updateGateStats(data, branchId);
                        }
                    });
                }
            } catch (Exception e) {
                logNonFatal("loadGateStats.thread", e);
            }
        });
    }

    /**
     * ✅ تحديث إحصائيات البوابات - مع فلترة الفرع
     */
    private void updateGateStats(JSONObject data, int branchId) {
        try {
            JSONObject statsObj = data.optJSONObject("data");
            if (statsObj == null) statsObj = data;
            
            int total = statsObj.optInt("total", statsObj.optInt("total_gates", 0));
            int online = statsObj.optInt("online", statsObj.optInt("online_gates", 0));
            int offline = statsObj.optInt("offline", statsObj.optInt("offline_gates", 0));
            
            if (tvTotalGates != null) tvTotalGates.setText(String.valueOf(total));
            if (tvOnlineGates != null) tvOnlineGates.setText(String.valueOf(online));
            if (tvOfflineGates != null) tvOfflineGates.setText(String.valueOf(offline));
            
            // تحديث حالة البوابات
            JSONArray gatesArray = statsObj.optJSONArray("gates");
            if (gatesArray != null) {
                for (int i = 0; i < gatesArray.length(); i++) {
                    JSONObject gateJson = gatesArray.optJSONObject(i);
                    String gateId = gateJson.optString("gate_id", "");
                    int gateBranchId = gateJson.optInt("branch_id", 0);
                    
                    // ✅ فلترة حسب الفرع
                    if (branchId > 0 && gateBranchId != 0 && gateBranchId != branchId) {
                        continue;
                    }
                    
                    String status = gateJson.optString("status", "offline");
                    String lastEvent = gateJson.optString("last_event", "");
                    int passCount = gateJson.optInt("pass_count", 0);
                    int denyCount = gateJson.optInt("deny_count", 0);
                    
                    for (MiraGatesSettings.GateDevice gate : gatesList) {
                        if (gate.gateId.equals(gateId)) {
                            gate.status = status;
                            gate.lastEvent = lastEvent;
                            gate.passCount = passCount;
                            gate.denyCount = denyCount;
                            break;
                        }
                    }
                }
                if (gateAdapter != null) gateAdapter.notifyDataSetChanged();
            }
            
            if (tvLastEvent != null) {
                tvLastEvent.setText(statsObj.optString("last_event", ""));
            }
        } catch (Exception e) {
            logNonFatal("updateGateStats", e);
        }
    }

    private void setupSpinners() {
        try {
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
        } catch (Exception e) {
            logNonFatal("setupSpinners", e);
        }
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
            case "beep_success": return isArabic ? "🔔 نجاح قصير" : "🔔 Short Success";
            case "beep_soft": return isArabic ? "🔔 ناعم" : "🔔 Soft";
            case "beep_double": return isArabic ? "🔔 مزدوج" : "🔔 Double";
            case "chime_elegant": return isArabic ? "🎵 نغمة أنيقة" : "🎵 Elegant Chime";
            case "silent": return isArabic ? "🔇 بدون صوت" : "🔇 Silent";
            case "beep_warning": return isArabic ? "⚠️ تحذير" : "⚠️ Warning";
            case "beep_error": return isArabic ? "❌ خطأ" : "❌ Error";
            case "alarm_soft": return isArabic ? "🚨 إنذار خفيف" : "🚨 Soft Alarm";
            case "buzz_double": return isArabic ? "📳 طنين مزدوج" : "📳 Double Buzz";
            case "alarm_sold_item": return isArabic ? "🚨 إنذار مباع" : "🚨 Sold Item Alarm";
            case "alarm_breach_security": return isArabic ? "🚨 اختراق أمني" : "🚨 Security Breach";
            case "alarm_siren_strong": return isArabic ? "🚨 صفارة قوية" : "🚨 Strong Siren";
            case "alarm_police": return isArabic ? "🚨 صفارة شرطة" : "🚨 Police Siren";
            case "alarm_buzzer_loud": return isArabic ? "🚨 طنين عالي" : "🚨 Loud Buzzer";
            case "alarm_klaxon": return isArabic ? "🚨 كلوكسون" : "🚨 Klaxon";
            default: return soundKey;
        }
    }

    private void loadSettings() {
        try {
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
        } catch (Exception e) {
            logNonFatal("loadSettings", e);
        }
    }

    private int findIndex(String[] array, String value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) return i;
        }
        return 0;
    }

    private void setupListeners() {
        try {
            if (btnAddGate != null) {
                btnAddGate.setOnClickListener(v -> {
                    if (permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager()) {
                        showAddGateDialog();
                    } else {
                        showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                    }
                });
            }

            if (btnTestSound != null) {
                btnTestSound.setOnClickListener(v -> {
                    String selectedSound = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[spSoundAlarm.getSelectedItemPosition()];
                    SoundGenerator.playSound(selectedSound);
                    showToast((isArabic ? "🔊 تشغيل: " : "🔊 Playing: ") + getSoundDisplayName(selectedSound));
                });
            }

            if (btnRefreshStatus != null) {
                btnRefreshStatus.setOnClickListener(v -> loadGateStats());
            }

            if (btnSaveSounds != null) {
                btnSaveSounds.setOnClickListener(v -> {
                    if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
                        showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
                        return;
                    }
                    
                    String soundPass = MiraGatesSettings.SoundOptions.PASS_SOUNDS[spSoundPass.getSelectedItemPosition()];
                    String soundDeny = MiraGatesSettings.SoundOptions.DENY_SOUNDS[spSoundDeny.getSelectedItemPosition()];
                    String soundAlarm = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[spSoundAlarm.getSelectedItemPosition()];

                    settings.setSoundPass(soundPass);
                    settings.setSoundDeny(soundDeny);
                    settings.setSoundAlarm(soundAlarm);

                    showToast(isArabic ? "✅ تم حفظ الأصوات" : "✅ Sounds saved");
                });
            }

            if (seekRfidPower != null) {
                seekRfidPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (tvRfidPowerValue != null) tvRfidPowerValue.setText(progress + " dBm");
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            if (btnSaveAll != null) {
                btnSaveAll.setOnClickListener(v -> {
                    if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
                        showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
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

                    showToast(isArabic ? "✅ تم حفظ جميع الإعدادات" : "✅ All settings saved");
                });
            }
        } catch (Exception e) {
            logNonFatal("setupListeners", e);
        }
    }

    private void loadGatesFromLocal() {
        try {
            gatesList.clear();
            gatesList.addAll(settings.getGates());
            if (gateAdapter != null) gateAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            logNonFatal("loadGatesFromLocal", e);
        }
    }

    /**
     * ✅ جلب البوابات من السيرفر - مع فلترة الفرع
     */
    private void loadGatesFromServer() {
        if (isDestroyed.get() || !isAdded()) return;
        
        if (!permissionManager.canView("gates") && !permissionManager.isAdmin() && 
            !permissionManager.isBranchManager() && !permissionManager.isSecurity() && 
            !permissionManager.isGateSecurity()) {
            return;
        }
        
        executor.execute(() -> {
            try {
                Context ctx = getContext();
                if (ctx == null) return;
                
                MiraApiClient client = MiraApiClient.getInstance(ctx);
                int currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                
                // ✅✅✅ جلب البوابات مع branch_id
                MiraApiClient.ApiResponse response = client.getGatesByBranch(currentBranchId);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray gatesArray = response.data.optJSONArray("gates");
                    if (gatesArray == null) {
                        JSONObject dataObj = response.data.optJSONObject("data");
                        if (dataObj != null) {
                            gatesArray = dataObj.optJSONArray("gates");
                        }
                    }

                    if (gatesArray != null && gatesArray.length() > 0) {
                        List<MiraGatesSettings.GateDevice> serverGates = new ArrayList<>();
                        
                        for (int i = 0; i < gatesArray.length(); i++) {
                            JSONObject gateJson = gatesArray.optJSONObject(i);
                            if (gateJson != null) {
                                int gateBranchId = gateJson.optInt("branch_id", 0);
                                
                                // ✅✅✅ فلترة حسب الفرع
                                if (currentBranchId > 0 && gateBranchId != 0 && gateBranchId != currentBranchId) {
                                    continue;
                                }
                                
                                MiraGatesSettings.GateDevice gate = new MiraGatesSettings.GateDevice();
                                gate.gateId = gateJson.optString("gate_id", "");
                                gate.gateName = gateJson.optString("gate_name", gateJson.optString("name", ""));
                                gate.location = gateJson.optString("gate_location", gateJson.optString("location", ""));
                                gate.deviceType = gateJson.optString("device_type", "chainway_c72");
                                gate.status = gateJson.optString("status", "offline");
                                gate.ipAddress = gateJson.optString("ip_address", "");
                                gate.port = gateJson.optInt("port", 8080);
                                gate.branchId = gateBranchId; // ✅✅✅
                                gate.passCount = gateJson.optInt("scan_count", gateJson.optInt("pass_count", 0));
                                gate.denyCount = gateJson.optInt("deny_count", 0);
                                gate.lastEvent = gateJson.optString("last_heartbeat", "");
                                serverGates.add(gate);
                            }
                        }

                        if (!serverGates.isEmpty()) {
                            settings.saveGates(serverGates);
                            mainHandler.post(() -> {
                                if (!isDestroyed.get() && isAdded()) {
                                    loadGatesFromLocal();
                                    showToast(isArabic ? "✅ تم تحميل " + serverGates.size() + " بوابة" : "✅ Loaded " + serverGates.size() + " gates");
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                logNonFatal("loadGatesFromServer.thread", e);
            }
        });
    }

    /**
     * ✅ إضافة بوابة مع branch_id
     */
    private void showAddGateDialog() {
        if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }
        
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(isArabic ? "➕ إضافة بوابة جديدة" : "➕ Add New Gate");

            LinearLayout layout = new LinearLayout(requireContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 20, 40, 10);

            final EditText etGateName = new EditText(requireContext());
            etGateName.setHint(isArabic ? "اسم البوابة" : "Gate name");
            etGateName.setSingleLine(true);
            layout.addView(etGateName);

            final EditText etLocation = new EditText(requireContext());
            etLocation.setHint(isArabic ? "الموقع" : "Location");
            etLocation.setSingleLine(true);
            layout.addView(etLocation);

            final EditText etIpAddress = new EditText(requireContext());
            etIpAddress.setHint(isArabic ? "عنوان IP (اختياري)" : "IP Address (optional)");
            etIpAddress.setSingleLine(true);
            layout.addView(etIpAddress);

            builder.setView(layout);
            builder.setPositiveButton(isArabic ? "إضافة" : "Add", (dialog, which) -> {
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
                    
                    // ✅✅✅ إضافة branch_id
                    if (branchesManager != null) {
                        gate.branchId = branchesManager.getActiveBranchId();
                    }

                    settings.addGate(gate);
                    loadGatesFromLocal();
                    syncGateToServer(gate);

                    showToast(isArabic ? "✅ تمت إضافة البوابة" : "✅ Gate added");
                } else {
                    showToast(isArabic ? "⚠️ اسم البوابة مطلوب" : "⚠️ Gate name required");
                }
            });
            builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
            builder.show();
        } catch (Exception e) {
            logNonFatal("showAddGateDialog", e);
        }
    }

    /**
     * ✅ مزامنة البوابة مع السيرفر - مع branch_id
     */
    private void syncGateToServer(MiraGatesSettings.GateDevice gate) {
        executor.execute(() -> {
            try {
                Context ctx = getContext();
                if (ctx == null) return;
                
                MiraApiClient client = MiraApiClient.getInstance(ctx);
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                
                JSONObject payload = new JSONObject();
                payload.put("gate_id", gate.gateId);
                payload.put("gate_name", gate.gateName);
                payload.put("gate_location", gate.location);
                payload.put("device_type", gate.deviceType);
                payload.put("ip_address", gate.ipAddress);
                payload.put("port", gate.port);
                payload.put("branch_id", branchId); // ✅✅✅

                MiraApiClient.ApiResponse response = client.createGate(payload);

                mainHandler.post(() -> {
                    if (!isDestroyed.get() && isAdded()) {
                        if (response != null && response.isSuccess) {
                            showToast(isArabic ? "✅ تمت المزامنة مع السيرفر" : "✅ Synced with server");
                        } else {
                            showToast(isArabic ? "⚠️ حفظ محلي فقط" : "⚠️ Local save only");
                        }
                    }
                });
            } catch (Exception e) {
                logNonFatal("syncGateToServer.thread", e);
                mainHandler.post(() -> {
                    if (!isDestroyed.get() && isAdded()) {
                        showToast(isArabic ? "💾 حفظ محلي فقط" : "💾 Local save only");
                    }
                });
            }
        });
    }

    private void removeGate(int position) {
        if (!(permissionManager.canDelete("gates") || permissionManager.isAdmin())) {
            showToast(isArabic ? "⛔ غير مصرح بالحذف" : "⛔ Delete denied");
            return;
        }
        
        if (position >= 0 && position < gatesList.size()) {
            MiraGatesSettings.GateDevice gate = gatesList.get(position);
            settings.removeGate(gate.gateId);
            loadGatesFromLocal();
            showToast((isArabic ? "🗑 تم حذف: " : "🗑 Deleted: ") + gate.gateName);
        }
    }

    private void toggleGateStatus(int position, boolean open) {
        if (!permissionManager.isAdmin() && !permissionManager.isBranchManager()) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }
        
        if (position >= 0 && position < gatesList.size()) {
            MiraGatesSettings.GateDevice gate = gatesList.get(position);
            
            if (open) {
                GateSecurityManager.getInstance(requireContext())
                    .startGateMonitoring(gate.gateId);
                gate.status = "armed";
                showToast((isArabic ? "🚪 تم تشغيل مراقبة: " : "🚪 Monitoring started: ") + gate.gateName);
            } else {
                GateSecurityManager.getInstance(requireContext())
                    .stopGateMonitoring();
                gate.status = "offline";
                showToast((isArabic ? "🔒 تم إيقاف مراقبة: " : "🔒 Monitoring stopped: ") + gate.gateName);
            }
            
            if (gateAdapter != null) gateAdapter.notifyDataSetChanged();
        }
    }

    private void showToast(String message) {
        try {
            mainHandler.post(() -> {
                if (!isDestroyed.get() && isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            logNonFatal("showToast", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            isDestroyed.set(false);
            startStatusRefresh();
            loadGateStats();
        } catch (Exception e) {
            logNonFatal("onResume", e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            stopStatusRefresh();
        } catch (Exception e) {
            logNonFatal("onPause", e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            isDestroyed.set(true);
            stopStatusRefresh();
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Exception e) {
            logNonFatal("onDestroyView", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (soundPool != null) {
                soundPool.release();
                soundPool = null;
            }
        } catch (Exception e) {
            logNonFatal("onDestroy", e);
        }
    }

    /**
     * ✅ تسجيل الأخطاء في Crashlytics
     */
    private void logNonFatal(String location, Throwable throwable) {
        try {
            Log.e(TAG, location + ": " + throwable.getMessage(), throwable);
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.setCustomKey("error_location", location);
            crashlytics.setCustomKey("fragment", "MiraGateSettingsFragment");
            crashlytics.recordException(throwable);
        } catch (Exception e) {
            Log.e(TAG, "logNonFatal error: " + e.getMessage());
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
            try {
                MiraGatesSettings.GateDevice gate = gates.get(position);

                if (holder.tvGateName != null) holder.tvGateName.setText(gate.gateName);
                if (holder.tvGateLocation != null) holder.tvGateLocation.setText("📍 " + (gate.location != null && !gate.location.isEmpty() ? gate.location : (isArabic ? "غير محدد" : "Unknown")));
                
                if (holder.tvGateStatus != null) {
                    if ("online".equals(gate.status) || "armed".equals(gate.status)) {
                        holder.tvGateStatus.setText(isArabic ? "🟢 متصل" : "🟢 Online");
                        holder.tvGateStatus.setTextColor(0xFF2E7D32);
                    } else {
                        holder.tvGateStatus.setText(isArabic ? "⚪ غير متصل" : "⚪ Offline");
                        holder.tvGateStatus.setTextColor(0xFF9E9E9E);
                    }
                }

                if (holder.tvGateStats != null) {
                    holder.tvGateStats.setText("✅ " + gate.passCount + " | ❌ " + gate.denyCount);
                }

                if (holder.tvGateLastEvent != null) {
                    holder.tvGateLastEvent.setText(gate.lastEvent != null && !gate.lastEvent.isEmpty() ? 
                        gate.lastEvent : (isArabic ? "لا أحداث" : "No events"));
                }

                if (holder.btnToggleGate != null) {
                    boolean isOnline = "online".equals(gate.status) || "armed".equals(gate.status);
                    holder.btnToggleGate.setText(isOnline ? "🔒" : "🔓");
                    holder.btnToggleGate.setOnClickListener(v -> {
                        int pos = holder.getAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) {
                            toggleListener.onToggle(pos, !isOnline);
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
            } catch (Exception e) {
                logNonFatal("GateAdapter.onBindViewHolder", e);
            }
        }

        @Override
        public int getItemCount() {
            return gates.size();
        }

        class GateViewHolder extends RecyclerView.ViewHolder {
            TextView tvGateName, tvGateLocation, tvGateStatus, tvGateStats, tvGateLastEvent;
            View btnRemove;
            MaterialButton btnToggleGate;

            GateViewHolder(@NonNull View itemView) {
                super(itemView);
                tvGateName = itemView.findViewById(R.id.tvGateName);
                tvGateLocation = itemView.findViewById(R.id.tvGateLocation);
                tvGateStatus = itemView.findViewById(R.id.tvGateStatus);
                tvGateStats = itemView.findViewById(R.id.tvGateStats);
                tvGateLastEvent = itemView.findViewById(R.id.tvGateLastEvent);
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
