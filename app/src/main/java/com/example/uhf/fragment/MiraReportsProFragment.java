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
        rvReports.setLayoutManager(new LinearLayoutManager(getContext()));

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
        }
    }

    private void openReportDialog(ReportItem item) {
        if (getContext() == null || !isAdded()) return;

        if (permissionManager != null && !permissionManager.canAccessFragment("Reports")) {
            Toast.makeText(getContext(), isArabic ? "⛔ غير مصرح" : "⛔ Access Denied", Toast.LENGTH_SHORT).show();
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

        dialog.show();

        fetchAndDisplay(item.type, rvData, layoutLoading, tvLoadingStatus, selectedDates[0], selectedDates[1]);
    }

    private void fetchAndDisplay(String type, RecyclerView rvData, View layoutLoading, 
                                  TextView tvLoadingStatus, String fromDate, String toDate) {
        if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
        if (tvLoadingStatus != null) tvLoadingStatus.setText(isArabic ? "جاري التحميل..." : "Loading...");

        executorService.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getReportData(type, fromDate, toDate, currentBranchId);

                mainHandler.post(() -> {
                    if (!isAdded() || layoutLoading != null && layoutLoading.getVisibility() == View.VISIBLE) {
                        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    }

                    if (response == null || !response.isSuccess || response.data == null) {
                        Toast.makeText(getContext(), isArabic ? "فشل التحميل" : "Failed to load", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    JSONObject data = response.data;
                    String cacheKey = "cache_pro_" + type + "_" + fromDate + "_" + toDate;
                    if (offlineCache != null) {
                        offlineCache.edit().putString(cacheKey, data.toString()).apply();
                    }

                    // ✅ اختيار الـ Adapter المناسب
                    switch (type) {
                        case "sales":
                            rvData.setAdapter(new SalesAdapter(parseSales(data)));
                            break;
                        case "inventory":
                            rvData.setAdapter(new InventoryAdapter(parseInventory(data)));
                            break;
                        case "audit":
                            rvData.setAdapter(new AuditAdapter(parseAudit(data)));
                            break;
                        case "reorder":
                            rvData.setAdapter(new ReorderAdapter(parseReorder(data)));
                            break;
                        case "users":
                            rvData.setAdapter(new UsersAdapter(parseUsers(data)));
                            break;
                        case "gates":
                            rvData.setAdapter(new GatesAdapter(parseGates(data)));
                            break;
                        case "movement":
                            rvData.setAdapter(new MovementAdapter(parseMovement(data)));
                            break;
                        case "branches":
                            rvData.setAdapter(new BranchesAdapter(parseBranches(data)));
                            break;
                        case "financial":
                            rvData.setAdapter(new FinancialAdapter(parseFinancial(data)));
                            break;
                        default:
                            rvData.setAdapter(new GenericAdapter(parseGeneric(data)));
                            break;
                    }
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

    // ==================== Parsing Functions ====================

    private List<SalesItem> parseSales(JSONObject data) {
        List<SalesItem> items = new ArrayList<>();
        JSONArray invoices = data.optJSONArray("invoices");
        if (invoices != null) {
            for (int i = 0; i < invoices.length(); i++) {
                JSONObject inv = invoices.optJSONObject(i);
                if (inv != null) {
                    items.add(new SalesItem(
                        inv.optString("invoice_no", inv.optString("sale_number", "-")),
                        inv.optString("customer_name", "-"),
                        inv.optDouble("final_amount", inv.optDouble("total", 0)),
                        inv.optString("payment_method", "-"),
                        inv.optString("seller_name", "-")
                    ));
                }
            }
        }
        return items;
    }

    private List<InventoryItem> parseInventory(JSONObject data) {
        List<InventoryItem> items = new ArrayList<>();
        JSONArray arr = data.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new InventoryItem(
                        obj.optString("item_title", "-"),
                        obj.optString("gold_karat", "-"),
                        obj.optDouble("gross_weight_grams", 0),
                        obj.optString("vault_status", "-"),
                        obj.optString("current_location", "-")
                    ));
                }
            }
        }
        return items;
    }

    private List<AuditItem> parseAudit(JSONObject data) {
        List<AuditItem> items = new ArrayList<>();
        JSONObject audit = data.optJSONObject("audit_summary");
        if (audit != null) {
            items.add(new AuditItem(
                audit.optDouble("accuracy", 0),
                audit.optInt("found_items", 0),
                audit.optInt("missing_items", 0),
                audit.optInt("total_sessions", 0)
            ));
        }
        return items;
    }

    private List<ReorderItem> parseReorder(JSONObject data) {
        List<ReorderItem> items = new ArrayList<>();
        JSONArray arr = data.optJSONArray("reorder_items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new ReorderItem(
                        obj.optString("gold_karat", "-"),
                        obj.optInt("sold_count", 0),
                        obj.optInt("remaining_stock", 0)
                    ));
                }
            }
        }
        return items;
    }

    private List<UsersItem> parseUsers(JSONObject data) {
        List<UsersItem> items = new ArrayList<>();
        JSONArray arr = data.optJSONArray("users");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new UsersItem(
                        obj.optString("display_name", "-"),
                        obj.optString("role_name", "-"),
                        obj.optString("status", "-")
                    ));
                }
            }
        }
        return items;
    }

    private List<GatesItem> parseGates(JSONObject data) {
        List<GatesItem> items = new ArrayList<>();
        JSONArray arr = data.optJSONArray("gate_logs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new GatesItem(
                        obj.optString("gate_name", "-"),
                        obj.optString("epc", "-"),
                        obj.optString("created_at", "-")
                    ));
                }
            }
        }
        return items;
    }

    private List<MovementItem> parseMovement(JSONObject data) {
        List<MovementItem> items = new ArrayList<>();
        JSONArray arr = data.optJSONArray("movements");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new MovementItem(
                        obj.optString("from_branch", "-"),
                        obj.optString("to_branch", "-"),
                        obj.optString("created_at", "-")
                    ));
                }
            }
        }
        return items;
    }

    private List<BranchesItem> parseBranches(JSONObject data) {
        List<BranchesItem> items = new ArrayList<>();
        JSONArray arr = data.optJSONArray("branches_performance");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new BranchesItem(
                        obj.optString("branch_name", "-"),
                        obj.optInt("items_count", 0)
                    ));
                }
            }
        }
        return items;
    }

    private List<FinancialItem> parseFinancial(JSONObject data) {
        List<FinancialItem> items = new ArrayList<>();
        JSONArray arr = data.optJSONArray("journal_entries");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    items.add(new FinancialItem(
                        obj.optString("description", "-"),
                        obj.optDouble("amount", 0)
                    ));
                }
            }
        }
        return items;
    }

    private List<GenericItem> parseGeneric(JSONObject data) {
        List<GenericItem> items = new ArrayList<>();
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = data.opt(key);
            if (value != null) {
                items.add(new GenericItem(key, value.toString()));
            }
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

    // ==================== Export CSV ====================

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
