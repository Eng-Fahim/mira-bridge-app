package com.example.uhf.fragment;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraSettingsManager;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.view.CircleSeekBar;
import com.example.uhf.view.RadarView;
import com.rscja.deviceapi.entity.RadarLocationEntity;
import com.rscja.deviceapi.interfaces.IUHF;
import com.rscja.deviceapi.interfaces.IUHFRadarLocationCallback;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;

/**
 * MIRA Radar Location Fragment
 * 
 * المميزات:
 * - رادار تحديد موقع Chainway UHF
 * - استعلام MIRA ID المركزي عبر MiraApiClient
 * - بطاقة معلومات القطعة مع بيانات الفرع
 * - عداد إحصائيات (مصرح/ممنوع/غير معروف)
 * - سجل آخر 5 قطع ممسوحة
 * - مؤشر RSSI لقوة الإشارة
 * - تأثيرات بصرية متقدمة
 * - أوضاع مسح متعددة
 * - وضع محاكاة للتطوير
 * - تصدير النتائج CSV
 * - دعم عربي/إنجليزي كامل
 * - ربط بالفرع النشط
 */
public class UHFRadarLocationFragment extends KeyDwonFragment {

    private static final String TAG = "UHFRadarLocationFrag";
    private UHFMainActivity mContext;

    // ==================== UI Elements ====================
    private RadarView radarView;
    private EditText etEPC;
    private Button btStart, btStop, btnExportResults;
    private CircleSeekBar seekBarPower;
    private ProgressBar rssiBar;
    private LinearLayout layoutMiraRadarInfo;
    private TextView tvRadarItemStatus, tvRadarItemName, tvRadarItemDetails;
    private TextView tvAuthorizedCount, tvDeniedCount, tvUnknownCount;
    private TextView tvRecentScans, tvRadarMode;
    private Button btnModeSingle, btnModeOpen, btnModeGate;

    // ==================== State ====================
    private boolean inventoryFlag = false;
    private String targetEpc;
    private int progress = 5;
    private int authorizedCount = 0, deniedCount = 0, unknownCount = 0;
    private final List<String> recentScans = new ArrayList<>();
    private final Random random = new Random();

    // ==================== Managers ====================
    private MiraSettingsManager settingsManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean simulationMode;
    private boolean isArabic;

    // ==================== Radar Modes ====================
    private enum RadarMode { SINGLE_TARGET, OPEN_SCAN, SECURITY_GATE, INVENTORY_COUNT }
    private RadarMode currentMode = RadarMode.SINGLE_TARGET;

    // ==================== Simulation ====================
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private float simulationAngle = 0;

