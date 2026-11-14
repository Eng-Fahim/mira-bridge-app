package com.example.uhf.widget;

import android.content.Context;
import android.os.CountDownTimer;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;

import java.util.Locale;

public class CountdownTimerView extends FrameLayout {

    private TextView tvTimer, tvStatus;
    private ProgressBar pbCountdown;
    private CountDownTimer timer;

    private static final int COLOR_SAFE   = 0xFF16A34A;
    private static final int COLOR_WARN   = 0xFFF59E0B;
    private static final int COLOR_DANGER = 0xFFDC2626;

    public CountdownTimerView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public CountdownTimerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_countdown_timer, this, true);
        tvTimer      = findViewById(R.id.tvTimer);
        tvStatus     = findViewById(R.id.tvStatus);
        pbCountdown  = findViewById(R.id.pbCountdown);
    }

    /**
     * بدء العد التنازلي.
     * @param createdAt وقت إنشاء الفاتورة (millis)
     * @param expiresAt وقت انتهاء المهلة (millis)
     * @param onExpired ما يحدث عند انتهاء الوقت (لتحديث البيانات)
     */
    public void start(long createdAt, long expiresAt, Runnable onExpired) {
        cancel();
        long totalDuration = Math.max(1, expiresAt - createdAt);
        long remaining = expiresAt - System.currentTimeMillis();

        if (remaining <= 0) {
            renderExpired();
            return;
        }

        timer = new CountDownTimer(remaining, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long seconds = millisUntilFinished / 1000;
                long minutes = seconds / 60;
                long secs = seconds % 60;

                if (tvTimer != null) {
                    tvTimer.setText(String.format(Locale.US, "%02d:%02d", minutes, secs));
                }

                int progress = (int) ((millisUntilFinished * 100) / totalDuration);
                if (pbCountdown != null) pbCountdown.setProgress(progress);

                int color = progress <= 15 ? COLOR_DANGER :
                            progress <= 50 ? COLOR_WARN   : COLOR_SAFE;

                if (tvTimer != null)   tvTimer.setTextColor(color);
                if (pbCountdown != null) pbCountdown.setProgressTintList(
                        android.content.res.ColorStateList.valueOf(color));

                if (tvStatus != null) {
                    tvStatus.setTextColor(color);
                    tvStatus.setText(progress <= 15 ? "🚨 ينفد" : "⏳ معلقة");
                }
            }

            @Override
            public void onFinish() {
                renderExpired();
                if (onExpired != null) onExpired.run();
            }
        };
        timer.start();
    }

    private void renderExpired() {
        if (tvTimer != null) {
            tvTimer.setText("انتهت");
            tvTimer.setTextColor(COLOR_DANGER);
        }
        if (tvStatus != null) {
            tvStatus.setText("✖ منتهية");
            tvStatus.setTextColor(COLOR_DANGER);
        }
        if (pbCountdown != null) pbCountdown.setProgress(0);
    }

    /** إيقاف التايمر — يجب استدعاؤها عند فصل العنصر من الشاشة */
    public void cancel() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
