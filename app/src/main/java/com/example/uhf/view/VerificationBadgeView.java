package com.example.uhf.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 🟢 شارة التحقق - تظهر مستوى المطابقة
 * 
 * ✅✅✅ دائرة خضراء كاملة = مطابقة ثلاثية
 * ✅✅  دائرة صفراء = مطابقة ثنائية
 * ✅   دائرة برتقالية = مطابقة أحادية
 * 🚨  دائرة حمراء = عدم تطابق
 */
public class VerificationBadgeView extends View {

    private Paint circlePaint;
    private Paint textPaint;
    private Paint arcPaint;
    private RectF rectF;

    private int score = 0;       // 0-100
    private String label = "";
    private int badgeColor = Color.parseColor("#EF4444");

    public VerificationBadgeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(8f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(32f);
        textPaint.setFakeBoldText(true);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(8f);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        rectF = new RectF();
    }

    public void setVerification(int score, String label) {
        this.score = score;
        this.label = label;

        if (score >= 90) badgeColor = Color.parseColor("#22C55E");      // أخضر
        else if (score >= 50) badgeColor = Color.parseColor("#F59E0B"); // برتقالي
        else badgeColor = Color.parseColor("#EF4444");                  // أحمر

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        int cx = width / 2;
        int cy = height / 2;
        int radius = Math.min(cx, cy) - 12;

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 🟢 خلفية
        circlePaint.setColor(Color.parseColor("#1A1E3A"));
        circlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, circlePaint);

        // 🟢 قوس التقدم
        arcPaint.setColor(badgeColor);
        float sweepAngle = (score / 100f) * 360f;
        canvas.drawArc(rectF, -90, sweepAngle, false, arcPaint);

        // 🟢 النص
        textPaint.setColor(badgeColor);
        canvas.drawText(label, cx, cy + 10, textPaint);
    }
}
