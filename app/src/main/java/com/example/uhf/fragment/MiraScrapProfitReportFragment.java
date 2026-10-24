package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraScrapProfitReportFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private TextView tvNetProfit, tvProfitMargin, tvPurchaseCost, tvReceivedValue, tvLossValue, tvUnsoldInfo, tvNoBatches;
    private MaterialButton btnDateFilter;
    private RecyclerView rvBatches;
    private BatchProfitAdapter adapter;
    private final List<JSONObject> batchesList = new ArrayList<>();
    
    private String fromDate, toDate;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_profit_report, container, false);
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        initViews(view);
        setupDateDefaults();
        setupListeners();
        
        loadReport();
        return view;
    }

    private void initViews(View v) {
        swipeRefresh     = v.findViewById(R.id.swipeRefresh);
        tvNetProfit      = v.findViewById(R.id.tvNetProfit);
        tvProfitMargin   = v.findViewById(R.id.tvProfitMargin);
        tvPurchaseCost   = v.findViewById(R.id.tvPurchaseCost);
        tvReceivedValue  = v.findViewById(R.id.tvReceivedValue);
        tvLossValue      = v.findViewById(R.id.tvLossValue);
        tvUnsoldInfo     = v.findViewById(R.id.tvUnsoldInfo);
        tvNoBatches      = v.findViewById(R.id.tvNoBatches);
        btnDateFilter    = v.findViewById(R.id.btnDateFilter);
        rvBatches        = v.findViewById(R.id.rvBatchesBreakdown);
        
        rvBatches.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BatchProfitAdapter(batchesList);
        rvBatches.setAdapter(adapter);
    }

    private void setupDateDefaults() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        fromDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        toDate   = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
    }

    private void setupListeners() {
        swipeRefresh.setOnRefreshListener(this::loadReport);
        btnDateFilter.setOnClickListener(v -> showDateFilterMenu());
    }

    private void showDateFilterMenu() {
        String[] options = {
            getString(R.string.this_month),
            getString(R.string.last_month),
            getString(R.string.last_3_months),
            getString(R.string.custom_range)
        };
        
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("اختر الفترة")
            .setItems(options, (d, which) -> {
                Calendar cal = Calendar.getInstance();
                switch (which) {
                    case 0: // هذا الشهر
                        cal.set(Calendar.DAY_OF_MONTH, 1);
                        fromDate = formatDate(cal);
                        toDate = formatDate(Calendar.getInstance());
                        btnDateFilter.setText(R.string.this_month);
                        break;
                    case 1: // الشهر الماضي
                        cal.add(Calendar.MONTH, -1);
                        cal.set(Calendar.DAY_OF_MONTH, 1);
                        fromDate = formatDate(cal);
                        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
                        toDate = formatDate(cal);
                        btnDateFilter.setText(R.string.last_month);
                        break;
                    case 2: // آخر 3 أشهر
                        cal.add(Calendar.MONTH, -3);
                        fromDate = formatDate(cal);
                        toDate = formatDate(Calendar.getInstance());
                        btnDateFilter.setText(R.string.last_3_months);
                        break;
                    case 3: // مخصص
                        showCustomDatePicker();
                        return;
                }
                loadReport();
            }).show();
    }

    private void showCustomDatePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (view, y, m, d) -> {
            fromDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
            
            Calendar cal2 = Calendar.getInstance();
            new DatePickerDialog(requireContext(), (view2, y2, m2, d2) -> {
                toDate = String.format(Locale.US, "%04d-%02d-%02d", y2, m2 + 1, d2);
                btnDateFilter.setText(fromDate + " → " + toDate);
                loadReport();
            }, cal2.get(Calendar.YEAR), cal2.get(Calendar.MONTH), cal2.get(Calendar.DAY_OF_MONTH)).show();
            
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String formatDate(Calendar cal) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private void loadReport() {
        swipeRefresh.setRefreshing(true);
        int branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();
        
        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.getScrapProfitReport(branchId, fromDate, toDate);
            
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                swipeRefresh.setRefreshing(false);
                if (response.isSuccess && response.data != null) {
                    renderReport(response.data);
                } else {
                    Toast.makeText(requireContext(), "فشل تحميل التقرير", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void renderReport(JSONObject data) {
        try {
            JSONObject summary = data.optJSONObject("summary");
            if (summary == null) return;
            
            double netProfit    = summary.optDouble("net_profit", 0);
            double margin       = summary.optDouble("profit_margin_percent", 0);
            double purchaseCost = summary.optDouble("total_purchase_cost", 0);
            double receivedVal  = summary.optDouble("total_received_value", 0);
            double lossVal      = summary.optDouble("total_loss_value", 0);
            double unsoldW      = summary.optDouble("unsold_weight", 0);
            double unsoldV      = summary.optDouble("unsold_value", 0);
            
            tvNetProfit.setText(String.format(Locale.US, "%s$%,.2f", netProfit >= 0 ? "+" : "", netProfit));
            tvNetProfit.setTextColor(netProfit >= 0 ? 0xFF16A34A : 0xFFDC2626);
            tvProfitMargin.setText(String.format(Locale.US, "%.1f%% هامش", margin));
            tvPurchaseCost.setText(String.format(Locale.US, "$%,.0f", purchaseCost));
            tvReceivedValue.setText(String.format(Locale.US, "$%,.0f", receivedVal));
            tvLossValue.setText(String.format(Locale.US, "$%,.0f", lossVal));
            tvUnsoldInfo.setText(String.format(Locale.US, "%.3f g (24K) — $%,.2f", unsoldW, unsoldV));
            
            JSONArray batches = data.optJSONArray("batches");
            batchesList.clear();
            if (batches != null && batches.length() > 0) {
                for (int i = 0; i < batches.length(); i++) {
                    batchesList.add(batches.optJSONObject(i));
                }
                tvNoBatches.setVisibility(View.GONE);
                rvBatches.setVisibility(View.VISIBLE);
            } else {
                tvNoBatches.setVisibility(View.VISIBLE);
                rvBatches.setVisibility(View.GONE);
            }
            adapter.notifyDataSetChanged();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Adapter
    // ═══════════════════════════════════════════════════════
    private class BatchProfitAdapter extends RecyclerView.Adapter<BatchProfitAdapter.VH> {
        private final List<JSONObject> items;
        BatchProfitAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_batch_profit, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject b = items.get(pos);
            h.tvNumber.setText(b.optString("batch_number"));
            h.tvDest.setText("→ " + b.optString("destination"));
            
            double sent = b.optDouble("sent_weight", 0);
            double recv = b.optDouble("received_weight", 0);
            double loss = b.optDouble("loss_weight", 0);
            double lossPct = b.optDouble("loss_percent", 0);
            
            h.tvWeights.setText(String.format(Locale.US, "📤 %.2fg → 📥 %.2fg", sent, recv));
            h.tvLoss.setText(String.format(Locale.US, "🔥 %.2fg (%.1f%%)", loss, lossPct));
            
            double profit = b.optDouble("profit", 0);
            double margin = b.optDouble("margin_percent", 0);
            h.tvProfit.setText(String.format(Locale.US, "%s$%,.2f", profit >= 0 ? "+" : "", profit));
            h.tvProfit.setTextColor(profit >= 0 ? 0xFF16A34A : 0xFFDC2626);
            h.tvMargin.setText(String.format(Locale.US, "%.1f%%", margin));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvNumber, tvDest, tvWeights, tvLoss, tvProfit, tvMargin;
            VH(View v) {
                super(v);
                tvNumber  = v.findViewById(R.id.tvBatchNumber);
                tvDest    = v.findViewById(R.id.tvDestination);
                tvWeights = v.findViewById(R.id.tvWeights);
                tvLoss    = v.findViewById(R.id.tvLoss);
                tvProfit  = v.findViewById(R.id.tvProfit);
                tvMargin  = v.findViewById(R.id.tvMargin);
            }
        }
    }
}
