package com.example.uhf.navigation;

import android.os.Handler;
import android.os.Looper;
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
 * ═══════════════════════════════════════════════════════════════════════════
 *  NavigationManager v2.0 — Single Source of Truth
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * التغييرات الجوهرية عن v1.0:
 *
 *   1. ✅ FragmentManager = المصدر الوحيد للحقيقة.
 *      getCurrentFragmentTag() يقرأ منه مباشرة، لا من متغير محلي.
 *
 *   2. ✅ إلغاء TAG_SECURE_GATE المكرر — يُستخدَم TAG_GATES فقط،
 *      مع normalizeTag() لتحويل القديم.
 *
 *   3. ✅ getCurrentFragment() يتحقق من isAdded قبل الإرجاع.
 *
 *   4. ✅ commitNow() بدلاً من commit() — التنفيذ فوري،
 *      لا race مع FragmentManager.isStateSaved.
 *
 *   5. ✅ notifyExternalChange() — دالة جديدة يُستدعيها المستهلكون
 *      بعد أي تغيير خارجي في FM (مثل navigation مباشر من fragment).
 *
 *   6. ✅ حذف public Fragment currentFragment — قراءة فقط عبر getter.
 *
 *   7. ✅ معالجة deferral بذكاء: commitAllowingStateLoss بدلاً من
 *      إعادة post لا نهائية.
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
    public static final String TAG_SECURE_GATE       = "SecureGate";  // ← يبقى للتوافق العكسي
    public static final String TAG_GATES             = "Gates";        // ✅ الرسمي
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
    public static final String TAG_SNAPSHOTS         = "Snapshots";

    // ==================== قائمة التبويبات الرئيسية ====================
    private static final String[] ROOT_TAGS = {
            TAG_DASHBOARD, TAG_INVENTORY, TAG_POS,
            TAG_GATES, TAG_RADAR,                      // ✅ TAG_GATES فقط (لا SECURE_GATE)
            TAG_REPORTS, TAG_ACCOUNTING, TAG_ADMIN_HUB
    };

    // ==================== الحقول ====================
    private final FragmentManager fragmentManager;
    private final PermissionManager permissionManager;
    private final int containerId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** ⚠️ cache — لكن getCurrentFragmentTag() لا يعتمد عليه */
    private String cachedTag = TAG_DASHBOARD;

    private String lastRootTag = TAG_DASHBOARD;
    private boolean loggingEnabled = true;

    private FragmentActivity activityRef;
    private BackPressInterceptor backPressInterceptor;

    public interface BackPressInterceptor {
        boolean onBackPressed();
    }

    // ==================== Constructor ====================

    public NavigationManager(@NonNull FragmentManager fragmentManager,
                             @NonNull PermissionManager permissionManager,
                             @IdRes int containerId) {
        this.fragmentManager = fragmentManager;
        this.permissionManager = permissionManager;
        this.containerId = containerId;
    }

    public void attachToActivity(@NonNull FragmentActivity activity) {
        this.activityRef = activity;
    }

    public void setBackPressInterceptor(BackPressInterceptor interceptor) {
        this.backPressInterceptor = interceptor;
    }

    private FragmentActivity getActivity() {
        return activityRef;
    }

    // ═══════════════════════════════════════════════════════════════
    //  🎯 Public API — التزامن
    // ═══════════════════════════════════════════════════════════════

    /**
     * ✅ [v2.0] يُستدعى بعد أي تغيير خارجي في FragmentManager.
     *            المستهلك (UHFMainActivity / MiraPosFragment) يستدعيه
     *            عندما يتلاعب بـ FM مباشرة.
     */
    public void notifyExternalChange() {
        String tag = readTagFromFM();
        if (tag != null) {
            cachedTag = tag;
            if (isRootTag(tag)) lastRootTag = tag;
            log("🔄 External change → tag=" + tag);
        }
    }

    /**
     * ✅ [v2.0] المصدر الحقيقي — FragmentManager.
     */
    @NonNull
    public String getCurrentFragmentTag() {
        String tag = readTagFromFM();
        if (tag != null) {
            cachedTag = tag;
            return tag;
        }
        return cachedTag;
    }

    /**
     * ✅ [v2.0] المصدر الحقيقي — FragmentManager.
     */
    @Nullable
    public Fragment getCurrentFragment() {
        try {
            Fragment active = fragmentManager.findFragmentById(containerId);
            if (active != null && active.isAdded() && !active.isRemoving()) {
                return active;
            }
        } catch (Exception e) {
            log("getCurrentFragment error: " + e.getMessage());
        }
        return null;
    }

    public String getLastRootTag() {
        // ✅ زامنه مع الواقع
        String current = getCurrentFragmentTag();
        if (isRootTag(current)) {
            lastRootTag = current;
        }
        return lastRootTag;
    }

    // ═══════════════════════════════════════════════════════════════
    //  التنقل الرئيسي
    // ═══════════════════════════════════════════════════════════════

    public void loadFragment(@NonNull Fragment fragment,
                             @NonNull String tag,
                             boolean addToBackStack) {
        loadFragment(fragment, tag, addToBackStack, false);
    }

    public void loadFragment(@NonNull Fragment fragment,
                             @NonNull String tag,
                             boolean addToBackStack,
                             boolean forceReload) {
        if (fragment == null || tag == null) return;
        tag = normalizeTag(tag);

        // 1) نفس الـ Fragment؟
        if (!forceReload && tag.equals(cachedTag)) {
            Fragment existing = fragmentManager.findFragmentByTag(tag);
            if (existing != null && existing.isAdded() && existing.isVisible()) {
                log("Already on " + tag + ", skipping");
                return;
            }
        }

        // 2) root tag → امسح الـ Back Stack
        if (isRootTag(tag)) {
            lastRootTag = tag;
            if (!addToBackStack) {
                clearBackStackInternal();
            }
        }

        // 3) أعد استخدام Fragment موجود
        Fragment existing = fragmentManager.findFragmentByTag(tag);
        Fragment target = (existing != null && existing.isAdded())
                ? existing : fragment;

        // 4) تنفيذ — مع commitNow
        try {
            logState("BEFORE loadFragment: " + tag);

            FragmentTransaction tx = fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .setCustomAnimations(0, 0, 0, 0)
                    .replace(containerId, target, tag)
                    .setPrimaryNavigationFragment(target);

            if (addToBackStack) {
                tx.addToBackStack(tag);
            }

            // ✅ commitNow — فوري، لا يبقى في الطابور
            if (fragmentManager.isStateSaved()) {
                tx.commitNowAllowingStateLoss();
                log("⚠️ State saved → used commitNowAllowingStateLoss");
            } else {
                tx.commitNow();
            }

            cachedTag = tag;
            log("✅ Navigated to: " + tag + " (backStack=" + addToBackStack + ")");

        } catch (IllegalStateException e) {
            log("loadFragment commit failed: " + e.getMessage());
        } catch (Exception e) {
            log("loadFragment error: " + e.getMessage());
        }
    }

    public void loadFragmentDirectly(@NonNull Fragment fragment, @NonNull String tag) {
        if (fragment == null || tag == null) return;
        tag = normalizeTag(tag);

        try {
            logState("BEFORE loadFragmentDirectly: " + tag);
            clearBackStackInternal();

            if (isRootTag(tag)) {
                lastRootTag = tag;
            }

            FragmentTransaction tx = fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .setCustomAnimations(0, 0, 0, 0)
                    .replace(containerId, fragment, tag)
                    .setPrimaryNavigationFragment(fragment);

            if (fragmentManager.isStateSaved()) {
                tx.commitNowAllowingStateLoss();
                log("⚠️ State saved → used commitNowAllowingStateLoss");
            } else {
                tx.commitNow();
            }

            cachedTag = tag;
            log("✅ Direct load: " + tag);

        } catch (IllegalStateException e) {
            log("loadFragmentDirectly commit failed: " + e.getMessage());
        } catch (Exception e) {
            log("loadFragmentDirectly error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  الرجوع
    // ═══════════════════════════════════════════════════════════════

    public boolean handleBackPress() {
        // 0) Interceptor
        if (backPressInterceptor != null && backPressInterceptor.onBackPressed()) {
            return true;
        }

        // 1) Back Stack → pop
        if (fragmentManager.getBackStackEntryCount() > 0) {
            try {
                fragmentManager.popBackStackImmediate();
                notifyExternalChange();   // ✅ زامن
                log("⬅️ Back → now at: " + cachedTag);
                return true;
            } catch (Exception e) {
                log("popBackStack error: " + e.getMessage());
            }
        }

        // 2) لسنا على lastRootTag → اذهب إليه
        String current = getCurrentFragmentTag();
        if (!lastRootTag.equals(current)) {
            log("⬅️ Going back to last root: " + lastRootTag);
            Fragment rootFragment = createFragmentByTag(lastRootTag);
            if (rootFragment != null) {
                loadFragmentDirectly(rootFragment, lastRootTag);
                return true;
            }
        }

        // 3) على root — اترك النظام يقرر
        return false;
    }

    @NonNull
    public OnBackPressedCallback createBackPressedCallback() {
        return new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!handleBackPress()) {
                    setEnabled(false);
                    FragmentActivity activity = getActivity();
                    if (activity != null) {
                        activity.getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            }
        };
    }

    public void goToDashboard() {
        loadFragmentDirectly(createFragmentByTag(TAG_DASHBOARD), TAG_DASHBOARD);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

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

    public boolean isRootTag(String tag) {
        if (tag == null) return false;
        tag = normalizeTag(tag);
        for (String rootTag : ROOT_TAGS) {
            if (rootTag.equals(tag)) return true;
        }
        return false;
    }

    /**
     * ✅ [v2.0] توحيد الأسماء المتقادمة
     */
    private String normalizeTag(String tag) {
        if (TAG_SECURE_GATE.equals(tag)) return TAG_GATES;
        return tag;
    }

    /**
     * ✅ [v2.0] قراءة حقيقية من FM — لا تُعدِّل أي متغير
     */
    @Nullable
    private String readTagFromFM() {
        try {
            // 1) back stack
            int count = fragmentManager.getBackStackEntryCount();
            if (count > 0) {
                String topTag = fragmentManager.getBackStackEntryAt(count - 1).getName();
                if (topTag != null && !topTag.isEmpty()) {
                    return normalizeTag(topTag);
                }
            }

            // 2) الـ container
            Fragment active = fragmentManager.findFragmentById(containerId);
            if (active != null && active.isAdded() && active.getTag() != null) {
                return normalizeTag(active.getTag());
            }
        } catch (Exception e) {
            log("readTagFromFM error: " + e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Factory
    // ═══════════════════════════════════════════════════════════════

    @NonNull
    public Fragment createFragmentByTag(@Nullable String tag) {
        if (tag == null) return new MiraDashboardFragment();
        tag = normalizeTag(tag);

        switch (tag) {
            case TAG_DASHBOARD:         return new MiraDashboardFragment();
            case TAG_INVENTORY:         return new MiraInventoryProFragment();
            case TAG_POS:               return new MiraPosFragment();
            case TAG_TRANSFERS:         return new MiraTransferFragment();
            case TAG_IDENTITY_STUDIO:   return new MiraIdentityStudioFragment();
            case TAG_RADAR:             return new MiraUHFRadarLocationFragment();
            case TAG_GATES:             return new MiraSecureGateFragment();  // ✅ موحّد
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
            case TAG_SNAPSHOTS:         return new MiraSnapshotsFragment();
            default:
                log("Unknown tag: " + tag + " → Dashboard");
                return new MiraDashboardFragment();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Logging
    // ═══════════════════════════════════════════════════════════════

    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled = enabled;
    }

    private void log(String message) {
        if (loggingEnabled) Log.d(TAG, message);
    }

    private void logState(String operation) {
        if (!loggingEnabled) return;

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════\n");
        sb.append("Operation: ").append(operation).append("\n");
        sb.append("cachedTag: ").append(cachedTag).append("\n");
        sb.append("realTag:   ").append(readTagFromFM()).append("\n");
        sb.append("lastRoot:  ").append(lastRootTag).append("\n");
        sb.append("BackStack: ").append(fragmentManager.getBackStackEntryCount()).append("\n");
        sb.append("Fragments: ").append(fragmentManager.getFragments().size()).append("\n");
        sb.append("═══════════════════════════════════════");

        Log.d(TAG, sb.toString());
    }
}
