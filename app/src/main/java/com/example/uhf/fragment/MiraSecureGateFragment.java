package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.OfflineGateCache;
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
    private volatile boolean isCameraOpen = false;

    private OfflineGateCache offlineCache;
    private volatile boolean isOnline = true;

    private UHFMainActivity mContext;
    private MiraGatesSettings miraGatesSettings;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService gateExecutor;
    private ScheduledExecutorService monitorScheduler;
    private ScheduledExecutorService pingScheduler;
    
    // ✅ إدارة الفروع
    private int currentBranchId = 0;
    private boolean isGlobalAdmin = false;
    private Spinner spBranchSelector;
    private TextView tvCurrentBranch;
    private List<BranchInfo> branchesList = new ArrayList<>();
    
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
    
    private String soldEpc = "", soldTitle = "";
    private String unsoldEpc = "", unsoldTitle = "";
    
    private volatile boolean isDestroyed = false;
    private volatile boolean isScanning = false;
    private volatile boolean isLockdown = false;
    private String logFilter = "ALL";
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean isArabic = true;

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(
        new ScanContract(),
        result -> {
            // معالجة نتيجة الكاميرا في خلفية آمنة لمنع تعليق الواجهة
            gateExecutor.execute(() -> {
                try {
                    Thread.sleep(300); // إعطاء مجال لنظام الكاميرا لإغلاق الـ Activity بالكامل
                } catch (InterruptedException ignored) {}

                mainHandler.post(() -> {
                    isCameraOpen = false;
                    
                    if (result.getContents() != null && !isDestroyed) {
                        processInputEpc(result.getContents().trim(), "CAMERA");
                    }
                    
                    // إعادة تشغيل الـ RFID بعد التأكد من تحرير الموارد
                    if (!isLockdown && !isDestroyed) {
                        autoStartScanning();
                    }
                });
            });
        }
    );

    // ==================== Helper Methods ====================
    
    private String getLocalizedText(String arabic, String english) {
        return isArabic ? arabic : english;
    }
    
    private int getColorFromTheme(int attrRes, int fallbackColor) {
        try {
            TypedValue typedValue = new TypedValue();
            Context context = getContext();
            if (context != null && context.getTheme().resolveAttribute(attrRes, typedValue, true)) {
                return typedValue.data;
            }
        } catch (Exception e) {
            Log.e(TAG, "getColorFromTheme: " + e.getMessage());
        }
        return fallbackColor;
    }
    
    private int getSuccessColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorPrimary, Color.parseColor("#4CAF50"));
    }
    
    private int getDangerColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorError, Color.parseColor("#EF4444"));
    }
    
    private int getWarningColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorSecondary, Color.parseColor("#FBBF24"));
    }
    
    private int getTextColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
    }
    
    private int getSecondaryTextColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_secure_gate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        Context context = getContext();
        isArabic = context != null && Locale.getDefault().getLanguage().equals("ar");
        
        initializeComponents();
        initViews(view);
        setupListeners();
        
        startMonitoring();
        startPingMonitor();
        loadTestEpcs();
        autoStartScanning();
        startBackgroundService();
        
        updateSystemStatus(true);
        logConsole(getLocalizedText("🚀 تم تفعيل مركز القيادة الأمنية", "🚀 SECURITY COMMAND CENTER ACTIVATED"));
    }

    private void initializeComponents() {
        Context context = getContext();
        if (context == null) return;
        
        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
        }
        offlineCache = OfflineGateCache.getInstance(context);
        miraGatesSettings = MiraGatesSettings.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        branchesManager = MiraBranchesManager.getInstance(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        gateExecutor = Executors.newFixedThreadPool(4);
        isDestroyed = false;
        
        loadCurrentBranchInfo();
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
        
        spBranchSelector = view.findViewById(R.id.spBranchSelector);
        tvCurrentBranch = view.findViewById(R.id.tvCurrentBranch);
        
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
                else showToast(getLocalizedText("⚠️ لا توجد قطعة مباعة", "⚠️ No sold item available"));
            });
        }
        if (btnSimulateDenied != null) {
            btnSimulateDenied.setOnClickListener(v -> {
                if (!unsoldEpc.isEmpty()) queryMiraGateServer(unsoldEpc);
                else showToast(getLocalizedText("⚠️ لا توجد قطعة غير مباعة", "⚠️ No unsold item available"));
            });
        }
        if (btnScanBarcode != null) {
    btnScanBarcode.setOnClickListener(v -> {
        isCameraOpen = true;
        
        if (mContext != null) {
            RfidHardwareHelper.getInstance().prepareForCamera(mContext.mReader, () -> {
                ScanOptions options = new ScanOptions();
                options.setPrompt(getLocalizedText("📷 وجه الكاميرا نحو باركود القطعة", "📷 Aim camera at item barcode"));
                options.setBeepEnabled(false);
                options.setOrientationLocked(true);
                
                try {
                    barcodeLauncher.launch(options);
                } catch (Exception e) {
                    isCameraOpen = false;
                    logConsole(getLocalizedText("⚠️ تعذر فتح الكاميرا: ", "⚠️ Camera error: ") + e.getMessage());
                    autoStartScanning();
                }
            });
        }
    });
}

        if (btnEmergencyLockdown != null) btnEmergencyLockdown.setOnClickListener(v -> toggleLockdown());
        
        if (btnClearConsole != null) {
            btnClearConsole.setOnClickListener(v -> {
                if (tvDebugConsole != null) tvDebugConsole.setText("");
            });
        }
        
        if (btnFilterAll != null) btnFilterAll.setOnClickListener(v -> { 
            logFilter = "ALL"; 
            logConsole(getLocalizedText("🔍 فلتر: الكل", "🔍 Filter: All")); 
        });
        if (btnFilterAlerts != null) btnFilterAlerts.setOnClickListener(v -> { 
            logFilter = "ALERTS"; 
            logConsole(getLocalizedText("🔍 فلتر: الإنذارات", "🔍 Filter: Alerts")); 
        });
        if (btnFilterPass != null) btnFilterPass.setOnClickListener(v -> { 
            logFilter = "PASS"; 
            logConsole(getLocalizedText("🔍 فلتر: المقبول", "🔍 Filter: Pass")); 
        });
    }

    private void showToast(String message) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================
    // ✅ إدارة الفروع
    // ============================================
    
    private void loadCurrentBranchInfo() {
        try {
            Context context = getContext();
            if (context == null) return;
            
            currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
            isGlobalAdmin = permissionManager != null && 
                (permissionManager.isSystemAdmin() || permissionManager.isOrgAdmin());
            
            mainHandler.post(() -> {
                if (isGlobalAdmin) {
                    loadBranchesForSelector();
                } else {
                    if (spBranchSelector != null) {
                        spBranchSelector.setVisibility(View.GONE);
                    }
                    if (tvCurrentBranch != null) {
                        tvCurrentBranch.setText(branchesManager != null ? branchesManager.getActiveBranchName() : "");
                        tvCurrentBranch.setVisibility(View.VISIBLE);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "loadCurrentBranchInfo: " + e.getMessage());
        }
    }

    private void loadBranchesForSelector() {
        if (gateExecutor == null || getContext() == null) return;
        
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                
                List<?> allBranches = branchesManager != null ? branchesManager.getAllBranches() : new ArrayList<>();
                
                branchesList.clear();
                if (allBranches != null) {
                    for (Object branchObj : allBranches) {
                        BranchInfo info = convertToBranchInfo(branchObj);
                        if (info != null) {
                            branchesList.add(info);
                        }
                    }
                }
                
                if (!branchesList.isEmpty()) {
                    mainHandler.post(() -> {
                        if (spBranchSelector != null && isAdded()) {
                            List<String> branchNames = new ArrayList<>();
                            branchNames.add(getLocalizedText("🌐 كل الفروع", "🌐 All Branches"));
                            for (BranchInfo branch : branchesList) {
                                branchNames.add(branch.branchName);
                            }
                            
                            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                context,
                                android.R.layout.simple_spinner_item,
                                branchNames
                            );
                            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            spBranchSelector.setAdapter(adapter);
                            
                            if (currentBranchId > 0) {
                                for (int i = 0; i < branchesList.size(); i++) {
                                    if (branchesList.get(i).branchId == currentBranchId) {
                                        spBranchSelector.setSelection(i + 1);
                                        break;
                                    }
                                }
                            }
                            
                            spBranchSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                                @Override
                                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                    if (position == 0) {
                                        currentBranchId = 0;
                                    } else if (position - 1 < branchesList.size()) {
                                        currentBranchId = branchesList.get(position - 1).branchId;
                                    }
                                    
                                    loadAllGatesStatus();
                                    
                                    String branchName = position == 0 ? 
                                        getLocalizedText("كل الفروع", "All Branches") : 
                                        branchesList.get(position - 1).branchName;
                                    
                                    logConsole(getLocalizedText("🔄 تم تغيير الفرع إلى: ", "🔄 Branch changed to: ") + branchName);
                                }
                                
                                @Override
                                public void onNothingSelected(AdapterView<?> parent) {}
                            });
                            
                            spBranchSelector.setVisibility(View.VISIBLE);
                            if (tvCurrentBranch != null) {
                                tvCurrentBranch.setVisibility(View.GONE);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "loadBranchesForSelector: " + e.getMessage());
            }
        });
    }

    private BranchInfo convertToBranchInfo(Object obj) {
        if (obj == null) return null;
        
        BranchInfo info = new BranchInfo();
        Class<?> clazz = obj.getClass();

        info.branchId = extractIntProperty(clazz, obj, new String[]{"getId", "getBranchId", "id", "branchId"});
        info.branchName = extractStringProperty(clazz, obj, new String[]{"getName", "getBranchName", "name", "branchName"});
        
        if (info.branchName == null || info.branchName.isEmpty()) {
            info.branchName = (isArabic ? "فرع " : "Branch ") + info.branchId;
        }

        return info;
    }

    private int extractIntProperty(Class<?> clazz, Object obj, String[] names) {
        for (String name : names) {
            try {
                java.lang.reflect.Method method = clazz.getMethod(name);
                Object result = method.invoke(obj);
                if (result instanceof Number) return ((Number) result).intValue();
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                Object result = field.get(obj);
                if (result instanceof Number) return ((Number) result).intValue();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private String extractStringProperty(Class<?> clazz, Object obj, String[] names) {
        for (String name : names) {
            try {
                java.lang.reflect.Method method = clazz.getMethod(name);
                Object result = method.invoke(obj);
                if (result != null) return result.toString();
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                Object result = field.get(obj);
                if (result != null) return result.toString();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private int getCurrentBranchId() {
        return currentBranchId;
    }

    private String getCurrentBranchName() {
        try {
            if (currentBranchId == 0) {
                return getLocalizedText("كل الفروع", "All Branches");
            }
            
            for (BranchInfo branch : branchesList) {
                if (branch.branchId == currentBranchId) {
                    return branch.branchName;
                }
            }
            
            return branchesManager != null ? branchesManager.getActiveBranchName() : String.valueOf(currentBranchId);
        } catch (Exception e) {
            return String.valueOf(currentBranchId);
        }
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
                logConsole(getLocalizedText("📱 خدمة المراقبة الخلفية مفعلة", "📱 Background monitoring service enabled"));
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
            logConsole(getLocalizedText("🔴 تم تفعيل الإغلاق الطارئ", "🔴 LOCKDOWN ACTIVATED"));
            if (btnEmergencyLockdown != null) {
                btnEmergencyLockdown.setText(getLocalizedText("🔓 فتح البوابات", "🔓 Unlock Gates"));
                btnEmergencyLockdown.setBackgroundColor(getSuccessColor());
            }
        } else {
            unlockAllGates();
            updateSystemStatus(true);
            logConsole(getLocalizedText("🟢 تم إلغاء الإغلاق الطارئ", "🟢 LOCKDOWN DEACTIVATED"));
            if (btnEmergencyLockdown != null) {
                btnEmergencyLockdown.setText(getLocalizedText("🔒 إغلاق طارئ", "🔒 Emergency Lockdown"));
                btnEmergencyLockdown.setBackgroundColor(getDangerColor());
            }
        }
    }

    private void lockAllGates() {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject payload = new JSONObject();
                payload.put("command", "LOCK_ALL");
                payload.put("branch_id", getCurrentBranchId());
                client.postWithAuth("/gate-command", payload);
            } catch (Exception e) {
                Log.e(TAG, "lockAllGates: " + e.getMessage());
            }
        });
    }

    private void unlockAllGates() {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject payload = new JSONObject();
                payload.put("command", "UNLOCK_ALL");
                payload.put("branch_id", getCurrentBranchId());
                client.postWithAuth("/gate-command", payload);
            } catch (Exception e) {
                Log.e(TAG, "unlockAllGates: " + e.getMessage());
            }
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
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                long start = System.currentTimeMillis();
                boolean ok = client.pingApi();
                long latency = System.currentTimeMillis() - start;
                mainHandler.post(() -> {
                    if (tvPingLatency != null) {
                        if (ok) {
                            tvPingLatency.setText(getLocalizedText("📡 Ping: ", "📡 Ping: ") + latency + " ms | ✅ " + getLocalizedText("متصل", "Connected"));
                            tvPingLatency.setTextColor(getSuccessColor());
                        } else {
                            tvPingLatency.setText(getLocalizedText("📡 Ping: -- | ⚠️ غير متصل", "📡 Ping: -- | ⚠️ Disconnected"));
                            tvPingLatency.setTextColor(getWarningColor());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "checkPing: " + e.getMessage());
            }
        });
    }

    private void updateSystemStatus(boolean armed) {
        if (tvSystemStatus != null) {
            if (isLockdown) {
                tvSystemStatus.setText(getLocalizedText("🔴 إغلاق طارئ", "🔴 LOCKDOWN"));
                tvSystemStatus.setTextColor(getDangerColor());
            } else if (armed) {
                tvSystemStatus.setText(getLocalizedText("🟢 النظام مسلح", "🟢 SYSTEM ARMED"));
                tvSystemStatus.setTextColor(getSuccessColor());
            } else {
                tvSystemStatus.setText(getLocalizedText("🟡 وضع الاستعداد", "🟡 STANDBY"));
                tvSystemStatus.setTextColor(getWarningColor());
            }
        }
    }

    private void updateLiveTicker(String message, boolean isAlert) {
        if (tvLiveTickerText != null) {
            tvLiveTickerText.setText(message);
            tvLiveTickerText.setTextColor(isAlert ? getDangerColor() : getSuccessColor());
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
    // ✅ المسح المعدل (حل مشكلة التجميد النهائي)
    // ============================================
    private void autoStartScanning() {
        mainHandler.postDelayed(() -> {
            if (!isDestroyed && isAdded() && !isLockdown && !isCameraOpen) {
                startScanning();
            }
        }, 1200); // زيادة أمان المهلة الزمنية لضمان إغلاق الكاميرا بالكامل
    }

    private void toggleScanning() {
        if (isScanning) stopScanning(); else startScanning();
    }

    private void startScanning() {
    if (isDestroyed || mContext == null || mContext.mReader == null || isCameraOpen) return;

    RfidHardwareHelper.getInstance().startAsync(
        mContext.mReader,
        miraGatesSettings.getRfidPower(),
        info -> {
            if (!isDestroyed && info != null && info.getEPC() != null) {
                processInputEpc(info.getEPC(), "RFID");
            }
        },
        () -> {
            isScanning = true;
            if (btnToggleScan != null) btnToggleScan.setText(getLocalizedText("🔴 إيقاف المسح", "🔴 Stop Scan"));
            logConsole(getLocalizedText("🛡️ قارئ RFID يعمل", "🛡️ RFID reader active"));
        }
    );
}

private void stopScanning() {
    if (mContext != null) {
        RfidHardwareHelper.getInstance().stopAsync(mContext.mReader, () -> {
            isScanning = false;
            if (btnToggleScan != null) btnToggleScan.setText(getLocalizedText("🛡️ تفعيل المسح", "🛡️ Start Scan"));
        });
    }
}


    // دالة إيقاف متزامنة وتعمل بأمان داخل الخيط الخلفي
    private void stopScanningSync() {
        if (mContext != null && mContext.mReader != null) {
            try { 
                mContext.mReader.stopInventory(); 
                mContext.mReader.setInventoryCallback(null); 
            } catch (Exception e) {
                Log.e(TAG, "stopScanningSync: " + e.getMessage());
            }
        }
        isScanning = false;
        mainHandler.post(() -> {
            if (btnToggleScan != null) btnToggleScan.setText(getLocalizedText("🛡️ تفعيل المسح", "🛡️ Start Scan"));
        });
    }

    // ============================================
    // 📡 المراقبة
    // ============================================
    private void startMonitoring() {
        monitorScheduler = Executors.newSingleThreadScheduledExecutor();
        monitorScheduler.scheduleAtFixedRate(() -> {
            if (!isDestroyed && isAdded()) {
                loadAllGatesStatus();
                checkConnection();
            }
        }, 0, MONITOR_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        loadAllGatesStatus();
        updateLocalAllowedCache();
    }

    private void loadAllGatesStatus() {
        if (gateExecutor == null || getContext() == null) return;
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                
                MiraApiClient.ApiResponse response = client.getGatesByBranch(
                    currentBranchId > 0 ? currentBranchId : 0
                );
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = extractGatesArray(response.data);
                    if (arr != null) {
                        List<GateMonitorItem> items = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject g = arr.optJSONObject(i);
                            GateMonitorItem item = new GateMonitorItem();
                            item.gateId = g.optString("gate_id", "");
                            item.gateName = g.optString("gate_name", getLocalizedText("بوابة", "Gate"));
                            item.location = g.optString("gate_location", "");
                            item.status = g.optString("status", "offline");
                            item.passCount = g.optInt("pass_count", 0);
                            item.denyCount = g.optInt("deny_count", 0);
                            item.lastEvent = g.optString("last_event", "");
                            item.branchId = g.optInt("branch_id", 0);
                            item.branchName = g.optString("branch_name", "");
                            items.add(item);
                        }
                        mainHandler.post(() -> updateMonitorList(items));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadAllGatesStatus: " + e.getMessage());
            }
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
        for (GateMonitorItem item : items) { 
            pass += item.passCount; 
            deny += item.denyCount; 
        }
        if (tvPassCount != null) tvPassCount.setText(String.valueOf(pass));
        if (tvDenyCount != null) tvDenyCount.setText(String.valueOf(deny));
        if (tvTotalCount != null) tvTotalCount.setText(String.valueOf(items.size()));
        
        updateRiskMeter(pass, deny);
        
        if (tvCurrentBranch != null) {
            String branchLabel;
            if (currentBranchId == 0) {
                branchLabel = getLocalizedText("🌐 كل الفروع", "🌐 All Branches");
            } else {
                branchLabel = getLocalizedText("📍 الفرع: ", "📍 Branch: ") + getCurrentBranchName();
            }
            tvCurrentBranch.setText(branchLabel);
        }
    }

    private void updateRiskMeter(int pass, int deny) {
        int total = pass + deny;
        if (total > 0) {
            int risk = (deny * 100) / total;
            if (pbRiskMeter != null) pbRiskMeter.setProgress(risk);
            if (tvRiskPercentage != null) {
                tvRiskPercentage.setText(getLocalizedText("نسبة الإنذارات: ", "Alert Rate: ") + risk + "%");
            }
        }
    }

    // ============================================
    // ✅ EPCs
    // ============================================
    private void loadTestEpcs() {
        if (gateExecutor == null || getContext() == null) return;
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                MiraApiClient.ApiResponse response = client.getTestEpcs();
                if (response != null && response.isSuccess && response.data != null) {
                    soldEpc = response.data.optString("sold_epc", "");
                    unsoldEpc = response.data.optString("unsold_epc", "");
                    mainHandler.post(() -> logConsole(getLocalizedText("✅ EPCs للمحاكاة جاهزة", "✅ Simulation EPCs ready")));
                }
            } catch (Exception e) {
                Log.e(TAG, "loadTestEpcs: " + e.getMessage());
            }
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
        
        logConsole(getLocalizedText("📡 [", "📡 [") + sourceType + getLocalizedText("] فحص: ", "] Scan: ") + cleanedEpc);
        
        if (offlineCache != null && offlineCache.isAllowedLocally(cleanedEpc)) {
            mainHandler.post(() -> handleGateDecision(true, getLocalizedText("قطعة مصرح بها (محلي)", "Authorized item (local)"), cleanedEpc));
            return;
        }
        
        if (!isOnline) {
            logConsole(getLocalizedText("⚠️ بدون إنترنت - رفض أمني فوري", "⚠️ Offline - immediate security denial"));
            mainHandler.post(() -> {
                handleGateDecision(false, getLocalizedText("بدون إنترنت", "Offline"), cleanedEpc);
                if (offlineCache != null) {
                    offlineCache.saveOfflineEvent(cleanedEpc, false, getLocalizedText("بدون إنترنت", "Offline"), "local");
                }
            });
            return;
        }
        
        queryMiraGateServer(cleanedEpc);
    }

    private void syncOfflineEvents() {
        if (gateExecutor == null || offlineCache == null) return;
        
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                JSONArray pendingEvents = offlineCache.getOfflineEvents();
                if (pendingEvents.length() == 0) return;
                
                MiraApiClient client = MiraApiClient.getInstance(context);
                MiraApiClient.ApiResponse response = client.syncOfflineTransactions(pendingEvents);
                
                if (response != null && response.isSuccess) {
                    offlineCache.clearOfflineEvents();
                    offlineCache.saveLastSyncTime(System.currentTimeMillis());
                    mainHandler.post(() -> logConsole(getLocalizedText("✅ تمت مزامنة ", "✅ Synced ") + pendingEvents.length() + getLocalizedText(" حدث", " events")));
                }
            } catch (Exception e) {
                Log.e(TAG, "Sync offline: " + e.getMessage());
            }
        });
    }

    private void updateLocalAllowedCache() {
        if (gateExecutor == null || getContext() == null) return;
        
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                MiraApiClient.ApiResponse response = client.getWithAuth("/allowed-list");
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("allowed_epcs");
                    if (arr != null) {
                        Set<String> allowed = ConcurrentHashMap.newKeySet();
                        for (int i = 0; i < arr.length(); i++) {
                            String epc = arr.optString(i, "");
                            if (!epc.isEmpty()) allowed.add(epc);
                        }
                        
                        localAllowedEpcCache.clear();
                        localAllowedEpcCache.addAll(allowed);
                        
                        if (offlineCache != null) {
                            offlineCache.saveAllowedEpcs(allowed);
                        }
                        
                        mainHandler.post(() -> logConsole(getLocalizedText("✅ القائمة المسموحة: ", "✅ Allowed list: ") + allowed.size()));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "updateLocalAllowedCache: " + e.getMessage());
            }
        });
    }

    private void checkConnection() {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                boolean wasOnline = isOnline;
                isOnline = client.pingApi();
                
                if (isOnline != wasOnline) {
                    mainHandler.post(() -> {
                        if (isOnline) {
                            logConsole(getLocalizedText("🟢 عاد الاتصال - جاري المزامنة...", "🟢 Connection restored - syncing..."));
                            updateLocalAllowedCache();
                            syncOfflineEvents();
                        } else {
                            logConsole(getLocalizedText("🔴 انقطع الاتصال - الوضع المحلي", "🔴 Connection lost - local mode"));
                        }
                        if (offlineCache != null) offlineCache.saveConnectionState(isOnline);
                    });
                }
            } catch (Exception e) {
                isOnline = false;
            }
        });
    }

    private void queryMiraGateServer(String epc) {
        if (gateExecutor == null) return;
        gateExecutor.execute(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject params = new JSONObject();
                params.put("context", "gate");
                params.put("branch_id", getCurrentBranchId());
                
                Future<MiraApiClient.ApiResponse> future = gateExecutor.submit(() -> client.authorize(epc, "gate", params));
                MiraApiClient.ApiResponse response = future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String itemTitle = item != null ? item.optString("title", getLocalizedText("قطعة", "Item")) : getLocalizedText("قطعة", "Item");
                    String serial = item != null ? item.optString("serial", epc) : epc;
                    String message = decision != null ? decision.optString("message", "") : "";
                    
                    if (allowed) localAllowedEpcCache.add(epc);
                    
                    mainHandler.post(() -> {
                        handleGateDecision(allowed, itemTitle, serial);
                        if (!message.isEmpty()) logConsole((allowed ? "✅ " : "🚨 ") + message);
                    });
                }
            } catch (TimeoutException e) {
                mainHandler.post(() -> handleGateDecision(false, getLocalizedText("انقطاع اتصال", "Connection timeout"), epc));
            } catch (Exception e) {
                mainHandler.post(() -> handleGateDecision(false, getLocalizedText("غير مصرح", "Unauthorized"), epc));
            }
        });
    }

    private void handleGateDecision(boolean allowed, String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        if (allowed && "ALERTS".equals(logFilter)) return;
        if (!allowed && "PASS".equals(logFilter)) return;
        
        if (allowed) {
            logConsole(getLocalizedText("✅ خروج شرعي: ", "✅ Legal exit: ") + itemTitle);
            updateLiveTicker("✅ " + itemTitle, false);
            SoundGenerator.playSound(miraGatesSettings.getSoundPass());
        } else {
            logConsole(getLocalizedText("🚨 خروج غير شرعي: ", "🚨 Illegal exit: ") + itemTitle);
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
                tvOverlayTitle.setText(getLocalizedText("🚨 إنذار: خروج غير مصرح", "🚨 ALERT: UNAUTHORIZED BREACH"));
                tvOverlayDetails.setText(itemTitle + "\n[" + serial + "]");
                overlayAlarm.setVisibility(View.VISIBLE);
                overlayAlarm.setBackgroundColor(getDangerColor());
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
        if (overlayAlarm != null) { 
            overlayAlarm.clearAnimation(); 
            overlayAlarm.setVisibility(View.GONE); 
        }
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
        String branchName;
        int passCount, denyCount;
        int branchId;
    }
    
    private static class BranchInfo {
        int branchId;
        String branchName;
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
            h.tvGateName.setTextColor(getTextColor());
            
            if (h.tvBranchName != null) {
                if (currentBranchId == 0 && !item.branchName.isEmpty()) {
                    h.tvBranchName.setText("📍 " + item.branchName);
                    h.tvBranchName.setVisibility(View.VISIBLE);
                } else {
                    h.tvBranchName.setVisibility(View.GONE);
                }
            }
            
            h.tvGateStats.setText("✅ " + item.passCount + " | ❌ " + item.denyCount);
            h.tvGateStats.setTextColor(getSecondaryTextColor());
            
            h.tvGateLastEvent.setText(item.lastEvent.isEmpty() ? getLocalizedText("لا أحداث", "No events") : item.lastEvent);
            h.tvGateLastEvent.setTextColor(getSecondaryTextColor());
            
            if ("alert".equals(item.status)) {
                h.tvGateStatus.setText(getLocalizedText("🔴 إنذار", "🔴 Alert"));
                h.tvGateStatus.setTextColor(getDangerColor());
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_red);
            } else if ("online".equals(item.status) || "armed".equals(item.status)) {
                h.tvGateStatus.setText(getLocalizedText("🟢 نشطة", "🟢 Active"));
                h.tvGateStatus.setTextColor(getSuccessColor());
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_green);
            } else {
                h.tvGateStatus.setText(getLocalizedText("⚪ غير نشطة", "⚪ Inactive"));
                h.tvGateStatus.setTextColor(getSecondaryTextColor());
                h.vStatusDot.setBackgroundResource(R.drawable.circle_indicator_red);
            }
        }
        
        @Override public int getItemCount() { return items.size(); }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvGateName, tvGateStatus, tvGateStats, tvGateLastEvent, tvBranchName;
            View vStatusDot;
            VH(@NonNull View itemView) {
                super(itemView);
                tvGateName = itemView.findViewById(R.id.tvGateName);
                tvGateStatus = itemView.findViewById(R.id.tvGateStatus);
                tvGateStats = itemView.findViewById(R.id.tvGateStats);
                tvGateLastEvent = itemView.findViewById(R.id.tvGateLastEvent);
                tvBranchName = itemView.findViewById(R.id.tvBranchName);
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
    stopScanning();
}

@Override
public void onResume() {
    super.onResume();
    // مهلة أمان 600ms للعودة الهادئة وتفريغ موارد الكاميرا بالكامل
    mainHandler.postDelayed(() -> {
        if (!isScanning && isAdded() && !isLockdown && !isCameraOpen) {
            startScanning();
        }
    }, 600);
}


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed = true;
        stopScanningSync();
        stopAlarm();
        if (monitorScheduler != null) monitorScheduler.shutdownNow();
        if (pingScheduler != null) pingScheduler.shutdownNow();
        if (gateExecutor != null) gateExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
            }