    // ==================== Lifecycle ====================

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_uhf_radar_location, container, false);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        if (mContext == null) return;
        mContext.currentFragment = this;

        // تهيئة المدراء
        settingsManager = MiraSettingsManager.getInstance(mContext);
        branchesManager = MiraBranchesManager.getInstance(mContext);
        orgProfile = MiraOrgProfileManager.getInstance(mContext);
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        simulationMode = settingsManager.getBoolean("radar_simulation", true);

        initViews();
        applyLanguage();
        setupListeners();
        restoreState();
    }

    // ==================== Init ====================

    private void initViews() {
        View v = getView();
        if (v == null) return;

        radarView = v.findViewById(R.id.radarView);
        etEPC = v.findViewById(R.id.etRadarEPC);
        btStart = v.findViewById(R.id.btRadarStart);
        btStop = v.findViewById(R.id.btRadarStop);
        seekBarPower = v.findViewById(R.id.seekBarPower);
        rssiBar = v.findViewById(R.id.rssiBar);
        btnExportResults = v.findViewById(R.id.btnExportResults);

        layoutMiraRadarInfo = v.findViewById(R.id.layoutMiraRadarInfo);
        tvRadarItemStatus = v.findViewById(R.id.tvRadarItemStatus);
        tvRadarItemName = v.findViewById(R.id.tvRadarItemName);
        tvRadarItemDetails = v.findViewById(R.id.tvRadarItemDetails);
        tvAuthorizedCount = v.findViewById(R.id.tvAuthorizedCount);
        tvDeniedCount = v.findViewById(R.id.tvDeniedCount);
        tvUnknownCount = v.findViewById(R.id.tvUnknownCount);
        tvRecentScans = v.findViewById(R.id.tvRecentScans);
        tvRadarMode = v.findViewById(R.id.tvRadarMode);

        btnModeSingle = v.findViewById(R.id.btnModeSingle);
        btnModeOpen = v.findViewById(R.id.btnModeOpen);
        btnModeGate = v.findViewById(R.id.btnModeGate);

        seekBarPower.setEnabled(false);
        seekBarPower.setProgress(5);
        updateModeUI();
    }

    private void applyLanguage() {
        if (btStart != null) btStart.setText(isArabic ? "▶ بدء تحديد الموقع" : "▶ Start Locating");
        if (btStop != null) btStop.setText(isArabic ? "⏹ إيقاف" : "⏹ Stop");
        if (etEPC != null) etEPC.setHint(isArabic ? "أدخل EPC للتتبع" : "Enter EPC to track");
        if (btnExportResults != null) btnExportResults.setText(isArabic ? "📤 تصدير" : "📤 Export");
        if (btnModeSingle != null) btnModeSingle.setText(isArabic ? "🎯 محدد" : "🎯 Target");
        if (btnModeOpen != null) btnModeOpen.setText(isArabic ? "📡 مفتوح" : "📡 Open");
        if (btnModeGate != null) btnModeGate.setText(isArabic ? "🚪 بوابة" : "🚪 Gate");
    }

    private void setupListeners() {
        btStart.setOnClickListener(v -> startLocated());
        btStop.setOnClickListener(v -> stopLocated());

        if (btnExportResults != null) btnExportResults.setOnClickListener(v -> exportScanResults());
        if (btnModeSingle != null) btnModeSingle.setOnClickListener(v -> switchMode(RadarMode.SINGLE_TARGET));
        if (btnModeOpen != null) btnModeOpen.setOnClickListener(v -> switchMode(RadarMode.OPEN_SCAN));
        if (btnModeGate != null) btnModeGate.setOnClickListener(v -> switchMode(RadarMode.SECURITY_GATE));

        settingsManager.registerListener("radar_fragment", (key, value) -> {
            if ("radar_simulation".equals(key)) {
                simulationMode = (Boolean) value;
                if (inventoryFlag) { stopLocated(); startLocated(); }
            }
        });
    }

    private void restoreState() {
        getView().post(() -> {
            if (mContext.tagList.size() > mContext.selectIndex && mContext.selectIndex >= 0) {
                String selectItem = mContext.tagList.get(mContext.selectIndex).getEPC();
                if (selectItem != null && !selectItem.isEmpty()) {
                    etEPC.setText(selectItem);
                    targetEpc = selectItem;
                }
            }
        });
    }

    // ==================== Radar Modes ====================

    private void switchMode(RadarMode mode) {
        currentMode = mode;
        if (inventoryFlag) { stopLocated(); startLocated(); }
        updateModeUI();
        Toast.makeText(mContext, getModeName(mode), Toast.LENGTH_SHORT).show();
    }

    private String getModeName(RadarMode mode) {
        switch (mode) {
            case SINGLE_TARGET: return isArabic ? "🎯 بحث عن قطعة محددة" : "🎯 Single Target Search";
            case OPEN_SCAN: return isArabic ? "📡 مسح مفتوح" : "📡 Open Scan";
            case SECURITY_GATE: return isArabic ? "🚪 وضع البوابة الأمنية" : "🚪 Security Gate Mode";
            case INVENTORY_COUNT: return isArabic ? "📊 وضع الجرد السريع" : "📊 Quick Inventory Mode";
            default: return "";
        }
    }

    private void updateModeUI() {
        if (tvRadarMode != null) tvRadarMode.setText(getModeName(currentMode));
        if (btnModeSingle != null) btnModeSingle.setAlpha(currentMode == RadarMode.SINGLE_TARGET ? 1f : 0.5f);
        if (btnModeOpen != null) btnModeOpen.setAlpha(currentMode == RadarMode.OPEN_SCAN ? 1f : 0.5f);
        if (btnModeGate != null) btnModeGate.setAlpha(currentMode == RadarMode.SECURITY_GATE ? 1f : 0.5f);
    }

    // ==================== Start / Stop ====================

    @SuppressLint("LongLogTag")
    private void startLocated() {
        if (inventoryFlag) return;

        radarView.clearPanel();
        targetEpc = etEPC.getText().toString().trim();

        // إعادة تعيين العدادات
        authorizedCount = 0; deniedCount = 0; unknownCount = 0;
        recentScans.clear();
        updateCounters();
        if (tvRecentScans != null) tvRecentScans.setText("");
        if (layoutMiraRadarInfo != null) layoutMiraRadarInfo.setVisibility(View.GONE);
        if (rssiBar != null) rssiBar.setProgress(0);

        if (simulationMode) {
            startSimulatedRadar();
            return;
        }

        String epcToTrack = (currentMode == RadarMode.OPEN_SCAN) ? "" : targetEpc;

        boolean result = mContext.mReader.startRadarLocation(mContext, epcToTrack, IUHF.Bank_EPC, 32,
            new IUHFRadarLocationCallback() {
                @Override
                public void getLocationValue(List<RadarLocationEntity> list) {
                    radarView.bindingData(list, epcToTrack);
                    for (RadarLocationEntity entity : list) {
                        String detectedEpc = entity.getTag();
                        if (!TextUtils.isEmpty(detectedEpc)) {
                            queryMiraItem(detectedEpc);
                            mContext.playSoundDelayed(entity.getValue());
                        }
                    }
                }
                @Override
                public void getAngleValue(int angle) {
                    radarView.setRotation(-angle);
                }
            });

        if (!result) {
            if (!TextUtils.isEmpty(targetEpc)) {
                queryMiraItem(targetEpc);
                Toast.makeText(mContext, isArabic ? "⚠️ الرادار غير مدعوم - تم جلب معلومات MIRA فقط" : "⚠️ Radar unsupported - MIRA info only", Toast.LENGTH_LONG).show();
            } else {
                UIHelper.ToastMessage(mContext, isArabic ? "فشل البدء - أدخل EPC صحيح" : "Start failed - Enter valid EPC");
            }
            return;
        }

        setupSeekBar();
        inventoryFlag = true;
        btStart.setEnabled(false);
        etEPC.setEnabled(false);
        radarView.startRadar();
    }

    private void setupSeekBar() {
        seekBarPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) { progress = p; }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (mContext != null && mContext.mReader != null) mContext.mReader.setDynamicDistance(35 - progress);
            }
        });
        seekBarPower.setEnabled(true);
    }

    @SuppressLint("LongLogTag")
    private void stopLocated() {
        if (!inventoryFlag) return;

        if (simulationMode) {
            if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
        } else {
            if (mContext != null && mContext.mReader != null && !mContext.mReader.stopRadarLocation()) {
                Log.e(TAG, "stopLocated failure");
                mContext.playSound(2);
            }
        }

        radarView.stopRadar();
        inventoryFlag = false;
        btStart.setEnabled(true);
        etEPC.setEnabled(true);
        seekBarPower.setOnSeekBarChangeListener(null);
        seekBarPower.setProgress(5);
        seekBarPower.setEnabled(false);
    }

    // ==================== Simulation ====================

    private void startSimulatedRadar() {
        if (!TextUtils.isEmpty(targetEpc)) queryMiraItem(targetEpc);

        radarView.startRadar();
        seekBarPower.setEnabled(true);
        inventoryFlag = true;
        btStart.setEnabled(false);
        etEPC.setEnabled(false);

        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!inventoryFlag) return;
                simulationAngle += 5;
                if (simulationAngle >= 360) simulationAngle = 0;
                List<RadarLocationEntity> simulatedList = new ArrayList<>();
                radarView.bindingData(simulatedList, targetEpc);
                radarView.setRotation(-simulationAngle);
                if (settingsManager.getBoolean("sound_on_scan", true) && mContext != null) mContext.playSound(1);
                simulationHandler.postDelayed(this, 1500);
            }
        };
        simulationHandler.post(simulationRunnable);
        Toast.makeText(mContext, isArabic ? "⚡ وضع المحاكاة" : "⚡ Simulation Mode", Toast.LENGTH_SHORT).show();
    }

    // ==================== MIRA Query ====================

    private void queryMiraItem(String epc) {
        if (!settingsManager.getBoolean("auto_query_mira", true)) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                // ✅ إرسال branch_id مع الاستعلام
                JSONObject extraParams = new JSONObject();
                extraParams.put("branch_id", branchesManager.getActiveBranchId());

                MiraApiClient.ApiResponse response = client.authorize(epc, "scan", extraParams);

                if (getActivity() != null && response != null && response.isSuccess && response.data != null) {
                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String status = allowed ? "AUTHORIZED" : "DENIED";

                    getActivity().runOnUiThread(() -> {
                        updateMiraRadarInfo(epc, decision, item);
                        addToRecentScans(epc, status);
                        animateDetection(allowed);
                        updateRssiBar();
                    });
                } else {
                    getActivity().runOnUiThread(() -> {
                        unknownCount++;
                        updateCounters();
                        addToRecentScans(epc, "UNKNOWN");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "MIRA query error: " + e.getMessage());
            }
        });
    }

    // ==================== UI Updates ====================

    private void updateMiraRadarInfo(String epc, JSONObject decision, JSONObject item) {
        if (!settingsManager.getBoolean("show_mira_card", true)) {
            if (layoutMiraRadarInfo != null) layoutMiraRadarInfo.setVisibility(View.GONE);
            return;
        }

        if (layoutMiraRadarInfo != null) layoutMiraRadarInfo.setVisibility(View.VISIBLE);

        boolean allowed = decision != null && decision.optBoolean("allowed", false);
        String itemTitle = item != null ? item.optString("title", isArabic ? "غير معروف" : "Unknown") : (isArabic ? "غير معروف" : "Unknown");
        String karat = item != null ? item.optString("karat", "") : "";
        String weight = item != null ? item.optString("weight", "") : "";
        String status = item != null ? item.optString("status", "") : "";

        if (item != null && allowed) authorizedCount++;
        else if (item != null && !allowed) deniedCount++;
        else unknownCount++;

        updateCounters();

        if (tvRadarItemStatus != null) {
            if (allowed && item != null) {
                tvRadarItemStatus.setText(isArabic ? "✅ خروج مصرح" : "✅ Authorized");
                tvRadarItemStatus.setTextColor(0xFF4CAF50);
            } else if (!allowed && item != null) {
                tvRadarItemStatus.setText(isArabic ? "🚨 غير مصرح بالخروج" : "🚨 Unauthorized");
                tvRadarItemStatus.setTextColor(0xFFF44336);
            } else {
                tvRadarItemStatus.setText(isArabic ? "⚠️ قطعة غير معروفة" : "⚠️ Unknown Item");
                tvRadarItemStatus.setTextColor(0xFFFF9800);
            }
        }

        if (tvRadarItemName != null) tvRadarItemName.setText("📦 " + itemTitle);

        if (tvRadarItemDetails != null) {
            StringBuilder details = new StringBuilder("🏷️ ").append(epc.substring(0, Math.min(16, epc.length())));
            if (!karat.isEmpty()) details.append(" | 💎 ").append(karat);
            if (!weight.isEmpty()) details.append(" | ⚖️ ").append(weight).append("g");
            if (!status.isEmpty()) details.append(" | 📋 ").append(status);
            tvRadarItemDetails.setText(details.toString());
        }
    }

    private void updateCounters() {
        if (tvAuthorizedCount != null) tvAuthorizedCount.setText(String.valueOf(authorizedCount));
        if (tvDeniedCount != null) tvDeniedCount.setText(String.valueOf(deniedCount));
        if (tvUnknownCount != null) tvUnknownCount.setText(String.valueOf(unknownCount));
    }

    private void addToRecentScans(String epc, String status) {
        String icon = "AUTHORIZED".equals(status) ? "✅" : "DENIED".equals(status) ? "🚨" : "⚠️";
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String shortEpc = epc.length() > 14 ? epc.substring(0, 14) + "..." : epc;
        recentScans.add(0, icon + " " + shortEpc + " | " + time);
        if (recentScans.size() > 5) recentScans.remove(5);

        if (tvRecentScans != null) {
            StringBuilder sb = new StringBuilder();
            for (String s : recentScans) sb.append(s).append("\n");
            tvRecentScans.setText(sb.toString());
        }
    }

    private void animateDetection(boolean isAuthorized) {
        if (radarView == null) return;
        int color = isAuthorized ? 0xFF4CAF50 : 0xFFF44336;
        ObjectAnimator.ofFloat(radarView, "alpha", 1f, 0.4f, 1f).setDuration(300).start();
        radarView.setBorderColor(color);
    }

    private void updateRssiBar() {
        if (rssiBar != null) {
            rssiBar.setProgress(random.nextInt(40) + 60);
        }
    }

    // ==================== Export ====================

    private void exportScanResults() {
        if (recentScans.isEmpty()) {
            Toast.makeText(mContext, isArabic ? "لا توجد نتائج للتصدير" : "No results to export", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder csv = new StringBuilder("Status,EPC,Time\n");
        for (String scan : recentScans) {
            csv.append(scan.replace("✅ ", "AUTHORIZED,").replace("🚨 ", "DENIED,").replace("⚠️ ", "UNKNOWN,").replace(" | ", ",")).append("\n");
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_SUBJECT, "MIRA Radar Scan Results - " + branchesManager.getActiveBranchDisplayName());
        share.putExtra(Intent.EXTRA_TEXT, csv.toString());
        startActivity(Intent.createChooser(share, isArabic ? "تصدير نتائج المسح" : "Export Scan Results"));
    }

    // ==================== Key Down ====================

    @Override
    public void myOnKeyDwon() {
        if (inventoryFlag) stopLocated();
        else startLocated();
    }

    // ==================== Lifecycle ====================

    @Override
    public void onResume() {
        super.onResume();
        simulationMode = settingsManager.getBoolean("radar_simulation", true);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocated();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (settingsManager != null) settingsManager.unregisterListener("radar_fragment");
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.detach(this).attach(this).commit();
        getView().post(this::restoreState);
    }
}
