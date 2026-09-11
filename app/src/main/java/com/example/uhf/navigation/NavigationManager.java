package com.example.uhf.navigation;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import android.util.Log;

import com.example.uhf.fragment.*;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.R;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;

import java.util.List;

/**
 * NavigationManager - الإصدار المُصلح
 *
 * ✅ الميزات:
 * - استخدام add/hide/show بدلاً من replace
 * - حفظ حالة Fragments في الذاكرة (لا تُدمَّر)
 * - أداء أسرع (لا إعادة إنشاء)
 * - لا اختفاء للمحتوى
 * - استعادة Fragments عند إعادة إنشاء Activity
 */
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

    // ==================== التنقل المحسّن ====================

    /**
     * ✅ تحميل Fragment بطريقة آمنة (بدون تدمير)
     *
     * @param fragment      الـ Fragment الجديد
     * @param tag           الوسم
     * @param addToBackStack هل يُضاف للـ back stack
     */
    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
        if (fragmentManager == null || fragment == null) return;

        try {
            // ✅ إذا كان الـ Fragment نفسه معروضاً → لا شيء
            if (currentFragment != null && tag != null && tag.equals(currentFragmentTag)) {
                Log.d(TAG, "Same fragment, skipping: " + tag);
                return;
            }

            // ✅ إذا طُلب عدم الإضافة للـ back stack → امسح أولاً
            if (!addToBackStack) {
                clearBackStackInternal();
            }

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setCustomAnimations(0, 0, 0, 0);

            // ✅ 1) أخفِ كل الـ Fragments النشطة (بدلاً من تدميرها)
            hideAllActiveFragments(transaction, tag);

            // ✅ 2) ابحث عن الـ Fragment المطلوب بواسطة الـ tag
            Fragment existingFragment = fragmentManager.findFragmentByTag(tag);

            if (existingFragment != null && existingFragment.isAdded()) {
                // ✅ موجود → أظهره فقط (لا حاجة لإعادة إنشاء)
                Log.d(TAG, "Showing existing fragment: " + tag);
                transaction.show(existingFragment);
                currentFragment = existingFragment;
            } else {
                // ✅ غير موجود → أضفه
                Log.d(TAG, "Adding new fragment: " + tag);
                transaction.add(CONTAINER_ID, fragment, tag);
                currentFragment = fragment;
            }

            currentFragmentTag = tag;

            if (addToBackStack) {
                transaction.addToBackStack(tag);
            }

            transaction.commit();

        } catch (Exception e) {
            Log.e(TAG, "loadFragment error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ تحميل فوري (يستبدل المحتوى)
     * ⚠️ استخدمه فقط عند الحاجة الفورية (مثل Dashboard الأولي)
     */
    public void loadFragmentDirectly(Fragment fragment, String tag) {
        if (fragmentManager == null || fragment == null) return;

        try {
            FragmentTransaction transaction = fragmentManager.beginTransaction();

            // ✅ أخفِ كل شيء
            hideAllActiveFragments(transaction, tag);

            // ✅ ابحث عن الموجود
            Fragment existingFragment = fragmentManager.findFragmentByTag(tag);

            if (existingFragment != null && existingFragment.isAdded()) {
                transaction.show(existingFragment);
                currentFragment = existingFragment;
            } else {
                transaction.add(CONTAINER_ID, fragment, tag);
                currentFragment = fragment;
            }

            currentFragmentTag = tag;
            transaction.commitNow();

        } catch (Exception e) {
            Log.e(TAG, "loadFragmentDirectly error: " + e.getMessage(), e);
        }
    }

    // ==================== أدوات مساعدة ====================

    /**
     * ✅ إخفاء كل الـ Fragments النشطة (باستثناء المستهدف)
     */
    private void hideAllActiveFragments(FragmentTransaction transaction, String exceptTag) {
        try {
            List<Fragment> fragments = fragmentManager.getFragments();
            if (fragments == null) return;

            for (Fragment f : fragments) {
                if (f == null || !f.isAdded()) continue;
                if (f.getTag() != null && f.getTag().equals(exceptTag)) continue;
                if (f.isHidden()) continue;

                transaction.hide(f);
            }
        } catch (Exception e) {
            Log.e(TAG, "hideAllActiveFragments error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ مسح الـ back stack (بدون تدمير Fragments)
     */
    private void clearBackStackInternal() {
        try {
            if (fragmentManager.getBackStackEntryCount() > 0) {
                fragmentManager.popBackStack(
                        null,
                        FragmentManager.POP_BACK_STACK_INCLUSIVE
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "clearBackStackInternal error: " + e.getMessage(), e);
        }
    }

    public void clearBackStack() {
        clearBackStackInternal();
    }

    // ==================== Factory ====================

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

    // ==================== استرجاع الحالة ====================

    /**
     * ✅ إرجاع أعلى Fragment في الـ back stack
     */
    public Fragment getTopFragment() {
        try {
            if (fragmentManager.getBackStackEntryCount() > 0) {
                FragmentManager.BackStackEntry entry = fragmentManager.getBackStackEntryAt(
                        fragmentManager.getBackStackEntryCount() - 1
                );
                Fragment f = fragmentManager.findFragmentByTag(entry.getName());
                if (f != null) return f;
            }
        } catch (Exception e) {
            Log.e(TAG, "getTopFragment error: " + e.getMessage());
        }
        return currentFragment;
    }

    /**
     * ✅ إرجاع الـ Fragment الحالي المعروض فعلاً
     */
    public Fragment getVisibleFragment() {
        try {
            List<Fragment> fragments = fragmentManager.getFragments();
            if (fragments != null) {
                for (Fragment f : fragments) {
                    if (f != null && f.isAdded() && !f.isHidden() && f.isVisible()) {
                        return f;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getVisibleFragment error: " + e.getMessage());
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
