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
 * ✅ v3.0:
 * - onDestroyView يُعيد إظهار BottomNav
 * - notifySetupCompleted() عند الاكتمال
 * - زر "الانتقال للنظام" عند الاكتمال
 */
public class MiraAccountingSetupFragment extends Fragment {

    private static final String TAG = "MiraSetup";

    private TextView tvProgressPercent;
    private TextView tvProgressText;
    private ProgressBar pbProgress;
    private LinearLayout stepsContainer;
    private MaterialButton btnStart;
    private MaterialButton btnReset;
    private MaterialButton btnGoToSystem;
    private ProgressBar pbLoading;
    private View contentContainer;

    private MiraApiClient apiClient;
    private boolean isArabic;
    private JSONObject currentStatus;
    private boolean setupCompletedNotified = false;
    private boolean isManualOpen = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    // ⭐ اقرأ العلم
    if (getArguments() != null) {
        isManualOpen = getArguments().getBoolean("manual_open", false);
    }
    Log.d(TAG, "onViewCreated: isManualOpen=" + isManualOpen);

    initViews(view);
    setupListeners();
    // ⭐ احذف loadStatus() من هنا — onResume سيستدعيها بعد قليل
}

    @Override
public void onStart() {
    super.onStart();
    // 🔒 أخفِ الـ BottomNav في كل مرة تظهر الشاشة
    // (يشمل: فتح الإعداد لأول مرة، والعودة من خطوة فرعية)
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
    }
}

@Override
public void onResume() {
    super.onResume();
    Log.d(TAG, "onResume: reloading setup status");
    loadStatus();
}
@Override
public void onStop() {
    super.onStop();
    mainHandler.removeCallbacksAndMessages(null);
}


