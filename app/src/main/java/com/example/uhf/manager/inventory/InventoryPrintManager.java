package com.example.uhf.manager.inventory;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.example.uhf.model.InventoryReport;
import com.example.uhf.model.ScannedItemDisplay;
import com.example.uhf.utils.PrintUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ✅ InventoryPrintManager v1.0
 * 
 * مسؤول عن:
 * - طباعة الملصقات الفردية
 * - طباعة التقارير الحالية
 * - طباعة الأرشيف
 * - عرض تفاصيل التقارير
 * 
 * يوفر على Fragment: ~130 سطر
 */
public class InventoryPrintManager {

    private static final String TAG = "InventoryPrintManager";

    private final Context context;
    private final boolean isArabic;

    // ✅ Time formatting
    private final SimpleDateFormat dateTimeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    // ==================== Callback ====================

    /**
     * واجهة للاتصال بالـ Fragment
     */
    public interface PrintCallback {
        /** الحصول على قائمة القطع الممسوحة */
        List<ScannedItemDisplay> getScannedItems();

        /** الحصول على session ID */
        String getSessionId();

        /** الحصول على expected count */
        int getExpectedCount();

        /** الحصول على scanned count */
        int getScannedCount();

        /** الحصول على قائمة التقارير (للأرشيف) */
        List<InventoryReport> getArchivedReports();
    }

    private PrintCallback callback;

    // ==================== Constructor ====================

