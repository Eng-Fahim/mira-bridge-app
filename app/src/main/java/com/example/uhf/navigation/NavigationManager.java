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
 *  NavigationManager v2.1 — Single Source of Truth (Fixed)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * التغيير الجوهري في v2.1:
 *
 *   🔴 [FIX] loadFragment() كان يستدعي commitNow() حتى مع addToBackStack=true.
 *            وهذا يرمي IllegalStateException دائماً:
 *            "This transaction is already being added to the back stack"
 *
 *            النتيجة: جميع عناصر الهيدر لم تعمل (GoldRates, Profile, ReaderControl,...).
 *
 *            الحل: التفريق بين الحالتين:
 *              - مع backStack → commit() / commitAllowingStateLoss()
 *              - بدون backStack → commitNow() / commitNowAllowingStateLoss()
 *
 * باقي الميزات من v2.0:
 *   ✅ FragmentManager = المصدر الوحيد للحقيقة
 *   ✅ TAG_GATES موحّد
 *   ✅ getCurrentFragment() يتحقق من isAdded
 *   ✅ notifyExternalChange()
 *   ✅ لا public Fragment currentFragment
 */
public class NavigationManager {

    private static final String TAG = "NavigationManager";

    // ==================== ثوابت الـ Tags ====================
    public static final String TAG_DASHBOARD         = "Dashboard";
    public static final String TAG_INVENTORY         = "Scan";
    public static final String TAG_POS               = "POS";
    public static final String TAG_TRANSFERS         = "Transfers";
    public static final String TAG_IDENTITY_STUDIO   = "IdentityStudio";
    public static final String TAG_SCRAP_BATCHES = "ScrapBatches";
    public static final String TAG_SCRAP_PROFIT_REPORT = "ScrapProfitReport";
    public static final String TAG_VOUCHERS = "Vouchers";
    public static final String TAG_PARTIES = "Parties";
    public static final String TAG_SCRAP_HISTORY = "ScrapHistory";
    public static final String TAG_SCRAP_AGING = "ScrapAging";
    public static final String TAG_GOLD_CONVERSION = "GoldConversion";
    public static final String TAG_PROFIT_ANALYSIS = "ProfitAnalysis";
    public static final String TAG_INVENTORY_REVALUATION = "InventoryRevaluation";
    public static final String TAG_PURCHASE_INVOICE = "PurchaseInvoice";

    
    public static final String TAG_RADAR             = "Radar";
    public static final String TAG_SECURE_GATE       = "SecureGate";
    public static final String TAG_GATES             = "Gates";
    public static final String TAG_REPORTS           = "Reports";
    public static final String TAG_ACCOUNTING        = "Accounting";
    public static final String TAG_ADMIN_HUB         = "AdminHub";
    public static final String TAG_SALES_HISTORY     = "SalesHistory";
    public static final String TAG_SERVER_CONFIG     = "ServerConfig";
    public static final String TAG_GOLD_RATES        = "GoldRates";
    public static final String TAG_PROFILE           = "Profile";
    public static final String TAG_USERS_MANAGEMENT  = "UsersManagement";
    public static final String TAG_CASH_RECONCILIATION = "CashReconciliation";
    public static final String TAG_RFID_WRITE        = "RFIDWrite";
    public static final String TAG_READER_SETUP      = "ReaderSetup";
    public static final String TAG_GATE_SETTINGS     = "GateSettings";
    public static final String TAG_QUICK_ACCESS      = "QuickAccessSettings";
    public static final String TAG_BRANCHES          = "Branches";
    public static final String TAG_RFID_BINDINGS     = "RfidBindings";
    public static final String TAG_SOUND_SETTINGS    = "SoundSettings";
    public static final String TAG_SNAPSHOTS         = "Snapshots";
    
    public static final String TAG_SCRAP_GOLD_BUY = "ScrapGoldBuy";
    public static final String TAG_AUDIT_LOGS = "AuditLogs";

    // ==================== قائمة التبويبات الرئيسية ====================
    private static final String[] ROOT_TAGS = {
            TAG_DASHBOARD, TAG_INVENTORY, TAG_POS,
            TAG_GATES, TAG_RADAR,
            TAG_REPORTS, TAG_ACCOUNTING, TAG_ADMIN_HUB
    };

    // ==================== الحقول ====================
    private final FragmentManager fragmentManager;
    private final PermissionManager permissionManager;
    private final int containerId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    public void notifyExternalChange() {
        String tag = readTagFromFM();
        if (tag != null) {
            cachedTag = tag;
            if (isRootTag(tag)) lastRootTag = tag;
            log("🔄 External change → tag=" + tag);
        }
    }

    @NonNull
    public String getCurrentFragmentTag() {
        String tag = readTagFromFM();
        if (tag != null) {
            cachedTag = tag;
            return tag;
        }
        return cachedTag;
    }

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

