package com.example.uhf.fragment;

import android.content.Context;
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
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MiraReportsProFragment extends Fragment {

    private static final String TAG = "MiraReportsPro";
    private RecyclerView rvReports;
    private ReportsAdapter adapter;
    private final List<ReportItem> reportList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);

        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
        }

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new GridLayoutManager(getContext(), 2));

        buildReportsList();
        adapter = new ReportsAdapter(reportList, this::openReportDialog);
        rvReports.setAdapter(adapter);

        return view;
    }

    private void buildReportsList() {
        reportList.clear();
        reportList.add(new ReportItem("📦", "المخزون", "الجرامات والعيارات", "inventory"));
        reportList.add(new ReportItem("💰", "المبيعات", "حركة النقد", "sales"));
        reportList.add(new ReportItem("📊", "لوحة القيادة", "ملخص شامل", "dashboard"));
        reportList.add(new ReportItem("🔄", "التحويلات", "حركة الفروع", "movement"));
        reportList.add(new ReportItem("🏢", "الفروع", "مقارنة الأداء", "branches"));
        reportList.add(new ReportItem("🚪", "البوابات", "سجل الإنذارات", "gates"));
        reportList.add(new ReportItem("📋", "الجرد", "المطابقة", "audit"));
        reportList.add(new ReportItem("💎", "النواقص", "إعادة الطلب", "reorder"));
    }

    private void openReportDialog(ReportItem item) {
        Toast.makeText(getContext(), "جاري تحميل " + item.title + "...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            JSONObject reportData = null;
            String errorMsg = null;

            try {
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
                String fromDate = getCalculatedDate(-30);
                String toDate = getDefaultToDate();

                MiraApiClient.ApiResponse response = apiClient.getReportData(
                    item.type, fromDate, toDate, branchId
                );

                Log.e(TAG, "Response code: " + (response != null ? response.code : "NULL"));
                Log.e(TAG, "Response success: " + (response != null ? response.isSuccess : "NULL"));
                Log.e(TAG, "Response data: " + (response != null && response.data != null ? response.data.toString() : "NULL"));

                if (response != null && response.isSuccess && response.data != null) {
                    reportData = response.data.optJSONObject("data");
                    if (reportData == null) reportData = response.data;
                } else {
                    errorMsg = "فشل الاتصال بالسيرفر (code: " + (response != null ? response.code : "NULL") + ")";
                }
            } catch (Exception e) {
                errorMsg = e.getMessage();
                Log.e(TAG, "Exception: " + e.getMessage());
            }

            final JSONObject finalData = reportData;
            final String finalError = errorMsg;

            mainHandler.post(() -> {
                if (finalData != null) {
                    showSimpleDialog(item, finalData);
                } else {
                    Toast.makeText(getContext(), "❌ " + (finalError != null ? finalError : "لا توجد بيانات"), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showSimpleDialog(ReportItem item, JSONObject data) {
        try {
            StringBuilder msg = new StringBuilder();
            msg.append(item.icon).append(" ").append(item.title).append("\n\n");

            // استخراج كل البيانات المتاحة
            JSONObject inventory = data.optJSONObject("inventory");
            if (inventory != null) {
                msg.append("📦 القطع: ").append(inventory.optInt("total_items", 0)).append("\n");
                msg.append("⚖️ الوزن: ").append(inventory.optDouble("total_weight", 0)).append(" جم\n");
                msg.append("✨ 24K: ").append(inventory.optDouble("net_24k_weight", 0)).append(" جم\n");
                
                JSONArray karats = inventory.optJSONArray("karat_breakdown");
                if (karats != null && karats.length() > 0) {
                    msg.append("\nتفاصيل العيارات:\n");
                    for (int i = 0; i < karats.length(); i++) {
                        JSONObject k = karats.optJSONObject(i);
                        if (k != null) {
                            msg.append("  ").append(k.optString("karat", "?"))
                               .append(": ").append(k.optDouble("gross_weight", 0)).append(" جم (")
                               .append(k.optInt("count", 0)).append(" قطعة)\n");
                        }
                    }
                }
            }

            JSONObject sales = data.optJSONObject("sales");
            if (sales != null) {
                msg.append("\n💰 الإيرادات: ").append(sales.optDouble("total_revenue", 0)).append("\n");
                msg.append("📈 الأرباح: ").append(sales.optDouble("net_profit", 0)).append("\n");
                msg.append("🧾 الفواتير: ").append(sales.optInt("invoices_count", 0)).append("\n");
            }

            JSONArray movements = data.optJSONArray("movements");
            if (movements != null) {
                msg.append("\n🔄 التحويلات: ").append(movements.length()).append("\n");
            }

            JSONArray branches = data.optJSONArray("branches_performance");
            if (branches != null) {
                msg.append("\n🏢 الفروع: ").append(branches.length()).append("\n");
            }

            JSONArray gates = data.optJSONArray("gate_logs");
            if (gates != null) {
                msg.append("\n🚪 سجلات: ").append(gates.length()).append("\n");
            }

            JSONObject audit = data.optJSONObject("audit_summary");
            if (audit != null) {
                msg.append("\n📋 الدقة: ").append(audit.optDouble("accuracy", 0)).append("%\n");
            }

            new AlertDialog.Builder(requireContext())
                .setTitle(item.icon + " " + item.title)
                .setMessage(msg.toString())
                .setPositiveButton("إغلاق", null)
                .show();

        } catch (Exception e) {
            Toast.makeText(getContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String getDefaultToDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private String getCalculatedDate(int daysOffset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, daysOffset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    static class ReportItem {
        String icon, title, subtitle, type;
        ReportItem(String icon, String title, String subtitle, String type) {
            this.icon = icon; this.title = title; this.subtitle = subtitle; this.type = type;
        }
    }

    class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.VH> {
        final List<ReportItem> list;
        final OnReportClickListener listener;

        ReportsAdapter(List<ReportItem> list, OnReportClickListener listener) {
            this.list = list; this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_report_card_pro, parent, false);
            return new VH(v);
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
            VH(View v) {
                super(v);
                tvIcon = v.findViewById(R.id.tvReportIcon);
                tvTitle = v.findViewById(R.id.tvReportTitle);
                tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
            }
        }
    }

    interface OnReportClickListener { void onClick(ReportItem item); }
}
