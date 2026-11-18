package com.example.uhf.fragment;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.LoginActivity;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraProfileFragment extends Fragment {

    private static final String TAG = "MiraProfileFragment";

    private ShapeableImageView imgUserAvatar;
    private TextView tvProfileName, tvRole, tvAccountNum, tvBranchName, tvUsername;
    private TextView tvOrgNameAr, tvOrgNameEn, tvPhone, tvEmail, tvTaxNum;
    private MaterialButton btnEditOrgProfile, btnEditProfile, btnChangePassword, btnChangePhoto,btnLogout;

    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private MiraOrgProfileManager orgProfileManager;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);

    private boolean isArabic = true;
    private ActivityResultLauncher<Intent> imagePickerLauncher;

    private final MiraOrgProfileManager.OnProfileChangedListener profileListener = this::loadLocalProfileData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mira_profile, container, false);

        isDestroyed.set(false);
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        sessionManager = new SessionManager(requireContext());
        apiClient = MiraApiClient.getInstance(requireContext());
        orgProfileManager = MiraOrgProfileManager.getInstance(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        initViews(root);
        setupImagePicker();
        setupListeners();

        orgProfileManager.addListener(profileListener);

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
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) uploadProfileImage(imageUri);
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
            showToast(isArabic ? "❌ تعذر فتح المعرض" : "❌ Could not open gallery");
        }
    }

    private void setupListeners() {
        if (btnEditOrgProfile != null) {
            if (permissionManager.isAdmin()) {
                btnEditOrgProfile.setVisibility(View.VISIBLE);
                btnEditOrgProfile.setOnClickListener(v -> showEditOrgDialog());
            } else {
                btnEditOrgProfile.setVisibility(View.GONE);
            }
        }

        if (btnChangePhoto != null) btnChangePhoto.setOnClickListener(v -> pickImage());
        if (imgUserAvatar != null) imgUserAvatar.setOnClickListener(v -> pickImage());
        if (btnLogout != null) btnLogout.setOnClickListener(v -> performLogout());
    }

    private void loadLocalProfileData() {
        try {
            String displayName = sessionManager.getDisplayName();
            String username = sessionManager.getUsername();
            String phone = sessionManager.getPhone();
            String role = sessionManager.getRole();

            if (tvProfileName != null && !TextUtils.isEmpty(displayName)) tvProfileName.setText(displayName);
            if (tvUsername != null && !TextUtils.isEmpty(username)) tvUsername.setText("@" + username);
            if (tvRole != null) tvRole.setText(role != null ? role : (isArabic ? "موظف" : "Employee"));
            if (tvAccountNum != null && !TextUtils.isEmpty(phone)) tvAccountNum.setText(phone);
            if (tvBranchName != null && branchesManager != null) {
                tvBranchName.setText("🏢 " + branchesManager.getActiveBranchDisplayName());
            }

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
        } catch (Exception e) {
            Log.e(TAG, "loadLocalProfileData error", e);
        }
    }

    private void fetchProfileFromServer() {
        if (isDestroyed.get() || !isAdded()) return;

        orgProfileManager.syncFromServer(apiClient, true, new MiraOrgProfileManager.SyncCallback() {
            @Override
            public void onSuccess() {
                mainHandler.post(MiraProfileFragment.this::loadLocalProfileData);
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Sync profile error: " + error);
            }
        });
    }

    private void uploadProfileImage(Uri imageUri) {
        executor.execute(() -> {
            try {
                InputStream inputStream = requireContext().getContentResolver().openInputStream(imageUri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                if (inputStream != null) inputStream.close();

                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                    String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);

                    JSONObject payload = new JSONObject();
                    payload.put("avatar_base64", base64Image);

                    apiClient.uploadUserAvatar(payload);

                    mainHandler.post(() -> showToast(isArabic ? "✅ تم تحديث الصورة" : "✅ Photo updated"));
                }
            } catch (Exception e) {
                mainHandler.post(() -> showToast(isArabic ? "❌ خطأ في رفع الصورة" : "❌ Upload error"));
            }
        });
    }

    private void showEditOrgDialog() {
        if (getContext() == null || isDestroyed.get()) return;

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_mira_profile, null);
        // يمكنك ربط الحقول وحفظها هنا عند الحاجة
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(isArabic ? "✏️ تعديل بيانات المنظمة" : "✏️ Edit Organization")
                .setView(dialogView)
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void performLogout() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).onPreLogout();
            }

            sessionManager.logout();
            orgProfileManager.logout();

            if (getActivity() != null && !getActivity().isFinishing()) {
                Intent intent = new Intent(getActivity(), LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                getActivity().finish();
            }
        } catch (Exception e) {
            Log.e(TAG, "logout error", e);
        }
    }

    private void showToast(String message) {
        if (isAdded() && !isDestroyed.get() && getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed.set(true);
        if (orgProfileManager != null) {
            orgProfileManager.removeListener(profileListener);
        }
    }
}
