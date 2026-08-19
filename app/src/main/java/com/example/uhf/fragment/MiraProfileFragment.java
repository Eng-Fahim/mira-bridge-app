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
import com.example.uhf.tools.AppConfigManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraProfileFragment extends Fragment {

    private static final String TAG = "MiraProfileFragment";

    private TextView tvProfileName, tvRole, tvAccountNum;
    private TextView tvOrgNameAr, tvOrgNameEn, tvPhone, tvEmail, tvTaxNum;

    private SessionManager sessionManager;
    private AppConfigManager configManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = null;
        try {
            root = inflater.inflate(R.layout.fragment_mira_profile, container, false);
            sessionManager = new SessionManager(requireContext());
            configManager = new AppConfigManager(requireContext());

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

        tvOrgNameAr = root.findViewById(R.id.tvOrgNameAr);
        tvOrgNameEn = root.findViewById(R.id.tvOrgNameEn);
        tvPhone = root.findViewById(R.id.tvPhone);
        tvEmail = root.findViewById(R.id.tvEmail);
        tvTaxNum = root.findViewById(R.id.tvTaxNum);
    }

    private void setupListeners(View root) {
        if (root == null) return;

        MaterialButton btnEditProfile = root.findViewById(R.id.btnEditProfile);
        if (btnEditProfile != null) {
            btnEditProfile.setOnClickListener(v -> showEditProfileDialog());
        }
    }

    private void fetchProfileDataFromServer() {
        showLoading(true);

        executor.execute(() -> {
            try {
                String baseUrl = sessionManager.getServerUrl();
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                URL url = new URL(baseUrl + "wp-json/mira-gate/v1/profile/get");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-KEY", sessionManager.getApiKey());
                conn.setRequestProperty("X-MIRA-Auth-Token", sessionManager.getAuthToken());
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
                        JSONObject config = json.optJSONObject("system_config");

                        if (org != null && config != null) {
                            configManager.saveConfig(
                                    org.optString("name_ar"),
                                    org.optString("name_en"),
                                    org.optString("phone"),
                                    org.optString("email"),
                                    org.optString("tax_number"),
                                    config.optString("currency_ar"),
                                    config.optString("currency_en"),
                                    config.optBoolean("enable_rfid"),
                                    config.optBoolean("enable_biometric")
                            );
                        }

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
                                    if (tvTaxNum != null) tvTaxNum.setText(org.optString("tax_number", ""));
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
            if (tvTaxNum != null && etTax != null) etTax.setText(tvTaxNum.getText());

            new MaterialAlertDialogBuilder(getContext())
                    .setView(dialogView)
                    .setPositiveButton("حفظ / Save", (dialog, which) -> {
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
            Log.e(TAG, "Error in edit dialog: ", e);
        }
    }

    private void saveProfileToServer(String nameAr, String nameEn, String phone, String email, String tax) {
        showLoading(true);

        executor.execute(() -> {
            try {
                String baseUrl = sessionManager.getServerUrl();
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                URL url = new URL(baseUrl + "wp-json/mira-gate/v1/profile/update");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("X-MIRA-API-KEY", sessionManager.getApiKey());
                conn.setRequestProperty("X-MIRA-Auth-Token", sessionManager.getAuthToken());
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
                            boolean isArabic = Locale.getDefault().getLanguage().equals("ar");
                            configManager.saveConfig(nameAr, nameEn, phone, email, tax, 
                                    configManager.getLocalizedCurrency(true), 
                                    configManager.getLocalizedCurrency(false), 
                                    configManager.isRfidEnabled(), 
                                    configManager.isBiometricEnabled());

                            if (tvOrgNameAr != null) tvOrgNameAr.setText(nameAr);
                            if (tvOrgNameEn != null) tvOrgNameEn.setText(nameEn);
                            if (tvPhone != null) tvPhone.setText(phone);
                            if (tvEmail != null) tvEmail.setText(email);
                            if (tvTaxNum != null) tvTaxNum.setText(tax);

                            Toast.makeText(getContext(), isArabic ? "تم حفظ التكوينات بنجاح" : "Settings saved successfully", Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating config: ", e);
            } finally {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showLoading(false));
                }
            }
        });
    }

    private void showLoading(boolean show) {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).showLoading();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdown();
    }
}
