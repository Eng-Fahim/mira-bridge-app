package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.SessionManager;

public class MiraProfileFragment extends Fragment {

    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_profile, container, false);

        sessionManager = new SessionManager(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        setupProfileData(view);
        setupActions(view);

        return view;
    }

    private void setupProfileData(View root) {
        TextView tvName = root.findViewById(R.id.tvProfileName);
        TextView tvRole = root.findViewById(R.id.tvUserRole);
        TextView tvAccountNumber = root.findViewById(R.id.tvAccountNumber);
        TextView tvAltNumber = root.findViewById(R.id.tvAltNumber);

        if (sessionManager != null) {
            String name = sessionManager.getDisplayName();
            if (name == null || name.isEmpty()) name = sessionManager.getUsername();
            tvName.setText(name != null ? name : "فهيم محسن الهاشمي");

            String role = sessionManager.getUserRole() != null ? sessionManager.getUserRole().name() : "مدير النظام";
            MiraOrgProfileManager org = MiraOrgProfileManager.getInstance(requireContext());
            String orgName = (org != null && org.getOrgName() != null) ? org.getOrgName() : "MIRA Systems";
            tvRole.setText(role + " | " + orgName);

            // تعيين الأرقام العشوائية أو المسجلة للمستخدم
            tvAccountNumber.setText("779033392");
            tvAltNumber.setText("9300874");
        }
    }

    private void setupActions(View root) {
        // 1. تحديث بيانات التطبيق
        setupRowItem(root.findViewById(R.id.itemSyncData), "تحديث بيانات التطبيق والمخزون", android.R.drawable.ic_popup_sync, v -> {
            Toast.makeText(getContext(), "جاري مزامنة أحدث البيانات من السيرفر...", Toast.LENGTH_SHORT).show();
        });

        // 2. إدارة الأجهزة وعتاد RFID
        setupRowItem(root.findViewById(R.id.itemDeviceManagement), "إدارة أجهزة RFID ومستشعرات البوابات", android.R.drawable.ic_menu_compass, v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(new MiraReaderSetupFragment(), "ReaderSetup");
            }
        });

        // 3. ملف الفرع والمؤسسة
        setupRowItem(root.findViewById(R.id.itemBranchInfo), "بيانات الفرع والمؤسسة الحالية", android.R.drawable.ic_menu_myplaces, v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(new MiraBranchesManagementFragment(), "Branches");
            }
        });

        // 4. الخصوصية والأمان والتشفير
        setupRowItem(root.findViewById(R.id.itemSecurity), "الخصوصية وأمان بطاقات NXP DNA", android.R.drawable.ic_lock_lock, v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(new AuthenticateNxpUcodeDnaFragment(), "Security");
            }
        });

        // 5. إعدادات إضافية وتخصيص
        setupRowItem(root.findViewById(R.id.itemAppSettings), "إعدادات القارئ والترددات (UHF Set)", android.R.drawable.ic_menu_preferences, v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(new UHFSetFragment(), "UHFSet");
            }
        });

        // 6. الدعم والمساعدة
        setupRowItem(root.findViewById(R.id.itemSupport), "الدعم الفني والمساعدة", android.R.drawable.ic_menu_help, v -> {
            Toast.makeText(getContext(), "منظومة MIRA Digital Trust - الدعم الفني جاهز 24/7", Toast.LENGTH_LONG).show();
        });

        // زر تسجيل الخروج
        View btnLogout = root.findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> confirmLogout());
        }

        // زر QR Code
        View btnQrCode = root.findViewById(R.id.btnQrCode);
        if (btnQrCode != null) {
            btnQrCode.setOnClickListener(v -> {
                Toast.makeText(getContext(), "رمز QR الخاص بهوية الحساب جاهز للمسح", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void setupRowItem(View itemLayout, String title, int iconRes, View.OnClickListener listener) {
        if (itemLayout == null) return;

        TextView tvTitle = itemLayout.findViewById(R.id.tvActionTitle);
        ImageView imgIcon = itemLayout.findViewById(R.id.imgActionIcon);

        if (tvTitle != null) tvTitle.setText(title);
        if (imgIcon != null) imgIcon.setImageResource(iconRes);

        itemLayout.setOnClickListener(listener);
    }

    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle("تسجيل الخروج")
                .setMessage("هل أنت تأكد من تسجيل الخروج من المنظومة؟")
                .setPositiveButton("تأكيد الخروج", (dialog, which) -> {
                    if (sessionManager != null) {
                        sessionManager.logout();
                    }
                    if (getActivity() != null) {
                        getActivity().finish();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
