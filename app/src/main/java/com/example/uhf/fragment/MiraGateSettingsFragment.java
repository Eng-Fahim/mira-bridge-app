package com.example.uhf.fragment;

import android.content.Context;
import android.media.AudioAttributes;
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

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * ✅ MiraGateSettingsFragment v2.2 — VANCH Fixed Support
 *
 * إصلاحات v2.2:
 * - ✅ زر "اختبار الاتصال" داخل نافذة الإضافة (لـ VANCH فقط)
 * - ✅ فحص دوري لحالة بوابات VANCH (TCP ping كل 10 ثوان)
 * - ✅ عرض حالة VANCH في البطاقة (🟢 متصل / 🔴 غير متصل)
 * - ✅ منع حفظ بوابة VANCH غير متاحة عبر تنبيه تأكيد
 * - ✅ اكتشاف الشبكة عند فتح الحقل (IP:Port validation)
 *
 * إصلاحات v2.1 (محفوظة):
 * - ✅ SeekBar range 5-30 dBm
 * - ✅ Spinner لنوع الجهاز (C72 / VANCH / Bluetooth / Simulator)
 * - ✅ testSound() بقائمة اختيار
 * - ✅ عرض نوع الجهاز في بطاقة البوابة
 * - ✅ إصلاح null في lastEvent
 */
public class MiraGateSettingsFragment extends Fragment {

    private static final String TAG = "MiraGateSettingsFragment";
    private static final long STATUS_REFRESH_INTERVAL = 10000;

    // ✅ نطاق الطاقة
    private static final int POWER_MIN = 5;
    private static final int POWER_MAX = 30;

    // ✅ ثوابت VANCH
    private static final int VANCH_HEALTH_TIMEOUT_MS = 2000;
    private static final int VANCH_DEFAULT_PORT = 4001;

    private MiraGatesSettings settings;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;
    private SoundPool soundPool;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorService executor;
    private final Object EXECUTOR_LOCK = new Object();

    // ✅ ذاكرة مؤقتة لحالة VANCH (gateId → reachable)
    private final Map<String, Boolean> vanchHealthCache = new ConcurrentHashMap<>();

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

    private int selectedPassPos = 0;
    private int selectedDenyPos = 0;
    private int selectedAlarmPos = 0;

    // ═══════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_gate_settings, container, false);
        } catch (Exception e) {
            Log.e(TAG, "onCreateView inflate error: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context ctx = getContext();
            if (ctx == null) {
                Log.e(TAG, "onViewCreated: context is null");
                return;
            }

            try { settings = MiraGatesSettings.getInstance(ctx); }
            catch (Exception e) { Log.e(TAG, "settings init error: " + e.getMessage(), e); }

            try { permissionManager = PermissionManager.getInstance(ctx); }
            catch (Exception e) { Log.e(TAG, "permissionManager init error: " + e.getMessage(), e); }

            try { branchesManager = MiraBranchesManager.getInstance(ctx); }
            catch (Exception e) { Log.e(TAG, "branchesManager init error: " + e.getMessage(), e); }

            try { isArabic = Locale.getDefault().getLanguage().equals("ar"); }
            catch (Exception ignored) { isArabic = true; }

            initSoundPool();
            initViews(view);
            setupSpinners();
            loadSettings();
            setupListeners();
            applyPermissions();
            loadBranches();
            loadGatesFromLocal();
            updateLinkedCountBadge();  
            loadGatesFromServer();
            loadGateStats();

            // ✅ [v2.2] أول فحص صحي بعد 3 ثوان
            mainHandler.postDelayed(this::checkVanchGatesHealth, 3000);

            startStatusRefresh();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated fatal error: " + e.getMessage(), e);
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "⚠️ خطأ في تهيئة الشاشة", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initSoundPool() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            soundPool = new SoundPool.Builder()
                    .setMaxStreams(3)
                    .setAudioAttributes(attrs)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "SoundPool init error: " + e.getMessage(), e);
            soundPool = null;
        }
    }

    // ═══════════════════════════════════════════════════════
    // Executor
    // ═══════════════════════════════════════════════════════

    private ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown() || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "GateSettings-Worker");
                    t.setDaemon(true);
                    return t;
                });
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
            Log.w(TAG, "Task rejected: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "executeTask error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Views
    // ═══════════════════════════════════════════════════════

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

            if (seekRfidPower != null) {
                seekRfidPower.setMax(POWER_MAX);
            }

            if (btnAddGate != null && (btnAddGate.getText() == null
                    || btnAddGate.getText().toString().trim().isEmpty())) {
                btnAddGate.setText(isArabic ? "➕ إضافة" : "➕ Add");
            }
            if (btnRefreshStatus != null && (btnRefreshStatus.getText() == null
                    || btnRefreshStatus.getText().toString().trim().isEmpty())) {
                btnRefreshStatus.setText("🔄");
            }
            if (btnTestSound != null && (btnTestSound.getText() == null
                    || btnTestSound.getText().toString().trim().isEmpty())) {
                btnTestSound.setText(isArabic ? "🔊 اختبار" : "🔊 Test");
            }
            if (btnSaveSounds != null && (btnSaveSounds.getText() == null
                    || btnSaveSounds.getText().toString().trim().isEmpty())) {
                btnSaveSounds.setText(isArabic ? "💾 حفظ الأصوات" : "💾 Save Sounds");
            }
            if (btnSaveAll != null && (btnSaveAll.getText() == null
                    || btnSaveAll.getText().toString().trim().isEmpty())) {
                btnSaveAll.setText(isArabic ? "💾 حفظ الكل" : "💾 Save All");
            }

            if (rvGates != null) {
                rvGates.setLayoutManager(new LinearLayoutManager(getContext()));
                gateAdapter = new GateAdapter(gatesList,
                        this::removeGate,
                        this::toggleGateStatus,
                        this::showEditGateDialog);
                rvGates.setAdapter(gateAdapter);
            }
        } catch (Exception e) {
            Log.e(TAG, "initViews error: " + e.getMessage(), e);
        }
    }

/**
 * ✅ [v3.0] تحديث شارة عدد البوابات المربوطة على زر الإضافة
 */
