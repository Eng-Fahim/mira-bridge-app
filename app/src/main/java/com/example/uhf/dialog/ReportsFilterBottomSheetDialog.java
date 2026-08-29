package com.example.uhf.dialog;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.model.ReportModels;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class ReportsFilterBottomSheetDialog extends BottomSheetDialogFragment {
    
    private ReportModels.ReportFilters currentFilters;
    private OnFilterAppliedListener listener;
    
    public interface OnFilterAppliedListener {
        void onFilterApplied(ReportModels.ReportFilters filters);
    }
    
    public ReportsFilterBottomSheetDialog(ReportModels.ReportFilters filters, 
                                          OnFilterAppliedListener listener) {
        this.currentFilters = new ReportModels.ReportFilters(filters);  // ✅ الآن يعمل
        this.listener = listener;
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_mira_report_viewer, container, false);
        
        MaterialButton btnFrom = view.findViewById(R.id.btnReportDateFrom);
        MaterialButton btnTo = view.findViewById(R.id.btnReportDateTo);
        MaterialButton btnApply = view.findViewById(R.id.btnReportApplyFilter);
        
        // تعيين التواريخ الحالية
        if (btnFrom != null && currentFilters.fromDate != null) {
            btnFrom.setText("من: " + currentFilters.fromDate);
        }
        if (btnTo != null && currentFilters.toDate != null) {
            btnTo.setText("إلى: " + currentFilters.toDate);
        }
        
        // اختيار التاريخ
        if (btnFrom != null) {
            btnFrom.setOnClickListener(v -> showDatePicker(date -> {
                currentFilters.fromDate = date;
                btnFrom.setText("من: " + date);
            }));
        }
        
        if (btnTo != null) {
            btnTo.setOnClickListener(v -> showDatePicker(date -> {
                currentFilters.toDate = date;
                btnTo.setText("إلى: " + date);
            }));
        }
        
        // تطبيق
        if (btnApply != null) {
            btnApply.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onFilterApplied(currentFilters);
                }
                dismiss();
            });
        }
        
        return view;
    }
    
    private void showDatePicker(DatePickerCallback cb) {
        if (getContext() == null) return;
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(getContext(), (v, y, m, d) -> 
            cb.onDate(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)), 
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }
    
    interface DatePickerCallback {
        void onDate(String date);
    }
}
