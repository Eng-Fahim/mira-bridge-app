package com.example.uhf.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class MiraSparklineView extends View {

    private Paint linePaint;
    private Paint fillPaint;
    private Path path;
    private float[] dataPoints = new float[]{};

    public MiraSparklineView(Context context) {
        super(context);
        init();
    }

    public MiraSparklineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MiraSparklineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setColor(0xFFF5D97A);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x33F5D97A);

        path = new Path();
    }

    public void setData(float[] points) {
        this.dataPoints = points;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (dataPoints == null || dataPoints.length < 2) return;

        float w = getWidth();
        float h = getHeight();
        float stepX = w / (dataPoints.length - 1);

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : dataPoints) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        float range = (max - min) == 0 ? 1 : (max - min);

        path.reset();
        for (int i = 0; i < dataPoints.length; i++) {
            float x = i * stepX;
            float y = h - ((dataPoints[i] - min) / range) * h;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }

        canvas.drawPath(path, linePaint);
    }
}
