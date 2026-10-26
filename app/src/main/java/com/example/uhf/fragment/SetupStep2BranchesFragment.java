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
 * المرحلة 2: الفروع
 * 
 * يعرض الفروع الحالية + يسمح بإدارة الفروع عبر الشاشة المتكاملة
 */
public class SetupStep2BranchesFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep2";

    private LinearLayout branchesContainer;
    private MaterialButton btnBack;
    private MaterialButton btnNext;
    private MaterialButton btnManageBranches;
    private View pbLoading;
    private TextView tvStats;

    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step2_branches, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        
        initViews(view);
        setupListeners();
        
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // ✅ إعادة تحميل الفروع عند العودة من شاشة الإدارة
        loadBranches();
    }

    private void initViews(View v) {
        branchesContainer = v.findViewById(R.id.branchesContainer);
        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);
        btnManageBranches = v.findViewById(R.id.btnManageBranches);
        pbLoading = v.findViewById(R.id.pbLoading);
        tvStats = v.findViewById(R.id.tvStats);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
        
        // ✅ فتح شاشة إدارة الفروع المتكاملة
        btnManageBranches.setOnClickListener(v -> {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity())
                    .loadFragment(
                        new MiraBranchesManagementFragment(),
                        "Branches"
                    );
            }
        });
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
                tvStats.setText("لا توجد فروع");
                return;
            }
            
            // ✅ إحصائيات
            tvStats.setText("عدد الفروع: " + branches.length());
            
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
            
            TextView tvName = card.findViewById(R.id.tvBranchName);
            TextView tvCode = card.findViewById(R.id.tvBranchCode);
            TextView tvCity = card.findViewById(R.id.tvBranchCity);
            
            String name = branch.optString("branch_name_ar", 
                         branch.optString("branch_name", "فرع"));
            String code = branch.optString("branch_code", "");
            String city = branch.optString("city", "");
            
            tvName.setText(name);
            tvCode.setText(code);
            tvCity.setText(city);
            
            // ✅ عند النقر → فتح شاشة الإدارة
            card.setOnClickListener(v -> {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity())
                        .loadFragment(
                            new MiraBranchesManagementFragment(),
                            "Branches"
                        );
                }
            });
            
            branchesContainer.addView(card);
            
        } catch (Exception e) {
            Log.e(TAG, "addBranchCard error", e);
        }
    }

    private void saveAndNext() {
        btnNext.setEnabled(false);
        btnNext.setText("جاري الحفظ...");
        
        new Thread(() -> {
            try {
                // ✅ نجمع الفروع من الـ UI
                JSONArray branchesArray = new JSONArray();
                
                for (int i = 0; i < branchesContainer.getChildCount(); i++) {
                    View card = branchesContainer.getChildAt(i);
                    TextView tvName = card.findViewById(R.id.tvBranchName);
                    
                    if (tvName != null && tvName.getText().length() > 0) {
                        JSONObject b = new JSONObject();
                        b.put("name_ar", tvName.getText().toString());
                        b.put("status", "active");
                        branchesArray.put(b);
                    }
                }
                
                JSONObject payload = new JSONObject();
                payload.put("branches", branchesArray);
                
                MiraApiClient.ApiResponse response = 
                    apiClient.postAccounting("setup/step/2", payload);
                
                new Handler(Looper.getMainLooper()).post(() -> {
    btnNext.setEnabled(true);
    btnNext.setText("التالي ←");

    if (response != null && response.isSuccess) {
        openNextStep();
    } else {
        String err = response != null ? response.error : "غير معروف";
        Toast.makeText(requireContext(),
            "❌ فشل الحفظ: " + err, Toast.LENGTH_LONG).show();
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
