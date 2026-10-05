package com.example.uhf.utils;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.example.uhf.api.dto.JournalEntryDto;
import com.example.uhf.api.dto.JournalLineDto;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ✅ مُصدِّر PDF احترافي لتفاصيل القيد
 * - ترويسة كاملة
 * - صندوق إجماليات
 * - جدول سطور
 * - تذييل وترقيم صفحات
 */
public class JournalPdfExporter {

    private static final String TAG = "JournalPdfExporter";

    // A4 @ 72dpi
    private static final int PAGE_WIDTH  = 595;
    private static final int PAGE_HEIGHT = 842;

    private static final int MARGIN_LEFT   = 40;
    private static final int MARGIN_RIGHT  = 40;
    private static final int MARGIN_TOP    = 60;
    private static final int MARGIN_BOTTOM = 60;

    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_ROW = 26;

    private static final int COLOR_PRIMARY = 0xFF2C3E50;
    private static final int COLOR_GOLD    = 0xFFD4AF37;
    private static final int COLOR_DEBIT   = 0xFF3498DB;
    private static final int COLOR_CREDIT  = 0xFFE74C3C;
    private static final int COLOR_GRAY    = 0xFF7F8C8D;
    private static final int COLOR_LIGHT   = 0xFFECF0F1;
    private static final int COLOR_ROW_ALT = 0xFFF9FAFB;

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    public static Uri exportAndShare(Context context,
                                     JournalEntryDto entry,
                                     List<JournalLineDto> lines) {
        try {
            if (entry == null) {
                Log.e(TAG, "Entry is null");
                return null;
            }

            PdfDocument doc = new PdfDocument();
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setTypeface(Typeface.DEFAULT);

            List<JournalLineDto> safeLines = lines != null ? lines : new java.util.ArrayList<>();

            int rowsPerPage = 22;
            int totalPages = Math.max(1,
                (int) Math.ceil(safeLines.size() / (double) rowsPerPage));

            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH, PAGE_HEIGHT, pageNum + 1).create();
                PdfDocument.Page page = doc.startPage(info);
                Canvas c = page.getCanvas();

                int y = drawHeader(c, paint, entry);
                y = drawEntryMeta(c, paint, entry, y);
                y = drawLinesTable(c, paint, safeLines, pageNum, rowsPerPage, y);
                drawFooter(c, paint, pageNum + 1, totalPages);

                doc.finishPage(page);
            }

