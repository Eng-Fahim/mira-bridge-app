package com.example.uhf.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.tabs.TabLayout;

/**
 * ✅ الشاشة الرئيسية للنظام المحاسبي
 * - 7 تبويبات
 * - معالجة زر الرجوع (يعود للتبويب الأول → ثم للداشبورد)
 */
public class MiraAccountingFragment extends Fragment {

    private static final String TAG = "MiraAccountingFragment";
    private static final String KEY_CURRENT_TAB = "current_tab";

    private static final int TAB_JOURNAL  = 0;
    private static final int TAB_TRIAL    = 1;
    private static final int TAB_ACCOUNTS = 2;
    private static final int TAB_INCOME   = 3;
    private static final int TAB_BALANCE  = 4;
    private static final int TAB_GOLD     = 5;
    private static final int TAB_LEDGER   = 6;
    private static final int TAB_ADJUSTING = 7;

    private TabLayout tabLayout;
    private FrameLayout tabContentContainer;

    private PermissionManager permissionManager;
    private SessionManager sessionManager;
    private boolean isArabic = true;
    private int currentTabIndex = 0;
    private Long pendingLedgerAccountId = null;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Override
public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (getContext() != null) {
        try {
            permissionManager = PermissionManager.getInstance(requireContext());
            sessionManager = new SessionManager(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "Session init error: " + e.getMessage());
        }
        isArabic = "ar".equals(java.util.Locale.getDefault().getLanguage());
    }

    if (savedInstanceState != null) {
        currentTabIndex = savedInstanceState.getInt(KEY_CURRENT_TAB, 0);
    }