        // 4) تنفيذ
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

            // ✅✅✅ [v2.1 CRITICAL FIX]
            //    commitNow() و commitNowAllowingStateLoss() لا يمكن استخدامهما
            //    عندما يكون addToBackStack=true (يرمي IllegalStateException).
            //
            //    لذلك نختار الدالة المناسبة حسب addToBackStack:
            //      - مع backStack → commit() / commitAllowingStateLoss()
            //      - بدون backStack → commitNow() / commitNowAllowingStateLoss()
            if (addToBackStack) {
                // لا commitNow — استخدم commit
                if (fragmentManager.isStateSaved()) {
                    tx.commitAllowingStateLoss();
                    log("✅ commitAllowingStateLoss (hasBackStack)");
                } else {
                    tx.commit();
                    log("✅ commit (hasBackStack)");
                }
            } else {
                // بدون backStack — commitNow آمن
                if (fragmentManager.isStateSaved()) {
                    tx.commitNowAllowingStateLoss();
                    log("✅ commitNowAllowingStateLoss (noBackStack)");
                } else {
                    tx.commitNow();
                    log("✅ commitNow (noBackStack)");
                }
            }

            cachedTag = tag;
            log("✅ Navigated to: " + tag + " (backStack=" + addToBackStack + ")");

        } catch (IllegalStateException e) {
            Log.e(TAG, "❌ loadFragment FAILED: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "❌ loadFragment error: " + e.getMessage(), e);
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

            // لا backStack هنا → commitNow آمن
            if (fragmentManager.isStateSaved()) {
                tx.commitNowAllowingStateLoss();
                log("✅ commitNowAllowingStateLoss (direct)");
            } else {
                tx.commitNow();
                log("✅ commitNow (direct)");
            }

            cachedTag = tag;
            log("✅ Direct load: " + tag);

        } catch (IllegalStateException e) {
            Log.e(TAG, "❌ loadFragmentDirectly FAILED: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "❌ loadFragmentDirectly error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  الرجوع
    // ═══════════════════════════════════════════════════════════════

    public boolean handleBackPress() {
        if (backPressInterceptor != null && backPressInterceptor.onBackPressed()) {
            return true;
        }

        if (fragmentManager.getBackStackEntryCount() > 0) {
            try {
                fragmentManager.popBackStackImmediate();
                notifyExternalChange();
                log("⬅️ Back → now at: " + cachedTag);
                return true;
            } catch (Exception e) {
                log("popBackStack error: " + e.getMessage());
            }
        }

        String current = getCurrentFragmentTag();
        if (!lastRootTag.equals(current)) {
            log("⬅️ Going back to last root: " + lastRootTag);
            Fragment rootFragment = createFragmentByTag(lastRootTag);
            if (rootFragment != null) {
                loadFragmentDirectly(rootFragment, lastRootTag);
                return true;
            }
        }

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

    private String normalizeTag(String tag) {
        if (TAG_SECURE_GATE.equals(tag)) return TAG_GATES;
        return tag;
    }

    @Nullable
    private String readTagFromFM() {
        try {
            int count = fragmentManager.getBackStackEntryCount();
            if (count > 0) {
                String topTag = fragmentManager.getBackStackEntryAt(count - 1).getName();
                if (topTag != null && !topTag.isEmpty()) {
                    return normalizeTag(topTag);
                }
            }

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
        case TAG_GATES:             return new MiraSecureGateFragment();
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
        
        case TAG_PURCHASE_INVOICE: return new MiraPurchaseInvoiceFragment();
        // ✅ شاشات كسر الذهب (مرة واحدة فقط لكل شاشة)
        case TAG_SCRAP_GOLD_BUY:      return new MiraScrapGoldBuyFragment();
        case TAG_SCRAP_HISTORY:       return new MiraScrapHistoryFragment();
        case TAG_SCRAP_BATCHES:       return new MiraScrapBatchesFragment();
        case TAG_SCRAP_PROFIT_REPORT: return new MiraScrapProfitReportFragment();
        case TAG_CASH_RECONCILIATION: return new MiraCashReconciliationFragment();
        case TAG_VOUCHERS: return new MiraVouchersFragment();
        case TAG_PARTIES: return new MiraPartiesListFragment();
        case TAG_SCRAP_AGING: return new MiraScrapAgingFragment();
        case TAG_GOLD_CONVERSION:   return new MiraGoldConversionFragment();
        case TAG_PROFIT_ANALYSIS: return new MiraProfitAnalysisFragment();
        case TAG_INVENTORY_REVALUATION: return new MiraInventoryRevaluationFragment();
        case TAG_AUDIT_LOGS: return new MiraAuditLogFragment();
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
