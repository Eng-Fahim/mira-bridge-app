package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * المرحلة 6: المراجعة والاعتماد النهائي
 */
public class SetupStep6ReviewFragment extends BaseSetupStepFragment {
    private static final String TAG = "SetupStep6";

    private TextView tvEntryDate;
    private TextView tvEntryDesc;
    private TextView tvTotalDebit;
    private TextView tvTotalCredit;
    private TextView tvTotalGold;
    private TextView tvBalanceStatus;
    private LinearLayout linesContainer;
    private MaterialButton btnBack;
    private MaterialButton btnApprove;
    private View pbLoading;

    private MiraApiClient apiClient;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step6_review, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        initViews(view);
        setupListeners();
        loadPreview();
        
        return view;
    }

    private void initViews(View v) {
        tvEntryDate = v.findViewById(R.id.tvEntryDate);
        tvEntryDesc = v.findViewById(R.id.tvEntryDesc);
        tvTotalDebit = v.findViewById(R.id.tvTotalDebit);
        tvTotalCredit = v.findViewById(R.id.tvTotalCredit);
        tvTotalGold = v.findViewById(R.id.tvTotalGold);
        tvBalanceStatus = v.findViewById(R.id.tvBalanceStatus);
        linesContainer = v.findViewById(R.id.linesContainer);
        btnBack = v.findViewById(R.id.btnBack);
        btnApprove = v.findViewById(R.id.btnApprove);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnApprove.setOnClickListener(v -> confirmApprove());
    }

    private void loadPreview() {
        showLoading(true);
        
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = 
                    apiClient.getAccounting("setup/step/6/preview");
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    showLoading(false);
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        renderPreview(response.data);
                    } else {
                        Toast.makeText(requireContext(), 
                            "❌ فشل تحميل المعاينة: " + 
                            (response != null ? response.error : "غير معروف"),
                            Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadPreview error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    showLoading(false);
                    Toast.makeText(requireContext(), 
                        "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void renderPreview(JSONObject data) {
        try {
            JSONObject preview = data.optJSONObject("data");
            if (preview == null) preview = data;
            
            String entryDate = preview.optString("entry_date", "");
            String description = preview.optString("description", "");
            double totalDebit = preview.optDouble("total_debit", 0);
            double totalCredit = preview.optDouble("total_credit", 0);
            double totalGold = preview.optDouble("total_gold_24k", 0);
            boolean isBalanced = preview.optBoolean("is_balanced", false);
            
            tvEntryDate.setText("التاريخ: " + entryDate);
            tvEntryDesc.setText(description);
            tvTotalDebit.setText(String.format(Locale.US, "%,.2f", totalDebit));
            tvTotalCredit.setText(String.format(Locale.US, "%,.2f", totalCredit));
            tvTotalGold.setText(String.format(Locale.US, "%.3f g", totalGold));
            
            if (isBalanced) {
                tvBalanceStatus.setText("✅ القيد متوازن — جاهز للاعتماد");
                tvBalanceStatus.setTextColor(0xFF27AE60);
            } else {
                tvBalanceStatus.setText("❌ القيد غير متوازن — تحقق من الأرصدة");
                tvBalanceStatus.setTextColor(0xFFE74C3C);
                btnApprove.setEnabled(false);
            }
            
            // السطور
            linesContainer.removeAllViews();
            JSONArray lines = preview.optJSONArray("lines");
            
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    JSONObject line = lines.getJSONObject(i);
                    addLineView(line);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "renderPreview error", e);
        }
    }

    private void addLineView(JSONObject line) {
        try {
            View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_review_line, linesContainer, false);
            
            TextView tvType = view.findViewById(R.id.tvLineType);
            TextView tvAccount = view.findViewById(R.id.tvLineAccount);
            TextView tvAmount = view.findViewById(R.id.tvLineAmount);
            TextView tvGold = view.findViewById(R.id.tvLineGold);
            
            String type = line.optString("type", "debit");
            String accountCode = line.optString("account_code", "");
            String accountName = line.optString("account_name", "");
            double money = line.optDouble("money", 0);
            double gold = line.optDouble("gold", 0);
            
            if ("debit".equals(type)) {
                tvType.setText("مدين");
                tvType.setTextColor(0xFF3498DB);
            } else {
                tvType.setText("دائن");
                tvType.setTextColor(0xFFE74C3C);
            }
            
            tvAccount.setText(accountCode + " — " + accountName);
            tvAmount.setText(String.format(Locale.US, "%,.2f YER", money));
            
            if (gold > 0) {
                tvGold.setVisibility(View.VISIBLE);
                tvGold.setText(String.format(Locale.US, "%.3f g", gold));
            } else {
                tvGold.setVisibility(View.GONE);
            }
            
            linesContainer.addView(view);
            
        } catch (Exception e) {
            Log.e(TAG, "addLineView error", e);
        }
    }

    private void confirmApprove() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("⚠️ تأكيد الاعتماد النهائي")
            .setMessage("سيتم إنشاء القيد الافتتاحي وقفل النظام المحاسبي.\n\n" +
                       "⚠️ لن يمكن التراجع إلا بموافقة مدير النظام.\n\n" +
                       "هل أنت متأكد؟")
            .setPositiveButton("نعم، اعتماد نهائي", (d, w) -> performApprove())
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void performApprove() {
    btnApprove.setEnabled(false);
    btnApprove.setText("جاري الاعتماد...");
    showLoading(true);
    
    new Thread(() -> {
        try {
            JSONObject payload = new JSONObject();
            
            MiraApiClient.ApiResponse response = 
                apiClient.postAccounting("setup/step/6/approve", payload);
            
            new Handler(Looper.getMainLooper()).post(() -> {
                showLoading(false);
                
                if (response != null && response.isSuccess) {
                    String entryNumber = response.optString("entry_number", "");
                    
                    // ⬇️⬇️⬇️ الإضافة المطلوبة — سطر واحد فقط ⬇️⬇️⬇️
                    // ✅ إبطال كاش حالة الإعداد — ليُعيد التوجيه في المرة القادمة
                    if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
                        ((com.example.uhf.activity.UHFMainActivity) getActivity())
                            .invalidateSetupCache();
                    }
                    // ⬆️⬆️⬆️ نهاية الإضافة ⬆️⬆️⬆️
                    
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("✅ تم الاعتماد بنجاح")
                        .setMessage("تم إنشاء القيد الافتتاحي:\n" + entryNumber +
                                   "\n\nالنظام المحاسبي جاهز للاستخدام.")
                        .setPositiveButton("فتح المحاسبة", (d, w) -> {
                            if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
                                ((com.example.uhf.activity.UHFMainActivity) getActivity())
                                    .openAccounting();
                            }
                        })
                        .setNegativeButton("إغلاق", null)
                        .setCancelable(false)
                        .show();
                } else {
                    btnApprove.setEnabled(true);
                    btnApprove.setText("✅ اعتماد نهائي");
                    
                    String error = response != null ? response.error : "غير معروف";
                    Toast.makeText(requireContext(), 
                        "❌ فشل: " + error, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "performApprove error", e);
            new Handler(Looper.getMainLooper()).post(() -> {
                showLoading(false);
                btnApprove.setEnabled(true);
                btnApprove.setText("✅ اعتماد نهائي");
                Toast.makeText(requireContext(), 
                    "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }).start();
}

    private void showLoading(boolean show) {
        pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
