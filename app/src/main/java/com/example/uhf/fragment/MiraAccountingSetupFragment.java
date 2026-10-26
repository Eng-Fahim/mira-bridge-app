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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * MIRA Accounting Setup — v3.3 (Final)
 *
 * المزايا:
 *  ✅ 10 خطوات في 5 مجموعات منطقية
 *  ✅ Routing كامل لجميع الشاشات
 *  ✅ BottomNav management (auto hide/show)
 *  ✅ Auto-redirect عند الاكتمال
 *  ✅ Reset مع cache invalidation
 *  ✅ Retry عند فشل API
 *  ✅ Rotation-safe (يحفظ الحالة)
 *  ✅ Arabic + English
 *  ✅ تقدم كل مجموعة على حدة
 */
public class MiraAccountingSetupFragment extends BaseSetupStepFragment {

    private static final String TAG = "MiraSetup";
    private static final String KEY_NOTIFIED = "setup_completed_notified";
    private static final String KEY_STATUS_JSON = "cached_status_json";

    // ─── Views ───
    private TextView tvProgressPercent, tvProgressText, tvGroupProgress;
    private ProgressBar pbProgress, pbLoading;
    private LinearLayout stepsContainer;
    private View contentContainer;
    private MaterialButton btnStart, btnReset, btnGoToSystem;

    // ─── State ───
    private MiraApiClient apiClient;
    private boolean isArabic;
    private JSONObject currentStatus;
    private boolean setupCompletedNotified = false;
    private boolean isManualOpen = false;
    private boolean isRetryMode = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRecoveryRunnable;

