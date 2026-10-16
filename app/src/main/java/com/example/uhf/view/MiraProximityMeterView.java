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
 * MiraProximityMeterView v2.0
 *
 * مقياس قرب دائري متطور:
 *   • قوس ملوّن (أحمر → برتقالي → أصفر → أخضر)
 *   • نبض داخلي (Geiger effect)
 *   • رادار دوّار (Radar Sweep)
 *   • أثر القراءات السابقة (Trail)
 *   • علامة أفضل قراءة (Peak Marker)
 *   • وميض احتفالي عند العثور (Flash Mode)
 *   • نسبة رقمية + تسمية نصية
 */
public class MiraProximityMeterView extends View {

    // ═══ الحالة ═══
    private float proximity = 0f;       // 0.0 (بعيد) → 1.0 (ملاصق)
    private boolean active = false;
    private float pulsePhase = 0f;
    private float sweepAngle = 0f;

    // ═══ Trail & Peak ═══
    private final float[] trailHistory = new float[30];
    private int trailIndex = 0;
    private float peakProximity = 0f;

    // ═══ Flash Mode ═══
    private boolean flashMode = false;
    private long flashStartTime = 0;
    private static final long FLASH_DURATION_MS = 900L;

    // ═══ Paints ═══
    private Paint bgPaint, arcBgPaint, arcPaint, innerPaint;
    private Paint textPaint, labelPaint, sweepPaint, trailPaint, peakPaint;
    private RectF arcRect;
    private ValueAnimator pulseAnimator;

    private static final float STROKE_WIDTH_DP = 14f;
    private float density;

    public MiraProximityMeterView(Context c) { super(c); init(); }
    public MiraProximityMeterView(Context c, AttributeSet a) { super(c, a); init(); }
    public MiraProximityMeterView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
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

        sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sweepPaint.setColor(0x4422C55E);
        sweepPaint.setStyle(Paint.Style.STROKE);
        sweepPaint.setStrokeWidth(stroke);
        sweepPaint.setStrokeCap(Paint.Cap.ROUND);

        trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trailPaint.setStyle(Paint.Style.FILL);

        peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        peakPaint.setColor(0xFFFFD700);
        peakPaint.setStyle(Paint.Style.FILL);

