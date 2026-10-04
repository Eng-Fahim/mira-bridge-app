package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * MIRA Accounting Setup Fragment
 * 
 * الشاشة الرئيسية لإعداد النظام المحاسبي
 * - 6 مراحل
 * - تتبع التقدم
 * - البدء والاستمرار
 */
public class MiraAccountingSetupFragment extends Fragment {

    private static final String TAG = "MiraSetup";

    // Views
    private TextView tvProgressPercent;
    private TextView tvProgressText;
    private ProgressBar pbProgress;
    private LinearLayout stepsContainer;
    private MaterialButton btnStart;
    private MaterialButton btnReset;
    private ProgressBar pbLoading;
    private View contentContainer;

    // State
    private MiraApiClient apiClient;
    private boolean isArabic;
    private JSONObject currentStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_accounting_setup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        loadStatus();
    }

    private void initViews(View v) {
        tvProgressPercent = v.findViewById(R.id.tvProgressPercent);
        tvProgressText = v.findViewById(R.id.tvProgressText);
        pbProgress = v.findViewById(R.id.pbProgress);
        stepsContainer = v.findViewById(R.id.stepsContainer);
        btnStart = v.findViewById(R.id.btnStart);
        btnReset = v.findViewById(R.id.btnReset);
        pbLoading = v.findViewById(R.id.pbLoading);
        contentContainer = v.findViewById(R.id.contentContainer);
    }

    private void setupListeners() {
        btnStart.setOnClickListener(v -> startOrContinue());
        btnReset.setOnClickListener(v -> confirmReset());
    }

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle — إدارة BottomNav
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onStart() {
        super.onStart();
        // ✅ إخفاء BottomNav أثناء شاشة الإعداد
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // ✅ أعد تحميل الحالة عند العودة (مثلاً بعد إتمام خطوة فرعية)
        Log.d(TAG, "onResume: reloading setup status");
        loadStatus();
    }

    // (لا نستخدم onStop — الحارس المركزي في النشاط يعيد الإظهار عند الحاجة)

    // ═══════════════════════════════════════════════════════════
    //  Load Status
    // ═══════════════════════════════════════════════════════════

    private void loadStatus() {
        if (!isAdded() || pbLoading == null) return;

        showLoading(true);

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getAccounting("setup/status");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;

                    showLoading(false);

                    if (response != null && response.isSuccess && response.data != null) {
                        currentStatus = response.data;
                        renderStatus(currentStatus);
                    } else {
                        showError("فشل تحميل الحالة: "
                                + (response != null ? response.error : "غير معروف"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadStatus error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showError(e.getMessage());
                });
            }
        }).start();
    }

    private void renderStatus(JSONObject response) {
        try {
            // ✅ البنية: قد يكون الرد مباشر أو داخله "data"
            JSONObject status = response;
            JSONObject nested = response.optJSONObject("data");
            if (nested != null) {
                status = nested;
            }

            int progress = status.optInt("progress_percent", 0);
            String setupStatus = status.optString("status", "pending");

            // Progress
            pbProgress.setProgress(progress);
            tvProgressPercent.setText(progress + "%");

            String statusText;
            switch (setupStatus) {
                case "pending":     statusText = "⏳ بانتظار البدء"; break;
                case "in_progress": statusText = "🔄 جاري الإعداد"; break;
                case "completed":   statusText = "✅ مكتمل"; break;
                case "locked":      statusText = "🔒 مقفل"; break;
                default:            statusText = setupStatus;
            }
            tvProgressText.setText(statusText);

            // Steps
            stepsContainer.removeAllViews();
            JSONArray steps = status.optJSONArray("steps");

            if (steps != null) {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.getJSONObject(i);
                    addStepCard(step, i + 1);
                }
            }

            // Buttons
            boolean completed = "completed".equals(setupStatus)
                    || "locked".equals(setupStatus);

            if (completed) {
                btnStart.setText("👁️ مراجعة الإعداد");
                btnStart.setEnabled(true);
                btnReset.setVisibility(View.VISIBLE);
            } else {
                int completedCount = status.optInt("completed_count", 0);

                if (completedCount == 0) {
                    btnStart.setText("🚀 بدء الإعداد");
                } else {
                    btnStart.setText("▶️ المتابعة");
                }

                btnStart.setEnabled(true);
                btnReset.setVisibility(View.GONE);
            }

        } catch (Exception e) {
            Log.e(TAG, "renderStatus error", e);
        }
    }

    private void addStepCard(JSONObject step, int stepNumber) {
        try {
            View card = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_setup_step, stepsContainer, false);

            TextView tvIcon = card.findViewById(R.id.tvStepIcon);
            TextView tvNumber = card.findViewById(R.id.tvStepNumber);
            TextView tvTitle = card.findViewById(R.id.tvStepTitle);
            TextView tvDesc = card.findViewById(R.id.tvStepDescription);
            TextView tvStatus = card.findViewById(R.id.tvStepStatus);
            View statusIndicator = card.findViewById(R.id.stepStatusIndicator);

            String icon = step.optString("icon", "📌");
            String title = isArabic
                    ? step.optString("title_ar", "")
                    : step.optString("title_en", "");
            String desc = step.optString("description", "");
            boolean isCompleted = step.optBoolean("completed", false);
            boolean isRequired = step.optBoolean("required", false);

            tvIcon.setText(icon);
            tvNumber.setText(String.valueOf(stepNumber));
            tvTitle.setText(title);
            tvDesc.setText(desc);

            if (isCompleted) {
                tvStatus.setText("✅ مكتمل");
                tvStatus.setTextColor(0xFF27AE60);
                statusIndicator.setBackgroundColor(0xFF27AE60);
            } else {
                if (isRequired) {
                    tvStatus.setText("⏳ مطلوب");
                    tvStatus.setTextColor(0xFFF39C12);
                    statusIndicator.setBackgroundColor(0xFFF39C12);
                } else {
                    tvStatus.setText("⏳ اختياري");
                    tvStatus.setTextColor(0xFF95A5A6);
                    statusIndicator.setBackgroundColor(0xFF95A5A6);
                }
            }

            card.setOnClickListener(v -> openStep(stepNumber));

            stepsContainer.addView(card);

        } catch (Exception e) {
            Log.e(TAG, "addStepCard error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════

    private void startOrContinue() {
        try {
            JSONObject status = currentStatus;
            JSONObject nested = currentStatus.optJSONObject("data");
            if (nested != null) status = nested;

            int currentStep = status.optInt("current_step", 1);
            String setupStatus = status.optString("status", "pending");

            if ("completed".equals(setupStatus) || "locked".equals(setupStatus)) {
                openStep(6);
            } else {
                openStep(currentStep);
            }
        } catch (Exception e) {
            openStep(1);
        }
    }

    private void openStep(int stepNumber) {
        Fragment fragment = null;

        switch (stepNumber) {
            case 1: fragment = new SetupStep1FiscalYearFragment(); break;
            case 2: fragment = new SetupStep2BranchesFragment(); break;
            case 3: fragment = new SetupStep3CurrenciesFragment(); break;
            case 4: fragment = new SetupStep4OpeningBalancesFragment(); break;
            case 5: fragment = new SetupStep5UsersFragment(); break;
            case 6: fragment = new SetupStep6ReviewFragment(); break;
        }

        if (fragment == null) return;

        Bundle args = new Bundle();
        args.putInt("setup_step", stepNumber);
        fragment.setArguments(args);

        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("SetupStep" + stepNumber)
                .commit();
    }

    private void confirmReset() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("⚠️ إعادة تعيين الإعداد")
                .setMessage("سيتم حذف جميع الأرصدة الافتتاحية والقيد الافتتاحي.\n\n"
                        + "هل أنت متأكد؟")
                .setPositiveButton("نعم، إعادة التعيين", (d, w) -> performReset())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void performReset() {
        showLoading(true);

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("reason", "طلب المدير");

                MiraApiClient.ApiResponse response = apiClient.postAccounting("setup/reset", payload);

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(),
                                "✅ تم إعادة التعيين", Toast.LENGTH_LONG).show();
                        loadStatus();
                    } else {
                        Toast.makeText(requireContext(),
                                "❌ فشل: " + (response != null ? response.error : "غير معروف"),
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "performReset error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    Toast.makeText(requireContext(),
                            "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void showLoading(boolean show) {
        if (pbLoading == null || contentContainer == null) return;
        pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        contentContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void showError(String msg) {
        if (!isAdded()) return;
        Toast.makeText(requireContext(), "❌ " + msg, Toast.LENGTH_LONG).show();
    }

    // ✅ Method عام لإعادة التحميل (يُستدعى من الشاشات الفرعية)
    public void reload() {
        loadStatus();
    }
}
