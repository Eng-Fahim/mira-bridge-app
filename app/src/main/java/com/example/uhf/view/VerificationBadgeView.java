package com.example.uhf.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
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

    // 🎨 Constants للألوان لتقليل الاستهلاك ولرفع الأداء
    private static final int COLOR_BG = 0xFF1A1E3A;
    private static final int COLOR_GREEN = 0xFF22C55E;  // مطابقة عالية جداً
    private static final int COLOR_ORANGE = 0xFFF59E0B; // مطابقة متوسطة
    private static final int COLOR_RED = 0xFFEF4444;    // عدم مطابقة / تفاوت

    private Paint circlePaint;
    private Paint textPaint;
    private Paint arcPaint;
    private RectF rectF;

    private int score = 0;       // 0-100
    private String label = "";
    private int badgeColor = COLOR_RED;

    public VerificationBadgeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VerificationBadgeView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        float strokeWidthPx = dpToPx(context, 3); // 3dp stroke
        float textSizePx = spToPx(context, 12);    // 12sp text

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(textSizePx);
        textPaint.setFakeBoldText(true);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidthPx);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        rectF = new RectF();
    }

    public void setVerification(int score, String label) {
        this.score = Math.max(0, Math.min(100, score)); // Clamp between 0-100
        this.label = label != null ? label : "";

        if (this.score >= 90) {
            badgeColor = COLOR_GREEN;
        } else if (this.score >= 50) {
            badgeColor = COLOR_ORANGE;
        } else {
            badgeColor = COLOR_RED;
        }

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        int cx = width / 2;
        int cy = height / 2;
        float padding = arcPaint.getStrokeWidth() / 2f + 4f;
        float radius = Math.min(cx, cy) - padding;

        if (radius <= 0) return;

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 🟢 خلفية الشارة
        circlePaint.setColor(COLOR_BG);
        canvas.drawCircle(cx, cy, radius, circlePaint);

        // 🟢 قوس نسبة التحقق / التقدم
        arcPaint.setColor(badgeColor);
        float sweepAngle = (score / 100f) * 360f;
        canvas.drawArc(rectF, -90, sweepAngle, false, arcPaint);

        // 🟢 توسيط النص دقيقاً في المنتصف (Exact Center Baseline)
        textPaint.setColor(badgeColor);
        float textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText(label, cx, textY, textPaint);
    }

    // 🛠️ Helpers لتحويل الأحجام للتجاوب مع كافة الشاشات
    private float dpToPx(Context context, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    private float spToPx(Context context, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics());
    }
}
