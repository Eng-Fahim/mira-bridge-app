package com.example.uhf.fragment;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ QuickActionsBottomSheetFragment — v4.0 "Premium Edition"
 *
 * التحديثات:
 * - Stagger Fade-in Animation (40ms بين كل عنصر)
 * - Delay قبل التنقل (250ms) لمنع تعارض Bottom Sheet مع الشاشة الجديدة
 * - Haptic Feedback خفيف عند الضغط
 * - بدون عنوان (Header-less design)
 */
public class QuickActionsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String TAG = "QuickActionsSheet";

    // ═══════════════════════════════════════════════════════
    // Animation Constants
    // ═══════════════════════════════════════════════════════
    private static final long STAGGER_DELAY_MS = 40L;
    private static final long ITEM_FADE_DURATION_MS = 250L;
    private static final long INITIAL_DELAY_MS = 100L;

    // ═══════════════════════════════════════════════════════
    // Navigation Constants
    // ═══════════════════════════════════════════════════════
    private static final long NAVIGATION_DELAY_MS = 250L;

    private boolean isArabic;
    private PermissionManager permissionManager;
    private MiraQuickAccessSettings quickAccessSettings;

    // ═══════════════════════════════════════════════════════
    // Animation state
    // ═══════════════════════════════════════════════════════
    private final List<View> animatedViews = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quick_actions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context context = requireContext();
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            permissionManager = PermissionManager.getInstance(context);
            quickAccessSettings = MiraQuickAccessSettings.getInstance(context);

            Log.d(TAG, "🚀 Initializing Quick Actions v4.0");

            // ═══════════════════════════════════════════════════
            // PRIMARY CTA — استوديو التكويد
            // ═══════════════════════════════════════════════════
            setupPrimaryCta(view);

            // ═══════════════════════════════════════════════════
            // GRID — بقية الإجراءات
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardRfidWrite,
                    "rfid_write",
                    permissionManager.canEditInventory(),
                    new MiraUHFReadWriteFragment(), "RFIDWrite");

            setupGridAction(view, R.id.cardRadarLocation,
                    "radar",
                    permissionManager.canAccessRadar(),
                    new MiraUHFRadarLocationFragment(), "Radar");

            setupGridAction(view, R.id.cardRfidBindings,
                    "rfid",
                    permissionManager.canViewRfidBindings(),
                    new MiraRfidBindingsFragment(), "RfidBindings");

            setupGridAction(view, R.id.cardSecureGate,
                    "gate",
                    permissionManager.canAccessGate(),
                    new MiraSecureGateFragment(), "SecureGate");

            setupGridAction(view, R.id.cardReports,
                    "reports",
                    permissionManager.canExportReports(),
                    new MiraReportsProFragment(), "Reports");

            setupGridAction(view, R.id.cardAccounting,
                    "accounting",
                    permissionManager.canAccessAccounting(),
                    new MiraAccountingFragment(), "Accounting");

            setupGridAction(view, R.id.cardTransfer,
                    "transfers",
                    permissionManager.canAccessTransfers(),
                    new MiraTransferFragment(), "Transfers");

            setupGridAction(view, R.id.cardSalesHistory,
                    "sales_history",
                    permissionManager.canViewSalesHistory(),
                    new MiraSalesHistoryFragment(), "SalesHistory");

            setupGridAction(view, R.id.cardAdminHub,
                    "admin_hub",
                    permissionManager.isAdmin(),
                    new MiraAdminHubFragment(), "AdminHub");

            setupGridAction(view, R.id.cardServerConfig,
                    "server_config",
                    permissionManager.canViewServerConfig(),
                    new MiraServerConfigFragment(), "ServerConfig");

            // ═══════════════════════════════════════════════════
            // CLOSE BUTTON
            // ═══════════════════════════════════════════════════
            View btnClose = view.findViewById(R.id.btnClose);
            if (btnClose != null) {
                animatedViews.add(btnClose);
                btnClose.setOnClickListener(v -> {
                    Log.d(TAG, "🔒 Close clicked");
                    performHaptic();
                    dismiss();
                });
            }

            // ═══════════════════════════════════════════════════
            // STAGGER FADE-IN ANIMATION
            // ═══════════════════════════════════════════════════
            runStaggerAnimation();

            Log.d(TAG, "✅ Quick Actions ready — "
                    + animatedViews.size() + " animated views");

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // PRIMARY CTA
    // ═══════════════════════════════════════════════════════

    private void setupPrimaryCta(View view) {
        try {
            View cta = view.findViewById(R.id.cardIdentityStudio);
            if (cta == null) return;

            boolean hasPermission = permissionManager.canAccessIdentityStudio();
            boolean quickAccessAllowed = quickAccessSettings == null
                    || quickAccessSettings.isActionEnabled("identity");

            if (!hasPermission || !quickAccessAllowed) {
                cta.setVisibility(View.GONE);
                Log.d(TAG, "⏭️ CTA hidden (no permission)");
                return;
            }

            cta.setVisibility(View.VISIBLE);
            animatedViews.add(cta);

            cta.setOnClickListener(v -> {
                Log.d(TAG, "✅ CTA clicked: Identity Studio");
                performHaptic();
                dismiss();
                navigateTo(new MiraIdentityStudioFragment(), "IdentityStudio");
            });

        } catch (Exception e) {
            Log.e(TAG, "setupPrimaryCta error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // GRID ACTIONS
    // ═══════════════════════════════════════════════════════

    private void setupGridAction(View root,
                                 int cardId,
                                 String actionKey,
                                 boolean hasPermission,
                                 Fragment targetFragment,
                                 String tag) {

        try {
            View card = root.findViewById(cardId);

            if (card == null) {
                Log.w(TAG, "Grid item not found: id=" + cardId + ", key=" + actionKey);
                return;
            }

            boolean quickAccessAllowed = quickAccessSettings == null
                    || quickAccessSettings.isActionEnabled(actionKey);
            boolean isVisible = hasPermission && quickAccessAllowed;

            if (!isVisible) {
                card.setVisibility(View.GONE);
                Log.d(TAG, "⏭️ Hidden: " + actionKey);
                return;
            }

            card.setVisibility(View.VISIBLE);
            animatedViews.add(card);

            card.setOnClickListener(v -> {
                Log.d(TAG, "✅ Clicked: " + actionKey);
                performHaptic();
                dismiss();
                navigateTo(targetFragment, tag);
            });

            Log.d(TAG, "✅ Registered: " + actionKey);

        } catch (Exception e) {
            Log.e(TAG, "setupGridAction(" + actionKey + ") error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // NAVIGATION — with Delay + Haptic
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ التنقل بعد تأخير 250ms
     *     — حتى يكتمل انزلاق Bottom Sheet بدون تعارض
     */
    private void navigateTo(Fragment targetFragment, String tag) {
        if (targetFragment == null) {
            Log.w(TAG, "Target fragment is null — cannot navigate");
            return;
        }

        mainHandler.postDelayed(() -> {
            if (!isAdded()) {
                Log.w(TAG, "Fragment not added — abort navigation");
                return;
            }

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(targetFragment, tag);
                Log.d(TAG, "🚀 Navigated to: " + tag);
            } else {
                Log.w(TAG, "Activity is not UHFMainActivity");
                if (getContext() != null) {
                    Toast.makeText(getContext(),
                            isArabic ? "تعذر فتح الشاشة" : "Unable to open screen",
                            Toast.LENGTH_SHORT).show();
                }
            }
        }, NAVIGATION_DELAY_MS);
    }

    /**
     * ✅ Haptic Feedback خفيف عند الضغط — إحساس فاخر
     */
    private void performHaptic() {
        try {
            View root = getView();
            if (root != null) {
                root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } catch (Exception e) {
            Log.w(TAG, "Haptic feedback failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // STAGGER FADE-IN ANIMATION
    // ═══════════════════════════════════════════════════════

    private void runStaggerAnimation() {
        try {
            if (animatedViews.isEmpty()) return;

            AnimatorSet masterSet = new AnimatorSet();
            List<Animator> animators = new ArrayList<>();

            long delay = INITIAL_DELAY_MS;

            for (View v : animatedViews) {
                if (v == null || v.getVisibility() != View.VISIBLE) continue;

                // الحالة الأولية: مخفي + مُنزاح قليلاً
                v.setAlpha(0f);
                v.setTranslationY(16f);

                ObjectAnimator alpha = ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
                alpha.setDuration(ITEM_FADE_DURATION_MS);
                alpha.setStartDelay(delay);

                ObjectAnimator translate =
                        ObjectAnimator.ofFloat(v, "translationY", 16f, 0f);
                translate.setDuration(ITEM_FADE_DURATION_MS);
                translate.setStartDelay(delay);

                animators.add(alpha);
                animators.add(translate);

                delay += STAGGER_DELAY_MS;
            }

            masterSet.playTogether(animators);
            masterSet.setInterpolator(new DecelerateInterpolator());
            masterSet.start();

            Log.d(TAG, "🎬 Stagger animation started — total "
                    + (delay + ITEM_FADE_DURATION_MS) + "ms");

        } catch (Exception e) {
            Log.e(TAG, "runStaggerAnimation error: " + e.getMessage(), e);

            // Fallback: إظهار كل العناصر فوراً
            for (View v : animatedViews) {
                if (v != null) {
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // BOTTOM SHEET HEIGHT
    // ═══════════════════════════════════════════════════════

    @Override
    public void onStart() {
        super.onStart();

        try {
            View bottomSheet = getDialog() != null
                    ? getDialog().findViewById(
                            com.google.android.material.R.id.design_bottom_sheet)
                    : null;

            if (bottomSheet != null) {
                bottomSheet.getLayoutParams().height =
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.80);
                bottomSheet.requestLayout();
                Log.d(TAG, "📏 Bottom sheet height set to 80%");
            }
        } catch (Exception e) {
            Log.e(TAG, "onStart error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        animatedViews.clear();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
}
