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
import androidx.core.content.ContextCompat;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Step 10 — المراجعة والاعتماد النهائي (v2 — Theme-compliant)
 *
 * ✅ v2 إصلاحات الثيم:
 *  - استبدال AlertDialog.Builder بـ MaterialAlertDialogBuilder
 *  - استبدال الألوان hardcoded بـ ContextCompat.getColor() من colors.xml
 *  - ألوان لها variants (values-night) للـ Dark Mode
 *  - كل النصوص تستخدم attributes من الثيم
 */
public class SetupStep10ReviewFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep10";

    private TextView tvEntryDate, tvEntryDesc;
    private TextView tvTotalDebit, tvTotalCredit, tvTotalGold;
    private TextView tvBalanceStatus;
    private LinearLayout linesContainer, actionsContainer;
    private TextView tvEmpty;
    private MaterialButton btnBack, btnApprove;
    private View pbLoading;

    private MiraApiClient apiClient;
    private boolean isArabic;
    private boolean isBalanced = false;

    // ─── Colors (loaded per-theme) ───
    private int colorDebit;
    private int colorDebitBg;
    private int colorCredit;
    private int colorCreditBg;
    private int colorSuccess;
    private int colorWarning;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step10_review, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        // ✅ تحميل الألوان من الـ theme الحالي (Light أو Dark)
        loadThemeColors();

        initViews(view);
        setupListeners();
        loadPreview();
        return view;
    }

    /**
     * ✅ تحميل الألوان من colors.xml (تتبدل تلقائياً مع theme)
     */
    private void loadThemeColors() {
        colorDebit    = ContextCompat.getColor(requireContext(), R.color.acct_debit);
        colorDebitBg  = ContextCompat.getColor(requireContext(), R.color.acct_debit_bg);
        colorCredit   = ContextCompat.getColor(requireContext(), R.color.acct_credit);
        colorCreditBg = ContextCompat.getColor(requireContext(), R.color.acct_credit_bg);
        colorSuccess  = ContextCompat.getColor(requireContext(), R.color.acct_success);
        colorWarning  = ContextCompat.getColor(requireContext(), R.color.acct_warning);
    }

    private void initViews(View v) {
        tvEntryDate     = v.findViewById(R.id.tvEntryDate);
        tvEntryDesc     = v.findViewById(R.id.tvEntryDesc);
        tvTotalDebit    = v.findViewById(R.id.tvTotalDebit);
        tvTotalCredit   = v.findViewById(R.id.tvTotalCredit);
        tvTotalGold     = v.findViewById(R.id.tvTotalGold);
        tvBalanceStatus = v.findViewById(R.id.tvBalanceStatus);
        linesContainer  = v.findViewById(R.id.linesContainer);
        actionsContainer= v.findViewById(R.id.actionsContainer);
        tvEmpty         = v.findViewById(R.id.tvEmpty);
        btnBack         = v.findViewById(R.id.btnBack);
        btnApprove      = v.findViewById(R.id.btnApprove);
        pbLoading       = v.findViewById(R.id.pbLoading);

        // ✅ تطبيق الألوان الثابتة على header (theme-aware)
        if (tvTotalDebit  != null) tvTotalDebit.setTextColor(colorDebit);
        if (tvTotalCredit != null) tvTotalCredit.setTextColor(colorCredit);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnApprove.setOnClickListener(v -> confirmApprove());
    }

    private void loadPreview() {
        showLoading(true);
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/step/10/preview");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (resp == null || !resp.isSuccess || resp.data == null) {
                        showError((resp != null && resp.error != null) ? resp.error : "unknown");
                        return;
                    }

                    JSONObject payload = resp.data.optJSONObject("data");
                    if (payload == null) payload = resp.data;
                    renderPreview(payload);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadPreview", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showError(e.getMessage());
                });
            }
        }).start();
    }

    private void renderPreview(JSONObject p) {
        try {
            String date = p.optString("entry_date", "");
            String desc = p.optString("description", "");
            double totalDebit = p.optDouble("total_debit", 0);
            double totalCredit = p.optDouble("total_credit", 0);
            double totalGold = p.optDouble("total_gold_24k", 0);
            isBalanced = p.optBoolean("is_balanced", false);

            tvEntryDate.setText((isArabic ? "التاريخ: " : "Date: ") + date);
            tvEntryDesc.setText(desc);
            tvTotalDebit.setText(formatMoney(totalDebit));
            tvTotalCredit.setText(formatMoney(totalCredit));
            tvTotalGold.setText(String.format(Locale.US, "%.3f g", totalGold));

            // ✅ ألوان theme-aware
            if (isBalanced) {
                tvBalanceStatus.setText(isArabic ? "✅ القيد متوازن — جاهز للاعتماد"
                                                : "✅ Balanced — ready to approve");
                tvBalanceStatus.setTextColor(colorSuccess);
                btnApprove.setEnabled(true);
            } else {
                tvBalanceStatus.setText(isArabic ? "❌ القيد غير متوازن — راجع الخطوة 6"
                                                : "❌ Unbalanced — check step 6");
                tvBalanceStatus.setTextColor(colorCredit);
                btnApprove.setEnabled(false);
            }

            linesContainer.removeAllViews();
            JSONArray lines = p.optJSONArray("lines");

            if (lines == null || lines.length() == 0) {
                tvEmpty.setVisibility(View.VISIBLE);
                linesContainer.setVisibility(View.GONE);
            } else {
                tvEmpty.setVisibility(View.GONE);
                linesContainer.setVisibility(View.VISIBLE);
                LayoutInflater inf = LayoutInflater.from(requireContext());
                for (int i = 0; i < lines.length(); i++) {
                    JSONObject line = lines.optJSONObject(i);
                    if (line == null) continue;
                    addLineView(inf, line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "renderPreview", e);
        }
    }

    private void addLineView(LayoutInflater inf, JSONObject line) {
        try {
            View row = inf.inflate(R.layout.item_setup_review_line, linesContainer, false);

            TextView tvType    = row.findViewById(R.id.tvLineType);
            TextView tvAccount = row.findViewById(R.id.tvLineAccount);
            TextView tvDesc    = row.findViewById(R.id.tvLineDesc);
            TextView tvAmount  = row.findViewById(R.id.tvLineAmount);
            TextView tvGold    = row.findViewById(R.id.tvLineGold);

            String type    = line.optString("type", "debit");
            String code    = line.optString("account_code", "");
            String name    = line.optString("account_name", "");
            String desc    = line.optString("description", "");
            String owner   = line.optString("ownership", "owned");
            double money   = line.optDouble("money", 0);
            double gold    = line.optDouble("gold", 0);
            String karat   = line.optString("karat", "");

            // ✅ badge بألوان theme-aware
            if ("debit".equals(type)) {
                tvType.setText(isArabic ? "مدين" : "DR");
                tvType.setTextColor(colorDebit);
                tvType.setBackgroundColor(colorDebitBg);
            } else {
                tvType.setText(isArabic ? "دائن" : "CR");
                tvType.setTextColor(colorCredit);
                tvType.setBackgroundColor(colorCreditBg);
            }

            String accountLabel = code.isEmpty() ? name : code + " — " + name;
            if (!karat.isEmpty()) accountLabel += " (" + karat + ")";
            tvAccount.setText(accountLabel);

            String descLabel = desc;
            if ("trust".equals(owner)) {
                descLabel = (isArabic ? "🏛️ أمانة — " : "🏛️ Trust — ") + descLabel;
            } else if ("pledged".equals(owner)) {
                descLabel = (isArabic ? "🔒 مرهون — " : "🔒 Pledged — ") + descLabel;
            }
            if (descLabel.isEmpty()) {
                tvDesc.setVisibility(View.GONE);
            } else {
                tvDesc.setVisibility(View.VISIBLE);
                tvDesc.setText(descLabel);
            }

            tvAmount.setText(formatMoney(money) + " YER");

            if (gold > 0) {
                tvGold.setVisibility(View.VISIBLE);
                tvGold.setText(String.format(Locale.US, "%.3f g", gold));
            } else {
                tvGold.setVisibility(View.GONE);
            }

            linesContainer.addView(row);
        } catch (Exception e) {
            Log.e(TAG, "addLineView", e);
        }
    }

    private void confirmApprove() {
        if (!isBalanced) {
            Toast.makeText(requireContext(),
                    isArabic ? "القيد غير متوازن" : "Entry not balanced",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // ✅ MaterialAlertDialogBuilder بدل AlertDialog.Builder
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "⚠️ تأكيد الاعتماد النهائي" : "⚠️ Confirm Final Approval")
                .setMessage(isArabic
                        ? "سيتم إنشاء القيد الافتتاحي واعتماد النظام المحاسبي.\n\n"
                        + "⚠️ بعد الاعتماد:\n"
                        + "• لا يمكن تعديل الأرصدة الافتتاحية\n"
                        + "• يمكن فقط إعادة تعيين الإعداد بالكامل\n"
                        + "• سيبدأ تتبع جميع العمليات\n\n"
                        + "هل أنت متأكد؟"
                        : "Opening journal entry will be created and system activated.\n\n"
                        + "⚠️ After approval:\n"
                        + "• Opening balances cannot be edited\n"
                        + "• Only a full reset is possible\n"
                        + "• All transactions will be tracked\n\n"
                        + "Are you sure?")
                .setIcon(R.drawable.ic_refresh) // أو أي أيقونة تحذير
                .setPositiveButton(isArabic ? "نعم، اعتماد نهائي" : "Yes, Approve",
                        (d, w) -> performApprove())
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void performApprove() {
        btnApprove.setEnabled(false);
        btnApprove.setText(isArabic ? "جاري الاعتماد..." : "Approving...");
        showLoading(true);

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/step/10/approve", payload);

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (resp != null && resp.isSuccess) {
                        if (getActivity() instanceof UHFMainActivity) {
                            try {
                                ((UHFMainActivity) getActivity()).invalidateSetupCache();
                            } catch (Exception ignored) {}
                        }

                        String entryNumber = "";
                        if (resp.data != null) {
                            JSONObject p = resp.data.optJSONObject("data");
                            if (p == null) p = resp.data;
                            entryNumber = p.optString("entry_number", "");
                        }

                        showSuccessDialog(entryNumber);
                    } else {
                        btnApprove.setEnabled(true);
                        btnApprove.setText(isArabic ? "✅ اعتماد نهائي" : "✅ Approve");
                        String err = (resp != null && resp.error != null) ? resp.error : "unknown";
                        Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "performApprove", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    btnApprove.setEnabled(true);
                    btnApprove.setText(isArabic ? "✅ اعتماد نهائي" : "✅ Approve");
                    Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showSuccessDialog(String entryNumber) {
        String msg = isArabic
                ? "✅ تم اعتماد النظام المحاسبي بنجاح\n\n"
                + (entryNumber.isEmpty() ? "" : "رقم القيد: " + entryNumber + "\n\n")
                + "النظام الآن جاهز للاستخدام"
                : "✅ Accounting system approved successfully\n\n"
                + (entryNumber.isEmpty() ? "" : "Entry: " + entryNumber + "\n\n")
                + "System is now ready for use";

        // ✅ MaterialAlertDialogBuilder
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🎉 نجاح" : "🎉 Success")
                .setMessage(msg)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton(isArabic ? "فتح المحاسبة" : "Open Accounting",
                        (d, w) -> {
                            if (getActivity() instanceof UHFMainActivity) {
                                ((UHFMainActivity) getActivity()).openAccounting();
                            }
                        })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .setCancelable(false)
                .show();
    }

    private void showError(String err) {
        if (!isAdded()) return;
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(isArabic
                ? "❌ فشل تحميل المعاينة: " + err + "\n\nتأكد من إكمال الخطوات 1-9"
                : "❌ Failed to load preview: " + err + "\n\nEnsure steps 1-9 are complete");
        btnApprove.setEnabled(false);
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private String formatMoney(double v) {
        try {
            NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
            nf.setMinimumFractionDigits(2);
            nf.setMaximumFractionDigits(2);
            return nf.format(v);
        } catch (Exception e) { return String.valueOf(v); }
    }
}
