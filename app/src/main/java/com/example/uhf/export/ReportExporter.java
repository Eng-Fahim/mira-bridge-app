package com.example.uhf.export;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * ✅ مصدر التقارير - يدعم CSV و PDF
 */
public class ReportExporter {
    private static final String TAG = "ReportExporter";
    
    /**
     * ✅ تصدير إلى CSV
     */
    public static File exportToCSV(Context context, JSONObject data, String reportType,
                                   String fromDate, String toDate, boolean isArabic) {
        try {
            StringBuilder csv = new StringBuilder();
            csv.append('\uFEFF'); // BOM للعربية
            
            // رأس الملف
            csv.append(isArabic ? "تقرير" : "Report").append(",")
               .append(reportType).append("\n");
            csv.append(isArabic ? "من" : "From").append(",").append(fromDate).append("\n");
            csv.append(isArabic ? "إلى" : "To").append(",").append(toDate).append("\n");
            csv.append(isArabic ? "تاريخ التصدير" : "Export Date").append(",")
               .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
               .append("\n\n");
            
            // استخراج البيانات
            JSONArray items = extractItemsArray(data);
            
            if (items != null && items.length() > 0) {
                // استخراج الأعمدة من أول عنصر
                JSONObject firstItem = items.optJSONObject(0);
                if (firstItem != null) {
                    List<String> columns = new ArrayList<>();
                    Iterator<String> keys = firstItem.keys();
                    while (keys.hasNext()) {
                        columns.add(keys.next());
                    }
                    
                    // كتابة رؤوس الأعمدة
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) csv.append(",");
                        csv.append(columns.get(i));
                    }
                    csv.append("\n");
                    
                    // كتابة البيانات
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item != null) {
                            for (int j = 0; j < columns.size(); j++) {
                                if (j > 0) csv.append(",");
                                String value = item.optString(columns.get(j), "");
                                value = sanitizeCSVValue(value);
                                csv.append(value);
                            }
                            csv.append("\n");
                        }
                    }
                }
            } else {
                // تصدير key-value
                csv.append(isArabic ? "المفتاح" : "Key").append(",")
                   .append(isArabic ? "القيمة" : "Value").append("\n");
                
                Iterator<String> keys = data.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = data.opt(key);
                    if (value != null && !key.equals("success") && !key.equals("timestamp")) {
                        csv.append(sanitizeCSVValue(key)).append(",")
                           .append(sanitizeCSVValue(value.toString())).append("\n");
                    }
                }
            }
            
            // حفظ الملف
            File exportDir = new File(context.getCacheDir(), "exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            
            String fileName = "MIRA_" + reportType + "_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
            File file = new File(exportDir, fileName);
            
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(csv.toString().getBytes("UTF-8"));
            fos.close();
            
            Log.d(TAG, "CSV exported: " + file.getAbsolutePath());
            return file;
            
        } catch (Exception e) {
            Log.e(TAG, "Error exporting CSV: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * ✅ تصدير إلى PDF
     */
    public static File exportToPDF(Context context, JSONObject data, String reportType,
                                   String fromDate, String toDate, boolean isArabic) {
        try {
            // إنشاء مستند PDF
            PdfDocument document = new PdfDocument();
            
            // إعدادات الصفحة A4
            int pageWidth = 595;
            int pageHeight = 842;
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            
            // رأس الصفحة
            paint.setColor(Color.parseColor("#1a237e"));
            paint.setTextSize(22);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            canvas.drawText("MIRA Jewelry Management System", 30, 40, paint);
            
            paint.setTextSize(16);
            paint.setColor(Color.DKGRAY);
            canvas.drawText("Report: " + reportType, 30, 70, paint);
            
            paint.setTextSize(12);
            paint.setColor(Color.GRAY);
            canvas.drawText("Period: " + fromDate + " to " + toDate, 30, 95, paint);
            canvas.drawText("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()), 
                          30, 115, paint);
            
            // خط فاصل
            paint.setColor(Color.LTGRAY);
            canvas.drawLine(30, 130, pageWidth - 30, 130, paint);
            
            // جدول البيانات
            int yPosition = 160;
            
            JSONArray items = extractItemsArray(data);
            
            if (items != null && items.length() > 0) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    
                    // فحص الحاجة لصفحة جديدة
                    if (yPosition > pageHeight - 50) {
                        document.finishPage(page);
                        pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
                        page = document.startPage(pageInfo);
                        canvas = page.getCanvas();
                        yPosition = 50;
                    }
                    
                    paint.setTextSize(11);
                    paint.setColor(Color.DKGRAY);
                    
                    Iterator<String> keys = item.keys();
                    int xPosition = 30;
                    
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = item.optString(key);
                        
                        String line = key + ": " + value;
                        canvas.drawText(line, xPosition, yPosition, paint);
                        yPosition += 18;
                    }
                    
                    yPosition += 10;
                }
            }
            
            // حفظ الملف
            document.finishPage(page);
            
            File exportDir = new File(context.getExternalFilesDir(null), "reports");
            if (!exportDir.exists()) exportDir.mkdirs();
            
            String fileName = "MIRA_" + reportType + "_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
            File file = new File(exportDir, fileName);
            
            FileOutputStream fos = new FileOutputStream(file);
            document.writeTo(fos);
            document.close();
            fos.close();
            
            Log.d(TAG, "PDF exported: " + file.getAbsolutePath());
            return file;
            
        } catch (Exception e) {
            Log.e(TAG, "Error exporting PDF: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * ✅ مشاركة ملف
     */
    public static void shareFile(Context context, File file, String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(context, 
                context.getPackageName() + ".fileprovider", file);
            
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            context.startActivity(Intent.createChooser(intent, "Share Report"));
            
        } catch (Exception e) {
            Log.e(TAG, "Error sharing file: " + e.getMessage(), e);
        }
    }
    
    /**
     * ✅ استخراج مصفوفة العناصر من البيانات
     */
    private static JSONArray extractItemsArray(JSONObject data) {
        if (data == null) return null;
        
        String[] possibleKeys = {
            "items", "data", "items_list", "invoices", "sales", "inventory",
            "movements", "transfers", "users", "gate_logs", "branches",
            "journal_entries", "reorder_items", "timeline", "gate_history"
        };
        
        // البحث المباشر
        for (String key : possibleKeys) {
            JSONArray arr = data.optJSONArray(key);
            if (arr != null && arr.length() > 0) return arr;
        }
        
        // البحث في كائن data المتداخل
        JSONObject dataObj = data.optJSONObject("data");
        if (dataObj != null) {
            for (String key : possibleKeys) {
                JSONArray arr = dataObj.optJSONArray(key);
                if (arr != null && arr.length() > 0) return arr;
            }
        }
        
        return null;
    }
    
    /**
     * ✅ تنظيف قيمة CSV
     */
    private static String sanitizeCSVValue(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "'").replace(",", "،")
                          .replace("\n", " ").replace("\r", " ") + "\"";
    }
}
