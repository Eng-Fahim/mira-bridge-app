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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.navigation.FragmentFactory;
import com.example.uhf.navigation.NavigationManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QuickActionsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String TAG = "QuickActionsSheet";

    private static final long STAGGER_DELAY_MS      = 40L;
    private static final long ITEM_FADE_DURATION_MS = 250L;
    private static final long INITIAL_DELAY_MS      = 100L;
    private static final long NAV_DELAY_MS          = 250L;

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
            permissionManager   = PermissionManager.getInstance(context);
            quickAccessSettings = MiraQuickAccessSettings.getInstance(context);

            // ═══════════════════════════════════════════════════
            //  PRIMARY CTA — استوديو التكويد
            // ═══════════════════════════════════════════════════
            setupPrimaryCta(view);

            // ═══════════════════════════════════════════════════
            //  ROW 1: التكويد والأمان
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardRfidWrite, "rfid_write", NavigationManager.TAG_RFID_WRITE);
            setupGridAction(view, R.id.cardRadarLocation, "radar", NavigationManager.TAG_RADAR);
            setupGridAction(view, R.id.cardRfidBindings, "rfid", NavigationManager.TAG_RFID_BINDINGS);
            setupGridAction(view, R.id.cardSecureGate, "gate", NavigationManager.TAG_GATES);

            // ═══════════════════════════════════════════════════
            //  ROW 2: المالية والمبيعات
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardReports, "reports", NavigationManager.TAG_REPORTS);
            setupGridAction(view, R.id.cardAccounting, "accounting", NavigationManager.TAG_ACCOUNTING);
            setupGridAction(view, R.id.cardVouchers, "vouchers", NavigationManager.TAG_VOUCHERS);
            setupGridAction(view, R.id.cardTransfer, "transfers", NavigationManager.TAG_TRANSFERS);

            // ═══════════════════════════════════════════════════
            //  ROW 3: المبيعات والذهب
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardSalesHistory, "sales_history", NavigationManager.TAG_SALES_HISTORY);
            setupGridAction(view, R.id.cardGoldConversion, "gold_conversion", NavigationManager.TAG_GOLD_CONVERSION);
            setupGridAction(view, R.id.cardGoldRates, "gold_rates", NavigationManager.TAG_GOLD_RATES);
            setupGridAction(view, R.id.cardScrapBuy, "scrap_buy", NavigationManager.TAG_SCRAP_GOLD_BUY);

            // ═══════════════════════════════════════════════════
            //  ROW 4: إدارة الكسر والعملاء
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardScrapHistory, "scrap_history", NavigationManager.TAG_SCRAP_HISTORY);
            setupGridAction(view, R.id.cardScrapBatches, "scrap_batches", NavigationManager.TAG_SCRAP_BATCHES);
            setupGridAction(view, R.id.cardScrapAging, "scrap_aging", NavigationManager.TAG_SCRAP_AGING);
            setupGridAction(view, R.id.cardParties, "parties", NavigationManager.TAG_PARTIES);

            // ═══════════════════════════════════════════════════
            //  ROW 5: الإدارة والإعدادات
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardCashReconciliation, "cash_reconciliation", NavigationManager.TAG_CASH_RECONCILIATION);
            setupGridAction(view, R.id.cardAdminHub, "admin_hub", NavigationManager.TAG_ADMIN_HUB);
            setupGridAction(view, R.id.cardServerConfig, "server_config", NavigationManager.TAG_SERVER_CONFIG);
            setupGridAction(view, R.id.cardAdminUsers, "admin_users", NavigationManager.TAG_USERS_MANAGEMENT);

            // ═══════════════════════════════════════════════════
            //  ROW 6: إعدادات إضافية
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardAdminBranches, "admin_branches", NavigationManager.TAG_BRANCHES);
            setupGridAction(view, R.id.cardReaderSetup, "reader_setup", NavigationManager.TAG_READER_SETUP);
            setupGridAction(view, R.id.cardQuickAccess, "quick_access", NavigationManager.TAG_QUICK_ACCESS);
            setupGridAction(view, R.id.cardAuditLogs, "audit_logs", NavigationManager.TAG_AUDIT_LOGS);

            // ═══════════════════════════════════════════════════
            //  ROW 7: المشتريات
            // ═══════════════════════════════════════════════════
            setupGridAction(view, R.id.cardPurchaseInvoice, "purchase_invoice", NavigationManager.TAG_PURCHASE_INVOICE);

            // ═══════════════════════════════════════════════════
            //  CLOSE BUTTON
            // ═══════════════════════════════════════════════════
            View btnClose = view.findViewById(R.id.btnClose);
            if (btnClose != null) {
                animatedViews.add(btnClose);
                btnClose.setOnClickListener(v -> {
                    performHaptic();
                    dismiss();
                });
            }

            runStaggerAnimation();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    private void setupPrimaryCta(View view) {
        try {
            View cta = view.findViewById(R.id.cardIdentityStudio);
            if (cta == null) return;

            if (cta instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) cta;
                vg.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                vg.setClickable(true);
                vg.setFocusable(true);
            }

            boolean hasPermission = permissionManager.canAccessFragment(NavigationManager.TAG_IDENTITY_STUDIO);
            boolean quickAccessAllowed = quickAccessSettings == null || quickAccessSettings.isActionEnabled("identity");

            if (!hasPermission || !quickAccessAllowed) {
                cta.setVisibility(View.GONE);
                return;
            }

            cta.setVisibility(View.VISIBLE);
            cta.setOnClickListener(v -> {
                performHaptic();
                navigateTo(NavigationManager.TAG_IDENTITY_STUDIO, new MiraIdentityStudioFragment());
            });
            animatedViews.add(cta);

        } catch (Exception e) {
            Log.e(TAG, "setupPrimaryCta error: " + e.getMessage(), e);
        }
    }

    private void setupGridAction(View root, int cardId, String actionKey, String tag) {
        try {
            View card = root.findViewById(cardId);
            if (card == null) return;

            boolean hasPermission = permissionManager.canAccessFragment(tag);
            boolean quickAccessAllowed = quickAccessSettings == null || quickAccessSettings.isActionEnabled(actionKey);

            // ✅ [إصلاح مؤقت]: إجبار الشاشات الجديدة على الظهور للتجربة
            if (NavigationManager.TAG_GOLD_CONVERSION.equals(tag) || 
                NavigationManager.TAG_PURCHASE_INVOICE.equals(tag)) {
                hasPermission = true;
                quickAccessAllowed = true;
            }

            if (!hasPermission || !quickAccessAllowed) {
                card.setVisibility(View.GONE);
                return;
            }

            card.setVisibility(View.VISIBLE);
            animatedViews.add(card);

            card.setOnClickListener(v -> {
                performHaptic();
                Fragment target = FragmentFactory.createFragmentByTag(tag);
                navigateTo(tag, target);
            });

        } catch (Exception e) {
            Log.e(TAG, "setupGridAction(" + actionKey + ") error: " + e.getMessage(), e);
        }
    }

    private void navigateTo(String tag, @Nullable Fragment fallbackFragment) {
        if (tag == null || tag.isEmpty()) return;
        final UHFMainActivity activity = (getActivity() instanceof UHFMainActivity) ? (UHFMainActivity) getActivity() : null;
        if (activity == null) return;

        Fragment fragment = FragmentFactory.createFragmentByTag(tag);
        if (fragment == null) fragment = fallbackFragment;
        if (fragment == null) return;

        final Fragment finalFragment = fragment;
        final String finalTag = tag;

        dismiss();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                activity.loadFragmentDirectly(finalFragment, finalTag);
            } catch (Throwable e) {
                Log.e(TAG, "❌ loadFragmentDirectly error", e);
            }
        }, NAV_DELAY_MS);
    }

    private void performHaptic() {
        try {
            View root = getView();
            if (root != null) root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Exception ignored) {}
    }

    private void runStaggerAnimation() {
        try {
            if (animatedViews.isEmpty()) return;
            AnimatorSet masterSet = new AnimatorSet();
            List<Animator> animators = new ArrayList<>();
            long delay = INITIAL_DELAY_MS;

            for (View v : animatedViews) {
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                v.setAlpha(0f); v.setTranslationY(16f);
                ObjectAnimator alpha = ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
                alpha.setDuration(ITEM_FADE_DURATION_MS); alpha.setStartDelay(delay);
                ObjectAnimator translate = ObjectAnimator.ofFloat(v, "translationY", 16f, 0f);
                translate.setDuration(ITEM_FADE_DURATION_MS); translate.setStartDelay(delay);
                animators.add(alpha); animators.add(translate);
                delay += STAGGER_DELAY_MS;
            }
            masterSet.playTogether(animators);
            masterSet.setInterpolator(new DecelerateInterpolator());
            masterSet.start();
        } catch (Exception e) {
            for (View v : animatedViews) { if (v != null) { v.setAlpha(1f); v.setTranslationY(0f); } }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            View bottomSheet = getDialog() != null ? getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet) : null;
            if (bottomSheet != null) {
                bottomSheet.getLayoutParams().height = (int) (getResources().getDisplayMetrics().heightPixels * 0.90);
                bottomSheet.requestLayout();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        animatedViews.clear();
    }
}
