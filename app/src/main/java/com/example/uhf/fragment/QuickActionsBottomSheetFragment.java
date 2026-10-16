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
import androidx.fragment.app.Fragment;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.navigation.FragmentFactory;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ QuickActionsBottomSheetFragment — v6.1
 *    + إضافة بطاقتي "المستخدمين" و"الفروع"
 */
public class QuickActionsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String TAG = "QuickActionsSheet";

    private static final long STAGGER_DELAY_MS = 40L;
    private static final long ITEM_FADE_DURATION_MS = 250L;
    private static final long INITIAL_DELAY_MS = 100L;
    private static final long NAV_DELAY_MS = 250L;

    private boolean isArabic;
    private PermissionManager permissionManager;
    private MiraQuickAccessSettings quickAccessSettings;

    private final List<View> animatedViews = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

            // ─── PRIMARY CTA ───
            setupPrimaryCta(view);

            // ─── GRID ───
            setupGridAction(view, R.id.cardRfidWrite,     "rfid_write",    permissionManager.canEditInventory(),    new MiraUHFReadWriteFragment(),        "RFIDWrite");
            setupGridAction(view, R.id.cardRadarLocation, "radar",         permissionManager.canAccessRadar(),      new MiraUHFRadarLocationFragment(),    "Radar");
            setupGridAction(view, R.id.cardRfidBindings,  "rfid",          permissionManager.canViewRfidBindings(), new MiraRfidBindingsFragment(),        "RfidBindings");
            setupGridAction(view, R.id.cardSecureGate,    "gate",          permissionManager.canAccessGate(),       new MiraSecureGateFragment(),          "SecureGate");
            setupGridAction(view, R.id.cardReports,       "reports",       permissionManager.canExportReports(),    new MiraReportsProFragment(),          "Reports");
            setupGridAction(view, R.id.cardAccounting,    "accounting",    permissionManager.canAccessAccounting(), new MiraAccountingFragment(),          "Accounting");
            setupGridAction(view, R.id.cardTransfer,      "transfers",     permissionManager.canAccessTransfers(),  new MiraTransferFragment(),            "Transfers");
            setupGridAction(view, R.id.cardSalesHistory,  "sales_history", permissionManager.canViewSalesHistory(), new MiraSalesHistoryFragment(),        "SalesHistory");
            setupGridAction(view, R.id.cardAdminHub,      "admin_hub",     permissionManager.isAdmin(),             new MiraAdminHubFragment(),            "AdminHub");
            setupGridAction(view, R.id.cardServerConfig,  "server_config", permissionManager.canViewServerConfig(), new MiraServerConfigFragment(),        "ServerConfig");

            // ─── ✨ جديد: المستخدمين والفروع ───
            setupGridAction(view, R.id.cardAdminUsers,    "admin_users",
                    permissionManager.isAdmin()
                 || permissionManager.isSystemAdmin()
                 || permissionManager.isOrgAdmin()
                 || permissionManager.isManager(),
                    new UsersManagementFragment(),
                    "UsersManagement");

            setupGridAction(view, R.id.cardAdminBranches, "admin_branches",
                    permissionManager.isAdmin()
                 || permissionManager.isOrgAdmin()
                 || permissionManager.isBranchManager(),
                    new MiraBranchesManagementFragment(),
                    "Branches");

            // ─── CLOSE BUTTON ───
            View btnClose = view.findViewById(R.id.btnClose);
            if (btnClose != null) {
                animatedViews.add(btnClose);
                btnClose.setOnClickListener(v -> {
                    performHaptic();
                    dismiss();
                });
            }

            // ─── ANIMATION ───
            runStaggerAnimation();

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
            if (cta == null) {
                Log.e(TAG, "CTA not found: cardIdentityStudio");
                return;
            }

            if (cta instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) cta;
                vg.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                vg.setClickable(true);
                vg.setFocusable(true);
            }

            boolean hasPermission = permissionManager.canAccessIdentityStudio();
            boolean quickAccessAllowed = quickAccessSettings == null
                    || quickAccessSettings.isActionEnabled("identity");

            if (!hasPermission || !quickAccessAllowed) {
                cta.setVisibility(View.GONE);
                return;
            }

            cta.setVisibility(View.VISIBLE);

            cta.setOnClickListener(v -> {
                performHaptic();
                navigateTo("IdentityStudio", new MiraIdentityStudioFragment());
            });

            animatedViews.add(cta);

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
                return;
            }

            card.setVisibility(View.VISIBLE);
            animatedViews.add(card);

            card.setOnClickListener(v -> {
                performHaptic();
                navigateTo(tag, targetFragment);
            });

        } catch (Exception e) {
            Log.e(TAG, "setupGridAction(" + actionKey + ") error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ التنقل الموحّد
     */
    private void navigateTo(String tag, Fragment fallbackFragment) {
        final UHFMainActivity activity =
                (getActivity() instanceof UHFMainActivity)
                        ? (UHFMainActivity) getActivity()
                        : null;

        if (activity == null) {
            Log.e(TAG, "❌ activity is NULL for tag: " + tag);
            return;
        }

        Fragment fragment = FragmentFactory.createFragmentByTag(tag);
        if (fragment == null) {
            fragment = fallbackFragment;
        }

        if (fragment == null) {
            Log.e(TAG, "❌ Cannot create fragment for tag: " + tag);
            return;
        }

        final Fragment finalFragment = fragment;
        final String finalTag = tag;

        dismiss();

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    Log.w(TAG, "⚠️ Activity is finishing, skipping navigation");
                    return;
                }
                activity.loadFragmentDirectly(finalFragment, finalTag);
                Log.d(TAG, "✅ Navigated to: " + finalTag);
            } catch (Throwable e) {
                Log.e(TAG, "❌ loadFragmentDirectly error", e);
            }
        }, NAV_DELAY_MS);
    }

    // ═══════════════════════════════════════════════════════
    // Haptic
    // ═══════════════════════════════════════════════════════

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
    // STAGGER ANIMATION
    // ═══════════════════════════════════════════════════════

    private void runStaggerAnimation() {
        try {
            if (animatedViews.isEmpty()) return;

            AnimatorSet masterSet = new AnimatorSet();
            List<Animator> animators = new ArrayList<>();

            long delay = INITIAL_DELAY_MS;

            for (View v : animatedViews) {
                if (v == null || v.getVisibility() != View.VISIBLE) continue;

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

        } catch (Exception e) {
            Log.e(TAG, "runStaggerAnimation error: " + e.getMessage(), e);

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
            }
        } catch (Exception e) {
            Log.e(TAG, "onStart error: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        animatedViews.clear();
    }
}
