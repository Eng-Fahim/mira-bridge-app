package com.example.uhf.fragment;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
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
import androidx.appcompat.app.AlertDialog;
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
import com.google.android.material.button.MaterialButton;
import com.rscja.deviceapi.entity.RadarLocationEntity;
import com.rscja.deviceapi.interfaces.IUHF;
import com.rscja.deviceapi.interfaces.IUHFRadarLocationCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;

/**
 * MIRA Proximity & Radar Location Fragment - المكتشف الذكي للقطع المفقودة
 */
public class MiraUHFRadarLocationFragment extends KeyDwonFragment {

    private static final String TAG = "MiraRadarLocation";
    private UHFMainActivity mContext;

    // ==================== UI Elements ====================
    private RadarView radarView;
    private EditText etEPC;
    private Button btStart, btStop, btnExportResults, btnFetchMissing;
    private MaterialButton btnMarkFound;
    private CircleSeekBar seekBarPower;
    private ProgressBar rssiBar;
    private TextView tvProximityLevel, tvSignalStrength;
    private LinearLayout layoutMiraRadarInfo;
    private TextView tvRadarItemStatus, tvRadarItemName, tvRadarItemDetails;
    private TextView tvAuthorizedCount, tvDeniedCount, tvUnknownCount;
    private TextView tvRecentScans, tvRadarMode;
    private Button btnModeSingle, btnModeOpen, btnModeGate;

    // ==================== State & Tracking ====================
    private boolean inventoryFlag = false;
    private String targetEpc = "";
    private int progress = 5;
    private int currentRssi = 0;
    private int authorizedCount = 0, deniedCount = 0, unknownCount = 0;
    private final List<String> recentScans = new ArrayList<>();
    private final Random random = new Random();

    // ==================== Sound & Geiger Feedback ====================
    private Vibrator vibrator;
    private long lastBeepTime = 0;

    // ==================== Managers ====================
    private MiraSettingsManager settingsManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean simulationMode;
    private boolean isArabic;

    // ==================== Modes ====================
    private enum RadarMode { SINGLE_TARGET, OPEN_SCAN, SECURITY_GATE, MISSING_SEARCH }
    private RadarMode currentMode = RadarMode.SINGLE_TARGET;

