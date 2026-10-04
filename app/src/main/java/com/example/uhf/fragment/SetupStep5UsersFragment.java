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
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * المرحلة 5: المستخدمون والصلاحيات
 * 
 * يعرض المستخدمين الحاليين + يفتح شاشة الإدارة المتكاملة
 */
public class SetupStep5UsersFragment extends Fragment {

    private static final String TAG = "SetupStep5";

    private LinearLayout usersContainer;
    private MaterialButton btnBack;
    private MaterialButton btnNext;
    private MaterialButton btnManageUsers;
    private View pbLoading;
    private TextView tvStats;

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
        
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // ✅ إعادة تحميل عند العودة
        loadUsers();
    }

    private void initViews(View v) {
        usersContainer = v.findViewById(R.id.usersContainer);
        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);
        btnManageUsers = v.findViewById(R.id.btnManageUsers);
        pbLoading = v.findViewById(R.id.pbLoading);
        tvStats = v.findViewById(R.id.tvStats);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
        
        // ✅ فتح شاشة إدارة المستخدمين المتكاملة
        btnManageUsers.setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity())
                    .loadFragment(
                        new UsersManagementFragment(),
                        "UsersManagement"
                    );
            }
        });
    }

    private void loadUsers() {
        showLoading(true);
        
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getWithAuth("auth/users");
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    showLoading(false);
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        // ✅ جرّب عدة مستويات
                        JSONArray users = response.data.optJSONArray("users");
                        
                        if (users == null) {
                            JSONObject dataObj = response.data.optJSONObject("data");
                            if (dataObj != null) {
                                users = dataObj.optJSONArray("users");
                            }
                        }
                        
                        if (users == null) {
                            users = response.data.optJSONArray("data");
                        }
                        
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
                tvStats.setText("لا يوجد مستخدمون");
                return;
            }
            
            tvStats.setText("عدد المستخدمين: " + users.length());
            
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
            tvPermissions.setText("👤 " + user.optString("username", ""));
            
            // ✅ عند النقر → فتح شاشة الإدارة
            card.setOnClickListener(v -> {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity())
                        .loadFragment(
                            new UsersManagementFragment(),
                            "UsersManagement"
                        );
                }
            });
            
            usersContainer.addView(card);
            
        } catch (Exception e) {
            Log.e(TAG, "addUserCard error", e);
        }
    }

    private String translateRole(String role) {
        switch (role) {
            case "SYSTEM_ADMIN":   return "🔑 مدير النظام";
            case "ORG_ADMIN":      return "🏢 مدير المؤسسة";
            case "ADMIN":          return "👑 مدير";
            case "BRANCH_MANAGER": return "🏬 مدير فرع";
            case "ACCOUNTANT":     return "💰 محاسب";
            case "AUDITOR":        return "🔍 مدقق";
            case "INVENTORY_MGR":  return "📦 مدير مخزون";
            case "POS_OPERATOR":   return "🛒 كاشير";
            default:               return role;
        }
    }

    private void saveAndNext() {
        btnNext.setEnabled(false);
        btnNext.setText("جاري الحفظ...");
        
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("users", new JSONArray());
                
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
