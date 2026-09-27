package com.example.uhf.fragment;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.Locale;

/**
 * ✅ QuickActionsBottomSheetFragment — v2.0
 *
 * تحديثات v2.0:
 * - التوافق مع fragment_quick_actions.xml الجديد (LinearLayout بدل MaterialCardView)
 * - إزالة التعديل الديناميكي للأيقونة/النص (موجودان في XML مباشرة)
 * - تحسين الـ logging للتشخيص
 */
public class QuickActionsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String TAG = "QuickActionsSheet";

    private boolean isArabic;
    private PermissionManager permissionManager;
    private MiraQuickAccessSettings quickAccessSettings;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_quick_actions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context context = requireContext();
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            permissionManager = PermissionManager.getInstance(context);
            quickAccessSettings = MiraQuickAccessSettings.getInstance(context);

            Log.d(TAG, "🚀 Initializing Quick Actions sheet");

            // ═══════════════════════════════════════════════════════
            // GROUP 1 — الأجهزة والتكويد
            // ═══════════════════════════════════════════════════════

            setupAction(view, R.id.cardRfidWrite,
                    "rfid_write",
                    permissionManager.canEditInventory(),
                    new MiraUHFReadWriteFragment(), "RFIDWrite");

            setupAction(view, R.id.cardIdentityStudio,
                    "identity",
                    permissionManager.canAccessIdentityStudio(),
                    new MiraIdentityStudioFragment(), "IdentityStudio");

            setupAction(view, R.id.cardRadarLocation,
                    "radar",
                    permissionManager.canAccessRadar(),
                    new MiraUHFRadarLocationFragment(), "Radar");

            setupAction(view, R.id.cardRfidBindings,
                    "rfid",
                    permissionManager.canViewRfidBindings(),
                    new MiraRfidBindingsFragment(), "RfidBindings");

            // ═══════════════════════════════════════════════════════
            // GROUP 2 — العمليات والمالية
            // ═══════════════════════════════════════════════════════

            setupAction(view, R.id.cardReports,
                    "reports",
                    permissionManager.canExportReports(),
                    new MiraReportsProFragment(), "Reports");

            setupAction(view, R.id.cardAccounting,
                    "accounting",
                    permissionManager.canAccessAccounting(),
                    new MiraAccountingFragment(), "Accounting");

            setupAction(view, R.id.cardTransfer,
                    "transfers",
                    permissionManager.canAccessTransfers(),
                    new MiraTransferFragment(), "Transfers");

            setupAction(view, R.id.cardSalesHistory,
                    "sales_history",
                    permissionManager.canViewSalesHistory(),
                    new MiraSalesHistoryFragment(), "SalesHistory");

            // ═══════════════════════════════════════════════════════
            // GROUP 3 — الإدارة والبوابات
            // ═══════════════════════════════════════════════════════

            setupAction(view, R.id.cardSecureGate,
                    "gate",
                    permissionManager.canAccessGate(),
                    new MiraSecureGateFragment(), "SecureGate");

            setupAction(view, R.id.cardAdminHub,
                    "admin_hub",
                    permissionManager.isAdmin(),
                    new MiraAdminHubFragment(), "AdminHub");

            setupAction(view, R.id.cardServerConfig,
                    "server_config",
                    permissionManager.canViewServerConfig(),
                    new MiraServerConfigFragment(), "ServerConfig");

            // ═══════════════════════════════════════════════════════
            // CLOSE BUTTON
            // ═══════════════════════════════════════════════════════

            View btnClose = view.findViewById(R.id.btnClose);
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> {
                    Log.d(TAG, "🔒 Close button clicked");
                    dismiss();
                });
            }

            Log.d(TAG, "✅ Quick Actions sheet ready");

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ إعداد إجراء سريع — النسخة v2
     *
     * @param root              الـ View الجذر
     * @param cardId            معرّف العنصر (LinearLayout)
     * @param actionKey         مفتاح الإجراء (للوصول السريع)
     * @param hasPermission     هل المستخدم لديه صلاحية
     * @param targetFragment    الشاشة المستهدفة
     * @param tag               وسيم التنقل
     */
    private void setupAction(View root,
                             int cardId,
                             String actionKey,
                             boolean hasPermission,
                             Fragment targetFragment,
                             String tag) {

        try {
            // ✅ العنصر الآن LinearLayout
            View card = root.findViewById(cardId);

            if (card == null) {
                Log.w(TAG, "setupAction: view not found for id=" + cardId
                        + ", key=" + actionKey);
                return;
            }

            // ✅ فحص الصلاحيات + إعدادات الوصول السريع
            boolean quickAccessAllowed = quickAccessSettings == null
                    || quickAccessSettings.isActionEnabled(actionKey);
            boolean isVisible = hasPermission && quickAccessAllowed;

            if (!isVisible) {
                card.setVisibility(View.GONE);
                Log.d(TAG, "⏭️ Hidden: " + actionKey
                        + " (permission=" + hasPermission
                        + ", quickAccess=" + quickAccessAllowed + ")");
                return;
            }

            card.setVisibility(View.VISIBLE);

            // ✅ عند الضغط → فتح الشاشة + إغلاق القائمة
            card.setOnClickListener(v -> {
                Log.d(TAG, "✅ Clicked: " + actionKey);

                // إغلاق Bottom Sheet
                dismiss();

                // فتح الشاشة المستهدفة
                if (getActivity() instanceof UHFMainActivity && targetFragment != null) {
                    ((UHFMainActivity) getActivity()).loadFragment(targetFragment, tag);
                } else {
                    Log.w(TAG, "Cannot navigate: activity="
                            + getActivity() + ", fragment=" + targetFragment);
                    Toast.makeText(getContext(),
                            "تعذر فتح الشاشة", Toast.LENGTH_SHORT).show();
                }
            });

            Log.d(TAG, "✅ Registered: " + actionKey);

        } catch (Exception e) {
            Log.e(TAG, "setupAction(" + actionKey + ") error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // ✅ ضبط ارتفاع Bottom Sheet ليأخذ 85% من الشاشة
        try {
            View bottomSheet = getDialog() != null
                    ? getDialog().findViewById(
                            com.google.android.material.R.id.design_bottom_sheet)
                    : null;

            if (bottomSheet != null) {
                bottomSheet.getLayoutParams().height =
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.85);
                bottomSheet.requestLayout();
            }
        } catch (Exception e) {
            Log.e(TAG, "onStart error: " + e.getMessage());
        }
    }
}
