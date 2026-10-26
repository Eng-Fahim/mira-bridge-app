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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;           // ← هذا

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MIRA Accounting Setup — v3.4 (Soft-Lock + Theme-Aware)
 *
 * التحسينات في v3.4:
 *  ✅ 🔒 أيقونة قفل على الخطوات المحظورة (Soft-Lock)
 *  ✅ Dialog ناعم عند الدخول لخطوة محظورة (لا يمنع، يوجه)
 *  ✅ منطق تبعيات دقيق:
 *      - Step 6 يحتاج Step 4 (أسعار الذهب)
 *      - Step 8 يحتاج Step 1 (السنة المالية)
 *      - Step 10 يحتاج كل الخطوات الإلزامية
 *  ✅ ألوان theme-aware (لا hardcoded)
 *  ✅ MaterialAlertDialogBuilder بدل AlertDialog.Builder
 *  ✅ null-safety كامل
 */
public class MiraAccountingSetupFragment extends BaseSetupStepFragment {

    private static final String TAG = "MiraSetup";
    private static final String KEY_NOTIFIED = "setup_completed_notified";
    private static final String KEY_STATUS_JSON = "cached_status_json";

    // ═══ الخطوات الإلزامية (للاعتماد) ═══
    private static final int[] REQUIRED_STEPS = {1, 2, 3, 4, 5, 6, 8, 9};

    // ═══ Views ═══
    private TextView tvProgressPercent, tvProgressText, tvGroupProgress;
    private ProgressBar pbProgress, pbLoading;
    private LinearLayout stepsContainer;
    private View contentContainer;
    private MaterialButton btnStart, btnReset, btnGoToSystem;

    // ═══ State ═══
    private MiraApiClient apiClient;
    private boolean isArabic;
    private JSONObject currentStatus;
    private boolean setupCompletedNotified = false;
    private boolean isManualOpen = false;
    private boolean isRetryMode = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRecoveryRunnable;

    // ═══ Colors (theme-aware) ═══
    private int colorSuccess;
    private int colorWarning;
    private int colorNeutral;
    private int colorError;

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

        loadThemeColors();
        initViews(view);
        setupListeners();

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
    //  Init
    // ═══════════════════════════════════════════════════════

    private void loadThemeColors() {
        colorSuccess = ContextCompat.getColor(requireContext(), R.color.acct_success);
        colorWarning = ContextCompat.getColor(requireContext(), R.color.acct_warning);
        colorNeutral = ContextCompat.getColor(requireContext(), R.color.acct_neutral);
        colorError   = ContextCompat.getColor(requireContext(), R.color.acct_credit);
    }

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

            if (pbProgress != null) pbProgress.setProgress(progress);
            if (tvProgressPercent != null) tvProgressPercent.setText(progress + "%");

            String statusText = statusLabel(setupStatus);
            if (tvProgressText != null) tvProgressText.setText(statusText);

            if (tvGroupProgress != null) {
                tvGroupProgress.setText(String.format(Locale.US,
                        isArabic ? "%d / %d خطوة مكتملة" : "%d / %d steps complete",
                        completedCount, totalSteps));
            }

            boolean completed = "completed".equals(setupStatus)
                    || "locked".equals(setupStatus);

