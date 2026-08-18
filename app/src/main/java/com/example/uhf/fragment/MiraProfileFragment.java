package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class MiraProfileFragment extends Fragment {

    private SessionManager sessionManager;
    private MiraOrgProfileManager orgProfileManager;

    private TextView tvProfileName, tvAccountNum, tvAltNum, tvRole, tvEmail, tvPhone, tvOrgName;
    private MaterialButton btnEditProfile;
    private boolean isArabic = true;

    // Expandable Sections Container Views
    private LinearLayout layoutSecurityExpandable, layoutAppThemeExpandable;
    private ImageView imgArrowSecurity, imgArrowTheme;

    // Switches & Controls
    private SwitchMaterial switchBiometricLogin, switchBiometricConfirm;

    public MiraProfileFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mira_profile, container, false);

        sessionManager = new SessionManager(requireContext());
        orgProfileManager = MiraOrgProfileManager.getInstance(requireContext());
        isArabic = MiraLanguageHelper.getSavedLanguage(requireContext()).equals("ar");

        initViews(root);
        loadUserData();
        setupExpandableSections(root);
        setupSecurityActions(root);
        setupThemeAndLanguageActions(root);

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
        btnEditProfile = root.findViewById(R.id.btnEditProfile);

        // Sections
        layoutSecurityExpandable = root.findViewById(R.id.layoutSecurityExpandable);
        layoutAppThemeExpandable = root.findViewById(R.id.layoutAppThemeExpandable);
        imgArrowSecurity = root.findViewById(R.id.imgArrowSecurity);
        imgArrowTheme = root.findViewById(R.id.imgArrowTheme);

        // Switches
        switchBiometricLogin = root.findViewById(R.id.switchBiometricLogin);
        switchBiometricConfirm = root.findViewById(R.id.switchBiometricConfirm);
    }

    private void loadUserData() {
        if (sessionManager != null) {
            String name = sessionManager.getDisplayName();
            if (TextUtils.isEmpty(name)) name = sessionManager.getUsername();

            tvProfileName.setText(name);
            tvAccountNum.setText(sessionManager.getAccountNo() != null ? sessionManager.getAccountNo() : "779033392");
            tvAltNum.setText(sessionManager.getAltNo() != null ? sessionManager.getAltNo() : "9300874");

            String roleText = sessionManager.getUserRole() != null ? sessionManager.getUserRole().name() : (isArabic ? "المسؤول العام" : "Admin");
            tvRole.setText(roleText);

            tvEmail.setText(sessionManager.getEmail() != null ? sessionManager.getEmail() : "info@mira.com");
            tvPhone.setText(sessionManager.getPhone() != null ? sessionManager.getPhone() : "+967 779033392");
        }

        if (orgProfileManager != null) {
            tvOrgName.setText(orgProfileManager.getOrgName());
        }

        btnEditProfile.setOnClickListener(v -> showEditProfileDialog());
    }

    // ==================== EXPANDABLE ACCORDION LOGIC ====================

    private void setupExpandableSections(View root) {
        // 1. الخصوصية والأمان
        root.findViewById(R.id.headerSecurity).setOnClickListener(v -> {
            boolean isExpanded = layoutSecurityExpandable.getVisibility() == View.VISIBLE;
            layoutSecurityExpandable.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
            imgArrowSecurity.setRotation(isExpanded ? 0 : 180);
        });

        // 2. تخصيص التطبيق
        root.findViewById(R.id.headerAppTheme).setOnClickListener(v -> {
            boolean isExpanded = layoutAppThemeExpandable.getVisibility() == View.VISIBLE;
            layoutAppThemeExpandable.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
            imgArrowTheme.setRotation(isExpanded ? 0 : 180);
        });
    }

    // ==================== SECURITY ACTIONS ====================

    private void setupSecurityActions(View root) {
        // تغيير رمز التأكيد (PIN)
        root.findViewById(R.id.btnChangePin).setOnClickListener(v -> 
            showInputChangeDialog(isArabic ? "تغيير رمز التأكيد (PIN)" : "Change PIN Code", isArabic ? "أدخل رمز التأكيد الجديد:" : "Enter new PIN:")
        );

        // تغيير كلمة المرور
        root.findViewById(R.id.btnChangePassword).setOnClickListener(v -> 
            showInputChangeDialog(isArabic ? "تغيير كلمة المرور" : "Change Password", isArabic ? "أدخل كلمة المرور الجديدة:" : "Enter new password:")
        );

        // المفاتيح التفاعلية
        switchBiometricLogin.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sessionManager.setBiometricLoginEnabled(isChecked);
            ToastUtil.show(requireContext(), isChecked ? 
                    (isArabic ? "تم تفعيل البصمة لتسجيل الدخول" : "Biometric login enabled") : 
                    (isArabic ? "تم إيقاف البصمة لتسجيل الدخول" : "Biometric login disabled"));
        });

        switchBiometricConfirm.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sessionManager.setBiometricConfirmEnabled(isChecked);
            ToastUtil.show(requireContext(), isChecked ? 
                    (isArabic ? "تم تفعيل البصمة لتأكيد العمليات" : "Biometric confirmation enabled") : 
                    (isArabic ? "تم إيقاف البصمة لتأكيد العمليات" : "Biometric confirmation disabled"));
        });

        // الشروط والأحكام
        root.findViewById(R.id.btnTerms).setOnClickListener(v -> 
            new AlertDialog.Builder(requireContext())
                    .setTitle(isArabic ? "الشروط والأحكام" : "Terms & Conditions")
                    .setMessage(isArabic ? "أنظمة وشروط الاستخدام الخاصة بمنظومة Mira الرقمية..." : "Mira System usage terms and conditions...")
                    .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                    .show()
        );
    }

    // ==================== THEME & LANGUAGE ACTIONS ====================

    private void setupThemeAndLanguageActions(View root) {
        // مظهر التطبيق (Light/Dark Mode)
        root.findViewById(R.id.btnAppThemeMode).setOnClickListener(v -> {
            String[] themes = isArabic ? new String[]{"الوضع الافتراضي للنظام", "الوضع الفاتح (Light)", "الوضع الداكن (Dark)"} 
                                       : new String[]{"System Default", "Light Mode", "Dark Mode"};

            new AlertDialog.Builder(requireContext())
                    .setTitle(isArabic ? "اختر مظهر التطبيق" : "Select App Theme")
                    .setItems(themes, (dialog, which) -> {
                        if (which == 0) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                        else if (which == 1) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                        else if (which == 2) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    })
                    .show();
        });

        // لغة التطبيق
        root.findViewById(R.id.btnAppLanguage).setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                // استدعاء ميزة تبديل اللغة الموجودة في النشاط الرئيسي
                Intent intent = requireActivity().getIntent();
                String newLang = isArabic ? "en" : "ar";
                MiraLanguageHelper.applyLanguage(requireContext(), newLang);
                requireActivity().finish();
                startActivity(intent);
            }
        });
    }

    // ==================== DIALOG HELPERS ====================

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

        builder.setTitle(isArabic ? "تعديل بيانات الملف" : "Edit Profile")
                .setPositiveButton(isArabic ? "حفظ" : "Save", (dialog, which) -> {
                    String newName = etName.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        tvProfileName.setText(newName);
                        tvPhone.setText(etPhone.getText().toString().trim());
                        tvEmail.setText(etEmail.getText().toString().trim());
                        sessionManager.setDisplayName(newName);
                        ToastUtil.show(requireContext(), isArabic ? "تم حفظ البيانات" : "Saved Successfully");
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void showInputChangeDialog(String title, String hint) {
        final EditText input = new EditText(requireContext());
        input.setHint(hint);
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(input)
                .setPositiveButton(isArabic ? "تأكيد" : "Confirm", (dialog, which) -> {
                    String val = input.getText().toString();
                    if (!val.isEmpty()) {
                        ToastUtil.show(requireContext(), isArabic ? "تم التحديث بنجاح" : "Updated Successfully");
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }
}
