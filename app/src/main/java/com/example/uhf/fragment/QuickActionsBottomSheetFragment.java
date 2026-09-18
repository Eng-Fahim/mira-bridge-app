package com.example.uhf.fragment;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.card.MaterialCardView;

import java.util.Locale;

/**
 * ✅ QuickActionsBottomSheetFragment — قائمة الإجراءات السريعة الاحترافية
 * 
 * يتم استدعاؤها من UHFMainActivity عند الضغط على FAB المركزي.
 * تعرض شبكة من الإجراءات السريعة المفلترة حسب صلاحيات المستخدم.
 */
public class QuickActionsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String TAG = "QuickActionsSheet";

    private boolean isArabic;
    private PermissionManager permissionManager;
    private MiraQuickAccessSettings quickAccessSettings;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
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

            // ✅ إعداد الأزرار
            setupAction(view, R.id.cardRfidWrite,
                    R.drawable.ic_rfid_write,
                    isArabic ? "برمجة RFID" : "RFID Write",
                    "rfid_write",
                    permissionManager.canEditInventory(),
                    new MiraUHFReadWriteFragment(), "RFIDWrite");

            setupAction(view, R.id.cardRfidBindings,
                    R.drawable.ic_rfid_bindings,
                    isArabic ? "إدارة الشرائح" : "RFID Bindings",
                    "rfid",
                    permissionManager.canViewRfidBindings(),
                    new MiraRfidBindingsFragment(), "RfidBindings");

            setupAction(view, R.id.cardIdentityStudio,
                    R.drawable.ic_identity_studio,
                    isArabic ? "استوديو التكويد" : "Identity Studio",
                    "identity",
                    permissionManager.canAccessIdentityStudio(),
                    new MiraIdentityStudioFragment(), "IdentityStudio");

            setupAction(view, R.id.cardRadarLocation,
                    R.drawable.ic_radar,
                    isArabic ? "رادار التتبع" : "Radar Location",
                    "radar",
                    permissionManager.canAccessRadar(),
                    new MiraUHFRadarLocationFragment(), "Radar");

            setupAction(view, R.id.cardSecureGate,
                    R.drawable.ic_security_gate,
                    isArabic ? "مراقبة البوابات" : "Secure Gate",
                    "gate",
                    permissionManager.canAccessGate(),
                    new MiraSecureGateFragment(), "SecureGate");

            setupAction(view, R.id.cardReports,
                    R.drawable.ic_reports,
                    isArabic ? "التقارير" : "Reports",
                    "reports",
                    permissionManager.canExportReports(),
                    new MiraReportsProFragment(), "Reports");

            setupAction(view, R.id.cardAccounting,
                    R.drawable.ic_accounting,
                    isArabic ? "المالية" : "Accounting",
                    "accounting",
                    permissionManager.canAccessAccounting(),
                    new MiraAccountingFragment(), "Accounting");

            setupAction(view, R.id.cardTransfer,
                    R.drawable.ic_transfer,
                    isArabic ? "التحويلات" : "Transfers",
                    "transfers",
                    permissionManager.canAccessTransfers(),
                    new MiraTransferFragment(), "Transfers");

            setupAction(view, R.id.cardSalesHistory,
                    R.drawable.ic_sales_history,
                    isArabic ? "سجل المبيعات" : "Sales History",
                    "sales_history",
                    permissionManager.canViewSalesHistory(),
                    new MiraSalesHistoryFragment(), "SalesHistory");

            setupAction(view, R.id.cardAdminHub,
                    R.drawable.ic_admin_hub,
                    isArabic ? "الخيارات الإدارية" : "Admin Hub",
                    "admin_hub",
                    permissionManager.isAdmin(),
                    new MiraAdminHubFragment(), "AdminHub");

            setupAction(view, R.id.cardServerConfig,
                    R.drawable.ic_server,
                    isArabic ? "إعدادات السيرفر" : "Server Config",
                    "server_config",
                    permissionManager.canViewServerConfig(),
                    new MiraServerConfigFragment(), "ServerConfig");

            // ✅ زر الإغلاق
            View btnClose = view.findViewById(R.id.btnClose);
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> dismiss());
            }

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ إعداد بطاقة إجراء سريع
     */
    private void setupAction(View root, int cardId, int iconRes, String title,
                             String actionKey, boolean hasPermission,
                             Fragment targetFragment, String tag) {

        try {
            MaterialCardView card = root.findViewById(cardId);
            if (card == null) return;

            // ✅ فحص الصلاحيات + إعدادات الوصول السريع
            boolean quickAccessAllowed = quickAccessSettings == null
                    || quickAccessSettings.isActionEnabled(actionKey);
            boolean isVisible = hasPermission && quickAccessAllowed;

            if (!isVisible) {
                card.setVisibility(View.GONE);
                return;
            }

            card.setVisibility(View.VISIBLE);

            // ✅ تعيين الأيقونة والنص
            ImageView imgIcon = card.findViewById(R.id.imgActionIcon);
            TextView tvTitle = card.findViewById(R.id.tvActionTitle);

            if (imgIcon != null) imgIcon.setImageResource(iconRes);
            if (tvTitle != null) tvTitle.setText(title);

            // ✅ عند الضغط → فتح الشاشة وإغلاق القائمة
            card.setOnClickListener(v -> {
                dismiss();
                if (getActivity() instanceof UHFMainActivity && targetFragment != null) {
                    ((UHFMainActivity) getActivity()).loadFragment(targetFragment, tag);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "setupAction(" + actionKey + ") error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // ✅ ضبط ارتفاع BottomSheet ليأخذ 85% من الشاشة
        try {
            View bottomSheet = getDialog() != null
                    ? getDialog().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    : null;
            if (bottomSheet != null) {
                bottomSheet.getLayoutParams().height = (int) (getResources()
                        .getDisplayMetrics().heightPixels * 0.85);
                bottomSheet.requestLayout();
            }
        } catch (Exception e) {
            Log.e(TAG, "onStart error: " + e.getMessage());
        }
    }
}
