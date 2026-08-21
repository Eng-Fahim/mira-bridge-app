package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
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
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraProfileFragment extends Fragment {

    private static final String TAG = "MiraProfileFragment";
    private static final String ORG_PREFS = "mira_org_profile";

    private TextView tvProfileName, tvRole, tvAccountNum, tvAltNum;
    private TextView tvOrgNameAr, tvOrgNameEn, tvPhone, tvEmail, tvTaxNum;
    private ImageView imgArrowSecurity, imgArrowTheme;
    private LinearLayout layoutSecurityExpandable, layoutAppThemeExpandable;
    private SwitchMaterial switchBiometricLogin, switchBiometricConfirm;

    private SessionManager sessionManager;
    private MiraApiClient apiClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mira_profile, container, false);
        
        sessionManager = new SessionManager(requireContext());
        apiClient = MiraApiClient.getInstance(requireContext());

        initViews(root);
        setupListeners(root);
        
        // ✅ تحميل البيانات المحلية أولاً
        loadLocalProfileData();
        
        // ✅ ثم جلب من السيرفر
        fetchProfileFromServer();

        return root;
    }

    private void initViews(View root) {
        tvProfileName = root.findViewById(R.id.tvProfileName);
        tvRole = root.findViewById(R.id.tvRole);
        tvAccountNum = root.findViewById(R.id.tvAccountNum);
        tvAltNum = root.findViewById(R.id.tvAltNum);

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
    }

    private void toggleSection(LinearLayout layout, ImageView arrow) {
        if (layout == null) return;
        boolean isVisible = layout.getVisibility() == View.VISIBLE;
        layout.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        if (arrow != null) {
            arrow.animate().rotation(isVisible ? 0 : 180).setDuration(200).start();
        }
    }

    /**
     * ✅ تحميل البيانات المحلية المخزنة
     */
    private void loadLocalProfileData() {
        try {
            // بيانات المستخدم
            String displayName = sessionManager.getDisplayName();
            String username = sessionManager.getUsername();
            String phone = sessionManager.getPhone();
            
            if (tvProfileName != null && !TextUtils.isEmpty(displayName)) {
                tvProfileName.setText(displayName);
            }
            
            if (tvRole != null) {
                String role = sessionManager.getRole();
                tvRole.setText(role != null ? role : "موظف");
            }
            
            if (tvAccountNum != null && !TextUtils.isEmpty(phone)) {
                tvAccountNum.setText(phone);
            }
            
            // بيانات المنظمة
            SharedPreferences prefs = requireContext()
                .getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE);
            
            String orgNameAr = prefs.getString("org_name_ar", "");
            String orgNameEn = prefs.getString("org_name_en", "");
            String orgPhone = prefs.getString("org_phone", "");
            String orgEmail = prefs.getString("org_email", "");
            String taxNumber = prefs.getString("tax_number", "");
            
            if (tvOrgNameAr != null && !TextUtils.isEmpty(orgNameAr)) {
                tvOrgNameAr.setText(orgNameAr);
            }
            
            if (tvOrgNameEn != null && !TextUtils.isEmpty(orgNameEn)) {
                tvOrgNameEn.setText(orgNameEn);
            }
            
            if (tvPhone != null && !TextUtils.isEmpty(orgPhone)) {
                tvPhone.setText(orgPhone);
            }
            
            if (tvEmail != null && !TextUtils.isEmpty(orgEmail)) {
                tvEmail.setText(orgEmail);
            }
            
            if (tvTaxNum != null && !TextUtils.isEmpty(taxNumber)) {
                tvTaxNum.setText("الرقم الضريبي: " + taxNumber);
            }
            
            // تفضيلات البيومترية
            if (switchBiometricLogin != null) {
                switchBiometricLogin.setChecked(sessionManager.isBiometricLoginEnabled());
            }
            
            if (switchBiometricConfirm != null) {
                switchBiometricConfirm.setChecked(sessionManager.isBiometricConfirmEnabled());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error loading local profile", e);
        }
    }

    /**
     * ✅ حفظ البيانات محلياً
     */
    private void saveLocalProfileData(JSONObject user, JSONObject org) {
        try {
            SharedPreferences prefs = requireContext()
                .getSharedPreferences(ORG_PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            
            if (org != null) {
                editor.putString("org_name_ar", org.optString("name_ar", ""));
                editor.putString("org_name_en", org.optString("name_en", ""));
                editor.putString("org_phone", org.optString("phone", ""));
                editor.putString("org_email", org.optString("email", ""));
                editor.putString("tax_number", org.optString("tax_number", ""));
            }
            
            if (user != null) {
                String displayName = user.optString("display_name", "");
                String role = user.optString("role", "");
                String phone = user.optString("phone", "");
                
                if (!TextUtils.isEmpty(displayName)) {
                    sessionManager.setDisplayName(displayName);
                }
                if (!TextUtils.isEmpty(role)) {
                    sessionManager.setRole(role);
                }
                if (!TextUtils.isEmpty(phone)) {
                    sessionManager.setPhone(phone);
                }
            }
            
            editor.apply();
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving local profile", e);
        }
    }

    /**
     * ✅ جلب البروفايل من السيرفر
     */
    private void fetchProfileFromServer() {
        showLoading(true);

        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getOrgProfile();
                
                if (response != null && response.isSuccess && response.data != null) {
                    // ✅ السيرفر يرجع user و organization مباشرة في الـ root
                    JSONObject user = response.data.optJSONObject("user");
                    JSONObject org = response.data.optJSONObject("organization");
                    
                    // ✅ حفظ محلياً
                    saveLocalProfileData(user, org);
                    
                    final JSONObject finalUser = user;
                    final JSONObject finalOrg = org;
                    
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            updateUIWithData(finalUser, finalOrg);
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching profile: ", e);
            } finally {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showLoading(false));
                }
            }
        });
    }

    /**
     * ✅ تحديث الواجهة
     */
    private void updateUIWithData(JSONObject user, JSONObject org) {
        if (user != null) {
            if (tvProfileName != null) {
                tvProfileName.setText(user.optString("display_name", ""));
            }
            if (tvRole != null) {
                tvRole.setText(user.optString("role", ""));
            }
            if (tvAccountNum != null) {
                tvAccountNum.setText(user.optString("phone", ""));
            }
        }
        
        if (org != null) {
            if (tvOrgNameAr != null) {
                tvOrgNameAr.setText(org.optString("name_ar", ""));
            }
            if (tvOrgNameEn != null) {
                tvOrgNameEn.setText(org.optString("name_en", ""));
            }
            if (tvPhone != null) {
                tvPhone.setText(org.optString("phone", ""));
            }
            if (tvEmail != null) {
                tvEmail.setText(org.optString("email", ""));
            }
            if (tvTaxNum != null) {
                String tax = org.optString("tax_number", "");
                if (!TextUtils.isEmpty(tax)) {
                    tvTaxNum.setText("الرقم الضريبي: " + tax);
                }
            }
        }
    }

    /**
     * ✅ عرض نافذة التعديل
     */
    private void showEditProfileDialog() {
        if (getContext() == null) return;

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
            String tax = taxText.replace("الرقم الضريبي: ", "").trim();
            etTax.setText(tax);
        }

        new MaterialAlertDialogBuilder(getContext())
                .setTitle("تعديل بيانات المنظمة")
                .setView(dialogView)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String nameAr = etNameAr != null ? etNameAr.getText().toString().trim() : "";
                    String nameEn = etNameEn != null ? etNameEn.getText().toString().trim() : "";
                    String phone = etPhone != null ? etPhone.getText().toString().trim() : "";
                    String email = etEmail != null ? etEmail.getText().toString().trim() : "";
                    String tax = etTax != null ? etTax.getText().toString().trim() : "";

                    saveProfileToServer(nameAr, nameEn, phone, email, tax);
                })
                .setNegativeButton("إلغاء", (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * ✅ حفظ في السيرفر
     */
    private void saveProfileToServer(String nameAr, String nameEn, String phone, String email, String tax) {
        showLoading(true);

        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("org_name_ar", nameAr);
                payload.put("org_name_en", nameEn);
                payload.put("org_phone", phone);
                payload.put("org_email", email);
                payload.put("tax_number", tax);

                MiraApiClient.ApiResponse response = apiClient.saveOrgProfile(payload);
                
                final boolean success = response != null && response.isSuccess;
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (success) {
                            if (tvOrgNameAr != null) tvOrgNameAr.setText(nameAr);
                            if (tvOrgNameEn != null) tvOrgNameEn.setText(nameEn);
                            if (tvPhone != null) tvPhone.setText(phone);
                            if (tvEmail != null) tvEmail.setText(email);
                            if (tvTaxNum != null) tvTaxNum.setText("الرقم الضريبي: " + tax);
                            
                            // حفظ محلياً
                            try {
                                JSONObject org = new JSONObject();
                                org.put("name_ar", nameAr);
                                org.put("name_en", nameEn);
                                org.put("phone", phone);
                                org.put("email", email);
                                org.put("tax_number", tax);
                                saveLocalProfileData(null, org);
                            } catch (Exception ignored) {}
                            
                            Toast.makeText(getContext(), "✅ تم حفظ البيانات بنجاح", Toast.LENGTH_SHORT).show();
                            
                            notifyProfileUpdated();
                        } else {
                            String error = response != null ? response.error : "Unknown error";
                            Toast.makeText(getContext(), "❌ فشل: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating profile: ", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "❌ خطأ في الحفظ", Toast.LENGTH_SHORT).show();
                    });
                }
            } finally {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showLoading(false));
                }
            }
        });
    }

    /**
     * ✅ إشعار باقي الأجزاء
     */
    private void notifyProfileUpdated() {
        try {
            Intent intent = new Intent("com.example.uhf.PROFILE_UPDATED");
            requireContext().sendBroadcast(intent);
            sessionManager.refreshProfileData();
        } catch (Exception e) {
            Log.e(TAG, "Error notifying", e);
        }
    }

    private void saveBiometricPreference(String key, boolean value) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put(key, value);
                MiraApiClient.ApiResponse response = apiClient.saveSettings(payload);
            } catch (Exception e) {
                Log.e(TAG, "Error saving biometric preference", e);
            }
        });
    }

    private void showLoading(boolean show) {
        if (getActivity() instanceof UHFMainActivity) {
            UHFMainActivity activity = (UHFMainActivity) getActivity();
            if (show) activity.showLoading();
            else activity.hideLoading();
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
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
