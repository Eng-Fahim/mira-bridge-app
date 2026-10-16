package com.example.uhf.fragment;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.view.MiraProximityMeterView;
import com.google.android.material.button.MaterialButton;
import com.rscja.deviceapi.interfaces.IUHF;
import com.rscja.deviceapi.interfaces.IUHFLocationCallback;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MiraUHFLocatorFragment v2.0
 *
 * ✨ الجديد في هذه النسخة:
 *   • تنعيم الإشارة (EMA Smoothing) — يقضي على القفزات المجنونة
 *   • اهتزاز متدرج ذكي (Patterned Vibrations)
 *   • الالتقاط التلقائي (Auto-Snap) عند ثبات الإشارة 2 ثانية
 *   • إحصائيات الجلسة (Best proximity, Time to find)
 *   • وميض احتفالي عند الالتقاط
 *   • تحسين الأداء والاستقرار
 */
public class MiraUHFLocatorFragment extends KeyDwonFragment {

    private static final String TAG = "MiraUHFLocator";

    // ═══ KeyCodes للزناد الفيزيائي ═══
    private static final int KEYCODE_TRIGGER_1 = 139;
    private static final int KEYCODE_TRIGGER_2 = 280;
    private static final int KEYCODE_TRIGGER_3 = 293;

    // ═══ Geiger Feedback ═══
    private static final long BEEP_MIN_INTERVAL = 80L;
    private static final long BEEP_MAX_INTERVAL = 800L;

    // ═══ Signal Smoothing (EMA) ═══
    private static final float SMOOTHING_FACTOR = 0.18f; // 0.1 = أنعم، 0.4 = أسرع استجابة

    // ═══ Auto-Snap ═══
    private static final float SNAP_THRESHOLD = 0.88f;  // عتبة الالتقاط
    private static final long SNAP_HOLD_MS = 2000L;     // مدة الثبات المطلوبة

    // ═══ State ═══
    private boolean isLocating = false;
    private boolean isArabic = false;
    private int currentValue = 100;

    // ═══ Signal Smoothing State ═══
    private float smoothedProximity = 0f;

    // ═══ Auto-Snap State ═══
    private long snapStartTime = 0;
    private boolean snapOffered = false;

    // ═══ Session Stats ═══
    private long sessionStartTime = 0;
    private int totalReadings = 0;
    private float bestProximity = 0f;
    private long timeToFindMs = 0;

    // ═══ References ═══
    private UHFMainActivity mainActivity;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;
    private Vibrator vibrator;
    private Handler mainHandler;
    private ExecutorService executor;

    // ═══ Views ═══
    private MiraProximityMeterView meterView;
    private EditText etEpc;
    private MaterialButton btnStart, btnStop, btnMiraSearch, btnExport, btnMarkFound;
    private ImageButton btnClose;
    private TextView tvStatus, tvDistance, tvReaderState;
    private TextView tvItemTitle, tvItemDetails, tvItemLocation, tvItemStatus;
    private LinearLayout layoutItemCard;
    private RecyclerView rvHistory;

    // ═══ Tracking ═══
    private long lastBeepTime = 0;
    private String currentEpc = "";
    private String currentTitle = "";
    private final List<HistoryEntry> history = new ArrayList<>();
    private HistoryAdapter historyAdapter;

    // ═══ Back press ═══
    private OnBackPressedCallback backCallback;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_mira_uhf_locator, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        super.onViewCreated(view, saved);