    // ═══════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            setupCompletedNotified = savedInstanceState.getBoolean(KEY_NOTIFIED, false);
            String cached = savedInstanceState.getString(KEY_STATUS_JSON, null);
            if (cached != null) {
                try { currentStatus = new JSONObject(cached); } catch (Exception ignored) {}
            }
        }
    }

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

        if (getArguments() != null) {
            isManualOpen = getArguments().getBoolean("manual_open", false);
        }

        initViews(view);
        setupListeners();

        // إذا كانت الحالة محفوظة، اعرضها فوراً
        if (currentStatus != null) {
            renderStatus(currentStatus);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadStatus();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_NOTIFIED, setupCompletedNotified);
        if (currentStatus != null) {
            outState.putString(KEY_STATUS_JSON, currentStatus.toString());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (pendingRecoveryRunnable != null) {
            mainHandler.removeCallbacks(pendingRecoveryRunnable);
            pendingRecoveryRunnable = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Views & Listeners
    // ═══════════════════════════════════════════════════════

    private void initViews(View v) {
        tvProgressPercent = v.findViewById(R.id.tvProgressPercent);
        tvProgressText    = v.findViewById(R.id.tvProgressText);
        tvGroupProgress   = v.findViewById(R.id.tvGroupProgress);
        pbProgress        = v.findViewById(R.id.pbProgress);
        pbLoading         = v.findViewById(R.id.pbLoading);
        stepsContainer    = v.findViewById(R.id.stepsContainer);
        contentContainer  = v.findViewById(R.id.contentContainer);
        btnStart          = v.findViewById(R.id.btnStart);
        btnReset          = v.findViewById(R.id.btnReset);
        btnGoToSystem     = v.findViewById(R.id.btnGoToSystem);
    }

    private void setupListeners() {
        if (btnStart != null) {
            btnStart.setOnClickListener(v -> {
                if (isRetryMode) {
                    isRetryMode = false;
                    loadStatus();
                } else {
                    startOrContinue();
                }
            });
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
        hideActionButtons();

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/status");
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    hideActionButtons();

                    if (resp != null && resp.isSuccess && resp.data != null) {
                        try {
                            JSONObject payload = resp.data;
                            JSONObject nested = payload.optJSONObject("data");
                            if (nested != null && nested.has("progress_percent")) {
                                payload = nested;
                            }
                            currentStatus = payload;
                            renderStatus(payload);
                        } catch (Exception e) {
                            Log.e(TAG, "render error", e);
                            showError("parse error");
                        }
                    } else {
                        String err = (resp != null && resp.error != null) ? resp.error : "unknown";
                        showError(err);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadStatus", e);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showError(e.getMessage());
                });
            }
        }).start();
    }

    private void showError(String err) {
        isRetryMode = true;
        if (btnStart != null) {
            btnStart.setVisibility(View.VISIBLE);
            btnStart.setText(isArabic ? "🔄 إعادة المحاولة" : "🔄 Retry");
            btnStart.setEnabled(true);
        }
        if (btnReset != null) btnReset.setVisibility(View.GONE);
        if (btnGoToSystem != null) btnGoToSystem.setVisibility(View.GONE);
        Toast.makeText(requireContext(),
                isArabic ? "❌ فشل تحميل الحالة: " + err
                        : "❌ Failed to load status: " + err,
                Toast.LENGTH_LONG).show();
    }

    private void hideActionButtons() {
        if (btnStart != null) btnStart.setVisibility(View.GONE);
        if (btnReset != null) btnReset.setVisibility(View.GONE);
        if (btnGoToSystem != null) btnGoToSystem.setVisibility(View.GONE);
    }

    // ═══════════════════════════════════════════════════════
    //  Render
    // ═══════════════════════════════════════════════════════

    private void renderStatus(JSONObject status) {
        if (status == null) return;
        try {
            int progress       = status.optInt("progress_percent", 0);
            String setupStatus = status.optString("status", "pending");
            int currentStep    = status.optInt("current_step", 1);
            int totalSteps     = status.optInt("total_steps", 10);
            int completedCount = status.optInt("completed_count", 0);

            pbProgress.setProgress(progress);
            tvProgressPercent.setText(progress + "%");

            String statusText = statusLabel(setupStatus);
            tvProgressText.setText(statusText);

            if (tvGroupProgress != null) {
                tvGroupProgress.setText(String.format(Locale.US,
                        isArabic ? "%d / %d خطوة مكتملة" : "%d / %d steps complete",
                        completedCount, totalSteps));
            }

            boolean completed = "completed".equals(setupStatus)
                    || "locked".equals(setupStatus);

            // ⭐ Auto-redirect
            if (completed && !setupCompletedNotified && !isManualOpen) {
                setupCompletedNotified = true;
                if (contentContainer != null) contentContainer.setVisibility(View.GONE);
                if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
                Log.d(TAG, "🚀 Auto-redirect (setup complete)");
                safeAutoRedirect();
                return;
            }

            // Render groups + steps
            renderGroupsAndSteps(status);

            // Buttons
            if (completed) {
                btnStart.setVisibility(View.VISIBLE);
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
                btnStart.setVisibility(View.VISIBLE);
                btnStart.setEnabled(true);
                if (completedCount == 0) {
                    btnStart.setText(isArabic ? "🚀 بدء الإعداد" : "🚀 Start Setup");
                } else {
                    btnStart.setText(isArabic
                            ? "▶️ المتابعة من الخطوة " + currentStep
                            : "▶️ Continue from step " + currentStep);
                }
                btnReset.setVisibility(View.GONE);
                if (btnGoToSystem != null) btnGoToSystem.setVisibility(View.GONE);
            }

        } catch (Exception e) {
            Log.e(TAG, "renderStatus", e);
        }
    }

    private String statusLabel(String s) {
        switch (s) {
            case "pending":     return isArabic ? "⏳ بانتظار البدء" : "⏳ Pending";
            case "in_progress": return isArabic ? "🔄 جاري الإعداد" : "🔄 In Progress";
            case "completed":   return isArabic ? "✅ مكتمل" : "✅ Completed";
            case "locked":      return isArabic ? "🔒 مقفل" : "🔒 Locked";
            default:            return s;
        }
    }

    private void renderGroupsAndSteps(JSONObject status) {
        stepsContainer.removeAllViews();

        JSONArray groups = status.optJSONArray("groups");
        JSONArray steps  = status.optJSONArray("steps");

        if (groups == null || steps == null) return;

        // خريطة step.number → JSONObject
        java.util.Map<Integer, JSONObject> stepMap = new java.util.HashMap<>();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            // steps array index = step number - 1 (نظراً للترتيب)
            int stepNum = i + 1;
            stepMap.put(stepNum, step);
        }

        // لكل مجموعة، اطبع header + خطواتها
        int stepCounter = 1;
        for (int g = 0; g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            if (group == null) continue;

            int groupId    = group.optInt("group_id", g + 1);
            int gTotal     = group.optInt("total", 0);
            int gCompleted = group.optInt("completed", 0);
            String gTitle  = isArabic
                    ? group.optString("title_ar", "")
                    : group.optString("title_en", "");
            String gIcon   = group.optString("icon", "•");

            addGroupHeader(gIcon + "  " + gTitle, gCompleted, gTotal);

            // خطوات هذه المجموعة
            for (int s = 0; s < gTotal; s++) {
                JSONObject step = stepMap.get(stepCounter);
                if (step != null) {
                    addStepCard(step, stepCounter);
                }
                stepCounter++;
            }
        }
    }

    private void addGroupHeader(String title, int completed, int total) {
        View header = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_group_header, stepsContainer, false);

        TextView tvTitle = header.findViewById(R.id.tvGroupTitle);
        TextView tvCount = header.findViewById(R.id.tvGroupCount);

        tvTitle.setText(title);
        tvCount.setText(String.format(Locale.US, "%d/%d", completed, total));

        if (completed == total && total > 0) {
            tvCount.setTextColor(0xFF27AE60);
        } else if (completed > 0) {
            tvCount.setTextColor(0xFFF39C12);
        } else {
            tvCount.setTextColor(0xFF95A5A6);
        }

        stepsContainer.addView(header);
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

            String icon = step.optString("icon", "📌");
            String title = isArabic
                    ? step.optString("title_ar", "")
                    : step.optString("title_en", "");
            String desc = step.optString("description", "");
            boolean isCompleted = step.optBoolean("completed", false);
            boolean isRequired  = step.optBoolean("required", false);

            tvIcon.setText(icon);
            tvNumber.setText(String.valueOf(stepNumber));
            tvTitle.setText(title);
            tvDesc.setText(desc);

            if (isCompleted) {
                tvStatus.setText(isArabic ? "✅ مكتمل" : "✅ Done");
                tvStatus.setTextColor(0xFF27AE60);
                statusIndicator.setBackgroundColor(0xFF27AE60);
            } else if (isRequired) {
                tvStatus.setText(isArabic ? "⏳ مطلوب" : "⏳ Required");
                tvStatus.setTextColor(0xFFF39C12);
                statusIndicator.setBackgroundColor(0xFFF39C12);
            } else {
                tvStatus.setText(isArabic ? "⏳ اختياري" : "⏳ Optional");
                tvStatus.setTextColor(0xFF95A5A6);
                statusIndicator.setBackgroundColor(0xFF95A5A6);
            }

            final int stepNum = stepNumber;
            card.setOnClickListener(v -> openStep(stepNum));
            stepsContainer.addView(card);

        } catch (Exception e) {
            Log.e(TAG, "addStepCard", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Navigation
    // ═══════════════════════════════════════════════════════

    private void startOrContinue() {
        int step = 1;
        try {
            if (currentStatus != null) {
                step = currentStatus.optInt("current_step", 1);
            }
        } catch (Exception ignored) {}
        openStep(step);
    }

    private void openStep(int stepNumber) {
        Fragment fragment = null;

        switch (stepNumber) {
            case 1:  fragment = new SetupStep1FiscalYearFragment(); break;
            case 2:  fragment = new SetupStep2BranchesFragment(); break;
            case 3:  fragment = new SetupStep3CurrenciesFragment(); break;
            case 4:  fragment = new SetupStep4GoldPricesFragment(); break;
            case 5:  fragment = new SetupStep5MakingChargesFragment(); break;
            case 6:  fragment = new SetupStep6OpeningBalancesFragment(); break;
            case 7:  fragment = new SetupStep7UsersPermissionsFragment(); break;
            case 8:  fragment = new SetupStep8ZakatFragment(); break;
            case 9:  fragment = new SetupStep9RfidFragment(); break;
            case 10: fragment = new SetupStep10ReviewFragment(); break;
        }

        if (fragment == null) {
            Toast.makeText(requireContext(),
                    isArabic ? "خطوة غير معروفة" : "Unknown step",
                    Toast.LENGTH_SHORT).show();
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

    // ═══════════════════════════════════════════════════════
    //  Auto Redirect
    // ═══════════════════════════════════════════════════════

    private void safeAutoRedirect() {
        if (!isAdded()) return;
        notifySetupCompleted();

        pendingRecoveryRunnable = () -> {
            if (!isAdded()) return;
            pendingRecoveryRunnable = null;
            if (pbLoading != null && pbLoading.getVisibility() == View.VISIBLE
                    && contentContainer != null && contentContainer.getVisibility() == View.GONE) {
                Log.w(TAG, "⚠️ Auto-redirect stalled — recovering");
                showLoading(false);
                if (currentStatus != null) renderStatus(currentStatus);
            }
        };
        mainHandler.postDelayed(pendingRecoveryRunnable, 2500);
    }

    private void notifySetupCompleted() {
        if (!isAdded()) return;
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).onSetupCompleted();
        }
    }

    private void navigateToSystem() {
        if (!isAdded()) return;
        setupCompletedNotified = true;
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity act = (UHFMainActivity) getActivity();
            act.setBottomNavVisible(true);
            act.onSetupCompleted();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Reset
    // ═══════════════════════════════════════════════════════

    private void confirmReset() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "⚠️ إعادة تعيين الإعداد" : "⚠️ Reset Setup")
                .setMessage(isArabic
                        ? "سيتم حذف:\n"
                        + "• جميع الأرصدة الافتتاحية\n"
                        + "• القيد الافتتاحي\n"
                        + "• حالة جميع الخطوات\n\n"
                        + "⚠️ لن يمكن التراجع.\n\n"
                        + "هل أنت متأكد؟"
                        : "This will delete:\n"
                        + "• All opening balances\n"
                        + "• Opening journal entry\n"
                        + "• All step progress\n\n"
                        + "⚠️ Cannot be undone.\n\n"
                        + "Are you sure?")
                .setPositiveButton(isArabic ? "نعم، إعادة تعيين" : "Yes, Reset",
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

                MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/reset", payload);

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (resp != null && resp.isSuccess) {
                        Toast.makeText(requireContext(),
                                isArabic ? "✅ تم إعادة التعيين" : "✅ Reset complete",
                                Toast.LENGTH_LONG).show();

                        // إبطال الكاش + إعادة التوجيه
                        if (getActivity() instanceof UHFMainActivity) {
                            try {
                                ((UHFMainActivity) getActivity()).invalidateSetupCache();
                            } catch (Exception ignored) {}
                        }

                        setupCompletedNotified = false;
                        isRetryMode = false;
                        currentStatus = null;
                        loadStatus();
                    } else {
                        String err = (resp != null && resp.error != null) ? resp.error : "unknown";
                        Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "performReset", e);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  Utils
    // ═══════════════════════════════════════════════════════

    private void showLoading(boolean show) {
        if (pbLoading == null || contentContainer == null) return;
        pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        contentContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }
}
