package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class MiraProfileFragment extends Fragment {

    private SessionManager sessionManager;
    private MiraOrgProfileManager orgProfileManager;

    private TextView tvProfileName, tvAccountNum, tvAltNum, tvRole, tvEmail, tvPhone, tvOrgName;
    private ImageView imgProfileAvatar, imgQrCode;
    private MaterialButton btnEditProfile, btnSaveProfile;
    private boolean isArabic = true;

    public MiraProfileFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mira_profile, container, false);

        sessionManager = new SessionManager(requireContext());
        orgProfileManager = MiraOrgProfileManager.getInstance(requireContext());
        isArabic = MiraLanguageHelper.getSavedLanguage(requireContext()).equals("ar");

        initViews(root);
        loadUserData();
        setupEvents(root);

        return root;
    }

    private void initViews(View root) {
        tvProfileName = root.findViewById(R.id.tvProfileName);
        tvAccountNum = root.findViewById(R.id.tvAccountNum);
        tvAltNum = root.findViewById(R.id.tvAltNum);
        tvRole = root.findViewById(R.id.tvRole);
        tvEmail = root.findViewById(R.id.tvEmail);
        tvPhone = root.findViewById(R.id.tvPhone);
        tvOrgName = root.findViewById(R.id.tvOrgName);
        
        imgProfileAvatar = root.findViewById(R.id.imgProfileAvatar);
        imgQrCode = root.findViewById(R.id.imgQrCode);

        btnEditProfile = root.findViewById(R.id.btnEditProfile);
    }

    private void loadUserData() {
        if (sessionManager != null) {
            String name = sessionManager.getDisplayName();
            if (TextUtils.isEmpty(name)) name = sessionManager.getUsername();
            
            tvProfileName.setText(name);
            tvAccountNum.setText(sessionManager.getAccountNo() != null ? sessionManager.getAccountNo() : "779033392");
            tvAltNum.setText(sessionManager.getAltNo() != null ? sessionManager.getAltNo() : "9300874");
            
            String roleText = sessionManager.getUserRole() != null ? sessionManager.getUserRole().name() : "المسؤول العام";
            tvRole.setText(roleText);
            
            tvEmail.setText(sessionManager.getEmail() != null ? sessionManager.getEmail() : "info@mira.com");
            tvPhone.setText(sessionManager.getPhone() != null ? sessionManager.getPhone() : "+967 779033392");
        }

        if (orgProfileManager != null) {
            tvOrgName.setText(orgProfileManager.getOrgName());
        }
    }

    private void setupEvents(View root) {
        // زر تعديل البيانات الشخصية
        btnEditProfile.setOnClickListener(v -> showEditProfileDialog());

        // خيارات الأقسام التفاعلية
        root.findViewById(R.id.cardSyncData).setOnClickListener(v -> 
            ToastUtil.show(requireContext(), isArabic ? "جاري تحديث بيانات الملف الشخصي..." : "Syncing Profile Data...")
        );

        root.findViewById(R.id.cardDevices).setOnClickListener(v -> 
            ToastUtil.show(requireContext(), isArabic ? "إدارة الأجهزة والمستشعرات المربوطة" : "Device Management")
        );

        root.findViewById(R.id.cardSecurity).setOnClickListener(v -> showSecurityDialog());
    }

    private void showEditProfileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_mira_profile, null);
        builder.setView(dialogView);

        EditText etName = dialogView.findViewById(R.id.etEditName);
        EditText etPhone = dialogView.findViewById(R.id.etEditPhone);
        EditText etEmail = dialogView.findViewById(R.id.etEditEmail);

        etName.setText(tvProfileName.getText());
        etPhone.setText(tvPhone.getText());
        etEmail.setText(tvEmail.getText());

        builder.setTitle(isArabic ? "تعديل بيانات الملف الشخصي" : "Edit Profile Info")
                .setPositiveButton(isArabic ? "حفظ" : "Save", (dialog, which) -> {
                    String newName = etName.getText().toString().trim();
                    String newPhone = etPhone.getText().toString().trim();
                    String newEmail = etEmail.getText().toString().trim();

                    if (!newName.isEmpty()) {
                        tvProfileName.setText(newName);
                        tvPhone.setText(newPhone);
                        tvEmail.setText(newEmail);
                        
                        // حفظ التحديثات للجلسة المحلية
                        sessionManager.setDisplayName(newName);
                        ToastUtil.show(requireContext(), isArabic ? "تم تحديث البيانات بنجاح" : "Profile Updated Successfully");
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);

        builder.create().show();
    }

    private void showSecurityDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "🛡️ الخصوصية والأمان" : "🛡️ Security & Privacy")
                .setMessage(isArabic ? "يمكنك تغيير كلمة المرور وتفعيل المصادقة الثنائية (2FA) للأجهزة." 
                                     : "Change password and enable 2FA for device authentication.")
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }
}
