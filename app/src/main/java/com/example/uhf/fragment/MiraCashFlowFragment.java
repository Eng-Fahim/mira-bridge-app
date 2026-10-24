package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.CashFlowDto;
import com.example.uhf.api.dto.CashFlowItemDto;
import com.google.gson.Gson;

import org.json.JSONObject; // ✅ تم إضافة هذا السطر

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class MiraCashFlowFragment extends Fragment {

    private TextView tvDate, tvNetCashFlow, tvTotalInflow, tvTotalOutflow;
    private LinearLayout llInflowContainer, llOutflowContainer;
    private String selectedDate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_cash_flow, container, false);
        
        tvDate = view.findViewById(R.id.tvDate);
        tvNetCashFlow = view.findViewById(R.id.tvNetCashFlow);
        tvTotalInflow = view.findViewById(R.id.tvTotalInflow);
        tvTotalOutflow = view.findViewById(R.id.tvTotalOutflow);
        llInflowContainer = view.findViewById(R.id.llInflowContainer);
        llOutflowContainer = view.findViewById(R.id.llOutflowContainer);

        // تعيين تاريخ اليوم كافتراضي
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        selectedDate = sdf.format(Calendar.getInstance().getTime());
        tvDate.setText("📅 " + selectedDate);

        view.findViewById(R.id.btnPickDate).setOnClickListener(v -> showDatePicker());

        loadCashFlow(selectedDate);

        return view;
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            Calendar selectedCal = Calendar.getInstance();
            selectedCal.set(year, month, dayOfMonth);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            selectedDate = sdf.format(selectedCal.getTime());
            tvDate.setText("📅 " + selectedDate);
            loadCashFlow(selectedDate);
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadCashFlow(String date) {
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.getCashFlowStatement(date, 0); // 0 = كل الفروع

                if (response != null && response.isSuccess && response.data != null) {
                    Gson gson = new Gson();
                    
                    // ✅ التصحيح: استخدام getJSONObject
                    JSONObject jsonData = response.data.getJSONObject("data");
                    CashFlowDto dto = gson.fromJson(jsonData.toString(), CashFlowDto.class);
                    
                    requireActivity().runOnUiThread(() -> {
                        if (dto != null) {
                            updateUI(dto);
                        }
                    });
                } else {
                    requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "لا توجد بيانات لهذا اليوم", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "حدث خطأ في الاتصال", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void updateUI(CashFlowDto dto) {
        tvNetCashFlow.setText(String.format(Locale.US, "%,.2f YER", dto.net_cash_flow));
        tvTotalInflow.setText(String.format(Locale.US, "إجمالي الداخل: %,.2f", dto.total_inflow));
        tvTotalOutflow.setText(String.format(Locale.US, "إجمالي الخارج: %,.2f", dto.total_outflow));

        // تغيير لون صافي الحركة إذا كان سالباً
        if (dto.net_cash_flow < 0) {
            tvNetCashFlow.setTextColor(0xFFDC2626); // أحمر
        } else {
            tvNetCashFlow.setTextColor(0xFFFFFFFF); // أبيض
        }

        // تعبئة قائمة الداخل
        llInflowContainer.removeAllViews();
        if (dto.inflow != null) {
            for (CashFlowItemDto item : dto.inflow) {
                addRow(llInflowContainer, item, true);
            }
        }

        // تعبئة قائمة الخارج
        llOutflowContainer.removeAllViews();
        if (dto.outflow != null) {
            for (CashFlowItemDto item : dto.outflow) {
                addRow(llOutflowContainer, item, false);
            }
        }
    }

    private void addRow(LinearLayout container, CashFlowItemDto item, boolean isInflow) {
        View row = getLayoutInflater().inflate(R.layout.item_cash_flow_row, container, false);
        TextView tvTitle = row.findViewById(R.id.tvRowTitle);
        TextView tvAmount = row.findViewById(R.id.tvRowAmount);

        tvTitle.setText(item.label + (item.account != null && item.account.contains("بنك") ? " (بنك)" : " (نقدي)"));
        tvAmount.setText(String.format(Locale.US, "%s%,.2f", isInflow ? "+" : "-", item.amount));
        
        // تلوين المبلغ
        tvAmount.setTextColor(isInflow ? 0xFF16A34A : 0xFFDC2626);

        container.addView(row);
    }
}
