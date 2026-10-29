package com.example.uhf.accounting;

import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraGeneralLedgerFragment;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public class MiraAccountingFragmentNew extends Fragment {

    private static final String TAG = "MiraAccountingNew";
    private static final String KEY_SCREEN_ID = "current_screen_id";
    private static final String KEY_HISTORY = "screen_history";

    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private MaterialToolbar toolbar;
    private FrameLayout contentContainer;

    private String currentScreenId = MiraAccountingScreens.ID_DASHBOARD;
    private final Deque<String> history = new ArrayDeque<>();
    private boolean isNavigating = false;
    private boolean isArabic = true;

    private PermissionManager permissionManager;
    private SessionManager sessionManager;

    private OnBackPressedCallback backCallback;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ 1. منع الـ Activity من إضافة قائمته (هذا سبب التكرار)
        setHasOptionsMenu(false); 

        if (getContext() != null) {
            try {
                permissionManager = PermissionManager.getInstance(requireContext());
                sessionManager = new SessionManager(requireContext());
            } catch (Exception e) {
                Log.e(TAG, "deps init: " + e.getMessage());
            }
            isArabic = "ar".equals(Locale.getDefault().getLanguage());
        }

        backCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPress();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_accounting, container, false);
        drawerLayout = v.findViewById(R.id.drawerLayout);
        navView = v.findViewById(R.id.navView);
        toolbar = v.findViewById(R.id.accountingToolbar);
        contentContainer = v.findViewById(R.id.accountingContent);

        if (drawerLayout == null || navView == null || toolbar == null || contentContainer == null) {
            Log.e(TAG, "❌ Missing views in fragment_mira_accounting.xml");
            return v;
        }

        setupStandardToolbar();

        MiraAccountingNavDrawerHelper.build(navView, isArabic, permissionManager,
                screen -> navigateTo(screen.id, true));

        boolean permanent = readPermanentDrawerFlag();
        if (permanent) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN);
            drawerLayout.setScrimColor(Color.TRANSPARENT);
        }

        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        // ✅ 2. إخفاء الشريط الأصفر وشريط التنقل السفلي
        if (getActivity() != null) {
            // إخفاء الـ AppBar (الشريط الأصفر)
            View appBar = getActivity().findViewById(R.id.appBarLayout);
            if (appBar != null) appBar.setVisibility(View.GONE);
            
            // إخفاء شريط التنقل السفلي
            View bottomNav = getActivity().findViewById(R.id.bottomNavContainer);
            if (bottomNav != null) bottomNav.setVisibility(View.GONE);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (getActivity() != null) {
            View appBar = getActivity().findViewById(R.id.appBarLayout);
            if (appBar != null) appBar.setVisibility(View.VISIBLE);
            
            View bottomNav = getActivity().findViewById(R.id.bottomNavContainer);
            if (bottomNav != null) bottomNav.setVisibility(View.VISIBLE);
        }
    }

    private void setupStandardToolbar() {
        // ✅ 3. تنظيف القائمة تماماً لمنع تكرار (تحديث)
        toolbar.getMenu().clear();
        
        // 4. إعداد أيقونة القائمة (Hamburger)
        toolbar.setNavigationIcon(R.drawable.ic_menu); 
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout != null) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        // 5. جلب اسم الجهة
        String orgName = "MIRA TECHNOLOGY";
        try {
            MiraOrgProfileManager profileMgr = MiraOrgProfileManager.getInstance(requireContext());
            String name = profileMgr.getDisplayName();
            if (name != null && !name.isEmpty()) {
                orgName = name;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load org name");
        }

        // 6. إعداد العنوان والفرع
        toolbar.setTitle(isArabic ? "لوحة التحكم" : "Dashboard");
        toolbar.setSubtitle(orgName);

        // 7. تحميل القائمة
        toolbar.inflateMenu(R.menu.menu_accounting_toolbar);
        
        // 8. استقبال ضغطات الأزرار
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            
            if (id == R.id.action_refresh) {
                refreshCurrentTab(); 
                Toast.makeText(requireContext(), isArabic ? "جاري التحديث..." : "Refreshing...", Toast.LENGTH_SHORT).show();
                return true;
            } 
            else if (id == R.id.action_profile) {
                Toast.makeText(requireContext(), isArabic ? "الملف الشخصي" : "Profile", Toast.LENGTH_SHORT).show();
                return true;
            }
            else if (id == R.id.action_exit) {
                exitAccounting();
                return true;
            }
            return false;
        });
    }

    private void exitAccounting() {
        if (backCallback != null) backCallback.setEnabled(false);
        requireActivity().getOnBackPressedDispatcher().onBackPressed();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String startId = MiraAccountingScreens.defaultScreenId();
        if (savedInstanceState != null) {
            startId = savedInstanceState.getString(KEY_SCREEN_ID, startId);
            String[] arr = savedInstanceState.getStringArray(KEY_HISTORY);
            if (arr != null) {
                history.clear();
                for (int i = arr.length - 1; i >= 0; i--) {
                    history.push(arr[i]);
                }
            }
        }

        navigateTo(startId, false);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(KEY_SCREEN_ID, currentScreenId);
        out.putStringArray(KEY_HISTORY, history.toArray(new String[0]));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        drawerLayout = null;
        navView = null;
        toolbar = null;
        contentContainer = null;
    }

    private void navigateTo(@NonNull String screenId, boolean pushHistory) {
        if (contentContainer == null || isNavigating) return;

        MiraAccountingScreens.Screen target = MiraAccountingScreens.byId(screenId);
        if (target == null) return;
        if (!canOpen(target)) {
            Toast.makeText(requireContext(), isArabic ? "⛔ غير مصرح بالوصول" : "⛔ Access denied", Toast.LENGTH_SHORT).show();
            return;
        }

        Fragment existing = getChildFragmentManager().findFragmentByTag("SCR_" + screenId);
        if (screenId.equals(currentScreenId) && existing != null) {
            closeDrawerIfOpen();
            return;
        }

        isNavigating = true;
        try {
            if (pushHistory && !currentScreenId.equals(screenId)) {
                history.push(currentScreenId);
            }
            currentScreenId = screenId;

            FragmentTransaction tx = getChildFragmentManager()
                    .beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);

            for (Fragment f : getChildFragmentManager().getFragments()) {
                if (f != existing && f.isVisible()) tx.hide(f);
            }

            if (existing != null) {
                tx.show(existing);
            } else {
                Fragment fresh = target.factory.create();
                if (fresh == null) return;
                tx.add(R.id.accountingContent, fresh, "SCR_" + screenId);
            }

            if (!getChildFragmentManager().isStateSaved()) {
                tx.commitAllowingStateLoss();
            }

            if (toolbar != null) {
                toolbar.setTitle(target.title(isArabic));
            }
            
            MiraAccountingNavDrawerHelper.setChecked(navView, screenId);
            closeDrawerIfOpen();
        } finally {
            isNavigating = false;
        }
    }

    private void closeDrawerIfOpen() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    private void handleBackPress() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        if (!history.isEmpty()) {
            String prev = history.pop();
            navigateTo(prev, false);
            return;
        }
        if (MiraAccountingScreens.ID_DASHBOARD.equals(currentScreenId)) {
            if (backCallback != null) backCallback.setEnabled(false);
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return;
        }
        navigateTo(MiraAccountingScreens.ID_DASHBOARD, false);
    }

    private boolean canOpen(@NonNull MiraAccountingScreens.Screen screen) {
        if (permissionManager == null) return true;
        try {
            boolean base = permissionManager.canView(MiraAccountingScreens.BASE_PERMISSION)
                    || permissionManager.isAdmin()
                    || permissionManager.isBranchManager()
                    || permissionManager.isAccountant()
                    || permissionManager.isFinanceManager()
                    || (sessionManager != null && "AUDITOR".equals(sessionManager.getRoleString()));
            if (!base) return false;
            
            if (permissionManager.isAccountant() || permissionManager.isFinanceManager()) {
                return true;
            }
            return permissionManager.isAdmin() || permissionManager.canView(screen.permissionKey);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean readPermanentDrawerFlag() {
        try {
            return getResources().getBoolean(R.bool.accounting_permanent_drawer);
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    @Deprecated
    public void navigateToTab(int tabIndex) {
        MiraAccountingScreens.Screen s = MiraAccountingScreens.byLegacyTab(tabIndex);
        if (s != null) navigateTo(s.id, true);
    }

    public void navigateToScreen(@NonNull String screenId) {
        navigateTo(screenId, true);
    }

    public void navigateToLedgerWithAccount(long accountId) {
        Fragment ledger = getChildFragmentManager().findFragmentByTag("SCR_" + MiraAccountingScreens.ID_GENERAL_LEDGER);
        if (ledger instanceof MiraGeneralLedgerFragment) {
            ((MiraGeneralLedgerFragment) ledger).setAccountId(accountId);
        }
        navigateTo(MiraAccountingScreens.ID_GENERAL_LEDGER, true);
        Fragment now = getChildFragmentManager().findFragmentByTag("SCR_" + MiraAccountingScreens.ID_GENERAL_LEDGER);
        if (now instanceof MiraGeneralLedgerFragment && now != ledger) {
            ((MiraGeneralLedgerFragment) now).setAccountId(accountId);
        }
    }

    public void refreshCurrentTab() {
        Fragment current = getChildFragmentManager().findFragmentByTag("SCR_" + currentScreenId);
        if (current instanceof MiraRefreshable) {
            try { ((MiraRefreshable) current).refresh(); } catch (Exception e) { Log.e(TAG, "refresh: " + e.getMessage()); }
        }
    }

    @NonNull
    public String getCurrentScreenId() { return currentScreenId; }

    public interface MiraRefreshable { void refresh(); }
}
