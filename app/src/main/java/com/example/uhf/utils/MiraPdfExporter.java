package com.example.uhf.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;

import com.example.uhf.api.dto.CashFlowDto;
import com.example.uhf.api.dto.CashFlowItemDto;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraPdfExporter {

    public static File exportCashFlowToPdf(Context context, CashFlowDto data, String branchName) throws IOException {
        PdfDocument document = new PdfDocument();
        
        // إعدادات الصفحة (A4 قياسي)
        int pageWidth = 595;
        int pageHeight = 842;
        int margin = 40;
        
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        // إعداد الفُرَش (Paints)
        Paint paint = new Paint();
        Paint titlePaint = new Paint();
        Paint textPaint = new Paint();
        Paint linePaint = new Paint();

        // إعداد الخطوط
        Typeface arabicTypeface = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface boldTypeface = Typeface.create("sans-serif", Typeface.BOLD);
        
        titlePaint.setTypeface(boldTypeface);
        titlePaint.setTextSize(20);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextAlign(Paint.Align.CENTER);

        textPaint.setTypeface(arabicTypeface);
        textPaint.setTextSize(12);
        textPaint.setColor(Color.DKGRAY);

        linePaint.setColor(Color.LTGRAY);
        linePaint.setStrokeWidth(1);

        // 1. رسم الترويسة (Header)
        canvas.drawText("MIRA TECHNOLOGY", pageWidth / 2, margin, titlePaint);
        
        titlePaint.setTextSize(16);
        canvas.drawText("تقرير حركة الخزينة اليومية", pageWidth / 2, margin + 30, titlePaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("التاريخ: " + data.date, pageWidth - margin, margin + 60, textPaint);
        canvas.drawText("الفرع: " + (branchName != null ? branchName : "كل الفروع"), pageWidth - margin, margin + 80, textPaint);
        
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("تم الطباعة بواسطة: النظام", margin, margin + 60, textPaint);
        canvas.drawText("وقت الطباعة: " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date()), margin, margin + 80, textPaint);

        // خط فاصل
        canvas.drawLine(margin, margin + 95, pageWidth - margin, margin + 95, linePaint);

        // 2. رسم الجدول
        int y = margin + 120;
        int rowHeight = 25;

        // رأس الجدول
        Paint headerPaint = new Paint();
        headerPaint.setColor(Color.parseColor("#F1F5F9"));
        canvas.drawRect(margin, y - 15, pageWidth - margin, y + 10, headerPaint);
        
        textPaint.setTypeface(boldTypeface);
        textPaint.setTextSize(14);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("البيان", pageWidth - margin - 10, y, textPaint);
        canvas.drawText("المبلغ (YER)", pageWidth / 2, y, textPaint);
        
        y += rowHeight + 10;

        // --- قسم الداخل ---
        textPaint.setColor(Color.parseColor("#16A34A")); // أخضر
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("📥 الداخل (Inflow)", pageWidth - margin - 10, y, textPaint);
        y += rowHeight;

        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(arabicTypeface);
        for (CashFlowItemDto item : data.inflow) {
            canvas.drawText(item.label + " - " + item.account, pageWidth - margin - 10, y, textPaint);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format(Locale.US, "%,.2f", item.amount), pageWidth / 2, y, textPaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawLine(margin, y + 5, pageWidth - margin, y + 5, linePaint);
            y += rowHeight;
        }
        
        // إجمالي الداخل
        textPaint.setTypeface(boldTypeface);
        canvas.drawText("إجمالي الداخل", pageWidth - margin - 10, y, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.format(Locale.US, "%,.2f", data.total_inflow), pageWidth / 2, y, textPaint);
        y += rowHeight * 2;

        // --- قسم الخارج ---
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(Color.parseColor("#DC2626")); // أحمر
        canvas.drawText("📤 الخارج (Outflow)", pageWidth - margin - 10, y, textPaint);
        y += rowHeight;

        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(arabicTypeface);
        for (CashFlowItemDto item : data.outflow) {
            canvas.drawText(item.label + " - " + item.account, pageWidth - margin - 10, y, textPaint);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format(Locale.US, "%,.2f", item.amount), pageWidth / 2, y, textPaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawLine(margin, y + 5, pageWidth - margin, y + 5, linePaint);
            y += rowHeight;
        }
        
        // إجمالي الخارج
        textPaint.setTypeface(boldTypeface);
        canvas.drawText("إجمالي الخارج", pageWidth - margin - 10, y, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.format(Locale.US, "%,.2f", data.total_outflow), pageWidth / 2, y, textPaint);
        y += rowHeight * 2;

        // 3. صافي الحركة (الملخص)
        Paint netPaint = new Paint();
        netPaint.setColor(data.net_cash_flow >= 0 ? Color.parseColor("#F0FDF4") : Color.parseColor("#FEF2F2"));
        canvas.drawRect(margin, y - 20, pageWidth - margin, y + 20, netPaint);

        titlePaint.setTextSize(16);
        titlePaint.setColor(data.net_cash_flow >= 0 ? Color.parseColor("#16A34A") : Color.parseColor("#DC2626"));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("💰 صافي الحركة: " + String.format(Locale.US, "%,.2f YER", data.net_cash_flow), pageWidth / 2, y + 5, titlePaint);

        // 4. التوقيعات
        y = pageHeight - margin - 50;
        textPaint.setColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("توقيع المحاسب: _________________", margin, y, textPaint);
        
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("توقيع المدير: _________________", pageWidth - margin, y, textPaint);

        document.finishPage(page);

        // 5. حفظ الملف
        File pdfDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MiraReports");
        if (!pdfDir.exists()) pdfDir.mkdirs();

        String fileName = "CashFlow_" + data.date + "_" + System.currentTimeMillis() + ".pdf";
        File file = new File(pdfDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            document.writeTo(fos);
        } finally {
            document.close();
        }

        return file;
    }
}
