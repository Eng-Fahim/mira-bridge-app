package com.example.uhf.fragment;

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
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraProfileFragment extends Fragment {

    private static final String TAG = "MiraProfileFragment";

    private TextView tvProfileName, tvRole, tvAccountNum, tvAltNum;
    private TextView tvOrgNameAr, tvOrgNameEn, tvPhone, tvEmail, tvTaxNum;
    private ImageView imgArrowSecurity, imgArrowTheme;
    private LinearLayout layoutSecurityExpandable, layoutAppThemeExpandable;
    private SwitchMaterial switchBiometricLogin, switchBiometricConfirm;

    private SessionManager sessionManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = null;
        try {
            root = inflater.inflate(R.layout.fragment_mira_profile, container, false);
            sessionManager = new SessionManager(requireContext());

            initViews(root);
            setupListeners(root);

            fetchProfileDataFromServer();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateView: ", e);
        }
        return root;
    }

    private void initViews(View root) {
        if (root == null) return;

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
        if (root == null) return;

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
    }

    private void toggleSection(LinearLayout layout, ImageView arrow) {
        if (layout == null) return;
        boolean isVisible = layout.getVisibility() == View.VISIBLE;
        layout.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        if (arrow != null) {
            arrow.animate().rotation(isVisible ? 0 : 180).setDuration(200).start();
        }
    }

    private void fetchProfileDataFromServer() {
        showLoading(true);

        executor.execute(() -> {
            try {
                String baseUrl = sessionManager.getServerUrl();
                if (baseUrl == null) baseUrl = "";
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                URL url = new URL(baseUrl + "wp-json/mira-gate/v1/profile/get");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                String token = sessionManager.getAuthToken();
                if (!TextUtils.isEmpty(token)) {
                    conn.setRequestProperty("X-MIRA-Auth-Token", token);
                }

                conn.setConnectTimeout(8000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) response.append(line);
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    if (json.optBoolean("success")) {
                        JSONObject user = json.optJSONObject("user");
                        JSONObject org = json.optJSONObject("organization");

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (user != null) {
                                    if (tvProfileName != null) tvProfileName.setText(user.optString("display_name", ""));
                                    if (tvRole != null) tvRole.setText(user.optString("role", ""));
                                    if (tvAccountNum != null) tvAccountNum.setText(user.optString("phone", ""));
                                }
                                if (org != null) {
                                    if (tvOrgNameAr != null) tvOrgNameAr.setText(org.optString("name_ar", ""));
                                    if (tvOrgNameEn != null) tvOrgNameEn.setText(org.optString("name_en", ""));
                                    if (tvPhone != null) tvPhone.setText(org.optString("phone", ""));
                                    if (tvEmail != null) tvEmail.setText(org.optString("email", ""));
                                    if (tvTaxNum != null) tvTaxNum.setText("الرقم الضريبي: " + org.optString("tax_number", ""));
                                }
                            });
                        }
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

    private void showEditProfileDialog() {
        if (getContext() == null) return;

        try {
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

            new MaterialAlertDialogBuilder(getContext())
                    .setView(dialogView)
                    .setPositiveButton("حفظ", (dialog, which) -> {
                        String nameAr = etNameAr != null ? etNameAr.getText().toString().trim() : "";
                        String nameEn = etNameEn != null ? etNameEn.getText().toString().trim() : "";
                        String phone  = etPhone != null ? etPhone.getText().toString().trim() : "";
                        String email  = etEmail != null ? etEmail.getText().toString().trim() : "";
                        String tax    = etTax != null ? etTax.getText().toString().trim() : "";

                        saveProfileToServer(nameAr, nameEn, phone, email, tax);
                    })
                    .setNegativeButton("إلغاء", (dialog, which) -> dialog.dismiss())
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error in dialog: ", e);
        }
    }

    private void saveProfileToServer(String nameAr, String nameEn, String phone, String email, String tax) {
        showLoading(true);

        executor.execute(() -> {
            try {
                String baseUrl = sessionManager.getServerUrl();
                if (baseUrl == null) baseUrl = "";
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                URL url = new URL(baseUrl + "wp-json/mira-gate/v1/profile/update");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                String token = sessionManager.getAuthToken();
                if (!TextUtils.isEmpty(token)) {
                    conn.setRequestProperty("X-MIRA-Auth-Token", token);
                }

                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("org_name_ar", nameAr);
                body.put("org_name_en", nameEn);
                body.put("org_phone", phone);
                body.put("org_email", email);
                body.put("tax_number", tax);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();

                if (conn.getResponseCode() == 200) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (tvOrgNameAr != null) tvOrgNameAr.setText(nameAr);
                            if (tvOrgNameEn != null) tvOrgNameEn.setText(nameEn);
                            if (tvPhone != null) tvPhone.setText(phone);
                            if (tvEmail != null) tvEmail.setText(email);
                            if (tvTaxNum != null) tvTaxNum.setText("الرقم الضريبي: " + tax);

                            Toast.makeText(getContext(), "تم حفظ البيانات بنجاح", Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating profile: ", e);
            } finally {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showLoading(false));
                }
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
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdown();
    }
}
