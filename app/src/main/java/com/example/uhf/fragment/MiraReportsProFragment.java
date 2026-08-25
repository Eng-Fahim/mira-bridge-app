package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.button.MaterialButton;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraReportsProFragment extends Fragment {

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
    private MiraPriceManager priceManager;
    private boolean isArabic;
    private int currentBranchId = 1;

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
            priceManager = MiraPriceManager.getInstance(getContext());
            offlineCache = getContext().getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        }

        isArabic = Locale.getDefault().getLanguage().equals("ar");
        currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(getContext(), 2));

        buildReportsList();
        adapter = new ReportsAdapter(reportList, this::openReportDialog);
        rvReports.setAdapter(adapter);

        return view;
    }

    private void buildReportsList() {
        reportList.clear();

        if (isArabic) {
            reportList.add(new ReportItem("📊", "لوحة القيادة", "ملخص شامل للعمليات", "dashboard"));
            reportList.add(new ReportItem("💰", "المبيعات التفصيلي", "فواتير + عملاء + طرق دفع", "sales"));
            reportList.add(new ReportItem("📦", "المخزون التفصيلي", "كل قطعة مع تفاصيلها", "inventory"));
            reportList.add(new ReportItem("🔄", "التحويلات", "سجل حركة القطع", "movement"));
            reportList.add(new ReportItem("🏢", "أداء الفروع", "مقارنة شاملة", "branches"));
            reportList.add(new ReportItem("🚪", "البوابات الأمنية", "سجل المرور", "gates"));
            reportList.add(new ReportItem("📋", "الجرد والتدقيق", "الدقة والفروقات", "audit"));
            reportList.add(new ReportItem("💎", "النواقص والطلب", "الأكثر مبيعاً", "reorder"));
            reportList.add(new ReportItem("👥", "المستخدمين", "الموظفين", "users"));
            reportList.add(new ReportItem("💵", "الحركة المالية", "القيود المحاسبية", "financial"));
            
            // ✅ التقارير المتقدمة الجديدة
            reportList.add(new ReportItem("⏱️", "تايم لاين المستخدم", "سجل نشاطات الموظف", "user_timeline"));
            reportList.add(new ReportItem("💠", "بصمة القطعة", "سجل حياة القطعة الكامل", "item_passport"));
            reportList.add(new ReportItem("🛡️", "مؤشر أمن البوابات", "تحليل المرور والإنذارات", "gate_security_index"));
            reportList.add(new ReportItem("📅", "أعمار المخزون", "تحليل الركود والتقادم", "inventory_aging"));
            reportList.add(new ReportItem("⚠️", "تفاصيل الفروقات", "فروقات الجرد التفصيلية", "audit_discrepancy_detail"));
        } else {
            reportList.add(new ReportItem("📊", "Dashboard", "Summary", "dashboard"));
            reportList.add(new ReportItem("💰", "Sales", "Invoices + customers", "sales"));
            reportList.add(new ReportItem("📦", "Inventory", "All items", "inventory"));
            reportList.add(new ReportItem("🔄", "Transfers", "Movement", "movement"));
            reportList.add(new ReportItem("🏢", "Branches", "Comparison", "branches"));
            reportList.add(new ReportItem("🚪", "Gates", "Log", "gates"));
            reportList.add(new ReportItem("📋", "Audit", "Accuracy", "audit"));
            reportList.add(new ReportItem("💎", "Reorder", "Best sellers", "reorder"));
            reportList.add(new ReportItem("👥", "Users", "Staff", "users"));
            reportList.add(new ReportItem("💵", "Financial", "Journal", "financial"));
            
            // ✅ Advanced Reports
            reportList.add(new ReportItem("⏱️", "User Timeline", "Staff activity log", "user_timeline"));
            reportList.add(new ReportItem("💠", "Item Passport", "Complete item lifecycle", "item_passport"));
            reportList.add(new ReportItem("🛡️", "Gate Security Index", "Traffic & alerts analysis", "gate_security_index"));
            reportList.add(new ReportItem("📅", "Inventory Aging", "Stagnation analysis", "inventory_aging"));
            reportList.add(new ReportItem("⚠️", "Discrepancy Detail", "Detailed audit discrepancies", "audit_discrepancy_detail"));
        }
    }

    private void openReportDialog(ReportItem item) {
        if (getContext() == null || !isAdded()) return;

        if (permissionManager != null && !permissionManager.canAccessFragment("Reports")) {
            Toast.makeText(getContext(), isArabic ? "⛔ غير مصرح" : "⛔ Access Denied", Toast.LENGTH_SHORT).show();
            return;
        }

        // ✅ معالجة خاصة للتقارير التي تحتاج مدخلات إضافية
        if (item.type.equals("user_timeline")) {
            showUserTimelineDialog(item);
            return;
        }
        
        if (item.type.equals("item_passport")) {
            showItemPassportDialog(item);
            return;
        }

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mira_report_viewer, null);

        TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
        RecyclerView rvData = dialogView.findViewById(R.id.rvReportData);
        View layoutLoading = dialogView.findViewById(R.id.layoutLoadingContainer);
        TextView tvLoadingStatus = dialogView.findViewById(R.id.tvLoadingStatus);

        MaterialButton btnFrom = dialogView.findViewById(R.id.btnReportDateFrom);
        MaterialButton btnTo = dialogView.findViewById(R.id.btnReportDateTo);
        MaterialButton btnApply = dialogView.findViewById(R.id.btnReportApplyFilter);
        MaterialButton btnExport = dialogView.findViewById(R.id.btnReportExport);
        MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);
        MaterialButton btnPrint = dialogView.findViewById(R.id.btnReportPrint);

        final String[] selectedDates = new String[]{getDefaultFromDate(), getDefaultToDate()};

        btnFrom.setText((isArabic ? "من: " : "From: ") + selectedDates[0]);
        btnTo.setText((isArabic ? "إلى: " : "To: ") + selectedDates[1]);

        MaterialButton btnQuickToday = dialogView.findViewById(R.id.btnQuickToday);
        MaterialButton btnQuickWeek = dialogView.findViewById(R.id.btnQuickWeek);
        MaterialButton btnQuickMonth = dialogView.findViewById(R.id.btnQuickMonth);

        tvTitle.setText(item.icon + " " + item.title);
        rvData.setLayoutManager(new LinearLayoutManager(getContext()));

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
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

        if (btnQuickToday != null) {
            btnQuickToday.setOnClickListener(v -> {
                selectedDates[0] = getDefaultToDate();
                selectedDates[1] = getDefaultToDate();
                btnFrom.setText((isArabic ? "من: " : "From: ") + selectedDates[0]);
                btnTo.setText((isArabic ? "إلى: " : "To: ") + selectedDates[1]);
                fetchAndDisplay(item.type, rvData, layoutLoading, tvLoadingStatus, selectedDates[0], selectedDates[1]);
            });
        }

        if (btnQuickWeek != null) {
            btnQuickWeek.setOnClickListener(v -> {
                selectedDates[0] = getCalculatedDate(-7);
                selectedDates[1] = getDefaultToDate();
                btnFrom.setText((isArabic ? "من: " : "From: ") + selectedDates[0]);
                btnTo.setText((isArabic ? "إلى: " : "To: ") + selectedDates[1]);
                fetchAndDisplay(item.type, rvData, layoutLoading, tvLoadingStatus, selectedDates[0], selectedDates[1]);
            });
        }

        if (btnQuickMonth != null) {
            btnQuickMonth.setOnClickListener(v -> {
                selectedDates[0] = getCalculatedDate(-30);
                selectedDates[1] = getDefaultToDate();
                btnFrom.setText((isArabic ? "من: " : "From: ") + selectedDates[0]);
                btnTo.setText((isArabic ? "إلى: " : "To: ") + selectedDates[1]);
                fetchAndDisplay(item.type, rvData, layoutLoading, tvLoadingStatus, selectedDates[0], selectedDates[1]);
            });
        }

        btnApply.setOnClickListener(v -> 
            fetchAndDisplay(item.type, rvData, layoutLoading, tvLoadingStatus, selectedDates[0], selectedDates[1])
        );

        btnExport.setOnClickListener(v -> exportReportToCSV(item.type, selectedDates[0], selectedDates[1]));
        btnClose.setOnClickListener(v -> dialog.dismiss());
        if (btnPrint != null) {
            btnPrint.setOnClickListener(v -> printRecyclerView(rvData, item.title));
        }

        dialog.show();

        fetchAndDisplay(item.type, rvData, layoutLoading, tvLoadingStatus, selectedDates[0], selectedDates[1]);
    }

    // ✅ دالة خاصة بتايم لاين المستخدم
    private void showUserTimelineDialog(ReportItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "⏱️ تايم لاين المستخدم" : "⏱️ User Timeline");
        
        View inputView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_input_user_id, null);
        EditText etUserId = inputView.findViewById(R.id.etUserId);
        
        builder.setView(inputView);
        builder.setPositiveButton(isArabic ? "عرض" : "Show", (dialog, which) -> {
            String userIdStr = etUserId.getText().toString().trim();
            if (!userIdStr.isEmpty()) {
                int userId = Integer.parseInt(userIdStr);
                fetchAdvancedReport(item.type, userId, "", "", 0, getDefaultFromDate(), getDefaultToDate());
            } else {
                Toast.makeText(getContext(), isArabic ? "أدخل معرف المستخدم" : "Enter user ID", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }

    // ✅ دالة خاصة ببصمة القطعة
    private void showItemPassportDialog(ReportItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "💠 بصمة القطعة" : "💠 Item Passport");
        
        View inputView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_input_serial, null);
        EditText etSerial = inputView.findViewById(R.id.etSerialNumber);
        
        builder.setView(inputView);
        builder.setPositiveButton(isArabic ? "بحث" : "Search", (dialog, which) -> {
            String serial = etSerial.getText().toString().trim();
            if (!serial.isEmpty()) {
                fetchAdvancedReport(item.type, 0, serial, "", 0, "", "");
            } else {
                Toast.makeText(getContext(), isArabic ? "أدخل الرقم التسلسلي" : "Enter serial number", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }

    // ✅ دالة جديدة لجلب التقارير المتقدمة
    private void fetchAdvancedReport(String type, int userId, String serialNumber, 
                                     String gtin13, int gateId, String fromDate, String toDate) {
        executorService.execute(() -> {
            try {
                // بناء الـ endpoint مع المعاملات الإضافية
                StringBuilder endpoint = new StringBuilder("/reports-analytics?type=").append(type);
                
                if (userId > 0) endpoint.append("&user_id=").append(userId);
                if (!serialNumber.isEmpty()) endpoint.append("&serial_number=").append(serialNumber);
                if (!gtin13.isEmpty()) endpoint.append("&gtin13=").append(gtin13);
                if (gateId > 0) endpoint.append("&gate_id=").append(gateId);
                if (!fromDate.isEmpty()) endpoint.append("&from=").append(fromDate);
                if (!toDate.isEmpty()) endpoint.append("&to=").append(toDate);
                if (currentBranchId > 0) endpoint.append("&branch_id=").append(currentBranchId);

                Log.d(TAG, "Advanced Report URL: " + endpoint.toString());
                
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint.toString());

                mainHandler.post(() -> {
                    if (response == null || !response.isSuccess) {
                        Toast.makeText(getContext(), 
                            isArabic ? "فشل التحميل" : "Failed to load", 
                            Toast.LENGTH_SHORT).show();
                        return;
                    }

                    JSONObject data = response.data;
                    if (data == null) {
                        Toast.makeText(getContext(), isArabic ? "لا توجد بيانات" : "No data available", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Log.d(TAG, "Advanced Report Data: " + data.toString());

                    // عرض البيانات في Dialog
                    showAdvancedReportDialog(type, data);
                });
            } catch (Exception e) {
                Log.e(TAG, "Advanced report error: " + e.getMessage());
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ✅ دالة لعرض التقارير المتقدمة في Dialog
    private void showAdvancedReportDialog(String type, JSONObject data) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mira_report_viewer, null);
        
        TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
        RecyclerView rvData = dialogView.findViewById(R.id.rvReportData);
        View layoutLoading = dialogView.findViewById(R.id.layoutLoadingContainer);
        TextView tvLoadingStatus = dialogView.findViewById(R.id.tvLoadingStatus);
        
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        
        tvTitle.setText(getReportTitle(type));
        rvData.setLayoutManager(new LinearLayoutManager(getContext()));
        
        // استخدام المحول المناسب
        RecyclerView.Adapter<?> adapter = createAdvancedAdapter(type, data);
        rvData.setAdapter(adapter);
        
        AlertDialog dialog = new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
                .setView(dialogView)
                .create();
        
        MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
        
        dialog.show();
    }

    private String getReportTitle(String type) {
        if (isArabic) {
            switch (type) {
                case "user_timeline": return "⏱️ تايم لاين المستخدم";
                case "item_passport": return "💠 بصمة القطعة";
                case "gate_security_index": return "🛡️ مؤشر أمن البوابات";
                case "inventory_aging": return "📅 أعمار المخزون";
                case "audit_discrepancy_detail": return "⚠️ تفاصيل الفروقات";
                default: return "📊 تقرير متقدم";
            }
        } else {
            switch (type) {
                case "user_timeline": return "⏱️ User Timeline";
                case "item_passport": return "💠 Item Passport";
                case "gate_security_index": return "🛡️ Gate Security Index";
                case "inventory_aging": return "📅 Inventory Aging";
                case "audit_discrepancy_detail": return "⚠️ Discrepancy Detail";
                default: return "📊 Advanced Report";
            }
        }
    }

    private RecyclerView.Adapter<?> createAdvancedAdapter(String type, JSONObject data) {
        JSONObject dataObj = data.optJSONObject("data");
        if (dataObj == null) dataObj = data;
        
        switch (type) {
            case "user_timeline":
                return new GenericAdapter(parseUserTimeline(dataObj));
            case "item_passport":
                return new GenericAdapter(parseItemPassport(dataObj));
            case "gate_security_index":
                return new GenericAdapter(parseGateSecurity(dataObj));
            case "inventory_aging":
                return new GenericAdapter(parseInventoryAging(dataObj));
            case "audit_discrepancy_detail":
                return new GenericAdapter(parseAuditDiscrepancy(dataObj));
            default:
                return new GenericAdapter(parseGeneric(dataObj));
        }
    }

    // ==================== Advanced Parsing Functions ====================

    private List<GenericItem> parseUserTimeline(JSONObject data) {
        List<GenericItem> items = new ArrayList<>();
        if (data == null) return items;
        
        JSONObject userInfo = data.optJSONObject("user_info");
        if (userInfo != null) {
            items.add(new GenericItem(
                isArabic ? "👤 المستخدم" : "👤 User",
                userInfo.optString("display_name", userInfo.optString("username", "-"))
            ));
        }
        
        items.add(new GenericItem(
            isArabic ? "📊 إجمالي الأحداث" : "📊 Total Events",
            String.valueOf(data.optInt("total_events", 0))
        ));
        
        JSONArray timeline = data.optJSONArray("timeline");
        if (timeline != null) {
            for (int i = 0; i < timeline.length(); i++) {
                JSONObject event = timeline.optJSONObject(i);
                if (event != null) {
                    String eventType = event.optString("event_type", "-");
                    String details = event.optString("details", "-");
                    String date = event.optString("created_at", "-");
                    
                    // ترجمة نوع الحدث
                    String translatedType = eventType;
                    if (isArabic) {
                        switch (eventType) {
                            case "SALE": translatedType = "💰 عملية بيع"; break;
                            case "INVENTORY_AUDIT": translatedType = "📋 جرد مخزون"; break;
                            case "TRANSFER": translatedType = "🔄 تحويل"; break;
                        }
                    }
                    
                    items.add(new GenericItem(translatedType, details + "\n" + date));
                }
            }
        }
        return items;
    }

    private List<GenericItem> parseItemPassport(JSONObject data) {
        List<GenericItem> items = new ArrayList<>();
        if (data == null) return items;
        
        JSONObject itemDetails = data.optJSONObject("item_details");
        if (itemDetails != null) {
            items.add(new GenericItem(
                isArabic ? "📦 عنوان القطعة" : "📦 Item Title",
                itemDetails.optString("item_title", "-")
            ));
            items.add(new GenericItem(
                isArabic ? "🔢 الرقم التسلسلي" : "🔢 Serial Number",
                itemDetails.optString("serial_number", "-")
            ));
            items.add(new GenericItem(
                isArabic ? "🏷️ العيار" : "🏷️ Karat",
                itemDetails.optString("gold_karat", "-")
            ));
            items.add(new GenericItem(
                isArabic ? "⚖️ الوزن" : "⚖️ Weight",
                itemDetails.optString("gross_weight_grams", "-") + " g"
            ));
            items.add(new GenericItem(
                isArabic ? "📍 الحالة" : "📍 Status",
                itemDetails.optString("vault_status", "-")
            ));
            items.add(new GenericItem(
                isArabic ? "🏢 الموقع" : "🏢 Location",
                itemDetails.optString("current_location", "-")
            ));
        }
        
        JSONArray gateHistory = data.optJSONArray("gate_history");
        if (gateHistory != null && gateHistory.length() > 0) {
            items.add(new GenericItem(
                isArabic ? "🚪 سجل البوابات" : "🚪 Gate History",
                String.valueOf(gateHistory.length())
            ));
            for (int i = 0; i < gateHistory.length(); i++) {
                JSONObject gate = gateHistory.optJSONObject(i);
                if (gate != null) {
                    items.add(new GenericItem(
                        "بوابة: " + gate.optString("gate_name", "-"),
                        "التاريخ: " + gate.optString("created_at", "-")
                    ));
                }
            }
        }
        
        JSONArray movements = data.optJSONArray("movements");
        if (movements != null && movements.length() > 0) {
            items.add(new GenericItem(
                isArabic ? "🔄 سجل التحويلات" : "🔄 Transfer History",
                String.valueOf(movements.length())
            ));
            for (int i = 0; i < movements.length(); i++) {
                JSONObject movement = movements.optJSONObject(i);
                if (movement != null) {
                    items.add(new GenericItem(
                        "من: " + movement.optString("from_b", "-") + " → إلى: " + movement.optString("to_b", "-"),
                        "التاريخ: " + movement.optString("created_at", "-")
                    ));
                }
            }
        }
        
        return items;
    }

    private List<GenericItem> parseGateSecurity(JSONObject data) {
        List<GenericItem> items = new ArrayList<>();
        if (data == null) return items;
        
        // البحث عن مصفوفة بيانات البوابات
        JSONArray gatesArr = null;
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = data.opt(key);
            if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                if (arr.length() > 0) {
                    JSONObject first = arr.optJSONObject(0);
                    if (first != null && first.has("total_events")) {
                        gatesArr = arr;
                        break;
                    }
                }
            }
        }
        
        if (gatesArr != null) {
            for (int i = 0; i < gatesArr.length(); i++) {
                JSONObject gate = gatesArr.optJSONObject(i);
                if (gate != null) {
                    String gateId = gate.optString("gate_id", "-");
                    int totalEvents = gate.optInt("total_events", 0);
                    int alerts = gate.optInt("alerts", 0);
                    int passed = gate.optInt("passed", 0);
                    
                    items.add(new GenericItem(
                        (isArabic ? "🚪 بوابة " : "🚪 Gate ") + gateId,
                        String.format(Locale.US, 
                            "%s: %d\n%s: %d\n%s: %d",
                            isArabic ? "إجمالي الأحداث" : "Total Events", totalEvents,
                            isArabic ? "إنذارات" : "Alerts", alerts,
                            isArabic ? "مسموح" : "Passed", passed)
                    ));
                }
            }
        }
        
        return items;
    }

    private List<GenericItem> parseInventoryAging(JSONObject data) {
        List<GenericItem> items = new ArrayList<>();
        if (data == null) return items;
        
        // البحث عن مصفوفة بيانات الأعمار
        JSONArray agingArr = null;
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = data.opt(key);
            if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                if (arr.length() > 0) {
                    JSONObject first = arr.optJSONObject(0);
                    if (first != null && first.has("age_bracket")) {
                        agingArr = arr;
                        break;
                    }
                }
            }
        }
        
        if (agingArr != null) {
            for (int i = 0; i < agingArr.length(); i++) {
                JSONObject aging = agingArr.optJSONObject(i);
                if (aging != null) {
                    String bracket = aging.optString("age_bracket", "-");
                    int itemsCount = aging.optInt("items_count", 0);
                    double totalWeight = aging.optDouble("total_weight", 0);
                    
                    // ترجمة الفئات العمرية
                    String translatedBracket = bracket;
                    if (isArabic) {
                        switch (bracket) {
                            case "0-30 Days": translatedBracket = "٠-٣٠ يوم"; break;
                            case "31-60 Days": translatedBracket = "٣١-٦٠ يوم"; break;
                            case "61-90 Days": translatedBracket = "٦١-٩٠ يوم"; break;
                            case "+90 Days (Stagnant)": translatedBracket = "+٩٠ يوم (راكد)"; break;
                        }
                    }
                    
                    items.add(new GenericItem(
                        translatedBracket,
                        String.format(Locale.US, 
                            "%s: %d\n%s: %.2f g",
                            isArabic ? "القطع" : "Items", itemsCount,
                            isArabic ? "الوزن" : "Weight", totalWeight)
                    ));
                }
            }
        }
        
        return items;
    }

    private List<GenericItem> parseAuditDiscrepancy(JSONObject data) {
        List<GenericItem> items = new ArrayList<>();
        if (data == null) return items;
        
        // البحث عن مصفوفة بيانات الفروقات
        JSONArray discArr = null;
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = data.opt(key);
            if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                if (arr.length() > 0) {
                    JSONObject first = arr.optJSONObject(0);
                    if (first != null && first.has("discrepancy")) {
                        discArr = arr;
                        break;
                    }
                }
            }
        }
        
        if (discArr != null) {
            for (int i = 0; i < discArr.length(); i++) {
                JSONObject disc = discArr.optJSONObject(i);
                if (disc != null) {
                    String sessionId = disc.optString("session_id", "-");
                    int branchId = disc.optInt("branch_id", 0);
                    int expectedItems = disc.optInt("expected_items", 0);
                    int foundItems = disc.optInt("found_items", 0);
                    int discrepancy = disc.optInt("discrepancy", 0);
                    
                    items.add(new GenericItem(
                        (isArabic ? "📋 جلسة " : "📋 Session ") + sessionId,
                        String.format(Locale.US,
                            "%s: %d\n%s: %d\n%s: %d",
                            isArabic ? "المتوقع" : "Expected", expectedItems,
                            isArabic ? "الموجود" : "Found", foundItems,
                            isArabic ? "الفرق" : "Difference", discrepancy)
                    ));
                }
            }
        }
        
        return items;
    }

    private void fetchAndDisplay(String type, RecyclerView rvData, View layoutLoading, 
                              TextView tvLoadingStatus, String fromDate, String toDate) {
        if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
        if (tvLoadingStatus != null) tvLoadingStatus.setText(isArabic ? "جاري التحميل..." : "Loading...");

        executorService.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getReportData(type, fromDate, toDate, currentBranchId);

                mainHandler.post(() -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);

                    if (response == null || !response.isSuccess) {
                        Toast.makeText(getContext(), 
                            isArabic ? "فشل التحميل" : "Failed to load", 
                            Toast.LENGTH_SHORT).show();
                        return;
                    }

                    JSONObject data = response.data;
                    String rawBody = response.getBody();

                    if (data == null && rawBody != null) {
                        try {
                            if (rawBody.trim().startsWith("[")) {
                                JSONArray rootArray = new JSONArray(rawBody);
                                data = new JSONObject();
                                data.put("items_list", rootArray);
                            } else {
                                data = new JSONObject(rawBody);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Parse JSON error", e);
                        }
                    }

                    if (data == null) {
                        Toast.makeText(getContext(), isArabic ? "لا توجد بيانات" : "No data available", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Log.d(TAG, "=== Report: " + type + " ===");
                    Log.d(TAG, "Data keys: " + data.keys().toString());

                    String cacheKey = "cache_pro_" + type + "_" + fromDate + "_" + toDate;
                    if (offlineCache != null) {
                        offlineCache.edit().putString(cacheKey, data.toString()).apply();
                    }

                    RecyclerView.Adapter<?> reportAdapter = createAdapterForType(type, data);
                    rvData.setAdapter(reportAdapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "Fetch error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private RecyclerView.Adapter<?> createAdapterForType(String type, JSONObject data) {
        switch (type) {
            case "sales": {
                List<SalesItem> sales = parseSales(data);
                return sales.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new SalesAdapter(sales);
            }
            case "inventory": {
                List<InventoryItem> inv = parseInventory(data);
                return inv.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new InventoryAdapter(inv);
            }
            case "audit": {
                List<AuditItem> audit = parseAudit(data);
                return audit.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new AuditAdapter(audit);
            }
            case "reorder": {
                List<ReorderItem> reorder = parseReorder(data);
                return reorder.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new ReorderAdapter(reorder);
            }
            case "users": {
                List<UsersItem> users = parseUsers(data);
                return users.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new UsersAdapter(users);
            }
            case "gates": {
                List<GatesItem> gates = parseGates(data);
                return gates.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new GatesAdapter(gates);
            }
            case "movement": {
                List<MovementItem> movement = parseMovement(data);
                return movement.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new MovementAdapter(movement);
            }
            case "branches": {
                List<BranchesItem> branches = parseBranches(data);
                return branches.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new BranchesAdapter(branches);
            }
            case "financial": {
                List<FinancialItem> financial = parseFinancial(data);
                return financial.isEmpty() ? new GenericAdapter(parseGeneric(data)) : new FinancialAdapter(financial);
            }
            default:
                return new DashboardAdapter(parseDashboard(data));
        }
    }

    // ==================== Parsing Functions ====================

    private JSONArray findArrayInData(JSONObject data, String[] possibleKeys, String[] identifyingKeys) {
        if (data == null) return null;
        
        for (String key : possibleKeys) {
            JSONArray arr = data.optJSONArray(key);
            if (arr != null && arr.length() > 0) return arr;
        }
        
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = data.opt(key);
            
            if (value instanceof JSONObject) {
                JSONArray nestedArr = findArrayInData((JSONObject) value, possibleKeys, identifyingKeys);
                if (nestedArr != null) return nestedArr;
            } else if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                if (arr.length() > 0 && identifyingKeys != null) {
                    JSONObject firstItem = arr.optJSONObject(0);
                    if (firstItem != null) {
                        for (String idKey : identifyingKeys) {
                            if (firstItem.has(idKey)) return arr;
                        }
                    }
                }
            }
        }
        
        return null;
    }

    private List<SalesItem> parseSales(JSONObject data) {
        List<SalesItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray invoices = findArrayInData(data, 
            new String[]{"invoices", "sales", "items_list", "data"}, 
            new String[]{"sale_number", "invoice_no", "final_amount", "customer_name"});

        if (invoices != null) {
            for (int i = 0; i < invoices.length(); i++) {
                JSONObject inv = invoices.optJSONObject(i);
                if (inv != null) {
                    items.add(new SalesItem(
                        inv.optString("sale_number", inv.optString("invoice_no", inv.optString("id", "-"))),
                        inv.optString("customer_name", inv.optString("customer", "-")),
                        inv.optDouble("final_amount", inv.optDouble("total", inv.optDouble("subtotal", 0))),
                        inv.optString("payment_method", inv.optString("payment", "-")),
                        inv.optString("seller_name", inv.optString("seller", "-"))
                    ));
                }
            }
        }
        return items;
    }

    private List<InventoryItem> parseInventory(JSONObject data) {
        List<InventoryItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray arr = findArrayInData(data, 
            new String[]{"items", "inventory", "items_list", "data"}, 
            new String[]{"item_title", "serial_number", "gross_weight_grams", "gold_karat"});

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new InventoryItem(
                        obj.optString("item_title", obj.optString("title", obj.optString("name", "-"))),
                        obj.optString("gold_karat", obj.optString("karat", "-")),
                        obj.optDouble("gross_weight_grams", obj.optDouble("weight", 0)),
                        obj.optString("vault_status", obj.optString("status", "-")),
                        obj.optString("current_location", obj.optString("location", "-"))
                    ));
                }
            }
        }
        return items;
    }

    private List<AuditItem> parseAudit(JSONObject data) {
        List<AuditItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONObject audit = data.optJSONObject("audit_summary");
        if (audit == null) audit = data.optJSONObject("audit");
        if (audit == null) audit = data;

        if (audit.has("accuracy") || audit.has("found") || audit.has("found_items")) {
            items.add(new AuditItem(
                audit.optDouble("accuracy", 0),
                audit.optInt("found_items", audit.optInt("found", 0)),
                audit.optInt("missing_items", audit.optInt("missing", 0)),
                audit.optInt("total_sessions", audit.optInt("sessions", 0))
            ));
        }
        return items;
    }

    private List<ReorderItem> parseReorder(JSONObject data) {
        List<ReorderItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray arr = findArrayInData(data, 
            new String[]{"reorder_items", "karat_breakdown", "items", "items_list", "data"}, 
            new String[]{"gold_karat", "sold_count", "remaining_stock"});

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new ReorderItem(
                        obj.optString("gold_karat", obj.optString("karat", "-")),
                        obj.optInt("sold_count", obj.optInt("count", obj.optInt("sold", 0))),
                        obj.optInt("remaining_stock", obj.optInt("remaining", 0))
                    ));
                }
            }
        }
        return items;
    }

    private List<UsersItem> parseUsers(JSONObject data) {
        List<UsersItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray arr = findArrayInData(data, 
            new String[]{"users", "data", "items_list"}, 
            new String[]{"display_name", "username", "role_name"});

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new UsersItem(
                        obj.optString("display_name", obj.optString("name", obj.optString("username", "-"))),
                        obj.optString("role_name", obj.optString("role", "-")),
                        obj.optString("status", obj.optString("is_active", "Active"))
                    ));
                }
            }
        }
        return items;
    }

    private List<GatesItem> parseGates(JSONObject data) {
        List<GatesItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray arr = findArrayInData(data, 
            new String[]{"gate_logs", "logs", "data", "items_list"}, 
            new String[]{"gate_name", "epc", "tag_epc"});

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new GatesItem(
                        obj.optString("gate_name", obj.optString("gate", obj.optString("device_id", "-"))),
                        obj.optString("epc", obj.optString("code", obj.optString("tag_epc", "-"))),
                        obj.optString("created_at", obj.optString("date", obj.optString("timestamp", "-")))
                    ));
                }
            }
        }
        return items;
    }

    private List<MovementItem> parseMovement(JSONObject data) {
        List<MovementItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray arr = findArrayInData(data, 
            new String[]{"movements", "transfers", "data", "items_list"}, 
            new String[]{"transfer_number", "from_branch", "to_branch"});

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new MovementItem(
                        obj.optString("from_branch", obj.optString("from", obj.optString("source", "-"))),
                        obj.optString("to_branch", obj.optString("to", obj.optString("destination", "-"))),
                        obj.optString("created_at", obj.optString("date", obj.optString("requested_at", "-")))
                    ));
                }
            }
        }
        return items;
    }

    private List<BranchesItem> parseBranches(JSONObject data) {
        List<BranchesItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray arr = findArrayInData(data, 
            new String[]{"branches_performance", "branches", "data", "items_list"}, 
            new String[]{"branch_name", "items_count"});

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new BranchesItem(
                        obj.optString("branch_name", obj.optString("name", "-")),
                        obj.optInt("items_count", obj.optInt("count", 0))
                    ));
                }
            }
        }
        return items;
    }

    private List<FinancialItem> parseFinancial(JSONObject data) {
        List<FinancialItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONArray arr = findArrayInData(data, 
            new String[]{"journal_entries", "entries", "financial", "data", "items_list"}, 
            new String[]{"description", "amount", "credit"});

        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new FinancialItem(
                        obj.optString("description", obj.optString("details", obj.optString("note", "-"))),
                        obj.optDouble("amount", obj.optDouble("total", obj.optDouble("credit", 0)))
                    ));
                }
            }
        }
        return items;
    }

    private List<GenericItem> parseGeneric(JSONObject data) {
        List<GenericItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONObject innerData = data.optJSONObject("data");
        if (innerData != null) data = innerData;

        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = data.opt(key);
            if (value != null && !key.equals("success") && !key.equals("timestamp") && !key.equals("type")) {
                items.add(new GenericItem(key, value.toString()));
            }
        }
        return items;
    }

    private List<DashboardItem> parseDashboard(JSONObject data) {
        List<DashboardItem> items = new ArrayList<>();
        if (data == null) return items;

        JSONObject inv = data.optJSONObject("inventory");
        if (inv == null) inv = data.optJSONObject("summary");
        
        if (inv != null) {
            items.add(new DashboardItem(
                isArabic ? "📦 إجمالي القطع" : "📦 Total Items",
                String.valueOf(inv.optInt("total_items", 0))
            ));
            items.add(new DashboardItem(
                isArabic ? "⚖️ الوزن الكلي" : "⚖️ Total Weight",
                String.format(Locale.US, "%.1f g", inv.optDouble("total_weight", 0))
            ));
            items.add(new DashboardItem(
                isArabic ? "✅ المتاح" : "✅ Available",
                String.valueOf(inv.optInt("available_count", 0))
            ));
            items.add(new DashboardItem(
                isArabic ? "💰 المباع" : "💰 Sold",
                String.valueOf(inv.optInt("sold_count", 0))
            ));
        }

        JSONObject sales = data.optJSONObject("sales");
        if (sales != null) {
            items.add(new DashboardItem(
                isArabic ? "🧾 عدد الفواتير" : "🧾 Invoices",
                String.valueOf(sales.optInt("invoices_count", 0))
            ));
            items.add(new DashboardItem(
                isArabic ? "💵 الإيرادات" : "💵 Revenue",
                String.format(Locale.US, "%,.2f", sales.optDouble("total_revenue", 0))
            ));
        }

        return items;
    }

    // ==================== Data Classes ====================

    static class SalesItem { String invoiceNo, customer, payment, seller; double amount;
        SalesItem(String i, String c, double a, String p, String s) { invoiceNo=i; customer=c; amount=a; payment=p; seller=s; } }
    static class InventoryItem { String title, karat, status, location; double weight;
        InventoryItem(String t, String k, double w, String s, String l) { title=t; karat=k; weight=w; status=s; location=l; } }
    static class AuditItem { double accuracy; int found, missing, sessions;
        AuditItem(double a, int f, int m, int s) { accuracy=a; found=f; missing=m; sessions=s; } }
    static class ReorderItem { String karat; int sold, remaining;
        ReorderItem(String k, int s, int r) { karat=k; sold=s; remaining=r; } }
    static class UsersItem { String name, role, status;
        UsersItem(String n, String r, String s) { name=n; role=r; status=s; } }
    static class GatesItem { String gateName, epc, date;
        GatesItem(String g, String e, String d) { gateName=g; epc=e; date=d; } }
    static class MovementItem { String from, to, date;
        MovementItem(String f, String t, String d) { from=f; to=t; date=d; } }
    static class BranchesItem { String name; int items;
        BranchesItem(String n, int i) { name=n; items=i; } }
    static class FinancialItem { String description; double amount;
        FinancialItem(String d, double a) { description=d; amount=a; } }
    static class GenericItem { String key, value;
        GenericItem(String k, String v) { key=k; value=v; } }
    static class DashboardItem { String label, value;
        DashboardItem(String l, String v) { label = l; value = v; } }

    // ==================== Adapters ====================

    class SalesAdapter extends RecyclerView.Adapter<SalesAdapter.VH> {
        private final List<SalesItem> items;
        SalesAdapter(List<SalesItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_sales, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            SalesItem i = items.get(pos);
            h.tvInvoice.setText(i.invoiceNo);
            h.tvCustomer.setText(i.customer);
            h.tvAmount.setText(priceManager != null ? priceManager.formatPriceDefault(i.amount) : String.valueOf(i.amount));
            h.tvPayment.setText(i.payment);
            h.tvSeller.setText(i.seller);
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvInvoice, tvCustomer, tvAmount, tvPayment, tvSeller;
            VH(View v) { super(v);
                tvInvoice = v.findViewById(R.id.tvInvoiceNo);
                tvCustomer = v.findViewById(R.id.tvCustomerName);
                tvAmount = v.findViewById(R.id.tvAmount);
                tvPayment = v.findViewById(R.id.tvPaymentMethod);
                tvSeller = v.findViewById(R.id.tvSellerName);
            }
        }
    }

    class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<InventoryItem> items;
        InventoryAdapter(List<InventoryItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_inventory, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            InventoryItem i = items.get(pos);
            h.tvTitle.setText(i.title);
            h.tvKarat.setText(i.karat);
            h.tvWeight.setText(String.format(Locale.US, "%.2f g", i.weight));
            h.tvStatus.setText(i.status);
            h.tvLocation.setText(i.location);
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvKarat, tvWeight, tvStatus, tvLocation;
            VH(View v) { super(v);
                tvTitle = v.findViewById(R.id.tvItemTitle);
                tvKarat = v.findViewById(R.id.tvKarat);
                tvWeight = v.findViewById(R.id.tvWeight);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvLocation = v.findViewById(R.id.tvLocation);
            }
        }
    }

    class AuditAdapter extends RecyclerView.Adapter<AuditAdapter.VH> {
        private final List<AuditItem> items;
        AuditAdapter(List<AuditItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_audit, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            AuditItem i = items.get(pos);
            h.tvAccuracy.setText(String.format(Locale.US, "%.1f%%", i.accuracy));
            h.tvFound.setText(String.valueOf(i.found));
            h.tvMissing.setText(String.valueOf(i.missing));
            h.tvSessions.setText(String.valueOf(i.sessions));
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvAccuracy, tvFound, tvMissing, tvSessions;
            VH(View v) { super(v);
                tvAccuracy = v.findViewById(R.id.tvAccuracy);
                tvFound = v.findViewById(R.id.tvFound);
                tvMissing = v.findViewById(R.id.tvMissing);
                tvSessions = v.findViewById(R.id.tvSessions);
            }
        }
    }

    class ReorderAdapter extends RecyclerView.Adapter<ReorderAdapter.VH> {
        private final List<ReorderItem> items;
        ReorderAdapter(List<ReorderItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_reorder, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReorderItem i = items.get(pos);
            h.tvKarat.setText(i.karat);
            h.tvSold.setText(String.valueOf(i.sold));
            h.tvRemaining.setText(String.valueOf(i.remaining));
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvKarat, tvSold, tvRemaining;
            VH(View v) { super(v);
                tvKarat = v.findViewById(R.id.tvKarat);
                tvSold = v.findViewById(R.id.tvSold);
                tvRemaining = v.findViewById(R.id.tvRemaining);
            }
        }
    }

    class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        private final List<UsersItem> items;
        UsersAdapter(List<UsersItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_users, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            UsersItem i = items.get(pos);
            h.tvName.setText(i.name);
            h.tvRole.setText(i.role);
            h.tvStatus.setText(i.status);
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvRole, tvStatus;
            VH(View v) { super(v);
                tvName = v.findViewById(R.id.tvName);
                tvRole = v.findViewById(R.id.tvRole);
                tvStatus = v.findViewById(R.id.tvStatus);
            }
        }
    }

    class GatesAdapter extends RecyclerView.Adapter<GatesAdapter.VH> {
        private final List<GatesItem> items;
        GatesAdapter(List<GatesItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_gates, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            GatesItem i = items.get(pos);
            h.tvGate.setText(i.gateName);
            h.tvEpc.setText(i.epc);
            h.tvDate.setText(i.date);
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvGate, tvEpc, tvDate;
            VH(View v) { super(v);
                tvGate = v.findViewById(R.id.tvGateName);
                tvEpc = v.findViewById(R.id.tvEpc);
                tvDate = v.findViewById(R.id.tvDate);
            }
        }
    }

    class MovementAdapter extends RecyclerView.Adapter<MovementAdapter.VH> {
        private final List<MovementItem> items;
        MovementAdapter(List<MovementItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_movement, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            MovementItem i = items.get(pos);
            h.tvFrom.setText(i.from);
            h.tvTo.setText(i.to);
            h.tvDate.setText(i.date);
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvFrom, tvTo, tvDate;
            VH(View v) { super(v);
                tvFrom = v.findViewById(R.id.tvFrom);
                tvTo = v.findViewById(R.id.tvTo);
                tvDate = v.findViewById(R.id.tvDate);
            }
        }
    }

    class BranchesAdapter extends RecyclerView.Adapter<BranchesAdapter.VH> {
        private final List<BranchesItem> items;
        BranchesAdapter(List<BranchesItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_branches, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            BranchesItem i = items.get(pos);
            h.tvName.setText(i.name);
            h.tvItems.setText(String.valueOf(i.items));
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvItems;
            VH(View v) { super(v);
                tvName = v.findViewById(R.id.tvBranchName);
                tvItems = v.findViewById(R.id.tvItemsCount);
            }
        }
    }

    class FinancialAdapter extends RecyclerView.Adapter<FinancialAdapter.VH> {
        private final List<FinancialItem> items;
        FinancialAdapter(List<FinancialItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_financial, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            FinancialItem i = items.get(pos);
            h.tvDescription.setText(i.description);
            h.tvAmount.setText(String.format(Locale.US, "%,.2f", i.amount));
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvDescription, tvAmount;
            VH(View v) { super(v);
                tvDescription = v.findViewById(R.id.tvDescription);
                tvAmount = v.findViewById(R.id.tvAmount);
            }
        }
    }

    class GenericAdapter extends RecyclerView.Adapter<GenericAdapter.VH> {
        private final List<GenericItem> items;
        GenericAdapter(List<GenericItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_generic, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            GenericItem i = items.get(pos);
            h.tvKey.setText(i.key);
            h.tvValue.setText(i.value);
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvKey, tvValue;
            VH(View v) { super(v);
                tvKey = v.findViewById(R.id.tvKey);
                tvValue = v.findViewById(R.id.tvValue);
            }
        }
    }

    class DashboardAdapter extends RecyclerView.Adapter<DashboardAdapter.VH> {
        private final List<DashboardItem> items;
        DashboardAdapter(List<DashboardItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_generic, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            DashboardItem item = items.get(pos);
            h.tvKey.setText(item.label);
            h.tvValue.setText(item.value);
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvKey, tvValue;
            VH(View v) { super(v);
                tvKey = v.findViewById(R.id.tvKey);
                tvValue = v.findViewById(R.id.tvValue);
            }
        }
    }

    // ==================== Export CSV & Helpers ====================

    private void printRecyclerView(RecyclerView rvData, String title) {
        if (getContext() == null || rvData == null) return;

        try {
            int width = rvData.getWidth();
            int height = rvData.getHeight();

            if (width <= 0 || height <= 0) {
                width = 1080;
                height = 1920;
            }

            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.WHITE);
            rvData.draw(canvas);

            android.print.PrintManager printManager = (android.print.PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
            android.print.PrintAttributes attributes = new android.print.PrintAttributes.Builder()
                    .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(new android.print.PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                    .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
                    .build();

            printManager.print(title, new android.print.PrintDocumentAdapter() {
                @Override
                public void onLayout(android.print.PrintAttributes oldAttributes, android.print.PrintAttributes newAttributes,
                                   android.os.CancellationSignal cancellationSignal,
                                   LayoutResultCallback callback, android.os.Bundle extras) {
                    callback.onLayoutFinished(new android.print.PrintDocumentInfo.Builder("report.pdf")
                            .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .build(), true);
                }

                @Override
                public void onWrite(android.print.PageRange[] pages, android.os.ParcelFileDescriptor destination,
                                  android.os.CancellationSignal cancellationSignal, WriteResultCallback callback) {
                    try {
                        android.graphics.pdf.PdfDocument pdfDocument = new android.graphics.pdf.PdfDocument();

                        android.graphics.pdf.PdfDocument.PageInfo pageInfo = new android.graphics.pdf.PdfDocument.PageInfo.Builder(
                                bitmap.getWidth(), bitmap.getHeight(), 1).create();

                        android.graphics.pdf.PdfDocument.Page page = pdfDocument.startPage(pageInfo);
                        page.getCanvas().drawBitmap(bitmap, 0, 0, null);
                        pdfDocument.finishPage(page);

                        pdfDocument.writeTo(new FileOutputStream(destination.getFileDescriptor()));
                        pdfDocument.close();

                        callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
                    } catch (Exception e) {
                        callback.onWriteFailed(e.getMessage());
                    }
                }
            }, attributes);

        } catch (Exception e) {
            Log.e(TAG, "Print error: " + e.getMessage());
            Toast.makeText(getContext(), "Print error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportReportToCSV(String type, String from, String to) {
        if (!isAdded() || getContext() == null) return;
        try {
            String cacheKey = "cache_pro_" + type + "_" + from + "_" + to;
            String cached = offlineCache != null ? offlineCache.getString(cacheKey, null) : null;
            if (cached == null) {
                Toast.makeText(getContext(), isArabic ? "لا توجد بيانات" : "No data", Toast.LENGTH_SHORT).show();
                return;
            }
            File exportDir = new File(requireContext().getCacheDir(), "exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            File file = new File(exportDir, "MIRA_Report_" + type + "_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(0xEF); fos.write(0xBB); fos.write(0xBF);
            fos.write(cached.getBytes("UTF-8"));
            fos.close();
            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share CSV"));
        } catch (Exception e) {
            Toast.makeText(getContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
        new DatePickerDialog(requireContext(), (v, y, m, d) -> 
            cb.onDate(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)), 
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    static class ReportItem { String icon, title, subtitle, type;
        ReportItem(String i, String t, String s, String ty) { icon=i; title=t; subtitle=s; type=ty; } }

    class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.VH> {
        final List<ReportItem> list; final OnReportClickListener listener;
        ReportsAdapter(List<ReportItem> l, OnReportClickListener c) { list=l; listener=c; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_card_pro, p, false)); }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReportItem item = list.get(pos);
            h.tvIcon.setText(item.icon); h.tvTitle.setText(item.title); h.tvSubtitle.setText(item.subtitle);
            h.itemView.setOnClickListener(v -> listener.onClick(item)); }
        @Override public int getItemCount() { return list.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvTitle, tvSubtitle;
            VH(View v) { super(v); tvIcon = v.findViewById(R.id.tvReportIcon); tvTitle = v.findViewById(R.id.tvReportTitle); tvSubtitle = v.findViewById(R.id.tvReportSubtitle); }
        }
    }

    interface OnReportClickListener { void onClick(ReportItem item); }
    interface DatePickerCallback { void onDate(String date); }

    @Override
    public void onDestroyView() {
        if (executorService != null && !executorService.isShutdown()) executorService.shutdownNow();
        super.onDestroyView();
    }
}
