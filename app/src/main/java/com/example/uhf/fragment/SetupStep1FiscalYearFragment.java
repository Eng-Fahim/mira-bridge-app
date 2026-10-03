package com.example.uhf.fragment;

import android.app.DatePickerDialog;
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
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * المرحلة 1: السنة المالية
 */
public class SetupStep1FiscalYearFragment extends Fragment {

    private static final String TAG = "SetupStep1";

    private TextInputEditText etStartDate;
    private TextInputEditText etEndDate;
    private MaterialButton btnBack;
    private MaterialButton btnNext;
    private TextView tvTitle;

    private MiraApiClient apiClient;
    private String startDate;
    private String endDate;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step1_fiscal_year, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        // الافتراضي: بداية ونهاية السنة الحالية
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MONTH, Calendar.JANUARY);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        startDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        
        cal.set(Calendar.MONTH, Calendar.DECEMBER);
        cal.set(Calendar.DAY_OF_MONTH, 31);
        endDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        
        initViews(view);
        setupListeners();
        
        return view;
    }

    private void initViews(View v) {
        etStartDate = v.findViewById(R.id.etStartDate);
        etEndDate = v.findViewById(R.id.etEndDate);
        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);
        tvTitle = v.findViewById(R.id.tvTitle);
        
        etStartDate.setText(startDate);
        etEndDate.setText(endDate);
        etStartDate.setFocusable(false);
        etEndDate.setFocusable(false);
    }

    private void setupListeners() {
        etStartDate.setOnClickListener(v -> pickDate(true));
        etEndDate.setOnClickListener(v -> pickDate(false));
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
    }

    private void pickDate(boolean isStart) {
        Calendar cal = Calendar.getInstance();
        try {
            String date = isStart ? startDate : endDate;
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date);
            if (parsed != null) cal.setTime(parsed);
        } catch (Exception ignored) {}
        
        new DatePickerDialog(requireContext(),
            (view, year, month, dayOfMonth) -> {
                String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                if (isStart) {
                    startDate = date;
                    etStartDate.setText(date);
                } else {
                    endDate = date;
                    etEndDate.setText(date);
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void saveAndNext() {
        if (startDate == null || startDate.isEmpty() || 
            endDate == null || endDate.isEmpty()) {
            Toast.makeText(requireContext(), "يرجى إدخال التاريخين", Toast.LENGTH_SHORT).show();
            return;
        }
        
        btnNext.setEnabled(false);
        btnNext.setText("جاري الحفظ...");
        
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("start_date", startDate);
                payload.put("end_date", endDate);
                payload.put("calendar", "gregorian");
                
                MiraApiClient.ApiResponse response = 
                    apiClient.postWithAuth("setup/step/1", payload);
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnNext.setEnabled(true);
                    btnNext.setText("التالي ←");
                    
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), 
                            "✅ تم حفظ السنة المالية", Toast.LENGTH_SHORT).show();
                        
                        // الانتقال للمرحلة التالية
                        openNextStep();
                    } else {
                        String error = response != null ? response.error : "غير معروف";
                        Toast.makeText(requireContext(), 
                            "❌ فشل: " + error, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "save error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnNext.setEnabled(true);
                    btnNext.setText("التالي ←");
                    Toast.makeText(requireContext(), 
                        "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void openNextStep() {
        SetupStep2BranchesFragment next = new SetupStep2BranchesFragment();
        requireActivity().getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.fragment_container, next)
            .addToBackStack("SetupStep2")
            .commit();
    }
}
