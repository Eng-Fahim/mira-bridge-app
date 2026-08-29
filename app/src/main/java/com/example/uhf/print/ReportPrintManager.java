package com.example.uhf.print;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;  // ✅ إضافة هذا الاستيراد
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * ✅ مدير الطباعة الاحترافية
 */
public class ReportPrintManager {
    private static final String TAG = "ReportPrintManager";
    
    /**
     * ✅ طباعة تقرير HTML
     */
    public static void printHtmlReport(Context context, String htmlContent, String jobName) {
        try {
            WebView webView = new WebView(context);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    PrintManager printManager = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null) {
                        PrintDocumentAdapter printAdapter = view.createPrintDocumentAdapter(jobName);
                        PrintAttributes attributes = new PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                .build();
                        printManager.print(jobName, printAdapter, attributes);
                    }
                }
            });
            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
            
        } catch (Exception e) {
            Log.e(TAG, "Print HTML error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ إنشاء HTML احترافي للتقرير
     */
    public static String generateReportHtml(JSONObject data, String reportType, 
                                           String orgName, String branchName,
                                           String fromDate, String toDate, 
                                           boolean isArabic) {
        StringBuilder html = new StringBuilder();
        String direction = isArabic ? "rtl" : "ltr";
        String textAlign = isArabic ? "right" : "left";
        
        html.append("<!DOCTYPE html><html dir='").append(direction).append("'><head><meta charset='utf-8'>")
            .append("<style>")
            .append("@page { size: A4; margin: 15mm; }")
            .append("body { font-family: 'Segoe UI', Tahoma, Arial, sans-serif; direction: ").append(direction)
            .append("; text-align: ").append(textAlign).append("; margin: 0; padding: 20px; color: #1a1a2e; }")
            .append(".header { text-align: center; border-bottom: 3px solid #1a237e; padding-bottom: 15px; margin-bottom: 20px; }")
            .append(".org-name { font-size: 24px; font-weight: bold; color: #1a237e; margin: 0; }")
            .append(".branch-name { font-size: 16px; color: #555; margin: 5px 0 0 0; }")
            .append(".report-title { font-size: 20px; font-weight: bold; color: #333; margin: 10px 0 5px 0; }")
            .append(".report-meta { font-size: 12px; color: #777; margin: 0; }")
            .append(".filters-box { background: #f5f5f5; border: 1px solid #ddd; border-radius: 5px; padding: 10px; margin: 15px 0; font-size: 11px; color: #555; }")
            .append("table { width: 100%; border-collapse: collapse; margin-top: 15px; }")
            .append("th { background: #1a237e; color: white; padding: 10px; font-size: 12px; text-align: center; }")
            .append("td { padding: 8px; border: 1px solid #ddd; font-size: 11px; text-align: center; }")
            .append("tr:nth-child(even) { background: #f9f9f9; }")
            .append(".footer { margin-top: 30px; text-align: center; font-size: 10px; color: #999; border-top: 1px solid #ccc; padding-top: 10px; }")
            .append(".logo { text-align: center; margin-bottom: 15px; }")
            .append(".logo span { font-size: 40px; }")
            .append("@media print { .no-print { display: none; } }")
            .append("</style></head><body>");
        
        // الشعار
        html.append("<div class='logo'><span>💎</span></div>");
        
        // الهيدر
        html.append("<div class='header'>")
            .append("<h1 class='org-name'>").append(orgName != null ? orgName : "MIRA System").append("</h1>")
            .append("<p class='branch-name'>").append(branchName != null ? branchName : "").append("</p>")
            .append("<h2 class='report-title'>").append(reportType).append("</h2>")
            .append("<p class='report-meta'>")
            .append(isArabic ? "تاريخ الإنشاء: " : "Generated: ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
            .append("</p>")
            .append("</div>");
        
        // معلومات الفترة
        html.append("<div class='filters-box'>")
            .append("<strong>").append(isArabic ? "📅 الفترة: " : "📅 Period: ").append("</strong>")
            .append(fromDate).append(" → ").append(toDate)
            .append("</div>");
        
        // جدول البيانات
        html.append("<table><thead><tr>");
        
        // استخراج الأعمدة
        JSONArray items = extractItemsArray(data);
        if (items != null && items.length() > 0) {
            JSONObject firstItem = items.optJSONObject(0);
            if (firstItem != null) {
                Iterator<String> keys = firstItem.keys();
                while (keys.hasNext()) {
                    html.append("<th>").append(keys.next()).append("</th>");
                }
            }
        } else {
            html.append("<th>").append(isArabic ? "المفتاح" : "Key").append("</th>")
                .append("<th>").append(isArabic ? "القيمة" : "Value").append("</th>");
        }
        html.append("</tr></thead><tbody>");
        
        // البيانات
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    html.append("<tr>");
                    Iterator<String> keys = item.keys();
                    while (keys.hasNext()) {
                        String value = item.optString(keys.next(), "");
                        html.append("<td>").append(value).append("</td>");
                    }
                    html.append("</tr>");
                }
            }
        } else {
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = data.optString(key, "");
                html.append("<tr><td>").append(key).append("</td><td>").append(value).append("</td></tr>");
            }
        }
        
        html.append("</tbody></table>");
        
        // التذييل
        html.append("<div class='footer'>")
            .append(isArabic ? "© نظام MIRA لإدارة المجوهرات - تقرير آلي" : "© MIRA Jewelry Management System - Automated Report")
            .append("</div>");
        
        html.append("</body></html>");
        
        return html.toString();
    }
    
    /**
     * ✅ طباعة PDF مباشرة
     */
    public static void printPdfReport(Context context, JSONObject data, String reportType,
                                      String orgName, String branchName,
                                      String fromDate, String toDate, boolean isArabic) {
        PrintManager printManager = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) return;
        
        String jobName = "MIRA_Report_" + reportType;
        
        PrintDocumentAdapter printAdapter = new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                               CancellationSignal cancellationSignal,
                               LayoutResultCallback callback, Bundle extras) {  // ✅ Bundle مستورد الآن
                callback.onLayoutFinished(new PrintDocumentInfo.Builder(jobName + ".pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build(), true);
            }
            
            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                              CancellationSignal cancellationSignal, WriteResultCallback callback) {
                try {
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
                    canvas.drawText(orgName != null ? orgName : "MIRA System", 30, 40, paint);
                    
                    paint.setTextSize(16);
                    paint.setColor(Color.DKGRAY);
                    canvas.drawText(reportType, 30, 70, paint);
                    
                    paint.setTextSize(12);
                    paint.setColor(Color.GRAY);
                    canvas.drawText("Period: " + fromDate + " to " + toDate, 30, 95, paint);
                    
                    // خط فاصل
                    paint.setColor(Color.LTGRAY);
                    canvas.drawLine(30, 120, pageWidth - 30, 120, paint);
                    
                    // البيانات
                    int yPosition = 150;
                    JSONArray items = extractItemsArray(data);
                    
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
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
                            while (keys.hasNext()) {
                                String key = keys.next();
                                String value = item.optString(key, "");
                                canvas.drawText(key + ": " + value, 30, yPosition, paint);
                                yPosition += 18;
                            }
                            yPosition += 10;
                        }
                    }
                    
                    document.finishPage(page);
                    document.writeTo(new FileOutputStream(destination.getFileDescriptor()));
                    document.close();
                    
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                    
                } catch (Exception e) {
                    Log.e(TAG, "PDF print error: " + e.getMessage());
                    callback.onWriteFailed(e.getMessage());
                }
            }
        };
        
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();
        
        printManager.print(jobName, printAdapter, attributes);
    }
    
    /**
     * ✅ استخراج مصفوفة العناصر
     */
    private static JSONArray extractItemsArray(JSONObject data) {
        if (data == null) return null;
        
        String[] keys = {"items", "data", "items_list", "invoices", "sales", 
                        "inventory", "movements", "transfers", "users", "gate_logs",
                        "branches", "journal_entries", "reorder_items"};
        
        for (String key : keys) {
            JSONArray arr = data.optJSONArray(key);
            if (arr != null && arr.length() > 0) return arr;
        }
        
        JSONObject dataObj = data.optJSONObject("data");
        if (dataObj != null) {
            for (String key : keys) {
                JSONArray arr = dataObj.optJSONArray(key);
                if (arr != null && arr.length() > 0) return arr;
            }
        }
        
        return null;
    }
}
