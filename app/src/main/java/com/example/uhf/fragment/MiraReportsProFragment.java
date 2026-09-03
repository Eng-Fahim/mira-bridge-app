package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;  // ✅ أضف هذا
import android.util.TypedValue;
import android.net.Uri;  // ✅ أضف هذا

import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.ImageView;
import com.example.uhf.charts.ReportChartGenerator;
import androidx.core.content.FileProvider;  // ✅ أضف هذا

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.adapter.ReportAdapters;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.export.ReportExporter;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.ReportModels;
import com.example.uhf.print.ReportPrintManager;
import com.example.uhf.repository.ReportRepository;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class MiraReportsProFragment extends Fragment {
    private static final String TAG = "MiraReportsPro";
    
    private RecyclerView rvReports;
    private ReportsAdapter adapter;
    private final List<ReportModels.ReportItem> reportList = new ArrayList<>();
    private final List<ReportModels.ReportItem> filteredReportList = new ArrayList<>();
    
    private MiraOrgProfileManager orgProfile;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private MiraPriceManager priceManager;
    private ReportRepository reportRepository;
    
    private boolean isArabic;
    private int currentBranchId = 1;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private JSONObject lastLoadedData = null;
    private String lastLoadedType = "";
    private String lastFromDate = "";
    private String lastToDate = "";
    
    @Nullable
@Override
public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                         @Nullable Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);
    
    // ✅ تطبيق الثيم
    applyTheme(view);
    
    initializeManagers();
    initializeViews(view);
    initializeEnhancedViews(view);
    buildReportsList();
    
    return view;
}

/**
 * ✅ تطبيق الثيم على العناصر
 */
