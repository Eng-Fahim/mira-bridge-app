package com.example.uhf.fragment;

import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MiraBranchPickerFragment extends DialogFragment {

    private RecyclerView rvBranches;
    private ProgressBar pbLoading;
    private TextView tvError;
    private View btnManage;
    private MiraBranchesManager branchesManager;
    private MiraApiClient apiClient;
    private final List<BranchData> branchList = new ArrayList<>();
    private BranchAdapter adapter;
    private OnBranchSelectedListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface OnBranchSelectedListener {
        void onBranchSelected(int branchId, String branchName, String branchCode);
    }

    public void setOnBranchSelectedListener(OnBranchSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.dialog_branch_picker, container, false);
        
        // جعل الحوار ملء الشاشة تقريباً
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }
        
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        branchesManager = MiraBranchesManager.getInstance(requireContext());
        apiClient = MiraApiClient.getInstance(requireContext());

        rvBranches = view.findViewById(R.id.rvBranches);
        pbLoading = view.findViewById(R.id.pbBranchesLoading);
        tvError = view.findViewById(R.id.tvBranchError);
        btnManage = view.findViewById(R.id.btnManageBranches);

        rvBranches.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BranchAdapter(branchList);
        rvBranches.setAdapter(adapter);

        btnManage.setOnClickListener(v -> {
            Toast.makeText(getContext(), "🏢 إدارة الفروع - قيد التطوير", Toast.LENGTH_SHORT).show();
        });

        loadBranches();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void loadBranches() {
        pbLoading.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);

        Executors.newSingleThreadExecutor().execute(() -> {
            // محاولة جلب من السحابة
            MiraApiClient.ApiResponse response = apiClient.getBranches();

            if (response != null && response.isSuccess && response.data != null) {
                JSONArray branches = response.data.optJSONArray("branches");
                if (branches != null && branches.length() > 0) {
                    branchesManager.cacheBranches(branches);
                    mainHandler.post(() -> displayBranches(branches));
                    return;
                }
            }

            // استخدام البيانات المخزنة محلياً
            JSONArray cached = branchesManager.getCachedBranches();
            if (cached.length() > 0) {
                mainHandler.post(() -> displayBranches(cached));
            } else {
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    tvError.setVisibility(View.VISIBLE);
                    tvError.setText("تعذر تحميل الفروع - تحقق من الاتصال");
                });
            }
        });
    }

    private void displayBranches(JSONArray branches) {
        pbLoading.setVisibility(View.GONE);
        branchList.clear();

        int activeBranchId = branchesManager.getActiveBranchId();

        for (int i = 0; i < branches.length(); i++) {
            JSONObject b = branches.optJSONObject(i);
            if (b == null) continue;

            BranchData data = new BranchData();
            data.id = b.optInt("id");
            data.name = b.optString("branch_name_ar", b.optString("branch_name", ""));
            data.code = b.optString("branch_code", "");
            data.type = b.optString("branch_type", "branch");
            data.city = b.optString("city", "");
            data.status = b.optString("status", "active");
            data.inventoryCount = b.optInt("inventory_count", 0);
            data.userCount = b.optInt("user_count", 0);
            data.isActive = (data.id == activeBranchId);

            branchList.add(data);
        }

        adapter.notifyDataSetChanged();

        if (branchList.isEmpty()) {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText("لا توجد فروع متاحة");
        }
    }

    // ==================== BranchData ====================

    static class BranchData {
        int id;
        String name, code, type, city, status;
        int inventoryCount, userCount;
        boolean isActive;
    }

    // ==================== Adapter ====================

    class BranchAdapter extends RecyclerView.Adapter<BranchAdapter.VH> {
        final List<BranchData> list;

        BranchAdapter(List<BranchData> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_branch_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BranchData b = list.get(pos);

            h.tvName.setText(b.name);
            h.tvInfo.setText(b.code + " • " + (b.city.isEmpty() ? b.type : b.city));
            h.tvStatus.setText((b.status.equals("active") ? "نشط" : "متوقف") + " • " + b.inventoryCount + " قطعة");

            if (b.status.equals("active")) {
                h.vStatus.setBackgroundResource(R.drawable.circle_green);
            } else {
                h.vStatus.setBackgroundResource(R.drawable.circle_red);
            }

            h.ivSelected.setVisibility(b.isActive ? View.VISIBLE : View.GONE);

            // تحديد أيقونة الفرع حسب النوع
            int iconTint = b.isActive ? R.color.primary : R.color.on_surface_variant;
            switch (b.type) {
                case "main": h.ivIcon.setImageResource(R.drawable.ic_branch); break;
                case "warehouse": h.ivIcon.setImageResource(R.drawable.ic_branch); break;
                default: h.ivIcon.setImageResource(R.drawable.ic_branch);
            }

            h.itemView.setOnClickListener(v -> {
                // تحديث الفرع النشط
                branchesManager.setActiveBranch(b.id, b.name, b.code, b.type, b.city);

                // تحديث القائمة
                for (BranchData bd : list) bd.isActive = (bd.id == b.id);
                notifyDataSetChanged();

                // إشعار المستمع
                if (listener != null) {
                    listener.onBranchSelected(b.id, b.name, b.code);
                }

                Toast.makeText(getContext(), "✅ تم تفعيل: " + b.name, Toast.LENGTH_SHORT).show();

                // إغلاق الحوار بعد اختيار الفرع
                mainHandler.postDelayed(() -> dismiss(), 400);
            });
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon, ivSelected;
            TextView tvName, tvInfo, tvStatus;
            View vStatus;

            VH(View v) {
                super(v);
                ivIcon = v.findViewById(R.id.ivBranchIcon);
                ivSelected = v.findViewById(R.id.ivBranchSelected);
                tvName = v.findViewById(R.id.tvBranchName);
                tvInfo = v.findViewById(R.id.tvBranchInfo);
                tvStatus = v.findViewById(R.id.tvBranchStatus);
                vStatus = v.findViewById(R.id.vBranchStatus);
            }
        }
    }
}
