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
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.tabs.TabLayout;

import java.util.Stack;

public class MiraAccountingFragment extends Fragment {

    private static final String TAG = "MiraAccountingFragment";
    private static final String KEY_CURRENT_TAB = "current_tab";
    private static final String KEY_TAB_HISTORY = "tab_history";

    // ═══════════════════════════════════════════════════════
    //  تعريف التبويبات
    // ═══════════════════════════════════════════════════════
    private static final int TAB_JOURNAL     = 0;
    private static final int TAB_TRIAL       = 1;
    private static final int TAB_ACCOUNTS    = 2;
    private static final int TAB_INCOME      = 3;
    private static final int TAB_BALANCE     = 4;
    private static final int TAB_GOLD        = 5;
    private static final int TAB_LEDGER      = 6;
    private static final int TAB_ADJUSTING   = 7;
    private static final int TAB_CASH        = 8;
    private static final int TAB_AGING       = 9;
    private static final int TAB_FISCAL      = 10;
    private static final int TAB_PROFIT      = 11; // ✅ تبويب تحليل الأرباح الجديد
    private static final int TAB_REVALUATION = 12; // ✅ تبويب إعادة تقييم المخزون الجديد
    private static final int MAX_TABS        = 13;

    private TabLayout tabLayout;
    private FrameLayout tabContentContainer;

    private PermissionManager permissionManager;
    private SessionManager sessionManager;
    private boolean isArabic = true;
    private int currentTabIndex = 0;
    
    private long pendingLedgerAccountId = 0;