private void updateLinkedCountBadge() {
    try {
        if (settings == null) return;
        int count = settings.getLinkedGateCount();

        if (btnAddGate != null) {
            String baseText = isArabic ? "➕ إضافة" : "➕ Add";
            String suffix = count > 0
                    ? " (" + count + " " + (isArabic ? "مربوط" : "linked") + ")"
                    : "";
            btnAddGate.setText(baseText + suffix);
        }
    } catch (Exception e) {
        Log.w(TAG, "updateLinkedCountBadge error: " + e.getMessage());
    }
}

    private void applyPermissions() {
        try {
            if (permissionManager == null) return;

            boolean canManageGates = permissionManager.isAdmin()
                    || permissionManager.isBranchManager()
                    || permissionManager.canEdit("gates");

            if (btnAddGate != null)
                btnAddGate.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
            if (btnSaveSounds != null)
                btnSaveSounds.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
            if (btnSaveAll != null)
                btnSaveAll.setVisibility(canManageGates ? View.VISIBLE : View.GONE);
        } catch (Exception e) {
            Log.e(TAG, "applyPermissions error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Branches
    // ═══════════════════════════════════════════════════════

    private void loadBranches() {
        try {
            int activeBranchId = 1;
            String activeBranchName = isArabic ? "الفرع الرئيسي" : "Main Branch";

            if (branchesManager != null) {
                try {
                    int id = branchesManager.getActiveBranchId();
                    if (id > 0) activeBranchId = id;

                    String name = branchesManager.getActiveBranchName();
                    if (name != null && !name.isEmpty()) {
                        activeBranchName = name;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "loadBranches values error: " + e.getMessage());
                }
            }

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
                                try {
                                    JSONObject b = arr.optJSONObject(i);
                                    if (b != null) {
                                        int id = b.optInt("id", 0);
                                        String name = b.optString("branch_name_ar",
                                                b.optString("branch_name", ""));
                                        if (id > 0 && !name.isEmpty()) {
                                            serverBranches.add(new BranchItem(id, name));
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "branch parse error: " + e.getMessage());
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
                    Log.e(TAG, "loadBranches network error: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "loadBranches error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Status Refresh + VANCH Health Check
    // ═══════════════════════════════════════════════════════

    private void startStatusRefresh() {
        statusRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded() && !isDetached()) {
                    loadGateStats();
                    checkVanchGatesHealth();   // ✅ [v2.2] فحص VANCH دورياً
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

    /**
     * ✅ [v2.2] فحص صحة بوابات VANCH Fixed عبر TCP ping
     * كل بوابة تُفحص في thread منفصل عبر executor
     */
    private void checkVanchGatesHealth() {
        if (!isAdded() || isDetached()) return;

        List<MiraGatesSettings.GateDevice> snapshot = new ArrayList<>(gatesList);

        for (MiraGatesSettings.GateDevice gate : snapshot) {
            if (gate == null) continue;
            if (!"vanch_fixed".equals(gate.deviceType)) continue;

            final String gateId = gate.gateId;
            final String ipAddress = gate.ipAddress;

            if (ipAddress == null || ipAddress.trim().isEmpty()) {
                // لا يوجد IP → offline
                vanchHealthCache.put(gateId, false);
                updateGateStatusInList(gate, false);
                continue;
            }

            executeTask(() -> {
                boolean reachable = isTcpReachable(ipAddress);
                Boolean previous = vanchHealthCache.put(gateId, reachable);

                if (previous == null || previous != reachable) {
                    Log.d(TAG, "VANCH health [" + gateId + "] "
                            + ipAddress + " → " + (reachable ? "online" : "offline"));

                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        MiraGatesSettings.GateDevice liveGate = findGateById(gateId);
                        if (liveGate != null) {
                            updateGateStatusInList(liveGate, reachable);
                        }
                    });
                }
            });
        }
    }

    /**
     * ✅ [v2.2] فحص TCP connectivity
     * يقبل "ip" أو "ip:port"
     */
    private boolean isTcpReachable(String ipAddressWithPort) {
        String host;
        int port;

        try {
            String[] parts = ipAddressWithPort.split(":");
            host = parts[0].trim();
            port = parts.length > 1
                    ? Integer.parseInt(parts[1].trim())
                    : VANCH_DEFAULT_PORT;

            if (host.isEmpty()) return false;

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port),
                        VANCH_HEALTH_TIMEOUT_MS);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private MiraGatesSettings.GateDevice findGateById(String gateId) {
        for (MiraGatesSettings.GateDevice g : gatesList) {
            if (g != null && g.gateId != null && g.gateId.equals(gateId)) {
                return g;
            }
        }
        return null;
    }

    private void updateGateStatusInList(MiraGatesSettings.GateDevice gate,
                                         boolean reachable) {
        try {
            String newStatus = reachable ? "online" : "offline";
            if (newStatus.equals(gate.status)) return;

            gate.status = newStatus;

            int index = gatesList.indexOf(gate);
            if (index >= 0 && gateAdapter != null) {
                gateAdapter.notifyItemChanged(index);
            }
        } catch (Exception e) {
            Log.w(TAG, "updateGateStatusInList error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Spinners
    // ═══════════════════════════════════════════════════════

    private void setupSpinners() {
        try {
            Context context = getContext();
            if (context == null) return;

            if (spSoundPass != null) {
                String[] passNames = getSoundNames(MiraGatesSettings.SoundOptions.PASS_SOUNDS);
                ArrayAdapter<String> passAdapter = new ArrayAdapter<>(context,
                        android.R.layout.simple_dropdown_item_1line, passNames);
                spSoundPass.setAdapter(passAdapter);
                spSoundPass.setOnItemClickListener((parent, view, position, id) ->
                        selectedPassPos = position);
            }

            if (spSoundDeny != null) {
                String[] denyNames = getSoundNames(MiraGatesSettings.SoundOptions.DENY_SOUNDS);
                ArrayAdapter<String> denyAdapter = new ArrayAdapter<>(context,
                        android.R.layout.simple_dropdown_item_1line, denyNames);
                spSoundDeny.setAdapter(denyAdapter);
                spSoundDeny.setOnItemClickListener((parent, view, position, id) ->
                        selectedDenyPos = position);
            }

            if (spSoundAlarm != null) {
                String[] alarmNames = getSoundNames(MiraGatesSettings.SoundOptions.ALARM_SOUNDS);
                ArrayAdapter<String> alarmAdapter = new ArrayAdapter<>(context,
                        android.R.layout.simple_dropdown_item_1line, alarmNames);
                spSoundAlarm.setAdapter(alarmAdapter);
                spSoundAlarm.setOnItemClickListener((parent, view, position, id) ->
                        selectedAlarmPos = position);
            }
        } catch (Exception e) {
            Log.e(TAG, "setupSpinners error: " + e.getMessage(), e);
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
        try {
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
            if (etAlarmDuration != null)
                etAlarmDuration.setText(String.valueOf(settings.getAlarmDuration()));

            if (seekRfidPower != null) {
                int power = settings.getRfidPower();
                power = Math.max(POWER_MIN, Math.min(POWER_MAX, power));
                seekRfidPower.setProgress(power);
            }
            if (tvRfidPowerValue != null) {
                int power = settings.getRfidPower();
                power = Math.max(POWER_MIN, Math.min(POWER_MAX, power));
                tvRfidPowerValue.setText(power + " dBm");
            }
        } catch (Exception e) {
            Log.e(TAG, "loadSettings error: " + e.getMessage(), e);
        }
    }

    private int findIndex(String[] array, String value) {
        if (array == null || value == null) return 0;
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(value)) return i;
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════
    // Listeners
    // ═══════════════════════════════════════════════════════

    private void setupListeners() {
        try {
            if (btnAddGate != null) {
                btnAddGate.setOnClickListener(v -> showAddGateDialog());
            }

            if (btnTestSound != null) {
                btnTestSound.setOnClickListener(v -> showTestSoundMenu());
            }

            if (btnRefreshStatus != null) {
                btnRefreshStatus.setOnClickListener(v -> {
                    loadGateStats();
                    loadGatesFromServer();
                    checkVanchGatesHealth();
                    Toast.makeText(getContext(),
                            isArabic ? "🔄 تم التحديث" : "🔄 Refreshed",
                            Toast.LENGTH_SHORT).show();
                });
            }

            if (btnSaveSounds != null) {
                btnSaveSounds.setOnClickListener(v -> saveSounds());
            }

            if (seekRfidPower != null) {
                seekRfidPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int clamped = Math.max(POWER_MIN, Math.min(POWER_MAX, progress));
                        if (clamped != progress) {
                            seekBar.setProgress(clamped);
                            return;
                        }
                        if (tvRfidPowerValue != null) {
                            tvRfidPowerValue.setText(progress + " dBm");
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            if (btnSaveAll != null) {
                btnSaveAll.setOnClickListener(v -> saveAllSettings());
            }
        } catch (Exception e) {
            Log.e(TAG, "setupListeners error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // اختبار الصوت
    // ═══════════════════════════════════════════════════════

    private void showTestSoundMenu() {
        try {
            if (!isAdded() || getContext() == null) return;

            String[] options = {
                isArabic ? "✅ صوت النجاح (Pass)" : "✅ Pass Sound",
                isArabic ? "❌ صوت الرفض (Deny)" : "❌ Deny Sound",
                isArabic ? "🚨 صوت الإنذار (Alarm)" : "🚨 Alarm Sound"
            };

            new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "🔊 اختبار أي صوت؟" : "🔊 Test which sound?")
                .setItems(options, (d, which) -> {
                    String soundKey = null;
                    switch (which) {
                        case 0:
                            if (selectedPassPos >= 0
                                    && selectedPassPos < MiraGatesSettings.SoundOptions.PASS_SOUNDS.length) {
                                soundKey = MiraGatesSettings.SoundOptions.PASS_SOUNDS[selectedPassPos];
                            }
                            break;
                        case 1:
                            if (selectedDenyPos >= 0
                                    && selectedDenyPos < MiraGatesSettings.SoundOptions.DENY_SOUNDS.length) {
                                soundKey = MiraGatesSettings.SoundOptions.DENY_SOUNDS[selectedDenyPos];
                            }
                            break;
                        case 2:
                            if (selectedAlarmPos >= 0
                                    && selectedAlarmPos < MiraGatesSettings.SoundOptions.ALARM_SOUNDS.length) {
                                soundKey = MiraGatesSettings.SoundOptions.ALARM_SOUNDS[selectedAlarmPos];
                            }
                            break;
                    }

                    if (soundKey != null && !soundKey.isEmpty()) {
                        if ("silent".equals(soundKey)) {
                            Toast.makeText(getContext(),
                                    isArabic ? "🔇 الصوت صامت" : "🔇 Sound is silent",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            SoundGenerator.playSound(soundKey);
                            Toast.makeText(getContext(),
                                    "🔊 " + getSoundDisplayName(soundKey),
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "playSound error: " + e.getMessage(), e);
                            Toast.makeText(getContext(),
                                    isArabic ? "⚠️ فشل تشغيل الصوت" : "⚠️ Play failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();

        } catch (Exception e) {
            Log.e(TAG, "showTestSoundMenu error: " + e.getMessage(), e);
        }
    }

    private void saveSounds() {
        try {
            if (!isAdded() || settings == null) return;

            if (selectedPassPos >= 0 && selectedPassPos < MiraGatesSettings.SoundOptions.PASS_SOUNDS.length) {
                settings.setSoundPass(MiraGatesSettings.SoundOptions.PASS_SOUNDS[selectedPassPos]);
            }
            if (selectedDenyPos >= 0 && selectedDenyPos < MiraGatesSettings.SoundOptions.DENY_SOUNDS.length) {
                settings.setSoundDeny(MiraGatesSettings.SoundOptions.DENY_SOUNDS[selectedDenyPos]);
            }
            if (selectedAlarmPos >= 0 && selectedAlarmPos < MiraGatesSettings.SoundOptions.ALARM_SOUNDS.length) {
                settings.setSoundAlarm(MiraGatesSettings.SoundOptions.ALARM_SOUNDS[selectedAlarmPos]);
            }

            Toast.makeText(getContext(),
                    isArabic ? "✅ تم حفظ الأصوات" : "✅ Sounds saved",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "saveSounds error: " + e.getMessage(), e);
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(),
                        isArabic ? "❌ فشل الحفظ" : "❌ Save failed",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveAllSettings() {
        try {
            if (!isAdded() || settings == null) return;

            if (switchVibration != null) settings.setVibrationEnabled(switchVibration.isChecked());
            if (switchFlash != null) settings.setFlashEnabled(switchFlash.isChecked());

            if (etAlarmDuration != null) {
                String durationInput = etAlarmDuration.getText().toString().trim();
                int duration = 10;
                try {
                    if (!durationInput.isEmpty()) duration = Integer.parseInt(durationInput);
                    duration = Math.max(1, Math.min(60, duration));
                } catch (NumberFormatException ignored) {}
                settings.setAlarmDuration(duration);
            }

            if (seekRfidPower != null) {
                int power = seekRfidPower.getProgress();
                power = Math.max(POWER_MIN, Math.min(POWER_MAX, power));
                settings.setRfidPower(power);
            }

            Toast.makeText(getContext(),
                    isArabic ? "✅ تم حفظ الإعدادات العامة" : "✅ General settings saved",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "saveAllSettings error: " + e.getMessage(), e);
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(),
                        isArabic ? "❌ فشل الحفظ" : "❌ Save failed",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Gates
    // ═══════════════════════════════════════════════════════

    private void loadGatesFromLocal() {
        try {
            if (settings == null) return;
            gatesList.clear();
            List<MiraGatesSettings.GateDevice> localGates = settings.getGates();
            if (localGates != null) {
                gatesList.addAll(localGates);
            }
            if (gateAdapter != null) {
                gateAdapter.notifyDataSetChanged();
            }
        } catch (Exception e) {
            Log.e(TAG, "loadGatesFromLocal error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Add Gate Dialog — مع اختبار VANCH
    // ═══════════════════════════════════════════════════════

    private void showAddGateDialog() {
        try {
            if (!isAdded() || getActivity() == null) return;

            Context context = getActivity();

            if (permissionManager != null
                    && !(permissionManager.canEdit("gates")
                    || permissionManager.isAdmin()
                    || permissionManager.isBranchManager())) {
                Toast.makeText(context,
                        isArabic ? "⛔ غير مصرح" : "⛔ Access Denied",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(isArabic ? "➕ إضافة بوابة جديدة" : "➕ Add New Gate");

            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 20, 40, 10);

            final EditText etGateName = new EditText(context);
            etGateName.setHint(isArabic ? "اسم البوابة" : "Gate Name");
            etGateName.setSingleLine(true);
            layout.addView(etGateName);

            final EditText etLocation = new EditText(context);
            etLocation.setHint(isArabic ? "الموقع (مثال: المدخل)" : "Location");
            etLocation.setSingleLine(true);
            layout.addView(etLocation);

            TextView tvBranchLabel = new TextView(context);
            tvBranchLabel.setText(isArabic ? "🏢 الفرع التابع له:" : "🏢 Branch:");
            tvBranchLabel.setTextSize(14);
            tvBranchLabel.setPadding(0, 20, 0, 5);
            layout.addView(tvBranchLabel);

            final Spinner spBranch = new Spinner(context);
            ArrayAdapter<BranchItem> branchAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, branchesList);
            branchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spBranch.setAdapter(branchAdapter);
            layout.addView(spBranch);

            // نوع الجهاز
            TextView tvDeviceLabel = new TextView(context);
            tvDeviceLabel.setText(isArabic ? "📡 نوع الجهاز:" : "📡 Device Type:");
            tvDeviceLabel.setTextSize(14);
            tvDeviceLabel.setPadding(0, 20, 0, 5);
            layout.addView(tvDeviceLabel);

            final Spinner spDeviceType = new Spinner(context);
            String[] deviceTypes = {
                isArabic ? "📱 Chainway C72 (يدوي)" : "📱 Chainway C72 (Handheld)",
                isArabic ? "📡 VANCH Fixed (شبكة)" : "📡 VANCH Fixed (Network)",
                isArabic ? "📶 Bluetooth عام" : "📶 Generic Bluetooth",
                isArabic ? "🧪 Simulator (اختبار)" : "🧪 Simulator (Test)"
            };
            final String[] deviceTypeValues = {
                "chainway_c72",
                "vanch_fixed",
                "generic_bluetooth",
                "simulator"
            };
            ArrayAdapter<String> deviceAdapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, deviceTypes);
            deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spDeviceType.setAdapter(deviceAdapter);
            layout.addView(spDeviceType);

            // حقل IP:Port (يظهر لـ VANCH فقط)
            final TextView tvIpLabel = new TextView(context);
            tvIpLabel.setText(isArabic ? "🌐 عنوان IP:Port" : "🌐 IP:Port");
            tvIpLabel.setTextSize(14);
            tvIpLabel.setPadding(0, 20, 0, 5);
            tvIpLabel.setVisibility(View.GONE);
            layout.addView(tvIpLabel);

            final EditText etIpAddress = new EditText(context);
            etIpAddress.setHint(isArabic ? "192.168.1.100:4001" : "192.168.1.100:4001");
            etIpAddress.setSingleLine(true);
            etIpAddress.setVisibility(View.GONE);
            layout.addView(etIpAddress);

            // ✅ [v2.2] زر اختبار الاتصال (VANCH فقط)
            final MaterialButton btnTestConnection = new MaterialButton(context);
            btnTestConnection.setText(isArabic ? "🔍 اختبار الاتصال" : "🔍 Test Connection");
            btnTestConnection.setVisibility(View.GONE);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.topMargin = 20;
            btnTestConnection.setLayoutParams(btnParams);
            layout.addView(btnTestConnection);

            // حالة اختبار الاتصال
            final TextView tvTestResult = new TextView(context);
            tvTestResult.setTextSize(13);
            tvTestResult.setPadding(0, 10, 0, 0);
            tvTestResult.setVisibility(View.GONE);
            layout.addView(tvTestResult);

            // متغير لحفظ حالة الاختبار
            final boolean[] lastTestSucceeded = {false};
            final String[] lastTestedIp = {""};

            // إظهار/إخفاء حقول VANCH
            spDeviceType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                        int position, long id) {
                    boolean showIp = position == 1; // VANCH Fixed
                    tvIpLabel.setVisibility(showIp ? View.VISIBLE : View.GONE);
                    etIpAddress.setVisibility(showIp ? View.VISIBLE : View.GONE);
                    btnTestConnection.setVisibility(showIp ? View.VISIBLE : View.GONE);
                    tvTestResult.setVisibility(View.GONE);

                    // إعادة تعيين حالة الاختبار عند تغيير النوع
                    if (!showIp) {
                        lastTestSucceeded[0] = false;
                        lastTestedIp[0] = "";
                    }
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });

            // زر اختبار الاتصال
            btnTestConnection.setOnClickListener(v -> {
                String ipInput = etIpAddress.getText().toString().trim();
                if (ipInput.isEmpty()) {
                    Toast.makeText(context,
                            isArabic ? "⚠️ أدخل IP أولاً" : "⚠️ Enter IP first",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!isValidIpFormat(ipInput)) {
                    tvTestResult.setVisibility(View.VISIBLE);
                    tvTestResult.setTextColor(0xFFDC2626);
                    tvTestResult.setText(isArabic
                            ? "❌ صيغة IP غير صحيحة (مثال: 192.168.1.100:4001)"
                            : "❌ Invalid IP format (e.g., 192.168.1.100:4001)");
                    return;
                }

                btnTestConnection.setEnabled(false);
                btnTestConnection.setText(isArabic ? "⏳ جاري الفحص..." : "⏳ Testing...");
                tvTestResult.setVisibility(View.VISIBLE);
                tvTestResult.setTextColor(0xFF6B7280);
                tvTestResult.setText(isArabic ? "🔍 جاري الاتصال..." : "🔍 Connecting...");

                final String ip = ipInput;
                new Thread(() -> {
                    boolean reachable = isTcpReachable(ip);
                    mainHandler.post(() -> {
                        btnTestConnection.setEnabled(true);
                        btnTestConnection.setText(isArabic
                                ? "🔍 اختبار الاتصال" : "🔍 Test Connection");

                        lastTestSucceeded[0] = reachable;
                        lastTestedIp[0] = ip;

                        if (reachable) {
                            tvTestResult.setTextColor(0xFF16A34A);
                            tvTestResult.setText(isArabic
                                    ? "✅ الجهاز متاح — يمكنك المتابعة"
                                    : "✅ Device reachable — you can proceed");
                        } else {
                            tvTestResult.setTextColor(0xFFDC2626);
                            tvTestResult.setText(isArabic
                                    ? "❌ غير متاح — تحقق من IP والشبكة"
                                    : "❌ Unreachable — check IP and network");
                        }
                    });
                }).start();
            });

            builder.setView(layout);
            builder.setPositiveButton(isArabic ? "إضافة" : "Add", (dialog, which) -> {
                try {
                    String gateName = etGateName.getText().toString().trim();
                    String location = etLocation.getText().toString().trim();
                    String ipAddress = etIpAddress.getText().toString().trim();

                    BranchItem selectedBranch = (BranchItem) spBranch.getSelectedItem();
                    int branchId = selectedBranch != null ? selectedBranch.id : 1;
                    String branchName = selectedBranch != null
                            ? selectedBranch.name : "Main Branch";

                    int deviceTypePos = spDeviceType.getSelectedItemPosition();
                    String deviceType = deviceTypeValues[deviceTypePos];

                    if (gateName.isEmpty()) {
                        Toast.makeText(context,
                                isArabic ? "⚠️ اسم البوابة مطلوب" : "⚠️ Name required",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // ✅ فحص VANCH: يجب اختبار الاتصال + نجاحه أو تأكيد المستخدم
                    if ("vanch_fixed".equals(deviceType)) {
                        if (ipAddress.isEmpty()) {
                            Toast.makeText(context,
                                    isArabic ? "⚠️ IP مطلوب لـ VANCH"
                                            : "⚠️ IP required for VANCH",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }

                        boolean tested = ipAddress.equals(lastTestedIp[0]);
                        boolean passed = lastTestSucceeded[0];

                        if (!tested || !passed) {
                            // نافذة تأكيد
                            new AlertDialog.Builder(context)
                                    .setTitle(isArabic ? "⚠️ لم يتم اختبار الاتصال"
                                            : "⚠️ Connection not verified")
                                    .setMessage(isArabic
                                            ? "لم يتم اختبار الاتصال بالجهاز أو فشل الاختبار.\n\n"
                                              + "هل تريد المتابعة على أي حال؟ (قد لا تعمل البوابة)"
                                            : "Connection not tested or failed.\n\n"
                                              + "Continue anyway? (Gate may not work)")
                                    .setPositiveButton(isArabic ? "متابعة" : "Continue",
                                            (d2, w2) -> createGate(context, gateName, location,
                                                    ipAddress, deviceType, branchId, branchName))
                                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                                    .show();
                            return;
                        }
                    }

                    createGate(context, gateName, location, ipAddress,
                            deviceType, branchId, branchName);

                } catch (Exception e) {
                    Log.e(TAG, "addGate dialog click error: " + e.getMessage(), e);
                }
            });
            builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
            builder.show();

        } catch (Exception e) {
            Log.e(TAG, "showAddGateDialog error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ [v2.2] إنشاء البوابة بعد التحقق
     */
    private void createGate(Context context, String gateName, String location,
                             String ipAddress, String deviceType,
                             int branchId, String branchName) {
        try {
            MiraGatesSettings.GateDevice gate = new MiraGatesSettings.GateDevice();
            gate.gateId = "gate_" + System.currentTimeMillis();
            gate.gateName = gateName;
            gate.location = location;
            gate.deviceType = deviceType;
            gate.status = "offline";
            gate.ipAddress = ipAddress;
            gate.port = VANCH_DEFAULT_PORT;
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

            // ✅ [v2.2] ابدأ فحص VANCH فوراً بعد الإضافة
            if ("vanch_fixed".equals(deviceType)) {
                mainHandler.postDelayed(this::checkVanchGatesHealth, 500);
            }

            updateLinkedCountBadge();   // ✅ [v3.0]

Toast.makeText(context,
        isArabic
            ? "✅ تمت إضافة البوابة لفرع: " + branchName
            : "✅ Gate added to: " + branchName,
        Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "createGate error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ [v2.2] فحص صيغة IP
     */
    private boolean isValidIpFormat(String ip) {
        if (ip == null || ip.isEmpty()) return false;

        String host;
        try {
            String[] parts = ip.split(":");
            host = parts[0].trim();
            if (parts.length > 1) {
                int port = Integer.parseInt(parts[1].trim());
                if (port < 1 || port > 65535) return false;
            }
        } catch (Exception e) {
            return false;
        }

        // فحص IPv4 بسيط
        String[] octets = host.split("\\.");
        if (octets.length != 4) return false;

        for (String octet : octets) {
            try {
                int v = Integer.parseInt(octet);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════
    // Edit Gate Dialog
    // ═══════════════════════════════════════════════════════

    private void showEditGateDialog(int position) {
        try {
            if (!isAdded() || getActivity() == null) return;
            if (position < 0 || position >= gatesList.size()) return;

            Context context = getActivity();

            if (permissionManager != null
                    && !(permissionManager.canEdit("gates")
                    || permissionManager.isAdmin()
                    || permissionManager.isBranchManager())) {
                Toast.makeText(context,
                        isArabic ? "⛔ غير مصرح" : "⛔ Access Denied",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            MiraGatesSettings.GateDevice existingGate = gatesList.get(position);
            if (existingGate == null) return;

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle((isArabic ? "✏️ تعديل: " : "✏️ Edit: ")
                    + safe(existingGate.gateName));

            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(40, 20, 40, 10);

            final EditText etGateName = new EditText(context);
            etGateName.setHint(isArabic ? "اسم البوابة" : "Gate Name");
            etGateName.setSingleLine(true);
            etGateName.setText(safe(existingGate.gateName));
            layout.addView(etGateName);

            final EditText etLocation = new EditText(context);
            etLocation.setHint(isArabic ? "الموقع" : "Location");
            etLocation.setSingleLine(true);
            etLocation.setText(safe(existingGate.location));
            layout.addView(etLocation);

            TextView tvBranchLabel = new TextView(context);
            tvBranchLabel.setText(isArabic ? "🏢 الفرع:" : "🏢 Branch:");
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

            // IP field (يظهر فقط إذا VANCH)
            final EditText etIpAddress = new EditText(context);
            etIpAddress.setHint(isArabic ? "عنوان IP:Port" : "IP:Port");
            etIpAddress.setSingleLine(true);
            etIpAddress.setText(safe(existingGate.ipAddress));
            if (!"vanch_fixed".equals(existingGate.deviceType)) {
                etIpAddress.setVisibility(View.GONE);
            }
            layout.addView(etIpAddress);

            builder.setView(layout);
            builder.setPositiveButton(isArabic ? "حفظ" : "Save", (dialog, which) -> {
                try {
                    String gateName = etGateName.getText().toString().trim();
                    String location = etLocation.getText().toString().trim();
                    String ipAddress = etIpAddress.getText().toString().trim();

                    BranchItem selectedBranch = (BranchItem) spBranch.getSelectedItem();
                    int branchId = selectedBranch != null
                            ? selectedBranch.id : existingGate.branchId;
                    String branchName = selectedBranch != null
                            ? selectedBranch.name : existingGate.branchName;

                    if (gateName.isEmpty()) {
                        Toast.makeText(context,
                                isArabic ? "⚠️ اسم البوابة مطلوب" : "⚠️ Name required",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

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

                    // ✅ إعادة فحص VANCH بعد التعديل
                    if ("vanch_fixed".equals(existingGate.deviceType)) {
                        mainHandler.postDelayed(this::checkVanchGatesHealth, 300);
                    }

                    Toast.makeText(context,
                            isArabic ? "✅ تم تحديث البوابة" : "✅ Gate updated",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "editGate dialog click error: " + e.getMessage(), e);
                }
            });
            builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
            builder.show();

        } catch (Exception e) {
            Log.e(TAG, "showEditGateDialog error: " + e.getMessage(), e);
        }
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private void syncGateToServer(MiraGatesSettings.GateDevice gate, int branchId) {
        if (gate == null) return;

        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;

                JSONObject payload = new JSONObject();
                payload.put("gate_id", safe(gate.gateId));
                payload.put("gate_name", safe(gate.gateName));
                payload.put("gate_location", safe(gate.location));
                payload.put("device_type", safe(gate.deviceType));
                payload.put("ip_address", safe(gate.ipAddress));
                payload.put("port", gate.port);
                payload.put("branch_id", branchId);
                payload.put("rfid_power", gate.rfidPower);
                payload.put("vibration_enabled", gate.vibrationEnabled);
                payload.put("flash_enabled", gate.flashEnabled);
                payload.put("sound_pass", safe(gate.soundPass));
                payload.put("sound_deny", safe(gate.soundDeny));
                payload.put("sound_alarm", safe(gate.soundAlarm));
                payload.put("alarm_duration", gate.alarmDuration);

                MiraApiClient.ApiResponse response = client.createGate(payload);

                mainHandler.post(() -> {
                    if (isAdded() && getContext() != null) {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(),
                                    isArabic ? "✅ تمت المزامنة" : "✅ Synced",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(),
                                    isArabic ? "⚠️ حفظ محلي فقط" : "⚠️ Saved locally",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "syncGateToServer error: " + e.getMessage(), e);
            }
        });
    }

    private void updateGateOnServer(MiraGatesSettings.GateDevice gate) {
        if (gate == null) return;

        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;

                JSONObject payload = new JSONObject();
                payload.put("gate_id", safe(gate.gateId));
                payload.put("gate_name", safe(gate.gateName));
                payload.put("gate_location", safe(gate.location));
                payload.put("device_type", safe(gate.deviceType));
                payload.put("ip_address", safe(gate.ipAddress));
                payload.put("port", gate.port);
                payload.put("branch_id", gate.branchId);

                MiraApiClient.ApiResponse response = client.postWithAuth("/gates/update", payload);

                mainHandler.post(() -> {
                    if (isAdded() && getContext() != null) {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(),
                                    isArabic ? "✅ تمت المزامنة" : "✅ Synced",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateGateOnServer error: " + e.getMessage(), e);
            }
        });
    }
/**
 * ✅ [v3.0] نافذة ربط/فك ربط متعدد
 */
private void confirmLinkGate(MiraGatesSettings.GateDevice gate) {
    try {
        if (!isAdded() || getContext() == null || gate == null || settings == null) return;

        boolean isCurrentlyLinked = settings.isGateLinked(gate.gateId);
        int totalLinked = settings.getLinkedGateCount();

        String typeDisplay = deviceTypeDisplayName(gate.deviceType);
        String ipLine = (gate.ipAddress != null && !gate.ipAddress.trim().isEmpty())
                ? "\n🌐 " + gate.ipAddress : "";

        String title = isArabic
                ? (isCurrentlyLinked ? "🔓 إلغاء ربط البوابة" : "📌 ربط البوابة")
                : (isCurrentlyLinked ? "🔓 Unlink Gate" : "📌 Link Gate");

        String message = isArabic
                ? (isCurrentlyLinked
                        ? "هذه البوابة مربوطة حالياً:\n\n📌 " + safe(gate.gateName)
                          + "\n\n📊 إجمالي المربوط: " + totalLinked
                          + "\n\nبعد إلغاء الربط ستتوقف هذه البوابة عن المسح."
                        : "سيتم إضافة هذه البوابة للقائمة النشطة:\n\n"
                          + "📌 " + safe(gate.gateName) + "\n"
                          + "📡 " + typeDisplay + ipLine
                          + "\n\n📊 سيصبح العدد: " + (totalLinked + 1)
                          + "\n\nستعمل هذه البوابة بالتوازي مع البوابات المربوطة الأخرى.")
                : (isCurrentlyLinked
                        ? "This gate is currently linked:\n\n📌 " + safe(gate.gateName)
                          + "\n\n📊 Total linked: " + totalLinked
                          + "\n\nUnlinking will stop this gate."
                        : "This gate will be added to active list:\n\n"
                          + "📌 " + safe(gate.gateName) + "\n"
                          + "📡 " + typeDisplay + ipLine
                          + "\n\n📊 New total: " + (totalLinked + 1)
                          + "\n\nIt will run in parallel with other linked gates.");

        AlertDialog.Builder b = new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);

        if (isCurrentlyLinked) {
            b.setPositiveButton(isArabic ? "🔓 إلغاء الربط" : "🔓 Unlink",
                    (d, w) -> {
                        settings.removeLinkedGate(gate.gateId);
                        loadGatesFromLocal();
                        updateLinkedCountBadge();
                        Toast.makeText(getContext(),
                                isArabic ? "🔓 تم إلغاء ربط: " + safe(gate.gateName)
                                        : "🔓 Unlinked: " + safe(gate.gateName),
                                Toast.LENGTH_SHORT).show();
                        Log.i(TAG, "Gate unlinked: " + gate.gateId
                                + " (remaining=" + settings.getLinkedGateCount() + ")");
                    });
        } else {
            b.setPositiveButton(isArabic ? "📌 ربط الآن" : "📌 Link Now",
                    (d, w) -> {
                        settings.addLinkedGate(gate.gateId);
                        loadGatesFromLocal();
                        updateLinkedCountBadge();
                        Toast.makeText(getContext(),
                                isArabic ? "✅ تم ربط: " + safe(gate.gateName)
                                          + " (الإجمالي: "
                                          + settings.getLinkedGateCount() + ")"
                                        : "✅ Linked: " + safe(gate.gateName)
                                          + " (total: "
                                          + settings.getLinkedGateCount() + ")",
                                Toast.LENGTH_LONG).show();
                        Log.i(TAG, "Gate linked: " + gate.gateId
                                + " (" + gate.deviceType + ")");
                    });
        }

        b.show();

    } catch (Exception e) {
        Log.e(TAG, "confirmLinkGate error: " + e.getMessage(), e);
    }
}
/** ✅ [v2.3] اسم نوع الجهاز للعرض */
private String deviceTypeDisplayName(String type) {
    if (type == null) return "Unknown";
    switch (type) {
        case "vanch_fixed":       return isArabic ? "📡 VANCH Fixed" : "📡 VANCH Fixed";
        case "chainway_c72":      return isArabic ? "📱 Chainway C72" : "📱 Chainway C72";
        case "generic_bluetooth": return isArabic ? "📶 Bluetooth" : "📶 Bluetooth";
        case "simulator":         return isArabic ? "🧪 Simulator" : "🧪 Simulator";
        default:                  return type;
    }
}
    private void removeGate(int position) {
        try {
            if (!isAdded() || getContext() == null) return;

            if (permissionManager != null
                    && !(permissionManager.canDelete("gates") || permissionManager.isAdmin())) {
                Toast.makeText(getContext(),
                        isArabic ? "⛔ غير مصرح" : "⛔ Access Denied",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            if (position >= 0 && position < gatesList.size()) {
                MiraGatesSettings.GateDevice gate = gatesList.get(position);
                if (gate == null) return;

                // نافذة تأكيد
                new AlertDialog.Builder(requireContext())
                        .setTitle(isArabic ? "🗑 حذف البوابة" : "🗑 Delete Gate")
                        .setMessage(isArabic
                                ? "هل تريد حذف: " + safe(gate.gateName) + "؟"
                                : "Delete: " + safe(gate.gateName) + "?")
                        .setPositiveButton(isArabic ? "حذف" : "Delete", (d, w) -> {
                                                        // ✅ [v3.0] إزالة تلقائية من القائمة المربوطة
if (gate.gateId != null && settings.isGateLinked(gate.gateId)) {
    settings.removeLinkedGate(gate.gateId);
    Log.w(TAG, "Linked gate removed — auto-unlinked");
}

if (settings != null) {
    settings.removeGate(gate.gateId);
}
vanchHealthCache.remove(gate.gateId);
loadGatesFromLocal();
updateLinkedCountBadge();

                            Toast.makeText(getContext(),
                                    isArabic
                                        ? "🗑 تم حذف: " + safe(gate.gateName)
                                        : "🗑 Deleted: " + safe(gate.gateName),
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                        .show();
            }
        } catch (Exception e) {
            Log.e(TAG, "removeGate error: " + e.getMessage(), e);
        }
    }

    private void toggleGateStatus(int position, boolean open) {
        try {
            if (!isAdded() || getContext() == null) return;

            if (permissionManager != null
                    && !permissionManager.isAdmin()
                    && !permissionManager.isBranchManager()) {
                Toast.makeText(getContext(),
                        isArabic ? "⛔ غير مصرح" : "⛔ Access Denied",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            if (position >= 0 && position < gatesList.size()) {
                MiraGatesSettings.GateDevice gate = gatesList.get(position);
                if (gate == null) return;

                executeTask(() -> {
                    try {
                        Context context = getContext();
                        if (context == null) return;

                        MiraApiClient client = MiraApiClient.getInstance(context);
                        if (client == null) return;

                        JSONObject payload = new JSONObject();
                        payload.put("gate_id", safe(gate.gateId));
                        payload.put("command", open ? "ARM" : "DISARM");
                        payload.put("branch_id", gate.branchId);

                        MiraApiClient.ApiResponse response =
                                client.postWithAuth("/gate-command", payload);

                        mainHandler.post(() -> {
                            if (isAdded() && getContext() != null) {
                                if (response != null && response.isSuccess) {
                                    gate.status = open ? "online" : "offline";
                                    if (gateAdapter != null) gateAdapter.notifyItemChanged(position);

                                    Toast.makeText(getContext(),
                                            open
                                                ? (isArabic ? "🚪 تفعيل: " : "🚪 Enabled: ")
                                                    + safe(gate.gateName)
                                                : (isArabic ? "🔒 إيقاف: " : "🔒 Disabled: ")
                                                    + safe(gate.gateName),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(getContext(),
                                            isArabic ? "❌ فشل" : "❌ Failed",
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "toggleGateStatus network error: " + e.getMessage(), e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "toggleGateStatus error: " + e.getMessage(), e);
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
                    try {
                        JSONObject gateJson = gatesArray.optJSONObject(i);
                        if (gateJson != null) {
                            String gateId = gateJson.optString("gate_id",
                                    gateJson.optString("id", ""));
                            String status = gateJson.optString("status", "offline");

                            for (MiraGatesSettings.GateDevice gate : gatesList) {
                                if (gate.gateId != null && gate.gateId.equals(gateId)) {
                                    // ✅ لا تفرض الحالة على VANCH — الفحص الصحي المحلي أدق
                                    if (!"vanch_fixed".equals(gate.deviceType)) {
                                        gate.status = status;
                                    }
                                    gate.passCount = gateJson.optInt("pass_count",
                                            gateJson.optInt("scan_count", 0));
                                    gate.denyCount = gateJson.optInt("deny_count", 0);
                                    gate.lastEvent = gateJson.optString("last_event",
                                            gateJson.optString("last_heartbeat", ""));
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "gate parse error: " + e.getMessage());
                    }
                }
                if (gateAdapter != null) gateAdapter.notifyDataSetChanged();
            }

            if (tvLastEvent != null) {
                String lastEventText = statsObj.optString("last_event", "");
                if (lastEventText.isEmpty() || "null".equalsIgnoreCase(lastEventText)) {
                    lastEventText = isArabic ? "لا أحداث" : "No events";
                }
                tvLastEvent.setText(lastEventText);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateGateStats error: " + e.getMessage(), e);
        }
    }

    private void loadGatesFromServer() {
        if (permissionManager == null) return;

        boolean isGlobalAdmin = permissionManager.isSystemAdmin()
                || permissionManager.isOrgAdmin();
        int currentBranchId = branchesManager != null
                ? branchesManager.getActiveBranchId() : 0;

        executeTask(() -> {
            try {
                Context context = getContext();
                if (context == null) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                if (client == null) return;

                String endpoint = isGlobalAdmin
                        ? "/gates?all=true"
                        : "/gates?branch_id=" + currentBranchId;
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
                            try {
                                JSONObject gateJson = gatesArray.optJSONObject(i);
                                if (gateJson != null) {
                                    MiraGatesSettings.GateDevice gate =
                                            new MiraGatesSettings.GateDevice();
                                    gate.gateId = gateJson.optString("gate_id",
                                            gateJson.optString("id", ""));
                                    gate.gateName = gateJson.optString("gate_name",
                                            gateJson.optString("name", ""));
                                    gate.location = gateJson.optString("gate_location",
                                            gateJson.optString("location", ""));
                                    gate.deviceType = gateJson.optString("device_type",
                                            "chainway_c72");
                                    gate.status = gateJson.optString("status", "offline");
                                    gate.ipAddress = gateJson.optString("ip_address", "");
                                    gate.port = gateJson.optInt("port", VANCH_DEFAULT_PORT);
                                    gate.passCount = gateJson.optInt("scan_count",
                                            gateJson.optInt("pass_count", 0));
                                    gate.denyCount = gateJson.optInt("deny_count", 0);
                                    gate.lastEvent = gateJson.optString("last_heartbeat",
                                            gateJson.optString("last_event", ""));
                                    gate.branchId = gateJson.optInt("branch_id", currentBranchId);
                                    gate.branchName = gateJson.optString("branch_name", "");
                                    serverGates.add(gate);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "gate parse error: " + e.getMessage());
                            }
                        }

                        if (!serverGates.isEmpty()) {
                            if (settings != null) {
                                settings.saveGates(serverGates);
                            }
                            mainHandler.post(() -> {
                                if (isAdded()) {
                                    loadGatesFromLocal();
                                    // ✅ [v2.2] فحص VANCH بعد التحميل من السيرفر
                                    mainHandler.postDelayed(
                                            this::checkVanchGatesHealth, 300);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadGatesFromServer error: " + e.getMessage(), e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // Lifecycle Cleanup
    // ═══════════════════════════════════════════════════════

    @Override
    public void onDestroyView() {
        try {
            if (statusRefreshRunnable != null) {
                mainHandler.removeCallbacks(statusRefreshRunnable);
                statusRefreshRunnable = null;
            }
            mainHandler.removeCallbacksAndMessages(null);
            shutdownExecutorSafely();
            vanchHealthCache.clear();
        } catch (Exception e) {
            Log.e(TAG, "onDestroyView error: " + e.getMessage(), e);
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        try {
            if (soundPool != null) {
                soundPool.release();
                soundPool = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage(), e);
        }
        super.onDestroy();
    }

    private void shutdownExecutorSafely() {
        synchronized (EXECUTOR_LOCK) {
            if (executor != null && !executor.isShutdown()) {
                try {
                    executor.shutdown();
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.e(TAG, "shutdownExecutor error: " + e.getMessage());
                } finally {
                    executor = null;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // BranchItem
    // ═══════════════════════════════════════════════════════

    private static class BranchItem {
        int id;
        String name;

        BranchItem(int id, String name) {
            this.id = id;
            if (name != null && !name.isEmpty()) {
                this.name = name;
            } else {
                this.name = "فرع " + id;
            }
        }

        @Override
        public String toString() {
            return name != null ? name : "";
        }
    }

    // ═══════════════════════════════════════════════════════
    // GateAdapter
    // ═══════════════════════════════════════════════════════

    private class GateAdapter extends RecyclerView.Adapter<GateAdapter.GateViewHolder> {

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
    try {
        if (position < 0 || position >= gates.size()) return;
        MiraGatesSettings.GateDevice gate = gates.get(position);
        if (gate == null) return;

        if (holder.tvGateName != null) {
            String name = gate.gateName != null && !gate.gateName.isEmpty()
                    ? gate.gateName
                    : (isArabic ? "بوابة" : "Gate");
            holder.tvGateName.setText(name);
        }

        if (holder.tvGateLocation != null) {
            String location = gate.location != null && !gate.location.isEmpty()
                    ? gate.location
                    : (isArabic ? "غير محدد" : "Not specified");
            holder.tvGateLocation.setText("📍 " + location);
        }

        if (holder.tvGateBranch != null) {
            String branch = gate.branchName != null && !gate.branchName.isEmpty()
                    ? gate.branchName
                    : (isArabic ? "غير محدد" : "Not specified");
            holder.tvGateBranch.setText("🏢 " + branch);
        }

        // نوع الجهاز
        if (holder.tvGateDeviceType != null) {
            String deviceTypeName;
            switch (gate.deviceType != null ? gate.deviceType : "chainway_c72") {
                case "vanch_fixed":
                    deviceTypeName = "📡 VANCH Fixed";
                    if (gate.ipAddress != null && !gate.ipAddress.isEmpty()) {
                        deviceTypeName += " • " + gate.ipAddress;
                    }
                    break;
                case "generic_bluetooth":
                    deviceTypeName = "📶 Bluetooth";
                    break;
                case "simulator":
                    deviceTypeName = "🧪 Simulator";
                    break;
                default:
                    deviceTypeName = "📱 Chainway C72";
            }
            holder.tvGateDeviceType.setText(deviceTypeName);
        }

        boolean isOnline = "online".equals(gate.status)
                || "armed".equals(gate.status);
        if (holder.tvGateStatus != null) {
            if (isOnline) {
                holder.tvGateStatus.setText(isArabic ? "🟢 متصل" : "🟢 Online");
                holder.tvGateStatus.setTextColor(0xFF2E7D32);
            } else {
                holder.tvGateStatus.setText(isArabic ? "⚪ غير متصل" : "⚪ Offline");
                holder.tvGateStatus.setTextColor(0xFF9E9E9E);
            }
        }

        if (holder.tvGateStats != null) {
            holder.tvGateStats.setText("✅ " + gate.passCount
                    + " | ❌ " + gate.denyCount);
        }

        if (holder.tvGateLastEvent != null) {
            String lastEventText = gate.lastEvent;
            if (lastEventText == null || lastEventText.isEmpty()
                    || "null".equalsIgnoreCase(lastEventText)) {
                lastEventText = isArabic ? "لا أحداث" : "No events";
            }
            holder.tvGateLastEvent.setText(lastEventText);
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

        // ✅ [v3.0] زر «📌 ربط» متعدد
if (holder.btnLinkDevice != null) {
    boolean isLinked = settings != null
            && gate.gateId != null
            && settings.isGateLinked(gate.gateId);

    if (isLinked) {
        holder.btnLinkDevice.setText(isArabic ? "✅ مربوط" : "✅ Linked");
        holder.btnLinkDevice.setTextColor(0xFF16A34A);
    } else {
        holder.btnLinkDevice.setText(isArabic ? "📌 ربط" : "📌 Link");
        holder.btnLinkDevice.setTextColor(0xFF1976D2);
    }

    holder.btnLinkDevice.setOnClickListener(v -> {
        int pos = holder.getAdapterPosition();
        if (pos != RecyclerView.NO_POSITION && pos < gates.size()) {
            confirmLinkGate(gates.get(pos));
        }
    });
}

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && editListener != null) {
                editListener.onEdit(pos);
            }
        });
    } catch (Exception e) {                    // ← ✅ CATCH بعد إغلاق try
        Log.e(TAG, "onBindViewHolder error: " + e.getMessage(), e);
    }
}                                              // ← ✅ إغلاق onBindViewHolder
                

        @Override
        public int getItemCount() {
            return gates != null ? gates.size() : 0;
        }

        class GateViewHolder extends RecyclerView.ViewHolder {
    TextView tvGateName, tvGateLocation, tvGateStatus, tvGateStats,
             tvGateLastEvent, tvGateBranch, tvGateDeviceType;
    View btnRemove;
    MaterialButton btnToggleGate;
    MaterialButton btnLinkDevice;   // ✅ [v2.3]

    GateViewHolder(@NonNull View itemView) {
        super(itemView);
        tvGateName = itemView.findViewById(R.id.tvGateName);
        tvGateLocation = itemView.findViewById(R.id.tvGateLocation);
        tvGateStatus = itemView.findViewById(R.id.tvGateStatus);
        tvGateStats = itemView.findViewById(R.id.tvGateStats);
        tvGateLastEvent = itemView.findViewById(R.id.tvGateLastEvent);
        tvGateBranch = itemView.findViewById(R.id.tvGateBranch);
        tvGateDeviceType = itemView.findViewById(R.id.tvGateDeviceType);
        btnRemove = itemView.findViewById(R.id.btnRemoveGate);
        btnToggleGate = itemView.findViewById(R.id.btnToggleGate);
        btnLinkDevice = itemView.findViewById(R.id.btnLinkDevice);  // ✅
    }
}

    // ═══════════════════════════════════════════════════════
    // Interfaces
    // ═══════════════════════════════════════════════════════

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
}
