package com.example.uhf.navigation;

import android.util.Log;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.fragment.*;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * NavigationManager — النسخة النهائية المستقرة
 *
 * ✅ يستخدم replace() بدلاً من hide/show
 * ✅ يمسح Back Stack عند الانتقال بين التبويبات الرئيسية
 * ✅ يقرأ الحالة الحقيقية من FragmentManager
 * ✅ يحفظ lastRootTag للرجوع الصحيح
 * ✅ logs تفصيلية للتشخيص
 * ✅ آمن ضد Process Death و Configuration Change
 * ✅ يدعم BackPressInterceptor (للجرد النشط)
 *
 * ✅ جديد في هذه النسخة:
 * - إضافة TAG_SNAPSHOTS (شاشة لقطات المخزون)
 */
public class NavigationManager {

    private static final String TAG = "NavigationManager";

    // ==================== ثوابت الـ Tags ====================
    public static final String TAG_DASHBOARD         = "Dashboard";
    public static final String TAG_INVENTORY         = "Scan";
    public static final String TAG_POS               = "POS";
    public static final String TAG_TRANSFERS         = "Transfers";
    public static final String TAG_IDENTITY_STUDIO   = "IdentityStudio";
    public static final String TAG_RADAR             = "Radar";
    public static final String TAG_SECURE_GATE       = "SecureGate";
    public static final String TAG_REPORTS           = "Reports";
    public static final String TAG_ACCOUNTING        = "Accounting";
    public static final String TAG_ADMIN_HUB         = "AdminHub";
    public static final String TAG_SALES_HISTORY     = "SalesHistory";
    public static final String TAG_SERVER_CONFIG     = "ServerConfig";
    public static final String TAG_GOLD_RATES        = "GoldRates";
    public static final String TAG_PROFILE           = "Profile";
    public static final String TAG_USERS_MANAGEMENT  = "UsersManagement";
    public static final String TAG_RFID_WRITE        = "RFIDWrite";
    public static final String TAG_READER_SETUP      = "ReaderSetup";
    public static final String TAG_GATE_SETTINGS     = "GateSettings";
    public static final String TAG_QUICK_ACCESS      = "QuickAccessSettings";
    public static final String TAG_BRANCHES          = "Branches";
    public static final String TAG_RFID_BINDINGS     = "RfidBindings";
    public static final String TAG_SOUND_SETTINGS    = "SoundSettings";

    // ✅ جديد: شاشة لقطات المخزون
    public static final String TAG_SNAPSHOTS         = "Snapshots";

    // ==================== قائمة التبويبات الرئيسية ====================
    private static final String[] ROOT_TAGS = {
            TAG_DASHBOARD, TAG_INVENTORY, TAG_POS,
            TAG_TRANSFERS, TAG_RADAR, TAG_SECURE_GATE,
            TAG_REPORTS, TAG_ACCOUNTING, TAG_ADMIN_HUB
    };

    // ==================== الحقول ====================
    private final FragmentManager fragmentManager;
    private final PermissionManager permissionManager;
    private final int containerId;

    private String currentFragmentTag = TAG_DASHBOARD;
    private String lastRootTag = TAG_DASHBOARD;
    private boolean loggingEnabled = true;

    // ✅ مرجع الـ Fragment الحالي (للتوافق مع UHFMainActivity)
    public Fragment currentFragment;

    // ✅ مرجع الـ Activity (للـ OnBackPressedDispatcher)
    private FragmentActivity activityRef;

    // ✅ Interceptor لمعالجة حدث الرجوع (مثل الجرد النشط)
    public interface BackPressInterceptor {
        boolean onBackPressed();
    }

    private BackPressInterceptor backPressInterceptor;

    // ==================== Constructor ====================

    public NavigationManager(@NonNull FragmentManager fragmentManager,
                             @NonNull PermissionManager permissionManager,
                             @IdRes int containerId) {
        this.fragmentManager = fragmentManager;
        this.permissionManager = permissionManager;
        this.containerId = containerId;
    }

    // ==================== Attach ====================

    public void attachToActivity(@NonNull FragmentActivity activity) {
        this.activityRef = activity;
    }

    public void setBackPressInterceptor(BackPressInterceptor interceptor) {
        this.backPressInterceptor = interceptor;
    }

    private FragmentActivity getActivity() {
        return activityRef;
    }

    // ==================== التنقل الرئيسي ====================

    public void loadFragment(@NonNull Fragment fragment,
                             @NonNull String tag,
                             boolean addToBackStack) {
        loadFragment(fragment, tag, addToBackStack, false);
    }