@Override
public void onDestroyView() {
    super.onDestroyView();
    
    // ⭐ أعِد إظهار BottomNav عند مغادرة الشاشة
    // (يضمن عدم بقائه مخفيًا عند الرجوع بعد فتح يدوي)
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
    }
    Log.d(TAG, "onDestroyView: restored BottomNav");
}
    private void initViews(View v) {
        tvProgressPercent = v.findViewById(R.id.tvProgressPercent);
        tvProgressText    = v.findViewById(R.id.tvProgressText);
        pbProgress        = v.findViewById(R.id.pbProgress);
        stepsContainer    = v.findViewById(R.id.stepsContainer);
        btnStart          = v.findViewById(R.id.btnStart);
        btnReset          = v.findViewById(R.id.btnReset);
        pbLoading         = v.findViewById(R.id.pbLoading);
        contentContainer  = v.findViewById(R.id.contentContainer);
        btnGoToSystem     = v.findViewById(R.id.btnGoToSystem);

        if (btnGoToSystem == null) {
            Log.d(TAG, "btnGoToSystem not found in layout");
        }
    }

    private void setupListeners() {
        if (btnStart != null) {
            btnStart.setOnClickListener(v -> startOrContinue());
        }
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> confirmReset());
        }
        if (btnGoToSystem != null) {
            btnGoToSystem.setOnClickListener(v -> navigateToSystem());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Load Status
    // ═══════════════════════════════════════════════════════

    private void loadStatus() {
        if (!isAdded() || pbLoading == null) return;

        showLoading(true);

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getWithAuth("/accounting/setup/status");
                mainHandler.post(() -> {
                    if (!isAdded()) return;

                    showLoading(false);

                    if (response != null && response.isSuccess && response.data != null) {
                        currentStatus = response.data;
                        renderStatus(currentStatus);
                    } else {
                        String err = (response != null && response.error != null)
                                ? response.error : "غير معروف";
                        showError(isArabic ? "فشل تحميل الحالة: " + err
                                           : "Failed to load status: " + err);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadStatus error", e);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showError(e.getMessage());
                });
            }
        }).start();
    }

    private void renderStatus(JSONObject response) {
        if (response == null) return;

        try {
            JSONObject status = response;
            JSONObject nested = response.optJSONObject("data");
            if (nested != null) {
                status = nested;
            }

            int progress = status.optInt("progress_percent", 0);
            String setupStatus = status.optString("status", "pending");

            pbProgress.setProgress(progress);
            tvProgressPercent.setText(progress + "%");

            String statusText;
            switch (setupStatus) {
                case "pending":     statusText = isArabic ? "⏳ بانتظار البدء" : "⏳ Pending"; break;
                case "in_progress": statusText = isArabic ? "🔄 جاري الإعداد" : "🔄 In Progress"; break;
                case "completed":   statusText = isArabic ? "✅ مكتمل" : "✅ Completed"; break;
                case "locked":      statusText = isArabic ? "🔒 مقفل" : "🔒 Locked"; break;
                default:            statusText = setupStatus;
            }
            tvProgressText.setText(statusText);

            boolean completed = "completed".equals(setupStatus)
        || "locked".equals(setupStatus);

// ⭐ عند الاكتمال + ليس فتحًا يدويًا + لم يُخطر بعد → انتقال فوري
if (completed && !setupCompletedNotified && !isManualOpen) {
    setupCompletedNotified = true;

    // ⭐ أخفِ المحتوى فورًا — لا يرى المستخدم شاشة الإعداد
    if (contentContainer != null) contentContainer.setVisibility(View.GONE);
    if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);

    Log.d(TAG, "🚀 Auto-redirect to Dashboard (setup completed)");
    notifySetupCompleted();
    return;   // ⭐ لا تُكمل رسم الواجهة
}

stepsContainer.removeAllViews();
JSONArray steps = status.optJSONArray("steps");

            if (steps != null) {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step != null) {
                        addStepCard(step, i + 1);
                    }
                }
            }

            if (completed) {
                btnStart.setText(isArabic ? "👁️ مراجعة الإعداد" : "👁️ Review Setup");
                btnStart.setEnabled(true);
                btnReset.setVisibility(View.VISIBLE);

                if (btnGoToSystem != null) {
                    btnGoToSystem.setVisibility(View.VISIBLE);
                    btnGoToSystem.setText(isArabic
                            ? "🚀 الانتقال إلى النظام"
                            : "🚀 Enter System");
                }
            } else {
                int completedCount = status.optInt("completed_count", 0);

                if (completedCount == 0) {
                    btnStart.setText(isArabic ? "🚀 بدء الإعداد" : "🚀 Start Setup");
                } else {
                    btnStart.setText(isArabic ? "▶️ المتابعة" : "▶️ Continue");
                }

                btnStart.setEnabled(true);
                btnReset.setVisibility(View.GONE);

                if (btnGoToSystem != null) {
                    btnGoToSystem.setVisibility(View.GONE);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "renderStatus error", e);
        }
    }

    private void addStepCard(JSONObject step, int stepNumber) {
        try {
            View card = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_setup_step, stepsContainer, false);

            TextView tvIcon         = card.findViewById(R.id.tvStepIcon);
            TextView tvNumber       = card.findViewById(R.id.tvStepNumber);
            TextView tvTitle        = card.findViewById(R.id.tvStepTitle);
            TextView tvDesc         = card.findViewById(R.id.tvStepDescription);
            TextView tvStatus       = card.findViewById(R.id.tvStepStatus);
            View statusIndicator    = card.findViewById(R.id.stepStatusIndicator);

            String icon       = step.optString("icon", "📌");
            String title      = isArabic
                    ? step.optString("title_ar", "")
                    : step.optString("title_en", "");
            String desc       = step.optString("description", "");
            boolean isCompleted = step.optBoolean("completed", false);
            boolean isRequired  = step.optBoolean("required", false);

            tvIcon.setText(icon);
            tvNumber.setText(String.valueOf(stepNumber));
            tvTitle.setText(title);
            tvDesc.setText(desc);

            if (isCompleted) {
                tvStatus.setText(isArabic ? "✅ مكتمل" : "✅ Completed");
                tvStatus.setTextColor(0xFF27AE60);
                statusIndicator.setBackgroundColor(0xFF27AE60);
            } else {
                if (isRequired) {
                    tvStatus.setText(isArabic ? "⏳ مطلوب" : "⏳ Required");
                    tvStatus.setTextColor(0xFFF39C12);
                    statusIndicator.setBackgroundColor(0xFFF39C12);
                } else {
                    tvStatus.setText(isArabic ? "⏳ اختياري" : "⏳ Optional");
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

    // ═══════════════════════════════════════════════════════
    //  Notify Completion
    // ═══════════════════════════════════════════════════════

    private void notifySetupCompleted() {
        if (!isAdded()) return;

        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            activity.onSetupCompleted();
            Log.d(TAG, "✅ Notified UHFMainActivity: setup completed");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════

    private void startOrContinue() {
        try {
            JSONObject status = currentStatus;
            if (status == null) {
                openStep(1);
                return;
            }
            JSONObject nested = status.optJSONObject("data");
            if (nested != null) status = nested;

            int currentStep    = status.optInt("current_step", 1);
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

        if (fragment == null) {
            Log.e(TAG, "openStep: unknown step number " + stepNumber);
            return;
        }

        Bundle args = new Bundle();
        args.putInt("setup_step", stepNumber);
        fragment.setArguments(args);

        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("SetupStep" + stepNumber)
                .commit();
    }

    private void navigateToSystem() {
        Log.d(TAG, "🚀 navigateToSystem called");

        if (!isAdded()) return;

        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            activity.setBottomNavVisible(true);
            activity.onSetupCompleted();
        }
    }

    private void confirmReset() {
        if (!isAdded()) return;

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "⚠️ إعادة تعيين الإعداد" : "⚠️ Reset Setup")
                .setMessage(isArabic
                        ? "سيتم حذف جميع الأرصدة الافتتاحية والقيد الافتتاحي.\n\nهل أنت متأكد؟"
                        : "All opening balances and journal entries will be deleted.\n\nAre you sure?")
                .setPositiveButton(isArabic ? "نعم، إعادة التعيين" : "Yes, Reset",
                        (d, w) -> performReset())
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void performReset() {
        showLoading(true);

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("reason", isArabic ? "طلب المدير" : "Manager request");

                MiraApiClient.ApiResponse response = apiClient.postWithAuth("/accounting/setup/reset", payload);

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(),
                                isArabic ? "✅ تم إعادة التعيين" : "✅ Reset complete",
                                Toast.LENGTH_LONG).show();
                        setupCompletedNotified = false;
                        loadStatus();
                    } else {
                        Toast.makeText(requireContext(),
                                isArabic
                                        ? "❌ فشل: " + (response != null ? response.error : "غير معروف")
                                        : "❌ Failed: " + (response != null ? response.error : "unknown"),
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "performReset error", e);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    Toast.makeText(requireContext(),
                            "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════

    private void showLoading(boolean show) {
        if (pbLoading == null || contentContainer == null) return;
        pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        contentContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void showError(String msg) {
        if (!isAdded() || getContext() == null) return;
        Toast.makeText(requireContext(), "❌ " + msg, Toast.LENGTH_LONG).show();
    }

    public void reload() {
        setupCompletedNotified = false;
        loadStatus();
    }
}
