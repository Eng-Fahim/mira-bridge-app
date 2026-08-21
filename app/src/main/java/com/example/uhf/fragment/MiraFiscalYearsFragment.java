package com.example.uhf.fragment;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
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
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraFiscalYearsFragment extends Fragment {

    private static final String TAG = "MiraFiscalYearsFragment";

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvFiscalYears;
    private TextView tvEmptyFiscalYears;
    private MaterialButton btnNewFiscalYear;

    private final List<FiscalYearItem> yearList = new ArrayList<>();
    private FiscalYearAdapter adapter;
    private boolean isArabic = true;

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
        
        rvFiscalYears.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new FiscalYearAdapter(yearList);
        rvFiscalYears.setAdapter(adapter);
    }

    private void setupListeners() {
        btnNewFiscalYear.setOnClickListener(v -> showCreateFiscalYearDialog());
    }

    private void loadFiscalYears() {
        executor.execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getFiscalYears();
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    parseFiscalYears(response.data);
                } else {
                    tvEmptyFiscalYears.setVisibility(View.VISIBLE);
                    rvFiscalYears.setVisibility(View.GONE);
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
                    FiscalYearItem item = new FiscalYearItem();
                    item.id = obj.optInt("id", i + 1);
                    item.yearName = obj.optString("year_name", "");
                    item.startDate = obj.optString("start_date", "");
                    item.endDate = obj.optString("end_date", "");
                    item.isClosed = obj.optInt("is_closed", 0) == 1;
                    yearList.add(item);
                }
                
                adapter.notifyDataSetChanged();
                tvEmptyFiscalYears.setVisibility(View.GONE);
                rvFiscalYears.setVisibility(View.VISIBLE);
            } else {
                tvEmptyFiscalYears.setVisibility(View.VISIBLE);
                rvFiscalYears.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            e.printStackTrace();
            tvEmptyFiscalYears.setVisibility(View.VISIBLE);
            rvFiscalYears.setVisibility(View.GONE);
        }
    }

    private void showCreateFiscalYearDialog() {
        if (getContext() == null) return;
        
        Dialog dialog = new Dialog(getContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_create_fiscal_year);
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        
        EditText etYearName = dialog.findViewById(R.id.etFiscalYearName);
        EditText etStartDate = dialog.findViewById(R.id.etFiscalStartDate);
        EditText etEndDate = dialog.findViewById(R.id.etFiscalEndDate);
        MaterialButton btnCancel = dialog.findViewById(R.id.btnFiscalCancel);
        MaterialButton btnSave = dialog.findViewById(R.id.btnFiscalSave);
        
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        btnSave.setOnClickListener(v -> {
            String yearName = etYearName.getText().toString().trim();
            String startDate = etStartDate.getText().toString().trim();
            String endDate = etEndDate.getText().toString().trim();
            
            if (yearName.isEmpty() || startDate.isEmpty() || endDate.isEmpty()) {
                Toast.makeText(getContext(), 
                    isArabic ? "جميع الحقول مطلوبة" : "All fields required", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            
            createFiscalYear(yearName, startDate, endDate);
            dialog.dismiss();
        });
        
        dialog.show();
    }

    private void createFiscalYear(String name, String start, String end) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("year_name", name);
                payload.put("start_date", start);
                payload.put("end_date", end);
                
                MiraApiClient.ApiResponse response = apiClient.createFiscalYear(payload);
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), 
                            isArabic ? "✅ تم إنشاء السنة المالية" : "✅ Fiscal year created", 
                            Toast.LENGTH_SHORT).show();
                        loadFiscalYears();
                    } else {
                        Toast.makeText(getContext(), 
                            isArabic ? "❌ فشل الإنشاء" : "❌ Failed", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // ==================== MODEL ====================
    private static class FiscalYearItem {
        int id;
        String yearName;
        String startDate;
        String endDate;
        boolean isClosed;
    }

    // ==================== ADAPTER ====================
    private class FiscalYearAdapter extends RecyclerView.Adapter<FiscalYearAdapter.ViewHolder> {
        private List<FiscalYearItem> items;
        
        FiscalYearAdapter(List<FiscalYearItem> items) { this.items = items; }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_fiscal_year, parent, false);
            return new ViewHolder(view);
        }
        
        
// تعديل Adapter لإضافة الضغط المطول للإغلاق:
@Override
public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    FiscalYearItem item = items.get(position);
    holder.tvYearName.setText(item.yearName);
    holder.tvDates.setText(item.startDate + " → " + item.endDate);
    
    if (item.isClosed) {
        holder.tvStatus.setText(isArabic ? "🔒 مغلقة" : "🔒 Closed");
        holder.tvStatus.setTextColor(Color.parseColor("#E74C3C"));
    } else {
        holder.tvStatus.setText(isArabic ? "✅ مفتوحة" : "✅ Open");
        holder.tvStatus.setTextColor(Color.parseColor("#2ECC71"));
    }
    
    // ✅ الضغط المطول للإغلاق
    holder.itemView.setOnLongClickListener(v -> {
        showCloseYearDialog(item);
        return true;
    });
}
        
        @Override
        public int getItemCount() { return items.size(); }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvYearName, tvDates, tvStatus;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvYearName = itemView.findViewById(R.id.tvFiscalYearName);
                tvDates = itemView.findViewById(R.id.tvFiscalDates);
                tvStatus = itemView.findViewById(R.id.tvFiscalStatus);
            }
        }
    }
    
    // في MiraFiscalYearsFragment.java - أضف دالة الإغلاق:

private void showCloseYearDialog(FiscalYearItem year) {
    if (getContext() == null || year.isClosed) return;
    
    new androidx.appcompat.app.AlertDialog.Builder(getContext())
        .setTitle(isArabic ? "إغلاق سنة مالية" : "Close Fiscal Year")
        .setMessage(isArabic ? 
            "هل أنت متأكد من إغلاق سنة " + year.yearName + "؟\n\nهذا الإجراء لا يمكن التراجع عنه." :
            "Are you sure you want to close year " + year.yearName + "?\n\nThis action cannot be undone.")
        .setPositiveButton(isArabic ? "إغلاق" : "Close", (dialog, which) -> {
            closeFiscalYear(year.id);
        })
        .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
        .show();
}

private void closeFiscalYear(int yearId) {
    executor.execute(() -> {
        MiraApiClient.ApiResponse response = apiClient.closeFiscalYear(yearId);
        
        mainHandler.post(() -> {
            if (response != null && response.isSuccess) {
                Toast.makeText(getContext(), 
                    isArabic ? "✅ تم إغلاق السنة المالية" : "✅ Year closed", 
                    Toast.LENGTH_SHORT).show();
                loadFiscalYears();
            } else {
                Toast.makeText(getContext(), 
                    isArabic ? "❌ فشل الإغلاق" : "❌ Failed", 
                    Toast.LENGTH_SHORT).show();
            }
        });
    });
}
}
