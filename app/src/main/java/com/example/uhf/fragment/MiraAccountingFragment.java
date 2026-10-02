package com.example.uhf.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.tools.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.tabs.TabLayout;

/**
 * الشاشة الرئيسية للنظام المحاسبي
 * 
 * تحتوي على 7 تبويبات:
 * 1. 📋 القيود اليومية
 * 2. ⚖️ ميزان المراجعة
 * 3. 📊 دليل الحسابات
 * 4. 📈 قائمة الدخل
 * 5. 💰 الميزانية العمومية
 * 6. 🥇 حركة الذهب
 * 7. 📖 دفتر الأستاذ
 * 
 * @version 2.0.0
 */
public class MiraAccountingFragment extends Fragment {

    private static final String TAG = "MiraAccountingFragment";
    private static final String KEY_CURRENT_TAB = "current_tab";

    // View
    private TabLayout tabLayout;
    private FrameLayout fragmentContainer;

    // Session
    private PermissionManager permissionManager;
    private SessionManager sessionManager;
    private boolean isArabic = true;

    // State
    private int currentTabIndex = 0;
    private Fragment currentFragment;

    // Tab definitions
    private static final int TAB_JOURNAL = 0;
    private static final int TAB_TRIAL = 1;
    private static final int TAB_ACCOUNTS = 2;
    private static final int TAB_INCOME = 3;
    private static final int TAB_BALANCE = 4;
    private static final int TAB_GOLD = 5;
    private static final int TAB_LEDGER = 6;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getContext() != null) {
            permissionManager = PermissionManager.getInstance(requireContext());
            sessionManager = new SessionManager(requireContext());
            isArabic = "ar".equals(java.util.Locale.getDefault().getLanguage());
        }

        // استرجاع التبويب الحالي
        if (savedInstanceState != null) {
            currentTabIndex = savedInstanceState.getInt(KEY_CURRENT_TAB, 0);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting_v2, container, false);

        initViews(view);
        checkPermissions();
        setupTabs();
        loadTab(currentTabIndex);

        return view;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_CURRENT_TAB, currentTabIndex);
    }

    // ═══════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════

    private void initViews(View view) {
        tabLayout = view.findViewById(R.id.tabAccounting);
        fragmentContainer = view.findViewById(R.id.fragmentContainer);

        if (tabLayout == null) {
            Log.e(TAG, "tabLayout not found in layout!");
        }
        if (fragmentContainer == null) {
            Log.e(TAG, "fragmentContainer not found in layout!");
        }
    }

    /**
     * فحص الصلاحيات
     */
    private void checkPermissions() {
        if (permissionManager == null) return;

        boolean hasAccess = permissionManager.canView("accounting")
            || permissionManager.isAdmin()
            || permissionManager.isBranchManager()
            || "AUDITOR".equals(sessionManager != null ? sessionManager.getRoleString() : "");

        if (!hasAccess) {
            Toast.makeText(requireContext(),
                isArabic ? "⛔ غير مصرح لك بالوصول للمحاسبة" : "⛔ Access Denied",
                Toast.LENGTH_LONG).show();
            // يمكن إغلاق الشاشة
            requireActivity().onBackPressed();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Tabs Setup
    // ═══════════════════════════════════════════════════════════

    private void setupTabs() {
        if (tabLayout == null) return;

        // إزالة أي تبويبات سابقة
        tabLayout.removeAllTabs();

        // إضافة التبويبات
        addTab("📋 القيود", "📋 Journal");
        addTab("⚖️ الميزان", "⚖️ Trial Balance");
        addTab("📊 الحسابات", "📊 Accounts");
        addTab("📈 الدخل", "📈 Income");
        addTab("💰 الميزانية", "💰 Balance");
        addTab("🥇 الذهب", "🥇 Gold");
        addTab("📖 الأستاذ", "📖 Ledger");

        // التبويب الحالي
        TabLayout.Tab initialTab = tabLayout.getTabAt(currentTabIndex);
        if (initialTab != null) {
            initialTab.select();
        }

        // مستمع التغيير
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position != currentTabIndex) {
                    currentTabIndex = position;
                    loadTab(position);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // لا شيء
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // إعادة التحميل عند الضغط مرة أخرى
                loadTab(tab.getPosition());
            }
        });
    }

    /**
     * إضافة تبويب جديد مع دعم اللغة
     */
    private void addTab(String arText, String enText) {
        if (tabLayout == null) return;
        TabLayout.Tab tab = tabLayout.newTab();
        tab.setText(isArabic ? arText : enText);
        tabLayout.addTab(tab);
    }

    // ═══════════════════════════════════════════════════════════
    //  Tab Loading
    // ═══════════════════════════════════════════════════════════

    /**
     * تحميل الشاشة المناسبة للتبويب المحدد
     */
    private void loadTab(int tabIndex) {
        if (fragmentContainer == null) return;

        Fragment targetFragment;

        switch (tabIndex) {
            case TAB_JOURNAL:
                targetFragment = new MiraJournalFragment();
                break;

            case TAB_TRIAL:
                targetFragment = new MiraTrialBalanceFragment();
                break;

            case TAB_ACCOUNTS:
                targetFragment = new MiraAccountTreeFragment();
                break;

            case TAB_INCOME:
                targetFragment = new MiraIncomeStatementFragment();
                break;

            case TAB_BALANCE:
                targetFragment = new MiraBalanceSheetFragment();
                break;

            case TAB_GOLD:
                targetFragment = new MiraGoldMovementFragment();
                break;

            case TAB_LEDGER:
                targetFragment = new MiraGeneralLedgerFragment();
                break;

            default:
                targetFragment = new MiraJournalFragment();
                break;
        }

        // استبدال الشاشة الحالية
        if (targetFragment != null && isAdded() && !isDetached()) {
            try {
                FragmentTransaction transaction = getChildFragmentManager().beginTransaction();

                // انتقال ناعم
                transaction.setCustomAnimations(
                    android.R.anim.fade_in,
                    android.R.anim.fade_out
                );

                transaction.replace(R.id.fragmentContainer, targetFragment);
                transaction.commit();

                currentFragment = targetFragment;

                Log.d(TAG, "Loaded tab " + tabIndex + ": " + targetFragment.getClass().getSimpleName());

            } catch (Exception e) {
                Log.e(TAG, "Error loading fragment", e);
                Toast.makeText(requireContext(), "❌ خطأ في تحميل الشاشة", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * التنقل إلى تبويب محدد برمجياً
     */
    public void navigateToTab(int tabIndex) {
        if (tabIndex < 0 || tabIndex > TAB_LEDGER) return;
        if (tabLayout == null) return;

        TabLayout.Tab tab = tabLayout.getTabAt(tabIndex);
        if (tab != null) {
            tab.select();
        }
    }

    /**
     * التنقل إلى دفتر أستاذ حساب محدد
     */
    public void navigateToGeneralLedger(long accountId) {
        Bundle args = new Bundle();
        args.putLong("account_id", accountId);

        MiraGeneralLedgerFragment glFragment = new MiraGeneralLedgerFragment();
        glFragment.setArguments(args);

        if (fragmentContainer != null && isAdded()) {
            getChildFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, glFragment)
                .addToBackStack(null)
                .commit();
        }
    }

    /**
     * التنقل إلى تفاصيل قيد محدد
     */
    public void navigateToJournalDetail(long entryId) {
        Bundle args = new Bundle();
        args.putLong("entry_id", entryId);

        MiraJournalDetailFragment detailFragment = new MiraJournalDetailFragment();
        detailFragment.setArguments(args);

        if (isAdded()) {
            requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, detailFragment)
                .addToBackStack(null)
                .commit();
        }
    }

    /**
     * إعادة تحميل التبويب الحالي
     */
    public void refreshCurrentTab() {
        loadTab(currentTabIndex);
    }

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle - إضافية
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onResume() {
        super.onResume();
        // يمكن إعادة تحميل البيانات عند العودة
        Log.d(TAG, "onResume - current tab: " + currentTabIndex);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // تنظيف
        tabLayout = null;
        fragmentContainer = null;
        currentFragment = null;
    }
}
