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
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SoundGenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.O)
public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGateMonitor";
    private static final int MONITOR_INTERVAL_SECONDS = 5; // ✅ تحديث كل 5 ثواني

    // ✅ المكونات
    private UHFMainActivity mContext;
    private MiraGatesSettings miraGatesSettings;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;
    
    // ✅ المعالجات
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService gateExecutor;
    private ScheduledExecutorService monitorScheduler;
    
    // ✅ عناصر UI
    private RecyclerView rvGatesMonitor;
    private GateMonitorAdapter monitorAdapter;
    private final List<GateMonitorItem> monitorList = new ArrayList<>();
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private TextView tvDebugConsole;
    private ScrollView scrollDebugLog;
    private FrameLayout overlayAlarm;
    private TextView tvOverlayTitle, tvOverlayDetails;
    private Button btnStopAlarm;
    
    // ✅ الحالة
    private volatile boolean isDestroyed = false;
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
        
        // ✅ بدء المراقبة الحية فوراً
        startMonitoring();
        
        logConsole("🚀 تم تشغيل لوحة مراقبة البوابات الأمنية");
        logConsole("📡 المراقبة الحية تعمل - تحديث كل " + MONITOR_INTERVAL_SECONDS + " ثواني");
    }

    private void initializeComponents() {
        Context context = getContext();
        if (context == null) return;
        
        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
        }
        
        miraGatesSettings = MiraGatesSettings.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        branchesManager = MiraBranchesManager.getInstance(context);
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
    }

    // ============================================
    // 📡 المراقبة الحية
    // ============================================

    private void startMonitoring() {
        if (monitorScheduler != null && !monitorScheduler.isShutdown()) {
            monitorScheduler.shutdownNow();
        }
        
        monitorScheduler = Executors.newSingleThreadScheduledExecutor();
        monitorScheduler.scheduleAtFixedRate(() -> {
            if (!isDestroyed && isAdded()) {
                loadAllGatesStatus();
            }
        }, 0, MONITOR_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        // ✅ تحميل فوري
        loadAllGatesStatus();
    }

    private void loadAllGatesStatus() {
        if (gateExecutor == null || gateExecutor.isShutdown()) return;
        if (getContext() == null) return;
        
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                if (client == null) return;
                
                int branchId = getCurrentBranchId();
                MiraApiClient.ApiResponse response = client.getGatesByBranch(branchId);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray gatesArray = extractGatesArray(response.data);
                    
                    if (gatesArray != null) {
                        List<GateMonitorItem> items = new ArrayList<>();
                        
                        for (int i = 0; i < gatesArray.length(); i++) {
                            JSONObject g = gatesArray.optJSONObject(i);
                            if (g == null) continue;
                            
                            GateMonitorItem item = new GateMonitorItem();
                            item.gateId = g.optString("gate_id", "");
                            item.gateName = g.optString("gate_name", g.optString("name", "بوابة"));
                            item.location = g.optString("gate_location", g.optString("location", ""));
                            item.status = g.optString("status", "offline");
                            item.passCount = g.optInt("pass_count", g.optInt("scan_count", 0));
                            item.denyCount = g.optInt("deny_count", 0);
                            item.lastEvent = g.optString("last_event", g.optString("last_heartbeat", ""));
                            item.branchName = g.optString("branch_name", "");
                            items.add(item);
                        }
                        
                        mainHandler.post(() -> {
                            if (!isDestroyed && isAdded()) {
                                updateMonitorList(items);
                            }
                        });
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
        if (dataObj != null) {
            arr = dataObj.optJSONArray("gates");
            if (arr != null) return arr;
        }
        
        return data.optJSONArray("items");
    }

    private void updateMonitorList(List<GateMonitorItem> items) {
        monitorList.clear();
        monitorList.addAll(items);
        
        if (monitorAdapter != null) {
            monitorAdapter.notifyDataSetChanged();
        }
        
        // ✅ تحديث الإحصائيات
        int totalPass = 0;
        int totalDeny = 0;
        int totalGates = items.size();
        
        for (GateMonitorItem item : items) {
            totalPass += item.passCount;
            totalDeny += item.denyCount;
        }
        
        if (tvPassCount != null) tvPassCount.setText("✅ مسموح: " + totalPass);
        if (tvDenyCount != null) tvDenyCount.setText("🚨 إنذار: " + totalDeny);
        if (tvTotalCount != null) tvTotalCount.setText("🚪 البوابات: " + totalGates);
    }

    private int getCurrentBranchId() {
        if (branchesManager != null) {
            return branchesManager.getActiveBranchId();
        }
        return 0;
    }

    // ============================================
    // 🚨 الإنذار
    // ============================================

    public void triggerAlarm(String gateName, String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        mainHandler.post(() -> {
            if (overlayAlarm != null) {
                tvOverlayTitle.setText("🚨 إنذار - " + gateName);
                tvOverlayDetails.setText(itemTitle + " [" + serial + "]");
                overlayAlarm.setVisibility(View.VISIBLE);
                overlayAlarm.setAlpha(0f);
                overlayAlarm.animate().alpha(1f).setDuration(300).start();
            }
            
            // ✅ اهتزاز
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 400, 200, 400};
                vibrator.vibrate(pattern, -1);
            }
            
            // ✅ صوت
            if (miraGatesSettings != null) {
                SoundGenerator.playSound(miraGatesSettings.getSoundAlarm());
            }
            
            logConsole("🚨 إنذار: " + gateName + " - " + itemTitle);
        });
    }

    private void stopAlarm() {
        if (overlayAlarm != null) {
            overlayAlarm.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> overlayAlarm.setVisibility(View.GONE))
                .start();
        }
        
        if (vibrator != null) {
            vibrator.cancel();
        }
        
        logConsole("🔕 تم إيقاف الإنذار");
    }

    // ============================================
    // 📝 السجل
    // ============================================

    private void logConsole(String message) {
        if (isDestroyed || !isAdded()) return;
        
        mainHandler.post(() -> {
            if (isDestroyed || !isAdded()) return;
            
            String currentTime = timeFormat.format(new Date());
            String logLine = "[" + currentTime + "] " + message + "\n";
            
            if (tvDebugConsole != null) {
                tvDebugConsole.append(logLine);
                if (scrollDebugLog != null) {
                    scrollDebugLog.post(() -> scrollDebugLog.fullScroll(View.FOCUS_DOWN));
                }
            }
            Log.d(TAG, message);
        });
    }

    // ============================================
    // 📦 Adapter
    // ============================================

    private static class GateMonitorItem {
        String gateId;
        String gateName;
        String location;
        String status;
        int passCount;
        int denyCount;
        String lastEvent;
        String branchName;
    }

    private class GateMonitorAdapter extends RecyclerView.Adapter<GateMonitorAdapter.VH> {
        private final List<GateMonitorItem> items;
        
        GateMonitorAdapter(List<GateMonitorItem> items) {
            this.items = items;
        }
        
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gate_monitor, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            GateMonitorItem item = items.get(position);
            
            h.tvGateName.setText(item.gateName);
            
            String locationText = item.location.isEmpty() ? "" : " 📍 " + item.location;
            String branchText = item.branchName.isEmpty() ? "" : " 🏢 " + item.branchName;
            h.tvGateName.setText(item.gateName + locationText + branchText);
            
            h.tvGateStats.setText("✅ " + item.passCount + " | ❌ " + item.denyCount);
            h.tvGateLastEvent.setText(item.lastEvent.isEmpty() ? "لا أحداث" : item.lastEvent);
            
            if ("online".equals(item.status) || "armed".equals(item.status)) {
                h.tvGateStatus.setText("🟢 نشطة");
                h.tvGateStatus.setTextColor(0xFF2E7D32);
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_green);
            } else {
                h.tvGateStatus.setText("⚪ غير نشطة");
                h.tvGateStatus.setTextColor(0xFF9E9E9E);
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_red);
            }
        }
        
        @Override
        public int getItemCount() { 
            return items.size(); 
        }
        
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
    public void onDestroyView() {
        super.onDestroyView();
        
        isDestroyed = true;
        
        if (monitorScheduler != null && !monitorScheduler.isShutdown()) {
            monitorScheduler.shutdownNow();
        }
        
        if (gateExecutor != null && !gateExecutor.isShutdown()) {
            gateExecutor.shutdownNow();
        }
        
        if (vibrator != null) {
            vibrator.cancel();
        }
        
        mainHandler.removeCallbacksAndMessages(null);
        
        Log.d(TAG, "Fragment destroyed and resources cleaned");
    }
}
