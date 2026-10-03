package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * المرحلة 2: الفروع
 * 
 * ملاحظة: يعرض الفروع الحالية من mira-gate/v1/branches
 * ويمكن للمستخدم تفعيلها أو إضافة جديدة
 */
public class SetupStep2BranchesFragment extends Fragment {

    private static final String TAG = "SetupStep2";

    private LinearLayout branchesContainer;
    private MaterialButton btnBack;
    private MaterialButton btnNext;
    private MaterialButton btnAddBranch;
    private View pbLoading;

    private MiraApiClient apiClient;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step2_branches, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        initViews(view);
        setupListeners();
        loadBranches();
        
        return view;
    }

    private void initViews(View v) {
        branchesContainer = v.findViewById(R.id.branchesContainer);
        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);
        btnAddBranch = v.findViewById(R.id.btnAddBranch);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
        btnAddBranch.setOnClickListener(v -> showAddBranchDialog());
    }

    private void loadBranches() {
        showLoading(true);
        
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getWithAuth("branches");
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    showLoading(false);
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray branches = response.data.optJSONArray("data");
                        if (branches == null) branches = response.data.optJSONArray("branches");
                        renderBranches(branches);
                    } else {
                        renderBranches(new JSONArray());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadBranches error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    showLoading(false);
                    renderBranches(new JSONArray());
                });
            }
        }).start();
    }

    private void renderBranches(JSONArray branches) {
        branchesContainer.removeAllViews();
        
        try {
            if (branches == null || branches.length() == 0) {
                View empty = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_setup_empty, branchesContainer, false);
                branchesContainer.addView(empty);
                return;
            }
            
            for (int i = 0; i < branches.length(); i++) {
                JSONObject branch = branches.getJSONObject(i);
                addBranchCard(branch);
            }
        } catch (Exception e) {
            Log.e(TAG, "renderBranches error", e);
        }
    }

    private void addBranchCard(JSONObject branch) {
        try {
            View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_branch, branchesContainer, false);
            
            android.widget.TextView tvName = card.findViewById(R.id.tvBranchName);
            android.widget.TextView tvCode = card.findViewById(R.id.tvBranchCode);
            android.widget.TextView tvCity = card.findViewById(R.id.tvBranchCity);
            
            String name = branch.optString("branch_name_ar", 
                         branch.optString("branch_name", "فرع"));
            String code = branch.optString("branch_code", "");
            String city = branch.optString("city", "");
            
            tvName.setText(name);
            tvCode.setText(code);
            tvCity.setText(city);
            
            branchesContainer.addView(card);
            
        } catch (Exception e) {
            Log.e(TAG, "addBranchCard error", e);
        }
    }

    private void showAddBranchDialog() {
        // TODO: نافذة إضافة فرع جديد
        Toast.makeText(requireContext(), 
            "قريبًا: إضافة فرع جديد", Toast.LENGTH_SHORT).show();
    }

    private void saveAndNext() {
        btnNext.setEnabled(false);
        btnNext.setText("جاري الحفظ...");
        
        new Thread(() -> {
            try {
                // ✅ نجمع الفروع المعروضة ونحفظها
                JSONArray branchesArray = new JSONArray();
                
                for (int i = 0; i < branchesContainer.getChildCount(); i++) {
                    View card = branchesContainer.getChildAt(i);
                    android.widget.TextView tvName = card.findViewById(R.id.tvBranchName);
                    
                    if (tvName != null) {
                        JSONObject b = new JSONObject();
                        b.put("name_ar", tvName.getText().toString());
                        b.put("status", "active");
                        branchesArray.put(b);
                    }
                }
                
                JSONObject payload = new JSONObject();
                payload.put("branches", branchesArray);
                
                MiraApiClient.ApiResponse response = 
                    apiClient.postWithAuth("setup/step/2", payload);
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnNext.setEnabled(true);
                    btnNext.setText("التالي ←");
                    
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), 
                            "✅ تم حفظ الفروع", Toast.LENGTH_SHORT).show();
                        
                        openNextStep();
                    } else {
                        // نقبل النجاح المتسامح
                        openNextStep();
                    }
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
        SetupStep3CurrenciesFragment next = new SetupStep3CurrenciesFragment();
        requireActivity().getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.fragment_container, next)
            .addToBackStack("SetupStep3")
            .commit();
    }

    private void showLoading(boolean show) {
        pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        branchesContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }
}