        arcRect = new RectF();
    }

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    public void setProximity(float p) {
        proximity = Math.max(0f, Math.min(1f, p));

        // تحديث القمة (Peak)
        if (proximity > peakProximity) {
            peakProximity = proximity;
        }

        // تحديث الـ Trail
        trailHistory[trailIndex] = proximity;
        trailIndex = (trailIndex + 1) % trailHistory.length;

        updateColor();
        invalidate();
    }

    public void setActive(boolean isActive) {
        active = isActive;
        if (isActive) startPulse();
        else stopPulse();
        invalidate();
    }

    /**
     * وميض احتفالي عند العثور على القطعة
     */
    public void flashFound() {
        flashMode = true;
        flashStartTime = System.currentTimeMillis();
        invalidate();
    }

    /**
     * إعادة تعيين القمة والأثر
     */
    public void resetTrailAndPeak() {
        peakProximity = 0f;
        for (int i = 0; i < trailHistory.length; i++) {
            trailHistory[i] = 0f;
        }
        trailIndex = 0;
        invalidate();
    }

    // ═══════════════════════════════════════════════════════════
    //  Color Update
    // ═══════════════════════════════════════════════════════════

    private void updateColor() {
        int color;
        if (proximity >= 0.75f)      color = 0xFF22C55E; // أخضر
        else if (proximity >= 0.5f)  color = 0xFFEAB308; // أصفر
        else if (proximity >= 0.25f) color = 0xFFF97316; // برتقالي
        else                          color = 0xFFEF4444; // أحمر

        arcPaint.setColor(color);
        textPaint.setColor(color);
    }

    // ═══════════════════════════════════════════════════════════
    //  Animator
    // ═══════════════════════════════════════════════════════════

    private void startPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) return;

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            pulsePhase = (float) a.getAnimatedValue();
            sweepAngle = (sweepAngle + 8f) % 360f; // دوران الرادار
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
        sweepAngle = 0f;
    }

    // ═══════════════════════════════════════════════════════════
    //  Drawing
    // ═══════════════════════════════════════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;

        float stroke = STROKE_WIDTH_DP * density;
        float radius = (size - stroke) / 2f - 8;
        float innerRadius = radius - stroke;

        // ── 1) الخلفية الداكنة ──
        canvas.drawCircle(cx, cy, radius - stroke / 2f, bgPaint);

        // ── 2) قوس الخلفية ──
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arcRect, 135, 270, false, arcBgPaint);

        // ── 3) قوس التقدم ──
        if (proximity > 0) {
            canvas.drawArc(arcRect, 135, 270 * proximity, false, arcPaint);
        }

        // ── 4) نبض داخلي (Geiger) ──
        if (active && !flashMode) {
            float pulseRadius = innerRadius * (0.7f + 0.3f * pulsePhase);
            int alpha = (int) (60 * (1 - pulsePhase));
            innerPaint.setColor(arcPaint.getColor());
            innerPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, pulseRadius, innerPaint);
        }

        // ── 5) دائرة داخلية ──
        innerPaint.setColor(0xFF1E293B);
        innerPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, innerRadius - 6, innerPaint);

        // ── 6) الرادار الدوّار ──
        if (active && !flashMode) {
            canvas.drawArc(arcRect, sweepAngle + 135, 40, false, sweepPaint);
        }

        // ── 7) الأثر (Trail) ──
        drawTrail(canvas, cx, cy, radius, stroke);

        // ── 8) علامة أفضل قراءة (Peak) ──
        if (peakProximity > 0.1f && !flashMode) {
            float angle = 135 + (270 * peakProximity);
            float rad = (float) Math.toRadians(angle);
            float x = cx + (float) ((radius + stroke * 0.6f) * Math.cos(rad));
            float y = cy + (float) ((radius + stroke * 0.6f) * Math.sin(rad));
            canvas.drawCircle(x, y, 5 * density, peakPaint);
        }

        // ── 9) وميض احتفالي عند العثور ──
        if (flashMode) {
            long elapsed = System.currentTimeMillis() - flashStartTime;
            if (elapsed < FLASH_DURATION_MS) {
                float t = elapsed / (float) FLASH_DURATION_MS;
                int alpha = (int) (220 * (1 - t));
                innerPaint.setColor(0xFF22C55E);
                innerPaint.setAlpha(alpha);
                canvas.drawCircle(cx, cy, innerRadius, innerPaint);
                invalidate(); // استمر بالرسم للحركة
            } else {
                flashMode = false;
                resetTrailAndPeak();
            }
        }

        // ── 10) النسبة الرقمية ──
        textPaint.setTextSize(innerRadius * 0.55f);
        String pct = String.format(java.util.Locale.US, "%d%%", (int)(proximity * 100));
        canvas.drawText(pct, cx, cy + innerRadius * 0.15f, textPaint);

        // ── 11) التسمية النصية ──
        labelPaint.setTextSize(innerRadius * 0.18f);
        String label = proximity >= 0.75f ? "ملاصق"
                : proximity >= 0.5f ? "قريب"
                : proximity >= 0.25f ? "متوسط"
                : "بعيد";
        canvas.drawText(label, cx, cy + innerRadius * 0.55f, labelPaint);
    }

    private void drawTrail(Canvas canvas, float cx, float cy, float radius, float stroke) {
        int baseColor = arcPaint.getColor();

        for (int i = 0; i < trailHistory.length; i++) {
            int idx = (trailIndex + i) % trailHistory.length;
            float p = trailHistory[idx];
            if (p <= 0.01f) continue;

            // شفافية تتدرج حسب قِدَم القراءة
            float age = i / (float) trailHistory.length;
            int alpha = (int) (200 * (1 - age));

            trailPaint.setColor((baseColor & 0x00FFFFFF) | (alpha << 24));

            float angle = 135 + (270 * p);
            float rad = (float) Math.toRadians(angle);
            float x = cx + (float) ((radius - stroke * 1.4f) * Math.cos(rad));
            float y = cy + (float) ((radius - stroke * 1.4f) * Math.sin(rad));

            // حجم النقطة يتدرج أيضاً
            float dotRadius = (4 - 2 * age) * density;
            canvas.drawCircle(x, y, dotRadius, trailPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
    }
}
