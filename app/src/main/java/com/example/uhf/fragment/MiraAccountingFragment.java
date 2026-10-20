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

import java.util.Stack;

/**
 * ✅ الشاشة الرئيسية للنظام المحاسبي
 */
public class MiraAccountingFragment extends Fragment {

    private static final String TAG = "MiraAccountingFragment";
    private static final String KEY_CURRENT_TAB = "current_tab";
    private static final String KEY_TAB_HISTORY = "tab_history";

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

    // ✅ 1. إضافة سجل للتبويبات (Stack) لحفظ مسار التنقل
    private final Stack<Integer> tabHistory = new Stack<>();
    private boolean isNavigatingBack = false; // لمنع تكرار الحفظ عند الرجوع

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

        // ✅ 2. استعادة الحالة (التبويب الحالي وسجل التبويبات) عند تدوير الشاشة أو إعادة إنشائها
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

        // ✅ 3. معالجة زر الرجوع بالاعتماد على سجل التبويبات
        requireActivity().getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    // إذا كان هناك تبويبات سابقة في السجل، ارجع إليها
                    if (!tabHistory.isEmpty()) {
                        int previousTab = tabHistory.pop();
                        TabLayout.Tab tab = tabLayout != null ? tabLayout.getTabAt(previousTab) : null;
                        if (tab != null) {
                            isNavigatingBack = true; // تفعيل وضع الرجوع
                            tab.select();
                            isNavigatingBack = false; // إيقاف وضع الرجوع
                            return;
                        }
                    }

                    // إذا كان السجل فارغاً (نحن في الجذر)، انتقل للداشبورد
                    if (getActivity() instanceof UHFMainActivity) {
                        UHFMainActivity act = (UHFMainActivity) getActivity();
                        act.loadFragmentDirectly(
                            new MiraDashboardFragment(),
                            "Dashboard");
                    }
                }
            });
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
        
        // ✅ 4. حفظ سجل التبويبات عند تدوير الشاشة
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
                    // ✅ 5. إذا لم نكن في وضع "الرجوع"، قم بحفظ التبويب الحالي في السجل
                    if (!isNavigatingBack) {
                        tabHistory.push(currentTabIndex); 
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
                    if (pendingLedgerAccountId != null && pendingLedgerAccountId > 0) {
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

    public void navigateToTab(int tabIndex) {
        if (tabIndex < 0 || tabIndex > TAB_LEDGER) return;
        if (tabLayout == null) return;
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
            loadTab(TAB_LEDGER);
        } else {
            // هنا سيتم استدعاء onTabSelected وسيقوم بحفظ تبويب "الحسابات" في الـ History تلقائياً
            ledgerTab.select();
        }
    }
}