            // ─── حفظ الملف ───
            File dir = new File(context.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Cannot create exports dir");
                doc.close();
                return null;
            }

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String safeNumber = entry.entryNumber != null
                ? entry.entryNumber.replaceAll("[^A-Za-z0-9_-]", "_")
                : "entry";
            File file = new File(dir, "journal_" + safeNumber + "_" + ts + ".pdf");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                doc.writeTo(fos);
            }
            doc.close();

            // ─── مشاركة ───
            Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT,
                "قيد محاسبي " + entry.entryNumber);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(Intent.createChooser(intent,
                "طباعة / مشاركة PDF"));

            Log.d(TAG, "✅ PDF saved: " + file.getAbsolutePath());
            return uri;

        } catch (Exception e) {
            Log.e(TAG, "exportAndShare error", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Header
    // ═══════════════════════════════════════════════════════════
    private static int drawHeader(Canvas c, Paint p, JournalEntryDto entry) {
        int y = MARGIN_TOP;

        p.setColor(COLOR_PRIMARY);
        p.setTextSize(18);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("MIRA Trust Platform", PAGE_WIDTH / 2f, y, p);

        y += 22;

        p.setTextSize(11);
        p.setFakeBoldText(false);
        p.setColor(COLOR_GRAY);
        c.drawText("نظام التوثيق المالي للمجوهرات",
            PAGE_WIDTH / 2f, y, p);

        y += 12;

        p.setColor(COLOR_GOLD);
        p.setStrokeWidth(2);
        c.drawLine(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y, p);

        y += 25;

        p.setColor(COLOR_PRIMARY);
        p.setTextSize(15);
        p.setFakeBoldText(true);
        c.drawText("تفاصيل القيد المحاسبي",
            PAGE_WIDTH / 2f, y, p);

        return y + 25;
    }

    // ═══════════════════════════════════════════════════════════
    //  Meta
    // ═══════════════════════════════════════════════════════════
    private static int drawEntryMeta(Canvas c, Paint p,
                                     JournalEntryDto entry, int y) {
        int colW  = (PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT) / 2;
        int rightX = PAGE_WIDTH - MARGIN_RIGHT;
        int leftX  = MARGIN_LEFT;

        p.setTextAlign(Paint.Align.RIGHT);
        p.setFakeBoldText(false);
        p.setTextSize(10);

        // ─── السطر الأول ───
        p.setColor(COLOR_GRAY);
        c.drawText("رقم القيد:", rightX, y, p);
        p.setColor(COLOR_PRIMARY);
        p.setFakeBoldText(true);
        c.drawText(safe(entry.entryNumber), rightX - 80, y, p);

        p.setColor(COLOR_GRAY);
        p.setFakeBoldText(false);
        c.drawText("التاريخ:", rightX - colW, y, p);
        p.setColor(COLOR_PRIMARY);
        p.setFakeBoldText(true);
        c.drawText(safe(entry.entryDate), rightX - colW - 60, y, p);

        y += 18;

        // ─── السطر الثاني ───
        p.setColor(COLOR_GRAY);
        p.setFakeBoldText(false);
        c.drawText("الحالة:", rightX, y, p);
        p.setColor(entry.getStatusColor());
        p.setFakeBoldText(true);
        c.drawText(entry.getStatusLabelAr(), rightX - 80, y, p);

        p.setColor(COLOR_GRAY);
        p.setFakeBoldText(false);
        c.drawText("المرجع:", rightX - colW, y, p);
        p.setColor(COLOR_PRIMARY);
        p.setFakeBoldText(true);
        c.drawText(safe(entry.reference), rightX - colW - 60, y, p);

        y += 18;

        // ─── الوصف ───
        p.setColor(COLOR_GRAY);
        p.setFakeBoldText(false);
        c.drawText("البيان:", leftX, y, p);
        p.setColor(COLOR_PRIMARY);
        p.setFakeBoldText(true);
        c.drawText(truncate(safe(entry.description), 70), leftX + 60, y, p);

        y += 20;

        // ─── صندوق الإجماليات ───
        int boxH = 46;
        p.setColor(COLOR_LIGHT);
        c.drawRect(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y + boxH, p);

        int innerY = y + 16;
        int w = (PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT) / 3;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(11);

        p.setColor(COLOR_DEBIT);
        p.setFakeBoldText(true);
        c.drawText("إجمالي المدين", MARGIN_LEFT + w / 2f, innerY, p);
        p.setColor(COLOR_PRIMARY);
        c.drawText(fmt(entry.totalDebit), MARGIN_LEFT + w / 2f, innerY + 18, p);

        p.setColor(COLOR_CREDIT);
        p.setFakeBoldText(true);
        c.drawText("إجمالي الدائن", MARGIN_LEFT + w * 1.5f, innerY, p);
        p.setColor(COLOR_PRIMARY);
        c.drawText(fmt(entry.totalCredit), MARGIN_LEFT + w * 1.5f, innerY + 18, p);

        p.setColor(COLOR_GOLD);
        p.setFakeBoldText(true);
        c.drawText("صافي الذهب", MARGIN_LEFT + w * 2.5f, innerY, p);
        p.setColor(COLOR_PRIMARY);
        double gold = entry.getNetGold();
        c.drawText(String.format(Locale.US, "%.3f g", gold),
            MARGIN_LEFT + w * 2.5f, innerY + 18, p);

        return y + boxH + 15;
    }

    // ═══════════════════════════════════════════════════════════
    //  Lines Table
    // ═══════════════════════════════════════════════════════════
    private static int drawLinesTable(Canvas c, Paint p,
                                  List<JournalLineDto> lines,
                                  int pageNum, int rowsPerPage,
                                  int startY) {
    int y = startY;

    // ═══ رأس الجدول ═══
    p.setColor(COLOR_PRIMARY);
    c.drawRect(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT,
        y + HEADER_ROW, p);

    p.setColor(Color.WHITE);
    p.setTextSize(11);
    p.setFakeBoldText(true);

    // ✅ حدود الأعمدة الجديدة (مضبوطة يدوياً)
    // Account:  40 → 275   (235)
    // مدين:    275 → 375   (100)
    // دائن:    375 → 475   (100)
    // الذهب:   475 → 555   (80)
    int accountRight = 275;
    int debitRight   = 375;
    int creditRight  = 475;
    int goldRight    = 555;

    int pad = 6;

    // رأس مدين/دائن/الذهب — يمين
    p.setTextAlign(Paint.Align.RIGHT);
    c.drawText("مدين",  debitRight  - pad, y + 17, p);
    c.drawText("دائن",  creditRight - pad, y + 17, p);
    c.drawText("الذهب", goldRight   - pad, y + 17, p);

    // رأس الحساب — يمين
    c.drawText("الحساب", accountRight - pad, y + 17, p);

    y += HEADER_ROW;

    // ═══ السطور ═══
    int start = pageNum * rowsPerPage;
    int end   = Math.min(start + rowsPerPage, lines.size());

    p.setFakeBoldText(false);
    p.setTextSize(9);

    for (int i = start; i < end; i++) {
        JournalLineDto line = lines.get(i);

        // خلفية متناوبة
        if ((i - start) % 2 == 0) {
            p.setColor(COLOR_ROW_ALT);
            c.drawRect(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT,
                y + ROW_HEIGHT, p);
        }

        // ─── اسم الحساب ───
        p.setColor(COLOR_PRIMARY);
        p.setTextAlign(Paint.Align.RIGHT);
        String accName = truncate(line.getDisplayAccount(), 38);
        c.drawText(accName, accountRight - pad, y + 15, p);

        // ─── مدين ───
        p.setTextAlign(Paint.Align.RIGHT);
        if (line.baseDebit > 0.01) {
            p.setColor(COLOR_DEBIT);
            p.setFakeBoldText(true);
            c.drawText(fmt(line.baseDebit), debitRight - pad, y + 15, p);
        } else {
            p.setColor(COLOR_GRAY);
            p.setFakeBoldText(false);
            c.drawText("—", debitRight - pad, y + 15, p);
        }

        // ─── دائن ───
        if (line.baseCredit > 0.01) {
            p.setColor(COLOR_CREDIT);
            p.setFakeBoldText(true);
            c.drawText(fmt(line.baseCredit), creditRight - pad, y + 15, p);
        } else {
            p.setColor(COLOR_GRAY);
            p.setFakeBoldText(false);
            c.drawText("—", creditRight - pad, y + 15, p);
        }

        // ─── الذهب ───
        if (line.hasGold()) {
            p.setColor(COLOR_GOLD);
            p.setFakeBoldText(true);
            double g = line.getNetGold();
            String sign = g >= 0 ? "+" : "";
            c.drawText(sign + String.format(Locale.US, "%.3f", g) + "g",
                goldRight - pad, y + 15, p);
        }

        y += ROW_HEIGHT;
    }

    // خط سفلي
    p.setColor(COLOR_PRIMARY);
    p.setStrokeWidth(1);
    c.drawLine(MARGIN_LEFT, y, PAGE_WIDTH - MARGIN_RIGHT, y, p);

    return y + 15;
}
    // ═══════════════════════════════════════════════════════════
    //  Footer
    // ═══════════════════════════════════════════════════════════
    private static void drawFooter(Canvas c, Paint p, int page, int total) {
        int y = PAGE_HEIGHT - MARGIN_BOTTOM + 20;

        p.setColor(COLOR_GOLD);
        p.setStrokeWidth(1);
        c.drawLine(MARGIN_LEFT, y - 10, PAGE_WIDTH - MARGIN_RIGHT, y - 10, p);

        p.setTextSize(9);
        p.setColor(COLOR_GRAY);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.CENTER);

        c.drawText("MIRA Trust Platform  •  Jewelry Financial System",
            PAGE_WIDTH / 2f, y, p);

        y += 12;
        c.drawText("صفحة " + page + " من " + total, PAGE_WIDTH / 2f, y, p);
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════
    private static String safe(String s) {
        return (s != null && !s.isEmpty()) ? s : "—";
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
