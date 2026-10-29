package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.license.LicenseStatusActivity;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.navigation.FragmentFactory;
import com.example.uhf.navigation.NavigationManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * QuickActionsBottomSheetFragment v3.0 — No-Gap Edition
 *
 * ✅ كل بطاقة تمر عبر فحص صلاحية موحّد
 * ✅ إعادة بناء GridLayout كاملة — لا فراغات أبداً
 * ✅ إضافة بطاقة جديدة = سطر واحد في CARD_CONFIGS
 * ✅ الحركة تعمل فقط على البطاقات النهائية المرئية
 */
public class QuickActionsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String TAG = "QuickActionsSheet";

    private static final long STAGGER_DELAY_MS      = 40L;
    private static final long ITEM_FADE_DURATION_MS = 250L;
    private static final long INITIAL_DELAY_MS      = 100L;
    private static final long NAV_DELAY_MS          = 250L;
    private static final int  GRID_COLUMNS          = 4;
    private static final int  GRID_MARGIN_DP        = 4;

    private boolean isArabic;
    private PermissionManager permissionManager;
    private MiraQuickAccessSettings quickAccessSettings;

    private final List<View> animatedViews = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════════
    //  ⭐ مصدر الحقيقة الوحيد
    //  أضف بطاقة جديدة = سطر واحد هنا فقط
    // ═══════════════════════════════════════════════════════════
    private static final class ActionCard {
        final int     viewId;
        final String  actionKey;
        final String  navTag;
        final boolean alwaysAllowed;

        ActionCard(int viewId, String actionKey, String navTag) {
            this(viewId, actionKey, navTag, false);
        }

        ActionCard(int viewId, String actionKey, String navTag, boolean alwaysAllowed) {
            this.viewId        = viewId;
            this.actionKey     = actionKey;
            this.navTag        = navTag;
            this.alwaysAllowed = alwaysAllowed;
        }
    }

    private static final ActionCard[] CARD_CONFIGS = {
        // ─── RFID & Location ───
        new ActionCard(R.id.cardRfidWrite,            "rfid_write",         NavigationManager.TAG_RFID_WRITE),
        new ActionCard(R.id.cardRadarLocation,        "radar",              NavigationManager.TAG_RADAR),
        new ActionCard(R.id.cardRfidBindings,         "rfid",               NavigationManager.TAG_RFID_BINDINGS),
        new ActionCard(R.id.cardSecureGate,           "gate",               NavigationManager.TAG_GATES),

        // ─── Business ───
        new ActionCard(R.id.cardReports,              "reports",            NavigationManager.TAG_REPORTS),
        new ActionCard(R.id.cardAccounting,           "accounting",         NavigationManager.TAG_ACCOUNTING),
        new ActionCard(R.id.cardVouchers,             "vouchers",           NavigationManager.TAG_VOUCHERS),
        new ActionCard(R.id.cardTransfer,             "transfers",          NavigationManager.TAG_TRANSFERS),

        new ActionCard(R.id.cardSalesHistory,         "sales_history",      NavigationManager.TAG_SALES_HISTORY),
        new ActionCard(R.id.cardGoldConversion,       "gold_conversion",    NavigationManager.TAG_GOLD_CONVERSION, true),
        new ActionCard(R.id.cardGoldRates,            "gold_rates",         NavigationManager.TAG_GOLD_RATES),
        new ActionCard(R.id.cardScrapBuy,             "scrap_buy",          NavigationManager.TAG_SCRAP_GOLD_BUY),

        new ActionCard(R.id.cardScrapHistory,         "scrap_history",      NavigationManager.TAG_SCRAP_HISTORY),
        new ActionCard(R.id.cardScrapBatches,         "scrap_batches",      NavigationManager.TAG_SCRAP_BATCHES),
        new ActionCard(R.id.cardScrapAging,           "scrap_aging",        NavigationManager.TAG_SCRAP_AGING),
        new ActionCard(R.id.cardParties,              "parties",            NavigationManager.TAG_PARTIES),

        new ActionCard(R.id.cardCashReconciliation,   "cash_reconciliation",NavigationManager.TAG_CASH_RECONCILIATION),
        new ActionCard(R.id.cardAdminHub,             "admin_hub",          NavigationManager.TAG_ADMIN_HUB),
        new ActionCard(R.id.cardServerConfig,         "server_config",      NavigationManager.TAG_SERVER_CONFIG),
        new ActionCard(R.id.cardAdminUsers,           "admin_users",        NavigationManager.TAG_USERS_MANAGEMENT),

        new ActionCard(R.id.cardAdminBranches,        "admin_branches",     NavigationManager.TAG_BRANCHES),
        new ActionCard(R.id.cardReaderSetup,          "reader_setup",       NavigationManager.TAG_READER_SETUP),
        new ActionCard(R.id.cardQuickAccess,          "quick_access",       NavigationManager.TAG_QUICK_ACCESS),
        new ActionCard(R.id.cardAuditLogs,            "audit_logs",         NavigationManager.TAG_AUDIT_LOGS),

        new ActionCard(R.id.cardPurchaseInvoice,      "purchase_invoice",   NavigationManager.TAG_PURCHASE_INVOICE, true),
    };

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

            setupPrimaryCta(view);
            setupLicenseStatusCard(view);
            rebuildGridWithPermissions(view);   // ⭐ الجديد

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

    // ═══════════════════════════════════════════════════════════
    //  ⭐ إعادة بناء GridLayout — لا فراغات
    // ═══════════════════════════════════════════════════════════
    private void rebuildGridWithPermissions(View root) {
        ViewGroup grid = root.findViewById(R.id.qaGrid);
        if (grid == null) {
            Log.w(TAG, "qaGrid not found — skipping");
            return;
        }

        // 1) المرور على كل بطاقة: فحص + إعداد click + تصنيف
        List<View> allowedCards = new ArrayList<>();
        List<View> deniedCards  = new ArrayList<>();

        for (ActionCard cfg : CARD_CONFIGS) {
            View card = root.findViewById(cfg.viewId);
            if (card == null) {
                Log.d(TAG, "Card not found: " + cfg.actionKey);
                continue;
            }

            if (isCardAllowed(cfg)) {
                // إعداد click
                if (card instanceof ViewGroup) {
                    ((ViewGroup) card).setDescendantFocusability(
                        ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                }
                card.setClickable(true);
                card.setFocusable(true);
                card.setOnClickListener(v -> {
                    performHaptic();
                    Fragment target = FragmentFactory.createFragmentByTag(cfg.navTag);
                    navigateTo(cfg.navTag, target);
                });
                allowedCards.add(card);
                animatedViews.add(card);
            } else {
                deniedCards.add(card);
            }
        }

        // 2) حذف كل شيء من Grid (الممنوع والمسموح) — نبدأ من صفحة بيضاء
        for (View v : deniedCards) {
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
        }
        for (View v : allowedCards) {
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
        }

        // 3) إعادة إضافة المسموح فقط بالترتيب — GridLayout يُوزّع تلقائياً
        int marginPx = (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, GRID_MARGIN_DP,
            getResources().getDisplayMetrics()
        );

        for (View card : allowedCards) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width      = 0;                                // ← يملأ عرض العمود
            lp.height     = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);   // ← weight=1
            lp.rowSpec    = GridLayout.spec(GridLayout.UNDEFINED);       // ← auto-row
            lp.setMargins(marginPx, marginPx, marginPx, marginPx);

            card.setLayoutParams(lp);
            card.setVisibility(View.VISIBLE);
            grid.addView(card);
        }

        Log.i(TAG, "Grid rebuilt: " + allowedCards.size() + " visible, "
            + deniedCards.size() + " hidden");
    }

    /**
     * فحص صلاحية موحّد:
     *   1) alwaysAllowed → مسموح دائماً
     *   2) canAccessFragment (في PermissionManager)
     *   3) إعدادات QuickAccess (في MiraQuickAccessSettings)
     */
    private boolean isCardAllowed(ActionCard cfg) {
        if (cfg.alwaysAllowed) return true;

        boolean hasPermission = permissionManager.canAccessFragment(cfg.navTag);
        boolean quickAccessAllowed = (quickAccessSettings == null)
            || quickAccessSettings.isActionEnabled(cfg.actionKey);

        return hasPermission && quickAccessAllowed;
    }

    // ═══════════════════════════════════════════════════════════
    //  Primary CTA (Identity Studio)
    // ═══════════════════════════════════════════════════════════
    private void setupPrimaryCta(View view) {
        try {
            View cta = view.findViewById(R.id.cardIdentityStudio);
            if (cta == null) return;

            if (cta instanceof ViewGroup) {
                ((ViewGroup) cta).setDescendantFocusability(
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            cta.setClickable(true);
            cta.setFocusable(true);

            boolean hasPermission = permissionManager
                .canAccessFragment(NavigationManager.TAG_IDENTITY_STUDIO);
            boolean quickAccessAllowed = (quickAccessSettings == null)
                || quickAccessSettings.isActionEnabled("identity");

            if (!hasPermission || !quickAccessAllowed) {
                cta.setVisibility(View.GONE);
                return;
            }

            cta.setVisibility(View.VISIBLE);
            cta.setOnClickListener(v -> {
                performHaptic();
                navigateTo(NavigationManager.TAG_IDENTITY_STUDIO,
                    new MiraIdentityStudioFragment());
            });
            animatedViews.add(cta);
        } catch (Exception e) {
            Log.e(TAG, "setupPrimaryCta error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  License Status Card
    // ═══════════════════════════════════════════════════════════
    private void setupLicenseStatusCard(View view) {
        try {
            View card = view.findViewById(R.id.cardLicenseStatus);
            if (card == null) return;

            if (card instanceof ViewGroup) {
                ((ViewGroup) card).setDescendantFocusability(
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            card.setClickable(true);
            card.setFocusable(true);

            boolean canSee = permissionManager.isAdmin()
                          || permissionManager.isManager();
            if (!canSee) {
                card.setVisibility(View.GONE);
                return;
            }

            card.setVisibility(View.VISIBLE);
            animatedViews.add(card);

            card.setOnClickListener(v -> {
                performHaptic();
                try {
                    startActivity(new Intent(requireContext(),
                        LicenseStatusActivity.class));
                    dismiss();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open LicenseStatusActivity", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "setupLicenseStatusCard error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Navigation
    // ═══════════════════════════════════════════════════════════
    private void navigateTo(String tag, @Nullable Fragment fallbackFragment) {
        if (tag == null || tag.isEmpty()) return;

        final UHFMainActivity activity = (getActivity() instanceof UHFMainActivity)
            ? (UHFMainActivity) getActivity()
            : null;
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
            if (root != null) {
                root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  Animation
    // ═══════════════════════════════════════════════════════════
    private void runStaggerAnimation() {
        try {
            if (animatedViews.isEmpty()) return;

            android.animation.AnimatorSet masterSet =
                new android.animation.AnimatorSet();
            List<android.animation.Animator> animators = new ArrayList<>();
            long delay = INITIAL_DELAY_MS;

            for (View v : animatedViews) {
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                if (v.getParent() == null) continue;   // ← تخطّي غير المُضاف

                v.setAlpha(0f);
                v.setTranslationY(16f);

                android.animation.ObjectAnimator alpha =
                    android.animation.ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
                alpha.setDuration(ITEM_FADE_DURATION_MS);
                alpha.setStartDelay(delay);

                android.animation.ObjectAnimator translate =
                    android.animation.ObjectAnimator.ofFloat(v, "translationY", 16f, 0f);
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
            for (View v : animatedViews) {
                if (v != null) {
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                }
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            View bottomSheet = (getDialog() != null)
                ? getDialog().findViewById(
                    com.google.android.material.R.id.design_bottom_sheet)
                : null;
            if (bottomSheet != null) {
                bottomSheet.getLayoutParams().height =
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.90);
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
