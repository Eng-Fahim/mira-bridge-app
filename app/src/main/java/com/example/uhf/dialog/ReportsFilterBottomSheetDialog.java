package com.example.uhf.dialog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.example.uhf.R;
import com.example.uhf.model.ReportModels;

public class ReportsFilterBottomSheetDialog extends BottomSheetDialogFragment {

    public interface OnFilterApplyListener {
        void onApply(ReportModels.ReportFilters filters);
    }

    private OnFilterApplyListener listener;
    private ReportModels.ReportFilters currentFilters;

    public static ReportsFilterBottomSheetDialog newInstance(ReportModels.ReportFilters filters) {
        ReportsFilterBottomSheetDialog fragment = new ReportsFilterBottomSheetDialog();
        fragment.currentFilters = new ReportModels.ReportFilters(filters);
        return fragment;
    }

    public void setOnFilterApplyListener(OnFilterApplyListener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_advanced_filter, container, false);

        TextInputEditText etFromDate = view.findViewById(R.id.etFromDate);
        TextInputEditText etToDate = view.findViewById(R.id.etToDate);
        Button btnApply = view.findViewById(R.id.btnApplyFilter);

        if (currentFilters != null) {
            if (etFromDate != null) etFromDate.setText(currentFilters.fromDate);
            if (etToDate != null) etToDate.setText(currentFilters.toDate);
        }

        btnApply.setOnClickListener(v -> {
            if (currentFilters == null) currentFilters = new ReportModels.ReportFilters();
            if (etFromDate != null) currentFilters.fromDate = etFromDate.getText().toString().trim();
            if (etToDate != null) currentFilters.toDate = etToDate.getText().toString().trim();

            if (listener != null) {
                listener.onApply(currentFilters);
            }
            dismiss();
        });

        return view;
    }
}
