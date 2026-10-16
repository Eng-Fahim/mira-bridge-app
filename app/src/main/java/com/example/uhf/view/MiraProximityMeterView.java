package com.example.uhf.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * MiraProximityMeterView v1.0
 * 
 * مقياس قرب دائري متحرك:
 *   • قوس ملوّن حول الدائرة (أخضر → أصفر → أحمر)
 *   • نبض متحرك (Geiger effect)
 *   • نسبة رقمية في المركز
 */
public class MiraProximityMeterView extends View {

    private float proximity = 0f;       // 0.0 (بعيد) → 1.0 (ملاصق)
    private boolean active = false;
    private float pulsePhase = 0f;

    private Paint bgPaint, arcBgPaint, arcPaint, innerPaint, textPaint, labelPaint;
    private RectF arcRect;
    private ValueAnimator pulseAnimator;

    private static final float STROKE_WIDTH_DP = 14f;

    public MiraProximityMeterView(Context c) { super(c); init(); }
    public MiraProximityMeterView(Context c, AttributeSet a) { super(c, a); init(); }
    public MiraProximityMeterView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        float stroke = STROKE_WIDTH_DP * density;

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFF0F172A);
        bgPaint.setStyle(Paint.Style.FILL);

        arcBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcBgPaint.setColor(0xFF1E293B);
        arcBgPaint.setStyle(Paint.Style.STROKE);
        arcBgPaint.setStrokeWidth(stroke);
        arcBgPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setColor(0xFFEF4444);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerPaint.setColor(0xFF1E293B);
        innerPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(0xFF94A3B8);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        arcRect = new RectF();
    }

    public void setProximity(float p) {
        proximity = Math.max(0f, Math.min(1f, p));
        updateColor();
        invalidate();
    }

    public void setActive(boolean isActive) {
        active = isActive;
        if (isActive) startPulse();
        else stopPulse();
        invalidate();
    }

    private void updateColor() {
        int color;
        if (proximity >= 0.75f)      color = 0xFF22C55E; // أخضر
        else if (proximity >= 0.5f)  color = 0xFFEAB308; // أصفر
        else if (proximity >= 0.25f) color = 0xFFF97316; // برتقالي
        else                          color = 0xFFEF4444; // أحمر

        arcPaint.setColor(color);
        textPaint.setColor(color);
    }

    private void startPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) return;

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            pulsePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        pulsePhase = 0f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;

        float stroke = STROKE_WIDTH_DP * getResources().getDisplayMetrics().density;
        float radius = (size - stroke) / 2f - 8;
        float innerRadius = radius - stroke;

        // 1) خلفية
        canvas.drawCircle(cx, cy, radius - stroke / 2f, bgPaint);

        // 2) قوس الخلفية
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arcRect, 135, 270, false, arcBgPaint);

        // 3) قوس التقدم
        if (proximity > 0) {
            canvas.drawArc(arcRect, 135, 270 * proximity, false, arcPaint);
        }

        // 4) نبض داخلي (Geiger)
        if (active) {
            float pulseRadius = innerRadius * (0.7f + 0.3f * pulsePhase);
            int alpha = (int) (60 * (1 - pulsePhase));
            innerPaint.setColor(arcPaint.getColor());
            innerPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, pulseRadius, innerPaint);
        }

        // 5) دائرة داخلية
        innerPaint.setColor(0xFF1E293B);
        innerPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, innerRadius - 6, innerPaint);

        // 6) النسبة الرقمية
        textPaint.setTextSize(innerRadius * 0.55f);
        String pct = String.format(java.util.Locale.US, "%d%%", (int)(proximity * 100));
        canvas.drawText(pct, cx, cy + innerRadius * 0.15f, textPaint);

        // 7) التسمية
        labelPaint.setTextSize(innerRadius * 0.18f);
        String label = proximity >= 0.75f ? "ملاصق"
                : proximity >= 0.5f ? "قريب"
                : proximity >= 0.25f ? "متوسط"
                : "بعيد";
        canvas.drawText(label, cx, cy + innerRadius * 0.55f, labelPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
    }
}
