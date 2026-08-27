package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraProfileFragment extends Fragment {

    private static final String TAG = "MiraProfileFragment";

    // UI
    private ShapeableImageView imgUserAvatar;
    private TextView tvProfileName, tvRole, tvAccountNum, tvBranchName, tvUsername;
    private TextView tvOrgNameAr, tvOrgNameEn, tvPhone, tvEmail, tvTaxNum;
    private ImageView imgArrowSecurity, imgArrowTheme;
    private LinearLayout layoutSecurityExpandable, layoutAppThemeExpandable;
    private Switch switchBiometricLogin, switchBiometricConfirm;
    private RadioGroup radioThemeGroup;
    private RadioButton radioThemeLight, radioThemeDark, radioThemeSystem;
    private MaterialButton btnEditOrgProfile, btnEditProfile, btnChangePassword, btnChangePhoto, btnLogout;

    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private MiraOrgProfileManager orgProfileManager;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;
    
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    
    private boolean isArabic = true;
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mira_profile, container, false);
        
        isDestroyed.set(false);
        isArabic = java.util.Locale.getDefault().getLanguage().equals("ar");
        
        sessionManager = new SessionManager(requireContext());
        apiClient = MiraApiClient.getInstance(requireContext());
        orgProfileManager = MiraOrgProfileManager.getInstance(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        initViews(root);
        setupImagePicker();
        setupListeners(root);
        
        loadLocalProfileData();
        fetchProfileFromServer();

        return root;
    }

    private void initViews(View root) {
        imgUserAvatar = root.findViewById(R.id.imgUserAvatar);
        tvProfileName = root.findViewById(R.id.tvProfileName);
        tvUsername = root.findViewById(R.id.tvUsername);
        tvRole = root.findViewById(R.id.tvRole);
        tvAccountNum = root.findViewById(R.id.tvAccountNum);
        tvBranchName = root.findViewById(R.id.tvBranchName);

        tvOrgNameAr = root.findViewById(R.id.tvOrgNameAr);
        tvOrgNameEn = root.findViewById(R.id.tvOrgNameEn);
        tvPhone = root.findViewById(R.id.tvPhone);
        tvEmail = root.findViewById(R.id.tvEmail);
        tvTaxNum = root.findViewById(R.id.tvTaxNum);

        imgArrowSecurity = root.findViewById(R.id.imgArrowSecurity);
        imgArrowTheme = root.findViewById(R.id.imgArrowTheme);

        layoutSecurityExpandable = root.findViewById(R.id.layoutSecurityExpandable);
        layoutAppThemeExpandable = root.findViewById(R.id.layoutAppThemeExpandable);

        switchBiometricLogin = root.findViewById(R.id.switchBiometricLogin);
        switchBiometricConfirm = root.findViewById(R.id.switchBiometricConfirm);
        
        // ✅ عناصر المظهر
        radioThemeGroup = root.findViewById(R.id.radioThemeGroup);
        radioThemeLight = root.findViewById(R.id.radioThemeLight);
        radioThemeDark = root.findViewById(R.id.radioThemeDark);
        radioThemeSystem = root.findViewById(R.id.radioThemeSystem);
        
        btnEditOrgProfile = root.findViewById(R.id.btnEditOrgProfile);
        btnEditProfile = root.findViewById(R.id.btnEditProfile);
        btnChangePassword = root.findViewById(R.id.btnChangePassword);
        btnChangePhoto = root.findViewById(R.id.btnChangePhoto);
        btnLogout = root.findViewById(R.id.btnLogout);
    }

    private void setupImagePicker() {
        imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        uploadProfileImage(imageUri);
                    }
                }
            }
        );
    }

    private void pickImage() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            imagePickerLauncher.launch(intent);
        } catch (Exception e) {
            logNonFatal("pickImage", e);
            showToast(isArabic ? "❌ تعذر فتح المعرض" : "❌ Could not open gallery");
        }
    }

    private void setupListeners(View root) {
        if (btnEditOrgProfile != null) {
            if (permissionManager.isAdmin()) {
                btnEditOrgProfile.setVisibility(View.VISIBLE);
                btnEditOrgProfile.setOnClickListener(v -> showEditOrgDialog());
            } else {
                btnEditOrgProfile.setVisibility(View.GONE);
            }
        }
        
        if (btnEditProfile != null) {
            btnEditProfile.setOnClickListener(v -> showEditUserDialog());
        }
        
        if (btnChangePassword != null) {
            btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());
        }
        
        if (btnChangePhoto != null) {
            btnChangePhoto.setOnClickListener(v -> pickImage());
        }
        
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> showLogoutDialog());
        }

        View headerSecurity = root.findViewById(R.id.headerSecurity);
        if (headerSecurity != null) {
            headerSecurity.setOnClickListener(v -> toggleSection(layoutSecurityExpandable, imgArrowSecurity));
        }

        View headerAppTheme = root.findViewById(R.id.headerAppTheme);
        if (headerAppTheme != null) {
            headerAppTheme.setOnClickListener(v -> toggleSection(layoutAppThemeExpandable, imgArrowTheme));
        }

        if (imgUserAvatar != null) {
            imgUserAvatar.setOnClickListener(v -> pickImage());
        }

        if (switchBiometricLogin != null) {
            switchBiometricLogin.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sessionManager.setBiometricLoginEnabled(isChecked);
                saveBiometricPreference("enable_biometric_login", isChecked);
            });
        }
        
        if (switchBiometricConfirm != null) {
            switchBiometricConfirm.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sessionManager.setBiometricConfirmEnabled(isChecked);
                saveBiometricPreference("enable_biometric_confirm", isChecked);
            });
        }
        
        // ✅ مستمع تغيير المظهر
        if (radioThemeGroup != null) {
            radioThemeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (isDestroyed.get()) return;
                
                int mode;
                if (checkedId == R.id.radioThemeDark) {
                    mode = AppCompatDelegate.MODE_NIGHT_YES;
                } else if (checkedId == R.id.radioThemeSystem) {
                    mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                } else {
                    mode = AppCompatDelegate.MODE_NIGHT_NO;
                }
                
                AppCompatDelegate.setDefaultNightMode(mode);
                saveThemePreference(mode);
                
                showToast(isArabic ? "✅ تم تغيير المظهر" : "✅ Theme changed");
            });
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

    private void loadLocalProfileData() {
        try {
            String displayName = sessionManager.getDisplayName();
            String username = sessionManager.getUsername();
            String phone = sessionManager.getPhone();
            String role = sessionManager.getRole();
            
            if (tvProfileName != null && !TextUtils.isEmpty(displayName)) {
                tvProfileName.setText(displayName);
            }
            
            if (tvUsername != null && !TextUtils.isEmpty(username)) {
                tvUsername.setText("@" + username);
            }
            
            if (tvRole != null) {
                tvRole.setText(role != null ? role : (isArabic ? "موظف" : "Employee"));
            }
            
            if (tvAccountNum != null && !TextUtils.isEmpty(phone)) {
                tvAccountNum.setText(phone);
            }
            
            if (tvBranchName != null && branchesManager != null) {
                tvBranchName.setText("🏢 " + branchesManager.getActiveBranchDisplayName());
            }
            
            loadUserAvatar();
            
            if (tvOrgNameAr != null) tvOrgNameAr.setText(orgProfileManager.getOrgNameAr());
            if (tvOrgNameEn != null) tvOrgNameEn.setText(orgProfileManager.getOrgNameEn());
            if (tvPhone != null) tvPhone.setText(orgProfileManager.getOrgPhone());
            if (tvEmail != null) tvEmail.setText(orgProfileManager.getOrgEmail());
            if (tvTaxNum != null) {
                String tax = orgProfileManager.getTaxNumber();
                if (!TextUtils.isEmpty(tax)) {
                    tvTaxNum.setText((isArabic ? "الرقم الضريبي: " : "Tax Number: ") + tax);
                }
            }
            
            if (switchBiometricLogin != null) switchBiometricLogin.setChecked(sessionManager.isBiometricLoginEnabled());
            if (switchBiometricConfirm != null) switchBiometricConfirm.setChecked(sessionManager.isBiometricConfirmEnabled());
            
            // ✅ تحميل إعداد المظهر
            loadThemePreference();
            
        } catch (Exception e) {
            logNonFatal("loadLocalProfileData", e);
        }
    }

    /**
     * ✅ تحميل إعداد المظهر
     */
    private void loadThemePreference() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("app_theme", Context.MODE_PRIVATE);
            int mode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            
            if (radioThemeLight != null) radioThemeLight.setChecked(mode == AppCompatDelegate.MODE_NIGHT_NO);
            if (radioThemeDark != null) radioThemeDark.setChecked(mode == AppCompatDelegate.MODE_NIGHT_YES);
            if (radioThemeSystem != null) radioThemeSystem.setChecked(mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        } catch (Exception e) {
            logNonFatal("loadThemePreference", e);
        }
    }

    /**
     * ✅ حفظ إعداد المظهر
     */
    private void saveThemePreference(int mode) {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("app_theme", Context.MODE_PRIVATE);
            prefs.edit().putInt("theme_mode", mode).apply();
        } catch (Exception e) {
            logNonFatal("saveThemePreference", e);
        }
    }

    private void loadUserAvatar() {
        try {
            SharedPreferences prefs = requireContext().getSharedPreferences("user_avatar", Context.MODE_PRIVATE);
            String avatarPath = prefs.getString("avatar_path", "");
            
            if (!avatarPath.isEmpty()) {
                File avatarFile = new File(avatarPath);
                if (avatarFile.exists() && imgUserAvatar != null && isAdded()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(avatarFile.getAbsolutePath());
                    if (bitmap != null) {
                        imgUserAvatar.setImageBitmap(bitmap);
                    }
                }
            }
        } catch (Exception e) {
            logNonFatal("loadUserAvatar", e);
        }
    }

    private void uploadProfileImage(Uri imageUri) {
        showLoading(true);
        
        executeSafely(() -> {
            try {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                    requireContext().getContentResolver(), imageUri);
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                byte[] imageBytes = baos.toByteArray();
                
                File avatarFile = new File(requireContext().getFilesDir(), "user_avatar.jpg");
                FileOutputStream fos = new FileOutputStream(avatarFile);
                fos.write(imageBytes);
                fos.close();
                
                SharedPreferences prefs = requireContext().getSharedPreferences("user_avatar", Context.MODE_PRIVATE);
                prefs.edit().putString("avatar_path", avatarFile.getAbsolutePath()).apply();
                
                String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);
                
                JSONObject payload = new JSONObject();
                payload.put("avatar_base64", base64Image);
                
                // ✅✅✅ استخدام الدالة الصحيحة
                MiraApiClient.ApiResponse response = apiClient.uploadUserAvatar(payload);
                
                mainHandler.post(() -> {
                    if (isDestroyed.get() || !isAdded()) return;
                    showLoading(false);
                    
                    if (response != null && response.isSuccess) {
                        String serverUrl = response.data != null ? 
                            response.data.optString("avatar_url", "") : "";
                        if (!serverUrl.isEmpty()) {
                            prefs.edit().putString("avatar_url", serverUrl).apply();
                        }
                        showToast(isArabic ? "✅ تم تحديث الصورة" : "✅ Photo updated");
                        loadUserAvatar();
                    } else {
                        showToast(isArabic ? "💾 حفظ محلي فقط" : "💾 Local save only");
                        loadUserAvatar();
                    }
                });
            } catch (Exception e) {
                logNonFatal("uploadProfileImage", e);
                mainHandler.post(() -> {
                    if (!isDestroyed.get() && isAdded()) {
                        showLoading(false);
                        showToast(isArabic ? "❌ خطأ في رفع الصورة" : "❌ Upload error");
                    }
                });
            }
        });
    }

    private void fetchProfileFromServer() {
    if (isDestroyed.get() || !isAdded()) return;
    
    // ❌❌❌ احذف هذا السطر - يسبب الوميض
    // showLoading(true);

    executeSafely(() -> {
        try {
            MiraApiClient.ApiResponse response = apiClient.getOrgProfile();
            
            if (response != null && response.isSuccess && response.data != null) {
                JSONObject user = response.data.optJSONObject("user");
                JSONObject org = response.data.optJSONObject("organization");
                
                if (org != null) {
                    orgProfileManager.saveFromServer(org);
                }
                
                if (user != null) {
                    String displayName = user.optString("display_name", "");
                    String role = user.optString("role", "");
                    String phone = user.optString("phone", "");
                    
                    if (!TextUtils.isEmpty(displayName)) sessionManager.setDisplayName(displayName);
                    if (!TextUtils.isEmpty(role)) sessionManager.setRole(role);
                    if (!TextUtils.isEmpty(phone)) sessionManager.setPhone(phone);
                }
                
                final JSONObject finalUser = user;
                final JSONObject finalOrg = org;
                
                mainHandler.post(() -> {
                    if (!isDestroyed.get() && isAdded()) {
                        updateUIWithData(finalUser, finalOrg);
                    }
                });
            }
        } catch (Exception e) {
            logNonFatal("fetchProfileFromServer.thread", e);
        }
        // ❌❌❌ احذف هذا السطر أيضاً
        // showLoading(false);
    });
}

    private void updateUIWithData(JSONObject user, JSONObject org) {
        if (user != null) {
            if (tvProfileName != null) tvProfileName.setText(user.optString("display_name", ""));
            if (tvUsername != null) {
                String username = user.optString("username", "");
                if (!TextUtils.isEmpty(username)) tvUsername.setText("@" + username);
            }
            if (tvRole != null) tvRole.setText(user.optString("role", ""));
            if (tvAccountNum != null) tvAccountNum.setText(user.optString("phone", ""));
            if (tvBranchName != null) {
                String branchName = user.optString("branch_name", "");
                if (!TextUtils.isEmpty(branchName)) tvBranchName.setText("🏢 " + branchName);
            }
        }
        
        if (org != null) {
            if (tvOrgNameAr != null) tvOrgNameAr.setText(org.optString("name_ar", ""));
            if (tvOrgNameEn != null) tvOrgNameEn.setText(org.optString("name_en", ""));
            if (tvPhone != null) tvPhone.setText(org.optString("phone", ""));
            if (tvEmail != null) tvEmail.setText(org.optString("email", ""));
            if (tvTaxNum != null) {
                String tax = org.optString("tax_number", "");
                if (!TextUtils.isEmpty(tax)) tvTaxNum.setText((isArabic ? "الرقم الضريبي: " : "Tax Number: ") + tax);
            }
        }
    }

    private void showEditOrgDialog() {
        if (getContext() == null || isDestroyed.get()) return;
        if (!permissionManager.isAdmin()) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }
        
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_mira_profile, null);
        EditText etNameAr = dialogView.findViewById(R.id.etEditNameAr);
        EditText etNameEn = dialogView.findViewById(R.id.etEditNameEn);
        EditText etPhone = dialogView.findViewById(R.id.etEditPhone);
        EditText etEmail = dialogView.findViewById(R.id.etEditEmail);
        EditText etTax = dialogView.findViewById(R.id.etEditTax);
        
        if (tvOrgNameAr != null && etNameAr != null) etNameAr.setText(tvOrgNameAr.getText());
        if (tvOrgNameEn != null && etNameEn != null) etNameEn.setText(tvOrgNameEn.getText());
        if (tvPhone != null && etPhone != null) etPhone.setText(tvPhone.getText());
        if (tvEmail != null && etEmail != null) etEmail.setText(tvEmail.getText());
        
        if (etTax != null && tvTaxNum != null) {
            String taxText = tvTaxNum.getText().toString();
            String tax = taxText.replace(isArabic ? "الرقم الضريبي: " : "Tax Number: ", "").trim();
            etTax.setText(tax);
        }
        
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(isArabic ? "✏️ تعديل بيانات المنظمة" : "✏️ Edit Organization")
                .setView(dialogView)
                .setPositiveButton(isArabic ? "حفظ" : "Save", (dialog, which) -> {
                    String nameAr = etNameAr != null ? etNameAr.getText().toString().trim() : "";
                    String nameEn = etNameEn != null ? etNameEn.getText().toString().trim() : "";
                    String phone = etPhone != null ? etPhone.getText().toString().trim() : "";
                    String email = etEmail != null ? etEmail.getText().toString().trim() : "";
                    String tax = etTax != null ? etTax.getText().toString().trim() : "";
                    
                    saveOrgProfileToServer(nameAr, nameEn, phone, email, tax);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void showEditUserDialog() {
        if (getContext() == null || isDestroyed.get()) return;
        
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_user_profile, null);
        EditText etDisplayName = dialogView.findViewById(R.id.etEditDisplayName);
        EditText etPhone = dialogView.findViewById(R.id.etEditUserPhone);
        
        if (tvProfileName != null && etDisplayName != null) etDisplayName.setText(tvProfileName.getText());
        if (tvAccountNum != null && etPhone != null) etPhone.setText(tvAccountNum.getText());
        
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(isArabic ? "👤 تعديل الملف الشخصي" : "👤 Edit Profile")
                .setView(dialogView)
                .setPositiveButton(isArabic ? "حفظ" : "Save", (dialog, which) -> {
                    String displayName = etDisplayName != null ? etDisplayName.getText().toString().trim() : "";
                    String phone = etPhone != null ? etPhone.getText().toString().trim() : "";
                    
                    saveUserProfileToServer(displayName, phone);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void showChangePasswordDialog() {
        if (getContext() == null || isDestroyed.get()) return;
        
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_change_password, null);
        EditText etCurrentPassword = dialogView.findViewById(R.id.etCurrentPassword);
        EditText etNewPassword = dialogView.findViewById(R.id.etNewPassword);
        EditText etConfirmPassword = dialogView.findViewById(R.id.etConfirmPassword);
        
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(isArabic ? "🔒 تغيير كلمة المرور" : "🔒 Change Password")
                .setView(dialogView)
                .setPositiveButton(isArabic ? "تغيير" : "Change", (dialog, which) -> {
                    String currentPassword = etCurrentPassword != null ? etCurrentPassword.getText().toString() : "";
                    String newPassword = etNewPassword != null ? etNewPassword.getText().toString() : "";
                    String confirmPassword = etConfirmPassword != null ? etConfirmPassword.getText().toString() : "";
                    
                    if (currentPassword.isEmpty() || newPassword.isEmpty()) {
                        showToast(isArabic ? "⚠️ جميع الحقول مطلوبة" : "⚠️ All fields required");
                        return;
                    }
                    
                    if (!newPassword.equals(confirmPassword)) {
                        showToast(isArabic ? "⚠️ كلمتا المرور غير متطابقتين" : "⚠️ Passwords do not match");
                        return;
                    }
                    
                    if (newPassword.length() < 6) {
                        showToast(isArabic ? "⚠️ كلمة المرور قصيرة (6 أحرف على الأقل)" : "⚠️ Password too short (min 6)");
                        return;
                    }
                    
                    changePassword(currentPassword, newPassword);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void changePassword(String currentPassword, String newPassword) {
        showLoading(true);
        
        executeSafely(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("current_password", currentPassword);
                payload.put("new_password", newPassword);
                
                MiraApiClient.ApiResponse response = apiClient.changePassword(currentPassword, newPassword);
                
                mainHandler.post(() -> {
                    if (isDestroyed.get() || !isAdded()) return;
                    showLoading(false);
                    
                    if (response != null && response.isSuccess) {
                        showToast(isArabic ? "✅ تم تغيير كلمة المرور" : "✅ Password changed");
                    } else {
                        String error = response != null ? response.error : "Unknown error";
                        showToast((isArabic ? "❌ فشل: " : "❌ Failed: ") + error);
                    }
                });
            } catch (Exception e) {
                logNonFatal("changePassword.thread", e);
                mainHandler.post(() -> {
                    if (!isDestroyed.get() && isAdded()) {
                        showLoading(false);
                        showToast(isArabic ? "❌ خطأ" : "❌ Error");
                    }
                });
            }
        });
    }

    private void showLogoutDialog() {
        if (getContext() == null || isDestroyed.get()) return;
        
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(isArabic ? "🚪 تسجيل الخروج" : "🚪 Logout")
                .setMessage(isArabic ? "هل أنت متأكد من تسجيل الخروج؟" : "Are you sure you want to logout?")
                .setPositiveButton(isArabic ? "تسجيل الخروج" : "Logout", (dialog, which) -> {
                    performLogout();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void performLogout() {
        executeSafely(() -> {
            try {
                String token = sessionManager.getAuthToken();
                if (!TextUtils.isEmpty(token)) {
                    apiClient.logout(token);
                }
            } catch (Exception e) {
                logNonFatal("performLogout", e);
            }
            
            mainHandler.post(() -> {
                if (isDestroyed.get() || !isAdded()) return;
                sessionManager.logout();
                if (getActivity() != null) {
                    getActivity().finish();
                }
            });
        });
    }

    private void saveOrgProfileToServer(String nameAr, String nameEn, String phone, String email, String tax) {
        if (!permissionManager.isAdmin()) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }
        
        showLoading(true);

        executeSafely(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("org_name_ar", nameAr);
                payload.put("org_name_en", nameEn);
                payload.put("org_phone", phone);
                payload.put("org_email", email);
                payload.put("tax_number", tax);

                MiraApiClient.ApiResponse response = apiClient.saveOrgProfile(payload);
                
                final boolean success = response != null && response.isSuccess;
                
                mainHandler.post(() -> {
                    if (isDestroyed.get() || !isAdded()) return;
                    showLoading(false);
                    
                    if (success) {
                        if (tvOrgNameAr != null) tvOrgNameAr.setText(nameAr);
                        if (tvOrgNameEn != null) tvOrgNameEn.setText(nameEn);
                        if (tvPhone != null) tvPhone.setText(phone);
                        if (tvEmail != null) tvEmail.setText(email);
                        if (tvTaxNum != null) tvTaxNum.setText((isArabic ? "الرقم الضريبي: " : "Tax Number: ") + tax);
                        
                        orgProfileManager.saveFullProfile(nameAr, nameEn, phone, email, tax);
                        
                        showToast(isArabic ? "✅ تم حفظ بيانات المنظمة" : "✅ Organization saved");
                    } else {
                        String error = response != null ? response.error : "Unknown error";
                        showToast((isArabic ? "❌ فشل: " : "❌ Failed: ") + error);
                    }
                });
            } catch (Exception e) {
                logNonFatal("saveOrgProfileToServer.thread", e);
                mainHandler.post(() -> {
                    if (!isDestroyed.get() && isAdded()) {
                        showLoading(false);
                        showToast(isArabic ? "❌ خطأ في الحفظ" : "❌ Save error");
                    }
                });
            }
        });
    }

    private void saveUserProfileToServer(String displayName, String phone) {
        showLoading(true);

        executeSafely(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("display_name", displayName);
                payload.put("phone", phone);

                MiraApiClient.ApiResponse response = apiClient.updateUserProfile(payload);
                
                final boolean success = response != null && response.isSuccess;
                
                mainHandler.post(() -> {
                    if (isDestroyed.get() || !isAdded()) return;
                    showLoading(false);
                    
                    if (success) {
                        if (tvProfileName != null) tvProfileName.setText(displayName);
                        if (tvAccountNum != null) tvAccountNum.setText(phone);
                        
                        if (!TextUtils.isEmpty(displayName)) sessionManager.setDisplayName(displayName);
                        if (!TextUtils.isEmpty(phone)) sessionManager.setPhone(phone);
                        
                        showToast(isArabic ? "✅ تم تحديث الملف الشخصي" : "✅ Profile updated");
                    } else {
                        String error = response != null ? response.error : "Unknown error";
                        showToast((isArabic ? "❌ فشل: " : "❌ Failed: ") + error);
                    }
                });
            } catch (Exception e) {
                logNonFatal("saveUserProfileToServer.thread", e);
                mainHandler.post(() -> {
                    if (!isDestroyed.get() && isAdded()) {
                        showLoading(false);
                        showToast(isArabic ? "❌ خطأ في الحفظ" : "❌ Save error");
                    }
                });
            }
        });
    }

    private void saveBiometricPreference(String key, boolean value) {
        executeSafely(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put(key, value);
                apiClient.saveSettings(payload);
            } catch (Exception e) {
                logNonFatal("saveBiometricPreference", e);
            }
        });
    }

    private void executeSafely(Runnable task) {
        if (executor != null && !executor.isShutdown() && !executor.isTerminated()) {
            try {
                executor.execute(task);
                return;
            } catch (RejectedExecutionException e) {
                logNonFatal("executeSafely.rejected", e);
            }
        }
        new Thread(task).start();
    }

    private void showLoading(boolean show) {
        try {
            if (getActivity() instanceof UHFMainActivity && !isDestroyed.get()) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                if (show) activity.showLoading();
                else activity.hideLoading();
            }
        } catch (Exception e) {
            logNonFatal("showLoading", e);
        }
    }

    private void showToast(String message) {
        try {
            if (isAdded() && !isDestroyed.get() && getContext() != null) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            logNonFatal("showToast", e);
        }
    }

    private void logNonFatal(String location, Throwable throwable) {
        try {
            Log.e(TAG, location + ": " + throwable.getMessage(), throwable);
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.setCustomKey("error_location", location);
            crashlytics.setCustomKey("fragment", "MiraProfileFragment");
            crashlytics.recordException(throwable);
        } catch (Exception e) {
            Log.e(TAG, "logNonFatal error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLocalProfileData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        
        // ✅✅✅ إخفاء الـ Loading Overlay عند مغادرة الشاشة
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).hideLoading();
            }
        } catch (Exception e) {
            Log.e(TAG, "hideLoading error: " + e.getMessage());
        }
    }
}
