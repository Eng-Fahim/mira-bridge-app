package com.example.uhf.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ScaleSimulationHelper {

    public interface SimulationCallback {
        void onWeightReceived(double weight, String label);
    }

    private Context context;
    private SimulationCallback callback;
    private boolean isArabic;
    private Handler handler = new Handler(Looper.getMainLooper());

    public ScaleSimulationHelper(Context context, SimulationCallback callback) {
        this.context = context;
        this.callback = callback;
        this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
    }

    public void showSimulationDialog() {
        new MaterialAlertDialogBuilder(context)
            .setTitle(isArabic ? "🧪 محاكاة الميزان" : "🧪 Scale Simulation")
            .setMessage(isArabic ? 
                "هذه محاكاة لاختبار الاتصال بالميزان.\n\n" +
                "سيتم إرسال أوزان تجريبية تلقائياً"
                :
                "This is a scale simulation for testing.\n\n" +
                "Test weights will be sent automatically")
            .setPositiveButton(isArabic ? "ابدأ المحاكاة" : "Start Simulation", (dialog, which) -> {
                dialog.dismiss();
                startSimulation();
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    public void startSimulation() {
        double[] weights = {15.50, 12.35, 18.75, 22.10, 8.90};
        String[] labels = {"خاتم ذهب", "سلسلة", "أسورة", "قلادة", "دبلة"};

        for (int i = 0; i < weights.length; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                if (callback != null) {
                    callback.onWeightReceived(weights[index], labels[index]);
                }
            }, (i + 1) * 2000);
        }
    }

    public void cleanup() {
        handler.removeCallbacksAndMessages(null);
    }
}
