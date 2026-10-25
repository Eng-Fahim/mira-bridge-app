package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.ProfitAnalysisDto;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.utils.DateHelper;
import com.google.gson.Gson;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

public class MiraProfitAnalysisFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private TextView tvTotalProfit, tvGoldProfit, tvGoldRevenue, tvGoldCogs, tvMakingProfit, tvMakingRevenue;
    private Button btnFilterDate;
    private ProgressBar pbLoading;

    private String fromDate, toDate;
    private int branchId;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profit_analysis, container, false);
        
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        tvTotalProfit = view.findViewById(R.id.tvTotalProfit);
        tvGoldProfit = view.findViewById(R.id.tvGoldProfit);
        tvGoldRevenue = view.findViewById(R.id.tvGoldRevenue);
        tvGoldCogs = view.findViewById(R.id.tvGoldCogs);
        tvMakingProfit = view.findViewById(R.id.tvMakingProfit);
        tvMakingRevenue = view.findViewById(R.id.tvMakingRevenue);
        btnFilterDate = view.findViewById(R.id.btnFilterDate);
        pbLoading = view.findViewById(R.id.pbLoading);

        // إعداد التواريخ الافتراضية (بداية الشهر إلى اليوم)
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        fromDate = String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)+1, cal.get(Calendar.DAY_OF_MONTH));
        toDate = String.format(Locale.US, "%04d-%02d-%02d", Calendar.getInstance().get(Calendar.YEAR), Calendar.getInstance().get(Calendar.MONTH)+1, Calendar.getInstance().get(Calendar.DAY_OF_MONTH));
        
        branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();

        swipeRefresh.setOnRefreshListener(this::loadReport);
        btnFilterDate.setOnClickListener(v -> showDatePicker());

        loadReport();
        return view;
    }

    private void loadReport() {
        pbLoading.setVisibility(View.VISIBLE);
        btnFilterDate.setText(DateHelper.toDisplay(fromDate) + " → " + DateHelper.toDisplay(toDate));

        new Thread(() -> {
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.getProfitAnalysis(String.valueOf(branchId), fromDate, toDate);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data != null) {
                        Gson gson = new Gson();
                        ProfitAnalysisDto dto = gson.fromJson(data.toString(), ProfitAnalysisDto.class);
                        renderData(dto);
                    }
                } else {
                    String err = (response != null && response.error != null) ? response.error : "فشل الاتصال";
                    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void renderData(ProfitAnalysisDto dto) {
        String currency = " YER"; // يمكنك تعديلها حسب إعدادات النظام
        tvTotalProfit.setText(String.format(Locale.US, "%,.2f%s", dto.totalProfit, currency));
        tvGoldProfit.setText(String.format(Locale.US, "%,.2f%s", dto.goldProfit, currency));
        tvGoldRevenue.setText(String.format(Locale.US, "إيراد الذهب: %,.2f%s", dto.goldRevenue, currency));
        tvGoldCogs.setText(String.format(Locale.US, "(-) تكلفة الذهب: %,.2f%s", dto.cogs, currency));
        tvMakingProfit.setText(String.format(Locale.US, "%,.2f%s", dto.makingProfit, currency));
        tvMakingRevenue.setText(String.format(Locale.US, "إيراد المصنعية: %,.2f%s", dto.makingRevenue, currency));
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            fromDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            
            new DatePickerDialog(requireContext(), (view2, year2, month2, dayOfMonth2) -> {
                toDate = String.format(Locale.US, "%04d-%02d-%02d", year2, month2 + 1, dayOfMonth2);
                loadReport();
            }, year, month, dayOfMonth).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }
}
