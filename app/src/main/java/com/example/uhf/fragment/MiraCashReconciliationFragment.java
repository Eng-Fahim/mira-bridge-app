package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraCashReconciliationFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private TextView tvDate, tvOpening, tvSales, tvScrap, tvExpected, tvDifference;
    private TextInputEditText etActualCash;
    private MaterialCardView cardDifference;
    private MaterialButton btnCloseCashier;
    
    private double expectedClosing = 0;
    private boolean isClosed = false;
    private String currentDate;
    
    // ✅ العملة الافتراضية (يمكنك جلبها من الإعدادات لاحقاً)
    private final String CURRENCY = "YER"; 

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_cash_reconciliation, container, false);
        
        initViews(view);
        currentDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        tvDate.setText(currentDate);
        
        setupListeners();
        loadSummary();
        return view;
    }

    private void initViews(View v) {
        swipeRefresh    = v.findViewById(R.id.swipeRefresh);
        tvDate          = v.findViewById(R.id.tvDate);
        tvOpening       = v.findViewById(R.id.tvOpening);
        tvSales         = v.findViewById(R.id.tvSales);
        tvScrap         = v.findViewById(R.id.tvScrap);
        tvExpected      = v.findViewById(R.id.tvExpected);
        tvDifference    = v.findViewById(R.id.tvDifference);
        etActualCash    = v.findViewById(R.id.etActualCash);
        cardDifference  = v.findViewById(R.id.cardDifference);
        btnCloseCashier = v.findViewById(R.id.btnCloseCashier);
    }

    private void setupListeners() {
        swipeRefresh.setOnRefreshListener(this::loadSummary);
        
        etActualCash.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                calculateDifference();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        
        btnCloseCashier.setOnClickListener(v -> submitClosure());
    }

    // ✅ دالة مساعدة لتنسيق المبالغ بشكل موحد
    private String formatMoney(double amount) {
        String formatted;
        if (amount < 0) {
            formatted = "-" + String.format(Locale.US, "%,.2f", Math.abs(amount));
        } else {
            formatted = String.format(Locale.US, "%,.2f", amount);
        }
        return formatted + " " + CURRENCY;
    }

    private void calculateDifference() {
        if (isClosed) return;
        
        double actual = 0;
        try {
            String input = etActualCash.getText() != null ? etActualCash.getText().toString().trim() : "";
            if (!input.isEmpty() && !input.equals(".")) {
                actual = Double.parseDouble(input);
            }
        } catch (NumberFormatException e) {
            // ✅ تجاهل الأخطاء أثناء الكتابة (مثل كتابة نقطة فقط)
        }

        double diff = actual - expectedClosing;

        tvDifference.setText(formatMoney(diff));
        
        int color, bgColor;
        if (Math.abs(diff) < 0.01) { 
            color = 0xFF16A34A; // أخضر
            bgColor = 0xFFF0FDF4;
        } else if (diff < 0) { 
            color = 0xFFDC2626; // أحمر
            bgColor = 0xFFFEF2F2;
        } else { 
            color = 0xFFF59E0B; // برتقالي
            bgColor = 0xFFFFFBEB;
        }
        
        tvDifference.setTextColor(color);
        cardDifference.setStrokeColor(android.content.res.ColorStateList.valueOf(color));
        cardDifference.setCardBackgroundColor(bgColor);
    }

    private void loadSummary() {
        swipeRefresh.setRefreshing(true);
        int branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();
        
        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.getCashReconciliationSummary(branchId, currentDate);
            
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                swipeRefresh.setRefreshing(false);
                if (response.isSuccess && response.data != null) {
                    try {
                        boolean closed = response.data.optBoolean("is_closed", false);
                        JSONObject data = response.data.optJSONObject("data");
                        
                        if (data != null) {
                            expectedClosing = data.optDouble("expected_closing", 0);
                            
                            // ✅ استخدام دالة التنسيق بدلاً من $
                            tvOpening.setText(formatMoney(data.optDouble("opening_balance", 0)));
                            tvSales.setText("+ " + formatMoney(data.optDouble("total_sales_cash", 0)));
                            tvScrap.setText("- " + formatMoney(data.optDouble("total_scrap_cash", 0)));
                            tvExpected.setText(formatMoney(expectedClosing));
                            
                            if (closed) {
                                isClosed = true;
                                double actual = data.optDouble("actual_closing", 0);
                                double diff = data.optDouble("difference", 0);
                                
                                etActualCash.setText(String.format(Locale.US, "%.2f", actual));
                                etActualCash.setEnabled(false);
                                btnCloseCashier.setEnabled(false);
                                btnCloseCashier.setText("✅ تم تقفيل هذا اليوم مسبقاً");
                                
                                tvDifference.setText(formatMoney(diff));
                                tvDifference.setTextColor(diff >= 0 ? 0xFF16A34A : 0xFFDC2626);
                            }
                        }
                    } catch (Exception e) { 
                        e.printStackTrace(); 
                    }
                } else {
                    Toast.makeText(requireContext(), "فشل تحميل بيانات الخزينة", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void submitClosure() {
        String input = etActualCash.getText() != null ? etActualCash.getText().toString().trim() : "";
        if (input.isEmpty() || input.equals(".")) {
            Toast.makeText(requireContext(), "الرجاء إدخال النقدية الفعلية", Toast.LENGTH_SHORT).show();
            return;
        }

        double actual;
        try {
            actual = Double.parseDouble(input);
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "الرجاء إدخال رقم صحيح", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("تأكيد التقفيل")
            .setMessage("هل أنت متأكد من إغلاق خزينة اليوم؟\nلا يمكن التعديل بعد التأكيد.")
            .setPositiveButton("تأكيد", (d, w) -> {
                btnCloseCashier.setEnabled(false);
                btnCloseCashier.setText("جاري الحفظ...");
                
                int branchId = MiraBranchesManager.getInstance(requireContext()).getActiveBranchId();
                
                new Thread(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("branch_id", branchId);
                        payload.put("date", currentDate);
                        payload.put("actual_closing", actual);
                        
                        MiraApiClient api = MiraApiClient.getInstance(requireContext());
                        MiraApiClient.ApiResponse response = api.closeCashier(payload);
                        
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(() -> {
                            if (response.isSuccess) {
                                Toast.makeText(requireContext(), "✅ تم تقفيل الخزينة بنجاح", Toast.LENGTH_LONG).show();
                                loadSummary();
                            } else {
                                btnCloseCashier.setEnabled(true);
                                btnCloseCashier.setText("🔒 إتمام تقفيل الخزينة");
                                Toast.makeText(requireContext(), "فشل: " + response.error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) { 
                        e.printStackTrace();
                        getActivity().runOnUiThread(() -> {
                            btnCloseCashier.setEnabled(true);
                            btnCloseCashier.setText("🔒 إتمام تقفيل الخزينة");
                        });
                    }
                }).start();
            })
            .setNegativeButton("إلغاء", null)
            .show();
    }
}
