package com.example.uhf.navigation;

import android.util.Log;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.fragment.*;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.R;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;

import java.util.ArrayList;
import java.util.List;

public class NavigationManager {

    private static final String TAG = "NavigationManager";
    private static final int CONTAINER_ID = R.id.fragment_container;

    private final FragmentManager fragmentManager;
    private final PermissionManager permissionManager;
    private String currentFragmentTag = "Dashboard";
    private Fragment currentFragment;

    public NavigationManager(FragmentManager fragmentManager, PermissionManager permissionManager) {
        this.fragmentManager = fragmentManager;
        this.permissionManager = permissionManager;
    }

    // ==================== التنقل الآمن والآني ====================

    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
        if (fragmentManager == null || fragment == null || tag == null) return;

        try {
            // ✅ 1) تجاهل إذا كان هو النشط حالياً
            if (currentFragment != null 
                && tag.equals(currentFragmentTag) 
                && currentFragment.isAdded()
                && !currentFragment.isHidden()) {
                Log.d(TAG, "Already on: " + tag);
                return;
            }

            // ✅ 2) لا تنفّذ إذا كان الـ state محفوظاً
            if (fragmentManager.isStateSaved()) {
                Log.w(TAG, "State saved, skipping navigation to: " + tag);
                return;
            }

            if (!addToBackStack) {
                clearBackStack();
            }

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setReorderingAllowed(true);  // ✅ تحسين الأداء
            transaction.setCustomAnimations(0, 0, 0, 0);

            // ✅ 3) ابحث عن Fragment موجود
            Fragment existing = fragmentManager.findFragmentByTag(tag);
            Fragment targetFragment = (existing != null) ? existing : fragment;

            // ✅ 4) أخفِ **كل** الـ Fragments الأخرى (ليس فقط currentFragment)
            List<Fragment> allFragments = new ArrayList<>(fragmentManager.getFragments());
            for (Fragment f : allFragments) {
                if (f == null || !f.isAdded()) continue;
                if (f == targetFragment) continue;
                if (f instanceof DialogFragment) continue;  // لا تلمس الـ Dialogs
                if (!f.isHidden()) {
                    transaction.hide(f);
                }
            }

            // ✅ 5) أظهر أو أضف الهدف
            if (existing != null) {
                if (existing.isHidden()) {
                    transaction.show(existing);
                }
                currentFragment = existing;
            } else {
                transaction.add(CONTAINER_ID, fragment, tag);
                currentFragment = fragment;
            }

            currentFragmentTag = tag;

            if (addToBackStack) {
                transaction.addToBackStack(tag);
            }

            // ✅ 6) commit الآن — لا تراكم commits
            transaction.commitAllowingStateLoss();

            Log.d(TAG, "✅ Navigated to: " + tag);

        } catch (Exception e) {
            Log.e(TAG, "Error in loadFragment: " + e.getMessage(), e);
        }
    }

    public void loadFragmentDirectly(Fragment fragment, String tag) {
        if (fragmentManager == null || fragment == null || tag == null) return;

        try {
            if (fragmentManager.isStateSaved()) {
                Log.w(TAG, "State saved, skipping direct load");
                return;
            }

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setReorderingAllowed(true);

            Fragment existing = fragmentManager.findFragmentByTag(tag);
            Fragment targetFragment = (existing != null) ? existing : fragment;

            // ✅ أخفِ كل الـ Fragments (عدا Dialogs)
            List<Fragment> allFragments = new ArrayList<>(fragmentManager.getFragments());
            for (Fragment f : allFragments) {
                if (f == null || !f.isAdded()) continue;
                if (f == targetFragment) continue;
                if (f instanceof DialogFragment) continue;
                if (!f.isHidden()) {
                    transaction.hide(f);
                }
            }

            if (existing != null) {
                if (existing.isHidden()) transaction.show(existing);
                currentFragment = existing;
            } else {
                transaction.add(CONTAINER_ID, fragment, tag);
                currentFragment = fragment;
            }

            currentFragmentTag = tag;

            // ✅ commit الآن (لأننا فحصنا isStateSaved أعلاه)
            transaction.commitNowAllowingStateLoss();

            Log.d(TAG, "✅ Direct navigated to: " + tag);

        } catch (Exception e) {
            Log.e(TAG, "Error in loadFragmentDirectly: " + e.getMessage(), e);
        }
    }

    public Fragment createFragmentByTag(String tag) {
        if (tag == null) return new MiraDashboardFragment();

        switch (tag) {
            case "Dashboard": return new MiraDashboardFragment();
            case "Scan": return new MiraInventoryProFragment();
            case "POS": return new MiraPosFragment();
            case "Transfers": return new MiraTransferFragment();
            case "IdentityStudio": return new MiraIdentityStudioFragment();
            case "Radar": return new MiraUHFRadarLocationFragment();
            case "SecureGate": return new MiraSecureGateFragment();
            case "Reports": return new MiraReportsProFragment();
            case "Accounting": return new MiraAccountingFragment();
            case "AdminHub": return new MiraAdminHubFragment();
            case "SalesHistory": return new MiraSalesHistoryFragment();
            case "ServerConfig": return new MiraServerConfigFragment();
            case "GoldRates": return new MiraGoldCurrencyFragment();
            case "Profile": return new MiraProfileFragment();
            case "UsersManagement": return new UsersManagementFragment();
            case "RFIDWrite": return new MiraUHFReadWriteFragment();
            case "ReaderSetup": return new MiraReaderSetupFragment();
            case "GateSettings": return new MiraGateSettingsFragment();
            case "QuickAccessSettings": return new MiraQuickAccessSettingsFragment();
            case "Branches": return new MiraBranchesManagementFragment();
            case "RfidBindings": return new MiraRfidBindingsFragment();
            case "SoundSettings": return new MiraSoundSettingsFragment();
            default: return new MiraDashboardFragment();
        }
    }

    public void clearBackStack() {
        if (fragmentManager != null && fragmentManager.getBackStackEntryCount() > 0) {
            try {
                fragmentManager.popBackStackImmediate(
                    null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing backstack: " + e.getMessage());
            }
        }
    }

    /**
     * ✅ يُرجع الـ Fragment المرئي حالياً (يتجاهل Dialogs)
     */
    public Fragment getTopFragment() {
        if (fragmentManager != null) {
            List<Fragment> fragments = fragmentManager.getFragments();
            for (int i = fragments.size() - 1; i >= 0; i--) {
                Fragment f = fragments.get(i);
                if (f == null || !f.isAdded()) continue;
                if (f instanceof DialogFragment) continue;  // ✅ تجاهل Dialogs
                if (!f.isHidden()) {
                    return f;
                }
            }
        }
        return currentFragment;
    }

    // ==================== Getters & Setters ====================

    public String getCurrentFragmentTag() {
        return currentFragmentTag;
    }

    public Fragment getCurrentFragment() {
        return currentFragment;
    }

    public void setCurrentFragmentTag(String tag) {
        this.currentFragmentTag = tag;
    }
}
