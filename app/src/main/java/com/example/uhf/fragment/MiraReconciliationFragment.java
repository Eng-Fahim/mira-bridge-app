package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class MiraReconciliationFragment extends Fragment {

    private MaterialAutoCompleteTextView spinnerBank;
    private TextInputEditText etDate, etStatementBalance;
    private TextView tvBookBalance, tvDifference;
    private RecyclerView rvPendingCheques;
    private MaterialButton btnSave;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // أمثلة للبنوك (يُفضل جلبها من API)
    private static final String[] BANK_NAMES = {"بنك التضامن (112001)", "البنك الأهلي (112003)", "بنك الكريمي (112004)"};
    private static final int[] BANK_IDS = {25, 27, 28};

    private double currentBookBalance = 0.0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_reconciliation, container, false);

        spinnerBank = v.findViewById(R.id.spinnerBank);
        etDate = v.findViewById(R.id.etDate);
        etStatementBalance = v.findViewById(R.id.etStatementBalance);
        tvBookBalance = v.findViewById(R.id.tvBookBalance);
        tvDifference = v.findViewById(R.id.tvDifference);
        rvPendingCheques = v.findViewById(R.id.rvPendingCheques);
        btnSave = v.findViewById(R.id.btnSave);

        rvPendingCheques.setLayoutManager(new LinearLayoutManager(getContext()));

        // إعداد القائمة المنسدلة للبنوك
        spinnerBank.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, BANK_NAMES));
        spinnerBank.setText(BANK_NAMES[0], false);
        spinnerBank.setOnItemClickListener((parent, view, position, id) -> loadData(BANK_IDS[position]));

        // تاريخ افتراضي
        etDate.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime()));
        etDate.setOnClickListener(view -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(requireContext(), (dp, y, m, d) -> {
                etDate.setText(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d));
                loadData(BANK_IDS[spinnerBank.getListSelection()]);
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });

        // حساب الفرق لحظياً
        etStatementBalance.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    double statement = Double.parseDouble(s.toString());
                    double diff = statement - currentBookBalance;
                    tvDifference.setText(String.format(Locale.US, "الفرق: %,.2f", diff));
                    tvDifference.setTextColor(diff == 0 ? 0xFF27AE60 : 0xFFE74C3C);
                } catch (Exception e) {
                    tvDifference.setText("الفرق: 0.00");
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        btnSave.setOnClickListener(v1 -> saveReconciliation());
        loadData(BANK_IDS[0]);

        return v;
    }

    private void loadData(int bankId) {
        String date = etDate.getText().toString();
        new Thread(() -> {
            MiraApiClient.ApiResponse res = MiraApiClient.getInstance(requireContext()).getReconciliationSummary(bankId, date);
            mainHandler.post(() -> {
                if (res != null && res.isSuccess && res.data != null) {
                    currentBookBalance = res.data.optDouble("book_balance", 0);
                    tvBookBalance.setText(String.format(Locale.US, "%,.2f YER", currentBookBalance));

                    JSONObject data = res.data.optJSONObject("data");
                    if (data != null) {
                        JSONArray cheques = data.optJSONArray("pending_cheques");
                        if (cheques != null && cheques.length() > 0) {
                            // عرض الشيكات (يمكنك تخصيص Adapter لاحقاً)
                        }
                    }
                }
            });
        }).start();
    }

    private void saveReconciliation() {
        String date = etDate.getText().toString();
        int bankId = BANK_IDS[spinnerBank.getListSelection()];
        double statementBalance = 0;
        try { statementBalance = Double.parseDouble(etStatementBalance.getText().toString()); } catch (Exception ignored) {}

        try {
            JSONObject payload = new JSONObject();
            payload.put("bank_account_id", bankId);
            payload.put("statement_balance", statementBalance);
            payload.put("book_balance", currentBookBalance);
            payload.put("date", date);

            new Thread(() -> {
                MiraApiClient.ApiResponse res = MiraApiClient.getInstance(requireContext()).saveReconciliation(payload);
                mainHandler.post(() -> {
                    if (res != null && res.isSuccess) {
                        Toast.makeText(getContext(), "✅ تم حفظ التسوية", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "❌ فشل الحفظ", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        } catch (Exception ignored) {}
    }
}
