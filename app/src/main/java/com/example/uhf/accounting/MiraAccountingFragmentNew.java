package com.example.uhf.accounting;

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
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraGeneralLedgerFragment;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * MIRA Accounting — New Container
 *
 * الحاوية الجديدة للواجهة المحاسبية. تستخدم DrawerLayout + NavigationView
 * بدلاً من الـ 17 TabLayout المزدحم.
 *
 * ── المبادئ ──
 *  1. لا تحذف الشاشات عند التنقل — تخفيها فقط (تحافظ على مدخلات المستخدم)
 *  2. كل التنقل يمر عبر الـ registry (MiraAccountingScreens)
 *  3. Back stack واضح: drawer → history → dashboard → exit
 *  4. الصلاحيات تُفحص عند كل انتقال
 *  5. State كامل يُحفظ ويُستعاد (screenId + history)
 *
 * ── للتوافق الخلفي ──
 *  نوفر نفس الـ API العام لـ MiraAccountingFragment القديم:
 *    navigateToTab(int), navigateToLedgerWithAccount(long), refreshCurrentTab()
 *
 * @version 1.2.0 — Fixed StackOverflowError on back press, fixed empty screen
 *                   on initial load, hardened against missing bools.xml
 */
public class MiraAccountingFragmentNew extends Fragment {

    private static final String TAG = "MiraAccountingNew";
    private static final String KEY_SCREEN_ID = "current_screen_id";
    private static final String KEY_HISTORY = "screen_history";

    // ─── Views ───
    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private MaterialToolbar toolbar;
    private FrameLayout contentContainer;

    // ─── State ───
    private String currentScreenId = MiraAccountingScreens.ID_DASHBOARD;
    private final Deque<String> history = new ArrayDeque<>();
    private boolean isNavigating = false;
    private boolean isArabic = true;

    // ─── Dependencies ───
    private PermissionManager permissionManager;
    private SessionManager sessionManager;

