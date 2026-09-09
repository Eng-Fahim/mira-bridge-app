package com.example.uhf.utils;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.example.uhf.R;
import com.example.uhf.manager.HardwareManager;

public class StatusIndicatorManager {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View statusDot;
    private TextView statusText;
    private HardwareManager hardwareManager;
    private Handler pulseHandler;
    private Runnable pulseRunnable;
    private boolean isPulseRunning = false;

    public StatusIndicatorManager(View rootView, HardwareManager hardwareManager) {
        this.statusDot = rootView.findViewById(R.id.statusDot);
        this.statusText = rootView.findViewById(R.id.statusText);
        this.hardwareManager = hardwareManager;
    }

    public void update() {
        if (statusDot == null || statusText == null) return;

        HardwareManager.HardwareStatus status = hardwareManager.getCurrentStatus();

        switch (status) {
            case CONNECTED:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                statusText.setText(R.string.status_connected);
                startPulseAnimation(1.0f, 1.15f);
                break;
            case CONNECTING:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange);
                statusText.setText(R.string.status_connecting);
                startPulseAnimation(0.7f, 1.3f);
                break;
            case DISCONNECTED:
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                statusText.setText(R.string.status_disconnected);
                stopPulseAnimation();
                statusDot.animate().alpha(0.5f).setDuration(300).start();
                break;
        }
    }

    private void startPulseAnimation(float minScale, float maxScale) {
        stopPulseAnimation();

        isPulseRunning = true;
        pulseHandler = new Handler(Looper.getMainLooper());
        pulseRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPulseRunning || statusDot == null) return;

                statusDot.animate()
                        .scaleX(maxScale)
                        .scaleY(maxScale)
                        .setDuration(600)
                        .withEndAction(() -> {
                            if (!isPulseRunning || statusDot == null) return;

                            statusDot.animate()
                                    .scaleX(minScale)
                                    .scaleY(minScale)
                                    .setDuration(600)
                                    .withEndAction(() -> {
                                        if (isPulseRunning && pulseHandler != null) {
                                            pulseHandler.postDelayed(pulseRunnable, 200);
                                        }
                                    })
                                    .start();
                        })
                        .start();
            }
        };

        pulseHandler.post(pulseRunnable);
    }

    private void stopPulseAnimation() {
        isPulseRunning = false;
        if (pulseHandler != null && pulseRunnable != null) {
            pulseHandler.removeCallbacks(pulseRunnable);
        }
        if (statusDot != null) {
            statusDot.animate().cancel();
            statusDot.setScaleX(1.0f);
            statusDot.setScaleY(1.0f);
            statusDot.setAlpha(1.0f);
        }
    }

    public void scheduleUpdates() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                update();
                handler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    public void cleanup() {
        handler.removeCallbacksAndMessages(null);
        stopPulseAnimation();
    }
}
