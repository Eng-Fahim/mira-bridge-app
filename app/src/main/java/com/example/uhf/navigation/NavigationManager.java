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
            // ✅ 1) تجنب إعادة التحميل إذا كان هو النشط حالياً (مع فحص isHidden)
            if (currentFragment != null 
                && tag.equals(currentFragmentTag) 
                && currentFragment.isAdded()
                && !currentFragment.isHidden()) {  // ✅ جديد
                return;
            }

            if (!addToBackStack) {
                clearBackStack();
            }

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setReorderingAllowed(true);  // ✅ جديد
            transaction.setCustomAnimations(0, 0, 0, 0);

            Fragment existingFragment = fragmentManager.findFragmentByTag(tag);

            if (currentFragment != null && currentFragment.isAdded() && !currentFragment.isHidden()) {
                transaction.hide(currentFragment);
            }

            if (existingFragment != null) {
                transaction.show(existingFragment);
                currentFragment = existingFragment;
            } else {
                transaction.add(R.id.fragment_container, fragment, tag);
                currentFragment = fragment;
            }

            currentFragmentTag = tag;

            if (addToBackStack) {
                transaction.addToBackStack(tag);
            }

            transaction.commitAllowingStateLoss();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in loadFragment: " + e.getMessage(), e);
        }
    }

    public void loadFragmentDirectly(Fragment fragment, String tag) {
        if (fragmentManager == null || fragment == null || tag == null) return;

        try {
            // ✅ 1) فحص isStateSaved
            if (fragmentManager.isStateSaved()) {
                Log.w(TAG, "State saved, skipping direct load: " + tag);
                return;
            }
            
            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setReorderingAllowed(true);  // ✅ جديد
            
            // ✅ 2) إخفاء كل الـ Fragments (عدا Dialogs)
            List<Fragment> fragments = new ArrayList<>(fragmentManager.getFragments());
            for (Fragment f : fragments) {
                if (f == null || !f.isAdded()) continue;
                if (f instanceof DialogFragment) continue;  // ✅ جديد
                if (!f.isHidden()) {
                    transaction.hide(f);
                }
            }

            Fragment existing = fragmentManager.findFragmentByTag(tag);
            if (existing != null) {
                if (existing.isHidden()) transaction.show(existing);  // ✅ جديد
                currentFragment = existing;
            } else {
                transaction.add(R.id.fragment_container, fragment, tag);
                currentFragment = fragment;
            }

            currentFragmentTag = tag;
            transaction.commitNowAllowingStateLoss();

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

    public Fragment getTopFragment() {
        if (fragmentManager != null) {
            List<Fragment> fragments = fragmentManager.getFragments();
            for (int i = fragments.size() - 1; i >= 0; i--) {
                Fragment f = fragments.get(i);
                if (f == null || !f.isAdded()) continue;
                if (f instanceof DialogFragment) continue;  // ✅ جديد
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