    // ==================== Simulation ====================
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private float simulationAngle = 0;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_uhf_radar_location, container, false);
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        if (mContext == null) return;
        mContext.currentFragment = this;

        if (getContext() != null) {
            vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        }

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
        btnFetchMissing = v.findViewById(R.id.btnFetchMissing);
        btnMarkFound = v.findViewById(R.id.btnMarkFound);

        tvProximityLevel = v.findViewById(R.id.tvProximityLevel);
        tvSignalStrength = v.findViewById(R.id.tvSignalStrength);

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
        if (btStart != null) btStart.setText(isArabic ? "▶ بدء التتبع Geiger" : "▶ Start Geiger Track");
        if (btStop != null) btStop.setText(isArabic ? "⏹ إيقاف" : "⏹ Stop");
        if (etEPC != null) etEPC.setHint(isArabic ? "أدخل EPC أو اختر قطعة مفقودة" : "Enter EPC or select missing");
        if (btnExportResults != null) btnExportResults.setText(isArabic ? "📤 تصدير" : "📤 Export");
        if (btnModeSingle != null) btnModeSingle.setText(isArabic ? "🎯 محدد" : "🎯 Target");
        if (btnModeOpen != null) btnModeOpen.setText(isArabic ? "📡 مسح مفتوح" : "📡 Open");
        if (btnModeGate != null) btnModeGate.setText(isArabic ? "🔍 مفقودات" : "🔍 Missing");
    }

    private void setupListeners() {
        btStart.setOnClickListener(v -> startLocated());
        btStop.setOnClickListener(v -> stopLocated());

        if (btnExportResults != null) btnExportResults.setOnClickListener(v -> exportScanResults());
        if (btnFetchMissing != null) btnFetchMissing.setOnClickListener(v -> showMissingItemsDialog());
        if (btnMarkFound != null) btnMarkFound.setOnClickListener(v -> markCurrentItemAsFound());

        if (btnModeSingle != null) btnModeSingle.setOnClickListener(v -> switchMode(RadarMode.SINGLE_TARGET));
        if (btnModeOpen != null) btnModeOpen.setOnClickListener(v -> switchMode(RadarMode.OPEN_SCAN));
        if (btnModeGate != null) btnModeGate.setOnClickListener(v -> switchMode(RadarMode.MISSING_SEARCH));

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

    // ==================== Missing Items Dialog ====================

    private void showMissingItemsDialog() {
        if (getContext() == null) return;

        AlertDialog loading = new AlertDialog.Builder(requireContext())
                .setMessage(isArabic ? "جاري جلب القطع المفقودة من السيرفر..." : "Fetching missing items...")
                .setCancelable(false)
                .show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                JSONObject response = client.getReportData("audit", "", "", branchesManager.getActiveBranchId());

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        loading.dismiss();
                        if (response != null && response.has("audit_summary")) {
                            parseAndSelectMissingItem(response);
                        } else {
                            Toast.makeText(getContext(), isArabic ? "لا توجد قطع مفقودة مسجلة في هذا الفرع" : "No missing items recorded", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        loading.dismiss();
                        Toast.makeText(getContext(), isArabic ? "خطأ في الاتصال بالخادم" : "Server Connection Failed", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void parseAndSelectMissingItem(JSONObject data) {
        JSONArray missingArray = data.optJSONArray("missing_details");
        List<String> displayList = new ArrayList<>();
        List<String> epcList = new ArrayList<>();

        if (missingArray != null && missingArray.length() > 0) {
            for (int i = 0; i < missingArray.length(); i++) {
                JSONObject obj = missingArray.optJSONObject(i);
                if (obj != null) {
                    String epc = obj.optString("epc", "");
                    String title = obj.optString("title", "قطعة مفقودة");
                    String weight = obj.optString("weight", "");
                    displayList.add("💎 " + title + " (" + weight + "g)\n🏷️ " + epc);
                    epcList.add(epc);
                }
            }
        } else {
            // بيانات افتراضية لأغراض الاختبار والعرض
            displayList.add("💎 خاتم سوليتير ذهب أبيض (4.2g)\n🏷️ E200001982130124");
            epcList.add("E200001982130124");
            displayList.add("💎 أسورة عيار 21 ممتازة (12.5g)\n🏷️ E200001982130125");
            epcList.add("E200001982130125");
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "اختر القطعة المفقودة للبحث عنها" : "Select Missing Item to Search")
                .setItems(displayList.toArray(new String[0]), (dialog, which) -> {
                    String selectedEpc = epcList.get(which);
                    etEPC.setText(selectedEpc);
                    targetEpc = selectedEpc;
                    switchMode(RadarMode.SINGLE_TARGET);
                    startLocated();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    // ==================== Modes ====================

    private void switchMode(RadarMode mode) {
        currentMode = mode;
        if (inventoryFlag) { stopLocated(); startLocated(); }
        updateModeUI();
        Toast.makeText(mContext, getModeName(mode), Toast.LENGTH_SHORT).show();
    }

    private String getModeName(RadarMode mode) {
        switch (mode) {
            case SINGLE_TARGET: return isArabic ? "🎯 تتبع قطعة محددة" : "🎯 Single Target Geiger";
            case OPEN_SCAN: return isArabic ? "📡 مسح مفتوح" : "📡 Open Radar Scan";
            case MISSING_SEARCH: return isArabic ? "🔍 كاشف المفقودات" : "🔍 Missing Items Scanner";
            default: return "";
        }
    }

    private void updateModeUI() {
        if (tvRadarMode != null) tvRadarMode.setText(getModeName(currentMode));
        if (btnModeSingle != null) btnModeSingle.setAlpha(currentMode == RadarMode.SINGLE_TARGET ? 1f : 0.5f);
        if (btnModeOpen != null) btnModeOpen.setAlpha(currentMode == RadarMode.OPEN_SCAN ? 1f : 0.5f);
        if (btnModeGate != null) btnModeGate.setAlpha(currentMode == RadarMode.MISSING_SEARCH ? 1f : 0.5f);
    }

    // ==================== Start / Stop ====================

    @SuppressLint("LongLogTag")
    private void startLocated() {
        if (inventoryFlag) return;

        radarView.clearPanel();
        targetEpc = etEPC.getText().toString().trim();

        authorizedCount = 0; deniedCount = 0; unknownCount = 0;
        recentScans.clear();
        updateCounters();

        if (tvRecentScans != null) tvRecentScans.setText("");
        if (layoutMiraRadarInfo != null) layoutMiraRadarInfo.setVisibility(View.GONE);
        if (btnMarkFound != null) btnMarkFound.setVisibility(View.GONE);

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
                        int val = entity.getValue(); // قيمة القرب والـ RSSI
                        
                        if (!TextUtils.isEmpty(detectedEpc)) {
                            handleGeigerAudioFeedback(val);
                            updateProximityUI(val);
                            queryMiraItem(detectedEpc, val);
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
                queryMiraItem(targetEpc, 70);
                Toast.makeText(mContext, isArabic ? "⚠️ الرادار غير مدعوم - تم العرض من MIRA API" : "⚠️ Radar unsupported - MIRA fallback", Toast.LENGTH_LONG).show();
            } else {
                UIHelper.ToastMessage(mContext, isArabic ? "فشل البدء - تحقق من الـ EPC" : "Start failed - Check EPC");
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
            }
        }

        radarView.stopRadar();
        inventoryFlag = false;
        btStart.setEnabled(true);
        etEPC.setEnabled(true);
        seekBarPower.setOnSeekBarChangeListener(null);
        seekBarPower.setProgress(5);
        seekBarPower.setEnabled(false);
        resetProximityUI();
    }

    // ==================== Simulation Geiger ====================

    private void startSimulatedRadar() {
        if (!TextUtils.isEmpty(targetEpc)) queryMiraItem(targetEpc, 85);

        radarView.startRadar();
        seekBarPower.setEnabled(true);
        inventoryFlag = true;
        btStart.setEnabled(false);
        etEPC.setEnabled(false);

        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!inventoryFlag) return;

                simulationAngle += 10;
                if (simulationAngle >= 360) simulationAngle = 0;

                // محاكاة اقتراب تدريجي
                currentRssi = Math.min(100, currentRssi + random.nextInt(15) - 5);
                if (currentRssi < 20) currentRssi = 30;

                updateProximityUI(currentRssi);
                handleGeigerAudioFeedback(currentRssi);

                List<RadarLocationEntity> simulatedList = new ArrayList<>();
                radarView.bindingData(simulatedList, targetEpc);
                radarView.setRotation(-simulationAngle);

                simulationHandler.postDelayed(this, Math.max(150, 800 - (currentRssi * 6)));
            }
        };
        simulationHandler.post(simulationRunnable);
        Toast.makeText(mContext, isArabic ? "⚡ وضع محاكاة الكاشف Geiger" : "⚡ Simulation Geiger Active", Toast.LENGTH_SHORT).show();
    }

    // ==================== Geiger Feedback & UI ====================

    private void handleGeigerAudioFeedback(int rssi) {
        long now = System.currentTimeMillis();
        long interval = Math.max(100, 1000 - (rssi * 9)); // كلما زادت الإشارة قل الفارق الزمني

        if (now - lastBeepTime > interval) {
            lastBeepTime = now;
            if (mContext != null && settingsManager.getBoolean("sound_on_scan", true)) {
                mContext.playSound(1);
            }

            if (rssi > 80 && vibrator != null) {
                vibrator.vibrate(50);
            }
        }
    }

    private void updateProximityUI(int rssi) {
        this.currentRssi = rssi;
        if (rssiBar != null) rssiBar.setProgress(rssi);
        if (tvSignalStrength != null) tvSignalStrength.setText(rssi + "%");

        if (tvProximityLevel != null) {
            if (rssi > 80) {
                tvProximityLevel.setText(isArabic ? "🔥 ملاصق جداً للقطعة!" : "🔥 Extremely Close!");
                tvProximityLevel.setTextColor(0xFF22C55E);
                if (btnMarkFound != null) btnMarkFound.setVisibility(View.VISIBLE);
            } else if (rssi > 50) {
                tvProximityLevel.setText(isArabic ? "📍 النطاق القريب" : "📍 Near Range");
                tvProximityLevel.setTextColor(0xFFF59E0B);
            } else {
                tvProximityLevel.setText(isArabic ? "🌐 إشارة ضعيفة / بعيد" : "🌐 Far / Weak Signal");
                tvProximityLevel.setTextColor(0xFFEF4444);
            }
        }
    }

    private void resetProximityUI() {
        if (rssiBar != null) rssiBar.setProgress(0);
        if (tvSignalStrength != null) tvSignalStrength.setText("0%");
        if (tvProximityLevel != null) tvProximityLevel.setText(isArabic ? "متوقف" : "Stopped");
    }

    // ==================== MIRA API Query ====================

    private void queryMiraItem(String epc, int rssi) {
        if (!settingsManager.getBoolean("auto_query_mira", true)) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                JSONObject extraParams = new JSONObject();
                extraParams.put("branch_id", branchesManager.getActiveBranchId());

                MiraApiClient.ApiResponse response = client.authorize(epc, "radar_locate", extraParams);

                if (getActivity() != null && response != null && response.isSuccess && response.data != null) {
                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String status = allowed ? "AUTHORIZED" : "DENIED";

                    getActivity().runOnUiThread(() -> {
                        updateMiraRadarInfo(epc, decision, item);
                        addToRecentScans(epc, status);
                        animateDetection(allowed);
                    });
                } else {
                    getActivity().runOnUiThread(() -> {
                        unknownCount++;
                        updateCounters();
                        addToRecentScans(epc, "UNKNOWN");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "MIRA Query Exception: " + e.getMessage());
            }
        });
    }

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
                tvRadarItemStatus.setText(isArabic ? "✅ مصرحة بالوجود بالفرع" : "✅ Authorized in Branch");
                tvRadarItemStatus.setTextColor(0xFF22C55E);
            } else if (!allowed && item != null) {
                tvRadarItemStatus.setText(isArabic ? "🚨 تنبيه: قطعة غير مخصصة للفرع" : "🚨 Unauthorized Branch Item");
                tvRadarItemStatus.setTextColor(0xFFEF4444);
            } else {
                tvRadarItemStatus.setText(isArabic ? "⚠️ قطعة مفقودة / غير مسجلة" : "⚠️ Missing/Unregistered Item");
                tvRadarItemStatus.setTextColor(0xFFF59E0B);
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

    // ==================== Sync Found Status ====================

    private void markCurrentItemAsFound() {
        if (TextUtils.isEmpty(targetEpc)) return;

        Toast.makeText(mContext, isArabic ? "جاري تحديث حالة القطعة إلى (موجودة)..." : "Updating item status to FOUND...", Toast.LENGTH_SHORT).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                JSONObject params = new JSONObject();
                params.put("epc", targetEpc);
                params.put("status", "AVAILABLE");
                params.put("branch_id", branchesManager.getActiveBranchId());

                // إرسال تحديث للسيرفر
                MiraApiClient.ApiResponse res = client.authorize(targetEpc, "mark_found", params);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(mContext, isArabic ? "🎉 تم استعادة وتأكيد موقع القطعة بنجاح!" : "🎉 Item restored successfully!", Toast.LENGTH_LONG).show();
                        stopLocated();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to mark item as found", e);
            }
        });
    }

    // ==================== UI Controls ====================

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
        int color = isAuthorized ? 0xFF22C55E : 0xFFEF4444;
        ObjectAnimator.ofFloat(radarView, "alpha", 1f, 0.4f, 1f).setDuration(300).start();
        radarView.setBorderColor(color);
    }

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
        share.putExtra(Intent.EXTRA_SUBJECT, "MIRA Radar Geiger Export - " + branchesManager.getActiveBranchDisplayName());
        share.putExtra(Intent.EXTRA_TEXT, csv.toString());
        startActivity(Intent.createChooser(share, isArabic ? "تصدير نتائج التتبع" : "Export Tracking Results"));
    }

    @Override
    public void myOnKeyDwon() {
        if (inventoryFlag) stopLocated();
        else startLocated();
    }

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
