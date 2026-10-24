package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraScrapAgingFragment extends Fragment {

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SwipeRefreshLayout swipeRefresh;
    private TextView tvTotalWeight, tvTotalValue, tvTotalCount;
    private TextView tvFreshWeight, tvFreshCount;
    private TextView tvMediumWeight, tvMediumCount;
    private TextView tvOldWeight, tvOldCount;
    private TextView tvWarningText, tvEmpty;
    private MaterialCardView cardWarning;
    private LinearLayout llItems;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_scrap_aging, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());

        swipeRefresh = v.findViewById(R.id.swipeRefreshAging);
        tvTotalWeight = v.findViewById(R.id.tvAgingTotalWeight);
        tvTotalValue = v.findViewById(R.id.tvAgingTotalValue);
        tvTotalCount = v.findViewById(R.id.tvAgingTotalCount);
        tvFreshWeight = v.findViewById(R.id.tvFreshWeight);
        tvFreshCount = v.findViewById(R.id.tvFreshCount);
        tvMediumWeight = v.findViewById(R.id.tvMediumWeight);
        tvMediumCount = v.findViewById(R.id.tvMediumCount);
        tvOldWeight = v.findViewById(R.id.tvOldWeight);
        tvOldCount = v.findViewById(R.id.tvOldCount);
        tvWarningText = v.findViewById(R.id.tvAgingWarningText);
        tvEmpty = v.findViewById(R.id.tvAgingEmpty);
        cardWarning = v.findViewById(R.id.cardAgingWarning);
        llItems = v.findViewById(R.id.llAgingItems);

        swipeRefresh.setOnRefreshListener(this::loadData);
        loadData();
        return v;
    }

    private void loadData() {
        swipeRefresh.setRefreshing(true);
        executor.execute(() -> {
            int branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();
            MiraApiClient.ApiResponse res = apiClient.getScrapAgingReport(branchId);

            mainHandler.post(() -> {
                swipeRefresh.setRefreshing(false);
                if (res.isSuccess && res.data != null) {
                    render(res.data);
                } else {
                    Toast.makeText(getContext(), "فشل تحميل التقرير", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void render(JSONObject data) {
        try {
            JSONObject summary = data.optJSONObject("summary");
            if (summary == null) return;

            tvTotalWeight.setText(String.format(Locale.US, "%.3f g", summary.optDouble("total_weight", 0)));
            tvTotalValue.setText(String.format(Locale.US, "%,.2f YER", summary.optDouble("total_value", 0)));
            tvTotalCount.setText(summary.optInt("total_items", 0) + " فاتورة");

            JSONObject fresh = summary.optJSONObject("fresh");
            JSONObject medium = summary.optJSONObject("medium");
            JSONObject old = summary.optJSONObject("old");

            if (fresh != null) {
                tvFreshWeight.setText(String.format(Locale.US, "%.3fg", fresh.optDouble("weight", 0)));
                tvFreshCount.setText(fresh.optInt("count", 0) + " فاتورة");
            }
            if (medium != null) {
                tvMediumWeight.setText(String.format(Locale.US, "%.3fg", medium.optDouble("weight", 0)));
                tvMediumCount.setText(medium.optInt("count", 0) + " فاتورة");
            }
            if (old != null) {
                tvOldWeight.setText(String.format(Locale.US, "%.3fg", old.optDouble("weight", 0)));
                tvOldCount.setText(old.optInt("count", 0) + " فاتورة");
            }

            // Warning Banner
            int oldCount = old != null ? old.optInt("count", 0) : 0;
            if (oldCount > 0) {
                cardWarning.setVisibility(View.VISIBLE);
                tvWarningText.setText("يوجد " + oldCount + " فاتورة كسر مضى عليها أكثر من 60 يوماً!\nالوقت المناسب لإرسالها للمصفاة.");
            } else {
                cardWarning.setVisibility(View.GONE);
            }

            // Items List
            llItems.removeAllViews();
            JSONArray items = data.optJSONArray("items");
            
            if (items == null || items.length() == 0) {
                tvEmpty.setVisibility(View.VISIBLE);
                return;
            }
            tvEmpty.setVisibility(View.GONE);

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                addItemRow(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addItemRow(JSONObject item) {
        View row = getLayoutInflater().inflate(R.layout.item_scrap_aging, llItems, false);
        
        View colorBar = row.findViewById(R.id.ageColorBar);
        TextView tvNumber = row.findViewById(R.id.tvAgingPurchaseNumber);
        TextView tvCustomer = row.findViewById(R.id.tvAgingCustomer);
        TextView tvWeight = row.findViewById(R.id.tvAgingWeight);
        TextView tvAge = row.findViewById(R.id.tvAgingAge);
        TextView tvValue = row.findViewById(R.id.tvAgingValue);

        String category = item.optString("age_category", "fresh");
        int ageDays = item.optInt("age_days", 0);

        int color;
        String ageLabel;
        if ("fresh".equals(category)) {
            color = 0xFF16A34A;
            ageLabel = "🟢 " + ageDays + " يوم";
        } else if ("medium".equals(category)) {
            color = 0xFFF59E0B;
            ageLabel = "🟡 " + ageDays + " يوم";
        } else {
            color = 0xFFDC2626;
            ageLabel = "🔴 " + ageDays + " يوم";
        }

        colorBar.setBackgroundColor(color);
        tvAge.setText(ageLabel);
        tvAge.setTextColor(color);

        tvNumber.setText(item.optString("purchase_number", ""));
        String customer = item.optString("customer_name", "");
        tvCustomer.setText(customer.isEmpty() ? "عميل نقدي" : "👤 " + customer);
        tvWeight.setText(String.format(Locale.US, "⚖️ %.3fg (%s)", 
            item.optDouble("net_pure_weight_24k", 0), 
            item.optString("gold_karat", "")));
        tvValue.setText(String.format(Locale.US, "%,.2f YER", item.optDouble("total_amount", 0)));

        // عند الضغط على الفاتورة → الانتقال إلى الدفعات لإنشاء دفعة منها
        row.setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(
                    new MiraScrapBatchesFragment(), "ScrapBatches");
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }
}
