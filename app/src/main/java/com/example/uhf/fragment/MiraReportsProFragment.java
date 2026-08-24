package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraReportsProFragment extends Fragment {

    private int currentBranchId = 1;

    private static final String TAG = "MiraReportsPro";
    private static final String CACHE_PREFS = "mira_reports_pro_cache";

    private RecyclerView rvReports;
    private ReportsAdapter adapter;
    private final List<ReportItem> reportList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    private MiraOrgProfileManager orgProfile;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private SharedPreferences offlineCache;
    private boolean isArabic;

    private AlertDialog currentReportDialog;
    private WebView currentWebView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);

        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            permissionManager = PermissionManager.getInstance(getContext());
            offlineCache = getContext().getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        }

        isArabic = Locale.getDefault().getLanguage().equals("ar");

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new GridLayoutManager(getContext(), 2));

        buildReportsList();
        adapter = new ReportsAdapter(reportList, this::openReportDialog);
        rvReports.setAdapter(adapter);

        return view;
    }

    @Override
    public void onDestroyView() {
        if (currentReportDialog != null && currentReportDialog.isShowing()) {
            safeDismissDialog(currentReportDialog, currentWebView);
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        super.onDestroyView();
    }

    private void buildReportsList() {
        reportList.clear();

        if (isArabic) {
            reportList.add(new ReportItem("📊", "لوحة القيادة", "ملخص شامل للعمليات", "dashboard"));
            reportList.add(new ReportItem("💰", "المبيعات التفصيلي", "فواتير + طرق دفع + عملاء", "sales"));
            reportList.add(new ReportItem("📦", "المخزون التفصيلي", "كل قطعة مع تفاصيلها", "inventory"));
            reportList.add(new ReportItem("🔄", "التحويلات", "سجل حركة القطع", "movement"));
            reportList.add(new ReportItem("🏢", "أداء الفروع", "مقارنة شاملة", "branches"));
            reportList.add(new ReportItem("🚪", "البوابات الأمنية", "سجل المرور والإنذارات", "gates"));
            reportList.add(new ReportItem("📋", "الجرد والتدقيق", "الفروقات والدقة", "audit"));
            reportList.add(new ReportItem("💎", "النواقص والطلب", "الأكثر مبيعاً", "reorder"));
            reportList.add(new ReportItem("👥", "المستخدمين", "نشاط الموظفين", "users"));
            reportList.add(new ReportItem("💵", "الحركة المالية", "القيود المحاسبية", "financial"));
            reportList.add(new ReportItem("⏳", "سجل نشاط الموظف", "تايم لاين أفعال الموظف", "user_timeline"));
            reportList.add(new ReportItem("🏷️", "البصمة الرقمية", "سجل حياة القطعة وتتبعها", "item_passport"));
            reportList.add(new ReportItem("📉", "أعمار المخزون", "تحليل حركة ودوران القطع", "inventory_aging"));
        } else {
            reportList.add(new ReportItem("📊", "Dashboard", "Comprehensive summary", "dashboard"));
            reportList.add(new ReportItem("💰", "Sales Details", "Invoices + payments + customers", "sales"));
            reportList.add(new ReportItem("📦", "Inventory Details", "Every item with details", "inventory"));
            reportList.add(new ReportItem("🔄", "Transfers", "Movement log", "movement"));
            reportList.add(new ReportItem("🏢", "Branches", "Performance comparison", "branches"));
            reportList.add(new ReportItem("🚪", "Gates", "Security log", "gates"));
            reportList.add(new ReportItem("📋", "Audit", "Variance analysis", "audit"));
            reportList.add(new ReportItem("💎", "Reorder", "Best sellers", "reorder"));
            reportList.add(new ReportItem("👥", "Users", "Staff activity", "users"));
            reportList.add(new ReportItem("💵", "Financial", "Journal entries", "financial"));
            reportList.add(new ReportItem("⏳", "User Timeline", "Audit staff activities", "user_timeline"));
            reportList.add(new ReportItem("🏷️", "Item Passport", "Complete lifecycle tracking", "item_passport"));
            reportList.add(new ReportItem("📉", "Inventory Aging", "Stock turnover analysis", "inventory_aging"));
        }
    }

    private void openReportDialog(ReportItem item) {
        if (getContext() == null || !isAdded()) return;

        if (permissionManager != null && !permissionManager.canAccessFragment("Reports")) {
            Toast.makeText(getContext(),
                    isArabic ? "⛔ غير مصرح لك بالوصول للتقارير" : "⛔ Access Denied",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mira_report_viewer, null);

        TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
        WebView webView = dialogView.findViewById(R.id.webReportView);
        View layoutLoading = dialogView.findViewById(R.id.layoutLoadingContainer);
        TextView tvLoadingStatus = dialogView.findViewById(R.id.tvLoadingStatus);

        MaterialButton btnFrom = dialogView.findViewById(R.id.btnReportDateFrom);
        MaterialButton btnTo = dialogView.findViewById(R.id.btnReportDateTo);
        MaterialButton btnApply = dialogView.findViewById(R.id.btnReportApplyFilter);
        MaterialButton btnPrint = dialogView.findViewById(R.id.btnReportPrint);
        MaterialButton btnExport = dialogView.findViewById(R.id.btnReportExport);
        MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);

        View layoutAdvancedFilters = dialogView.findViewById(R.id.layoutAdvancedFilters);
        Spinner spinnerUsers = dialogView.findViewById(R.id.spinnerReportUsers);
        EditText etItemCode = dialogView.findViewById(R.id.etReportItemCode);

        tvTitle.setText(item.icon + " " + item.title);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setBackgroundColor(0x00000000);

        if (layoutAdvancedFilters != null) {
            boolean isUserReport = "user_timeline".equals(item.type);
            boolean isItemReport = "item_passport".equals(item.type);

            layoutAdvancedFilters.setVisibility((isUserReport || isItemReport) ? View.VISIBLE : View.GONE);

            if (spinnerUsers != null) {
                spinnerUsers.setVisibility(isUserReport ? View.VISIBLE : View.GONE);
                if (isUserReport) {
                    populateUsersSpinner(spinnerUsers);
                }
            }

            if (etItemCode != null) {
                etItemCode.setVisibility(isItemReport ? View.VISIBLE : View.GONE);
            }
        }

        final String[] selectedDates = new String[]{getDefaultFromDate(), getDefaultToDate()};

        btnFrom.setText((isArabic ? "من: " : "From: ") + selectedDates[0]);
        btnTo.setText((isArabic ? "إلى: " : "To: ") + selectedDates[1]);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
                .setView(dialogView)
                .create();

        currentReportDialog = dialog;
        currentWebView = webView;

        btnFrom.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[0] = date;
            btnFrom.setText((isArabic ? "من: " : "From: ") + date);
        }));

        btnTo.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[1] = date;
            btnTo.setText((isArabic ? "إلى: " : "To: ") + date);
        }));

        btnApply.setOnClickListener(v -> {
            String fromDate = selectedDates[0];
            String toDate = selectedDates[1];

            int selectedUserId = 0;
            if (spinnerUsers != null && spinnerUsers.getVisibility() == View.VISIBLE && spinnerUsers.getSelectedItem() != null) {
                selectedUserId = ((UserSpinnerItem) spinnerUsers.getSelectedItem()).getId();
            }

            String itemCode = "";
            if (etItemCode != null && etItemCode.getVisibility() == View.VISIBLE) {
                itemCode = etItemCode.getText().toString().trim();
            }

            fetchAndRenderReportEx(item, webView, layoutLoading, tvLoadingStatus, fromDate, toDate, selectedUserId, itemCode, dialog);
        });

        btnPrint.setOnClickListener(v -> printReportWebView(webView, item.title));
        btnExport.setOnClickListener(v -> exportReportToCSV(item.type, selectedDates[0], selectedDates[1]));

        btnClose.setOnClickListener(v -> safeDismissDialog(dialog, webView));
        dialog.setOnCancelListener(d -> safeDismissDialog(dialog, webView));

        dialog.show();

        webView.post(() -> fetchAndRenderReportEx(item, webView, layoutLoading, tvLoadingStatus, selectedDates[0], selectedDates[1], 0, "", dialog));
    }

    private void safeDismissDialog(AlertDialog dialog, WebView webView) {
        if (webView != null) {
            try {
                webView.stopLoading();
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) {
                    parent.removeView(webView);
                }
                webView.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error destroying webview", e);
            }
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        if (dialog == currentReportDialog) {
            currentReportDialog = null;
            currentWebView = null;
        }
    }

    private void fetchAndRenderReportEx(ReportItem item, WebView webView, View layoutLoading,
                                   TextView tvLoadingStatus, String fromDate, String toDate,
                                   int userId, String itemCode, Dialog dialog) {
    if (getContext() == null || !isAdded()) return;

    if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
    if (tvLoadingStatus != null) tvLoadingStatus.setText(isArabic ? "جاري جلب وتحليل البيانات..." : "Fetching and analyzing data...");

    boolean isAdvanced = "user_timeline".equals(item.type) || "item_passport".equals(item.type) || "inventory_aging".equals(item.type);
    String endpoint = isAdvanced ? "/reports-analytics" : "/reports-app";

    // ✅ التعديل: استخدم المسار النسبي فقط بدون الدومين
    StringBuilder urlBuilder = new StringBuilder();
    urlBuilder.append(endpoint)
              .append("?type=").append(item.type)
              .append("&from=").append(fromDate)
              .append("&to=").append(toDate)
              .append("&branch_id=").append(currentBranchId);

    if (userId > 0) {
        urlBuilder.append("&user_id=").append(userId);
    }
    if (itemCode != null && !itemCode.trim().isEmpty()) {
        urlBuilder.append("&item_code=").append(Uri.encode(itemCode.trim()));
    }

    String requestUrl = urlBuilder.toString();

    apiClient.get(requestUrl, new MiraApiClient.ApiCallback() {
        @Override
        public void onSuccess(MiraApiClient.ApiResponse response) {
            if (getActivity() == null || !isAdded()) return;

            getActivity().runOnUiThread(() -> {
                if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);

                String jsonResponse = response.getBody() != null ? response.getBody() : "";
                String htmlContent = "user_timeline".equals(item.type) || "item_passport".equals(item.type)
                        ? buildReportHtmlTemplate(item, jsonResponse, isArabic)
                        : generateExecutiveHTML(item.type, parseJsonObject(jsonResponse), fromDate, toDate);

                webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
            });
        }

        @Override
        public void onFailure(String errorMessage) {
            handleReportError(errorMessage, layoutLoading);
        }

        @Override
        public void onError(String errorMessage) {
            handleReportError(errorMessage, layoutLoading);
        }
    });
}
    private void handleReportError(String errorMessage, View layoutLoading) {
        if (getActivity() == null || !isAdded()) return;

        getActivity().runOnUiThread(() -> {
            if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
            Toast.makeText(getContext(),
                    (isArabic ? "فشل جلب البيانات: " : "Error: ") + errorMessage,
                    Toast.LENGTH_LONG).show();
        });
    }

    private JSONObject parseJsonObject(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.has("data") ? obj.optJSONObject("data") : obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String buildReportHtmlTemplate(ReportItem item, String jsonResponse, boolean isArabic) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='").append(isArabic ? "rtl" : "ltr").append("'><head>");
        html.append("<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<style>");
        html.append("body { font-family: sans-serif; margin: 15px; background-color: #f8f9fa; color: #333; }");
        html.append(".card { background: #fff; padding: 15px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); margin-bottom: 15px; }");
        html.append(".timeline-item { border-right: 3px solid #1a73e8; padding-right: 15px; margin-bottom: 15px; position: relative; }");
        html.append(".badge { display: inline-block; padding: 3px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; color: #fff; background: #1a73e8; }");
        html.append("table { width: 100%; border-collapse: collapse; margin-top: 10px; }");
        html.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: start; }");
        html.append("th { background-color: #f1f3f4; }");
        html.append("</style></head><body>");

        try {
            JSONObject responseObj = new JSONObject(jsonResponse);
            JSONObject dataObj = responseObj.optJSONObject("data");

            if (dataObj == null) {
                html.append("<div class='card'><h3>").append(isArabic ? "لا توجد بيانات متاحة" : "No data available").append("</h3></div>");
            } else if ("user_timeline".equals(item.type)) {
                JSONObject userInfo = dataObj.optJSONObject("user_info");
                JSONArray timeline = dataObj.optJSONArray("timeline");

                html.append("<div class='card'><h2>").append(isArabic ? "سجل نشاط الموظف: " : "User Activity: ")
                    .append(userInfo != null ? userInfo.optString("display_name") : "").append("</h2></div>");

                if (timeline != null && timeline.length() > 0) {
                    for (int i = 0; i < timeline.length(); i++) {
                        JSONObject event = timeline.getJSONObject(i);
                        html.append("<div class='card timeline-item'>");
                        html.append("<span class='badge'>").append(event.optString("event_type")).append("</span>");
                        html.append("<p><strong>").append(isArabic ? "التفاصيل: " : "Details: ").append("</strong>").append(event.optString("details")).append("</p>");
                        html.append("<small style='color:#777;'>").append(event.optString("created_at")).append("</small>");
                        html.append("</div>");
                    }
                } else {
                    html.append("<div class='card'><p>").append(isArabic ? "لا توجد أنشطة مسجلة لهذا الموظف في هذه الفترة" : "No activities recorded").append("</p></div>");
                }
            } else if ("item_passport".equals(item.type)) {
                JSONObject itemDetails = dataObj.optJSONObject("item_details");
                if (itemDetails != null) {
                    html.append("<div class='card'><h2>").append(isArabic ? "البصمة الرقمية للقطعة" : "Digital Passport").append("</h2>");
                    html.append("<p><strong>").append(isArabic ? "رمز القطعة: " : "SKU: ").append("</strong>").append(itemDetails.optString("item_code")).append("</p>");
                    html.append("<p><strong>").append(isArabic ? "العيار / الوزن: " : "Karat / Weight: ").append("</strong>").append(itemDetails.optString("gold_karat")).append(" / ").append(itemDetails.optString("gross_weight_grams")).append("g</p>");
                    html.append("</div>");
                }
            } else {
                html.append("<div class='card'><pre>").append(dataObj.toString(2)).append("</pre></div>");
            }

        } catch (Exception e) {
            html.append("<div class='card' style='color:red;'><h3>").append(isArabic ? "خطأ في معالجة البيانات" : "Data Parsing Error").append("</h3></div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private String generateExecutiveHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        String orgName = orgProfile != null ? orgProfile.getOrgName() : "MIRA SYSTEM";
        String dir = isArabic ? "rtl" : "ltr";

        html.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>")
                .append("<style>")
                .append("html, body { background-color: #F4F6F9 !important; color: #1F2937; margin: 0; padding: 12px; width: 100%; min-height: 100%; box-sizing: border-box; }")
                .append(".header{background:linear-gradient(135deg,#1E293B,#0F172A);color:#FFF;padding:16px;border-radius:12px;margin-bottom:12px;}")
                .append(".header h2{margin:0;font-size:18px;color:#F1F5F9;}")
                .append(".header p{margin:4px 0 0;font-size:11px;color:#94A3B8;}")
                .append(".card{background:#FFF;padding:14px;border-radius:10px;margin-bottom:12px;border:1px solid #E2E8F0;}")
                .append(".card-title{font-size:14px;font-weight:bold;color:#0F172A;margin-bottom:10px;border-bottom:2px solid #F1F5F9;padding-bottom:6px;}")
                .append(".grid{display:grid;grid-template-columns:repeat(2, 1fr);gap:8px;margin-bottom:10px;}")
                .append(".metric{background:#F8FAFC;border:1px solid #E2E8F0;padding:10px;border-radius:8px;text-align:center;}")
                .append(".metric .val{font-size:15px;font-weight:800;color:#0F172A;}")
                .append(".metric .lbl{font-size:10px;color:#64748B;}")
                .append(".gold{color:#D97706;font-weight:bold;}")
                .append("table{width:100%;border-collapse:collapse;margin-top:8px;font-size:11px;}")
                .append("th{background:#0F172A;color:#FFF;padding:8px;text-align:center;}")
                .append("td{padding:8px;border-bottom:1px solid #E2E8F0;text-align:center;}")
                .append("</style></head><body>");

        html.append("<div class='header'><h2>").append(orgName).append("</h2>")
                .append("<p>").append(isArabic ? "الفترة: " : "Period: ").append(from)
                .append(isArabic ? " إلى " : " to ").append(to).append("</p></div>");

        switch (type) {
            case "inventory": renderInventoryHTML(html, data); break;
            case "sales": renderSalesHTML(html, data); break;
            case "movement": renderMovementHTML(html, data); break;
            case "branches": renderBranchesHTML(html, data); break;
            case "gates": renderGatesHTML(html, data); break;
            case "audit": renderAuditHTML(html, data); break;
            case "reorder": renderReorderHTML(html, data); break;
            case "users": renderUsersHTML(html, data); break;
            case "financial": renderFinancialHTML(html, data); break;
            default: renderDashboardHTML(html, data); break;
        }

        html.append("</body></html>");
        return html.toString();
    }

    private void renderDashboardHTML(StringBuilder html, JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        JSONObject sales = data.optJSONObject("sales");

        if (inv == null) inv = data;
        if (sales == null) sales = data;

        html.append("<div class='card'><div class='card-title'>📊 ")
                .append(isArabic ? "الملخص التنفيذي" : "Executive Summary")
                .append("</div><div class='grid'>");
        html.append(metricBox("📦", String.valueOf(inv.optInt("total_items", 0)), isArabic ? "القطع" : "Items"));
        html.append(metricBox("⚖️", String.format(Locale.US, "%.1f g", inv.optDouble("total_weight", 0)), isArabic ? "الوزن" : "Weight"));
        html.append(metricBox("💰", String.format(Locale.US, "%,.0f", sales.optDouble("total_revenue", 0)), isArabic ? "الإيرادات" : "Revenue"));
        html.append(metricBox("🧾", String.valueOf(sales.optInt("invoices_count", 0)), isArabic ? "الفواتير" : "Invoices"));
        html.append("</div></div>");
    }

    private void renderInventoryHTML(StringBuilder html, JSONObject data) {
        JSONObject summary = data.optJSONObject("summary");
        if (summary == null) summary = data;

        html.append("<div class='card'><div class='card-title'>📦 ")
                .append(isArabic ? "المخزون التفصيلي" : "Inventory Details")
                .append("</div><div class='grid'>");
        html.append(metricBox("📦", String.valueOf(summary.optInt("total_items", 0)), isArabic ? "إجمالي" : "Total"));
        html.append(metricBox("✅", String.valueOf(summary.optInt("available_count", 0)), isArabic ? "متاح" : "Available"));
        html.append(metricBox("💰", String.valueOf(summary.optInt("sold_count", 0)), isArabic ? "مباع" : "Sold"));
        html.append(metricBox("⚖️", String.format(Locale.US, "%.1f g", summary.optDouble("total_weight", 0)), isArabic ? "الوزن" : "Weight"));
        html.append("</div></div>");

        JSONArray items = data.optJSONArray("items");
        if (items != null && items.length() > 0) {
            html.append("<table><tr><th>#</th><th>").append(isArabic ? "الصنف" : "Item").append("</th><th>")
                    .append(isArabic ? "العيار" : "Karat").append("</th><th>")
                    .append(isArabic ? "الوزن" : "Weight").append("</th><th>")
                    .append(isArabic ? "الحالة" : "Status").append("</th><th>")
                    .append(isArabic ? "الموقع" : "Location").append("</th></tr>");
            for (int i = 0; i < Math.min(items.length(), 100); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                html.append("<tr><td>").append(i + 1).append("</td>")
                        .append("<td>").append(item.optString("item_title", "—")).append("</td>")
                        .append("<td>").append(item.optString("gold_karat", "—")).append("</td>")
                        .append("<td>").append(String.format(Locale.US, "%.2f", item.optDouble("gross_weight_grams", 0))).append("</td>")
                        .append("<td>").append(item.optString("vault_status", "—")).append("</td>")
                        .append("<td>").append(item.optString("current_location", "—")).append("</td></tr>");
            }
            html.append("</table>");
        }
    }

    private void renderSalesHTML(StringBuilder html, JSONObject data) {
        JSONObject summary = data.optJSONObject("summary");
        if (summary == null) summary = data;

        int invoicesCount = summary.optInt("invoices_count", summary.optInt("total_invoices", 0));
        double totalRevenue = summary.optDouble("total_revenue", summary.optDouble("total_sales", 0.0));
        double avgInvoice = summary.optDouble("avg_invoice", 0.0);

        html.append("<div class='card'><div class='card-title'>💰 ")
                .append(isArabic ? "ملخص المبيعات" : "Sales Summary")
                .append("</div><div class='grid'>");
        html.append(metricBox("📄", String.valueOf(invoicesCount), isArabic ? "عدد الفواتير" : "Invoices"));
        html.append(metricBox("💵", String.format(Locale.US, "%.2f", totalRevenue), isArabic ? "الإيرادات" : "Revenue"));
        html.append(metricBox("📊", String.format(Locale.US, "%.2f", avgInvoice), isArabic ? "متوسط الفاتورة" : "Avg Invoice"));
        html.append("</div></div>");

        JSONArray salesArray = data.optJSONArray("invoices");
        if (salesArray == null) {
            salesArray = data.optJSONArray("sales");
        }

        if (salesArray != null && salesArray.length() > 0) {
            html.append("<div class='card'><div class='card-title'>🧾 ")
                    .append(isArabic ? "سجل الفواتير" : "Invoices Log")
                    .append("</div><table><thead><tr>")
                    .append("<th>#</th>")
                    .append("<th>").append(isArabic ? "المبلغ" : "Amount").append("</th>")
                    .append("<th>").append(isArabic ? "طريقة الدفع" : "Payment").append("</th>")
                    .append("<th>").append(isArabic ? "البائع" : "Seller").append("</th>")
                    .append("</tr></thead><tbody>");

            for (int i = 0; i < Math.min(salesArray.length(), 100); i++) {
                JSONObject s = salesArray.optJSONObject(i);
                if (s == null) continue;

                String id = s.optString("id", s.optString("invoice_no", "-"));
                double amount = s.optDouble("final_amount", s.optDouble("subtotal", 0.0));
                String method = s.optString("payment_method", "CASH");
                String seller = s.optString("seller_name", "-");

                html.append("<tr>")
                        .append("<td>").append(id).append("</td>")
                        .append("<td>").append(String.format(Locale.US, "%.2f", amount)).append("</td>")
                        .append("<td>").append(method).append("</td>")
                        .append("<td>").append(seller).append("</td>")
                        .append("</tr>");
            }
            html.append("</tbody></table></div>");
        }
    }

    private void renderMovementHTML(StringBuilder html, JSONObject data) {
        JSONArray movements = data.optJSONArray("movements");
        html.append("<div class='card'><div class='card-title'>🔄 ")
                .append(isArabic ? "سجل التحويلات" : "Transfer Log")
                .append("</div>");
        if (movements != null && movements.length() > 0) {
            html.append("<table><tr><th>#</th><th>").append(isArabic ? "من" : "From").append("</th><th>")
                    .append(isArabic ? "إلى" : "To").append("</th><th>")
                    .append(isArabic ? "التاريخ" : "Date").append("</th></tr>");
            for (int i = 0; i < movements.length(); i++) {
                JSONObject m = movements.optJSONObject(i);
                if (m != null) {
                    html.append("<tr><td>").append(i + 1).append("</td>")
                            .append("<td>").append(m.optString("from_branch", "—")).append("</td>")
                            .append("<td>").append(m.optString("to_branch", "—")).append("</td>")
                            .append("<td>").append(m.optString("created_at", "—")).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>").append(isArabic ? "لا توجد تحويلات" : "No transfers").append("</p>");
        }
        html.append("</div>");
    }

    private void renderBranchesHTML(StringBuilder html, JSONObject data) {
        JSONArray branches = data.optJSONArray("branches_performance");
        html.append("<div class='card'><div class='card-title'>🏢 ")
                .append(isArabic ? "أداء الفروع" : "Branch Performance")
                .append("</div>");
        if (branches != null && branches.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "الفرع" : "Branch").append("</th><th>")
                    .append(isArabic ? "القطع" : "Items").append("</th></tr>");
            for (int i = 0; i < branches.length(); i++) {
                JSONObject b = branches.optJSONObject(i);
                if (b != null) {
                    html.append("<tr><td>").append(b.optString("branch_name", "—")).append("</td>")
                            .append("<td>").append(b.optInt("items_count", 0)).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>").append(isArabic ? "لا توجد فروع" : "No branches").append("</p>");
        }
        html.append("</div>");
    }

    private void renderGatesHTML(StringBuilder html, JSONObject data) {
        JSONArray logs = data.optJSONArray("gate_logs");
        html.append("<div class='card'><div class='card-title'>🚪 ")
                .append(isArabic ? "سجل البوابات" : "Gate Log")
                .append("</div>");
        if (logs != null && logs.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "البوابة" : "Gate").append("</th><th>")
                    .append(isArabic ? "EPC" : "EPC").append("</th><th>")
                    .append(isArabic ? "التاريخ" : "Date").append("</th></tr>");
            for (int i = 0; i < Math.min(logs.length(), 50); i++) {
                JSONObject l = logs.optJSONObject(i);
                if (l != null) {
                    html.append("<tr><td>").append(l.optString("gate_name", "—")).append("</td>")
                            .append("<td>").append(l.optString("epc", "—")).append("</td>")
                            .append("<td>").append(l.optString("created_at", "—")).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>").append(isArabic ? "لا توجد أحداث" : "No events").append("</p>");
        }
        html.append("</div>");
    }

    private void renderAuditHTML(StringBuilder html, JSONObject data) {
        JSONObject audit = data.optJSONObject("audit_summary");
        if (audit == null) audit = data;

        html.append("<div class='card'><div class='card-title'>📋 ")
                .append(isArabic ? "نتائج الجرد" : "Audit Results")
                .append("</div><div class='grid'>");
        html.append(metricBox("🎯", String.format(Locale.US, "%.1f%%", audit.optDouble("accuracy", 0)), isArabic ? "الدقة" : "Accuracy"));
        html.append(metricBox("✅", String.valueOf(audit.optInt("found_items", 0)), isArabic ? "موجود" : "Found"));
        html.append(metricBox("❌", String.valueOf(audit.optInt("missing_items", 0)), isArabic ? "مفقود" : "Missing"));
        html.append(metricBox("📊", String.valueOf(audit.optInt("total_sessions", 0)), isArabic ? "الجلسات" : "Sessions"));
        html.append("</div></div>");
    }

    private void renderReorderHTML(StringBuilder html, JSONObject data) {
        JSONArray items = data.optJSONArray("reorder_items");
        html.append("<div class='card'><div class='card-title'>💎 ")
                .append(isArabic ? "النواقص والطلب" : "Reorder & Demand")
                .append("</div>");
        if (items != null && items.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "العيار" : "Karat").append("</th><th>")
                    .append(isArabic ? "المباع" : "Sold").append("</th><th>")
                    .append(isArabic ? "المتبقي" : "Remaining").append("</th></tr>");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    html.append("<tr><td><b>").append(item.optString("gold_karat", "—")).append("</b></td>")
                            .append("<td>").append(item.optInt("sold_count", 0)).append("</td>")
                            .append("<td>").append(item.optInt("remaining_stock", 0)).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>")
                    .append(isArabic ? "المخزون متوازن" : "Inventory balanced").append("</p>");
        }
        html.append("</div>");
    }

    private void renderUsersHTML(StringBuilder html, JSONObject data) {
        JSONArray users = data.optJSONArray("users");
        html.append("<div class='card'><div class='card-title'>👥 ")
                .append(isArabic ? "المستخدمين" : "Users")
                .append("</div>");
        if (users != null && users.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "الاسم" : "Name").append("</th><th>")
                    .append(isArabic ? "الصلاحية" : "Role").append("</th><th>")
                    .append(isArabic ? "الحالة" : "Status").append("</th></tr>");
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u != null) {
                    html.append("<tr><td>").append(u.optString("display_name", "—")).append("</td>")
                            .append("<td>").append(u.optString("role_name", "—")).append("</td>")
                            .append("<td>").append(u.optString("status", "—")).append("</td></tr>");
                }
            }
            html.append("</table>");
        }
        html.append("</div>");
    }

    private void renderFinancialHTML(StringBuilder html, JSONObject data) {
        JSONArray entries = data.optJSONArray("journal_entries");
        html.append("<div class='card'><div class='card-title'>💵 ")
                .append(isArabic ? "الحركة المالية" : "Financial Journal")
                .append("</div>");
        if (entries != null && entries.length() > 0) {
            html.append("<table><tr><th>#</th><th>").append(isArabic ? "البيان" : "Description").append("</th><th>")
                    .append(isArabic ? "المبلغ" : "Amount").append("</th></tr>");
            for (int i = 0; i < Math.min(entries.length(), 50); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e != null) {
                    html.append("<tr><td>").append(i + 1).append("</td>")
                            .append("<td>").append(e.optString("description", "—")).append("</td>")
                            .append("<td class='gold'>").append(String.format(Locale.US, "%,.0f", e.optDouble("amount", 0))).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>").append(isArabic ? "لا توجد قيود" : "No entries").append("</p>");
        }
        html.append("</div>");
    }

    private String metricBox(String icon, String val, String lbl) {
        return metricBox(icon, val, lbl, "");
    }

    private String metricBox(String icon, String val, String lbl, String extraClass) {
        return "<div class='metric'><div style='font-size:16px'>" + icon + "</div>" +
                "<div class='val " + extraClass + "'>" + val + "</div>" +
                "<div class='lbl'>" + lbl + "</div></div>";
    }

    private void populateUsersSpinner(Spinner spinner) {
    if (getContext() == null || apiClient == null) return;

    // ✅ التعديل: استخدم المسار النسبي فقط بدون الدومين
    String url = "/reports-analytics?type=users_list";

    apiClient.get(url, new MiraApiClient.ApiCallback() {
        @Override
        public void onSuccess(MiraApiClient.ApiResponse response) {
            if (!isAdded() || getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                try {
                    String jsonResponse = response.getBody() != null ? response.getBody() : "";
                    JSONObject responseObj = new JSONObject(jsonResponse);
                    JSONArray usersArray = responseObj.optJSONArray("data");
                    List<UserSpinnerItem> userItems = new ArrayList<>();

                    userItems.add(new UserSpinnerItem(0, isArabic ? "-- اختر الموظف --" : "-- Select User --"));

                    if (usersArray != null) {
                        for (int i = 0; i < usersArray.length(); i++) {
                            JSONObject u = usersArray.optJSONObject(i);
                            if (u != null) {
                                userItems.add(new UserSpinnerItem(
                                    u.optInt("ID", u.optInt("id")),
                                    u.optString("display_name", u.optString("user_login"))
                                ));
                            }
                        }
                    }

                    android.widget.ArrayAdapter<UserSpinnerItem> adapter = new android.widget.ArrayAdapter<>(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        userItems
                    );
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinner.setAdapter(adapter);

                } catch (Exception e) {
                    Log.e(TAG, "Error parsing users list", e);
                }
            });
        }

        @Override
        public void onFailure(String errorMessage) {
            Log.e(TAG, "Failed to load users for spinner: " + errorMessage);
        }

        @Override
        public void onError(String errorMessage) {
            Log.e(TAG, "Failed to load users for spinner: " + errorMessage);
        }
    });
}

    private void printReportWebView(WebView webView, String jobTitle) {
        if (!isAdded() || getContext() == null || webView == null) return;
        PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (pm != null) {
            pm.print(jobTitle, webView.createPrintDocumentAdapter(jobTitle), new PrintAttributes.Builder().build());
        }
    }

    private void exportReportToCSV(String type, String from, String to) {
        if (!isAdded() || getContext() == null) return;

        executorService.execute(() -> {
            try {
                String cacheKey = "cache_pro_" + type + "_" + from + "_" + to;
                String cached = offlineCache != null ? offlineCache.getString(cacheKey, null) : null;

                if (cached == null) {
                    mainHandler.post(() -> Toast.makeText(getContext(), isArabic ? "لا توجد بيانات للتصدير" : "No data to export", Toast.LENGTH_SHORT).show());
                    return;
                }

                JSONObject data = new JSONObject(cached);
                StringBuilder csvContent = new StringBuilder();

                csvContent.append("\uFEFF");
                csvContent.append("Report Type,").append(type).append("\n");
                csvContent.append("From,").append(from).append(",To,").append(to).append("\n\n");

                buildFormattedCSV(type, data, csvContent);

                File exportDir = new File(requireContext().getCacheDir(), "exports");
                if (!exportDir.exists()) exportDir.mkdirs();

                File file = new File(exportDir, "MIRA_Report_" + type + "_" + System.currentTimeMillis() + ".csv");
                FileOutputStream fos = new FileOutputStream(file);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);

                writer.write(csvContent.toString());
                writer.flush();
                writer.close();

                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    Uri contentUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/csv");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "Share CSV"));
                });

            } catch (Exception e) {
                Log.e(TAG, "Export CSV error", e);
                mainHandler.post(() -> Toast.makeText(getContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void buildFormattedCSV(String type, JSONObject data, StringBuilder csv) {
        switch (type) {
            case "inventory":
                JSONArray items = data.optJSONArray("items");
                csv.append("Index,Item Title,Karat,Weight(g),Status,Location\n");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        csv.append(i + 1).append(",")
                           .append(escapeCsv(item.optString("item_title", "-"))).append(",")
                           .append(escapeCsv(item.optString("gold_karat", "-"))).append(",")
                           .append(item.optDouble("gross_weight_grams", 0)).append(",")
                           .append(escapeCsv(item.optString("vault_status", "-"))).append(",")
                           .append(escapeCsv(item.optString("current_location", "-"))).append("\n");
                    }
                }
                break;

            case "sales":
                JSONArray sales = data.optJSONArray("invoices");
                if (sales == null) sales = data.optJSONArray("sales");
                csv.append("Invoice ID,Amount,Payment Method,Seller\n");
                if (sales != null) {
                    for (int i = 0; i < sales.length(); i++) {
                        JSONObject s = sales.optJSONObject(i);
                        if (s == null) continue;
                        csv.append(escapeCsv(s.optString("id", s.optString("invoice_no", "-")))).append(",")
                           .append(s.optDouble("final_amount", s.optDouble("subtotal", 0.0))).append(",")
                           .append(escapeCsv(s.optString("payment_method", "CASH"))).append(",")
                           .append(escapeCsv(s.optString("seller_name", "-"))).append("\n");
                    }
                }
                break;

            default:
                csv.append("Raw Data Summary\n");
                csv.append(escapeCsv(data.toString())).append("\n");
                break;
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "\"\"";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String getDefaultFromDate() { return getCalculatedDate(-30); }
    private String getDefaultToDate() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    private String getCalculatedDate(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private void showDatePicker(DatePickerCallback cb) {
        if (!isAdded() || getContext() == null) return;
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(requireContext(),
                (v, y, m, d) -> cb.onDate(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    static class ReportItem {
        String icon, title, subtitle, type;
        ReportItem(String i, String t, String s, String ty) { icon=i; title=t; subtitle=s; type=ty; }
    }

    class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.VH> {
        final List<ReportItem> list;
        final OnReportClickListener listener;
        ReportsAdapter(List<ReportItem> l, OnReportClickListener c) { list=l; listener=c; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_card_pro, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReportItem item = list.get(pos);
            h.tvIcon.setText(item.icon);
            h.tvTitle.setText(item.title);
            h.tvSubtitle.setText(item.subtitle);
            h.itemView.setOnClickListener(v -> listener.onClick(item));
        }

        @Override
        public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvTitle, tvSubtitle;
            VH(View v) { super(v);
                tvIcon = v.findViewById(R.id.tvReportIcon);
                tvTitle = v.findViewById(R.id.tvReportTitle);
                tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
            }
        }
    }

    interface OnReportClickListener { void onClick(ReportItem item); }
    interface DatePickerCallback { void onDate(String date); }

    static class UserSpinnerItem {
        private final int id;
        private final String name;

        public UserSpinnerItem(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() { return id; }
        public String getName() { return name; }

        @NonNull
        @Override
        public String toString() { return name; }
    }
                            }
