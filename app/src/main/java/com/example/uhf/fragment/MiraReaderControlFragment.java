package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.example.uhf.activity.BaseTabFragmentActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.rscja.deviceapi.RFIDWithUHFUART;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ✅ MiraReaderControlFragment v3 — لوحة التحكم الكاملة
 *
 * يعرض الإعدادات الحقيقية من توثيق Chainway SDK الرسمي:
 *   - 19 منطقة تردد (HEX)
 *   - RFLink بـ 12 وضعاً
 *   - Session / Target / Q
 *   - FastID / TagFocus / Fast Inventory
 *   - 📦 بيانات الوسم (EPC / TID / USER)
 *   - 📡 اختبار القراءة المباشر
 */
public class MiraReaderControlFragment extends Fragment {

    private static final String TAG = "MiraReaderControl";

    private MiraReaderSettings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isUpdatingUi = new AtomicBoolean(false);
    private boolean isArabic = true;

    // ═══ Views ═══
    private SeekBar seekPower;
    private TextView tvPowerValue, tvSummary;
    private Spinner spFrequency, spRfLink, spSession, spTarget, spQValue;
    private MaterialSwitch swFastId, swTagFocus, swFastInventory;
    private MaterialButton btnApplyNow, btnReset, btnReadCurrent;
    private View loading;

    // ═══ Live Test ═══
    private TextView tvLiveReadCount, tvLiveRate, tvLiveLastEpc, tvLiveRssi;
    private MaterialButton btnLiveTestToggle;

    private volatile boolean isLiveTesting = false;
    private volatile int liveReadCount = 0;
    private volatile long liveTestStartTime = 0;
    private volatile String liveLastEpc = null;
    private volatile int liveLastRssi = -100;
    private Thread liveReadThread;
    private final Handler liveUiHandler = new Handler(Looper.getMainLooper());
    private Runnable liveRateUpdater;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_mira_reader_control, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
        super.onViewCreated(v, saved);
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        settings = MiraReaderSettings.getInstance(requireContext());

