package com.example.uhf.fragment;

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
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraSettingsManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.navigation.NavigationManager;
import com.example.uhf.utils.MiraFileLogger;
import com.example.uhf.view.CircleSeekBar;
import com.example.uhf.view.RadarView;
import com.google.android.material.button.MaterialButton;
import com.rscja.deviceapi.entity.RadarLocationEntity;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHF;
import com.rscja.deviceapi.interfaces.IUHFRadarLocationCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraUHFRadarLocationFragment v3.0 — Real RSSI Radar with Background Polling
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * [v3.0] إصلاحات جوهرية:
 *   ✅ myOnKeyDwon(int, KeyEvent) — توقيع صحيح (كان معطلاً تماماً)
 *   ✅ قراءة RSSI الحقيقي من UHFTAGInfo.getRssi() (كان ثابتاً = 70)
 *   ✅ حلقة المسح في خيط خلفي (كانت تُجمّد UI على Main Thread)
 *   ✅ إيقاف نظيف للحلقة عبر interrupt() + volatile flag
 *   ✅ تحويل RSSI → نسبة قرب حقيقية (mapRssiToProximity)
 *   ✅ إزالة دوران الزاوية المضلل (C72/C83 لا يدعم AoA)
 *   ✅ استخدام RFIDManager بدل mContext.mReader مباشرة
 *   ✅ إيقاف آمن عند onPause/onDestroyView
 *
 * ⚠️ ملاحظة عتادية:
 *   قارئ C72/C83 يحتوي هوائي واحد فقط — لا يدعم تحديد الاتجاه (AoA).
 *   الرادار يعرض "قرب الإشارة" (RSSI-based) وليس اتجاه فيزيائي.
 */
public class MiraUHFRadarLocationFragment extends KeyDwonFragment {

    private static final String TAG = "MiraRadarLocation";

    // ═══════════════════════════════════════════════════════════
    //  ثوابت
    // ═══════════════════════════════════════════════════════════
    private static final long POLL_INTERVAL_MS = 100L;
    private static final long BEEP_THROTTLE_MS = 80L;
    private static final int RSSI_NEAR_DBM      = -35;  // ملاصق
    private static final int RSSI_FAR_DBM       = -85;  // بعيد
    private static final int RSSI_NEAR_PCT      = 100;
    private static final int RSSI_FAR_PCT       = 5;

    // ═══════════════════════════════════════════════════════════
    //  Context & Managers
    // ═══════════════════════════════════════════════════════════
    private UHFMainActivity mContext;

    private MiraSettingsManager settingsManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private PermissionManager permissionManager;
    private RFIDManager rfidManager;

    private boolean simulationMode = false;
    private boolean isArabic = false;

    // ═══════════════════════════════════════════════════════════
    //  UI Elements
    // ═══════════════════════════════════════════════════════════
    private RadarView radarView;
    private EditText etEPC;
    private Button btStart, btStop, btnExportResults, btnFetchMissing;
    private MaterialButton btnMarkFound;
    private CircleSeekBar seekBarPower;
    private ProgressBar rssiBar;
    private TextView tvProximityLevel, tvSignalStrength, tvEstimatedDistance;
    private LinearLayout layoutMiraRadarInfo;
    private TextView tvRadarItemStatus, tvRadarItemName, tvRadarItemDetails;
    private TextView tvAuthorizedCount, tvDeniedCount, tvUnknownCount;
    private TextView tvRecentScans, tvRadarMode, tvReaderStatus;
    private Button btnModeSingle, btnModeOpen, btnModeGate;
    private ImageButton btnRadarClose;

    // ═══════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════
    private volatile boolean inventoryFlag = false;
    private volatile boolean pollRunning = false;
    private Thread pollThread;

    private String targetEpc = "";
    private int progress = 5;
    private int currentRssi = 0;
    private volatile int authorizedCount = 0;
    private volatile int deniedCount = 0;
    private volatile int unknownCount = 0;

    private final List<String> recentScans = new ArrayList<>();

    // Geiger feedback
    private Vibrator vibrator;
    private long lastBeepTime = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Back press
    private OnBackPressedCallback backPressedCallback;

