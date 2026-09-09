package com.example.uhf.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.example.uhf.model.InventoryReport;
import com.example.uhf.model.ScannedItemDisplay;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class PrintUtils {

    private static final String TAG = "PrintUtils";

    public interface PrintCallback {
        void onPrintStarted();
        void onPrintCompleted();
        void onPrintError(String error);
    }

    public static void printReportWithQRCode(Context context, InventoryReport report,
                                             List<ScannedItemDisplay> items,
                                             boolean isArabic,
                                             PrintCallback callback) {
        try {
            String qrData = generateReportQRData(report);
            Bitmap qrBitmap = generateQRCode(qrData);
            String qrBase64 = convertBitmapToBase64(qrBitmap);

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<style>")
                .append("body { font-family: sans-serif; direction: rtl; text-align: right; margin: 20px; }")
                .append(".header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }")
                .append(".header h1 { margin: 0; color: #1a237e; font-size: 22px; }")
                .append(".header p { margin: 5px 0 0 0; color: #666; font-size: 13px; }")
                .append(".summary-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }")
                .append(".summary-table td { padding: 8px; border: 1px solid #ddd; font-weight: bold; }")
                .append(".summary-table td.label { background-color: #f5f5f5; width: 25%; }")
                .append(".items-table { width: 100%; border-collapse: collapse; margin-top: 10px; }")
                .append(".items-table th { background-color: #1a237e; color: white; padding: 8px; border: 1px solid #1a237e; font-size: 12px; }")
                .append(".items-table td { padding: 6px; border: 1px solid #ddd; font-size: 11px; text-align: center; }")
                .append(".footer { margin-top: 30px; text-align: left; font-size: 10px; color: #888; border-top: 1px solid #ccc; padding-top: 5px; }")
                .append(".qr-code { text-align: center; margin-top: 20px; }")
                .append(".qr-code img { width: 150px; height: 150px; }")
                .append("</style></head><body>");

            html.append("<div class='header'>")
                .append("<h1>").append(isArabic ? "تقرير عمليات الجرد المتقدم - Mira RFID" :
                    "Advanced Inventory Report - Mira RFID").append("</h1>")
                .append("<p>").append(isArabic ? "رقم الجلسة: " : "Session ID: ")
                .append(report.sessionId)
                .append(isArabic ? " | التاريخ: " : " | Date: ")
                .append(report.date).append("</p>")
                .append("</div>");

            int accuracy = report.expectedCount > 0 ?
                (int)(((float)report.scannedCount / report.expectedCount) * 100) : 100;

            html.append("<table class='summary-table'>")
                .append("<tr><td class='label'>").append(isArabic ? "المواد المتوقعة:" : "Expected Items:")
                .append("</td><td>").append(report.expectedCount).append("</td>")
                .append("<td class='label'>").append(isArabic ? "المواد الممسوحة:" : "Scanned Items:")
                .append("</td><td>").append(report.scannedCount).append("</td></tr>")
                .append("<tr><td class='label'>").append(isArabic ? "الفارق / المفقود:" : "Variance / Missing:")
                .append("</td><td>").append(report.missingCount).append("</td>")
                .append("<td class='label'>").append(isArabic ? "نسبة الدقة:" : "Accuracy:")
                .append("</td><td>").append(accuracy).append("%</td></tr>")
                .append("</table>");

            html.append("<h3>").append(isArabic ? "تفاصيل العناصر الممسوحة:" : "Scanned Items Details:")
                .append("</h3>");
            html.append("<table class='items-table'><thead><tr>")
                .append("<th>#</th>")
                .append("<th>").append(isArabic ? "الرمز (EPC)" : "Code (EPC)").append("</th>")
                .append("<th>").append(isArabic ? "وصف القطعة" : "Item Description").append("</th>")
                .append("<th>").append(isArabic ? "العيار" : "Karat").append("</th>")
                .append("<th>").append(isArabic ? "التكرار" : "Count").append("</th>")
                .append("<th>").append(isArabic ? "الإشارة (dBm)" : "Signal (dBm)").append("</th>")
                .append("</tr></thead><tbody>");

            if (!items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    ScannedItemDisplay item = items.get(i);
                    html.append("<tr>")
                        .append("<td>").append(i + 1).append("</td>")
                        .append("<td>").append(item.epc).append("</td>")
                        .append("<td>").append(item.title).append("</td>")
                        .append("<td>").append(item.karat).append("</td>")
                        .append("<td>").append(item.count).append("</td>")
                        .append("<td>").append(item.rssi).append("</td>")
                        .append("</tr>");
                }
            } else {
                html.append("<tr><td colspan='6'>")
                    .append(isArabic ? "لا توجد عناصر ممسوحة مسجلة" : "No scanned items recorded")
                    .append("</td></tr>");
            }

            html.append("</tbody></table>");

            if (qrBase64 != null && !qrBase64.isEmpty()) {
                html.append("<div class='qr-code'>")
                    .append("<img src='data:image/png;base64,").append(qrBase64).append("'/>")
                    .append("<p>").append(isArabic ? "امسح للتحقق من التقرير" : "Scan to verify report")
                    .append("</p>")
                    .append("</div>");
            }

            html.append("<div class='footer'>")
                .append(isArabic ? "تم إنشاء هذا التقرير تلقائياً بواسطة نظام Mira Pro RFID" :
                    "This report was automatically generated by Mira Pro RFID System")
                .append("</div>");
            html.append("</body></html>");

            if (callback != null) callback.onPrintStarted();

            WebView webView = new WebView(context);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    try {
                        PrintManager printManager = (PrintManager)
                            context.getSystemService(Context.PRINT_SERVICE);
                        if (printManager != null) {
                            String jobName = "Mira_Inventory_Report_" + report.sessionId;
                            PrintDocumentAdapter printAdapter =
                                view.createPrintDocumentAdapter(jobName);
                            PrintAttributes attributes = new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                    .build();
                            printManager.print(jobName, printAdapter, attributes);
                        }
                        if (callback != null) callback.onPrintCompleted();
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting print", e);
                        if (callback != null) callback.onPrintError(e.getMessage());
                    } finally {
                        cleanupWebView(view);
                    }
                }
            });

            webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);

        } catch (Exception e) {
            Log.e(TAG, "Error printing report with QR code", e);
            if (callback != null) callback.onPrintError(e.getMessage());
        }
    }

    private static void cleanupWebView(WebView webView) {
        if (webView == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.clearCache(true);
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error cleaning up WebView", e);
            }
        }, 1000);
    }

    private static Bitmap generateQRCode(String data) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 300, 300);

            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR code", e);
            return null;
        }
    }

    private static String convertBitmapToBase64(Bitmap bitmap) {
        if (bitmap == null) return "";

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();

        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }

    private static String generateReportQRData(InventoryReport report) {
        try {
            JSONObject qrData = new JSONObject();
            qrData.put("type", "inventory_report");
            qrData.put("session_id", report.sessionId);
            qrData.put("date", report.date);
            qrData.put("expected_count", report.expectedCount);
            qrData.put("scanned_count", report.scannedCount);
            qrData.put("missing_count", report.missingCount);
            qrData.put("status", report.status);

            return qrData.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