        try {
            mainActivity = (UHFMainActivity) getActivity();
            if (mainActivity == null) return;

            isArabic = AppCompatDelegate.getApplicationLocales()
                    .toLanguageTags().contains("ar");

            mainHandler = new Handler(Looper.getMainLooper());
            executor = Executors.newSingleThreadExecutor();
            vibrator = (Vibrator) requireContext()
                    .getSystemService(Context.VIBRATOR_SERVICE);

            permissionManager = PermissionManager.getInstance(requireContext());
            branchesManager = MiraBranchesManager.getInstance(requireContext());

            bindViews(view);
            setupHistory();
            setupListeners();
            setupBackPress();
            updateReaderStatus();
            applyLanguage();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateReaderStatus();
        if (mainActivity != null) {
            mainActivity.setActiveRfidListener(this::onTagScanned);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocating();
        if (mainActivity != null) {
            mainActivity.setActiveRfidListener(null);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopLocating();
        if (backCallback != null) {
            backCallback.remove();
            backCallback = null;
        }
        if (executor != null) executor.shutdownNow();
    }

    // ═══════════════════════════════════════════════════════════
    //  Setup
    // ═══════════════════════════════════════════════════════════

    private void bindViews(View v) {
        meterView = v.findViewById(R.id.meterView);
        etEpc = v.findViewById(R.id.etEpc);
        btnStart = v.findViewById(R.id.btnStart);
        btnStop = v.findViewById(R.id.btnStop);
        btnMiraSearch = v.findViewById(R.id.btnMiraSearch);
        btnExport = v.findViewById(R.id.btnExport);
        btnMarkFound = v.findViewById(R.id.btnMarkFound);
        btnClose = v.findViewById(R.id.btnClose);

        tvStatus = v.findViewById(R.id.tvStatus);
        tvDistance = v.findViewById(R.id.tvDistance);
        tvReaderState = v.findViewById(R.id.tvReaderState);

        tvItemTitle = v.findViewById(R.id.tvItemTitle);
        tvItemDetails = v.findViewById(R.id.tvItemDetails);
        tvItemLocation = v.findViewById(R.id.tvItemLocation);
        tvItemStatus = v.findViewById(R.id.tvItemStatus);
        layoutItemCard = v.findViewById(R.id.layoutItemCard);

        rvHistory = v.findViewById(R.id.rvHistory);
    }

    private void setupHistory() {
        historyAdapter = new HistoryAdapter(history);
        rvHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvHistory.setAdapter(historyAdapter);
    }

    private void setupListeners() {
        btnStart.setOnClickListener(x -> startLocating());
        btnStop.setOnClickListener(x -> stopLocating());
        btnMiraSearch.setOnClickListener(x -> searchMiraByInput());
        btnExport.setOnClickListener(x -> exportHistory());
        btnMarkFound.setOnClickListener(x -> markAsFound());
        btnClose.setOnClickListener(x -> navigateAway());
    }

    private void setupBackPress() {
        backCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isLocating) {
                    stopLocating();
                } else {
                    navigateAway();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backCallback);
    }

    // ═══════════════════════════════════════════════════════════
    //  زر الزناد الفيزيائي
    // ═══════════════════════════════════════════════════════════

    @Override
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        boolean isTrigger = (keyCode == KEYCODE_TRIGGER_1
                || keyCode == KEYCODE_TRIGGER_2
                || keyCode == KEYCODE_TRIGGER_3);

        if (!isTrigger) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {

            if (isLocating) {
                stopLocating();
            } else {
                startLocating();
            }
            return true;
        }

        return event.getAction() == KeyEvent.ACTION_UP;
    }

    // ═══════════════════════════════════════════════════════════
    //  Start / Stop
    // ═══════════════════════════════════════════════════════════

    private void startLocating() {
        if (isLocating) return;

        if (mainActivity == null || mainActivity.mReader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل" : "⚠️ Reader not connected");
            return;
        }

        String epc = etEpc.getText().toString().trim();
        if (TextUtils.isEmpty(epc)) {
            toast(isArabic ? "⚠️ أدخل EPC أولاً" : "⚠️ Enter EPC first");
            return;
        }

        currentEpc = epc;

        // إعادة تعيين حالة الجلسة
        resetSessionState();

        try {
            boolean started = mainActivity.mReader.startLocation(
                    mainActivity,
                    epc,
                    IUHF.Bank_EPC,
                    32,
                    new IUHFLocationCallback() {
                        @Override
                        public void getLocationValue(int value, boolean valid) {
                            handleLocationValue(value, valid);
                        }
                    });

            if (!started) {
                toast(isArabic ? "❌ فشل بدء التتبع" : "❌ Failed to start");
                return;
            }

            isLocating = true;
            sessionStartTime = System.currentTimeMillis();
            onStartUI();
            queryMiraItem(epc);

            Log.i(TAG, "✅ Locator started for: " + epc);

        } catch (Exception e) {
            Log.e(TAG, "startLocating error", e);
            toast(isArabic ? "❌ خطأ" : "❌ Error");
        }
    }

