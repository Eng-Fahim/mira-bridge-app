package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.service.MiraGateMonitorService;
import com.example.uhf.tools.SoundGenerator;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RequiresApi(api = Build.VERSION_CODES.O)
public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGate";
    private static final long SCAN_COOL_DOWN_MS = 2500;
    private static final int API_TIMEOUT_SECONDS = 5;
    private static final int MONITOR_INTERVAL_SECONDS = 5;
    private static final int PING_INTERVAL_SECONDS = 10;

    private UHFMainActivity mContext;
    private MiraGatesSettings miraGatesSettings;
    private PermissionManager permissionManager;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService gateExecutor;
    private ScheduledExecutorService monitorScheduler;
    private ScheduledExecutorService pingScheduler;
    
    // UI
    private RecyclerView rvGatesMonitor;
    private GateMonitorAdapter monitorAdapter;
    private final List<GateMonitorItem> monitorList = new ArrayList<>();
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private TextView tvDebugConsole;
    private ScrollView scrollDebugLog;
    private FrameLayout overlayAlarm;
    private TextView tvOverlayTitle, tvOverlayDetails;
    private TextView tvPingLatency, tvSystemStatus, tvLiveTickerText;
    private ProgressBar pbRiskMeter;
    private TextView tvRiskPercentage;
    private Button btnStopAlarm, btnToggleScan;
    private Button btnSimulateAllowed, btnSimulateDenied;
    private Button btnScanBarcode, btnEmergencyLockdown, btnClearConsole;
    private Button btnFilterAll, btnFilterAlerts, btnFilterPass;
    
    // متغيرات المحاكاة
    private String soldEpc = "", soldTitle = "";
    private String unsoldEpc = "", unsoldTitle = "";
    
    // حالة
    private volatile boolean isDestroyed = false;
    private volatile boolean isScanning = false;
    private volatile boolean isLockdown = false;
    private String logFilter = "ALL";
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(
        new ScanContract(),
        result -> {
            if (result.getContents() != null && !isDestroyed) {
                processInputEpc(result.getContents().trim(), "CAMERA");
            }
        }
    );

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_secure_gate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initializeComponents();
        initViews(view);
        setupListeners();
        
        startMonitoring();
        startPingMonitor();
        loadTestEpcs();
        autoStartScanning();
        startBackgroundService();
        
        updateSystemStatus(true);
        logConsole("🚀 MIRA SECURITY COMMAND CENTER ACTIVATED");
    }

    private void initializeComponents() {
        Context context = getContext();
        if (context == null) return;
        
        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
        }
        
        miraGatesSettings = MiraGatesSettings.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        gateExecutor = Executors.newFixedThreadPool(4);
        isDestroyed = false;
    }

    private void initViews(View view) {
        rvGatesMonitor = view.findViewById(R.id.rvGatesMonitor);
        tvPassCount = view.findViewById(R.id.tvPassCount);
        tvDenyCount = view.findViewById(R.id.tvDenyCount);
        tvTotalCount = view.findViewById(R.id.tvTotalCount);
        tvDebugConsole = view.findViewById(R.id.tvDebugConsole);
        scrollDebugLog = view.findViewById(R.id.scrollDebugLog);
        overlayAlarm = view.findViewById(R.id.overlayAlarm);
        tvOverlayTitle = view.findViewById(R.id.tvOverlayTitle);
        tvOverlayDetails = view.findViewById(R.id.tvOverlayDetails);
        tvPingLatency = view.findViewById(R.id.tvPingLatency);
        tvSystemStatus = view.findViewById(R.id.tvSystemStatus);
        tvLiveTickerText = view.findViewById(R.id.tvLiveTickerText);
        pbRiskMeter = view.findViewById(R.id.pbRiskMeter);
        tvRiskPercentage = view.findViewById(R.id.tvRiskPercentage);
        btnStopAlarm = view.findViewById(R.id.btnStopAlarm);
        btnToggleScan = view.findViewById(R.id.btnToggleScan);
        btnSimulateAllowed = view.findViewById(R.id.btnSimulateAllowed);
        btnSimulateDenied = view.findViewById(R.id.btnSimulateDenied);
        btnScanBarcode = view.findViewById(R.id.btnScanBarcode);
        btnEmergencyLockdown = view.findViewById(R.id.btnEmergencyLockdown);
        btnClearConsole = view.findViewById(R.id.btnClearConsole);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterAlerts = view.findViewById(R.id.btnFilterAlerts);
        btnFilterPass = view.findViewById(R.id.btnFilterPass);
        
        if (rvGatesMonitor != null) {
            rvGatesMonitor.setLayoutManager(new LinearLayoutManager(getContext()));
            monitorAdapter = new GateMonitorAdapter(monitorList);
            rvGatesMonitor.setAdapter(monitorAdapter);
        }
    }

    private void setupListeners() {
        if (btnStopAlarm != null) btnStopAlarm.setOnClickListener(v -> stopAlarm());
        if (btnToggleScan != null) btnToggleScan.setOnClickListener(v -> toggleScanning());
        
        if (btnSimulateAllowed != null) {
            btnSimulateAllowed.setOnClickListener(v -> {
                if (!soldEpc.isEmpty()) queryMiraGateServer(soldEpc);
                else Toast.makeText(getContext(), "⚠️ لا توجد قطعة مباعة", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnSimulateDenied != null) {
            btnSimulateDenied.setOnClickListener(v -> {
                if (!unsoldEpc.isEmpty()) queryMiraGateServer(unsoldEpc);
                else Toast.makeText(getContext(), "⚠️ لا توجد قطعة غير مباعة", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnScanBarcode != null) {
            btnScanBarcode.setOnClickListener(v -> {
                ScanOptions options = new ScanOptions();
                options.setPrompt("📷 وجه الكاميرا نحو باركود القطعة");
                options.setBeepEnabled(false);
                options.setOrientationLocked(true);
                barcodeLauncher.launch(options);
            });
        }
        if (btnEmergencyLockdown != null) btnEmergencyLockdown.setOnClickListener(v -> toggleLockdown());
        
        if (btnClearConsole != null) {
            btnClearConsole.setOnClickListener(v -> {
                if (tvDebugConsole != null) tvDebugConsole.setText("");
            });
        }
        
        // ✅ فلترة السجل
        if (btnFilterAll != null) btnFilterAll.setOnClickListener(v -> { logFilter = "ALL"; logConsole("🔍 فلتر: الكل"); });
        if (btnFilterAlerts != null) btnFilterAlerts.setOnClickListener(v -> { logFilter = "ALERTS"; logConsole("🔍 فلتر: الإنذارات"); });
        if (btnFilterPass != null) btnFilterPass.setOnClickListener(v -> { logFilter = "PASS"; logConsole("🔍 فلتر: المقبول"); });
    }

    // ============================================
    // ✅ خدمة الخلفية
    // ============================================
    private void startBackgroundService() {
        try {
            Context context = getContext();
            if (context != null) {
                Intent serviceIntent = new Intent(context, MiraGateMonitorService.class);
                context.startForegroundService(serviceIntent);
                logConsole("📱 خدمة المراقبة الخلفية مفعلة");
            }
        } catch (Exception e) {
            Log.e(TAG, "Start service: " + e.getMessage());
        }
    }

    // ============================================
    // 🔴 Lockdown
    // ============================================
    private void toggleLockdown() {
        isLockdown = !isLockdown;
        if (isLockdown) {
            lockAllGates();
            updateSystemStatus(false);
            logConsole("🔴 LOCKDOWN ACTIVATED");
            if (btnEmergencyLockdown != null) {
                btnEmergencyLockdown.setText("🔓 فتح البوابات");
                btnEmergencyLockdown.setBackgroundColor(Color.parseColor("#16A34A"));
            }
        } else {
            unlockAllGates();
            updateSystemStatus(true);
            logConsole("🟢 LOCKDOWN DEACTIVATED");
            if (btnEmergencyLockdown != null) {
                btnEmergencyLockdown.setText("🔒 إغلاق طارئ");
                btnEmergencyLockdown.setBackgroundColor(Color.parseColor("#DC2626"));
            }
        }
    }

    private void lockAllGates() {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                JSONObject payload = new JSONObject();
                payload.put("command", "LOCK_ALL");
                payload.put("branch_id", getCurrentBranchId());
                client.postWithAuth("/gate-command", payload);
            } catch (Exception e) {}
        });
    }

    private void unlockAllGates() {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                JSONObject payload = new JSONObject();
                payload.put("command", "UNLOCK_ALL");
                payload.put("branch_id", getCurrentBranchId());
                client.postWithAuth("/gate-command", payload);
            } catch (Exception e) {}
        });
    }

    // ============================================
    // 📡 Ping
    // ============================================
    private void startPingMonitor() {
        pingScheduler = Executors.newSingleThreadScheduledExecutor();
        pingScheduler.scheduleAtFixedRate(() -> {
            if (!isDestroyed && isAdded()) checkPing();
        }, 0, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void checkPing() {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                long start = System.currentTimeMillis();
                boolean ok = client.pingApi();
                long latency = System.currentTimeMillis() - start;
                mainHandler.post(() -> {
                    if (tvPingLatency != null) {
                        tvPingLatency.setText(ok ? "📡 Ping: " + latency + " ms | ✅ متصل" : "📡 Ping: -- | ⚠️ غير متصل");
                    }
                });
            } catch (Exception e) {}
        });
    }

    private void updateSystemStatus(boolean armed) {
        if (tvSystemStatus != null) {
            if (isLockdown) {
                tvSystemStatus.setText("🔴 LOCKDOWN");
                tvSystemStatus.setTextColor(Color.parseColor("#EF4444"));
            } else if (armed) {
                tvSystemStatus.setText("🟢 SYSTEM ARMED");
                tvSystemStatus.setTextColor(Color.parseColor("#4ADE80"));
            } else {
                tvSystemStatus.setText("🟡 STANDBY");
                tvSystemStatus.setTextColor(Color.parseColor("#FBBF24"));
            }
        }
    }

    private void updateLiveTicker(String message, boolean isAlert) {
        if (tvLiveTickerText != null) {
            tvLiveTickerText.setText(message);
            tvLiveTickerText.setTextColor(isAlert ? Color.parseColor("#F87171") : Color.parseColor("#4ADE80"));
            if (isAlert) {
                Animation anim = new AlphaAnimation(0.3f, 1.0f);
                anim.setDuration(300);
                anim.setRepeatMode(Animation.REVERSE);
                anim.setRepeatCount(5);
                tvLiveTickerText.startAnimation(anim);
            }
        }
    }

    // ============================================
    // ✅ المسح
    // ============================================
    private void autoStartScanning() {
        mainHandler.postDelayed(() -> {
            if (!isDestroyed && isAdded() && !isLockdown) startScanning();
        }, 1000);
    }

    private void toggleScanning() {
        if (isScanning) stopScanning(); else startScanning();
    }

    private void startScanning() {
        if (isDestroyed || mContext == null || mContext.mReader == null) {
            logConsole("⚠️ قارئ RFID غير متوفر - استخدم الكاميرا");
            return;
        }
        try {
            mContext.mReader.setPower(miraGatesSettings.getRfidPower());
            mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                @Override
                public void callback(UHFTAGInfo info) {
                    if (!isDestroyed && info != null && info.getEPC() != null) {
                        processInputEpc(info.getEPC(), "RFID");
                    }
                }
            });
            mContext.mReader.startInventoryTag();
            isScanning = true;
            if (btnToggleScan != null) btnToggleScan.setText("🔴 إيقاف المسح");
            logConsole("🛡️ قارئ RFID يعمل");
        } catch (Exception e) {
            logConsole("⚠️ خطأ: " + e.getMessage());
        }
    }

    private void stopScanning() {
        if (mContext != null && mContext.mReader != null) {
            try { mContext.mReader.stopInventory(); mContext.mReader.setInventoryCallback(null); } catch (Exception e) {}
        }
        isScanning = false;
        if (btnToggleScan != null) btnToggleScan.setText("🛡️ تفعيل المسح");
    }

    // ============================================
    // 📡 المراقبة
    // ============================================
    private void startMonitoring() {
        monitorScheduler = Executors.newSingleThreadScheduledExecutor();
        monitorScheduler.scheduleAtFixedRate(() -> {
            if (!isDestroyed && isAdded()) loadAllGatesStatus();
        }, 0, MONITOR_INTERVAL_SECONDS, TimeUnit.SECONDS);
        loadAllGatesStatus();
    }

    private void loadAllGatesStatus() {
        if (gateExecutor == null || getContext() == null) return;
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = client.getGatesByBranch(getCurrentBranchId());
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = extractGatesArray(response.data);
                    if (arr != null) {
                        List<GateMonitorItem> items = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject g = arr.optJSONObject(i);
                            GateMonitorItem item = new GateMonitorItem();
                            item.gateId = g.optString("gate_id", "");
                            item.gateName = g.optString("gate_name", "بوابة");
                            item.location = g.optString("gate_location", "");
                            item.status = g.optString("status", "offline");
                            item.passCount = g.optInt("pass_count", 0);
                            item.denyCount = g.optInt("deny_count", 0);
                            item.lastEvent = g.optString("last_event", "");
                            items.add(item);
                        }
                        mainHandler.post(() -> updateMonitorList(items));
                    }
                }
            } catch (Exception e) {}
        });
    }

    private JSONArray extractGatesArray(JSONObject data) {
        JSONArray arr = data.optJSONArray("gates");
        if (arr != null) return arr;
        JSONObject d = data.optJSONObject("data");
        return d != null ? d.optJSONArray("gates") : null;
    }

    private void updateMonitorList(List<GateMonitorItem> items) {
        monitorList.clear();
        monitorList.addAll(items);
        if (monitorAdapter != null) monitorAdapter.notifyDataSetChanged();
        
        int pass = 0, deny = 0;
        for (GateMonitorItem item : items) { pass += item.passCount; deny += item.denyCount; }
        if (tvPassCount != null) tvPassCount.setText(String.valueOf(pass));
        if (tvDenyCount != null) tvDenyCount.setText(String.valueOf(deny));
        if (tvTotalCount != null) tvTotalCount.setText(String.valueOf(items.size()));
        
        // ✅ مؤشر المخاطر
        updateRiskMeter(pass, deny);
    }

    private void updateRiskMeter(int pass, int deny) {
        int total = pass + deny;
        if (total > 0) {
            int risk = (deny * 100) / total;
            if (pbRiskMeter != null) pbRiskMeter.setProgress(risk);
            if (tvRiskPercentage != null) tvRiskPercentage.setText("نسبة الإنذارات: " + risk + "%");
        }
    }

    private int getCurrentBranchId() {
        try {
            com.example.uhf.manager.MiraBranchesManager bm = 
                com.example.uhf.manager.MiraBranchesManager.getInstance(mContext);
            return bm != null ? bm.getActiveBranchId() : 0;
        } catch (Exception e) { return 0; }
    }

    // ============================================
    // ✅ EPCs
    // ============================================
    private void loadTestEpcs() {
        if (gateExecutor == null || getContext() == null) return;
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = client.getTestEpcs();
                if (response != null && response.isSuccess && response.data != null) {
                    soldEpc = response.data.optString("sold_epc", "");
                    unsoldEpc = response.data.optString("unsold_epc", "");
                    mainHandler.post(() -> logConsole("✅ EPCs للمحاكاة جاهزة"));
                }
            } catch (Exception e) {}
        });
    }

    // ============================================
    // 📡 فحص القطع
    // ============================================
    private void processInputEpc(String epc, String sourceType) {
        if (isDestroyed || !isAdded()) return;
        if (epc == null || epc.trim().isEmpty()) return;
        final String cleanedEpc = epc.trim().toUpperCase();
        
        long now = System.currentTimeMillis();
        Long last = scanDebounceMap.putIfAbsent(cleanedEpc, now);
        if (last != null && now - last < SCAN_COOL_DOWN_MS) return;
        scanDebounceMap.put(cleanedEpc, now);
        
        logConsole("📡 [" + sourceType + "] فحص: " + cleanedEpc);
        if (localAllowedEpcCache.contains(cleanedEpc)) {
            mainHandler.post(() -> handleGateDecision(true, "قطعة مصرح بها", cleanedEpc));
        } else {
            queryMiraGateServer(cleanedEpc);
        }
    }

    private void queryMiraGateServer(String epc) {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                JSONObject params = new JSONObject();
                params.put("context", "gate");
                params.put("branch_id", getCurrentBranchId());
                
                Future<MiraApiClient.ApiResponse> future = gateExecutor.submit(() -> client.authorize(epc, "gate", params));
                MiraApiClient.ApiResponse response = future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String itemTitle = item != null ? item.optString("title", "قطعة") : "قطعة";
                    String serial = item != null ? item.optString("serial", epc) : epc;
                    String message = decision != null ? decision.optString("message", "") : "";
                    
                    if (allowed) localAllowedEpcCache.add(epc);
                    
                    mainHandler.post(() -> {
                        handleGateDecision(allowed, itemTitle, serial);
                        if (!message.isEmpty()) logConsole((allowed ? "✅ " : "🚨 ") + message);
                    });
                }
            } catch (TimeoutException e) {
                mainHandler.post(() -> handleGateDecision(false, "انقطاع اتصال", epc));
            } catch (Exception e) {
                mainHandler.post(() -> handleGateDecision(false, "غير مصرح", epc));
            }
        });
    }

    private void handleGateDecision(boolean allowed, String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        // ✅ الفلترة
        if (allowed && "ALERTS".equals(logFilter)) return;
        if (!allowed && "PASS".equals(logFilter)) return;
        
        if (allowed) {
            logConsole("✅ خروج شرعي: " + itemTitle);
            updateLiveTicker("✅ " + itemTitle, false);
            SoundGenerator.playSound(miraGatesSettings.getSoundPass());
        } else {
            logConsole("🚨 خروج غير شرعي: " + itemTitle);
            updateLiveTicker("🚨 " + itemTitle, true);
            triggerAlarm(itemTitle, serial);
        }
    }

    // ============================================
    // 🚨 الإنذار
    // ============================================
    private void triggerAlarm(String itemTitle, String serial) {
        mainHandler.post(() -> {
            if (overlayAlarm != null) {
                tvOverlayTitle.setText("🚨 ALERT: UNAUTHORIZED BREACH");
                tvOverlayDetails.setText(itemTitle + "\n[" + serial + "]");
                overlayAlarm.setVisibility(View.VISIBLE);
                Animation anim = new AlphaAnimation(0.4f, 1.0f);
                anim.setDuration(300);
                anim.setRepeatMode(Animation.REVERSE);
                anim.setRepeatCount(Animation.INFINITE);
                overlayAlarm.startAnimation(anim);
            }
            if (vibrator != null) vibrator.vibrate(new long[]{0, 500, 200, 500}, -1);
            SoundGenerator.playSound(miraGatesSettings.getSoundAlarm());
        });
    }

    private void stopAlarm() {
        if (overlayAlarm != null) { overlayAlarm.clearAnimation(); overlayAlarm.setVisibility(View.GONE); }
        if (vibrator != null) vibrator.cancel();
    }

    // ============================================
    // 📝 السجل
    // ============================================
    private void logConsole(String message) {
        mainHandler.post(() -> {
            if (isDestroyed || !isAdded()) return;
            String line = "[" + timeFormat.format(new Date()) + "] " + message + "\n";
            if (tvDebugConsole != null) {
                tvDebugConsole.append(line);
                if (scrollDebugLog != null) scrollDebugLog.fullScroll(View.FOCUS_DOWN);
            }
            Log.d(TAG, message);
        });
    }

    // ============================================
    // 📦 Adapter
    // ============================================
    private static class GateMonitorItem {
        String gateId, gateName, location, status, lastEvent;
        int passCount, denyCount;
    }

    private class GateMonitorAdapter extends RecyclerView.Adapter<GateMonitorAdapter.VH> {
        private final List<GateMonitorItem> items;
        GateMonitorAdapter(List<GateMonitorItem> items) { this.items = items; }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gate_monitor, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            GateMonitorItem item = items.get(position);
            h.tvGateName.setText(item.gateName);
            h.tvGateStats.setText("✅ " + item.passCount + " | ❌ " + item.denyCount);
            h.tvGateLastEvent.setText(item.lastEvent.isEmpty() ? "لا أحداث" : item.lastEvent);
            
            if ("alert".equals(item.status)) {
                h.tvGateStatus.setText("🔴 إنذار");
                h.tvGateStatus.setTextColor(Color.parseColor("#EF4444"));
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_red);
            } else if ("online".equals(item.status) || "armed".equals(item.status)) {
                h.tvGateStatus.setText("🟢 نشطة");
                h.tvGateStatus.setTextColor(Color.parseColor("#4ADE80"));
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_green);
            } else {
                h.tvGateStatus.setText("⚪ غير نشطة");
                h.tvGateStatus.setTextColor(Color.parseColor("#94A3B8"));
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_red);
            }
        }
        
        @Override public int getItemCount() { return items.size(); }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvGateName, tvGateStatus, tvGateStats, tvGateLastEvent;
            View vStatusDot;
            VH(@NonNull View itemView) {
                super(itemView);
                tvGateName = itemView.findViewById(R.id.tvGateName);
                tvGateStatus = itemView.findViewById(R.id.tvGateStatus);
                tvGateStats = itemView.findViewById(R.id.tvGateStats);
                tvGateLastEvent = itemView.findViewById(R.id.tvGateLastEvent);
                vStatusDot = itemView.findViewById(R.id.vGateStatusDot);
            }
        }
    }

    // ============================================
    // 🔄 دورة الحياة
    // ============================================
    @Override
    public void onPause() {
        super.onPause();
        if (isScanning) stopScanning();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isScanning && isAdded() && !isLockdown) autoStartScanning();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed = true;
        stopScanning();
        stopAlarm();
        if (monitorScheduler != null) monitorScheduler.shutdownNow();
        if (pingScheduler != null) pingScheduler.shutdownNow();
        if (gateExecutor != null) gateExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
