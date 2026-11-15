package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MiraInventoryDashboardFragment extends Fragment {

    private static final String TAG = "InvDashboardFragment";

    private TextView tvTotalValue, tvTotalItems, tvTotalWeight;
    private TextView tvMissing, tvMatched, tvExtra, tvPendingSessions;
    private PieChart pieChartInventoryStatus;
    private ExtendedFloatingActionButton fabStartNewInventory;

    private MiraApiClient apiClient;
    private SessionManager sessionManager;
    private PermissionManager permissionManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_inventory_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        sessionManager = new SessionManager(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());

        initViews(view);
        fetchInventoryDashboardData();
    }

    private void initViews(View v) {
        tvTotalValue = v.findViewById(R.id.tvInvTotalValue);
        tvTotalItems = v.findViewById(R.id.tvInvTotalItems);
        tvTotalWeight = v.findViewById(R.id.tvInvTotalWeight);
        
        tvMissing = v.findViewById(R.id.tvInvMissing);
        tvMatched = v.findViewById(R.id.tvInvMatched);
        tvExtra = v.findViewById(R.id.tvInvExtra);
        tvPendingSessions = v.findViewById(R.id.tvInvPendingSessions);
        
        pieChartInventoryStatus = v.findViewById(R.id.pieChartInventoryStatus);
        fabStartNewInventory = v.findViewById(R.id.fabStartNewInventory);

        // ربط زر بدء جرد جديد (نقله إلى شاشة الجرد الفعلية)
        fabStartNewInventory.setOnClickListener(v1 -> {
            // استبدل هذا بالانتقال لشاشة مسح الجرد
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(new MiraInventoryProFragment(), "Inventory");
            }
        });

        applyPieChartTheme();
    }

    private void fetchInventoryDashboardData() {
        // جلب بيانات المخزون من السيرفر
        int branchId = sessionManager.getBranchId();
        
        new Thread(() -> {
            try {
                // استخدام المسار الحقيقي لجلب إحصائيات المخزون
                MiraApiClient.ApiResponse response = apiClient.getBranchInventoryStats(branchId);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data; // fallback

                    final double totalValue = data.optDouble("total_value", 0.0);
                    final int totalItems = data.optInt("total_items", 0);
                    final double totalWeight = data.optDouble("total_weight", 0.0);
                    
                    final int missing = data.optInt("missing_items", 0);
                    final int matched = data.optInt("matched_items", totalItems); // افتراضي الكل مطابق
                    final int extra = data.optInt("extra_items", 0);
                    final int pending = data.optInt("pending_sessions", 0);

                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        
                        // تحديث البطاقة الرئيسية
                        tvTotalValue.setText(String.format(Locale.US, "%,.2f YER", totalValue * 530.0)); // افترض سعر الصرف
                        tvTotalItems.setText(String.valueOf(totalItems));
                        tvTotalWeight.setText(String.format(Locale.US, "%,.2f g", totalWeight));

                        // تحديث الإحصائيات
                        tvMissing.setText(String.valueOf(missing));
                        tvMatched.setText(String.valueOf(matched));
                        tvExtra.setText(String.valueOf(extra));
                        tvPendingSessions.setText(String.valueOf(pending));

                        // رسم الدائرة البيانية
                        renderStatusChart(matched, missing, extra);
                    });
                } else {
                    Log.e(TAG, "Failed to fetch inventory stats");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching inventory data", e);
            }
        }).start();
    }

    private void renderStatusChart(int matched, int missing, int extra) {
        List<PieEntry> entries = new ArrayList<>();
        if (matched > 0) entries.add(new PieEntry(matched, "مطابق"));
        if (missing > 0) entries.add(new PieEntry(missing, "مفقود"));
        if (extra > 0) entries.add(new PieEntry(extra, "زائد"));

        if (entries.isEmpty()) {
            pieChartInventoryStatus.clear();
            pieChartInventoryStatus.setNoDataText("لا توجد بيانات جرد سابقة");
            pieChartInventoryStatus.invalidate();
            return;
        }

        PieDataSet dataSet = new PieDataSet(entries, "");
        List<Integer> colors = new ArrayList<>();
        colors.add(Color.parseColor("#16A34A")); // أخضر للمطابق
        colors.add(Color.parseColor("#DC2626")); // أحمر للمفقود
        colors.add(Color.parseColor("#2563EB")); // أزرق للزائد
        dataSet.setColors(colors);
        dataSet.setValueTextSize(14f);
        dataSet.setValueTextColor(Color.WHITE);
        dataSet.setSliceSpace(3f);

        PieData pieData = new PieData(dataSet);
        pieChartInventoryStatus.setData(pieData);
        pieChartInventoryStatus.getDescription().setEnabled(false);
        pieChartInventoryStatus.setUsePercentValues(true);
        pieChartInventoryStatus.setHoleRadius(50f);
        pieChartInventoryStatus.setTransparentCircleRadius(55f);
        pieChartInventoryStatus.animateY(1000);
        pieChartInventoryStatus.invalidate();
    }

    private void applyPieChartTheme() {
        if (pieChartInventoryStatus != null) {
            pieChartInventoryStatus.setHoleColor(Color.TRANSPARENT);
            pieChartInventoryStatus.getLegend().setTextColor(Color.GRAY);
        }
    }
}
