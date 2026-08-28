package com.example.uhf.fragment;

import android.content.Context;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import com.example.uhf.tools.SoundGenerator;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private UHFMainActivity mContext;
    private MiraGatesSettings miraGatesSettings;
    private PermissionManager permissionManager;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService gateExecutor;
    private ScheduledExecutorService monitorScheduler;
    
    // UI
    private RecyclerView rvGatesMonitor;
    private GateMonitorAdapter monitorAdapter;
    private final List<GateMonitorItem> monitorList = new ArrayList<>();
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private TextView tvDebugConsole;
    private ScrollView scrollDebugLog;
    private FrameLayout overlayAlarm;
    private TextView tvOverlayTitle, tvOverlayDetails;
    private Button btnStopAlarm, btnToggleScan;
private Button btnSimulateAllowed, btnSimulateDenied;  // ✅ أضف هذا
    
    // حالة
    private volatile boolean isDestroyed = false;
    private volatile boolean isScanning = false;
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

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
        autoStartScanning(); // ✅ تلقائياً عند الفتح
        
        logConsole("🚀 تم تشغيل البوابة الأمنية MIRA");
        logConsole("📡 جاري تشغيل قارئ RFID تلقائياً...");
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
        btnStopAlarm = view.findViewById(R.id.btnStopAlarm);
       btnToggleScan = view.findViewById(R.id.btnToggleScan);
