package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.InventoryRevaluationDto;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.button.MaterialButton;
import com.google.gson.Gson;

import org.json.JSONObject;

import java.util.Locale;

public class MiraInventoryRevaluationFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private TextView tvGoldGrams, tvGramPrice, tvBookValue, tvMarketValue, tvDifferenceLabel, tvDifference;
    private MaterialButton btnPostRevaluation;
    private ProgressBar pbLoading;

    private InventoryRevaluationDto currentDto;
    private int branchId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_inventory_revaluation, container, false);
        
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        tvGoldGrams = view.findViewById(R.id.tvGoldGrams);
        tvGramPrice = view.findViewById(R.id.tvGramPrice);
        tvBookValue = view.findViewById(R.id.tvBookValue);
        tvMarketValue = view.findViewById(R.id.tvMarketValue);
        tvDifferenceLabel = view.findViewById(R.id.tvDifferenceLabel);
        tvDifference = view.findViewById(R.id.tvDifference);
        btnPostRevaluation = view.findViewById(R.id.btnPostRevaluation);
        pbLoading = view.findViewById(R.id.pbLoading);

        branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();

        swipeRefresh.setOnRefreshListener(this::loadPreview);
        btnPostRevaluation.setOnClickListener(v -> postRevaluation());

        loadPreview();
        return view;
    }

    private void loadPreview() {
        pbLoading.setVisibility(View.VISIBLE);
        btnPostRevaluation.setVisibility(View.GONE);

        new Thread(() -> {
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.previewInventoryRevaluation(branchId);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data != null) {
                        currentDto = new Gson().fromJson(data.toString(), InventoryRevaluationDto.class);
                        renderData(currentDto);
                    }
                } else {
                    String err = (response != null && response.error != null) ? response.error : "فشل الاتصال";
                    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void renderData(InventoryRevaluationDto dto) {
        tvGoldGrams.setText(String.format(Locale.US, "إجمالي وزن الذهب: %,.3f g (24K)", dto.totalGoldGrams));
        tvGramPrice.setText(String.format(Locale.US, "سعر الجرام الحالي: %,.2f", dto.currentGramPrice));
        tvBookValue.setText(String.format(Locale.US, "القيمة الدفترية: %,.2f", dto.bookValue));
        tvMarketValue.setText(String.format(Locale.US, "قيمة السوق: %,.2f", dto.marketValue));

        if ("gain".equals(dto.status)) {
            tvDifferenceLabel.setText("الفرق (ربح تقييم):");
            tvDifference.setText(String.format(Locale.US, "+%,.2f", dto.difference));
            tvDifference.setTextColor(0xFF16A34A); // أخضر
            btnPostRevaluation.setVisibility(View.VISIBLE);
        } else if ("loss".equals(dto.status)) {
            tvDifferenceLabel.setText("الفرق (خسارة تقييم):");
            tvDifference.setText(String.format(Locale.US, "-%,.2f", Math.abs(dto.difference)));
            tvDifference.setTextColor(0xFFDC2626); // أحمر
            btnPostRevaluation.setVisibility(View.VISIBLE);
        } else {
            tvDifferenceLabel.setText("لا يوجد فرق في التقييم");
            tvDifference.setText("0.00");
            tvDifference.setTextColor(0xFF6B7280);
            btnPostRevaluation.setVisibility(View.GONE);
        }
    }

    private void postRevaluation() {
        if (currentDto == null || Math.abs(currentDto.difference) < 0.01) return;

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("تأكيد الترحيل")
            .setMessage("هل أنت متأكد من ترحيل قيد إعادة التقييم؟ سيتم تعديل قيمة المخزون محاسبياً.")
            .setPositiveButton("نعم، رحّل القيد", (d, w) -> {
                btnPostRevaluation.setEnabled(false);
                btnPostRevaluation.setText("جاري الترحيل...");

                new Thread(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("branch_id", branchId);
                        payload.put("difference", currentDto.difference);

                        MiraApiClient client = MiraApiClient.getInstance(requireContext());
                        MiraApiClient.ApiResponse response = client.postInventoryRevaluation(payload);

                        getActivity().runOnUiThread(() -> {
                            btnPostRevaluation.setEnabled(true);
                            btnPostRevaluation.setText("✅ ترحيل قيد إعادة التقييم");

                            if (response != null && response.isSuccess) {
                                Toast.makeText(requireContext(), "✅ تم ترحيل القيد بنجاح", Toast.LENGTH_LONG).show();
                                loadPreview(); // إعادة التحميل لتصفير الفرق
                            } else {
                                String err = (response != null && response.error != null) ? response.error : "فشل الترحيل";
                                Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }
}
