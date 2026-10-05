package com.example.uhf.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.example.uhf.adapter.LedgerRowAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ✅ مُصدِّر CSV احترافي لدفتر الأستاذ
 * - UTF-8 BOM ليفتح Excel العربي بشكل صحيح
 * - فاصل الآلاف (كـ Excel يقرأه صحيحاً)
 * - ترويسة كاملة
 */
public class LedgerCsvExporter {

    private static final String TAG = "LedgerCsvExporter";

    public static Uri exportAndShare(Context context,
                                     List<LedgerRowAdapter.LedgerRow> rows,
                                     String accountCode,
                                     String accountName,
                                     String fromDate,
                                     String toDate,
                                     double openingBalance,
                                     double closingBalance) {
        if (rows == null || rows.isEmpty()) {
            Log.w(TAG, "No rows to export");
            return null;
        }

        try {
            File file = generateCsvFile(context, rows, accountCode, accountName,
                fromDate, toDate, openingBalance, closingBalance);
            if (file == null) return null;

            Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                "دفتر الأستاذ — " + accountName);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            context.startActivity(Intent.createChooser(shareIntent,
                "مشاركة ملف CSV"));

            return uri;

        } catch (Exception e) {
            Log.e(TAG, "exportAndShare error", e);
            return null;
        }
    }

   private static File generateCsvFile(Context context,
                                    List<LedgerRowAdapter.LedgerRow> rows,
                                    String accountCode,
                                    String accountName,
                                    String fromDate,
                                    String toDate,
                                    double openingBalance,
                                    double closingBalance) {
    try {
        File dir = new File(context.getExternalFilesDir(null), "exports");
        if (!dir.exists() && !dir.mkdirs()) return null;

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(new Date());
        String safeCode = (accountCode != null)
            ? accountCode.replaceAll("[^0-9A-Za-z_-]", "_")
            : "account";

        File file = new File(dir, "ledger_" + safeCode + "_" + ts + ".csv");

        try (FileOutputStream fos = new FileOutputStream(file)) {
            // ✅ UTF-8 BOM لـ Excel العربي
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            try (OutputStreamWriter w = new OutputStreamWriter(
                    fos, StandardCharsets.UTF_8)) {

                // ✅ تلميح Excel: استخدم الفاصلة كفاصل
                w.write("sep=,\n");

                // ─── ترويسة ───
                w.write(esc("MIRA Trust Platform — دفتر الأستاذ العام") + "\n\n");

                w.write(esc("الحساب") + "," + esc(accountCode + " - " + accountName) + "\n");
                w.write(esc("من") + "," + forceText(fromDate) + "\n");
                w.write(esc("إلى") + "," + forceText(toDate) + "\n");
                w.write(esc("الرصيد الافتتاحي") + "," + num(openingBalance) + "\n");
                w.write(esc("الرصيد الختامي")   + "," + num(closingBalance) + "\n");
                w.write("\n");

                // ─── الأعمدة ───
                w.write(
                    esc("التاريخ") + "," +
                    esc("رقم القيد") + "," +
                    esc("البيان")   + "," +
                    esc("المرجع")   + "," +
                    esc("مدين")     + "," +
                    esc("دائن")     + "," +
                    esc("الرصيد")   + "," +
                    esc("الذهب (g)") + "\n"
                );

                // ─── السطور ───
                double totalDebit = 0, totalCredit = 0;
                for (LedgerRowAdapter.LedgerRow r : rows) {
                    w.write(
                        forceText(r.date) + "," +                    // ✅ تاريخ كنص
                        esc(r.entryNumber) + "," +
                        esc(r.description) + "," +
                        esc(r.reference) + "," +
                        num(r.baseDebit) + "," +
                        num(r.baseCredit) + "," +
                        num(r.runningBalance) + "," +
                        numGold(r.runningGold) + "\n"
                    );
                    totalDebit  += r.baseDebit;
                    totalCredit += r.baseCredit;
                }

                // ─── الإجماليات ───
                w.write("\n");
                w.write(esc("الإجمالي") + ",,," +
                        num(totalDebit) + "," + num(totalCredit) + ",,\n");
                w.write("\n");
                w.write(esc("تم الإنشاء بواسطة MIRA Trust Platform") + "," +
                        esc(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(new Date())) + "\n");

                w.flush();
            }
        }

        Log.d(TAG, "✅ CSV saved: " + file.getAbsolutePath());
        return file;

    } catch (Exception e) {
        Log.e(TAG, "generateCsvFile error", e);
        return null;
    }
}

// ═══════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════

/** رقم بفواصل آلاف — Excel يفهمها كأرقام */
private static String num(double v) {
    return String.format(Locale.US, "%.2f", v);
}

private static String numGold(double v) {
    return String.format(Locale.US, "%.3f", v);
}

/** escape للـ CSV */
private static String esc(String s) {
    if (s == null) return "\"\"";
    String clean = s.replace("\"", "\"\"")
                     .replace("\n", " ")
                     .replace("\r", " ");
    return "\"" + clean + "\"";
}

/** ✅ فرض النص — يمنع Excel من تحويل التاريخ */
private static String forceText(String s) {
    if (s == null) return "\"\"";
    String clean = s.replace("\"", "\"\"");
    // مسافة بادئة → Excel يعرضها كنص بدون تحويل
    return "\" " + clean + "\"";
}
}