btnSimulateAllowed = view.findViewById(R.id.btnSimulateAllowed);  // ✅ بدون Button
btnSimulateDenied = view.findViewById(R.id.btnSimulateDenied);    // ✅ بدون Button
        
        if (rvGatesMonitor != null) {
            rvGatesMonitor.setLayoutManager(new LinearLayoutManager(getContext()));
            monitorAdapter = new GateMonitorAdapter(monitorList);
            rvGatesMonitor.setAdapter(monitorAdapter);
        }
    }

    private void setupListeners() {
        if (btnStopAlarm != null) {
            btnStopAlarm.setOnClickListener(v -> stopAlarm());
        }
        
        if (btnToggleScan != null) {
            btnToggleScan.setOnClickListener(v -> toggleScanning());
        }
        
        if (btnSimulateAllowed != null) {
        btnSimulateAllowed.setOnClickListener(v -> {
        String mockEpc = "E20041000000000000009999";
        processInputEpc(mockEpc, "SIMULATION");
    });
}

if (btnSimulateDenied != null) {
    btnSimulateDenied.setOnClickListener(v -> {
        String mockEpc = "E20041000000000000001111";
        processInputEpc(mockEpc, "SIMULATION");
    });
}
    }

    // ============================================
    // ✅ التشغيل التلقائي لقارئ RFID
    // ============================================

    private void autoStartScanning() {
        mainHandler.postDelayed(() -> {
            if (!isDestroyed && isAdded()) {
                startScanning();
            }
        }, 1000); // ✅ بعد ثانية واحدة
    }

    private void toggleScanning() {
        if (isScanning) {
            stopScanning();
        } else {
            startScanning();
        }
    }

    private void startScanning() {
        if (isDestroyed || mContext == null || mContext.mReader == null) {
            logConsole("⚠️ قارئ RFID غير متوفر");
            Toast.makeText(getContext(), "⚠️ قارئ RFID غير متوفر", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            int rfidPower = miraGatesSettings.getRfidPower();
            mContext.mReader.setPower(rfidPower);
            
            mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                @Override
                public void callback(UHFTAGInfo uhftagInfo) {
                    if (!isDestroyed && uhftagInfo != null && uhftagInfo.getEPC() != null) {
                        processInputEpc(uhftagInfo.getEPC(), "RFID");
                    }
                }
            });
            
            mContext.mReader.startInventoryTag();
            isScanning = true;
            
            if (btnToggleScan != null) {
                btnToggleScan.setText("🔴 إيقاف المسح");
            }
            
            logConsole("🛡️ قارئ RFID يعمل - المسح نشط");
        } catch (Exception e) {
            logConsole("⚠️ خطأ في تشغيل القارئ: " + e.getMessage());
        }
    }

    private void stopScanning() {
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.stopInventory();
                mContext.mReader.setInventoryCallback(null);
            } catch (Exception e) {
                Log.e(TAG, "Stop reader error: " + e.getMessage());
            }
        }
        
        isScanning = false;
        
        if (btnToggleScan != null) {
            btnToggleScan.setText("🛡️ تفعيل المسح");
        }
        
        logConsole("🔒 تم إيقاف المسح");
    }

    // ============================================
    // 📡 المراقبة الحية
    // ============================================

    private void startMonitoring() {
        monitorScheduler = Executors.newSingleThreadScheduledExecutor();
        monitorScheduler.scheduleAtFixedRate(() -> {
            if (!isDestroyed && isAdded()) {
                loadAllGatesStatus();
            }
        }, 0, MONITOR_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        loadAllGatesStatus();
    }

    private void loadAllGatesStatus() {
        if (gateExecutor == null || getContext() == null) return;
        
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                int branchId = getCurrentBranchId();
                MiraApiClient.ApiResponse response = client.getGatesByBranch(branchId);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray gatesArray = extractGatesArray(response.data);
                    
                    if (gatesArray != null) {
                        List<GateMonitorItem> items = new ArrayList<>();
                        for (int i = 0; i < gatesArray.length(); i++) {
                            JSONObject g = gatesArray.optJSONObject(i);
                            GateMonitorItem item = new GateMonitorItem();
                            item.gateId = g.optString("gate_id", "");
                            item.gateName = g.optString("gate_name", "بوابة");
                            item.status = g.optString("status", "offline");
                            item.passCount = g.optInt("pass_count", 0);
                            item.denyCount = g.optInt("deny_count", 0);
                            item.lastEvent = g.optString("last_event", "");
                            items.add(item);
                        }
                        
                        mainHandler.post(() -> updateMonitorList(items));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Monitor error: " + e.getMessage());
            }
        });
    }

    private JSONArray extractGatesArray(JSONObject data) {
        JSONArray arr = data.optJSONArray("gates");
        if (arr != null) return arr;
        JSONObject dataObj = data.optJSONObject("data");
        if (dataObj != null) return dataObj.optJSONArray("gates");
        return null;
    }

    private void updateMonitorList(List<GateMonitorItem> items) {
        monitorList.clear();
        monitorList.addAll(items);
        if (monitorAdapter != null) monitorAdapter.notifyDataSetChanged();
        
        int pass = 0, deny = 0;
        for (GateMonitorItem item : items) {
            pass += item.passCount;
            deny += item.denyCount;
        }
        if (tvPassCount != null) tvPassCount.setText("✅ مسموح: " + pass);
        if (tvDenyCount != null) tvDenyCount.setText("🚨 إنذار: " + deny);
        if (tvTotalCount != null) tvTotalCount.setText("🚪 البوابات: " + items.size());
    }

    private int getCurrentBranchId() {
        try {
            com.example.uhf.manager.MiraBranchesManager bm = 
                com.example.uhf.manager.MiraBranchesManager.getInstance(mContext);
            return bm != null ? bm.getActiveBranchId() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ============================================
    // 📡 فحص القطع
    // ============================================

    private void processInputEpc(String epc, String sourceType) {
        if (isDestroyed || !isAdded()) return;
        if (epc == null || epc.trim().isEmpty()) return;

        final String cleanedEpc = epc.trim().toUpperCase();
        long currentTime = System.currentTimeMillis();
        
        Long lastTime = scanDebounceMap.putIfAbsent(cleanedEpc, currentTime);
        if (lastTime != null && currentTime - lastTime < SCAN_COOL_DOWN_MS) return;
        scanDebounceMap.put(cleanedEpc, currentTime);
        
        logConsole("📡 فحص: " + cleanedEpc);
        
        if (localAllowedEpcCache.contains(cleanedEpc)) {
            mainHandler.post(() -> handleGateDecision(true, "قطعة مصرح بها", cleanedEpc));
        } else {
            queryMiraGateServer(cleanedEpc);
        }
    }

    private void queryMiraGateServer(String epc) {
        if (gateExecutor == null) return;
        
        gateExecutor.execute(() -> {
            Future<MiraApiClient.ApiResponse> future = null;
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                JSONObject extraParams = new JSONObject();
                extraParams.put("context", "gate");
                extraParams.put("branch_id", getCurrentBranchId());
                
                future = gateExecutor.submit(() -> client.authorize(epc, "gate", extraParams));
                MiraApiClient.ApiResponse response = future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    
                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String message = decision != null ? decision.optString("message", "") : "";
                    String itemTitle = item != null ? item.optString("title", "قطعة") : "قطعة";
                    String serial = item != null ? item.optString("serial", epc) : epc;
                    
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
        
        if (allowed) {
            logConsole("✅ خروج شرعي: " + itemTitle);
            SoundGenerator.playSound(miraGatesSettings.getSoundPass());
        } else {
            logConsole("🚨 خروج غير شرعي: " + itemTitle);
            triggerAlarm(itemTitle, serial);
        }
    }

    // ============================================
    // 🚨 الإنذار
    // ============================================

    private void triggerAlarm(String itemTitle, String serial) {
        mainHandler.post(() -> {
            if (overlayAlarm != null) {
                tvOverlayTitle.setText("🚨 إنذار أمني");
                tvOverlayDetails.setText(itemTitle + " [" + serial + "]");
                overlayAlarm.setVisibility(View.VISIBLE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(new long[]{0, 500, 200, 500}, -1);
            }
            SoundGenerator.playSound(miraGatesSettings.getSoundAlarm());
        });
    }

    private void stopAlarm() {
        if (overlayAlarm != null) overlayAlarm.setVisibility(View.GONE);
        if (vibrator != null) vibrator.cancel();
        logConsole("🔕 تم إيقاف الإنذار");
    }

    // ============================================
    // 📝 السجل
    // ============================================

    private void logConsole(String message) {
        mainHandler.post(() -> {
            if (isDestroyed || !isAdded()) return;
            String logLine = "[" + timeFormat.format(new Date()) + "] " + message + "\n";
            if (tvDebugConsole != null) {
                tvDebugConsole.append(logLine);
                if (scrollDebugLog != null) scrollDebugLog.fullScroll(View.FOCUS_DOWN);
            }
            Log.d(TAG, message);
        });
    }

    // ============================================
    // 📦 Adapter
    // ============================================

    private static class GateMonitorItem {
        String gateId, gateName, status, lastEvent;
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
            
            if ("online".equals(item.status) || "armed".equals(item.status)) {
                h.tvGateStatus.setText("🟢 نشطة");
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_green);
            } else {
                h.tvGateStatus.setText("⚪ غير نشطة");
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
        if (!isScanning && isAdded()) autoStartScanning();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed = true;
        stopScanning();
        stopAlarm();
        
        if (monitorScheduler != null) monitorScheduler.shutdownNow();
        if (gateExecutor != null) gateExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
                              }
