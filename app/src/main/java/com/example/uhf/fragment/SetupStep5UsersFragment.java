package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * المرحلة 5: المستخدمون والصلاحيات (اختيارية)
 */
public class SetupStep5UsersFragment extends Fragment {

    private static final String TAG = "SetupStep5";

    private LinearLayout usersContainer;
    private MaterialButton btnBack;
    private MaterialButton btnNext;
    private MaterialButton btnManageUsers;
    private View pbLoading;

    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step5_users, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        
        initViews(view);
        setupListeners();
        loadUsers();
        
        return view;
    }

    private void initViews(View v) {
        usersContainer = v.findViewById(R.id.usersContainer);
        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);
        btnManageUsers = v.findViewById(R.id.btnManageUsers);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
        btnManageUsers.setOnClickListener(v -> {
            // فتح شاشة إدارة المستخدمين
            if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
                ((com.example.uhf.activity.UHFMainActivity) getActivity())
                    .loadFragment(new UsersManagementFragment(), "UsersManagement");
            }
        });
    }

    private void loadUsers() {
        showLoading(true);
        
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getAccounting("auth/users");
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    showLoading(false);
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray users = response.data.optJSONArray("users");
                        if (users == null) users = response.data.optJSONArray("data");
                        renderUsers(users);
                    } else {
                        renderUsers(new JSONArray());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadUsers error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    showLoading(false);
                    renderUsers(new JSONArray());
                });
            }
        }).start();
    }

    private void renderUsers(JSONArray users) {
        usersContainer.removeAllViews();
        
        try {
            if (users == null || users.length() == 0) {
                View empty = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_setup_empty, usersContainer, false);
                usersContainer.addView(empty);
                return;
            }
            
            for (int i = 0; i < users.length(); i++) {
                addUserCard(users.getJSONObject(i));
            }
        } catch (Exception e) {
            Log.e(TAG, "renderUsers error", e);
        }
    }

    private void addUserCard(JSONObject user) {
        try {
            View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_user, usersContainer, false);
            
            TextView tvName = card.findViewById(R.id.tvUserName);
            TextView tvRole = card.findViewById(R.id.tvUserRole);
            TextView tvPermissions = card.findViewById(R.id.tvUserPermissions);
            
            String name = user.optString("display_name",
                         user.optString("username", "مستخدم"));
            String role = user.optString("role", "");
            
            tvName.setText(name);
            tvRole.setText(translateRole(role));
            
            // صلاحيات
            StringBuilder perms = new StringBuilder("الصلاحيات: ");
            if (user.optBoolean("can_post_journal", false)) perms.append("✅ نشر القيود | ");
            if (user.optBoolean("can_approve_journal", false)) perms.append("✅ اعتماد القيود | ");
            if (user.optBoolean("can_edit_opening", false)) perms.append("✅ تعديل الأرصدة | ");
            
            if (perms.length() <= "الصلاحيات: ".length()) {
                perms.append("لا صلاحيات محاسبية");
            }
            
            tvPermissions.setText(perms.toString());
            
            usersContainer.addView(card);
            
        } catch (Exception e) {
            Log.e(TAG, "addUserCard error", e);
        }
    }

    private String translateRole(String role) {
        switch (role) {
            case "SYSTEM_ADMIN": return "🔑 مدير النظام";
            case "ORG_ADMIN":    return "🏢 مدير المؤسسة";
            case "ADMIN":        return "👑 مدير";
            case "BRANCH_MANAGER": return "🏬 مدير فرع";
            case "ACCOUNTANT":   return "💰 محاسب";
            case "AUDITOR":      return "🔍 مدقق";
            default:             return role;
        }
    }

    private void saveAndNext() {
        btnNext.setEnabled(false);
        btnNext.setText("جاري الحفظ...");
        
        new Thread(() -> {
            try {
                // ✅ نرسل قائمة فارغة — المستخدمون يُعدَّلون من شاشة الإدارة
                JSONObject payload = new JSONObject();
                JSONArray usersArray = new JSONArray();
                payload.put("users", usersArray);
                
                MiraApiClient.ApiResponse response = 
                    apiClient.postAccounting("setup/step/5", payload);
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnNext.setEnabled(true);
                    btnNext.setText("التالي ←");
                    openNextStep();
                });
            } catch (Exception e) {
                Log.e(TAG, "save error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnNext.setEnabled(true);
                    btnNext.setText("التالي ←");
                    openNextStep();
                });
            }
        }).start();
    }

    private void openNextStep() {
        SetupStep6ReviewFragment next = new SetupStep6ReviewFragment();
        requireActivity().getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.fragment_container, next)
            .addToBackStack("SetupStep6")
            .commit();
    }

    private void showLoading(boolean show) {
        pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        usersContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }
}
