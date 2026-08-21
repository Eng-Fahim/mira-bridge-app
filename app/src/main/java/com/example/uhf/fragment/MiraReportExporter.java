package com.example.uhf.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ✅ تصدير التقارير المالية إلى PDF
 */
public class MiraReportExporter {

    private static final String TAG = "MiraReportExporter";

    /**
     * ✅ تصدير تقرير نصي إلى PDF
     */
    public static File exportReportToPdf(Context context, String title, String content) {
        try {
            PdfDocument document = new PdfDocument();
            
            int pageWidth = 595; // A4 width in points
            int pageHeight = 842; // A4 height in points
            int margin = 40;
            
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                pageWidth, pageHeight, 1).create();
            
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            
            Paint titlePaint = new Paint();
            titlePaint.setColor(Color.parseColor("#1A237E"));
            titlePaint.setTextSize(18);
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            
            Paint contentPaint = new Paint();
            contentPaint.setColor(Color.BLACK);
            contentPaint.setTextSize(10);
            contentPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            
            // العنوان
            canvas.drawText(title, pageWidth / 2, margin + 20, titlePaint);
            
            // خط فاصل
            Paint linePaint = new Paint();
            linePaint.setColor(Color.parseColor("#1A237E"));
            linePaint.setStrokeWidth(1);
            canvas.drawLine(margin, margin + 30, pageWidth - margin, margin + 30, linePaint);
            
            // التاريخ
            Paint datePaint = new Paint();
            datePaint.setColor(Color.GRAY);
            datePaint.setTextSize(8);
            String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
            canvas.drawText(dateStr, margin, margin + 45, datePaint);
            
            // المحتوى
            String[] lines = content.split("\n");
            float y = margin + 65;
            float lineHeight = 14;
            
            for (String line : lines) {
                if (y > pageHeight - margin) {
                    // صفحة جديدة
                    document.finishPage(page);
                    pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
                    page = document.startPage(pageInfo);
                    canvas = page.getCanvas();
                    y = margin + 20;
                }
                canvas.drawText(line, margin, y, contentPaint);
                y += lineHeight;
            }
            
            document.finishPage(page);
            
            // حفظ الملف
            File dir = new File(context.getExternalFilesDir(null), "reports");
            if (!dir.exists()) dir.mkdirs();
            
            String fileName = "Report_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
            File file = new File(dir, fileName);
            
            FileOutputStream fos = new FileOutputStream(file);
            document.writeTo(fos);
            document.close();
            fos.close();
            
            return file;
            
        } catch (Exception e) {
            Log.e(TAG, "PDF export error: " + e.getMessage());
            return null;
        }
    }
}
