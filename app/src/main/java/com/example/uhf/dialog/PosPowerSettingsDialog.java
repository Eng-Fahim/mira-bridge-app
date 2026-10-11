package com.example.uhf.dialog;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.example.uhf.R;
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  PosPowerSettingsDialog — إعداد قوة قراءة POS
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * المستخدم يختار:
 *   - قوة الإرسال (5-30 dBm)
 *   - يرى المدى التقريبي مباشرة
 *
 * يُحفظ في MiraReaderSettings.setPosPowerDbm()
 */
public final class PosPowerSettingsDialog {

    private static final String TAG = "PosPowerSettingsDialog";

    public interface OnPowerChangedListener {
        void onPowerChanged(int newPowerDbm);
    }

    private PosPowerSettingsDialog() {}

    /**
     * ✅ فتح نافذة الإعداد
     *
     * @param ctx        السياق
     * @param isArabic   للترجمة
     * @param onChanged  callback ليعيد تطبيق القوة على القارئ
     */
    public static void show(@NonNull Context ctx,
                            boolean isArabic,
                            @NonNull OnPowerChangedListener onChanged) {

        MiraReaderSettings settings = MiraReaderSettings.getInstance(ctx);
        final int savedValue = settings.getPosPowerDbm();

        // ═══════════════════════════════════════════════════════
        //  بناء الواجهة برمجياً
        // ═══════════════════════════════════════════════════════
        View view = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_pos_power_settings, null);

        TextView tvValue      = view.findViewById(R.id.tvPowerValue);
        TextView tvRange      = view.findViewById(R.id.tvPowerRange);
        TextView tvHint       = view.findViewById(R.id.tvPowerHint);
        SeekBar  seekBar      = view.findViewById(R.id.seekPower);
        MaterialButton btnReset = view.findViewById(R.id.btnPowerReset);

        final int[] currentValue = { savedValue };

        // ═══════════════════════════════════════════════════════
        //  القيم الابتدائية
        // ═══════════════════════════════════════════════════════
        int min = MiraReaderSettings.MIN_POS_POWER_DBM;
        int max = MiraReaderSettings.MAX_POS_POWER_DBM;

        seekBar.setMax(max - min);
        seekBar.setProgress(savedValue - min);

        // ═══════════════════════════════════════════════════════
        //  ربط الأحداث
        // ═══════════════════════════════════════════════════════
        updateLabels(tvValue, tvRange, tvHint, savedValue, isArabic);

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

        btnReset.setOnClickListener(v -> {
            int def = MiraReaderSettings.DEF_POS_POWER_DBM;
            seekBar.setProgress(def - min);
            currentValue[0] = def;
            updateLabels(tvValue, tvRange, tvHint, def, isArabic);
        });

        // ═══════════════════════════════════════════════════════
        //  عرض AlertDialog
        // ═══════════════════════════════════════════════════════
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(isArabic
                        ? "⚡ قوة قراءة POS"
                        : "⚡ POS Reading Power")
                .setView(view)
                .setPositiveButton(isArabic ? "حفظ" : "Save", (d, w) -> {
                    int finalValue = currentValue[0];
                    settings.setPosPowerDbm(finalValue);
                    Log.i(TAG, "✅ POS power changed → " + finalValue + " dBm");
                    onChanged.onPowerChanged(finalValue);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .setNeutralButton(isArabic ? "الافتراضي" : "Default",
                        (d, w) -> {
                            int def = MiraReaderSettings.DEF_POS_POWER_DBM;
                            settings.setPosPowerDbm(def);
                            onChanged.onPowerChanged(def);
                        })
                .show();
    }

    /**
     * ✅ تحديث النصوص المعروضة
     */
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
                } else if (dbm <= 10) {
                    hint = isArabic
                            ? "⚖️ توازن — مدى قصير وموثوق"
                            : "⚖️ Balanced — short range, reliable";
                } else if (dbm <= 18) {
                    hint = isArabic
                            ? "⚠️ قد يقرأ قطعاً مجاورة"
                            : "⚠️ May read adjacent items";
                } else {
                    hint = isArabic
                            ? "❌ خطر — قراءة قطع غير مرغوبة"
                            : "❌ Risk — unwanted tag reads";
                }
                tvHint.setText(hint);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateLabels error", e);
        }
    }
}