    // ✅ [إصلاح StackOverflow]: نحتفظ بمرجع للـ callback
    //    لنتمكن من تعطيله قبل تفويض زر الرجوع للـ activity.
    private OnBackPressedCallback backCallback;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getContext() != null) {
            try {
                permissionManager = PermissionManager.getInstance(requireContext());
                sessionManager = new SessionManager(requireContext());
            } catch (Exception e) {
                Log.e(TAG, "deps init: " + e.getMessage());
            }
            isArabic = "ar".equals(Locale.getDefault().getLanguage());
        }

        // ✅ Back handler — نحفظ المرجع في حقل
        backCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackPressed();
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
        drawerLayout = v.findViewById(R.id.drawerLayout);
        navView = v.findViewById(R.id.navView);
        toolbar = v.findViewById(R.id.accountingToolbar);
        contentContainer = v.findViewById(R.id.accountingContent);

        if (drawerLayout == null || navView == null
                || toolbar == null || contentContainer == null) {
            Log.e(TAG, "❌ Missing views in fragment_mira_accounting.xml");
            return v;
        }

        // Toolbar: hamburger
        toolbar.setNavigationOnClickListener(view -> {
            if (drawerLayout != null) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        // Toolbar: refresh
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) {
                refreshCurrentTab();
                return true;
            }
            return false;
        });

        // Build drawer from registry
        MiraAccountingNavDrawerHelper.build(navView, isArabic,
                screen -> navigateTo(screen.id, true));

        // Responsive — permanent drawer on tablets
        // ✅ [حماية]: نقرأ الـ bool بأمان؛ إن لم يكن موجوداً نستخدم false
        boolean permanent = readPermanentDrawerFlag();
        if (permanent) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN);
            drawerLayout.setScrimColor(Color.TRANSPARENT);
        }

        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Restore state
        String startId = MiraAccountingScreens.defaultScreenId();
        if (savedInstanceState != null) {
            startId = savedInstanceState.getString(KEY_SCREEN_ID, startId);
            String[] arr = savedInstanceState.getStringArray(KEY_HISTORY);
            if (arr != null) {
                history.clear();
                // push بترتيب معكوس لأن ArrayDeque.push يُضيف في الرأس
                for (int i = arr.length - 1; i >= 0; i--) {
                    history.push(arr[i]);
                }
            }
        }

        // ✅ الآن سيعمل: currentScreenId = dashboard, لكن existing = null
        //    → navigateTo لن يخرج مبكراً وسيضيف الـ fragment فعلياً
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

    // ═══════════════════════════════════════════════════════════
    //  Navigation
    // ═══════════════════════════════════════════════════════════
    private void navigateTo(@NonNull String screenId, boolean pushHistory) {
        if (contentContainer == null || isNavigating) return;

        MiraAccountingScreens.Screen target = MiraAccountingScreens.byId(screenId);
        if (target == null) {
            Log.w(TAG, "Unknown screen id: " + screenId);
            return;
        }
        if (!canOpen(target)) {
            Toast.makeText(requireContext(),
                    isArabic ? "⛔ غير مصرح بالوصول" : "⛔ Access denied",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // ✅ [إصلاح الشاشة الفارغة]:
        //    نبحث عن الـ fragment أولاً، ثم نقرر إذا كان هناك حاجة للانتقال.
        //    سابقاً كان الشرط `screenId == currentScreenId` يُرجع دائماً في أول تحميل
        //    لأن كلاهما = "dashboard" لكن لا يوجد fragment معروض بعد.
        Fragment existing = getChildFragmentManager()
                .findFragmentByTag("SCR_" + screenId);
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

            // hide كل الشاشات الأخرى دون حذفها
            for (Fragment f : getChildFragmentManager().getFragments()) {
                if (f != existing && f.isVisible()) {
                    tx.hide(f);
                }
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

            // Toolbar + check
            toolbar.setTitle(target.title(isArabic));
            MiraAccountingNavDrawerHelper.setChecked(navView, screenId);

            closeDrawerIfOpen();
        } finally {
            isNavigating = false;
        }
    }

    private void closeDrawerIfOpen() {
        if (drawerLayout != null
                && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    private void onBackPressed() {
        // 1) drawer open → close
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        // 2) history → pop
        if (!history.isEmpty()) {
            String prev = history.pop();
            navigateTo(prev, /*pushHistory=*/false);
            return;
        }
        // 3) من dashboard → للـ activity
        if (MiraAccountingScreens.ID_DASHBOARD.equals(currentScreenId)) {
            // ✅ [إصلاح StackOverflow]:
            //    نعطّل الـ callback أولاً قبل استدعاء dispatcher.onBackPressed().
            //    بدون هذا، الـ dispatcher يُعيد استدعاء نفس الـ callback
            //    → onBackPressed → dispatcher → callback → ...
            //    حتى ينفجر الـ stack.
            if (backCallback != null) {
                backCallback.setEnabled(false);
            }
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return;
        }
        // 4) من أي شاشة أخرى → للوحة التحكم
        navigateTo(MiraAccountingScreens.ID_DASHBOARD, /*pushHistory=*/false);
    }

    // ═══════════════════════════════════════════════════════════
    //  Permissions
    // ═══════════════════════════════════════════════════════════
    private boolean canOpen(@NonNull MiraAccountingScreens.Screen screen) {
        if (permissionManager == null) return true;
        try {
            boolean base = permissionManager.canView(MiraAccountingScreens.BASE_PERMISSION)
                    || permissionManager.isAdmin()
                    || permissionManager.isBranchManager()
                    || (sessionManager != null
                        && "AUDITOR".equals(sessionManager.getRoleString()));
            if (!base) return false;
            return permissionManager.isAdmin()
                    || permissionManager.canView(screen.permissionKey);
        } catch (Exception e) {
            Log.e(TAG, "canOpen: " + e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * قراءة flag الـ permanent drawer بأمان.
     * إن لم يُعرَّف المورد (bools.xml مفقود) → false بلا crash.
     */
    private boolean readPermanentDrawerFlag() {
        try {
            return getResources().getBoolean(R.bool.accounting_permanent_drawer);
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "accounting_permanent_drawer resource missing — defaulting to false");
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Public API — backward compatible with old container
    // ═══════════════════════════════════════════════════════════

    /** @deprecated استخدم navigateToScreen(String) */
    @Deprecated
    public void navigateToTab(int tabIndex) {
        MiraAccountingScreens.Screen s = MiraAccountingScreens.byLegacyTab(tabIndex);
        if (s != null) navigateTo(s.id, true);
    }

    public void navigateToScreen(@NonNull String screenId) {
        navigateTo(screenId, true);
    }

    public void navigateToLedgerWithAccount(long accountId) {
        // pre-set على الشاشة القائمة أو المنشأة حديثاً
        Fragment ledger = getChildFragmentManager()
                .findFragmentByTag("SCR_" + MiraAccountingScreens.ID_GENERAL_LEDGER);

        if (ledger instanceof MiraGeneralLedgerFragment) {
            ((MiraGeneralLedgerFragment) ledger).setAccountId(accountId);
        }
        navigateTo(MiraAccountingScreens.ID_GENERAL_LEDGER, true);

        // بعد الانتقال، اضبط مرة أخرى إن كانت الشاشة جديدة
        Fragment now = getChildFragmentManager()
                .findFragmentByTag("SCR_" + MiraAccountingScreens.ID_GENERAL_LEDGER);
        if (now instanceof MiraGeneralLedgerFragment && now != ledger) {
            ((MiraGeneralLedgerFragment) now).setAccountId(accountId);
        }
    }

    public void refreshCurrentTab() {
        Fragment current = getChildFragmentManager()
                .findFragmentByTag("SCR_" + currentScreenId);
        if (current instanceof MiraRefreshable) {
            try {
                ((MiraRefreshable) current).refresh();
            } catch (Exception e) {
                Log.e(TAG, "refresh: " + e.getMessage());
            }
        }
    }

    /** يتيح للـ Fragments الأخرى الاستعلام عن الشاشة الحالية */
    @NonNull
    public String getCurrentScreenId() {
        return currentScreenId;
    }

    // ═══════════════════════════════════════════════════════════
    //  Optional interface للشاشات القابلة للتحديث
    // ═══════════════════════════════════════════════════════════
    public interface MiraRefreshable {
        void refresh();
    }
}
