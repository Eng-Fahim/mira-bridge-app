package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.MiraFiscalYearAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.MiraFiscalYearItem;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraFiscalYearsFragment extends Fragment implements MiraFiscalYearAdapter.OnItemClickListener {

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvFiscalYears;
    private TextView tvEmptyFiscalYears;
    private MaterialButton btnNewFiscalYear;
    private ProgressBar progressBar;

    private final List<MiraFiscalYearItem> yearList = new ArrayList<>();
    private MiraFiscalYearAdapter adapter;
    private boolean isArabic = true;

    private String selectedStartDate = "";
    private String selectedEndDate = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_fiscal_years, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        loadFiscalYears();

        return view;
    }

    private void initViews(View view) {
        rvFiscalYears = view.findViewById(R.id.rvFiscalYears);
        tvEmptyFiscalYears = view.findViewById(R.id.tvEmptyFiscalYears);
        btnNewFiscalYear = view.findViewById(R.id.btnNewFiscalYear);
        progressBar = view.findViewById(R.id.progressBar);

        rvFiscalYears.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new MiraFiscalYearAdapter(yearList, isArabic, this);
        rvFiscalYears.setAdapter(adapter);
    }

    private void setupListeners() {
        btnNewFiscalYear.setOnClickListener(v -> showCreateFiscalYearDialog());
    }

    private void loadFiscalYears() {
        showLoading(true);
        executor.execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getFiscalYears();
            
            mainHandler.post(() -> {
                showLoading(false);
                if (response != null && response.isSuccess && response.data != null) {
                    parseFiscalYears(response.data);
                } else {
                    showErrorState(isArabic ? "فشل تحميل البيانات" : "Failed to load fiscal years");
                }
            });
        });
    }

    private void parseFiscalYears(JSONObject data) {
        try {
            yearList.clear();
            JSONArray items = data.optJSONArray("data");
            
            if (items != null && items.length() > 0) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    MiraFiscalYearItem item = new MiraFiscalYearItem(
                            obj.optInt("id", i + 1),
                            obj.optString("year_name", ""),
                            obj.optString("start_date", ""),
                            obj.optString("end_date", ""),
                            obj.optInt("is_closed", 0) == 1,
                            obj.optDouble("closing_gold_24k", 0.0),
                            obj.optDouble("closing_cash", 0.0)
                    );
                    yearList.add(item);
                }
                
                adapter.notifyDataSetChanged();
                tvEmptyFiscalYears.setVisibility(View.GONE);
                rvFiscalYears.setVisibility(View.VISIBLE);
            } else {
                showErrorState(isArabic ? "لا توجد سنوات مالية مسجلة" : "No fiscal years available");
            }
        } catch (Exception e) {
            showErrorState(isArabic ? "خطأ في معالجة البيانات" : "Parsing Error");
        }
    }

    private void showCreateFiscalYearDialog() {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_create_fiscal_year_m3, null);
        TextInputEditText etYearName = dialogView.findViewById(R.id.etFiscalYearName);
        MaterialButton btnSelectDates = dialogView.findViewById(R.id.btnSelectDateRange);
        TextView tvSelectedDates = dialogView.findViewById(R.id.tvSelectedDates);

        btnSelectDates.setOnClickListener(v -> {
            MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder.dateRangePicker()
                    .setTitleText(isArabic ? "اختر فترة السنة المالية" : "Select Fiscal Year Range")
                    .build();

            picker.addOnPositiveButtonClickListener(selection -> {
                if (selection.first != null && selection.second != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    selectedStartDate = sdf.format(new Date(selection.first));
                    selectedEndDate = sdf.format(new Date(selection.second));
                    tvSelectedDates.setText(String.format("%s  ➔  %s", selectedStartDate, selectedEndDate));
                }
            });

            picker.show(getParentFragmentManager(), "FISCAL_DATE_PICKER");
        });

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "إنشاء سنة مالية جديدة" : "New Fiscal Year")
                .setView(dialogView)
                .setPositiveButton(isArabic ? "حفظ وتأسيس" : "Save", (dialog, which) -> {
                    String yearName = etYearName.getText() != null ? etYearName.getText().toString().trim() : "";
                    if (yearName.isEmpty() || selectedStartDate.isEmpty() || selectedEndDate.isEmpty()) {
                        Toast.makeText(getContext(), isArabic ? "يرجى استكمال جميع البيانات" : "All fields required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createFiscalYear(yearName, selectedStartDate, selectedEndDate);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void createFiscalYear(String name, String start, String end) {
        showLoading(true);
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("year_name", name);
                payload.put("start_date", start);
                payload.put("end_date", end);
                
                MiraApiClient.ApiResponse response = apiClient.createFiscalYear(payload);
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), isArabic ? "✅ تم إنشاء السنة المالية وترحيل الأرصدة" : "✅ Created", Toast.LENGTH_SHORT).show();
                        loadFiscalYears();
                    } else {
                        showLoading(false);
                        Toast.makeText(getContext(), isArabic ? "❌ فشل الإنشاء" : "❌ Failed", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> showLoading(false));
            }
        });
    }

    @Override
    public void onItemLongClick(MiraFiscalYearItem item) {
        if (item.isClosed()) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🔒 إغلاق وتدوير السنة المالية" : "🔒 Close Fiscal Year")
                .setMessage(isArabic ? 
                        "هل أنت متأكد من إغلاق سنة (" + item.getYearName() + ")؟\n\nسيتم قفل القيود وتدوير أوزان الذهب والنقدية للسنة الجديدة تلقائياً ولا يمكن التراجع." :
                        "Are you sure you want to close " + item.getYearName() + "?\nGold weights and balances will be rolled over.")
                .setPositiveButton(isArabic ? "إغلاق وتدوير" : "Close Year", (dialog, which) -> closeFiscalYear(item.getId()))
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void closeFiscalYear(int yearId) {
        showLoading(true);
        executor.execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.closeFiscalYear(yearId);
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), isArabic ? "✅ تم إغلاق السنة وتدوير الجرامات بنجاح" : "✅ Closed & Rolled Over", Toast.LENGTH_SHORT).show();
                    loadFiscalYears();
                } else {
                    showLoading(false);
                    Toast.makeText(getContext(), isArabic ? "❌ فشل الإغلاق" : "❌ Failed", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void showLoading(boolean isLoading) {
        progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        if (isLoading) {
            rvFiscalYears.setVisibility(View.GONE);
            tvEmptyFiscalYears.setVisibility(View.GONE);
        }
    }

    private void showErrorState(String message) {
        showLoading(false);
        tvEmptyFiscalYears.setVisibility(View.VISIBLE);
        tvEmptyFiscalYears.setText(message);
        rvFiscalYears.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
