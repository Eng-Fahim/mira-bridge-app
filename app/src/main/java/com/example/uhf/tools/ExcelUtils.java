package com.example.uhf.tools;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ✅ ExcelUtils — نسخة خفيفة تستخدم CSV بدلاً من Apache POI
 * 
 * ✅ المزايا:
 * - توفير ~25 MB من حجم APK
 * - لا مكتبات خارجية
 * - Excel يفتح ملفات CSV مباشرة
 * - دعم كامل للنصوص العربية (UTF-8 BOM)
 * 
 * ⚠️ العيب الوحيد:
 * - لا يدعم التنسيقات (ألوان، خطوط عريضة)
 * - لكن الكود الأصلي لم يستخدمها أصلاً
 */
public class ExcelUtils {
    public static String TAG = "ExcelUtils";

    private File csvFile = null;
    private boolean isFirstWrite = true;

    public ExcelUtils() {
    }

    /**
     * ✅ تحويل المسار إلى .csv
     */
    private String toCsvPath(File file) {
        String path = file.getAbsolutePath();
        if (path.toLowerCase().endsWith(".xlsx")) {
            return path.substring(0, path.length() - 5) + ".csv";
        } else if (path.toLowerCase().endsWith(".xls")) {
            return path.substring(0, path.length() - 4) + ".csv";
        }
        return path + ".csv";
    }

    /**
     * ✅ إنشاء ملف جديد مع العناوين
     */
    public void createExcel(File file, String[] head) {
        csvFile = new File(toCsvPath(file));
        isFirstWrite = true;

        Writer writer = null;
        try {
            // حذف الملف القديم
            if (csvFile.exists()) {
                csvFile.delete();
            }

            // كتابة العناوين
            writer = new OutputStreamWriter(
                    new FileOutputStream(csvFile),
                    StandardCharsets.UTF_8);

            // BOM لـ Excel العربي
            writer.write('\ufeff');

            // Headers
            for (int k = 0; k < head.length; k++) {
                if (k > 0) writer.write(',');
                writer.write(escapeCsv(head[k]));
            }
            writer.write('\n');

            writer.flush();
            Log.d(TAG, "✅ تم إنشاء ملف CSV: " + csvFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "خطأ في إنشاء الملف: " + e.getMessage());
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * ✅ كتابة صف واحد
     */
    public void writeToExcel(String[] args) {
        if (args == null || args.length == 0) {
            Log.w(TAG, "البيانات فارغة");
            return;
        }
        if (csvFile == null) {
            Log.e(TAG, "لم يتم إنشاء الملف أولاً");
            return;
        }

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(
                    new FileOutputStream(csvFile, true),
                    StandardCharsets.UTF_8);

            for (int k = 0; k < args.length; k++) {
                if (k > 0) writer.write(',');
                writer.write(escapeCsv(args[k]));
            }
            writer.write('\n');
            writer.flush();

            Log.d(TAG, "✅ تمت كتابة صف بنجاح");

        } catch (Exception e) {
            Log.e(TAG, "خطأ في كتابة الصف: " + e.getMessage());
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * ✅ كتابة عدة صفوف
     */
    public void writeToExcel(List<String[]> listData) {
        if (listData == null || listData.isEmpty()) {
            Log.w(TAG, "القائمة فارغة");
            return;
        }
        if (csvFile == null) {
            Log.e(TAG, "لم يتم إنشاء الملف أولاً");
            return;
        }

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(
                    new FileOutputStream(csvFile, true),
                    StandardCharsets.UTF_8);

            for (String[] args : listData) {
                if (args == null) continue;
                for (int k = 0; k < args.length; k++) {
                    if (k > 0) writer.write(',');
                    writer.write(escapeCsv(args[k]));
                }
                writer.write('\n');
            }
            writer.flush();

            Log.d(TAG, "✅ تمت كتابة " + listData.size() + " صفوف");

        } catch (Exception e) {
            Log.e(TAG, "خطأ في كتابة الصفوف: " + e.getMessage());
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * ✅ تهريب CSV
     */
    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
