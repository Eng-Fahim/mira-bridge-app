package com.example.uhf.dialog;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.example.uhf.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  PosPowerSettingsDialog — إعداد قوة قراءة POS / UHF
 * ═══════════════════════════════════════════════════════════════════════════
 */
public final class PosPowerSettingsDialog {

    private static final String TAG = "PosPowerSettingsDialog";

    public interface OnPowerChangedListener {
        void onPowerChanged(int newPowerDbm);
    }

    private PosPowerSettingsDialog() {}

    public static void show(@NonNull Context ctx,
                            boolean isArabic,
                            @NonNull OnPowerChangedListener onChanged) {

        MiraReaderSettings settings = MiraReaderSettings.getInstance(ctx);
        int min = MiraReaderSettings.MIN_POS_POWER_DBM;
        int max = Math.max(min + 1, MiraReaderSettings.MAX_POS_POWER_DBM);
        final int savedValue = Math.max(min, Math.min(max, settings.getPosPowerDbm()));

        // تغليف السياق بثيم MaterialComponents لمنع InflateException مع MaterialButton و MaterialAlertDialogBuilder
        Context themedCtx = new ContextThemeWrapper(
                ctx,
                com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert
        );

        View view = LayoutInflater.from(themedCtx)
                .inflate(R.layout.dialog_pos_power_settings, null);
        view.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        TextView tvValue   = view.findViewById(R.id.tvPowerValue);
        TextView tvRange   = view.findViewById(R.id.tvPowerRange);
        TextView tvHint    = view.findViewById(R.id.tvPowerHint);
        SeekBar  seekBar   = view.findViewById(R.id.seekPower);
        View     btnReset  = view.findViewById(R.id.btnPowerReset);

        final int[] currentValue = { savedValue };

        if (seekBar != null) {
            seekBar.setMax(max - min);
            seekBar.setProgress(savedValue - min);

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    int value = min + progress;
                    currentValue[0] = value;
                    updateLabels(tvValue, tvRange, tvHint, value, isArabic);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        updateLabels(tvValue, tvRange, tvHint, savedValue, isArabic);

        if (btnReset != null) {
            btnReset.setOnClickListener(v -> {
                int def = Math.max(min, Math.min(max, MiraReaderSettings.DEF_POS_POWER_DBM));
                if (seekBar != null) {
                    seekBar.setProgress(def - min);
                }
                currentValue[0] = def;
                updateLabels(tvValue, tvRange, tvHint, def, isArabic);
            });
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(themedCtx)
                .setTitle(isArabic ? "⚡ إعدادات طاقة القارئ" : "⚡ Reader Power Settings")
                .setView(view)
                .setPositiveButton(isArabic ? "حفظ" : "Save", (d, w) -> {
                    int finalValue = currentValue[0];
                    settings.setPosPowerDbm(finalValue);
                    Log.i(TAG, "✅ Reader power changed → " + finalValue + " dBm");
                    onChanged.onPowerChanged(finalValue);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .setNeutralButton(isArabic ? "الافتراضي" : "Default", (d, w) -> {
                    int def = Math.max(min, Math.min(max, MiraReaderSettings.DEF_POS_POWER_DBM));
                    settings.setPosPowerDbm(def);
                    onChanged.onPowerChanged(def);
                })
                .create();

        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR
            );
        }

        dialog.show();
    }

    private static void updateLabels(TextView tvValue, TextView tvRange,
                                     TextView tvHint, int dbm, boolean isArabic) {
        try {
            if (tvValue != null) {
                tvValue.setText(dbm + " dBm");
            }
            if (tvRange != null) {
                tvRange.setText(isArabic
                        ? "المدى التقريبي: " + MiraReaderSettings.getPosRangeLabel(dbm)
                        : "Approx. range: " + MiraReaderSettings.getPosRangeLabel(dbm));
            }
            if (tvHint != null) {
                String hint;
                if (dbm <= 5) {
                    hint = isArabic
                            ? "🛡️ آمن جداً — الشريحة على بُعد سنتيمترات فقط"
                            : "🛡️ Very safe — tag must be centimeters away";
                } else if (dbm <= 12) {
                    hint = isArabic
                            ? "⚖️ توازن — مدى قصير وموثوق"
                            : "⚖️ Balanced — short range, reliable";
                } else if (dbm <= 20) {
                    hint = isArabic
                            ? "⚠️ مدى متوسط — مناسب للأدراج والواجهات"
                            : "⚠️ Medium range — suitable for trays and displays";
                } else {
                    hint = isArabic
                            ? "📡 مدى بعيد — مناسب للجرد الشامل السريع"
                            : "📡 Long range — suitable for fast full inventory";
                }
                tvHint.setText(hint);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateLabels error", e);
        }
    }
}
