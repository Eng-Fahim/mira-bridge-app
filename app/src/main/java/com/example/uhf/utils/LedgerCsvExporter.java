package com.example.uhf.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
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
 * ✅ مُصدِّر CSV لدفتر الأستاذ العام
 * - يدعم Excel (UTF-8 BOM)
 * - يشارك الملف عبر FileProvider
 */
public class LedgerCsvExporter {

    private static final String TAG = "LedgerCsvExporter";

    /**
     * @param context    السياق
     * @param rows       سطور دفتر الأستاذ
     * @param accountCode كود الحساب (لاسم الملف)
     * @param accountName اسم الحساب
     * @param fromDate   من تاريخ
     * @param toDate     إلى تاريخ
     * @return Uri ملف CSV أو null عند الفشل
     */
    public static Uri exportAndShare(Context context,
                                     List<LedgerRowAdapter.LedgerRow> rows,
                                     String accountCode,
                                     String accountName,
                                     String fromDate,
                                     String toDate,
                                     double openingBalance,
                                     double closingBalance) {
        if (rows == null || rows.isEmpty()) {
            Log.w(TAG, "لا توجد بيانات للتصدير");
            return null;
        }

        try {
            File file = generateCsvFile(context, rows, accountCode, accountName,
                fromDate, toDate, openingBalance, closingBalance);
            if (file == null) return null;

            Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file
            );

            // إطلاق Intent للمشاركة
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                "دفتر الأستاذ — " + accountName);
            shareIntent.putExtra(Intent.EXTRA_TEXT,
                "تصدير دفتر الأستاذ من " + fromDate + " إلى " + toDate);
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
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Cannot create exports directory");
                return null;
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.US).format(new Date());

            String safeCode = accountCode != null
                ? accountCode.replaceAll("[^0-9A-Za-z_-]", "_") : "account";

            File file = new File(dir,
                "ledger_" + safeCode + "_" + timestamp + ".csv");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                // ✅ UTF-8 BOM ليفتح Excel العربي بشكل صحيح
                fos.write(0xEF);
                fos.write(0xBB);
                fos.write(0xBF);

                try (OutputStreamWriter writer =
                         new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {

                    // ═══ رأس الملف ═══
                    writer.write("دفتر الأستاذ العام\n");
                    writer.write("الحساب," + escape(accountCode) + " - " + escape(accountName) + "\n");
                    writer.write("من," + fromDate + "\n");
                    writer.write("إلى," + toDate + "\n");
                    writer.write("الرصيد الافتتاحي," + openingBalance + "\n");
                    writer.write("الرصيد الختامي," + closingBalance + "\n");
                    writer.write("\n");

                    // ═══ الأعمدة ═══
                    writer.write("التاريخ,رقم القيد,البيان,المرجع,مدين,دائن,الرصيد,الذهب (جرام)\n");

                    // ═══ السطور ═══
                    for (LedgerRowAdapter.LedgerRow r : rows) {
                        writer.write(
                            escape(r.date) + "," +
                            escape(r.entryNumber) + "," +
                            escape(r.description) + "," +
                            escape(r.reference) + "," +
                            fmt(r.baseDebit) + "," +
                            fmt(r.baseCredit) + "," +
                            fmt(r.runningBalance) + "," +
                            fmt(r.runningGold) +
                            "\n"
                        );
                    }

                    // ═══ الإجماليات ═══
                    double totalDebit = 0, totalCredit = 0;
                    for (LedgerRowAdapter.LedgerRow r : rows) {
                        totalDebit  += r.baseDebit;
                        totalCredit += r.baseCredit;
                    }

                    writer.write("\n");
                    writer.write("الإجمالي,,,," + fmt(totalDebit) + ","
                        + fmt(totalCredit) + ",,\n");

                    writer.flush();
                }
            }

            Log.d(TAG, "✅ CSV saved: " + file.getAbsolutePath());
            return file;

        } catch (Exception e) {
            Log.e(TAG, "generateCsvFile error", e);
            return null;
        }
    }

    private static String fmt(double v) {
        // ✅ تنسيق بسيط (بدون فاصلة آلاف لتجنب مشاكل Excel)
        return String.format(Locale.US, "%.2f", v);
    }

    private static String escape(String s) {
        if (s == null) return "";
        // ✅ استبدال الفواصل والاقتباسات
        String clean = s.replace("\"", "\"\"")
                         .replace(",", "،")     // فاصلة عربية لتجنب كسر CSV
                         .replace("\n", " ")
                         .replace("\r", " ");
        return "\"" + clean + "\"";
    }
}