    private void stopLocating() {
        if (!isLocating) return;

        try {
            if (mainActivity != null && mainActivity.mReader != null) {
                mainActivity.mReader.stopLocation();
            }
        } catch (Exception e) {
            Log.w(TAG, "stopLocation error", e);
        }

        isLocating = false;
        onStopUI();
        Log.i(TAG, "⏹️ Locator stopped");
    }

    private void resetSessionState() {
        smoothedProximity = 0f;
        snapStartTime = 0;
        snapOffered = false;
        totalReadings = 0;
        bestProximity = 0f;
        timeToFindMs = 0;

        if (meterView != null) {
            meterView.resetTrailAndPeak();
        }
    }

    private void onStartUI() {
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        etEpc.setEnabled(false);
        btnMiraSearch.setEnabled(false);
        meterView.setActive(true);

        tvStatus.setText(isArabic ? "📡 جاري البحث..." : "📡 Searching...");
        tvStatus.setTextColor(0xFF38BDF8);
        btnMarkFound.setVisibility(View.GONE);
    }

    private void onStopUI() {
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        etEpc.setEnabled(true);
        btnMiraSearch.setEnabled(true);
        meterView.setActive(false);
        meterView.setProximity(0);

        tvStatus.setText(isArabic ? "جاهز" : "Ready");
        tvStatus.setTextColor(0xFF94A3B8);
        tvDistance.setText("—");
        btnMarkFound.setVisibility(View.GONE);

        resetAutoSnap();
    }

    // ═══════════════════════════════════════════════════════════
    //  Core: Handle Location Value (مع التنعيم والالتقاط التلقائي)
    // ═══════════════════════════════════════════════════════════

    private void handleLocationValue(int value, boolean valid) {
        if (!valid) return;

        currentValue = value;
        totalReadings++;

        // ─── 1) حساب القرب الخام ───
        float rawProximity = Math.max(0f, Math.min(1f, (80f - value) / 60f));

        // ─── 2) تطبيق التنعيم (EMA) ───
        smoothedProximity = (SMOOTHING_FACTOR * rawProximity)
                          + ((1 - SMOOTHING_FACTOR) * smoothedProximity);

        // ─── 3) تحديث أفضل قراءة ───
        if (smoothedProximity > bestProximity) {
            bestProximity = smoothedProximity;
        }

        final float finalProximity = smoothedProximity;

        mainHandler.post(() -> {
            if (!isLocating) return;

            meterView.setProximity(finalProximity);
            tvDistance.setText(estimateDistance(value));

            updateStatusText(finalProximity);
            handleAutoSnap(finalProximity);
            geigerFeedback(value);
        });
    }

