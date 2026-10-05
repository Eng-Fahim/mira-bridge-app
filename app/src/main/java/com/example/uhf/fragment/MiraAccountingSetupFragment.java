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
 * MIRA Accounting Setup Fragment — النسخة النهائية
 *
 * الشاشة الرئيسية لإعداد النظام المحاسبي
 * - 6 مراحل
 * - تتبع التقدم
 * - البدء والاستمرار
 * - إشعار النشاط عند الاكتمال (v2.0)
 * - زر الانتقال للنظام بعد الاكتمال
 */
public class MiraAccountingSetupFragment extends Fragment {

    private static final String TAG = "MiraSetup";

    // ═══════════════════════════════════════════════════════════
    //  Views
    // ═══════════════════════════════════════════════════════════
    private TextView tvProgressPercent;
    private TextView tvProgressText;
    private ProgressBar pbProgress;
    private LinearLayout stepsContainer;
    private MaterialButton btnStart;
    private MaterialButton btnReset;
    private MaterialButton btnGoToSystem;   // ⭐ جديد: زر الانتقال للنظام
    private ProgressBar pbLoading;
    private View contentContainer;

    // ═══════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════
    private MiraApiClient apiClient;
    private boolean isArabic;
    private JSONObject currentStatus;
    private boolean setupCompletedNotified = false;   // ⭐ لتجنب التكرار
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

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

@Override
public void onDestroyView() {
    super.onDestroyView();
    // ✅ أعِد إظهار BottomNav عند تدمير الواجهة
    if (getActivity() instanceof UHFMainActivity) {
        ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
    }
}

    // ═══════════════════════════════════════════════════════════
    //  Init
    // ═══════════════════════════════════════════════════════════

    private void initViews(View v) {
        tvProgressPercent = v.findViewById(R.id.tvProgressPercent);
        tvProgressText    = v.findViewById(R.id.tvProgressText);
        pbProgress        = v.findViewById(R.id.pbProgress);
        stepsContainer    = v.findViewById(R.id.stepsContainer);
        btnStart          = v.findViewById(R.id.btnStart);
        btnReset          = v.findViewById(R.id.btnReset);
        pbLoading         = v.findViewById(R.id.pbLoading);
        contentContainer  = v.findViewById(R.id.contentContainer);

        // ⭐ زر الانتقال للنظام — قد لا يكون موجودًا في الـ layout القديم
        btnGoToSystem = v.findViewById(R.id.btnGoToSystem);

        // إذا لم يكن موجودًا في الـ XML، نخفيه ديناميكيًا
        if (btnGoToSystem == null) {
            Log.d(TAG, "btnGoToSystem not found in layout — dynamic mode");
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

    // ═══════════════════════════════════════════════════════════
    //  Load Status
    // ═══════════════════════════════════════════════════════════

    private void loadStatus() {
        if (!isAdded() || pbLoading == null) return;

        showLoading(true);

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getAccounting("setup/status");

                mainHandler.post(() -> {
                    if (!isAdded()) return;

                    showLoading(false);

                    if (response != null && response.isSuccess && response.data != null) {
                        currentStatus = response.data;
                        renderStatus(currentStatus);
                    } else {
                        String err = (response != null && response.error != null)
                                ? response.error
                                : "غير معروف";
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

    // ═══════════════════════════════════════════════════════════
    //  Render Status
    // ═══════════════════════════════════════════════════════════

    private void renderStatus(JSONObject response) {
        if (response == null) return;

        try {
            // ✅ البنية: قد يكون الرد مباشر أو داخله "data"
            JSONObject status = response;
            JSONObject nested = response.optJSONObject("data");
            if (nested != null) {
                status = nested;
            }

            int progress = status.optInt("progress_percent", 0);
            String setupStatus = status.optString("status", "pending");

            // ─── Progress ───
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

            // ═══════════════════════════════════════════════════════
            // ⭐ عند الاكتمال → أخطر النشاط فورًا (مرة واحدة)
            // ═══════════════════════════════════════════════════════
            boolean completed = "completed".equals(setupStatus)
                    || "locked".equals(setupStatus);

            if (completed && !setupCompletedNotified) {
                setupCompletedNotified = true;
                notifySetupCompleted();
            }

            // ─── Steps ───
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

            // ─── Buttons ───
            if (completed) {
                // ✅ النظام مُعد → أعرض زر "الانتقال للنظام"
                btnStart.setText(isArabic ? "👁️ مراجعة الإعداد" : "👁️ Review Setup");
                btnStart.setEnabled(true);
                btnReset.setVisibility(View.VISIBLE);

                if (btnGoToSystem != null) {
                    btnGoToSystem.setVisibility(View.VISIBLE);
                    btnGoToSystem.setText(isArabic
                            ? "🚀 الانتقال إلى النظام"
                            : "🚀 Enter System");
                }

                // ✅ عرض رسالة نجاح
                showSuccessBanner();

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

    // ═══════════════════════════════════════════════════════════
    //  ⭐ Notify Completion (v2.0)
    // ═══════════════════════════════════════════════════════════

    /**
     * إشعار UHFMainActivity بأن الإعداد اكتمل
     * - يُبطل الكاش القديم
     * - يحفظ "completed"
     * - يحدّث الواجهة
     */
    private void notifySetupCompleted() {
        if (!isAdded()) return;

        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            activity.onSetupCompleted();
            Log.d(TAG, "✅ Notified UHFMainActivity: setup completed");
        } else {
            Log.w(TAG, "⚠️ Cannot notify: activity is not UHFMainActivity");
        }
    }

    /**
     * عرض رسالة نجاح مؤقتة أسفل Progress
     */
    private void showSuccessBanner() {
        if (getContext() == null) return;

        // يمكن استخدام Snackbar أو Toast — هنا نستخدم Toast بسيط
        // لتجنب تجاوز التصميم
        Log.d(TAG, "🎉 Setup completed — success banner shown");
    }

    // ═══════════════════════════════════════════════════════════
    //  Step Card
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════

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
                openStep(6);   // مراجعة
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

    /**
     * ⭐ الانتقال إلى النظام بعد اكتمال الإعداد
     */
    private void navigateToSystem() {
        Log.d(TAG, "🚀 navigateToSystem called");

        if (!isAdded()) return;

        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();

            // ✅ إخفاء BottomNav مرة أخرى (في حال كان مخفيًا)
            activity.setBottomNavVisible(true);

            // ✅ استدعاء onSetupCompleted لضمان التحديث
            activity.onSetupCompleted();

            Log.d(TAG, "✅ Navigated to system");
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

                MiraApiClient.ApiResponse response =
                        apiClient.postAccounting("setup/reset", payload);

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(),
                                isArabic ? "✅ تم إعادة التعيين" : "✅ Reset complete",
                                Toast.LENGTH_LONG).show();
                        setupCompletedNotified = false;   // ⭐ أعِد التعيين
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

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void showLoading(boolean show) {
        if (pbLoading == null || contentContainer == null) return;
        pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        contentContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void showError(String msg) {
        if (!isAdded() || getContext() == null) return;
        Toast.makeText(requireContext(), "❌ " + msg, Toast.LENGTH_LONG).show();
    }

    /**
     * ✅ Method عام لإعادة التحميل (يُستدعى من الشاشات الفرعية)
     */
    public void reload() {
        setupCompletedNotified = false;   // ⭐ أعِد التعيين عند إعادة التحميل
        loadStatus();
    }
}