    // ✅ معالجة زر الرجوع — بدون re-dispatch
    requireActivity().getOnBackPressedDispatcher().addCallback(
        this,
        new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // الحالة 1: لسنا في القيود → ارجع للقيود
                if (currentTabIndex != TAB_JOURNAL) {
                    TabLayout.Tab firstTab = tabLayout != null
                        ? tabLayout.getTabAt(TAB_JOURNAL) : null;
                    if (firstTab != null) firstTab.select();
                    return;
                }

                // الحالة 2: نحن في القيود → انتقل للداشبورد مباشرة
                if (getActivity() instanceof UHFMainActivity) {
                    UHFMainActivity act = (UHFMainActivity) getActivity();
                    act.loadFragmentDirectly(
                        new MiraDashboardFragment(),
                        "Dashboard");
                }
            }
        });
}
    /**
     * ✅ منطق زر الرجوع:
     * - إذا لسنا في القيود → ارجع للقيود
     * - إذا كنا في القيود → ارجع للداشبورد (UHFMainActivity)
     */
    private void handleBackPress() {
        if (currentTabIndex != TAB_JOURNAL) {
            TabLayout.Tab firstTab = tabLayout != null
                ? tabLayout.getTabAt(TAB_JOURNAL) : null;
            if (firstTab != null) firstTab.select();
        } else {
            if (getActivity() instanceof UHFMainActivity) {
                // استدعاء الرجوع الافتراضي للـ Activity
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            } else if (getActivity() != null) {
                requireActivity().finish();
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting_v2,
                container, false);

        initViews(view);

        if (tabLayout == null || tabContentContainer == null) {
            Log.e(TAG, "❌ Critical views not found!");
            return view;
        }

        checkPermissions();
        setupTabs();

        if (savedInstanceState == null) {
            loadTab(currentTabIndex);
        }

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setBottomNavVisible(true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_CURRENT_TAB, currentTabIndex);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        tabLayout = null;
        tabContentContainer = null;
    }

    // ═══════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════

    private void initViews(View view) {
        tabLayout           = view.findViewById(R.id.tabAccounting);
        tabContentContainer = view.findViewById(R.id.tabContentContainer);
    }

    private void checkPermissions() {
        if (permissionManager == null) return;
        try {
            boolean hasAccess = permissionManager.canView("accounting")
                || permissionManager.isAdmin()
                || permissionManager.isBranchManager()
                || (sessionManager != null
                    && "AUDITOR".equals(sessionManager.getRoleString()));

            if (!hasAccess) {
                Toast.makeText(requireContext(),
                    isArabic ? "⛔ غير مصرح لك بالوصول للمحاسبة"
                             : "⛔ Access Denied",
                    Toast.LENGTH_LONG).show();
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "checkPermissions error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Tabs
    // ═══════════════════════════════════════════════════════════

    private void setupTabs() {
        if (tabLayout == null) return;

        tabLayout.removeAllTabs();
        addTab("📋 القيود",     "📋 Journal");
        addTab("⚖️ الميزان",    "⚖️ Trial");
        addTab("📊 الحسابات",   "📊 Accounts");
        addTab("📈 الدخل",      "📈 Income");
        addTab("💰 الميزانية",  "💰 Balance");
        addTab("🥇 الذهب",      "🥇 Gold");
        addTab("📖 الأستاذ",    "📖 Ledger");
        addTab("📝 التسويات",   "📝 Adjust");

        TabLayout.Tab initialTab = tabLayout.getTabAt(currentTabIndex);
        if (initialTab != null) initialTab.select();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position != currentTabIndex) {
                    currentTabIndex = position;
                    loadTab(position);
                }
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                loadTab(tab.getPosition());
            }
        });
    }

    private void addTab(String arText, String enText) {
        if (tabLayout == null) return;
        TabLayout.Tab tab = tabLayout.newTab();
        tab.setText(isArabic ? arText : enText);
        tabLayout.addTab(tab);
    }

    // ═══════════════════════════════════════════════════════════
    //  Tab Loading
    // ═══════════════════════════════════════════════════════════

    private void loadTab(int tabIndex) {
        if (tabContentContainer == null) {
            Log.e(TAG, "tabContentContainer is null");
            return;
        }

        if (!isAdded() || isDetached()) return;

        Fragment targetFragment = createFragmentForTab(tabIndex);
        if (targetFragment == null) return;

        try {
            getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.tabContentContainer, targetFragment)
                .commitAllowingStateLoss();

            Log.d(TAG, "✅ Tab " + tabIndex + " loaded: "
                + targetFragment.getClass().getSimpleName());

        } catch (Exception e) {
            Log.e(TAG, "Error loading tab " + tabIndex, e);
        }
    }

    private Fragment createFragmentForTab(int tabIndex) {
        try {
            switch (tabIndex) {
                case TAB_JOURNAL:  return new MiraJournalFragment();
                case TAB_TRIAL:    return new MiraTrialBalanceFragment();
                case TAB_ACCOUNTS: return new MiraAccountTreeFragment();
                case TAB_INCOME:   return new MiraIncomeStatementFragment();
                case TAB_BALANCE:  return new MiraBalanceSheetFragment();
                case TAB_GOLD:     return new MiraGoldMovementFragment();

                case TAB_LEDGER: {
                    MiraGeneralLedgerFragment f = new MiraGeneralLedgerFragment();
                    if (pendingLedgerAccountId != null
                            && pendingLedgerAccountId > 0) {
                        Bundle args = new Bundle();
                        args.putLong("account_id", pendingLedgerAccountId);
                        f.setArguments(args);
                        pendingLedgerAccountId = null;
                    }
                    return f;
                }
                case TAB_ADJUSTING:
                return new MiraAdjustingEntryFragment();

                default: return new MiraJournalFragment();
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot create fragment for tab " + tabIndex, e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    public void navigateToTab(int tabIndex) {
        if (tabIndex < 0 || tabIndex > TAB_LEDGER) return;
        if (tabLayout == null) return;
        TabLayout.Tab tab = tabLayout.getTabAt(tabIndex);
        if (tab != null) tab.select();
    }

    public void refreshCurrentTab() {
        loadTab(currentTabIndex);
    }

    /**
     * ✅ الانتقال لدفتر الأستاذ مع تمرير الحساب
     */
    public void navigateToLedgerWithAccount(long accountId) {
        this.pendingLedgerAccountId = accountId;

        if (tabLayout == null) return;

        TabLayout.Tab ledgerTab = tabLayout.getTabAt(TAB_LEDGER);
        if (ledgerTab == null) return;

        if (currentTabIndex == TAB_LEDGER) {
            loadTab(TAB_LEDGER);
        } else {
            ledgerTab.select();
        }
    }
}