    private void updateStatusText(float proximity) {
        if (proximity >= 0.85f) {
            tvStatus.setText(isArabic
                    ? "🔥 ملاصق جداً — " + currentTitle
                    : "🔥 Extremely Close — " + currentTitle);
            tvStatus.setTextColor(0xFF22C55E);
            btnMarkFound.setVisibility(View.VISIBLE);
        } else if (proximity >= 0.55f) {
            tvStatus.setText(isArabic
                    ? "📍 قريب — " + currentTitle
                    : "📍 Near — " + currentTitle);
            tvStatus.setTextColor(0xFFEAB308);
            btnMarkFound.setVisibility(View.GONE);
        } else {
            tvStatus.setText(isArabic
                    ? "🌐 بعيد — " + currentTitle
                    : "🌐 Far — " + currentTitle);
            tvStatus.setTextColor(0xFFEF4444);
            btnMarkFound.setVisibility(View.GONE);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Auto-Snap (الالتقاط التلقائي)
    // ═══════════════════════════════════════════════════════════

    private void handleAutoSnap(float proximity) {
        if (snapOffered) return;

        if (proximity >= SNAP_THRESHOLD) {
            long now = System.currentTimeMillis();
            if (snapStartTime == 0) {
                snapStartTime = now;
            } else if (now - snapStartTime >= SNAP_HOLD_MS) {
                triggerAutoSnap();
            }
        } else {
            snapStartTime = 0;
        }
    }

    private void triggerAutoSnap() {
        snapOffered = true;

        // وميض احتفالي
        if (meterView != null) meterView.flashFound();

        // اهتزاز احتفالي
        performHapticCelebration();

        // صوت
        try {
            if (mainActivity != null) mainActivity.playSound(2);
        } catch (Exception ignored) {}

        toast(isArabic
                ? "🎯 إشارة قوية! اضغط ✅ للتأكيد"
                : "🎯 Strong signal! Tap ✅ to confirm");
    }

    private void resetAutoSnap() {
        snapStartTime = 0;
        snapOffered = false;
    }

    private void performHapticCelebration() {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 80, 40, 80, 40, 200};
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  Distance Estimation
    // ═══════════════════════════════════════════════════════════

    private String estimateDistance(int value) {
        if (value < 25) return isArabic ? "📏 أقل من 10 سم" : "📏 < 10 cm";
        if (value < 35) return isArabic ? "📏 10-30 سم" : "📏 10-30 cm";
        if (value < 45) return isArabic ? "📏 30-60 سم" : "📏 30-60 cm";
        if (value < 55) return isArabic ? "📏 0.6-1 متر" : "📏 0.6-1 m";
        if (value < 65) return isArabic ? "📏 1-2 متر" : "📏 1-2 m";
        if (value < 75) return isArabic ? "📏 2-4 متر" : "📏 2-4 m";
        return isArabic ? "📏 > 4 متر" : "📏 > 4 m";
    }

    // ═══════════════════════════════════════════════════════════
    //  Geiger Feedback (صوت + اهتزاز متدرج)
    // ═══════════════════════════════════════════════════════════

    private void geigerFeedback(int value) {
        long now = System.currentTimeMillis();

        // حساب الفاصل الزمني حسب القرب
        long interval = (long) (BEEP_MIN_INTERVAL
                + (BEEP_MAX_INTERVAL - BEEP_MIN_INTERVAL)
                * Math.min(1.0, value / 80.0));

        if (now - lastBeepTime < interval) return;
        lastBeepTime = now;

        // ─── الصوت ───
        try {
            if (mainActivity != null) {
                // 0 = بعيد، 1 = متوسط، 2 = قريب
                int soundId = value < 35 ? 2 : (value < 55 ? 1 : 0);
                mainActivity.playSound(soundId);
            }
        } catch (Exception ignored) {}

        // ─── الاهتزاز المتدرج ───
        if (vibrator == null || !vibrator.hasVibrator()) return;

        try {
            long[] pattern;
            if (value < 30) {
                // 🔥 ملاصق — اهتزاز مكثف
                pattern = new long[]{0, 60, 30, 60, 30, 60};
            } else if (value < 45) {
                // 📍 قريب — اهتزاز مزدوج
                pattern = new long[]{0, 50, 50, 50};
            } else if (value < 60) {
                // 🟡 متوسط — اهتزازة واحدة
                pattern = new long[]{0, 40};
            } else {
                // 🌐 بعيد — اهتزازة خفيفة
                pattern = new long[]{0, 15};
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  MIRA Integration
    // ═══════════════════════════════════════════════════════════

    private void searchMiraByInput() {
        String input = etEpc.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            toast(isArabic ? "أدخل EPC / Serial" : "Enter EPC / Serial");
            return;
        }
        queryMiraItem(input);
    }

    private void queryMiraItem(String epc) {
        executor.execute(() -> {
            try {
                MiraApiClient api = MiraApiClient.getInstance(requireContext());
                if (api == null) return;

                JSONObject extra = new JSONObject();
                if (branchesManager != null) {
                    extra.put("branch_id", branchesManager.getActiveBranchId());
                }

                MiraApiClient.ApiResponse resp =
                        api.authorize(epc, "locator", extra);

                if (resp != null && resp.isSuccess && resp.data != null) {
                    JSONObject item = resp.data.optJSONObject("item");
                    if (item != null) {
                        final String title = item.optString("title",
                                isArabic ? "قطعة" : "Item");
                        final String karat = item.optString("karat", "");
                        final String weight = item.optString("weight", "");
                        final String location = item.optString("location", "—");
                        final String status = item.optString("status", "");

                        mainHandler.post(() -> {
                            if (!isAdded()) return;
                            currentTitle = title;
                            showItemCard(title, karat, weight, location, status);
                        });
                    }
                } else {
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        currentTitle = isArabic ? "غير مسجلة" : "Unregistered";
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "queryMiraItem error", e);
            }
        });
    }

    private void showItemCard(String title, String karat,
                              String weight, String location, String status) {
        layoutItemCard.setVisibility(View.VISIBLE);
        tvItemTitle.setText("📦 " + title);

        StringBuilder details = new StringBuilder();
        details.append("🏷️ ").append(currentEpc);
        if (!karat.isEmpty()) details.append("  •  💎 ").append(karat);
        if (!weight.isEmpty()) details.append("  •  ⚖️ ").append(weight).append("g");
        tvItemDetails.setText(details.toString());

        tvItemLocation.setText("📍 " + location);

        String statusText = mapStatus(status);
        tvItemStatus.setText(statusText);
        tvItemStatus.setTextColor(0xFF22C55E);
    }

    private String mapStatus(String status) {
        if (status == null) return "";
        switch (status) {
            case "in_stock":
            case "available":
                return isArabic ? "✅ متاح" : "✅ Available";
            case "sold":
                return isArabic ? "🔴 مباع" : "🔴 Sold";
            case "display":
                return isArabic ? "👁️ معروض" : "👁️ On Display";
            case "reserved":
                return isArabic ? "⏳ محجوز" : "⏳ Reserved";
            default:
                return status;
        }
    }

    private void markAsFound() {
        if (TextUtils.isEmpty(currentEpc)) return;

        // حساب وقت العثور
        if (sessionStartTime > 0) {
            timeToFindMs = System.currentTimeMillis() - sessionStartTime;
        }

        // تسجيل في السجل
        addHistory(currentEpc, currentTitle, true);

        // عرض إحصائيات الجلسة
        showSessionStats();

        toast(isArabic ? "✅ تم تسجيل القطعة" : "✅ Item marked");
        stopLocating();
    }

    private void showSessionStats() {
        try {
            long seconds = timeToFindMs / 1000;
            String timeStr = seconds < 60
                    ? seconds + (isArabic ? " ثانية" : " sec")
                    : (seconds / 60) + (isArabic ? " د " : " min ") + (seconds % 60) + (isArabic ? " ث" : " sec");

            int bestPct = (int) (bestProximity * 100);
            int readings = totalReadings;

            String message = isArabic
                    ? "⏱️ وقت البحث: " + timeStr + "\n" +
                      "📊 عدد القراءات: " + readings + "\n" +
                      "🏆 أفضل إشارة: " + bestPct + "%\n" +
                      "🎯 تم العثور على: " + currentTitle
                    : "⏱️ Search time: " + timeStr + "\n" +
                      "📊 Total readings: " + readings + "\n" +
                      "🏆 Best signal: " + bestPct + "%\n" +
                      "🎯 Found: " + currentTitle;

            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(isArabic ? "📈 إحصائيات الجلسة" : "📈 Session Stats")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "رائع!" : "Great!", null)
                    .show();
        } catch (Exception e) {
            Log.w(TAG, "showSessionStats error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  RFID Listener
    // ═══════════════════════════════════════════════════════════

    private void onTagScanned(String epc) {
        if (epc == null || epc.isEmpty()) return;
        if (isLocating) return;
        if (epc.equals(currentEpc)) return;

        mainHandler.post(() -> {
            if (etEpc != null && !isLocating) {
                etEpc.setText(epc);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  History
    // ═══════════════════════════════════════════════════════════

    private void addHistory(String epc, String title, boolean found) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date());
        history.add(0, new HistoryEntry(epc, title, time, found));
        if (history.size() > 20) history.remove(history.size() - 1);
        if (historyAdapter != null) historyAdapter.notifyDataSetChanged();
    }

    private void exportHistory() {
        if (history.isEmpty()) {
            toast(isArabic ? "لا توجد سجلات" : "No records");
            return;
        }

        StringBuilder sb = new StringBuilder("EPC,Title,Time,Found\n");
        for (HistoryEntry h : history) {
            sb.append(h.epc).append(",")
              .append(h.title).append(",")
              .append(h.time).append(",")
              .append(h.found ? "YES" : "NO").append("\n");
        }

        String preview = sb.length() > 500
                ? sb.substring(0, 500) + "..."
                : sb.toString();

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "📤 تصدير السجل" : "📤 Export")
                .setMessage(preview)
                .setPositiveButton(isArabic ? "نسخ" : "Copy", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "MIRA History", sb.toString()));
                    toast(isArabic ? "✅ تم النسخ" : "✅ Copied");
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    // ═══════════════════════════════════════════════════════════
    //  UI Helpers
    // ═══════════════════════════════════════════════════════════

    private void updateReaderStatus() {
        if (tvReaderState == null) return;

        boolean connected = mainActivity != null
                && mainActivity.mReader != null;

        if (connected) {
            tvReaderState.setText(isArabic ? "📡 القارئ: متصل ✓" : "📡 Reader: Connected ✓");
            tvReaderState.setTextColor(0xFF22C55E);
        } else {
            tvReaderState.setText(isArabic ? "📡 القارئ: غير متصل" : "📡 Reader: Disconnected");
            tvReaderState.setTextColor(0xFFEF4444);
        }
    }

    private void applyLanguage() {
        if (btnStart != null) btnStart.setText(isArabic ? "▶ بدء" : "▶ Start");
        if (btnStop != null) btnStop.setText(isArabic ? "⏹ إيقاف" : "⏹ Stop");
        if (btnMiraSearch != null) btnMiraSearch.setText(isArabic ? "🔍 بحث MIRA" : "🔍 Search");
        if (btnExport != null) btnExport.setText(isArabic ? "📤 تصدير" : "📤 Export");
        if (btnMarkFound != null) btnMarkFound.setText(isArabic ? "✅ تم العثور" : "✅ Found");
        if (etEpc != null) etEpc.setHint(isArabic ? "أدخل EPC أو Serial" : "Enter EPC / Serial");
    }

    private void navigateAway() {
        try {
            stopLocating();
            if (mainActivity != null && mainActivity.getNavigationManager() != null) {
                mainActivity.getNavigationManager().goToDashboard();
            }
        } catch (Exception ignored) {}
    }

    private void toast(String msg) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  History Model + Adapter
    // ═══════════════════════════════════════════════════════════

    public static class HistoryEntry {
        public final String epc;
        public final String title;
        public final String time;
        public final boolean found;

        public HistoryEntry(String epc, String title, String time, boolean found) {
            this.epc = epc;
            this.title = title;
            this.time = time;
            this.found = found;
        }
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final List<HistoryEntry> items;

        HistoryAdapter(List<HistoryEntry> items) { this.items = items; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_locator_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            HistoryEntry e = items.get(position);
            h.icon.setText(e.found ? "✅" : "🔍");
            h.title.setText(e.title);
            h.epc.setText(e.epc);
            h.time.setText(e.time);
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView icon, title, epc, time;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.tvIcon);
                title = v.findViewById(R.id.tvTitle);
                epc = v.findViewById(R.id.tvEpc);
                time = v.findViewById(R.id.tvTime);
            }
        }
    }
}