            // ✅ Auto-redirect
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
                if (btnStart != null) {
                    btnStart.setVisibility(View.VISIBLE);
                    btnStart.setText(isArabic ? "👁️ مراجعة الإعداد" : "👁️ Review Setup");
                    btnStart.setEnabled(true);
                }
                if (btnReset != null) btnReset.setVisibility(View.VISIBLE);
                if (btnGoToSystem != null) {
                    btnGoToSystem.setVisibility(View.VISIBLE);
                    btnGoToSystem.setText(isArabic
                            ? "🚀 الانتقال إلى النظام"
                            : "🚀 Enter System");
                }
            } else {
                if (btnStart != null) {
                    btnStart.setVisibility(View.VISIBLE);
                    btnStart.setEnabled(true);
                    if (completedCount == 0) {
                        btnStart.setText(isArabic ? "🚀 بدء الإعداد" : "🚀 Start Setup");
                    } else {
                        btnStart.setText(isArabic
                                ? "▶️ المتابعة من الخطوة " + currentStep
                                : "▶️ Continue from step " + currentStep);
                    }
                }
                if (btnReset != null) btnReset.setVisibility(View.GONE);
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
        if (stepsContainer == null) return;
        stepsContainer.removeAllViews();

        JSONArray groups = status.optJSONArray("groups");
        JSONArray steps  = status.optJSONArray("steps");

        if (groups == null || steps == null) return;

        // خريطة step.number → JSONObject
        Map<Integer, JSONObject> stepMap = new HashMap<>();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            stepMap.put(i + 1, step);
        }

        int stepCounter = 1;
        for (int g = 0; g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            if (group == null) continue;

            int gTotal     = group.optInt("total", 0);
            int gCompleted = group.optInt("completed", 0);
            String gTitle  = isArabic
                    ? group.optString("title_ar", "")
                    : group.optString("title_en", "");
            String gIcon   = group.optString("icon", "•");

            addGroupHeader(gIcon + "  " + gTitle, gCompleted, gTotal);

            for (int s = 0; s < gTotal; s++) {
                JSONObject step = stepMap.get(stepCounter);
                if (step != null) {
                    addStepCard(step, stepCounter, status);
                }
                stepCounter++;
            }
        }
    }

    private void addGroupHeader(String title, int completed, int total) {
        if (stepsContainer == null) return;
        View header = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_group_header, stepsContainer, false);

        TextView tvTitle = header.findViewById(R.id.tvGroupTitle);
        TextView tvCount = header.findViewById(R.id.tvGroupCount);

        if (tvTitle != null) tvTitle.setText(title);
        if (tvCount != null) {
            tvCount.setText(String.format(Locale.US, "%d/%d", completed, total));

            if (completed == total && total > 0) {
                tvCount.setTextColor(colorSuccess);
            } else if (completed > 0) {
                tvCount.setTextColor(colorWarning);
            } else {
                tvCount.setTextColor(colorNeutral);
            }
        }

        stepsContainer.addView(header);
    }

    /**
     * ⭐ v3.4 — إضافة بطاقة خطوة مع كشف التبعيات
     */
    private void addStepCard(JSONObject step, int stepNumber, JSONObject status) {
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

            // ═══ فحص التبعيات ═══
            BlockInfo blockInfo = isCompleted ? null : getBlockInfo(stepNumber, status);
            boolean isBlocked = blockInfo != null;

            if (tvIcon != null) tvIcon.setText(icon);
            if (tvNumber != null) tvNumber.setText(String.valueOf(stepNumber));
            if (tvTitle != null) tvTitle.setText(title);
            if (tvDesc != null) tvDesc.setText(desc);

            // ═══ تحديد الحالة + اللون ═══
            if (isCompleted) {
                // ✅ مكتمل
                if (tvStatus != null) {
                    tvStatus.setText(isArabic ? "✅ مكتمل" : "✅ Done");
                    tvStatus.setTextColor(colorSuccess);
                }
                if (statusIndicator != null) statusIndicator.setBackgroundColor(colorSuccess);

            } else if (isBlocked) {
                // 🔒 محظور — يحتاج خطوة سابقة
                if (tvStatus != null) {
                    tvStatus.setText(isArabic ? "🔒 متطلب سابق" : "🔒 Blocked");
                    tvStatus.setTextColor(colorWarning);
                }
                if (statusIndicator != null) statusIndicator.setBackgroundColor(colorWarning);

            } else if (isRequired) {
                // ⏳ مطلوب (لكن غير محظور)
                if (tvStatus != null) {
                    tvStatus.setText(isArabic ? "⏳ مطلوب" : "⏳ Required");
                    tvStatus.setTextColor(colorWarning);
                }
                if (statusIndicator != null) statusIndicator.setBackgroundColor(colorWarning);

            } else {
                // ⏳ اختياري
                if (tvStatus != null) {
                    tvStatus.setText(isArabic ? "⏳ اختياري" : "⏳ Optional");
                    tvStatus.setTextColor(colorNeutral);
                }
                if (statusIndicator != null) statusIndicator.setBackgroundColor(colorNeutral);
            }

            // ═══ Click handler ═══
            final int stepNum = stepNumber;
            final BlockInfo finalBlock = blockInfo;
            card.setOnClickListener(v -> {
                if (finalBlock != null) {
                    showSoftLockDialog(stepNum, finalBlock);
                } else {
                    openStep(stepNum);
                }
            });

            stepsContainer.addView(card);

        } catch (Exception e) {
            Log.e(TAG, "addStepCard", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Dependency Check (v3.4 — جديد)
    // ═══════════════════════════════════════════════════════

    /**
     * فحص تبعيات الخطوة
     * @return null إذا كانت الخطوة جاهزة، وإلا كائن BlockInfo
     */
    @Nullable
    private BlockInfo getBlockInfo(int stepNumber, JSONObject status) {
        if (status == null) return null;

        // خريطة الحالة المكتملة لكل خطوة
        Map<Integer, Boolean> completedMap = new HashMap<>();
        JSONArray steps = status.optJSONArray("steps");
        if (steps != null) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (s != null) {
                    completedMap.put(i + 1, s.optBoolean("completed", false));
                }
            }
        }

        switch (stepNumber) {
            case 6: // الأرصدة الافتتاحية ← تحتاج Step 4 (أسعار الذهب)
                if (!Boolean.TRUE.equals(completedMap.get(4))) {
                    return new BlockInfo(
                            4,
                            isArabic
                                    ? "تحتاج إكمال الخطوة 4 (أسعار الذهب) لحساب قيمة الأوزان تلقائياً"
                                    : "Complete Step 4 (Gold Prices) to auto-calculate values"
                    );
                }
                break;

            case 8: // الزكاة ← تحتاج Step 1 (السنة المالية)
                if (!Boolean.TRUE.equals(completedMap.get(1))) {
                    return new BlockInfo(
                            1,
                            isArabic
                                    ? "تحتاج إكمال الخطوة 1 (السنة المالية) لربط الزكاة بالسنة"
                                    : "Complete Step 1 (Fiscal Year) to link zakat"
                    );
                }
                break;

            case 10: // المراجعة والاعتماد ← تحتاج كل الخطوات الإلزامية
                for (int required : REQUIRED_STEPS) {
                    if (!Boolean.TRUE.equals(completedMap.get(required))) {
                        return new BlockInfo(
                                required,
                                isArabic
                                        ? "أكمل جميع الخطوات الإلزامية أولاً (المتبقي: الخطوة " + required + ")"
                                        : "Complete all required steps first (missing: step " + required + ")"
                        );
                    }
                }
                break;
        }

        return null;
    }

    /**
     * ⭐ Dialog ناعم عند الدخول لخطوة محظورة
     */
    private void showSoftLockDialog(int stepNumber, BlockInfo info) {
        if (!isAdded()) return;

        String message = info.reason + "\n\n"
                + (isArabic
                ? "يمكنك فتحها على أي حال، لكن بعض الحقول قد لا تعمل بشكل صحيح."
                : "You can open it anyway, but some fields may not work correctly.");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("🔒 " + (isArabic ? "خطوة غير جاهزة" : "Step Not Ready"))
                .setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(
                        isArabic ? "اذهب للخطوة " + info.blockingStep
                                : "Go to step " + info.blockingStep,
                        (d, w) -> openStep(info.blockingStep))
                .setNeutralButton(
                        isArabic ? "فتح على أي حال" : "Open Anyway",
                        (d, w) -> openStep(stepNumber))
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
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
        Fragment fragment = SetupNavigator.getFragmentForStep(stepNumber);

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
        new MaterialAlertDialogBuilder(requireContext())
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
                .setIcon(android.R.drawable.ic_dialog_alert)
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

    // ═══════════════════════════════════════════════════════
    //  Inner classes
    // ═══════════════════════════════════════════════════════

    /**
     * معلومات الحظر لخطوة معينة
     */
    private static class BlockInfo {
        final int blockingStep;
        final String reason;

        BlockInfo(int blockingStep, String reason) {
            this.blockingStep = blockingStep;
            this.reason = reason;
        }
    }
}
