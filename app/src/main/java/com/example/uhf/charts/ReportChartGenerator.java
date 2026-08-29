package com.example.uhf.charts;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.List;

/**
 * ✅ مولد الرسوم البيانية
 */
public class ReportChartGenerator {
    
    /**
     * ✅ رسم بياني شريطي
     */
    public static Bitmap generateBarChart(List<ChartData> dataList, String title, 
                                          int width, int height, boolean isArabic) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        // خلفية بيضاء
        canvas.drawColor(Color.WHITE);
        
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        
        // رسم العنوان
        paint.setTextSize(36);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setColor(Color.parseColor("#1a237e"));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(title, width / 2, 50, paint);
        
        // حساب أبعاد الرسم
        int chartLeft = 80;
        int chartRight = width - 40;
        int chartTop = 120;
        int chartBottom = height - 80;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;
        
        // إيجاد القيمة القصوى
        float maxValue = 0;
        for (ChartData data : dataList) {
            maxValue = Math.max(maxValue, data.value);
        }
        if (maxValue == 0) maxValue = 1;
        
        // رسم الأعمدة
        float barWidth = chartWidth / (float) dataList.size() * 0.7f;
        float barSpacing = chartWidth / (float) dataList.size();
        
        for (int i = 0; i < dataList.size(); i++) {
            ChartData data = dataList.get(i);
            float barHeight = (data.value / maxValue) * chartHeight;
            float left = chartLeft + (i * barSpacing) + (barSpacing - barWidth) / 2;
            float top = chartBottom - barHeight;
            float right = left + barWidth;
            
            // تدرج لوني
            Paint barPaint = new Paint();
            barPaint.setAntiAlias(true);
            LinearGradient gradient = new LinearGradient(
                left, top, left, chartBottom,
                data.color, Color.argb(100, Color.red(data.color), 
                                       Color.green(data.color), Color.blue(data.color)),
                Shader.TileMode.CLAMP);
            barPaint.setShader(gradient);
            
            // رسم العمود
            canvas.drawRoundRect(new RectF(left, top, right, chartBottom), 8, 8, barPaint);
            
            // رسم القيمة
            Paint valuePaint = new Paint();
            valuePaint.setAntiAlias(true);
            valuePaint.setTextSize(28);
            valuePaint.setColor(Color.DKGRAY);
            valuePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format("%.1f", data.value), 
                          (left + right) / 2, top - 15, valuePaint);
            
            // رسم التسمية
            Paint labelPaint = new Paint();
            labelPaint.setAntiAlias(true);
            labelPaint.setTextSize(24);
            labelPaint.setColor(Color.GRAY);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            
            String label = data.label;
            if (label.length() > 15) {
                label = label.substring(0, 15) + "...";
            }
            canvas.drawText(label, (left + right) / 2, chartBottom + 40, labelPaint);
        }
        
        // رسم المحاور
        Paint axisPaint = new Paint();
        axisPaint.setAntiAlias(true);
        axisPaint.setStrokeWidth(2);
        axisPaint.setColor(Color.LTGRAY);
        
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint);
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint);
        
        return bitmap;
    }
    
    /**
     * ✅ رسم بياني دائري
     */
    public static Bitmap generatePieChart(List<ChartData> dataList, String title,
                                          int width, int height, boolean isArabic) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        canvas.drawColor(Color.WHITE);
        
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        
        // رسم العنوان
        paint.setTextSize(36);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setColor(Color.parseColor("#1a237e"));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(title, width / 2, 50, paint);
        
        // حساب مجموع القيم
        float total = 0;
        for (ChartData data : dataList) {
            total += data.value;
        }
        if (total == 0) total = 1;
        
        // رسم الدائرة
        int centerX = width / 2;
        int centerY = height / 2 + 30;
        int radius = Math.min(width, height) / 2 - 120;
        
        float startAngle = -90;
        RectF oval = new RectF(centerX - radius, centerY - radius, 
                               centerX + radius, centerY + radius);
        
        for (ChartData data : dataList) {
            float sweepAngle = (data.value / total) * 360;
            
            Paint piePaint = new Paint();
            piePaint.setAntiAlias(true);
            piePaint.setColor(data.color);
            piePaint.setStyle(Paint.Style.FILL);
            
            canvas.drawArc(oval, startAngle, sweepAngle, true, piePaint);
            
            // رسم النسبة المئوية
            if (sweepAngle > 15) {
                float midAngle = startAngle + sweepAngle / 2;
                float labelRadius = radius * 0.7f;
                float labelX = centerX + (float) Math.cos(Math.toRadians(midAngle)) * labelRadius;
                float labelY = centerY + (float) Math.sin(Math.toRadians(midAngle)) * labelRadius;
                
                Paint percentPaint = new Paint();
                percentPaint.setAntiAlias(true);
                percentPaint.setTextSize(26);
                percentPaint.setColor(Color.WHITE);
                percentPaint.setTextAlign(Paint.Align.CENTER);
                percentPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                
                String percent = String.format("%.1f%%", (data.value / total) * 100);
                canvas.drawText(percent, labelX, labelY, percentPaint);
            }
            
            startAngle += sweepAngle;
        }
        
        // رسم وسيلة الإيضاح
        int legendY = centerY + radius + 40;
        int legendX = 60;
        
        for (int i = 0; i < dataList.size(); i++) {
            ChartData data = dataList.get(i);
            int rowY = legendY + (i * 45);
            
            Paint legendPaint = new Paint();
            legendPaint.setAntiAlias(true);
            legendPaint.setColor(data.color);
            legendPaint.setStyle(Paint.Style.FILL);
            
            canvas.drawRect(legendX, rowY, legendX + 25, rowY + 25, legendPaint);
            
            Paint labelPaint = new Paint();
            labelPaint.setAntiAlias(true);
            labelPaint.setTextSize(24);
            labelPaint.setColor(Color.DKGRAY);
            labelPaint.setTextAlign(Paint.Align.LEFT);
            
            String label = data.label + " (" + String.format("%.1f%%", (data.value / total) * 100) + ")";
            canvas.drawText(label, legendX + 40, rowY + 20, labelPaint);
        }
        
        return bitmap;
    }
    
    /**
     * ✅ عنصر بيانات الرسم البياني
     */
    public static class ChartData {
        public String label;
        public float value;
        public int color;
        
        public ChartData(String label, float value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }
}