    private final Stack<Integer> tabHistory = new Stack<>();
    private boolean isNavigatingBack = false;

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
            int[] historyArray = savedInstanceState.getIntArray(KEY_TAB_HISTORY);
            if (historyArray != null) {
                tabHistory.clear();
                for (int tab : historyArray) {
                    tabHistory.push(tab);
                }
            }
        }

        requireActivity().getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (!tabHistory.isEmpty()) {
                        int previousTab = tabHistory.pop();
                        TabLayout.Tab tab = tabLayout != null ? tabLayout.getTabAt(previousTab) : null;
                        if (tab != null) {
                            isNavigatingBack = true;
                            tab.select();
                            isNavigatingBack = false;
                            return;
                        }
                    }

                    if (getActivity() instanceof UHFMainActivity) {
                        UHFMainActivity act = (UHFMainActivity) getActivity();
                        act.loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
                    }
                }
            });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting_v2, container, false);

        initViews(view);

        if (tabLayout == null || tabContentContainer == null) {
            Log.e(TAG, "❌ Critical views not found!");
            return view;
        }

        if (!hasPermission()) {
            return view; 
        }

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
        
        int[] historyArray = new int[tabHistory.size()];
        for (int i = 0; i < tabHistory.size(); i++) {
            historyArray[i] = tabHistory.get(i);
        }
        outState.putIntArray(KEY_TAB_HISTORY, historyArray);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        tabLayout = null;
        tabContentContainer = null;
    }

    private void initViews(View view) {
        tabLayout           = view.findViewById(R.id.tabAccounting);
        tabContentContainer = view.findViewById(R.id.tabContentContainer);
    }

    private boolean hasPermission() {
        if (permissionManager == null) return true;
        try {
            boolean hasAccess = permissionManager.canView("accounting")
                || permissionManager.isAdmin()
                || permissionManager.isBranchManager()
                || (sessionManager != null && "AUDITOR".equals(sessionManager.getRoleString()));

            if (!hasAccess) {
                Toast.makeText(requireContext(),
                    isArabic ? "⛔ غير مصرح لك بالوصول للمحاسبة" : "⛔ Access Denied",
                    Toast.LENGTH_LONG).show();
                
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "checkPermissions error: " + e.getMessage());
            return true; 
        }
    }

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
        addTab("💵 الخزينة",    "💵 Cash");
        addTab("⏳ الديون",     "⏳ Aging");
        addTab("🔒 الإقفال",    "🔒 Closing");
        addTab("📊 الأرباح",    "📊 Profit");     // ✅ التبويب الجديد
        addTab("⚖️ التقييم",    "⚖️ Revaluation"); // ✅ التبويب الجديد

        TabLayout.Tab initialTab = tabLayout.getTabAt(currentTabIndex);
        if (initialTab != null) initialTab.select();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position != currentTabIndex) {
                    if (!isNavigatingBack) {
                        if (tabHistory.isEmpty() || tabHistory.peek() != currentTabIndex) {
                            tabHistory.push(currentTabIndex);
                        }
                    }
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

    private void loadTab(int tabIndex) {
        if (tabContentContainer == null || !isAdded() || isDetached()) return;

        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        String targetTag = "TAB_" + tabIndex;

        for (int i = 0; i < MAX_TABS; i++) {
            if (i == tabIndex) continue;
            Fragment f = getChildFragmentManager().findFragmentByTag("TAB_" + i);
            if (f != null) {
                transaction.hide(f);
            }
        }

        Fragment targetFragment = getChildFragmentManager().findFragmentByTag(targetTag);

        if (targetFragment == null) {
            targetFragment = createFragmentForTab(tabIndex);
            if (targetFragment == null) return;
            transaction.add(R.id.tabContentContainer, targetFragment, targetTag);
        } else {
            transaction.show(targetFragment);

            if (targetFragment instanceof MiraGeneralLedgerFragment && pendingLedgerAccountId > 0) {
                ((MiraGeneralLedgerFragment) targetFragment).setAccountId(pendingLedgerAccountId);
                pendingLedgerAccountId = 0;
            }
        }

        try {
            if (!getChildFragmentManager().isStateSaved()) {
                transaction.commitAllowingStateLoss();
            }
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
                    if (pendingLedgerAccountId > 0) {
                        f.setAccountId(pendingLedgerAccountId);
                        pendingLedgerAccountId = 0;
                    }
                    return f;
                }
                case TAB_ADJUSTING:
                    return new MiraAdjustingEntryFragment();

                case TAB_CASH: 
                    return new MiraCashReconciliationFragment();

                case TAB_AGING: 
                    return new MiraAgingReportFragment();

                case TAB_FISCAL: 
                    return new MiraFiscalClosingFragment();

                case TAB_PROFIT: // ✅ ربط تبويب تحليل الأرباح
                    return new MiraProfitAnalysisFragment();

                case TAB_REVALUATION: // ✅ ربط تبويب إعادة التقييم
                    return new MiraInventoryRevaluationFragment();

                default: return new MiraJournalFragment();
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot create fragment for tab " + tabIndex, e);
            return null;
        }
    }

    public void navigateToTab(int tabIndex) {
        // ✅ السماح بالوصول حتى آخر تبويب (التقييم)
        if (tabIndex < 0 || tabIndex > TAB_REVALUATION || tabLayout == null) return;
        TabLayout.Tab tab = tabLayout.getTabAt(tabIndex);
        if (tab != null) tab.select();
    }

    public void refreshCurrentTab() {
        loadTab(currentTabIndex);
    }

    public void navigateToLedgerWithAccount(long accountId) {
        this.pendingLedgerAccountId = accountId;

        if (tabLayout == null) return;

        TabLayout.Tab ledgerTab = tabLayout.getTabAt(TAB_LEDGER);
        if (ledgerTab == null) return;

        if (currentTabIndex == TAB_LEDGER) {
            Fragment f = getChildFragmentManager().findFragmentByTag("TAB_" + TAB_LEDGER);
            if (f instanceof MiraGeneralLedgerFragment) {
                ((MiraGeneralLedgerFragment) f).setAccountId(accountId);
                pendingLedgerAccountId = 0;
            }
        } else {
            ledgerTab.select();
        }
    }
}
