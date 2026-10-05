package com.example.uhf.utils;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import com.example.uhf.adapter.LedgerRowAdapter;

public class LedgerPdfExporter {

    private static final String TAG = "LedgerPdfExporter";
    private static final int PAGE_WIDTH = 595;   // A4 @ 72dpi
    private static final int PAGE_HEIGHT = 842;

    public static Uri exportAndShare(Context context,
                                     List<LedgerRowAdapter.LedgerRow> rows,
                                     String accountCode,
                                     String accountName,
                                     String fromDate,
                                     String toDate) {
        try {
            PdfDocument doc = new PdfDocument();
            Paint paint = new Paint();
            paint.setAntiAlias(true);

            int rowsPerPage = 30;
            int totalPages = (int) Math.ceil(rows.size() / (double) rowsPerPage);

            for (int p = 0; p < totalPages; p++) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo
                    .Builder(PAGE_WIDTH, PAGE_HEIGHT, p + 1).create();
                PdfDocument.Page page = doc.startPage(info);
                Canvas c = page.getCanvas();

                paint.setColor(Color.BLACK);
                paint.setTextSize(14);
                paint.setFakeBoldText(true);
                c.drawText("دفتر الأستاذ — " + accountCode + " " + accountName,
                    20, 30, paint);

                paint.setTextSize(10);
                paint.setFakeBoldText(false);
                c.drawText("من " + fromDate + " إلى " + toDate, 20, 50, paint);

                // رأس الجدول
                int y = 80;
                paint.setFakeBoldText(true);
                c.drawText("التاريخ", 20, y, paint);
                c.drawText("البيان", 100, y, paint);
                c.drawText("مدين", 350, y, paint);
                c.drawText("دائن", 420, y, paint);
                c.drawText("الرصيد", 490, y, paint);
                y += 15;

                paint.setFakeBoldText(false);
                int start = p * rowsPerPage;
                int end = Math.min(start + rowsPerPage, rows.size());

                for (int i = start; i < end; i++) {
                    LedgerRowAdapter.LedgerRow r = rows.get(i);
                    c.drawText(safe(r.date), 20, y, paint);
                    c.drawText(truncate(safe(r.description), 40), 100, y, paint);
                    c.drawText(r.baseDebit > 0 ? fmt(r.baseDebit) : "—", 350, y, paint);
                    c.drawText(r.baseCredit > 0 ? fmt(r.baseCredit) : "—", 420, y, paint);
                    c.drawText(fmt(r.runningBalance), 490, y, paint);
                    y += 15;
                }

                doc.finishPage(page);
            }

            File dir = new File(context.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) return null;

            File file = new File(dir, "ledger_" + accountCode + "_"
                + System.currentTimeMillis() + ".pdf");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                doc.writeTo(fos);
            }
            doc.close();

            Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", file);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, "طباعة/مشاركة PDF"));

            return uri;

        } catch (Exception e) {
            Log.e(TAG, "exportAndShare error", e);
            return null;
        }
    }

    private static String safe(String s) { return s != null ? s : ""; }
    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.2f", v); }
    private static String truncate(String s, int len) {
        return s.length() > len ? s.substring(0, len) + "…" : s;
    }
}
