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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class MiraProfileFragment extends Fragment {

    private TextView tvProfileName, tvRole, tvAccountNum, tvAltNum;
    private TextView tvOrgName, tvPhone, tvEmail;
    private ImageView imgArrowSecurity, imgArrowTheme;
    private LinearLayout layoutSecurityExpandable, layoutAppThemeExpandable;
    private SwitchMaterial switchBiometricLogin, switchBiometricConfirm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mira_profile, container, false);

        initViews(root);
        setupListeners(root);
        loadUserData();

        return root;
    }

    private void initViews(View root) {
        tvProfileName = root.findViewById(R.id.tvProfileName);
        tvRole = root.findViewById(R.id.tvRole);
        tvAccountNum = root.findViewById(R.id.tvAccountNum);
        tvAltNum = root.findViewById(R.id.tvAltNum);

        tvOrgName = root.findViewById(R.id.tvOrgName);
        tvPhone = root.findViewById(R.id.tvPhone);
        tvEmail = root.findViewById(R.id.tvEmail);

        imgArrowSecurity = root.findViewById(R.id.imgArrowSecurity);
        imgArrowTheme = root.findViewById(R.id.imgArrowTheme);

        layoutSecurityExpandable = root.findViewById(R.id.layoutSecurityExpandable);
        layoutAppThemeExpandable = root.findViewById(R.id.layoutAppThemeExpandable);

        switchBiometricLogin = root.findViewById(R.id.switchBiometricLogin);
        switchBiometricConfirm = root.findViewById(R.id.switchBiometricConfirm);
    }

    private void setupListeners(View root) {
        MaterialButton btnEditProfile = root.findViewById(R.id.btnEditProfile);
        if (btnEditProfile != null) {
            btnEditProfile.setOnClickListener(v -> showEditProfileDialog());
        }

        View headerSecurity = root.findViewById(R.id.headerSecurity);
        if (headerSecurity != null) {
            headerSecurity.setOnClickListener(v -> toggleSection(layoutSecurityExpandable, imgArrowSecurity));
        }

        View headerAppTheme = root.findViewById(R.id.headerAppTheme);
        if (headerAppTheme != null) {
            headerAppTheme.setOnClickListener(v -> toggleSection(layoutAppThemeExpandable, imgArrowTheme));
        }

        if (switchBiometricLogin != null) {
            switchBiometricLogin.setOnCheckedChangeListener((buttonView, isChecked) -> 
                showNotification(isChecked ? "تم تفعيل بصمة الدخول" : "تم إلغاء بصمة الدخول"));
        }

        if (switchBiometricConfirm != null) {
            switchBiometricConfirm.setOnCheckedChangeListener((buttonView, isChecked) -> 
                showNotification(isChecked ? "تم تفعيل بصمة التأكيد" : "تم إلغاء بصمة التأكيد"));
        }
    }

    private void toggleSection(LinearLayout layout, ImageView arrow) {
        if (layout == null) return;
        boolean isVisible = layout.getVisibility() == View.VISIBLE;
        layout.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        if (arrow != null) {
            arrow.animate().rotation(isVisible ? 0 : 180).setDuration(200).start();
        }
    }

    private void loadUserData() {
        if (tvProfileName != null) tvProfileName.setText("فهيم الهاشمي");
        if (tvRole != null) tvRole.setText("المسؤول العام");
        if (tvAccountNum != null) tvAccountNum.setText("779033392");
        if (tvAltNum != null) tvAltNum.setText("9300874");

        if (tvOrgName != null) tvOrgName.setText("شركة ميرا للتكنولوجيا");
        if (tvPhone != null) tvPhone.setText("+967 779033392");
        if (tvEmail != null) tvEmail.setText("info@mira.com");
    }

    private void showEditProfileDialog() {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_mira_profile, null);
        EditText etEditName = dialogView.findViewById(R.id.etEditName);
        EditText etEditPhone = dialogView.findViewById(R.id.etEditPhone);
        EditText etEditEmail = dialogView.findViewById(R.id.etEditEmail);

        if (tvOrgName != null && etEditName != null) etEditName.setText(tvOrgName.getText());
        if (tvPhone != null && etEditPhone != null) etEditPhone.setText(tvPhone.getText());
        if (tvEmail != null && etEditEmail != null) etEditEmail.setText(tvEmail.getText());

        new MaterialAlertDialogBuilder(getContext())
                .setView(dialogView)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    if (tvOrgName != null && etEditName != null && !TextUtils.isEmpty(etEditName.getText())) {
                        tvOrgName.setText(etEditName.getText().toString().trim());
                    }
                    if (tvPhone != null && etEditPhone != null && !TextUtils.isEmpty(etEditPhone.getText())) {
                        tvPhone.setText(etEditPhone.getText().toString().trim());
                    }
                    if (tvEmail != null && etEditEmail != null && !TextUtils.isEmpty(etEditEmail.getText())) {
                        tvEmail.setText(etEditEmail.getText().toString().trim());
                    }
                    showNotification("تم تحديث البيانات بنجاح");
                })
                .setNegativeButton("إلغاء", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showNotification(String message) {
        if (isAdded()) {
            ToastUtil.show(message);
        }
    }
}