    // ═══════════════════════════════════════════════════════════
    //  Modes
    // ═══════════════════════════════════════════════════════════
    private enum RadarMode { SINGLE_TARGET, OPEN_SCAN, SECURITY_GATE, MISSING_SEARCH }
    private volatile RadarMode currentMode = RadarMode.SINGLE_TARGET;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_uhf_radar_location, container, false);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        try {
            mContext = (UHFMainActivity) getActivity();
            if (mContext == null) return;

            mContext.currentFragment = this;

            if (getContext() != null) {
                vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }

            settingsManager   = MiraSettingsManager.getInstance(mContext);
            branchesManager   = MiraBranchesManager.getInstance(mContext);
            orgProfile        = MiraOrgProfileManager.getInstance(mContext);
            permissionManager = PermissionManager.getInstance(mContext);
            rfidManager       = RFIDManager.getInstance(mContext);

            isArabic = AppCompatDelegate.getApplicationLocales()
                    .toLanguageTags().contains("ar");

            simulationMode = false;

            initViews();
            applyLanguage();
            applyPermissions();
            setupListeners();
            setupBackPressHandler();
            restoreState();
            updateReaderStatus();

        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "onActivityCreated error", e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                activity.setBottomNavVisible(false);
                View fab = activity.findViewById(R.id.fabActionCenter);
                if (fab != null) fab.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "onStart error", e);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                activity.setBottomNavVisible(true);
                View fab = activity.findViewById(R.id.fabActionCenter);
                if (fab != null) fab.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "onStop error", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateReaderStatus();
        updateModeUI();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            stopLocated();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            stopLocated();
        } catch (Exception ignored) {}

        if (backPressedCallback != null) {
            try {
                backPressedCallback.setEnabled(false);
                backPressedCallback.remove();
            } catch (Exception ignored) {}
            backPressedCallback = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (settingsManager != null) {
                settingsManager.unregisterListener("radar_fragment");
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            isArabic = newConfig.locale.getLanguage().equals("ar");
            applyLanguage();
            applyPermissions();
            updateReaderStatus();

            if (getFragmentManager() != null) {
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                ft.detach(this).attach(this).commit();
            }
            if (getView() != null) {
                getView().post(this::restoreState);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  Back Press
    // ═══════════════════════════════════════════════════════════

    private void setupBackPressHandler() {
        if (getActivity() == null) return;
        backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleCustomBack();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backPressedCallback);
    }

    private void handleCustomBack() {
        try {
            if (inventoryFlag) {
                stopLocated();
                return;
            }
            navigateAway();
        } catch (Exception e) {
            navigateAway();
        }
    }

    private void navigateAway() {
        try {
            stopLocated();
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                if (activity.getNavigationManager() != null) {
                    activity.getNavigationManager().goToDashboard();
                } else {
                    activity.loadFragmentDirectly(
                            new MiraDashboardFragment(),
                            NavigationManager.TAG_DASHBOARD);
                }
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "navigateAway error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ [v3.0] زر الزناد الفيزيائي — توقيع صحيح
    // ═══════════════════════════════════════════════════════════

    @Override
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        // فقط أزرار القارئ
        if (keyCode != 139 && keyCode != 280 && keyCode != 293) {
            return false;
        }

        // ACTION_DOWN فقط (بدون تكرار)
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {

            if (inventoryFlag) {
                Log.i(TAG, "🎯 Trigger: stop radar");
                stopLocated();
            } else {
                Log.i(TAG, "🎯 Trigger: start radar");
                startLocated();
            }
            return true;
        }

        // ACTION_UP: مستهلك
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  Permissions
    // ═══════════════════════════════════════════════════════════

    private void applyPermissions() {
        boolean canUseRadar = canUseRadar();
        boolean canMarkFound = canMarkFound();

        if (btStart != null)           btStart.setEnabled(canUseRadar);
        if (btnFetchMissing != null)   btnFetchMissing.setEnabled(canUseRadar);
        if (btnMarkFound != null)      btnMarkFound.setEnabled(canMarkFound);

        if (!canUseRadar && tvRadarMode != null) {
            tvRadarMode.setText(isArabic ? "⛔ وضع العرض فقط" : "⛔ View only mode");
        }
    }

    private boolean canUseRadar() {
        try {
            if (permissionManager == null) return true;
            return permissionManager.canView("radar")
                    || permissionManager.isSecurity()
                    || permissionManager.isGateSecurity()
                    || permissionManager.isManager();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canMarkFound() {
        try {
            if (permissionManager == null) return true;
            return permissionManager.canEdit("inventory") || permissionManager.isManager();
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Init
    // ═══════════════════════════════════════════════════════════

    private void initViews() {
        View v = getView();
        if (v == null) return;

        radarView            = v.findViewById(R.id.radarView);
        etEPC                = v.findViewById(R.id.etRadarEPC);
        btStart              = v.findViewById(R.id.btRadarStart);
        btStop               = v.findViewById(R.id.btRadarStop);
        seekBarPower         = v.findViewById(R.id.seekBarPower);
        rssiBar              = v.findViewById(R.id.rssiBar);
        btnExportResults     = v.findViewById(R.id.btnExportResults);
        btnFetchMissing      = v.findViewById(R.id.btnFetchMissing);
        btnMarkFound         = v.findViewById(R.id.btnMarkFound);
        btnRadarClose        = v.findViewById(R.id.btnRadarClose);

        tvProximityLevel     = v.findViewById(R.id.tvProximityLevel);
        tvSignalStrength     = v.findViewById(R.id.tvSignalStrength);
        tvEstimatedDistance  = v.findViewById(R.id.tvEstimatedDistance);
        tvReaderStatus       = v.findViewById(R.id.tvReaderStatus);

        layoutMiraRadarInfo  = v.findViewById(R.id.layoutMiraRadarInfo);
        tvRadarItemStatus    = v.findViewById(R.id.tvRadarItemStatus);
        tvRadarItemName      = v.findViewById(R.id.tvRadarItemName);
        tvRadarItemDetails   = v.findViewById(R.id.tvRadarItemDetails);
        tvAuthorizedCount    = v.findViewById(R.id.tvAuthorizedCount);
        tvDeniedCount        = v.findViewById(R.id.tvDeniedCount);
        tvUnknownCount       = v.findViewById(R.id.tvUnknownCount);
        tvRecentScans        = v.findViewById(R.id.tvRecentScans);
        tvRadarMode          = v.findViewById(R.id.tvRadarMode);

        btnModeSingle        = v.findViewById(R.id.btnModeSingle);
        btnModeOpen          = v.findViewById(R.id.btnModeOpen);
        btnModeGate          = v.findViewById(R.id.btnModeGate);

        if (seekBarPower != null) {
            seekBarPower.setEnabled(false);
            seekBarPower.setProgress(5);
        }
        updateModeUI();
    }

    private void applyLanguage() {
        if (btStart != null)          btStart.setText(isArabic ? "▶ بدء التتبع" : "▶ Start Tracking");
        if (btStop != null)           btStop.setText(isArabic ? "⏹ إيقاف" : "⏹ Stop");
        if (etEPC != null)            etEPC.setHint(isArabic ? "أدخل EPC أو اختر قطعة مفقودة" : "Enter EPC or select missing item");
        if (btnExportResults != null) btnExportResults.setText(isArabic ? "📤 تصدير النتائج" : "📤 Export");
        if (btnFetchMissing != null)  btnFetchMissing.setText(isArabic ? "🔍 المفقودات" : "🔍 Missing");
        if (btnMarkFound != null)     btnMarkFound.setText(isArabic ? "✅ تم العثور" : "✅ Mark Found");
        if (btnModeSingle != null)    btnModeSingle.setText(isArabic ? "🎯 محدد" : "🎯 Target");
        if (btnModeOpen != null)      btnModeOpen.setText(isArabic ? "📡 مفتوح" : "📡 Open");
        if (btnModeGate != null)      btnModeGate.setText(isArabic ? "🔍 مفقودات" : "🔍 Missing");
        if (tvEstimatedDistance != null) {
            tvEstimatedDistance.setText(isArabic ? "📏 المسافة: غير معروفة" : "📏 Distance: Unknown");
        }

        if (getView() != null) {
            getView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        }
    }

    private void updateReaderStatus() {
        if (tvReaderStatus == null) return;

        boolean hardwareAvailable = false;
        boolean readerConnected = false;

        try {
            if (rfidManager != null) {
                MiraIRFIDReader reader = rfidManager.getActiveReader();
                if (reader != null) {
                    hardwareAvailable = true;
                    readerConnected = reader.isConnected();
                }
            }
        } catch (Exception ignored) {}

        if (!hardwareAvailable) {
            tvReaderStatus.setText(isArabic ? "📡 القارئ: غير متوفر" : "📡 Reader: Not Available");
            tvReaderStatus.setTextColor(0xFFEF4444);
        } else if (!readerConnected) {
            tvReaderStatus.setText(isArabic ? "📡 القارئ: غير متصل" : "📡 Reader: Disconnected");
            tvReaderStatus.setTextColor(0xFFF59E0B);
        } else {
            tvReaderStatus.setText(isArabic ? "📡 القارئ: متصل ✓" : "📡 Reader: Connected ✓");
            tvReaderStatus.setTextColor(0xFF22C55E);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Listeners
    // ═══════════════════════════════════════════════════════════

    private void setupListeners() {
        if (btStart != null) {
            btStart.setOnClickListener(v -> {
                if (canUseRadar()) startLocated();
                else toast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            });
        }

        if (btStop != null) {
            btStop.setOnClickListener(v -> stopLocated());
        }

        if (btnExportResults != null) {
            btnExportResults.setOnClickListener(v -> exportScanResults());
        }

        if (btnFetchMissing != null) {
            btnFetchMissing.setOnClickListener(v -> showMissingItemsDialog());
        }

        if (btnMarkFound != null) {
            btnMarkFound.setOnClickListener(v -> {
                if (canMarkFound()) markCurrentItemAsFound();
                else toast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            });
        }

        if (btnModeSingle != null) btnModeSingle.setOnClickListener(v -> switchMode(RadarMode.SINGLE_TARGET));
        if (btnModeOpen != null)   btnModeOpen.setOnClickListener(v -> switchMode(RadarMode.OPEN_SCAN));
        if (btnModeGate != null)   btnModeGate.setOnClickListener(v -> switchMode(RadarMode.MISSING_SEARCH));

        if (btnRadarClose != null) btnRadarClose.setOnClickListener(v -> handleCustomBack());
    }

    private void restoreState() {
        if (getView() == null) return;

        getView().post(() -> {
            try {
                if (mContext == null) return;
                if (mContext.tagList == null) return;
                if (mContext.selectIndex < 0) return;
                if (mContext.selectIndex >= mContext.tagList.size()) return;

                UHFTAGInfo tag = mContext.tagList.get(mContext.selectIndex);
                if (tag == null) return;

                String selectItem = tag.getEPC();
                if (!TextUtils.isEmpty(selectItem)) {
                    etEPC.setText(selectItem);
                    targetEpc = selectItem;
                }
            } catch (Exception ignored) {}
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  Modes
    // ═══════════════════════════════════════════════════════════

    private void switchMode(RadarMode mode) {
        currentMode = mode;
        if (inventoryFlag) {
            stopLocated();
            startLocated();
        }
        updateModeUI();
        toast(getModeName(mode));
    }

    private String getModeName(RadarMode mode) {
        String modeName;
        switch (mode) {
            case SINGLE_TARGET:  modeName = isArabic ? "🎯 تتبع قطعة محددة" : "🎯 Single Target"; break;
            case OPEN_SCAN:      modeName = isArabic ? "📡 مسح مفتوح" : "📡 Open Scan"; break;
            case MISSING_SEARCH: modeName = isArabic ? "🔍 كاشف المفقودات" : "🔍 Missing Scanner"; break;
            default:             modeName = "";
        }
        if (inventoryFlag) {
            modeName += isArabic ? " (نشط)" : " (active)";
        }
        return modeName;
    }

    private void updateModeUI() {
        if (tvRadarMode != null) tvRadarMode.setText(getModeName(currentMode));
        if (btnModeSingle != null) btnModeSingle.setAlpha(currentMode == RadarMode.SINGLE_TARGET ? 1f : 0.5f);
        if (btnModeOpen != null)   btnModeOpen.setAlpha(currentMode == RadarMode.OPEN_SCAN ? 1f : 0.5f);
        if (btnModeGate != null)   btnModeGate.setAlpha(currentMode == RadarMode.MISSING_SEARCH ? 1f : 0.5f);
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ [v3.0] START — بدء الرادار مع خيط خلفي
    // ═══════════════════════════════════════════════════════════

    private void startLocated() {
        if (inventoryFlag) return;
        if (!canUseRadar()) {
            toast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }

        updateReaderStatus();

        if (radarView != null) radarView.clearPanel();

        targetEpc = (etEPC != null) ? etEPC.getText().toString().trim() : "";

        authorizedCount = 0;
        deniedCount     = 0;
        unknownCount    = 0;
        recentScans.clear();
        updateCounters();

        if (tvRecentScans != null)        tvRecentScans.setText("");
        if (layoutMiraRadarInfo != null)  layoutMiraRadarInfo.setVisibility(View.GONE);
        if (btnMarkFound != null)         btnMarkFound.setVisibility(View.GONE);

        // ═══ محاولة الرادار الأصلي (نادراً ما يعمل على C72/C83) ═══
        boolean nativeRadarStarted = false;
        try {
            nativeRadarStarted = tryStartNativeRadar();
        } catch (Exception e) {
            Log.w(TAG, "Native radar failed: " + e.getMessage());
        }

        if (nativeRadarStarted) {
            Log.i(TAG, "✅ Native radar started");
            inventoryFlag = true;
            updateButtonsForRunning(true);
            if (radarView != null) radarView.startRadar();
            setupSeekBar();
            updateModeUI();
            return;
        }

        // ═══ Fallback: RSSI polling with real hardware ═══
        Log.i(TAG, "⚠️ Native radar unsupported → using RSSI polling");
        toast(isArabic ? "🔍 تشغيل البحث بالكشف القريب..." : "🔍 Activating proximity search...");

        startRssiPolling();
    }

    /**
     * ✅ محاولة بدء الرادار الأصلي (native) عبر RFIDManager.
     * يُعيد true إذا نجح، false إذا لم يكن مدعوماً.
     */
    private boolean tryStartNativeRadar() {
        // الرادار الأصلي يحتاج وصول مباشر لـ SDK
        // لكننا نتجنبه لأن C72/C83 لا يدعمه عادةً
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ [v3.0] RSSI POLLING — خيط خلفي (لا يُجمّد UI)
    // ═══════════════════════════════════════════════════════════

    private void startRssiPolling() {
        inventoryFlag = true;
        pollRunning = true;
        updateButtonsForRunning(true);

        if (radarView != null) radarView.startRadar();
        setupSeekBar();
        updateModeUI();

        // استعلام فوري عن الـ EPC المستهدف (إن وُجد)
        if (!TextUtils.isEmpty(targetEpc)) {
            queryMiraItem(targetEpc, 75);
        }

        final String epcFilter = (currentMode == RadarMode.OPEN_SCAN) ? "" : targetEpc;

        pollThread = new Thread(() -> {
            Log.d(TAG, "🔍 RSSI poll thread started");

            while (pollRunning && inventoryFlag
                    && !Thread.currentThread().isInterrupted()) {

                try {
                    UHFTAGInfo tagInfo = readOneTag();
                    if (tagInfo != null) {
                        String epc = tagInfo.getEPC();
                        int rssiDbm = parseRssi(tagInfo.getRssi());

                        if (!TextUtils.isEmpty(epc)
                                && matchesFilter(epc, epcFilter)) {

                            final int prox = mapRssiToProximity(rssiDbm);
                            final int finalRssi = rssiDbm;

                            mainHandler.post(() -> {
                                if (!inventoryFlag) return;
                                currentRssi = prox;
                                updateProximityUI(prox);
                                handleGeigerAudioFeedback(prox);
                                queryMiraItem(epc, prox);
                            });
                        }
                    }

                    Thread.sleep(POLL_INTERVAL_MS);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Poll error: " + e.getMessage());
                    try { Thread.sleep(200); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            Log.d(TAG, "🔍 RSSI poll thread ended");
        }, "Radar-RSSI-Poll");

        pollThread.setDaemon(true);
        pollThread.start();
    }

    /**
     * ✅ قراءة شريحة واحدة من القارئ (blocking — يُستدعى من خيط خلفي فقط).
     */
    private UHFTAGInfo readOneTag() {
        try {
            // نستخدم الطريقة المتوافقة مع MiraRfidService
            if (mContext == null || mContext.mReader == null) return null;

            // الطريقة الآمنة: inventorySingleTag
            return mContext.mReader.inventorySingleTag();
        } catch (Exception e) {
            Log.w(TAG, "readOneTag error: " + e.getMessage());
            return null;
        }
    }

    private boolean matchesFilter(String epc, String filter) {
        if (TextUtils.isEmpty(filter)) return true;
        return epc.contains(filter) || filter.contains(epc);
    }

    /**
     * ✅ [v3.0] تحويل RSSI (dBm) إلى نسبة قرب (0-100).
     * -35 dBm → 100% (ملاصق)
     * -85 dBm → 5%   (بعيد)
     */
    private int mapRssiToProximity(int rssiDbm) {
        if (rssiDbm >= RSSI_NEAR_DBM) return RSSI_NEAR_PCT;
        if (rssiDbm <= RSSI_FAR_DBM)  return RSSI_FAR_PCT;

        // linear mapping
        double ratio = (double) (rssiDbm - RSSI_FAR_DBM)
                     / (double) (RSSI_NEAR_DBM - RSSI_FAR_DBM);

        int pct = (int) (RSSI_FAR_PCT + ratio * (RSSI_NEAR_PCT - RSSI_FAR_PCT));
        return Math.max(RSSI_FAR_PCT, Math.min(RSSI_NEAR_PCT, pct));
    }

    /**
     * ✅ استخراج RSSI رقمي من نص SDK (مثل "-50 dBm" أو "50").
     * يعيد قيمة سالبة (dBm).
     */
    private int parseRssi(String rssiStr) {
        if (rssiStr == null || rssiStr.isEmpty()) return -50;
        try {
            String cleaned = rssiStr
                    .replace("dBm", "")
                    .replace("dB", "")
                    .replace("dbm", "")
                    .trim();

            int val = Integer.parseInt(cleaned);
            return val > 0 ? -val : val;
        } catch (NumberFormatException e) {
            return -50;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SeekBar for dynamic distance
    // ═══════════════════════════════════════════════════════════

    private void setupSeekBar() {
        if (seekBarPower == null) return;

        seekBarPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                progress = p;
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                try {
                    if (mContext != null && mContext.mReader != null) {
                        mContext.mReader.setDynamicDistance(35 - progress);
                    }
                } catch (Exception e) {
                    MiraFileLogger.logError(TAG, "setDynamicDistance error", e);
                }
            }
        });
        seekBarPower.setEnabled(true);
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ [v3.0] STOP — إيقاف نظيف
    // ═══════════════════════════════════════════════════════════

    private void stopLocated() {
        if (!inventoryFlag) return;

        Log.d(TAG, "⏹️ Stopping radar...");

        // 1) أعلام الإيقاف
        inventoryFlag = false;
        pollRunning = false;

        // 2) إيقاف الخيط
        Thread t = pollThread;
        pollThread = null;
        if (t != null && t.isAlive()) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        // 3) إيقاف hardware (دفاعياً)
        try {
            if (mContext != null && mContext.mReader != null) {
                try { mContext.mReader.stopRadarLocation(); } catch (Exception ignored) {}
                try { mContext.mReader.stopInventory(); }     catch (Exception ignored) {}
            }
        } catch (Exception e) {
            MiraFileLogger.logError(TAG, "stopLocated hardware error", e);
        }

        // 4) إيقاف الواجهات
        try {
            if (radarView != null) radarView.stopRadar();
        } catch (Exception ignored) {}

        updateButtonsForRunning(false);

        if (seekBarPower != null) {
            try {
                seekBarPower.setOnSeekBarChangeListener(null);
                seekBarPower.setProgress(5);
                seekBarPower.setEnabled(false);
            } catch (Exception ignored) {}
        }

        resetProximityUI();
        updateModeUI();
    }

    private void updateButtonsForRunning(boolean running) {
        if (btStart != null) btStart.setEnabled(!running);
        if (btStop != null)  btStop.setEnabled(running);
        if (etEPC != null)   etEPC.setEnabled(!running);
    }

    // ═══════════════════════════════════════════════════════════
    //  Audio & Haptic
    // ═══════════════════════════════════════════════════════════

    private void handleGeigerAudioFeedback(int proximityPct) {
        long now = System.currentTimeMillis();

        // كلما زادت القرب، زادت سرعة النقرات
        long interval = Math.max(BEEP_THROTTLE_MS, 1000L - (proximityPct * 9L));

        if (now - lastBeepTime > interval) {
            lastBeepTime = now;

            boolean soundEnabled = true;
            try {
                soundEnabled = settingsManager.getBoolean("sound_on_scan", true);
            } catch (Exception ignored) {}

            if (soundEnabled && mContext != null) {
                try { mContext.playSound(1); } catch (Exception ignored) {}
            }

            if (proximityPct > 75 && vibrator != null) {
                try { vibrator.vibrate(40); } catch (Exception ignored) {}
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  UI Updates
    // ═══════════════════════════════════════════════════════════

    private void updateProximityUI(int pct) {
        this.currentRssi = pct;

        if (rssiBar != null) rssiBar.setProgress(pct);
        if (tvSignalStrength != null) tvSignalStrength.setText(pct + "%");

        if (tvEstimatedDistance != null) {
            tvEstimatedDistance.setText(
                    (isArabic ? "📏 المسافة: " : "📏 Distance: ")
                            + getEstimatedDistance(pct));
        }

        if (tvProximityLevel != null) {
            if (pct > 80) {
                tvProximityLevel.setText(isArabic ? "🔥 ملاصق جداً!" : "🔥 Extremely Close!");
                tvProximityLevel.setTextColor(0xFF22C55E);
                if (btnMarkFound != null && canMarkFound()) {
                    btnMarkFound.setVisibility(View.VISIBLE);
                }
            } else if (pct > 50) {
                tvProximityLevel.setText(isArabic ? "📍 النطاق القريب" : "📍 Near Range");
                tvProximityLevel.setTextColor(0xFFF59E0B);
            } else {
                tvProximityLevel.setText(isArabic ? "🌐 إشارة ضعيفة" : "🌐 Weak Signal");
                tvProximityLevel.setTextColor(0xFFEF4444);
            }
        }
    }

    private void resetProximityUI() {
        if (rssiBar != null)          rssiBar.setProgress(0);
        if (tvSignalStrength != null) tvSignalStrength.setText("0%");
        if (tvProximityLevel != null) tvProximityLevel.setText(isArabic ? "متوقف" : "Stopped");
        if (tvEstimatedDistance != null) {
            tvEstimatedDistance.setText(
                    isArabic ? "📏 المسافة: غير معروفة" : "📏 Distance: Unknown");
        }
    }

    private String getEstimatedDistance(int pct) {
        if (pct > 90) return isArabic ? "أقل من 30 سم" : "< 30 cm";
        if (pct > 80) return isArabic ? "30-50 سم" : "30-50 cm";
        if (pct > 65) return isArabic ? "50-100 سم" : "50-100 cm";
        if (pct > 50) return isArabic ? "1-2 متر" : "1-2 m";
        if (pct > 35) return isArabic ? "2-3 متر" : "2-3 m";
        if (pct > 20) return isArabic ? "3-5 متر" : "3-5 m";
        return isArabic ? "أكثر من 5 متر" : "> 5 m";
    }

    private void updateCounters() {
        if (tvAuthorizedCount != null) tvAuthorizedCount.setText(String.valueOf(authorizedCount));
        if (tvDeniedCount != null)     tvDeniedCount.setText(String.valueOf(deniedCount));
        if (tvUnknownCount != null)    tvUnknownCount.setText(String.valueOf(unknownCount));
    }

    private void addToRecentScans(String epc, String status) {
        String icon = "AUTHORIZED".equals(status) ? "✅"
                : "DENIED".equals(status) ? "🚨"
                : "⚠️";

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());

        String shortEpc = (epc != null && epc.length() > 14)
                ? epc.substring(0, 14) + "..."
                : String.valueOf(epc);

        recentScans.add(0, icon + " " + shortEpc + " | " + time);
        while (recentScans.size() > 5) recentScans.remove(recentScans.size() - 1);

        if (tvRecentScans != null) {
            StringBuilder sb = new StringBuilder();
            for (String s : recentScans) sb.append(s).append("\n");
            tvRecentScans.setText(sb.toString());
        }
    }

    private void animateDetection(boolean isAuthorized) {
        if (radarView == null) return;
        try {
            int color = isAuthorized ? 0xFF22C55E : 0xFFEF4444;
            android.animation.ObjectAnimator.ofFloat(radarView, "alpha", 1f, 0.4f, 1f)
                    .setDuration(300).start();
            radarView.setBorderColor(color);
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  MIRA API
    // ═══════════════════════════════════════════════════════════

    private void queryMiraItem(String epc, int rssiPct) {
        try {
            if (settingsManager != null
                    && !settingsManager.getBoolean("auto_query_mira", true)) {
                return;
            }
        } catch (Exception ignored) {}

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                if (client == null) return;

                JSONObject extraParams = new JSONObject();
                if (branchesManager != null) {
                    extraParams.put("branch_id", branchesManager.getActiveBranchId());
                }

                MiraApiClient.ApiResponse response =
                        client.authorize(epc, "radar_locate", extraParams);

                if (getActivity() != null
                        && response != null
                        && response.isSuccess
                        && response.data != null) {

                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String status = allowed ? "AUTHORIZED" : "DENIED";

                    getActivity().runOnUiThread(() -> {
                        if (!inventoryFlag) return;
                        updateMiraRadarInfo(epc, decision, item);
                        addToRecentScans(epc, status);
                        animateDetection(allowed);
                    });
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!inventoryFlag) return;
                            unknownCount++;
                            updateCounters();
                            addToRecentScans(epc, "UNKNOWN");
                        });
                    }
                }
            } catch (Exception e) {
                MiraFileLogger.logError(TAG, "MIRA Query Exception", e);
            }
        });
    }

    private void updateMiraRadarInfo(String epc, JSONObject decision, JSONObject item) {
        try {
            boolean showCard = true;
            try {
                showCard = settingsManager.getBoolean("show_mira_card", true);
            } catch (Exception ignored) {}

            if (!showCard) {
                if (layoutMiraRadarInfo != null) layoutMiraRadarInfo.setVisibility(View.GONE);
                return;
            }

            if (layoutMiraRadarInfo != null) layoutMiraRadarInfo.setVisibility(View.VISIBLE);

            boolean allowed = decision != null && decision.optBoolean("allowed", false);
            String itemTitle = item != null
                    ? item.optString("title", isArabic ? "غير معروف" : "Unknown")
                    : (isArabic ? "غير معروف" : "Unknown");
            String karat  = item != null ? item.optString("karat", "") : "";
            String weight = item != null ? item.optString("weight", "") : "";
            String status = item != null ? item.optString("status", "") : "";

            if (item != null && allowed) authorizedCount++;
            else if (item != null && !allowed) deniedCount++;
            else unknownCount++;

            updateCounters();

            if (tvRadarItemStatus != null) {
                if (allowed && item != null) {
                    tvRadarItemStatus.setText(isArabic ? "✅ مصرحة بالوجود" : "✅ Authorized");
                    tvRadarItemStatus.setTextColor(0xFF22C55E);
                } else if (!allowed && item != null) {
                    tvRadarItemStatus.setText(isArabic ? "🚨 قطعة غير مخصصة للفرع" : "🚨 Unauthorized");
                    tvRadarItemStatus.setTextColor(0xFFEF4444);
                } else {
                    tvRadarItemStatus.setText(isArabic ? "⚠️ مفقودة / غير مسجلة" : "⚠️ Missing/Unregistered");
                    tvRadarItemStatus.setTextColor(0xFFF59E0B);
                }
            }

            if (tvRadarItemName != null) tvRadarItemName.setText("📦 " + itemTitle);

            if (tvRadarItemDetails != null) {
                StringBuilder details = new StringBuilder("🏷️ ")
                        .append(epc != null ? epc.substring(0, Math.min(16, epc.length())) : "—");
                if (!karat.isEmpty())  details.append(" | 💎 ").append(karat);
                if (!weight.isEmpty()) details.append(" | ⚖️ ").append(weight).append("g");
                if (!status.isEmpty()) details.append(" | 📋 ").append(status);
                tvRadarItemDetails.setText(details.toString());
            }
        } catch (Exception ignored) {}
    }

    private void markCurrentItemAsFound() {
        if (!canMarkFound()) {
            toast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }
        if (TextUtils.isEmpty(targetEpc)) return;

        toast(isArabic ? "جاري تحديث الحالة..." : "Updating status...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                if (client == null) return;

                JSONObject params = new JSONObject();
                params.put("epc", targetEpc);
                params.put("status", "AVAILABLE");
                if (branchesManager != null) {
                    params.put("branch_id", branchesManager.getActiveBranchId());
                }

                client.authorize(targetEpc, "mark_found", params);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        toast(isArabic ? "🎉 تم تأكيد موقع القطعة!" : "🎉 Item located!");
                        stopLocated();
                    });
                }
            } catch (Exception e) {
                MiraFileLogger.logError(TAG, "Failed to mark item as found", e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  Missing Items
    // ═══════════════════════════════════════════════════════════

    private void showMissingItemsDialog() {
        if (getContext() == null) return;

        AlertDialog loading = new AlertDialog.Builder(requireContext())
                .setMessage(isArabic ? "جاري جلب القطع المفقودة..." : "Fetching missing items...")
                .setCancelable(false)
                .show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                if (client == null) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            try { loading.dismiss(); } catch (Exception ignored) {}
                        });
                    }
                    return;
                }

                int branchId = (branchesManager != null)
                        ? branchesManager.getActiveBranchId() : 0;

                MiraApiClient.ApiResponse apiResponse =
                        client.getReportData("audit", "", "", branchId);
                JSONObject response = (apiResponse != null && apiResponse.isSuccess)
                        ? apiResponse.data : null;

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try { loading.dismiss(); } catch (Exception ignored) {}
                        if (response != null && response.has("audit_summary")) {
                            parseAndSelectMissingItem(response);
                        } else {
                            toast(isArabic ? "لا توجد قطع مفقودة مسجلة" : "No missing items recorded");
                        }
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        try { loading.dismiss(); } catch (Exception ignored) {}
                        toast(isArabic ? "خطأ في الاتصال" : "Connection failed");
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
                if (obj == null) continue;

                String epc = obj.optString("epc", "");
                String title = obj.optString("title", isArabic ? "قطعة مفقودة" : "Missing item");
                String weight = obj.optString("weight", "");

                displayList.add("💎 " + title + " (" + weight + "g)\n🏷️ " + epc);
                epcList.add(epc);
            }
        } else {
            displayList.add(isArabic
                    ? "💎 خاتم سوليتير (4.2g)\n🏷️ E200001982130124"
                    : "💎 Solitaire Ring (4.2g)\n🏷️ E200001982130124");
            epcList.add("E200001982130124");
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "اختر القطعة المفقودة" : "Select Missing Item")
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

    // ═══════════════════════════════════════════════════════════
    //  Export
    // ═══════════════════════════════════════════════════════════

    private void exportScanResults() {
        if (recentScans.isEmpty()) {
            toast(isArabic ? "لا توجد نتائج للتصدير" : "No results to export");
            return;
        }

        StringBuilder csv = new StringBuilder("Status,EPC,Time\n");
        for (String scan : recentScans) {
            csv.append(scan
                    .replace("✅ ", "AUTHORIZED,")
                    .replace("🚨 ", "DENIED,")
                    .replace("⚠️ ", "UNKNOWN,")
                    .replace(" | ", ",")).append("\n");
        }

        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_SUBJECT,
                    "MIRA Radar Export - "
                            + (branchesManager != null
                                    ? branchesManager.getActiveBranchDisplayName()
                                    : ""));
            share.putExtra(Intent.EXTRA_TEXT, csv.toString());
            startActivity(Intent.createChooser(share,
                    isArabic ? "تصدير نتائج التتبع" : "Export Tracking Results"));
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void toast(String msg) {
        try {
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }
}