    public InventoryPrintManager(Context context, boolean isArabic) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
    }

    public void setCallback(PrintCallback callback) {
        this.callback = callback;
    }

    // ==================== Helpers ====================

    private synchronized String getCurrentDateTimeString() {
        return dateTimeFormat.format(new Date());
    }

    private String getSafeSessionId() {
        if (callback != null) {
            String sessionId = callback.getSessionId();
            if (sessionId != null && !sessionId.isEmpty()) {
                return sessionId;
            }
        }
        return "SESS-UNKNOWN";
    }

    private List<ScannedItemDisplay> getSafeScannedItems() {
        if (callback != null) {
            List<ScannedItemDisplay> items = callback.getScannedItems();
            if (items != null) {
                return new ArrayList<>(items);
            }
        }
        return new ArrayList<>();
    }

    private int getSafeExpectedCount() {
        return callback != null ? callback.getExpectedCount() : 0;
    }

    private int getSafeScannedCount() {
        return callback != null ? callback.getScannedCount() : 0;
    }

    // ==================== 1. طباعة ملصق فردي ====================

    /**
     * ✅ طباعة ملصق قطعة واحدة
     */
    public void printSingleItemLabel(ScannedItemDisplay item) {
        if (item == null) return;

        try {
            InventoryReport singleReport = new InventoryReport(
                    0,
                    getSafeSessionId() + "-SINGLE",
                    getCurrentDateTimeString(),
                    1,
                    1,
                    0,
                    isArabic ? "ملصق فردي" : "Single Label"
            );

            List<ScannedItemDisplay> singleList = new ArrayList<>();
            singleList.add(item);

            PrintUtils.printReportWithQRCode(
                    context,
                    singleReport,
                    singleList,
                    isArabic,
                    null
            );

            Log.d(TAG, "✅ Single label printed: " + item.epc);
        } catch (Exception e) {
            Log.e(TAG, "printSingleItemLabel error", e);
            showError(isArabic ? "فشل الطباعة" : "Print failed");
        }
    }

    // ==================== 2. طباعة التقرير الحالي ====================

    /**
     * ✅ طباعة التقرير الحالي (قيد التشغيل)
     */
    public void printCurrentReport() {
        try {
            int expected = getSafeExpectedCount();
            int scanned = getSafeScannedCount();
            int missing = Math.max(0, expected - scanned);

            InventoryReport currentReport = new InventoryReport(
                    0,
                    getSafeSessionId(),
                    getCurrentDateTimeString(),
                    expected,
                    scanned,
                    missing,
                    isArabic ? "جارية" : "In progress"
            );

            List<ScannedItemDisplay> snapshotList = getSafeScannedItems();

            PrintUtils.printReportWithQRCode(
                    context,
                    currentReport,
                    snapshotList,
                    isArabic,
                    null
            );

            Log.d(TAG, "✅ Current report printed: " + scanned + "/" + expected);
        } catch (Exception e) {
            Log.e(TAG, "printCurrentReport error", e);
            showError(isArabic ? "فشل طباعة التقرير" : "Report print failed");
        }
    }

    // ==================== 3. طباعة تقرير مؤرشَف ====================

    /**
     * ✅ طباعة تقرير من الأرشيف
     */
    public void printArchivedReport(InventoryReport report) {
        if (report == null) return;

        try {
            List<ScannedItemDisplay> snapshotList = getSafeScannedItems();

            PrintUtils.printReportWithQRCode(
                    context,
                    report,
                    snapshotList,
                    isArabic,
                    null
            );

            Log.d(TAG, "✅ Archived report printed: " + report.sessionId);
        } catch (Exception e) {
            Log.e(TAG, "printArchivedReport error", e);
            showError(isArabic ? "فشل الطباعة" : "Print failed");
        }
    }

    // ==================== 4. طباعة تقرير مخصص ====================

    /**
     * ✅ طباعة تقرير مع قائمة قطع محددة
     */
    public void printReportWithItems(InventoryReport report,
                                     List<ScannedItemDisplay> items) {
        if (report == null) return;

        try {
            List<ScannedItemDisplay> safeItems = items != null
                    ? new ArrayList<>(items)
                    : new ArrayList<>();

            PrintUtils.printReportWithQRCode(
                    context,
                    report,
                    safeItems,
                    isArabic,
                    null
            );

            Log.d(TAG, "✅ Custom report printed: " + report.sessionId);
        } catch (Exception e) {
            Log.e(TAG, "printReportWithItems error", e);
        }
    }

    // ==================== 5. طباعة كل التقارير ====================

    /**
     * ✅ طباعة كل تقارير الأرشيف
     */
    public void printAllReports(List<InventoryReport> reports) {
        if (reports == null || reports.isEmpty()) return;

        try {
            List<ScannedItemDisplay> snapshotList = getSafeScannedItems();

            for (InventoryReport report : reports) {
                if (report == null) continue;
                try {
                    PrintUtils.printReportWithQRCode(
                            context,
                            report,
                            snapshotList,
                            isArabic,
                            null
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Error printing report: " + report.sessionId, e);
                }
            }

            Log.d(TAG, "✅ Printed " + reports.size() + " reports");
            showSuccess(isArabic
                    ? "✅ تم إرسال " + reports.size() + " تقرير للطباعة"
                    : "✅ Sent " + reports.size() + " reports to print");
        } catch (Exception e) {
            Log.e(TAG, "printAllReports error", e);
            showError(isArabic ? "فشل طباعة التقارير" : "Reports print failed");
        }
    }

    // ==================== 6. عرض تفاصيل التقرير ====================

    /**
     * ✅ عرض تفاصيل التقرير + خيارات
     */
    public void showReportDetails(InventoryReport report,
                                   OnReportActionListener listener) {
        if (report == null || context == null) return;

        String message = buildReportDetailsMessage(report);

        String[] options = isArabic
                ? new String[]{
                    "🖨️ طباعة التقرير",
                    "❌ إغلاق"
                }
                : new String[]{
                    "🖨️ Print Report",
                    "❌ Close"
                };

        try {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(isArabic ? "تفاصيل التقرير" : "Report Details")
                    .setMessage(message)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            printArchivedReport(report);
                            if (listener != null) {
                                listener.onPrintRequested(report);
                            }
                        }
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showReportDetails error", e);
        }
    }

    /**
     * واجهة لمعالجة الإجراءات
     */
    public interface OnReportActionListener {
        void onPrintRequested(InventoryReport report);
    }

    // ==================== 7. بناء نص التفاصيل ====================

    /**
     * ✅ بناء نص تفاصيل التقرير
     */
    private String buildReportDetailsMessage(InventoryReport report) {
        if (report == null) return "";

        return String.format(
                "📋 %s:\n\n" +
                "• %s: %s\n" +
                "• %s: %s\n" +
                "• %s: %d\n" +
                "• %s: %d\n" +
                "• %s: %d\n" +
                "• %s: %s",
                isArabic ? "تفاصيل التقرير" : "Report Details",
                isArabic ? "رقم الجلسة" : "Session ID", report.sessionId,
                isArabic ? "التاريخ" : "Date", report.date,
                isArabic ? "المتوقع" : "Expected", report.expectedCount,
                isArabic ? "الممسوح" : "Scanned", report.scannedCount,
                isArabic ? "المفقود" : "Missing", report.missingCount,
                isArabic ? "الحالة" : "Status", report.status
        );
    }

    // ==================== 8. Toast Helpers ====================

    private void showSuccess(String msg) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private void showError(String msg) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    // ==================== Cleanup ====================

    public void cleanup() {
        callback = null;
        Log.d(TAG, "cleanup complete");
    }
}
