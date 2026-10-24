package com.example.uhf.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.navigation.NavigationManager;
import com.google.android.material.card.MaterialCardView;

public class MiraAdminHubFragment extends Fragment {

    private static final String TAG = "MiraAdminHub";

    private PermissionManager permissionManager;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_admin_hub, container, false);

        try {
            permissionManager = PermissionManager.getInstance(requireContext());
            isArabic = java.util.Locale.getDefault().getLanguage().equals("ar");

            // ═══════════════════════════════════════════════════
            //  Section 1 — تهيئة الأجهزة والشبكة
            // ═══════════════════════════════════════════════════

            bindCard(view, R.id.cardAdminSettings,
                    "ServerConfig",
                    NavigationManager.TAG_SERVER_CONFIG,
                    permissionManager.canViewServerConfig());

            bindCard(view, R.id.cardAdminReaderSetup,
                    "ReaderSetup",
                    NavigationManager.TAG_READER_SETUP,
                    permissionManager.canViewReaderSetup());

            bindCard(view, R.id.cardAdminGateSetup,
                    "GateSettings",
                    NavigationManager.TAG_GATE_SETTINGS,
                    permissionManager.canManageGates());

            bindCard(view, R.id.cardAdminSecurity,
                    "NxpSecurity",
                    "NxpSecurity",
                    permissionManager.isSystemAdmin() || permissionManager.isOrgAdmin());

            bindCard(view, R.id.cardAdminUhfSet,
                    "UHFSet",
                    "UHFSet",
                    permissionManager.canViewReaderSetup());

            bindCard(view, R.id.cardAdminGoldRates,
                    "GoldRates",
                    NavigationManager.TAG_GOLD_RATES,
                    permissionManager.canViewGoldRates());

            // ═══════════════════════════════════════════════════
            //  Section 2 — إدارة المنظومة الحاكمة
            // ═══════════════════════════════════════════════════

            // 🏢 الفروع
            bindCard(view, R.id.cardAdminBranches,
                    "Branches",
                    NavigationManager.TAG_BRANCHES,
                    permissionManager.canViewBranches());

            // 👥 المستخدمين
            bindCard(view, R.id.cardAdminUsers,
                    "UsersManagement",
                    NavigationManager.TAG_USERS_MANAGEMENT,
                    permissionManager.canManageUsers());

            // 👤 الملف الشخصي
            bindCard(view, R.id.cardAdminProfile,
                    "Profile",
                    NavigationManager.TAG_PROFILE,
                    true);

            // 📝 السندات المالية (قبض / صرف) - ✅ هنا في القسم الثاني
            bindCard(view, R.id.cardAdminVouchers,
                    "Vouchers",
                    NavigationManager.TAG_VOUCHERS,
                    permissionManager.isAdmin()
                    || permissionManager.isManager()
                    || permissionManager.isFinanceManager());

        } catch (Exception e) {
            Log.e(TAG, "onCreateView error", e);
        }

        return view;
    }

    // ═══════════════════════════════════════════════════════
    //  bindCard — البوابة الموحّدة
    // ═══════════════════════════════════════════════════════

    private void bindCard(View root,
                          int cardId,
                          String displayKey,
                          String tag,
                          boolean hasPermission) {
        if (root == null) return;

        View card = root.findViewById(cardId);
        if (card == null) {
            Log.w(TAG, "❌ Card NOT FOUND in layout: " + displayKey
                    + " (id=" + getResourceIdName(cardId) + ")");
            return;
        }

        boolean canAccess = hasPermission && permissionManager.canAccessFragment(tag);

        if (!canAccess) {
            card.setVisibility(View.GONE);
            Log.d(TAG, "⏭️ Hidden card: " + displayKey + " (no permission)");
            return;
        }

        card.setVisibility(View.VISIBLE);

        if (card instanceof MaterialCardView) {
            ((MaterialCardView) card).setClickable(true);
            ((MaterialCardView) card).setFocusable(true);
        }

        card.setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();

                Fragment target = com.example.uhf.navigation.FragmentFactory
                        .createFragmentByTag(tag);

                if (target != null) {
                    activity.loadFragmentDirectly(target, tag);
                    Log.d(TAG, "✅ Navigated to: " + tag);
                } else {
                    Log.e(TAG, "❌ FragmentFactory returned null for: " + tag);
                }
            } else {
                Log.w(TAG, "Activity is not UHFMainActivity");
            }
        });
    }

    private String getResourceIdName(int resId) {
        try {
            return getResources().getResourceEntryName(resId);
        } catch (Exception e) {
            return String.valueOf(resId);
        }
    }
}
