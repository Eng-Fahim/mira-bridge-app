package com.example.uhf.accounting;

import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
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
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.activity.LoginActivity;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.fragment.MiraGeneralLedgerFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * MIRA Accounting — New Container (Enterprise v2.5)
 * 
 * التحديثات:
 * - إضافة نافذة تأكيد احترافية عند الخروج من شاشة المحاسبة (زر X و زر الرجوع).
 */
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
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_mira_accounting, container, false);

        if (getActivity() != null) {
            View appBar = getActivity().findViewById(R.id.appBarLayout);
            if (appBar != null) appBar.setVisibility(View.GONE);
            
            View bottomNav = getActivity().findViewById(R.id.bottomNavContainer);
            if (bottomNav != null) bottomNav.setVisibility(View.GONE);
        }

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

        ViewCompat.requestApplyInsets(v);

        return v;
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
        toolbar.getMenu().clear();
        
        toolbar.setNavigationIcon(R.drawable.ic_menu); 
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout != null) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        String orgName = "MIRA TECHNOLOGY";
        try {
            MiraOrgProfileManager profileMgr = MiraOrgProfileManager.getInstance(requireContext());
            String name = profileMgr.getDisplayName();
            if (name != null && !name.isEmpty()) orgName = name;
        } catch (Exception e) { Log.w(TAG, "Failed to load org name"); }

        toolbar.setTitle(isArabic ? "لوحة التحكم" : "Dashboard");
        toolbar.setSubtitle(orgName);

        toolbar.inflateMenu(R.menu.menu_accounting_toolbar);
        
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            
            if (id == R.id.action_refresh) {
                refreshCurrentTab(); 
                Toast.makeText(requireContext(), isArabic ? "جاري التحديث..." : "Refreshing...", Toast.LENGTH_SHORT).show();
                return true;
            } 
            else if (id == R.id.action_profile) {
                openProfile();
                return true;
            }
            else if (id == R.id.action_logout) {
                performLogout();
                return true;
            }
            else if (id == R.id.action_exit) {
                showExitConfirmationDialog(); // ✅ استدعاء نافذة التأكيد
                return true;
            }
            return false;
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  Actions (Profile, Logout, Exit)
    // ═══════════════════════════════════════════════════════════

    private void openProfile() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity mainActivity = (UHFMainActivity) getActivity();
                mainActivity.loadFragment(new MiraProfileFragment(), "Profile");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening profile", e);
            Toast.makeText(requireContext(), "لا يمكن فتح الملف الشخصي", Toast.LENGTH_SHORT).show();
        }
    }

    private void performLogout() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "تسجيل الخروج" : "Logout")
                .setMessage(isArabic ? "هل أنت متأكد من رغبتك في تسجيل الخروج من حسابك؟" 
                                     : "Are you sure you want to log out of your account?")
                .setIcon(R.drawable.ic_logout)
                .setPositiveButton(isArabic ? "تسجيل الخروج" : "Logout", (dialog, which) -> {
                    try {
                        if (sessionManager != null) sessionManager.logoutUser();
                        if (permissionManager != null) permissionManager.clearPermissions();

                        Intent intent = new Intent(requireContext(), LoginActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        requireActivity().finish();
                    } catch (Exception e) {
                        Log.e(TAG, "Logout error", e);
                        Toast.makeText(requireContext(), "حدث خطأ أثناء الخروج", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    // ✅ دالة الخروج الاحترافية (تظهر نافذة تأكيد)
    private void showExitConfirmationDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "تأكيد الخروج من المحاسبة" : "Confirm Exit")
                .setMessage(isArabic 
                        ? "أنت على وشك الخروج من مركز المحاسبة والعودة إلى لوحة التحكم الرئيسية. هل تريد المتابعة؟" 
                        : "You are about to exit the Accounting Center and return to the main dashboard. Do you want to proceed?")
                .setIcon(android.R.drawable.ic_dialog_alert) // أيقونة تنبيه احترافية
                .setPositiveButton(isArabic ? "خروج" : "Exit", (dialog, which) -> {
                    if (backCallback != null) backCallback.setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    // ═══════════════════════════════════════════════════════════
    //  Navigation Logic
    // ═══════════════════════════════════════════════════════════

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

        navigateTo(startId, /*pushHistory=*/false);
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
            navigateTo(prev, /*pushHistory=*/false);
            return;
        }
        
        // ✅ إذا كان في الشاشة الرئيسية للمحاسبة وضغط زر الرجوع، نعرض نافذة التأكيد
        if (MiraAccountingScreens.ID_DASHBOARD.equals(currentScreenId)) {
            showExitConfirmationDialog();
            return;
        }
        
        navigateTo(MiraAccountingScreens.ID_DASHBOARD, /*pushHistory=*/false);
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
