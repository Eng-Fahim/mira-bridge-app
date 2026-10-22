package com.example.uhf.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.example.uhf.api.dto.TrialBalanceDto;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class TrialBalanceCsvExporter {

    public static void exportAndShare(Context context, List<TrialBalanceDto.TrialBalanceRow> rows, 
                                      String fromDate, String toDate) {
        if (rows == null || rows.isEmpty()) return;

        StringBuilder csv = new StringBuilder();
        // إضافة BOM لملفات الإكسيل لقراءة اللغة العربية بشكل صحيح
        csv.append('\ufeff');

        // العناوين
        csv.append("الكود,اسم الحساب,أول المدة (مدين),أول المدة (دائن),حركة الفترة (مدين),حركة الفترة (دائن),آخر المدة (مدين),آخر المدة (دائن),ذهب مدين (جرام),ذهب دائن (جرام),رصيد الذهب (جرام)\n");

        // البيانات
        for (TrialBalanceDto.TrialBalanceRow r : rows) {
            csv.append(escapeCsv(r.accountCode)).append(",")
               .append(escapeCsv(r.accountNameAr)).append(",")
               .append(String.format(Locale.US, "%.2f", r.openingDebit)).append(",")
               .append(String.format(Locale.US, "%.2f", r.openingCredit)).append(",")
               .append(String.format(Locale.US, "%.2f", r.periodDebit)).append(",")
               .append(String.format(Locale.US, "%.2f", r.periodCredit)).append(",")
               .append(String.format(Locale.US, "%.2f", r.closingDebit)).append(",")
               .append(String.format(Locale.US, "%.2f", r.closingCredit)).append(",")
               .append(String.format(Locale.US, "%.3f", r.goldDebit)).append(",")
               .append(String.format(Locale.US, "%.3f", r.goldCredit)).append(",")
               .append(String.format(Locale.US, "%.3f", r.goldBalance)).append("\n");
        }

        try {
            // حفظ الملف في مجلد الكاش
            File cacheDir = context.getCacheDir();
            String fileName = "TrialBalance_" + fromDate + "_to_" + toDate + ".csv";
            File file = new File(cacheDir, fileName);

            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write(csv.toString());
            writer.close();
            fos.close();

            // مشاركة الملف
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "ميزان المراجعة " + fromDate + " إلى " + toDate);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, "مشاركة ملف الإكسيل عبر..."));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