    public void loadFragment(@NonNull Fragment fragment,
                             @NonNull String tag,
                             boolean addToBackStack,
                             boolean forceReload) {

        if (fragmentManager.isStateSaved()) {
            log("loadFragment skipped - state saved: " + tag);
            return;
        }

        // ✅ 1) إذا كان نفس الـ Fragment الحالي، لا تفعل شيئاً (إلا إذا forceReload)
        if (!forceReload && tag.equals(currentFragmentTag)) {
            Fragment existing = fragmentManager.findFragmentByTag(tag);
            if (existing != null && existing.isVisible()) {
                log("Already on " + tag + ", skipping");
                return;
            }
        }

        // ✅ 2) إذا كان root tag، امسح الـ Back Stack أولاً واحفظه كآخر root
        if (isRootTag(tag)) {
            lastRootTag = tag;
            if (!addToBackStack) {
                clearBackStackInternal();
            }
        }

        // ✅ 3) إذا كان الـ Fragment موجوداً مسبقاً، أعد استخدامه
        Fragment existing = fragmentManager.findFragmentByTag(tag);
        Fragment target = (existing != null) ? existing : fragment;

        // ✅ 4) تنفيذ الـ Transaction
        try {
            logState("BEFORE loadFragment: " + tag);

            FragmentTransaction tx = fragmentManager.beginTransaction();
            tx.setReorderingAllowed(true);
            tx.setCustomAnimations(0, 0, 0, 0);

            tx.replace(containerId, target, tag);
            tx.setPrimaryNavigationFragment(target);

            if (addToBackStack) {
                tx.addToBackStack(tag);
            }

            tx.commit();

            currentFragmentTag = tag;
            currentFragment = target;   // ✅ جديد

            log("✅ Navigated to: " + tag + " (backStack=" + addToBackStack + ")");

        } catch (IllegalStateException e) {
            log("loadFragment commit failed (state saved): " + e.getMessage());
        } catch (Exception e) {
            log("loadFragment error: " + e.getMessage());
        }
    }

    public void loadFragmentDirectly(@NonNull Fragment fragment, @NonNull String tag) {
        if (fragmentManager.isStateSaved()) {
            log("loadFragmentDirectly skipped - state saved: " + tag);
            return;
        }

        try {
            logState("BEFORE loadFragmentDirectly: " + tag);

            clearBackStackInternal();

            if (isRootTag(tag)) {
                lastRootTag = tag;
            }

            FragmentTransaction tx = fragmentManager.beginTransaction();
            tx.setReorderingAllowed(true);
            tx.setCustomAnimations(0, 0, 0, 0);
            tx.replace(containerId, fragment, tag);
            tx.setPrimaryNavigationFragment(fragment);
            tx.commit();

            currentFragmentTag = tag;
            currentFragment = fragment;   // ✅ جديد

            log("✅ Direct load: " + tag);

        } catch (IllegalStateException e) {
            log("loadFragmentDirectly commit failed: " + e.getMessage());
        } catch (Exception e) {
            log("loadFragmentDirectly error: " + e.getMessage());
        }
    }

    // ==================== الرجوع ====================

    public boolean handleBackPress() {
        // ✅ 0) استدعِ الـ Interceptor أولاً (للجرد النشط مثلاً)
        if (backPressInterceptor != null && backPressInterceptor.onBackPressed()) {
            return true;
        }

        // ✅ 1) هناك Back Stack → ارجع خطوة واحدة
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStackImmediate();
            updateCurrentTagFromBackStack();
            log("⬅️ Back pressed → now at: " + currentFragmentTag);
            return true;
        }

        // ✅ 2) لسنا على آخر root tag → اذهب إليه
        if (!lastRootTag.equals(currentFragmentTag)) {
            log("⬅️ Going back to last root: " + lastRootTag);
            Fragment rootFragment = createFragmentByTag(lastRootTag);
            loadFragmentDirectly(rootFragment, lastRootTag);
            return true;
        }