private void applyTheme(View view) {
    try {
        Context context = getContext();
        if (context == null) return;
        
        // ✅ استخدام ألوان الثيم الديناميكية
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
        int primaryColor = typedValue.data;
        
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true);
        int surfaceColor = typedValue.data;
        
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true);
        int primaryContainerColor = typedValue.data;
        
        // ✅ الجذر
        View rootView = view.findViewById(R.id.fragmentRoot);
        if (rootView != null) {
            rootView.setBackgroundColor(surfaceColor);
        }
        
        // ✅ SwipeRefresh
        SwipeRefreshLayout swipeRefresh = view.findViewById(R.id.swipeRefreshReports);
        if (swipeRefresh != null) {
            swipeRefresh.setBackgroundColor(surfaceColor);
            swipeRefresh.setColorSchemeColors(primaryColor);
        }
        
        // ✅ TabLayout
        TabLayout tabCategories = view.findViewById(R.id.tabReportCategories);
        if (tabCategories != null) {
            tabCategories.setBackgroundColor(primaryContainerColor);
            
            // الحصول على ألوان النص المناسبة
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, typedValue, true);
            int onPrimaryContainerColor = typedValue.data;
            
            context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
            int accentColor = typedValue.data;
            
            tabCategories.setTabTextColors(
                onPrimaryContainerColor,
                onPrimaryContainerColor
            );
            tabCategories.setSelectedTabIndicatorColor(accentColor);
        }
        
        // ✅ RecyclerView
        if (rvReports != null) {
            rvReports.setBackgroundColor(surfaceColor);
        }
    } catch (Exception e) {
        Log.e(TAG, "applyTheme error: " + e.getMessage());
    }
}
    
    @Override
    public void onResume() {
        super.onResume();
        if (branchesManager != null) {
            currentBranchId = branchesManager.getActiveBranchId();
        }
    }
    
    private void initializeManagers() {
        Context context = getContext();
        if (context == null) return;
        
        orgProfile = MiraOrgProfileManager.getInstance(context);
        apiClient = MiraApiClient.getInstance(context);
        branchesManager = MiraBranchesManager.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        priceManager = MiraPriceManager.getInstance(context);
        reportRepository = ReportRepository.getInstance(context);
        
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
    }
    
    private void initializeViews(View view) {
        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new GridLayoutManager(getContext(), 2));
        
        adapter = new ReportsAdapter(filteredReportList, this::openReportDialog);
        rvReports.setAdapter(adapter);
    }
    
    private void initializeEnhancedViews(View view) {
        SwipeRefreshLayout swipeRefresh = view.findViewById(R.id.swipeRefreshReports);
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(() -> {
                buildReportsList();
                swipeRefresh.setRefreshing(false);
            });
        }
        
        TabLayout tabCategories = view.findViewById(R.id.tabReportCategories);
        if (tabCategories != null) {
            tabCategories.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    filterReportsByCategory(tab.getPosition());
                }
                @Override
                public void onTabUnselected(TabLayout.Tab tab) {}
                @Override
                public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
        
        MaterialButton btnSearch = view.findViewById(R.id.btnSearchReports);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> showSearchDialog());
        }
        
        ExtendedFloatingActionButton fabExport = view.findViewById(R.id.fabQuickExport);
        if (fabExport != null) {
            fabExport.setOnClickListener(v -> showQuickExportDialog());
        }
        
        TextView tvBranchInfo = view.findViewById(R.id.tvBranchInfo);
        if (tvBranchInfo != null && branchesManager != null) {
            tvBranchInfo.setText(branchesManager.getActiveBranchName());
        }
    }
    
        private void loadReportData(String reportType, String fromDate, String toDate, int branchId) {
        // استخدام دالة إظهار التحميل المصممة في واجهتك
        showLoadingContainer(true);

        new Thread(() -> {
            MiraApiClient apiClient = MiraApiClient.getInstance(getContext());
            MiraApiClient.ApiResponse response = apiClient.getReportData(reportType, fromDate, toDate, branchId);

            if (getActivity() == null) return;

            getActivity().runOnUiThread(() -> {
                showLoadingContainer(false);
                if (response != null && response.isSuccess && response.data != null) {
                    handleReportSuccess(reportType, response.data);
                } else {
                    String errorMsg = (response != null && response.error != null) ? response.error : "فشل جلب البيانات";
                    Toast.makeText(getContext(), "❌ " + errorMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void handleReportSuccess(String reportType, JSONObject data) {
        lastLoadedData = data;
        lastLoadedType = reportType;

        RecyclerView.Adapter<?> adapter = createAdapterForType(reportType, data);
        if (rvReports != null && adapter != null) {
            rvReports.setAdapter(adapter);
        }
    }

    private void showLoadingContainer(boolean show) {
        if (getView() == null) return;
        View layoutLoading = getView().findViewById(R.id.swipeRefreshReports);
        if (layoutLoading instanceof SwipeRefreshLayout) {
            ((SwipeRefreshLayout) layoutLoading).setRefreshing(show);
        }
    }

    
    private void buildReportsList() {
        reportList.clear();
        
        if (isArabic) {
            reportList.add(new ReportModels.ReportItem("📊", "لوحة القيادة", "نظرة شاملة على الأداء", 
                "dashboard", ReportModels.ReportCategory.GENERAL, false, R.color.icon_dashboard));
            reportList.add(new ReportModels.ReportItem("💰", "المبيعات", "الفواتير والعملاء", 
                "sales", ReportModels.ReportCategory.SALES, false, R.color.icon_sales));
            reportList.add(new ReportModels.ReportItem("📦", "المخزون", "كل القطع وتفاصيلها", 
                "inventory", ReportModels.ReportCategory.INVENTORY, false, R.color.icon_inventory));
            reportList.add(new ReportModels.ReportItem("🔄", "التحويلات", "حركة القطع بين الفروع", 
                "movement", ReportModels.ReportCategory.MOVEMENT, false, R.color.icon_movement));
            reportList.add(new ReportModels.ReportItem("🏢", "أداء الفروع", "مقارنة شاملة", 
                "branches", ReportModels.ReportCategory.GENERAL, false, R.color.icon_branches));
            reportList.add(new ReportModels.ReportItem("🚪", "البوابات", "سجل المرور الأمني", 
                "gates", ReportModels.ReportCategory.SECURITY, false, R.color.icon_gates));
            reportList.add(new ReportModels.ReportItem("📋", "الجرد", "الدقة والفروقات", 
                "audit", ReportModels.ReportCategory.INVENTORY, false, R.color.icon_audit));
            reportList.add(new ReportModels.ReportItem("💎", "النواقص", "الأكثر مبيعاً", 
                "reorder", ReportModels.ReportCategory.INVENTORY, false, R.color.icon_reorder));
            reportList.add(new ReportModels.ReportItem("👥", "المستخدمين", "الموظفين والأدوار", 
                "users", ReportModels.ReportCategory.GENERAL, false, R.color.icon_users));
            reportList.add(new ReportModels.ReportItem("💵", "المالية", "القيود المحاسبية", 
                "financial", ReportModels.ReportCategory.FINANCIAL, false, R.color.icon_financial));
            
            reportList.add(new ReportModels.ReportItem("⏱️", "تايم لاين", "سجل نشاطات الموظف", 
                "user_timeline", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("💠", "بصمة القطعة", "سجل حياة القطعة", 
                "item_passport", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("🛡️", "مؤشر الأمان", "تحليل المرور", 
                "gate_security_index", ReportModels.ReportCategory.SECURITY, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("📅", "أعمار المخزون", "تحليل الركود", 
                "inventory_aging", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("⚠️", "الفروقات", "فروقات الجرد", 
                "audit_discrepancy_detail", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
        } else {
            reportList.add(new ReportModels.ReportItem("📊", "Dashboard", "Performance overview", 
                "dashboard", ReportModels.ReportCategory.GENERAL, false, R.color.icon_dashboard));
            reportList.add(new ReportModels.ReportItem("💰", "Sales", "Invoices & customers", 
                "sales", ReportModels.ReportCategory.SALES, false, R.color.icon_sales));
            reportList.add(new ReportModels.ReportItem("📦", "Inventory", "All items & details", 
                "inventory", ReportModels.ReportCategory.INVENTORY, false, R.color.icon_inventory));
            reportList.add(new ReportModels.ReportItem("🔄", "Transfers", "Branch movements", 
                "movement", ReportModels.ReportCategory.MOVEMENT, false, R.color.icon_movement));
            reportList.add(new ReportModels.ReportItem("🏢", "Branches", "Performance comparison", 
                "branches", ReportModels.ReportCategory.GENERAL, false, R.color.icon_branches));
            reportList.add(new ReportModels.ReportItem("🚪", "Gates", "Security gate logs", 
                "gates", ReportModels.ReportCategory.SECURITY, false, R.color.icon_gates));
            reportList.add(new ReportModels.ReportItem("📋", "Audit", "Accuracy & discrepancies", 
                "audit", ReportModels.ReportCategory.INVENTORY, false, R.color.icon_audit));
            reportList.add(new ReportModels.ReportItem("💎", "Reorder", "Best sellers", 
                "reorder", ReportModels.ReportCategory.INVENTORY, false, R.color.icon_reorder));
            reportList.add(new ReportModels.ReportItem("👥", "Users", "Staff & roles", 
                "users", ReportModels.ReportCategory.GENERAL, false, R.color.icon_users));
            reportList.add(new ReportModels.ReportItem("💵", "Financial", "Journal entries", 
                "financial", ReportModels.ReportCategory.FINANCIAL, false, R.color.icon_financial));
            
            reportList.add(new ReportModels.ReportItem("⏱️", "Timeline", "User activity log", 
                "user_timeline", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("💠", "Passport", "Item lifecycle", 
                "item_passport", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("🛡️", "Security", "Gate analysis", 
                "gate_security_index", ReportModels.ReportCategory.SECURITY, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("📅", "Aging", "Stock stagnation", 
                "inventory_aging", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
            reportList.add(new ReportModels.ReportItem("⚠️", "Discrepancies", "Audit details", 
                "audit_discrepancy_detail", ReportModels.ReportCategory.ANALYTICS, true, R.color.icon_advanced));
        }
        
        filteredReportList.clear();
        filteredReportList.addAll(reportList);
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
    
    private void filterReportsByCategory(int categoryPosition) {
        filteredReportList.clear();
        
        if (categoryPosition == 0) {
            filteredReportList.addAll(reportList);
        } else {
            ReportModels.ReportCategory targetCategory = ReportModels.ReportCategory.values()[categoryPosition - 1];
            for (ReportModels.ReportItem item : reportList) {
                if (item.category == targetCategory) {
                    filteredReportList.add(item);
                }
            }
        }
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
    
    private void showSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "🔍 بحث في التقارير" : "🔍 Search Reports");
        
        View searchView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_search_reports, null);
        EditText etSearch = searchView.findViewById(R.id.etSearchReports);
        
        builder.setView(searchView);
        builder.setPositiveButton(isArabic ? "بحث" : "Search", (dialog, which) -> {
            String query = etSearch.getText().toString().trim();
            if (!query.isEmpty()) {
                searchReports(query);
            }
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void searchReports(String query) {
        filteredReportList.clear();
        String lowerQuery = query.toLowerCase();
        
        for (ReportModels.ReportItem item : reportList) {
            if (item.title.toLowerCase().contains(lowerQuery) ||
                item.subtitle.toLowerCase().contains(lowerQuery) ||
                item.type.toLowerCase().contains(lowerQuery)) {
                filteredReportList.add(item);
            }
        }
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        
        Toast.makeText(getContext(), 
            isArabic ? "✅ تم العثور على " + filteredReportList.size() + " تقرير" : 
            "✅ Found " + filteredReportList.size() + " reports", 
            Toast.LENGTH_SHORT).show();
    }
    
    private void showQuickExportDialog() {
        if (lastLoadedData == null) {
            Toast.makeText(getContext(), 
                isArabic ? "⚠️ لا توجد بيانات للتصدير" : "⚠️ No data to export", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] options = {
            isArabic ? "📄 تصدير CSV" : "📄 Export CSV",
            isArabic ? "📑 تصدير PDF" : "📑 Export PDF",
            isArabic ? "🖨️ طباعة" : "🖨️ Print"
        };
        
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "تصدير سريع" : "Quick Export")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: exportToCSV(); break;
                    case 1: exportToPDF(); break;
                    case 2: printLastReport(); break;
                }
            })
            .show();
    }
    
    private void exportToCSV() {
        if (lastLoadedData == null) return;
        
        File file = ReportExporter.exportToCSV(requireContext(), lastLoadedData, 
            lastLoadedType, lastFromDate, lastToDate, isArabic);
        
        if (file != null) {
            ReportExporter.shareFile(requireContext(), file, "text/csv");
        }
    }
    
    private void exportToPDF() {
        if (lastLoadedData == null) return;
        
        File file = ReportExporter.exportToPDF(requireContext(), lastLoadedData, 
            lastLoadedType, lastFromDate, lastToDate, isArabic);
        
        if (file != null) {
            ReportExporter.shareFile(requireContext(), file, "application/pdf");
        }
    }
    
    private void printLastReport() {
        if (lastLoadedData == null) return;
        
        String html = ReportPrintManager.generateReportHtml(lastLoadedData, lastLoadedType,
            orgProfile != null ? orgProfile.getOrgName() : null,
            branchesManager != null ? branchesManager.getActiveBranchName() : null,
            lastFromDate, lastToDate, isArabic);
        
        ReportPrintManager.printHtmlReport(requireContext(), html, 
            "MIRA_Report_" + lastLoadedType);
    }
    
    private void openReportDialog(ReportModels.ReportItem item) {
        if (getContext() == null || !isAdded()) return;
        
        if (permissionManager != null && !permissionManager.canAccessFragment("Reports")) {
            Toast.makeText(getContext(), 
                isArabic ? "⛔ غير مصرح" : "⛔ Access Denied", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (item.isAdvanced) {
            handleAdvancedReport(item);
        } else {
            showFilteredReportDialog(item);
        }
    }
    
    private void handleAdvancedReport(ReportModels.ReportItem item) {
        switch (item.type) {
            case "user_timeline":
                showUserTimelineDialog(item);
                break;
            case "item_passport":
                showItemPassportDialog(item);
                break;
            case "gate_security_index":
                showGateSecurityDialog(item);
                break;
            case "inventory_aging":
                fetchAdvancedReport(item.type, null);
                break;
            case "audit_discrepancy_detail":
                showAuditDiscrepancyDialog(item);
                break;
        }
    }
    
    /**
 * ✅ إنشاء رسم بياني حسب نوع التقرير
 */
private Bitmap createChartForReport(String type, JSONObject data) {
    if (data == null) return null;
    
    switch (type) {
        case "dashboard":
            return createKaratBarChart(data);
        case "sales":
            return createPaymentPieChart(data);
        case "inventory":
            return createKaratBarChart(data);
        case "branches":
            return createBranchesBarChart(data);
        case "gates":
            return createGatesPieChart(data);
        case "audit":
            return createAuditPieChart(data);
        case "users":
            return createUsersBarChart(data);
        case "reorder":
            return createKaratBarChart(data);
        case "financial":
            return createFinancialBarChart(data);
        default:
            return null;
    }
}

/**
 * ✅ رسم شريطي للعيارات
 */
private Bitmap createKaratBarChart(JSONObject data) {
    try {
        List<ReportChartGenerator.ChartData> chartData = new ArrayList<>();
        
        // محاولة من karat_breakdown
        JSONArray karatBreakdown = data.optJSONArray("karat_breakdown");
        if (karatBreakdown == null) {
            karatBreakdown = data.optJSONArray("breakdown_by_karat");
        }
        if (karatBreakdown == null) {
            karatBreakdown = data.optJSONArray("reorder_items");
        }
        
        if (karatBreakdown != null && karatBreakdown.length() > 0) {
            int[] colors = {Color.parseColor("#C9A961"), Color.parseColor("#E5C87E"), 
                           Color.parseColor("#A8893F"), Color.parseColor("#8C6D23"),
                           Color.parseColor("#6B8E23")};
            for (int i = 0; i < karatBreakdown.length(); i++) {
                JSONObject obj = karatBreakdown.optJSONObject(i);
                if (obj != null) {
                    String label = obj.optString("gold_karat", obj.optString("karat", "K"));
                    float value = (float) obj.optDouble("weight", 
                        obj.optDouble("gross_weight", obj.optDouble("count", 
                        obj.optDouble("remaining_stock", 0))));
                    chartData.add(new ReportChartGenerator.ChartData(
                        label, value, colors[i % colors.length]));
                }
            }
        }
        
        // محاولة من items
        if (chartData.isEmpty()) {
            JSONArray items = extractItemsArray(data);
            if (items != null) {
                java.util.Map<String, Float> karatMap = new java.util.HashMap<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) {
                        String karat = item.optString("gold_karat", item.optString("karat", "غير محدد"));
                        float weight = (float) item.optDouble("gross_weight_grams", 
                            item.optDouble("weight", 0));
                        karatMap.merge(karat, weight, Float::sum);
                    }
                }
                
                int[] colors = {Color.parseColor("#C9A961"), Color.parseColor("#E5C87E"), 
                               Color.parseColor("#A8893F"), Color.parseColor("#8C6D23")};
                int idx = 0;
                for (java.util.Map.Entry<String, Float> entry : karatMap.entrySet()) {
                    chartData.add(new ReportChartGenerator.ChartData(
                        entry.getKey(), entry.getValue(), colors[idx % colors.length]));
                    idx++;
                }
            }
        }
        
        if (!chartData.isEmpty()) {
            return ReportChartGenerator.generateBarChart(chartData,
                isArabic ? "الوزن حسب العيار" : "Weight by Karat",
                800, 500, isArabic);
        }
    } catch (Exception e) {
        Log.e(TAG, "Karat chart error: " + e.getMessage());
    }
    return null;
}

/**
 * ✅ رسم دائري لطرق الدفع
 */
private Bitmap createPaymentPieChart(JSONObject data) {
    try {
        JSONArray paymentBreakdown = data.optJSONArray("payment_breakdown");
        
        if (paymentBreakdown != null && paymentBreakdown.length() > 0) {
            List<ReportChartGenerator.ChartData> chartData = new ArrayList<>();
            int[] colors = {Color.parseColor("#4CAF50"), Color.parseColor("#2196F3"), 
                           Color.parseColor("#9C27B0"), Color.parseColor("#FF9800")};
            
            for (int i = 0; i < paymentBreakdown.length(); i++) {
                JSONObject payment = paymentBreakdown.optJSONObject(i);
                if (payment != null) {
                    String method = payment.optString("payment_method", "CASH");
                    float total = (float) payment.optDouble("total", 0);
                    String label = method;
                    if (isArabic) {
                        switch (method.toLowerCase()) {
                            case "cash": label = "نقدي"; break;
                            case "card": label = "بطاقة"; break;
                            case "transfer": label = "تحويل"; break;
                        }
                    }
                    chartData.add(new ReportChartGenerator.ChartData(
                        label, total, colors[i % colors.length]));
                }
            }
            
            if (!chartData.isEmpty()) {
                return ReportChartGenerator.generatePieChart(chartData,
                    isArabic ? "طرق الدفع" : "Payment Methods",
                    800, 600, isArabic);
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Payment chart error: " + e.getMessage());
    }
    return null;
}

/**
 * ✅ رسم دائري للجرد
 */
private Bitmap createAuditPieChart(JSONObject data) {
    try {
        JSONObject auditSummary = data.optJSONObject("audit_summary");
        
        if (auditSummary != null) {
            int found = auditSummary.optInt("found_items", 0);
            int missing = auditSummary.optInt("missing_items", 0);
            
            if (found > 0 || missing > 0) {
                List<ReportChartGenerator.ChartData> chartData = new ArrayList<>();
                chartData.add(new ReportChartGenerator.ChartData(
                    isArabic ? "موجود" : "Found", found, Color.parseColor("#4CAF50")));
                chartData.add(new ReportChartGenerator.ChartData(
                    isArabic ? "مفقود" : "Missing", missing, Color.parseColor("#F44336")));
                
                return ReportChartGenerator.generatePieChart(chartData,
                    isArabic ? "نتيجة الجرد" : "Audit Result",
                    800, 600, isArabic);
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Audit chart error: " + e.getMessage());
    }
    return null;
}

/**
 * ✅ رسم شريطي للفروع
 */
private Bitmap createBranchesBarChart(JSONObject data) {
    try {
        JSONArray branches = data.optJSONArray("branches_performance");
        if (branches == null) branches = data.optJSONArray("branches");
        
        if (branches != null && branches.length() > 0) {
            List<ReportChartGenerator.ChartData> chartData = new ArrayList<>();
            int[] colors = {Color.parseColor("#5C6BC0"), Color.parseColor("#26A69A"), 
                           Color.parseColor("#FF7043"), Color.parseColor("#AB47BC")};
            
            for (int i = 0; i < branches.length(); i++) {
                JSONObject branch = branches.optJSONObject(i);
                if (branch != null) {
                    String name = branch.optString("branch_name", 
                        branch.optString("branch_name_ar", "فرع " + (i+1)));
                    int count = branch.optInt("items_count", branch.optInt("count", 0));
                    
                    if (name.length() > 12) name = name.substring(0, 12) + "...";
                    
                    chartData.add(new ReportChartGenerator.ChartData(
                        name, count, colors[i % colors.length]));
                }
            }
            
            if (!chartData.isEmpty()) {
                return ReportChartGenerator.generateBarChart(chartData,
                    isArabic ? "القطع حسب الفرع" : "Items by Branch",
                    800, 500, isArabic);
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Branches chart error: " + e.getMessage());
    }
    return null;
}

/**
 * ✅ رسم دائري للبوابات
 */
private Bitmap createGatesPieChart(JSONObject data) {
    try {
        JSONArray gateLogs = data.optJSONArray("gate_logs");
        if (gateLogs == null) gateLogs = data.optJSONArray("gates");
        
        if (gateLogs != null && gateLogs.length() > 0) {
            int allowed = 0, alerts = 0;
            
            for (int i = 0; i < gateLogs.length(); i++) {
                JSONObject log = gateLogs.optJSONObject(i);
                if (log != null) {
                    int isAllowed = log.optInt("allowed", 1);
                    if (isAllowed == 1) allowed++;
                    else alerts++;
                }
            }
            
            List<ReportChartGenerator.ChartData> chartData = new ArrayList<>();
            chartData.add(new ReportChartGenerator.ChartData(
                isArabic ? "مسموح" : "Allowed", allowed, Color.parseColor("#4CAF50")));
            chartData.add(new ReportChartGenerator.ChartData(
                isArabic ? "إنذار" : "Alert", alerts, Color.parseColor("#F44336")));
            
            return ReportChartGenerator.generatePieChart(chartData,
                isArabic ? "حركة البوابات" : "Gate Activity",
                800, 600, isArabic);
        }
    } catch (Exception e) {
        Log.e(TAG, "Gates chart error: " + e.getMessage());
    }
    return null;
}

/**
 * ✅ رسم شريطي للمستخدمين
 */
private Bitmap createUsersBarChart(JSONObject data) {
    try {
        JSONArray users = data.optJSONArray("users");
        if (users == null) users = data.optJSONArray("data");
        
        if (users != null && users.length() > 0) {
            java.util.Map<String, Integer> roleMap = new java.util.HashMap<>();
            
            for (int i = 0; i < users.length(); i++) {
                JSONObject user = users.optJSONObject(i);
                if (user != null) {
                    String role = user.optString("role_name", user.optString("role", "غير محدد"));
                    roleMap.merge(role, 1, Integer::sum);
                }
            }
            
            List<ReportChartGenerator.ChartData> chartData = new ArrayList<>();
            int[] colors = {Color.parseColor("#7E57C2"), Color.parseColor("#26A69A"), 
                           Color.parseColor("#FF7043"), Color.parseColor("#42A5F5")};
            int idx = 0;
            for (java.util.Map.Entry<String, Integer> entry : roleMap.entrySet()) {
                chartData.add(new ReportChartGenerator.ChartData(
                    entry.getKey(), entry.getValue(), colors[idx % colors.length]));
                idx++;
            }
            
            if (!chartData.isEmpty()) {
                return ReportChartGenerator.generateBarChart(chartData,
                    isArabic ? "المستخدمين حسب الدور" : "Users by Role",
                    800, 500, isArabic);
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Users chart error: " + e.getMessage());
    }
    return null;
}

/**
 * ✅ رسم شريطي للمالية
 */
private Bitmap createFinancialBarChart(JSONObject data) {
    try {
        JSONArray entries = data.optJSONArray("journal_entries");
        if (entries == null) entries = data.optJSONArray("entries");
        
        if (entries != null && entries.length() > 0) {
            // تجميع حسب النوع
            java.util.Map<String, Float> typeMap = new java.util.HashMap<>();
            
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null) {
                    String type = entry.optString("entry_type", entry.optString("type", "أخرى"));
                    float amount = (float) entry.optDouble("amount", 
                        entry.optDouble("debit", entry.optDouble("credit", 0)));
                    typeMap.merge(type, amount, Float::sum);
                }
            }
            
            List<ReportChartGenerator.ChartData> chartData = new ArrayList<>();
            int[] colors = {Color.parseColor("#26C6DA"), Color.parseColor("#4CAF50"), 
                           Color.parseColor("#F44336"), Color.parseColor("#FF9800")};
            int idx = 0;
            for (java.util.Map.Entry<String, Float> entry : typeMap.entrySet()) {
                chartData.add(new ReportChartGenerator.ChartData(
                    entry.getKey(), entry.getValue(), colors[idx % colors.length]));
                idx++;
            }
            
            if (!chartData.isEmpty()) {
                return ReportChartGenerator.generateBarChart(chartData,
                    isArabic ? "الحركات المالية" : "Financial Movements",
                    800, 500, isArabic);
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Financial chart error: " + e.getMessage());
    }
    return null;
}
    
    private void showFilteredReportDialog(ReportModels.ReportItem item) {
        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_mira_report_viewer, null);
        
        TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
        RecyclerView rvData = dialogView.findViewById(R.id.rvReportData);
        View layoutLoading = dialogView.findViewById(R.id.layoutLoadingContainer);
        TextView tvLoadingStatus = dialogView.findViewById(R.id.tvLoadingStatus);
        MaterialButton btnFrom = dialogView.findViewById(R.id.btnReportDateFrom);
        MaterialButton btnTo = dialogView.findViewById(R.id.btnReportDateTo);
        MaterialButton btnApply = dialogView.findViewById(R.id.btnReportApplyFilter);
        MaterialButton btnExport = dialogView.findViewById(R.id.btnReportExport);
        MaterialButton btnPrint = dialogView.findViewById(R.id.btnReportPrint);
        MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);
        MaterialButton btnAdvancedFilter = dialogView.findViewById(R.id.btnAdvancedFilter);
        
        final String[] selectedDates = new String[]{getDefaultFromDate(), getDefaultToDate()};
        final String[] selectedFilters = new String[]{"", "", ""};
        
        tvTitle.setText(item.icon + " " + item.title);
        rvData.setLayoutManager(new LinearLayoutManager(getContext()));
        
        btnFrom.setText((isArabic ? "من: " : "From: ") + selectedDates[0]);
        btnTo.setText((isArabic ? "إلى: " : "To: ") + selectedDates[1]);
        
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), 
            android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
            .setView(dialogView)
            .create();
        
        btnFrom.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[0] = date;
            btnFrom.setText((isArabic ? "من: " : "From: ") + date);
        }));
        
        btnTo.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[1] = date;
            btnTo.setText((isArabic ? "إلى: " : "To: ") + date);
        }));
        
        if (btnAdvancedFilter != null) {
            btnAdvancedFilter.setOnClickListener(v -> 
                showAdvancedFilterDialog(selectedFilters, () -> {
                    loadReportWithFilters(item.type, rvData, layoutLoading, 
    tvLoadingStatus, selectedDates, selectedFilters, dialogView);
                }));
        }
        
        btnApply.setOnClickListener(v -> 
    loadReportWithFilters(item.type, rvData, layoutLoading, 
        tvLoadingStatus, selectedDates, selectedFilters, dialogView));
        
        btnExport.setOnClickListener(v -> {
            File file = ReportExporter.exportToCSV(requireContext(), lastLoadedData, 
                item.type, selectedDates[0], selectedDates[1], isArabic);
            if (file != null) {
                ReportExporter.shareFile(requireContext(), file, "text/csv");
            }
        });
        
        if (btnPrint != null) {
            btnPrint.setOnClickListener(v -> {
                String html = ReportPrintManager.generateReportHtml(lastLoadedData, item.type,
                    orgProfile != null ? orgProfile.getOrgName() : null,
                    branchesManager != null ? branchesManager.getActiveBranchName() : null,
                    selectedDates[0], selectedDates[1], isArabic);
                ReportPrintManager.printHtmlReport(requireContext(), html, 
                    "MIRA_Report_" + item.type);
            });
        }
        
        btnClose.setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
        
        loadReportWithFilters(item.type, rvData, layoutLoading, 
    tvLoadingStatus, selectedDates, selectedFilters, dialogView);
    }
    
    private void showAdvancedFilterDialog(String[] filters, Runnable onApply) {
        View filterView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_advanced_filter, null);
        
        EditText etSearch = filterView.findViewById(R.id.etFilterSearch);
        EditText etFilter1 = filterView.findViewById(R.id.etFilter1);
        EditText etFilter2 = filterView.findViewById(R.id.etFilter2);
        
        etSearch.setText(filters[0]);
        etFilter1.setText(filters[1]);
        etFilter2.setText(filters[2]);
        
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "🔍 فلترة متقدمة" : "🔍 Advanced Filter")
            .setView(filterView)
            .setPositiveButton(isArabic ? "تطبيق" : "Apply", (d, which) -> {
                filters[0] = etSearch.getText().toString().trim();
                filters[1] = etFilter1.getText().toString().trim();
                filters[2] = etFilter2.getText().toString().trim();
                onApply.run();
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .create();
        
        dialog.show();
    }
    
    private void loadReportWithFilters(String type, RecyclerView rvData, 
                                   View layoutLoading, TextView tvLoadingStatus,
                                   String[] dates, String[] filters, View dialogView) {
        if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
        if (tvLoadingStatus != null) {
            tvLoadingStatus.setText(isArabic ? "⏳ جاري تحميل البيانات..." : "⏳ Loading data...");
        }
        
        ReportModels.ReportFilters reportFilters = new ReportModels.ReportFilters();
        reportFilters.fromDate = dates[0];
        reportFilters.toDate = dates[1];
        reportFilters.branchId = currentBranchId;
        
        if (filters != null) {
            reportFilters.searchQuery = filters[0];
            reportFilters.filter1 = filters[1];
            reportFilters.filter2 = filters[2];
        }
        
        reportRepository.fetchReport(type, reportFilters).observe(getViewLifecycleOwner(), 
            result -> {
                if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                
                if (result == null || !result.success || result.data == null) {
                    String error = result != null ? result.errorMessage : "Unknown error";
                    Toast.makeText(getContext(), 
                        isArabic ? "❌ فشل التحميل: " + error : "❌ Failed to load: " + error, 
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                
                lastLoadedData = result.data;
                lastLoadedType = type;
                lastFromDate = dates[0];
                lastToDate = dates[1];
                
                RecyclerView.Adapter<?> reportAdapter = createAdapterForType(type, result.data);
                rvData.setAdapter(reportAdapter);
                
                // ✅ عرض الرسم البياني
            // ✅ عرض الرسم البياني
            if (dialogView != null) {
                Bitmap chart = createChartForReport(type, result.data);
                ImageView ivChart = dialogView.findViewById(R.id.ivChart);
                View scrollChart = dialogView.findViewById(R.id.scrollChart);
                if (ivChart != null && scrollChart != null) {
                    if (chart != null) {
                        ivChart.setImageBitmap(chart);
                        scrollChart.setVisibility(View.VISIBLE);
                    } else {
                        scrollChart.setVisibility(View.GONE);
                    }
                }
            }
        });  
}  

    
    private void fetchAdvancedReport(String type, ReportModels.ReportFilters filters) {
        if (filters == null) {
            filters = new ReportModels.ReportFilters();
        }
        filters.branchId = currentBranchId;
        
        reportRepository.fetchAdvancedReport(type, filters).observe(getViewLifecycleOwner(), 
            result -> {
                if (result == null || !result.success || result.data == null) {
                    Toast.makeText(getContext(), 
                        isArabic ? "❌ فشل التحميل" : "❌ Failed to load", 
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                
                lastLoadedData = result.data;
                lastLoadedType = type;
                showAdvancedReportDialog(type, result.data);
            });
    }
    
    private void showAdvancedReportDialog(String type, JSONObject data) {
    View dialogView = LayoutInflater.from(requireContext())
        .inflate(R.layout.dialog_mira_report_viewer, null);
    
    TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
    RecyclerView rvData = dialogView.findViewById(R.id.rvReportData);
    View layoutLoading = dialogView.findViewById(R.id.layoutLoadingContainer);
    MaterialButton btnPrint = dialogView.findViewById(R.id.btnReportPrint);
    MaterialButton btnExport = dialogView.findViewById(R.id.btnReportExport);
    MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);
    
    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
    
    tvTitle.setText(getReportTitle(type));
    rvData.setLayoutManager(new LinearLayoutManager(getContext()));
    
    // ✅ تنسيق البيانات أولاً
    List<ReportModels.GenericItem> formattedItems = parseToFormattedGeneric(data);
    
    RecyclerView.Adapter<?> adapter = new GenericAdapter(formattedItems);
    rvData.setAdapter(adapter);
    
    AlertDialog dialog = new AlertDialog.Builder(requireContext(), 
        android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        .setView(dialogView)
        .create();
    
    if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
    
    if (btnPrint != null) {
        btnPrint.setVisibility(View.VISIBLE);
        btnPrint.setOnClickListener(v -> {
            // ✅ استخدام البيانات المنسقة للطباعة
            String html = generateFormattedHtml(type, formattedItems);
            ReportPrintManager.printHtmlReport(requireContext(), html, 
                "MIRA_Report_" + type);
        });
    }
    
    if (btnExport != null) {
        btnExport.setVisibility(View.VISIBLE);
        btnExport.setOnClickListener(v -> {
            // ✅ استخدام البيانات المنسقة للتصدير
            exportFormattedAdvancedReport(type, formattedItems);
        });
    }
    
    dialog.show();
}

/**
 * ✅ إنشاء HTML منسق من البيانات المعالجة
 */
private String generateFormattedHtml(String type, List<ReportModels.GenericItem> items) {
    String direction = isArabic ? "rtl" : "ltr";
    String textAlign = isArabic ? "right" : "left";
    
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html><html dir='").append(direction).append("'><head><meta charset='utf-8'>")
        .append("<style>")
        .append("@page { size: A4; margin: 15mm; }")
        .append("body { font-family: 'Segoe UI', Tahoma, Arial, sans-serif; direction: ").append(direction)
        .append("; text-align: ").append(textAlign).append("; margin: 0; padding: 20px; color: #1a1a2e; }")
        .append(".header { text-align: center; border-bottom: 3px solid #1a237e; padding-bottom: 15px; margin-bottom: 20px; }")
        .append(".org-name { font-size: 22px; font-weight: bold; color: #1a237e; margin: 0; }")
        .append(".report-title { font-size: 18px; font-weight: bold; color: #c5a059; margin: 10px 0 5px 0; }")
        .append(".report-meta { font-size: 12px; color: #777; margin: 0; }")
        .append("table { width: 100%; border-collapse: collapse; margin-top: 15px; }")
        .append("th { background: #1a237e; color: white; padding: 10px; font-size: 12px; text-align: center; }")
        .append("td { padding: 8px; border: 1px solid #ddd; font-size: 11px; text-align: right; }")
        .append("tr:nth-child(even) { background: #f9f9f9; }")
        .append(".key-col { font-weight: bold; background: #f0f0f0; width: 35%; }")
        .append(".footer { margin-top: 30px; text-align: center; font-size: 10px; color: #999; border-top: 1px solid #ccc; padding-top: 10px; }")
        .append("</style></head><body>");
    
    // الهيدر
    html.append("<div class='header'>")
        .append("<h1 class='org-name'>").append(orgProfile != null ? orgProfile.getOrgName() : "MIRA System").append("</h1>")
        .append("<div class='report-title'>").append(getReportTitle(type)).append("</div>")
        .append("<p class='report-meta'>").append(isArabic ? "تاريخ الإنشاء: " : "Generated: ")
        .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
        .append("</p>")
        .append("</div>");
    
    // جدول البيانات
    html.append("<table><thead><tr>")
        .append("<th>").append(isArabic ? "البيان" : "Item").append("</th>")
        .append("<th>").append(isArabic ? "القيمة" : "Value").append("</th>")
        .append("</tr></thead><tbody>");
    
    for (ReportModels.GenericItem item : items) {
        if (item.key.equals("──────────")) {
            html.append("<tr><td colspan='2' style='background:#f0f0f0;height:2px;padding:0;'></td></tr>");
        } else {
            String value = item.value != null ? item.value.replace("\n", "<br>").replace("\"", "'") : "";
            html.append("<tr>")
                .append("<td class='key-col'>").append(item.key).append("</td>")
                .append("<td>").append(value).append("</td>")
                .append("</tr>");
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
 * ✅ تصدير البيانات المنسقة
 */
private void exportFormattedAdvancedReport(String type, List<ReportModels.GenericItem> items) {
    if (!isAdded() || getContext() == null) return;
    
    try {
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        
        csv.append(isArabic ? "نوع التقرير" : "Report Type").append(",").append(type).append("\n");
        csv.append(isArabic ? "تاريخ التصدير" : "Export Date").append(",")
           .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
           .append("\n\n");
        
        csv.append(isArabic ? "البيان" : "Item").append(",")
           .append(isArabic ? "القيمة" : "Value").append("\n");
        
        for (ReportModels.GenericItem item : items) {
            if (!item.key.equals("──────────")) {
                csv.append("\"").append(item.key.replace("\"", "'")).append("\",")
                   .append("\"").append(item.value.replace("\"", "'").replace("\n", " ")).append("\"\n");
            }
        }
        
        File exportDir = new File(requireContext().getCacheDir(), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();
        
        File file = new File(exportDir, "MIRA_Advanced_" + type + "_" + System.currentTimeMillis() + ".csv");
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(csv.toString().getBytes("UTF-8"));
        fos.close();
        
        Uri uri = FileProvider.getUriForFile(requireContext(), 
            requireContext().getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, isArabic ? "مشاركة التقرير" : "Share Report"));
        
    } catch (Exception e) {
        Log.e(TAG, "Export error: " + e.getMessage());
        Toast.makeText(getContext(), "Export error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
    }
}
    private void showUserTimelineDialog(ReportModels.ReportItem item) {
        reportRepository.fetchUsers().observe(getViewLifecycleOwner(), users -> {
            if (users == null || users.isEmpty()) {
                Toast.makeText(getContext(), 
                    isArabic ? "لا يوجد مستخدمين" : "No users found", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            
            String[] userNames = new String[users.size()];
            for (int i = 0; i < users.size(); i++) {
                ReportModels.UserInfo user = users.get(i);
                userNames[i] = "👤 " + user.name + " (@" + user.username + ")";
            }
            
            new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "👥 اختر المستخدم" : "👥 Select User")
                .setItems(userNames, (dialog, which) -> {
                    ReportModels.UserInfo selectedUser = users.get(which);
                    ReportModels.ReportFilters filters = new ReportModels.ReportFilters();
                    filters.userId = selectedUser.id;
                    filters.fromDate = getDefaultFromDate();
                    filters.toDate = getDefaultToDate();
                    fetchAdvancedReport("user_timeline", filters);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
        });
    }
    
    private void showItemPassportDialog(ReportModels.ReportItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "💠 بصمة القطعة" : "💠 Item Passport");
        
        View inputView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_input_serial, null);
        EditText etSerial = inputView.findViewById(R.id.etSerialNumber);
        
        builder.setView(inputView);
        builder.setPositiveButton(isArabic ? "بحث" : "Search", (dialog, which) -> {
            String serial = etSerial.getText().toString().trim();
            if (!serial.isEmpty()) {
                ReportModels.ReportFilters filters = new ReportModels.ReportFilters();
                filters.serialNumber = serial;
                fetchAdvancedReport("item_passport", filters);
            }
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void showGateSecurityDialog(ReportModels.ReportItem item) {
        reportRepository.fetchGates(currentBranchId).observe(getViewLifecycleOwner(), gates -> {
            if (gates == null || gates.isEmpty()) {
                fetchAdvancedReport("gate_security_index", null);
                return;
            }
            
            String[] options = new String[gates.size() + 1];
            options[0] = isArabic ? "🚪 كل البوابات" : "🚪 All Gates";
            
            for (int i = 0; i < gates.size(); i++) {
                ReportModels.GateInfo gate = gates.get(i);
                options[i + 1] = "🚪 " + gate.name + " (" + gate.gateId + ")";
            }
            
            new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "🛡️ اختر البوابة" : "🛡️ Select Gate")
                .setItems(options, (dialog, which) -> {
                    ReportModels.ReportFilters filters = new ReportModels.ReportFilters();
                    if (which > 0) {
                        filters.gateId = gates.get(which - 1).id;
                    }
                    fetchAdvancedReport("gate_security_index", filters);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
        });
    }
    
    private void showAuditDiscrepancyDialog(ReportModels.ReportItem item) {
        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_mira_report_viewer, null);
        
        TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
        MaterialButton btnFrom = dialogView.findViewById(R.id.btnReportDateFrom);
        MaterialButton btnTo = dialogView.findViewById(R.id.btnReportDateTo);
        MaterialButton btnApply = dialogView.findViewById(R.id.btnReportApplyFilter);
        MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);
        
        final String[] selectedDates = new String[]{getDefaultFromDate(), getDefaultToDate()};
        
        tvTitle.setText(item.icon + " " + item.title);
        btnFrom.setText((isArabic ? "من: " : "From: ") + selectedDates[0]);
        btnTo.setText((isArabic ? "إلى: " : "To: ") + selectedDates[1]);
        
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create();
        
        btnFrom.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[0] = date;
            btnFrom.setText((isArabic ? "من: " : "From: ") + date);
        }));
        
        btnTo.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[1] = date;
            btnTo.setText((isArabic ? "إلى: " : "To: ") + date);
        }));
        
        btnApply.setOnClickListener(v -> {
            ReportModels.ReportFilters filters = new ReportModels.ReportFilters();
            filters.fromDate = selectedDates[0];
            filters.toDate = selectedDates[1];
            fetchAdvancedReport("audit_discrepancy_detail", filters);
            dialog.dismiss();
        });
        
        btnClose.setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
    }
    
    private RecyclerView.Adapter<?> createAdapterForType(String type, JSONObject data) {
        JSONArray itemsArray = extractItemsArray(data);
        String currency = "";
    
        
        switch (type) {
        case "dashboard":
    // ✅ حساب صافي الإيرادات (بعد المرتجعات)
    JSONObject dashboardData = data;
    double grossRevenue = dashboardData.optDouble("total_revenue_usd", 0);
    double refunds = dashboardData.optDouble("total_refunds_usd", 0);
    double netRevenue = grossRevenue - refunds;
    
    try {
        dashboardData.put("net_revenue_usd", netRevenue);
        dashboardData.put("total_refunds_usd", refunds);
    } catch (Exception e) {}
    
    return new GenericAdapter(parseToFormattedGeneric(dashboardData));
           case "sales":
    // ✅ استبعاد المرتجعات من الإيرادات
    JSONArray salesArray = extractItemsArray(data);
    if (salesArray != null) {
        JSONArray filteredSales = new JSONArray();
        for (int i = 0; i < salesArray.length(); i++) {
            JSONObject sale = salesArray.optJSONObject(i);
            if (sale != null) {
                String status = sale.optString("status", "completed");
                String refundStatus = sale.optString("refund_status", "completed");
                
                // ✅ استبعاد المرتجعات من الإيرادات
                if (!"refunded".equals(status) && !"fully_refunded".equals(status)
                    && !"refunded".equals(refundStatus) && !"fully_refunded".equals(refundStatus)) {
                    filteredSales.put(sale);
                }
            }
        }
        return new ReportAdapters.SalesAdapter(filteredSales, isArabic, currency);
    }
    return new ReportAdapters.SalesAdapter(salesArray, isArabic, currency);
            case "inventory":
                return new ReportAdapters.InventoryAdapter(itemsArray, isArabic);
            case "gates":
                return new ReportAdapters.GatesAdapter(itemsArray, isArabic);
            case "users":
                return new ReportAdapters.UsersAdapter(itemsArray, isArabic);
            case "financial":
                return new ReportAdapters.FinancialAdapter(itemsArray, isArabic, currency);
            case "movement":
                return new ReportAdapters.MovementAdapter(itemsArray, isArabic);
            default:
                return new GenericAdapter(parseToFormattedGeneric(data));
        }
    }
    
    private JSONArray extractItemsArray(JSONObject data) {
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
    
    private List<ReportModels.GenericItem> parseToFormattedGeneric(JSONObject data) {
    List<ReportModels.GenericItem> items = new ArrayList<>();
    if (data == null) return items;
    
    // ✅ أولاً: البحث عن المصفوفات المعروفة
    String[] arrayKeys = {
        "items", "invoices", "sales", "movements", "transfers", 
        "users", "gate_logs", "branches_performance", "journal_entries",
        "reorder_items", "timeline", "gate_history", "breakdown_by_karat"
    };
    
    for (String key : arrayKeys) {
        JSONArray arr = data.optJSONArray(key);
        if (arr != null && arr.length() > 0) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String itemKey = keys.next();
                        String value = obj.optString(itemKey, "");
                        String formattedKey = formatKeyName(itemKey, isArabic);
                        String formattedValue = formatValue(value, itemKey, isArabic);
                        items.add(new ReportModels.GenericItem(formattedKey, formattedValue));
                    }
                    if (i < arr.length() - 1) {
                        items.add(new ReportModels.GenericItem("──────────", ""));
                    }
                }
            }
            return items; // ✅ وجدنا المصفوفة
        }
    }
    
    // ✅ ثانياً: البحث عن الكائنات المعروفة
    String[] objectKeys = {"audit_summary", "inventory", "sales", "summary", "item_details", "user_info"};
    boolean foundObject = false;
    
    for (String key : objectKeys) {
        JSONObject obj = data.optJSONObject(key);
        if (obj != null) {
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String itemKey = keys.next();
                String value = obj.optString(itemKey, "");
                String formattedKey = formatKeyName(itemKey, isArabic);
                String formattedValue = formatValue(value, itemKey, isArabic);
                items.add(new ReportModels.GenericItem(formattedKey, formattedValue));
            }
            foundObject = true;
            items.add(new ReportModels.GenericItem("──────────", ""));
        }
    }
    
    if (foundObject) {
        return items;
    }
    
    // ✅ ثالثاً: عرض جميع المفاتيح المتبقية
    Iterator<String> keys = data.keys();
    while (keys.hasNext()) {
        String key = keys.next();
        Object value = data.opt(key);
        if (value != null && !key.equals("success") && !key.equals("timestamp") && !key.equals("type") && !key.equals("branch_id") && !key.equals("filters_applied")) {
            items.add(new ReportModels.GenericItem(
                formatKeyName(key, isArabic), 
                value.toString()
            ));
        }
    }
    
    return items;
}
    private String formatKeyName(String key, boolean isArabic) {
        if (isArabic) {
            switch (key.toLowerCase()) {
                case "id": return "المعرف";
                case "name": return "الاسم";
                case "title": return "العنوان";
                case "status": return "الحالة";
                case "date": return "التاريخ";
                case "created_at": return "تاريخ الإنشاء";
                case "updated_at": return "تاريخ التحديث";
                case "total": return "الإجمالي";
                case "amount": return "المبلغ";
                case "count": return "العدد";
                case "weight": return "الوزن";
                case "karat": return "العيار";
                case "price": return "السعر";
                case "quantity": return "الكمية";
                case "description": return "الوصف";
                case "category": return "الفئة";
                case "branch": return "الفرع";
                case "user": return "المستخدم";
                case "role": return "الدور";
                default:
                    return key.replace("_", " ").substring(0, 1).toUpperCase() + 
                           key.replace("_", " ").substring(1);
            }
        } else {
            return key.replace("_", " ").substring(0, 1).toUpperCase() + 
                   key.replace("_", " ").substring(1);
        }
    }
    
    private String formatValue(String value, String key, boolean isArabic) {
    if (value == null || value.isEmpty() || value.equals("null")) {
        return isArabic ? "غير محدد" : "N/A";
    }
    
    String lowerKey = key.toLowerCase();
    
    // ✅ تنسيق الأرقام المالية
    if (lowerKey.contains("amount") || lowerKey.contains("revenue") ||
        lowerKey.contains("total") || lowerKey.contains("price") ||
        lowerKey.contains("cost") || lowerKey.contains("avg") ||
        lowerKey.contains("value")) {
        try {
            double num = Double.parseDouble(value);
            return String.format(Locale.US, "%,.2f", num);
        } catch (Exception e) {}
    }
    
    // ✅ تنسيق النسب المئوية
    if (lowerKey.contains("accuracy") || lowerKey.contains("rate")) {
        try {
            double num = Double.parseDouble(value);
            return String.format(Locale.US, "%.2f%%", num);
        } catch (Exception e) {}
    }
    
    // ✅ تنسيق الأوزان
    if (lowerKey.contains("weight")) {
        try {
            double num = Double.parseDouble(value);
            return String.format(Locale.US, "%.3f g", num);
        } catch (Exception e) {}
    }
    
    // ✅ تنسيق التواريخ
    if (lowerKey.contains("date") || lowerKey.contains("time") ||
        lowerKey.contains("created") || lowerKey.contains("updated") ||
        lowerKey.contains("seen")) {
        return formatDateString(value);
    }
    
    // ✅ تنسيق الحالات
    if (lowerKey.equals("status") || lowerKey.equals("vault_status")) {
        return formatStatusValue(value, isArabic);
    }
    
    return value;
}
    
    private String formatDateString(String dateStr) {
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
            Date date = inputFormat.parse(dateStr);
            return outputFormat.format(date);
        } catch (Exception e) {
            return dateStr;
        }
    }
    
    private String formatStatusValue(String status, boolean isArabic) {
        if (isArabic) {
            switch (status.toLowerCase()) {
                case "active": return "✅ نشط";
                case "inactive": return "⛔ غير نشط";
                case "suspended": return "🚫 موقوف";
                case "approved": return "✅ معتمد";
                case "rejected": return "❌ مرفوض";
                case "pending": return "⏳ قيد الانتظار";
                case "draft": return "📝 مسودة";
                case "in_stock": return "✅ متاح";
                case "sold": return "💰 مباع";
                case "displayed": return "🏪 معروض";
                default: return status;
            }
        } else {
            return status;
        }
    }
    
    private RecyclerView.Adapter<?> createAdvancedAdapter(String type, JSONObject data) {
        return new GenericAdapter(parseToFormattedGeneric(data));
    }
    
    private String getReportTitle(String type) {
        if (isArabic) {
            switch (type) {
                case "user_timeline": return "⏱️ تايم لاين المستخدم";
                case "item_passport": return "💠 بصمة القطعة";
                case "gate_security_index": return "🛡️ مؤشر أمن البوابات";
                case "inventory_aging": return "📅 أعمار المخزون";
                case "audit_discrepancy_detail": return "⚠️ تفاصيل الفروقات";
                default: return "📊 تقرير";
            }
        } else {
            switch (type) {
                case "user_timeline": return "⏱️ User Timeline";
                case "item_passport": return "💠 Item Passport";
                case "gate_security_index": return "🛡️ Gate Security Index";
                case "inventory_aging": return "📅 Inventory Aging";
                case "audit_discrepancy_detail": return "⚠️ Discrepancy Detail";
                default: return "📊 Report";
            }
        }
    }
    
    private String getDefaultFromDate() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, -30);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }
    
    private String getDefaultToDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
    
    private void showDatePicker(DatePickerCallback cb) {
        if (!isAdded() || getContext() == null) return;
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (v, y, m, d) -> 
            cb.onDate(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)), 
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }
    
    // ==================== Adapter Classes ====================
    
    class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.VH> {
        final List<ReportModels.ReportItem> list;
        final OnReportClickListener listener;
        
        ReportsAdapter(List<ReportModels.ReportItem> l, OnReportClickListener c) {
            list = l;
            listener = c;
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_report_card_pro, p, false));
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReportModels.ReportItem item = list.get(pos);
            
            h.tvIcon.setText(item.icon);
            h.tvTitle.setText(item.title);
            h.tvSubtitle.setText(item.subtitle);
            
            if (item.color != 0 && h.flIconContainer != null) {
                h.flIconContainer.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                        h.itemView.getContext().getColor(item.color)));
            }
            
            if (h.chipAdvanced != null) {
                if (item.isAdvanced) {
                    h.chipAdvanced.setVisibility(View.VISIBLE);
                    h.chipAdvanced.setText(isArabic ? "✨ متقدم" : "✨ Advanced");
                } else {
                    h.chipAdvanced.setVisibility(View.GONE);
                }
            }
            
            h.itemView.setOnClickListener(v -> {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100)
                    .withEndAction(() -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                    }).start();
                v.postDelayed(() -> listener.onClick(item), 120);
            });
        }
        
        @Override
        public int getItemCount() {
            return list.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvTitle, tvSubtitle;
            FrameLayout flIconContainer;
            TextView chipAdvanced;
            
            VH(View v) {
                super(v);
                tvIcon = v.findViewById(R.id.tvReportIcon);
                tvTitle = v.findViewById(R.id.tvReportTitle);
                tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
                flIconContainer = v.findViewById(R.id.flIconContainer);
                chipAdvanced = v.findViewById(R.id.chipAdvanced);
            }
        }
    }
    
    class GenericAdapter extends RecyclerView.Adapter<GenericAdapter.VH> {
        final List<ReportModels.GenericItem> items;
        
        GenericAdapter(List<ReportModels.GenericItem> items) {
            this.items = items;
        }
        
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_report_generic, p, false));
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReportModels.GenericItem item = items.get(pos);
            h.tvKey.setText(item.key);
            h.tvValue.setText(item.value);
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvKey, tvValue;
            
            VH(View v) {
                super(v);
                tvKey = v.findViewById(R.id.tvKey);
                tvValue = v.findViewById(R.id.tvValue);
            }
        }
    }
    
    interface OnReportClickListener {
        void onClick(ReportModels.ReportItem item);
    }
    
    interface DatePickerCallback {
        void onDate(String date);
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
    }
            }
