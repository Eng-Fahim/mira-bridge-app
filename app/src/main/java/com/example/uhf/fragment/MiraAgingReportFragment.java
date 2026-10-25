package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.AgingReportAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.AgingReportDto;
import com.google.gson.Gson;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class MiraAgingReportFragment extends Fragment {

    private Button btnPickDateAging;
    private Spinner spAgingType;
    private RecyclerView rvAging;
    private TextView tvAgingTotal;
    private ProgressBar pbLoading;

    private AgingReportAdapter adapter;
    private String currentDate;
    private final String[] types = {"عملاء (مدينون)", "موردون (دائنون)"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_aging_report, container, false);

        btnPickDateAging = v.findViewById(R.id.btnPickDateAging);
        spAgingType = v.findViewById(R.id.spAgingType);
        rvAging = v.findViewById(R.id.rvAging);
        tvAgingTotal = v.findViewById(R.id.tvAgingTotal);
        pbLoading = v.findViewById(R.id.pbLoading); // تأكد من إضافته في الـ XML

        // إعداد القائمة المنسدلة
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, types);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAgingType.setAdapter(typeAdapter);

        // التاريخ الحالي
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        currentDate = sdf.format(Calendar.getInstance().getTime());
        btnPickDateAging.setText("📅 " + currentDate);

        // إعداد الجدول
        adapter = new AgingReportAdapter();
        rvAging.setLayoutManager(new LinearLayoutManager(getContext()));
        rvAging.setAdapter(adapter);

        // المستمعين
        btnPickDateAging.setOnClickListener(view -> showDatePicker());
        spAgingType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                loadData();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        loadData();
        return v;
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, dayOfMonth);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            currentDate = sdf.format(selected.getTime());
            btnPickDateAging.setText("📅 " + currentDate);
            loadData();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadData() {
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        String type = spAgingType.getSelectedItemPosition() == 0 ? "receivable" : "payable";

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.getAgingReport(currentDate, type);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                        
                        if (response != null && response.isSuccess && response.data != null) {
                            Gson gson = new Gson();
                            JSONObject dataObj = response.data.optJSONObject("data");
                            if (dataObj != null) {
                                AgingReportDto dto = gson.fromJson(dataObj.toString(), AgingReportDto.class);
                                
                                if (dto != null && dto.rows != null) {
                                    adapter.setItems(dto.rows);
                                    
                                    // تحديث التذييل
                                    if (dto.totals != null) {
                                        double grandTotal = dto.totals.total0_30 + dto.totals.total31_60 + dto.totals.total61_90 + dto.totals.totalOver90;
                                        tvAgingTotal.setText(String.format(Locale.US, "إجمالي الديون: %,.2f", grandTotal));
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(requireContext(), "لا توجد بيانات", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "خطأ في الاتصال", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }).start();
    }
}
