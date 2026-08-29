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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.export.ReportExporter;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.models.ReportModels;
import com.example.uhf.repository.ReportRepository;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * ✅ النسخة المحسنة من Fragment التقارير
 */
public class MiraReportsProFragment extends Fragment {
    private static final String TAG = "MiraReportsPro";
    
    // المكونات الأساسية
    private RecyclerView rvReports;
    private ReportsAdapter adapter;
    private final List<ReportModels.ReportItem> reportList = new ArrayList<>();
    
    // المديرين
    private MiraOrgProfileManager orgProfile;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private MiraPriceManager priceManager;
    private ReportRepository reportRepository;
    
    // الحالة
    private boolean isArabic;
    private int currentBranchId = 1;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // البيانات المحملة
    private JSONObject lastLoadedData = null;
    private String lastLoadedType = "";
    private String lastFromDate = "";
    private String lastToDate = "";
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);
        
        // تهيئة المديرين
        initializeManagers();
        
        // تهيئة الواجهة
        initializeViews(view);
        
        // بناء قائمة التقارير
        buildReportsList();
        
        return view;
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
        
        adapter = new ReportsAdapter(reportList, this::openReportDialog);
        rvReports.setAdapter(adapter);
    }
    
    private void buildReportsList() {
        reportList.clear();
        
        if (isArabic) {
            // التقارير الأساسية
            reportList.add(new ReportModels.ReportItem("📊", "لوحة القيادة", "ملخص شامل للعمليات", "dashboard"));
            reportList.add(new ReportModels.ReportItem("💰", "المبيعات التفصيلي", "فواتير + عملاء + طرق دفع", "sales"));
            reportList.add(new ReportModels.ReportItem("📦", "المخزون التفصيلي", "كل قطعة مع تفاصيلها", "inventory"));
            reportList.add(new ReportModels.ReportItem("🔄", "التحويلات", "سجل حركة القطع", "movement"));
            reportList.add(new ReportModels.ReportItem("🏢", "أداء الفروع", "مقارنة شاملة", "branches"));
            reportList.add(new ReportModels.ReportItem("🚪", "البوابات الأمنية", "سجل المرور", "gates"));
            reportList.add(new ReportModels.ReportItem("📋", "الجرد والتدقيق", "الدقة والفروقات", "audit"));
            reportList.add(new ReportModels.ReportItem("💎", "النواقص والطلب", "الأكثر مبيعاً", "reorder"));
            reportList.add(new ReportModels.ReportItem("👥", "المستخدمين", "الموظفين", "users"));
            reportList.add(new ReportModels.ReportItem("💵", "الحركة المالية", "القيود المحاسبية", "financial"));
            
            // التقارير المتقدمة
            reportList.add(new ReportModels.ReportItem("⏱️", "تايم لاين المستخدم", "سجل نشاطات الموظف", "user_timeline"));
            reportList.add(new ReportModels.ReportItem("💠", "بصمة القطعة", "سجل حياة القطعة الكامل", "item_passport"));
            reportList.add(new ReportModels.ReportItem("🛡️", "مؤشر أمن البوابات", "تحليل المرور والإنذارات", "gate_security_index"));
            reportList.add(new ReportModels.ReportItem("📅", "أعمار المخزون", "تحليل الركود والتقادم", "inventory_aging"));
            reportList.add(new ReportModels.ReportItem("⚠️", "تفاصيل الفروقات", "فروقات الجرد التفصيلية", "audit_discrepancy_detail"));
        } else {
            // English reports
            reportList.add(new ReportModels.ReportItem("📊", "Dashboard", "Summary", "dashboard"));
            reportList.add(new ReportModels.ReportItem("💰", "Sales", "Invoices + customers", "sales"));
            reportList.add(new ReportModels.ReportItem("📦", "Inventory", "All items", "inventory"));
            reportList.add(new ReportModels.ReportItem("🔄", "Transfers", "Movement", "movement"));
            reportList.add(new ReportModels.ReportItem("🏢", "Branches", "Comparison", "branches"));
            reportList.add(new ReportModels.ReportItem("🚪", "Gates", "Log", "gates"));
            reportList.add(new ReportModels.ReportItem("📋", "Audit", "Accuracy", "audit"));
            reportList.add(new ReportModels.ReportItem("💎", "Reorder", "Best sellers", "reorder"));
            reportList.add(new ReportModels.ReportItem("👥", "Users", "Staff", "users"));
            reportList.add(new ReportModels.ReportItem("💵", "Financial", "Journal", "financial"));
            
            // Advanced
            reportList.add(new ReportModels.ReportItem("⏱️", "User Timeline", "Staff activity log", "user_timeline"));
            reportList.add(new ReportModels.ReportItem("💠", "Item Passport", "Complete item lifecycle", "item_passport"));
            reportList.add(new ReportModels.ReportItem("🛡️", "Gate Security Index", "Traffic & alerts analysis", "gate_security_index"));
            reportList.add(new ReportModels.ReportItem("📅", "Inventory Aging", "Stagnation analysis", "inventory_aging"));
            reportList.add(new ReportModels.ReportItem("⚠️", "Discrepancy Detail", "Detailed audit discrepancies", "audit_discrepancy_detail"));
        }
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }
    
    /**
     * ✅ فتح حوار التقرير
     */
    private void openReportDialog(ReportModels.ReportItem item) {
        if (getContext() == null || !isAdded()) return;
        
        // فحص الصلاحيات
        if (permissionManager != null && !permissionManager.canAccessFragment("Reports")) {
            Toast.makeText(getContext(), 
                isArabic ? "⛔ غير مصرح" : "⛔ Access Denied", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // التقارير المتقدمة
        if (item.isAdvanced) {
            handleAdvancedReport(item);
            return;
        }
        
        // التقارير العادية
        showFilteredReportDialog(item);
    }
    
    /**
     * ✅ معالجة التقارير المتقدمة
     */
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
     * ✅ عرض حوار تقرير مع فلاتر
     */
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
        
        // أزرار التاريخ
        btnFrom.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[0] = date;
            btnFrom.setText((isArabic ? "من: " : "From: ") + date);
        }));
        
        btnTo.setOnClickListener(v -> showDatePicker(date -> {
            selectedDates[1] = date;
            btnTo.setText((isArabic ? "إلى: " : "To: ") + date);
        }));
        
        // الفلتر المتقدم
        if (btnAdvancedFilter != null) {
            btnAdvancedFilter.setOnClickListener(v -> 
                showAdvancedFilterDialog(item.type, selectedFilters, () -> {
                    loadReportWithFilters(item.type, rvData, layoutLoading, 
                        tvLoadingStatus, selectedDates, selectedFilters);
                }));
        }
        
        // تطبيق الفلتر
        btnApply.setOnClickListener(v -> 
            loadReportWithFilters(item.type, rvData, layoutLoading, 
                tvLoadingStatus, selectedDates, selectedFilters));
        
        // تصدير CSV
        btnExport.setOnClickListener(v -> exportReport(item.type, selectedDates));
        
        // طباعة
        if (btnPrint != null) {
            btnPrint.setOnClickListener(v -> printReport(item, rvData, selectedDates, selectedFilters));
        }
        
        // إغلاق
        btnClose.setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
        
        // تحميل البيانات تلقائياً
        loadReportWithFilters(item.type, rvData, layoutLoading, 
            tvLoadingStatus, selectedDates, selectedFilters);
    }
    
    /**
     * ✅ تحميل التقرير مع الفلاتر
     */
    private void loadReportWithFilters(String type, RecyclerView rvData, 
                                       View layoutLoading, TextView tvLoadingStatus,
                                       String[] dates, String[] filters) {
        if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
        if (tvLoadingStatus != null) {
            tvLoadingStatus.setText(isArabic ? "⏳ جاري تحميل البيانات..." : "⏳ Loading data...");
        }
        
        // بناء الفلاتر
        ReportModels.ReportFilters reportFilters = new ReportModels.ReportFilters();
        reportFilters.fromDate = dates[0];
        reportFilters.toDate = dates[1];
        reportFilters.branchId = currentBranchId;
        
        if (filters != null) {
            reportFilters.searchQuery = filters[0];
            reportFilters.filter1 = filters[1];
            reportFilters.filter2 = filters[2];
        }
        
        // جلب البيانات
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
                
                // تخزين البيانات
                lastLoadedData = result.data;
                lastLoadedType = type;
                lastFromDate = dates[0];
                lastToDate = dates[1];
                
                // إنشاء المحول
                RecyclerView.Adapter<?> reportAdapter = createAdapterForType(type, result.data);
                rvData.setAdapter(reportAdapter);
                
                Toast.makeText(getContext(), 
                    isArabic ? "✅ تم تحميل التقرير" : "✅ Report loaded", 
                    Toast.LENGTH_SHORT).show();
            });
    }
    
    /**
     * ✅ جلب تقرير متقدم
     */
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
                
                showAdvancedReportDialog(type, result.data);
            });
    }
    
    /**
     * ✅ عرض حوار التقرير المتقدم
     */
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
        
        RecyclerView.Adapter<?> adapter = createAdvancedAdapter(type, data);
        rvData.setAdapter(adapter);
        
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), 
            android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
            .setView(dialogView)
            .create();
        
        if (btnClose != null) btnClose.setOnClickListener(v -> dialog.dismiss());
        
        if (btnPrint != null) {
            btnPrint.setVisibility(View.VISIBLE);
            btnPrint.setOnClickListener(v -> printAdvancedReport(type, data, rvData));
        }
        
        if (btnExport != null) {
            btnExport.setVisibility(View.VISIBLE);
            btnExport.setOnClickListener(v -> {
                File file = ReportExporter.exportToCSV(requireContext(), data, type, 
                    getDefaultFromDate(), getDefaultToDate(), isArabic);
                if (file != null) {
                    ReportExporter.shareFile(requireContext(), file, "text/csv");
                }
            });
        }
        
        dialog.show();
    }
    
    /**
     * ✅ إنشاء محول حسب نوع التقرير
     */
    private RecyclerView.Adapter<?> createAdapterForType(String type, JSONObject data) {
        switch (type) {
            case "sales":
                return new GenericAdapter(parseToGeneric(data));
            case "inventory":
                return new GenericAdapter(parseToGeneric(data));
            case "audit":
                return new GenericAdapter(parseToGeneric(data));
            case "reorder":
                return new GenericAdapter(parseToGeneric(data));
            case "users":
                return new GenericAdapter(parseToGeneric(data));
            case "gates":
                return new GenericAdapter(parseToGeneric(data));
            case "movement":
                return new GenericAdapter(parseToGeneric(data));
            case "branches":
                return new GenericAdapter(parseToGeneric(data));
            case "financial":
                return new GenericAdapter(parseToGeneric(data));
            default:
                return new GenericAdapter(parseToGeneric(data));
        }
    }
    
    /**
     * ✅ تحويل JSON إلى قائمة GenericItem
     */
    private List<ReportModels.GenericItem> parseToGeneric(JSONObject data) {
        List<ReportModels.GenericItem> items = new ArrayList<>();
        if (data == null) return items;
        
        // البحث عن المصفوفة
        JSONArray array = findArray(data);
        
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) {
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = obj.optString(key, "");
                        items.add(new ReportModels.GenericItem(key, value));
                    }
                    // فاصل بين العناصر
                    if (i < array.length() - 1) {
                        items.add(new ReportModels.GenericItem("---", "---"));
                    }
                }
            }
        } else {
            // تصدير key-value مباشرة
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = data.opt(key);
                if (value != null && !key.equals("success") && !key.equals("timestamp")) {
                    items.add(new ReportModels.GenericItem(key, value.toString()));
                }
            }
        }
        
        return items;
    }
    
    /**
     * ✅ البحث عن مصفوفة في البيانات
     */
    private JSONArray findArray(JSONObject data) {
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
    
    /**
     * ✅ إنشاء محول متقدم
     */
    private RecyclerView.Adapter<?> createAdvancedAdapter(String type, JSONObject data) {
        List<ReportModels.GenericItem> items = parseToGeneric(data);
        return new GenericAdapter(items);
    }
    
    // ==================== دوال مساعدة ====================
    
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
    
    // ==================== Adapter ====================
    
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
            
            h.itemView.setOnClickListener(v -> listener.onClick(item));
        }
        
        @Override
        public int getItemCount() {
            return list.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvTitle, tvSubtitle;
            
            VH(View v) {
                super(v);
                tvIcon = v.findViewById(R.id.tvReportIcon);
                tvTitle = v.findViewById(R.id.tvReportTitle);
                tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
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