        // ✅ 3) نحن على root tag ولا يوجد Back Stack → اترك النظام يقرر
        return false;
    }

    @NonNull
    public OnBackPressedCallback createBackPressedCallback() {
        return new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!handleBackPress()) {
                    setEnabled(false);
                    if (getActivity() != null) {
                        getActivity().getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            }
        };
    }

    public void goToDashboard() {
        loadFragmentDirectly(createFragmentByTag(TAG_DASHBOARD), TAG_DASHBOARD);
    }

    // ==================== دوال مساعدة ====================

    private void clearBackStackInternal() {
        if (fragmentManager.getBackStackEntryCount() > 0) {
            try {
                fragmentManager.popBackStackImmediate(
                        null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
                log("Back stack cleared");
            } catch (Exception e) {
                log("clearBackStack error: " + e.getMessage());
            }
        }
    }

    private void updateCurrentTagFromBackStack() {
        int count = fragmentManager.getBackStackEntryCount();
        if (count > 0) {
            FragmentManager.BackStackEntry entry = fragmentManager.getBackStackEntryAt(count - 1);
            currentFragmentTag = entry.getName();
        } else {
            currentFragmentTag = lastRootTag;
        }

        // ✅ حدّث currentFragment أيضاً
        Fragment f = fragmentManager.findFragmentByTag(currentFragmentTag);
        if (f != null) {
            currentFragment = f;
        }
    }

    public boolean isRootTag(String tag) {
        if (tag == null) return false;
        for (String rootTag : ROOT_TAGS) {
            if (rootTag.equals(tag)) return true;
        }
        return false;
    }

    // ==================== Factory ====================

    @NonNull
    public Fragment createFragmentByTag(@Nullable String tag) {
        if (tag == null) return new MiraDashboardFragment();

        switch (tag) {
            case TAG_DASHBOARD:         return new MiraDashboardFragment();
            case TAG_INVENTORY:         return new MiraInventoryProFragment();
            case TAG_POS:               return new MiraPosFragment();
            case TAG_TRANSFERS:         return new MiraTransferFragment();
            case TAG_IDENTITY_STUDIO:   return new MiraIdentityStudioFragment();
            case TAG_RADAR:             return new MiraUHFRadarLocationFragment();
            case TAG_SECURE_GATE:       return new MiraSecureGateFragment();
            case TAG_REPORTS:           return new MiraReportsProFragment();
            case TAG_ACCOUNTING:        return new MiraAccountingFragment();
            case TAG_ADMIN_HUB:         return new MiraAdminHubFragment();
            case TAG_SALES_HISTORY:     return new MiraSalesHistoryFragment();
            case TAG_SERVER_CONFIG:     return new MiraServerConfigFragment();
            case TAG_GOLD_RATES:        return new MiraGoldCurrencyFragment();
            case TAG_PROFILE:           return new MiraProfileFragment();
            case TAG_USERS_MANAGEMENT:  return new UsersManagementFragment();
            case TAG_RFID_WRITE:        return new MiraUHFReadWriteFragment();
            case TAG_READER_SETUP:      return new MiraReaderSetupFragment();
            case TAG_GATE_SETTINGS:     return new MiraGateSettingsFragment();
            case TAG_QUICK_ACCESS:      return new MiraQuickAccessSettingsFragment();
            case TAG_BRANCHES:          return new MiraBranchesManagementFragment();
            case TAG_RFID_BINDINGS:     return new MiraRfidBindingsFragment();
            case TAG_SOUND_SETTINGS:    return new MiraSoundSettingsFragment();
            // ✅ جديد: شاشة اللقطات
            case TAG_SNAPSHOTS:         return new MiraSnapshotsFragment();
            default:
                log("Unknown tag: " + tag + ", falling back to Dashboard");
                return new MiraDashboardFragment();
        }
    }

    // ==================== Getters ====================

    public String getCurrentFragmentTag() {
        int count = fragmentManager.getBackStackEntryCount();
        if (count > 0) {
            return fragmentManager.getBackStackEntryAt(count - 1).getName();
        }
        return currentFragmentTag;
    }

    @Nullable
    public Fragment getCurrentFragment() {
        if (fragmentManager == null) return currentFragment;

        // أولاً: ابحث بالـ tag الحالي
        Fragment f = fragmentManager.findFragmentByTag(currentFragmentTag);
        if (f != null && f.isAdded()) {
            return f;
        }

        // ثانياً: fallback
        return currentFragment;
    }

    public String getLastRootTag() {
        return lastRootTag;
    }

    // ==================== Logging ====================

    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled = enabled;
    }

    private void log(String message) {
        if (loggingEnabled) {
            Log.d(TAG, message);
        }
    }

    private void logState(String operation) {
        if (!loggingEnabled) return;

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════\n");
        sb.append("Operation: ").append(operation).append("\n");
        sb.append("currentTag: ").append(currentFragmentTag).append("\n");
        sb.append("lastRootTag: ").append(lastRootTag).append("\n");
        sb.append("BackStack Count: ").append(fragmentManager.getBackStackEntryCount()).append("\n");
        sb.append("Fragments in FM: ").append(fragmentManager.getFragments().size()).append("\n");

        List<Fragment> fragments = new ArrayList<>(fragmentManager.getFragments());
        for (Fragment f : fragments) {
            if (f != null && f.isAdded()) {
                sb.append("  - ").append(f.getTag())
                  .append(" | visible=").append(f.isVisible())
                  .append(" | hidden=").append(f.isHidden())
                  .append(" | added=").append(f.isAdded())
                  .append("\n");
            }
        }
        sb.append("═══════════════════════════════════════");

        Log.d(TAG, sb.toString());
    }
}
