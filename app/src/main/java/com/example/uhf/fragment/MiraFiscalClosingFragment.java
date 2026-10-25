package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Calendar;

public class MiraFiscalClosingFragment extends Fragment {

    private TextInputEditText etFiscalYear;
    private MaterialButton btnCloseYear;
    private ProgressBar pbLoading;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_fiscal_closing, container, false);

        etFiscalYear = v.findViewById(R.id.etFiscalYear);
        btnCloseYear = v.findViewById(R.id.btnCloseYear);
        pbLoading = v.findViewById(R.id.pbLoading);

        // تعيين السنة الحالية افتراضياً
        etFiscalYear.setText(String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));

        btnCloseYear.setOnClickListener(view -> showConfirmationDialog());

        return v;
    }

    private void showConfirmationDialog() {
        String yearStr = etFiscalYear.getText().toString().trim();
        if (yearStr.isEmpty()) {
            Toast.makeText(requireContext(), "الرجاء إدخال السنة", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ تأكيد إقفال السنة " + yearStr)
            .setMessage("هل أنت متأكد؟ سيتم إنشاء قيد إقفال، وتصفير حسابات الإيرادات والمصروفات. لا يمكن التراجع.")
            .setPositiveButton("نعم، أقفل السنة", (dialog, which) -> executeClosing(Integer.parseInt(yearStr)))
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void executeClosing(int year) {
        btnCloseYear.setEnabled(false);
        pbLoading.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.closeFiscalYear(year);

                requireActivity().runOnUiThread(() -> {
                    btnCloseYear.setEnabled(true);
                    pbLoading.setVisibility(View.GONE);

                    if (response != null && response.isSuccess) {
                        String msg = response.data != null ? response.data.optString("message") : "تم الإقفال بنجاح";
                        double profit = response.data != null ? response.data.optDouble("net_profit", 0) : 0;
                        
                        new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("✅ نجاح")
                            .setMessage(msg + "\n\nصافي الربح المرحل: " + String.format("%,.2f", profit))
                            .setPositiveButton("حسناً", null)
                            .show();
                    } else {
                        String err = (response != null && response.error != null) ? response.error : "فشل الإقفال";
                        Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    btnCloseYear.setEnabled(true);
                    pbLoading.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "❌ حدث خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