        bindViews(v);
        setupSpinners();
        setupListeners();
        loadSettingsToUi();
        updateSummary();
    }

    // ═══════════════════════════════════════════════════════════
    //  Bind
    // ═══════════════════════════════════════════════════════════
    private void bindViews(View v) {
        // Main
        seekPower         = v.findViewById(R.id.seekPower);
        tvPowerValue      = v.findViewById(R.id.tvPowerValue);
        tvSummary         = v.findViewById(R.id.tvSettingsSummary);
        spFrequency       = v.findViewById(R.id.spFrequency);
        spRfLink          = v.findViewById(R.id.spRfLink);
        spSession         = v.findViewById(R.id.spSession);
        spTarget          = v.findViewById(R.id.spTarget);
        spQValue          = v.findViewById(R.id.spQValue);
        swFastId          = v.findViewById(R.id.swFastId);
        swTagFocus        = v.findViewById(R.id.swTagFocus);
        swFastInventory   = v.findViewById(R.id.swFastInventory);
        btnApplyNow       = v.findViewById(R.id.btnApplyNow);
        btnReset          = v.findViewById(R.id.btnResetDefaults);
        btnReadCurrent    = v.findViewById(R.id.btnReadCurrent);
        loading           = v.findViewById(R.id.readerLoading);

        // Live Test
        tvLiveReadCount   = v.findViewById(R.id.tvLiveReadCount);
        tvLiveRate        = v.findViewById(R.id.tvLiveRate);
        tvLiveLastEpc     = v.findViewById(R.id.tvLiveLastEpc);
        tvLiveRssi        = v.findViewById(R.id.tvLiveRssi);
        btnLiveTestToggle = v.findViewById(R.id.btnLiveTestToggle);
    }

    // ═══════════════════════════════════════════════════════════
    //  Spinners
    // ═══════════════════════════════════════════════════════════
    private void setupSpinners() {
        // ✅ 19 منطقة تردد
        spFrequency.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MiraReaderSettings.FREQ_LABELS));

        // ✅ RFLink — 12 وضعاً
        spRfLink.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MiraReaderSettings.RFLINK_LABELS));

        // Session
        String[] sessions = {"S0", "S1", "S2", "S3"};
        spSession.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, sessions));

        // Target
        String[] targets = {"A", "B"};
        spTarget.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, targets));

        // Q Value (0-15)
        String[] qValues = new String[16];
        for (int i = 0; i < 16; i++) qValues[i] = String.valueOf(i);
        spQValue.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, qValues));
    }

    // ═══════════════════════════════════════════════════════════
    //  Listeners
    // ═══════════════════════════════════════════════════════════
    private void setupListeners() {
        // ═══ Power ═══
        seekPower.setMax(33);
        seekPower.setMin(5);
        seekPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (isUpdatingUi.get()) return;
                tvPowerValue.setText(p + " dBm");
                settings.setPowerDbm(p);
                updateSummary();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // ═══ Frequency — القيمة HEX ═══
        spFrequency.setOnItemSelectedListener(spinnerListener(pos -> {
            int mode = MiraReaderSettings.FREQ_MODES[pos][0];
            settings.setFreqMode(mode);
        }));

        // ═══ RFLink ═══
        spRfLink.setOnItemSelectedListener(spinnerListener(pos -> settings.setRfLink(pos)));

        // ═══ Session / Target / Q ═══
        spSession.setOnItemSelectedListener(spinnerListener(pos -> settings.setSession(pos)));
        spTarget.setOnItemSelectedListener(spinnerListener(pos -> settings.setTarget(pos)));
        spQValue.setOnItemSelectedListener(spinnerListener(pos -> settings.setQValue(pos)));

        // ═══ Switches ═══
        swFastId.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setFastId(c); updateSummary();
        });
        swTagFocus.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setTagFocus(c); updateSummary();
        });
        swFastInventory.setOnCheckedChangeListener((b, c) -> {
            if (isUpdatingUi.get()) return;
            settings.setFastInventory(c); updateSummary();
        });

        // ═══ Main Buttons ═══
        btnApplyNow.setOnClickListener(x -> applyNow());
        btnReset.setOnClickListener(x -> confirmReset());
        btnReadCurrent.setOnClickListener(x -> readCurrent());

        // ═══ Live Test ═══ (✅ في المكان الصحيح)
        btnLiveTestToggle.setOnClickListener(x -> toggleLiveTest());
    }

    private AdapterView.OnItemSelectedListener spinnerListener(IntConsumer onChange) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (isUpdatingUi.get()) return;
                onChange.accept(pos);
                updateSummary();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
    }

    private interface IntConsumer { void accept(int value); }

    // ═══════════════════════════════════════════════════════════
    //  Load UI
    // ═══════════════════════════════════════════════════════════
    private void loadSettingsToUi() {
        isUpdatingUi.set(true);
        try {
            seekPower.setProgress(settings.getPowerDbm());
            tvPowerValue.setText(settings.getPowerDbm() + " dBm");

            int freqIdx = 0;
            for (int i = 0; i < MiraReaderSettings.FREQ_MODES.length; i++) {
                if (MiraReaderSettings.FREQ_MODES[i][0] == settings.getFreqMode()) {
                    freqIdx = i; break;
                }
            }
            spFrequency.setSelection(freqIdx);

            spRfLink.setSelection(Math.min(settings.getRfLink(),
                    MiraReaderSettings.RFLINK_LABELS.length - 1));
            spSession.setSelection(settings.getSession());
            spTarget.setSelection(settings.getTarget());
            spQValue.setSelection(settings.getQValue());

            swFastId.setChecked(settings.isFastId());
            swTagFocus.setChecked(settings.isTagFocus());
            swFastInventory.setChecked(settings.isFastInventory());
        } finally {
            isUpdatingUi.set(false);
        }
    }

    private void updateSummary() {
        if (tvSummary != null) tvSummary.setText(settings.getSummary());
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ تطبيق على القارئ الآن
    // ═══════════════════════════════════════════════════════════
    private void applyNow() {
        RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
        if (reader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل — سيُطبَّق عند الاتصال"
                           : "⚠️ Reader not connected — will apply on connect");
            return;
        }

        showLoading(true);
        new Thread(() -> {
            MiraReaderSettings.ApplyResult r = settings.applyToReader(reader);
            ui.post(() -> {
                showLoading(false);
                String msg = r.isSuccess()
                    ? (isArabic ? "✅ تم تطبيق " + r.applied + " إعداد"
                                : "✅ Applied " + r.applied + " settings")
                    : (isArabic ? "⚠️ نجح " + r.applied + " / فشل " + r.failed
                                : "⚠️ OK " + r.applied + " / Failed " + r.failed);
                toast(msg);
            });
        }, "ApplyMiraSettings").start();
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ قراءة الحالة الحقيقية من العتاد
    // ═══════════════════════════════════════════════════════════
    private void readCurrent() {
        RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
        if (reader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل" : "⚠️ Reader not connected");
            return;
        }

        StringBuilder sb = new StringBuilder();

        try {
            int p = reader.getPower();
            sb.append("Power (real): ").append(p).append(" dBm\n");
        } catch (Exception e) {
            sb.append("Power (real): ?\n");
        }

        try {
            int fm = reader.getFrequencyMode();
            sb.append("Frequency (real): ").append(MiraReaderSettings.getFreqLabel(fm))
              .append("  [0x").append(Integer.toHexString(fm)).append("]\n");
        } catch (Exception e) {
            sb.append("Frequency (real): ?\n");
        }

        try {
            int rl = reader.getRFLink();
            String lbl = (rl >= 0 && rl < MiraReaderSettings.RFLINK_LABELS.length)
                    ? MiraReaderSettings.RFLINK_LABELS[rl] : "?";
            sb.append("RFLink (real): ").append(lbl).append("  [").append(rl).append("]\n");
        } catch (Exception e) {
            sb.append("RFLink (real): ?\n");
        }

        sb.append("\n── Saved in MIRA ──\n");
        sb.append("Power (saved): ").append(settings.getPowerDbm()).append(" dBm\n");
        sb.append("Frequency (saved): ").append(MiraReaderSettings.getFreqLabel(settings.getFreqMode())).append("\n");
        sb.append("Session (saved): S").append(settings.getSession()).append("\n");
        sb.append("Target (saved): ").append(settings.getTarget() == 0 ? "A" : "B").append("\n");
        sb.append("Q (saved): ").append(settings.getQValue()).append("\n");
        sb.append("FastID (saved): ").append(settings.isFastId() ? "ON" : "OFF").append("\n");
        sb.append("TagFocus (saved): ").append(settings.isTagFocus() ? "ON" : "OFF").append("\n");

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📖 الحالة الحالية" : "📖 Current State")
            .setMessage(sb.toString())
            .setPositiveButton(isArabic ? "موافق" : "OK", null)
            .show();
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "🔄 إعادة تعيين" : "🔄 Reset")
            .setMessage(isArabic
                ? "سيتم إرجاع كل الإعدادات للقيم الافتراضية.\nهل أنت متأكد؟"
                : "All settings will reset to defaults.\nContinue?")
            .setPositiveButton(isArabic ? "نعم" : "Yes", (d, w) -> {
                settings.resetToDefaults();
                loadSettingsToUi();
                updateSummary();
                applyNow();
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    // ═══════════════════════════════════════════════════════════
    //  📡 Live Test Operations
    // ═══════════════════════════════════════════════════════════

    private void toggleLiveTest() {
        if (isLiveTesting) {
            stopLiveTest();
        } else {
            startLiveTest();
        }
    }

    private void startLiveTest() {
        RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
        if (reader == null) {
            toast(isArabic ? "⚠️ القارئ غير متصل" : "⚠️ Reader not connected");
            return;
        }

        // ═══ 1) طبّق الإعدادات الحالية ═══
        try {
            MiraReaderSettings.ApplyResult r = settings.applyToReader(reader);
            Log.i(TAG, "Live test — settings applied: " + r);
        } catch (Exception e) {
            Log.e(TAG, "applyToReader failed", e);
        }

        // ═══ 2) ابدأ inventory ═══
        try {
            if (!reader.startInventoryTag()) {
                toast(isArabic ? "❌ فشل بدء القراءة" : "❌ Failed to start");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "startInventoryTag failed", e);
            toast(isArabic ? "❌ خطأ في القارئ" : "❌ Reader error");
            return;
        }

        // ═══ 3) حدّث الحالة ═══
        isLiveTesting = true;
        liveReadCount = 0;
        liveLastEpc = null;
        liveLastRssi = -100;
        liveTestStartTime = System.currentTimeMillis();

        if (btnLiveTestToggle != null) {
            btnLiveTestToggle.setText(isArabic ? "⏹️ إيقاف الاختبار" : "⏹️ Stop Test");
            btnLiveTestToggle.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFDC2626));
        }

        // ═══ 4) شغّل حلقة القراءة ═══
        liveReadThread = new Thread(this::liveReadLoop, "LiveTest");
        liveReadThread.setDaemon(true);
        liveReadThread.start();

        // ═══ 5) شغّل مُحدّث الواجهة ═══
        startLiveRateUpdater();

        Log.i(TAG, "✅ Live test started");
    }

    private void liveReadLoop() {
        while (isLiveTesting && !Thread.currentThread().isInterrupted()) {
            try {
                RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
                if (reader == null) { Thread.sleep(200); continue; }

                com.rscja.deviceapi.entity.UHFTAGInfo tag =
                        reader.readTagFromBuffer();

                if (tag != null) {
                    String epc = tag.getEPC();
                    String rssiStr = tag.getRssi();
                    int rssi = parseRssi(rssiStr);

                    if (epc != null && !epc.isEmpty()) {
                        liveReadCount++;
                        liveLastEpc = epc;
                        liveLastRssi = rssi;
                    }
                } else {
                    Thread.sleep(40);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "liveReadLoop error: " + e.getMessage());
                try { Thread.sleep(100); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Log.d(TAG, "Live read loop ended");
    }

    private int parseRssi(String rssiStr) {
        if (rssiStr == null || rssiStr.isEmpty()) return -50;
        try {
            String cleaned = rssiStr.replace("dBm", "")
                    .replace("dbm", "").replace("dB", "").trim();
            int rssi = Integer.parseInt(cleaned);
            return rssi > 0 ? -rssi : rssi;
        } catch (NumberFormatException e) {
            return -50;
        }
    }

    private void startLiveRateUpdater() {
        liveRateUpdater = new Runnable() {
            @Override
            public void run() {
                if (!isLiveTesting) return;
                updateLiveUi();
                liveUiHandler.postDelayed(this, 250);
            }
        };
        liveUiHandler.post(liveRateUpdater);
    }

    private void updateLiveUi() {
        if (!isAdded()) return;
        try {
            long elapsed = System.currentTimeMillis() - liveTestStartTime;
            double rate = elapsed > 0 ? (liveReadCount * 1000.0 / elapsed) : 0.0;

            if (tvLiveReadCount != null)
                tvLiveReadCount.setText(String.valueOf(liveReadCount));

            if (tvLiveRate != null)
                tvLiveRate.setText(String.format(Locale.US, "%.1f/s", rate));

            if (tvLiveLastEpc != null)
                tvLiveLastEpc.setText(liveLastEpc != null ? liveLastEpc : "—");

            if (tvLiveRssi != null) {
                if (liveLastEpc != null) {
                    tvLiveRssi.setText(liveLastRssi + " dBm");

                    int color;
                    if (liveLastRssi >= -45) color = 0xFF16A34A;
                    else if (liveLastRssi >= -65) color = 0xFFF59E0B;
                    else color = 0xFFDC2626;
                    tvLiveRssi.setTextColor(color);
                } else {
                    tvLiveRssi.setText("— dBm");
                    tvLiveRssi.setTextColor(0xFF6B7280);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "updateLiveUi error: " + e.getMessage());
        }
    }

    private void stopLiveTest() {
        if (!isLiveTesting) return;
        isLiveTesting = false;

        // 1) أوقف الـ thread
        if (liveReadThread != null) {
            liveReadThread.interrupt();
            liveReadThread = null;
        }

        // 2) أوقف مُحدّث الـ UI
        if (liveUiHandler != null && liveRateUpdater != null) {
            liveUiHandler.removeCallbacks(liveRateUpdater);
            liveRateUpdater = null;
        }

        // 3) أوقف القارئ
        try {
            RFIDWithUHFUART reader = BaseTabFragmentActivity.getSharedReader();
            if (reader != null) reader.stopInventory();
        } catch (Exception e) {
            Log.w(TAG, "stopInventory error: " + e.getMessage());
        }

        // 4) أعِد الزر لحالته
        if (btnLiveTestToggle != null) {
            btnLiveTestToggle.setText(isArabic
                    ? "▶️ ابدأ اختبار القراءة"
                    : "▶️ Start Test");
            btnLiveTestToggle.setBackgroundTintList(null);
        }

        Log.i(TAG, "🛑 Live test stopped. Total reads: " + liveReadCount);
        toast(isArabic
                ? "🛑 تم إيقاف الاختبار — " + liveReadCount + " قراءة"
                : "🛑 Test stopped — " + liveReadCount + " reads");
    }

    @Override
    public void onDestroyView() {
        if (isLiveTesting) {
            stopLiveTest();
        }
        super.onDestroyView();
    }

    private void showLoading(boolean show) {
        if (loading != null) loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }
}
