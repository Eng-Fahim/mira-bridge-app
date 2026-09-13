package com.example.uhf.navigation;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.fragment.*;
import com.example.uhf.manager.PermissionManager;

public class NavigationManager {

    private final FragmentManager fragmentManager;
    private final PermissionManager permissionManager;
    private String currentFragmentTag = "Dashboard";
    private Fragment currentFragment;

    public NavigationManager(FragmentManager fragmentManager, PermissionManager permissionManager) {
        this.fragmentManager = fragmentManager;
        this.permissionManager = permissionManager;
    }

    // ==================== التنقل ====================

    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
        if (fragmentManager == null || fragment == null) return;

        try {
            currentFragment = fragment;
            currentFragmentTag = tag;

            if (!addToBackStack) {
                clearBackStack();
            }

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setCustomAnimations(0, 0, 0, 0);
            transaction.replace(R.id.fragment_container, fragment, tag);

            if (addToBackStack) {
                transaction.addToBackStack(tag);
            }

            transaction.commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadFragmentDirectly(Fragment fragment, String tag) {
        if (fragmentManager == null || fragment == null) return;

        try {
            currentFragment = fragment;
            currentFragmentTag = tag;

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.replace(R.id.fragment_container, fragment, tag);
            transaction.commitNow();
        } catch (Exception e) {
            e.printStackTrace();
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
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    public Fragment getTopFragment() {
        if (fragmentManager != null && fragmentManager.getBackStackEntryCount() > 0) {
            FragmentManager.BackStackEntry entry = fragmentManager.getBackStackEntryAt(fragmentManager.getBackStackEntryCount() - 1);
            return fragmentManager.findFragmentByTag(entry.getName());
        }
        return currentFragment;
    }

    // ==================== Getters ====================

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
