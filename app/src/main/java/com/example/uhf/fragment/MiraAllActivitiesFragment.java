package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.adapter.AllActivitiesAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.ActivityDetailsBottomSheet;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ✅ شاشة عرض جميع العمليات
 */
public class MiraAllActivitiesFragment extends Fragment {

    private static final String TAG = "MiraAllActivitiesFragment";
    private static final int PAGE_SIZE = 20;

    // UI
    private RecyclerView rvAllActivities;
    private SwipeRefreshLayout swipeRefreshAll;
    private LinearLayout emptyStateAll;
    private ProgressBar pbLoadingAll;
    private ImageButton btnBackAll, btnExportAll, btnRefreshAll;
    private EditText etSearchAll;
    private ImageView btnClearSearchAll;
    private Chip chipAll, chipSale, chipInventory, chipTransfer, chipRfid, chipSystem;

    // Adapter
    private AllActivitiesAdapter adapter;

    // State
    private final List<JSONObject> allActivities = new ArrayList<>();
    private final List<JSONObject> filteredActivities = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private boolean isArabic = true;
    private String currentFilter = "all";
    private String currentSearch = "";
    private int currentPage = 1;
    private boolean isLoadingMore = false;
    private boolean hasMorePages = true;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_all_activities, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isArabic = Locale.getDefault().getLanguage().equals("ar");
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        initViews(view);
        setupListeners();
        loadActivities(true);
    }

    private void initViews(View v) {
        rvAllActivities = v.findViewById(R.id.rvAllActivities);
        swipeRefreshAll = v.findViewById(R.id.swipeRefreshAll);
        emptyStateAll = v.findViewById(R.id.emptyStateAll);
        pbLoadingAll = v.findViewById(R.id.pbLoadingAll);
        btnBackAll = v.findViewById(R.id.btnBackAll);
        btnExportAll = v.findViewById(R.id.btnExportAll);
        btnRefreshAll = v.findViewById(R.id.btnRefreshAll);
        etSearchAll = v.findViewById(R.id.etSearchAll);
        btnClearSearchAll = v.findViewById(R.id.btnClearSearchAll);

        chipAll = v.findViewById(R.id.chipAll);
        chipSale = v.findViewById(R.id.chipSale);
        chipInventory = v.findViewById(R.id.chipInventory);
        chipTransfer = v.findViewById(R.id.chipTransfer);
        chipRfid = v.findViewById(R.id.chipRfid);
        chipSystem = v.findViewById(R.id.chipSystem);

        // Adapter
        adapter = new AllActivitiesAdapter(isArabic, this::showActivityDetails);
        rvAllActivities.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvAllActivities.setAdapter(adapter);

        // Infinite Scroll
        rvAllActivities.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && !isLoadingMore && hasMorePages) {
                    int total = lm.getItemCount();
                    int lastVisible = lm.findLastVisibleItemPosition();
                    if (lastVisible >= total - 3) {
                        loadMore();
                    }
                }
            }
        });
    }

    private void setupListeners() {
        btnBackAll.setOnClickListener(v -> {
            if (getParentFragmentManager() != null) {
                getParentFragmentManager().popBackStack();
            }
        });

        btnRefreshAll.setOnClickListener(v -> loadActivities(true));

        btnExportAll.setOnClickListener(v -> exportActivities());

        swipeRefreshAll.setOnRefreshListener(() -> loadActivities(true));

        etSearchAll.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                currentSearch = s.toString().trim().toLowerCase();
                btnClearSearchAll.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                applyFilters();
            }
        });

        btnClearSearchAll.setOnClickListener(v -> {
            etSearchAll.setText("");
            currentSearch = "";
            applyFilters();
        });

        // Filter Chips
        chipAll.setOnClickListener(v -> selectFilter("all"));
        chipSale.setOnClickListener(v -> selectFilter("sale"));
        chipInventory.setOnClickListener(v -> selectFilter("inventory"));
        chipTransfer.setOnClickListener(v -> selectFilter("transfer"));
        chipRfid.setOnClickListener(v -> selectFilter("rfid"));
        chipSystem.setOnClickListener(v -> selectFilter("system"));
    }

    private void selectFilter(String filter) {
        currentFilter = filter;
        applyFilters();
    }

    /**
     * ✅ تحميل العمليات
     */
    private void loadActivities(boolean reset) {
        if (apiClient == null) return;

        if (reset) {
            currentPage = 1;
            hasMorePages = true;
            allActivities.clear();
        }

        showLoading(true);

        executor.execute(() -> {
            try {
                int branchId = branchesManager != null 
                        ? branchesManager.getActiveBranchId() : 0;

                MiraApiClient.ApiResponse response =
                        apiClient.getUnifiedActivity(branchId, PAGE_SIZE * currentPage);

                mainHandler.post(() -> {
                    showLoading(false);
                    swipeRefreshAll.setRefreshing(false);

                    if (response != null && response.isSuccess && response.data != null) {
                        try {
                            JSONArray activities = response.data.optJSONArray("activities");
                            if (activities != null) {
                                allActivities.clear();
                                for (int i = 0; i < activities.length(); i++) {
                                    allActivities.add(activities.getJSONObject(i));
                                }
                                hasMorePages = activities.length() >= PAGE_SIZE;
                            }
                            applyFilters();
                        } catch (Exception e) {
                            Log.e(TAG, "parse error", e);
                        }
                    } else {
                        showEmptyState(true);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "load error", e);
                mainHandler.post(() -> {
                    showLoading(false);
                    swipeRefreshAll.setRefreshing(false);
                    showEmptyState(true);
                });
            }
        });
    }

    /**
     * ✅ تحميل المزيد
     */
    private void loadMore() {
        if (isLoadingMore || !hasMorePages) return;
        isLoadingMore = true;
        currentPage++;
        loadActivities(false);
    }

    /**
     * ✅ تطبيق الفلاتر والبحث
     */
    private void applyFilters() {
        filteredActivities.clear();

        for (JSONObject act : allActivities) {
            // ✅ فلتر النوع
            if (!"all".equals(currentFilter)) {
                String type = act.optString("type", "");
                if (!currentFilter.equals(type)) continue;
            }

            // ✅ فلتر البحث
            if (!currentSearch.isEmpty()) {
                boolean matches = false;
                String[] fields = {"title_ar", "title_en", "details_ar", "details_en", "user"};
                for (String field : fields) {
                    String val = act.optString(field, "").toLowerCase();
                    if (val.contains(currentSearch)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;
            }

            filteredActivities.add(act);
        }

        adapter.submitList(new ArrayList<>(filteredActivities));
        showEmptyState(filteredActivities.isEmpty());
    }

    /**
     * ✅ عرض تفاصيل العملية
     */
    private void showActivityDetails(JSONObject activity) {
        try {
            ActivityDetailsBottomSheet sheet =
                    ActivityDetailsBottomSheet.newInstance(activity, isArabic);
            sheet.show(getParentFragmentManager(), "ActivityDetails");
        } catch (Exception e) {
            Log.e(TAG, "details error", e);
        }
    }

    /**
     * ✅ تصدير العمليات إلى CSV
     */
    private void exportActivities() {
        if (filteredActivities.isEmpty()) {
            Toast.makeText(requireContext(),
                    isArabic ? "⚠️ لا توجد عمليات للتصدير" : "⚠️ No activities to export",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                File dir = new File(requireContext().getExternalFilesDir(null), "exports");
                if (!dir.exists()) dir.mkdirs();

                String fileName = "activities_" + 
                        new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
                File file = new File(dir, fileName);

                FileWriter writer = new FileWriter(file);
                writer.append("Timestamp,Type,Title,Details,User,Amount,Currency,Status\n");

                for (JSONObject act : filteredActivities) {
                    writer.append(escapeCsv(act.optString("timestamp", ""))).append(",");
                    writer.append(escapeCsv(act.optString("type", ""))).append(",");
                    writer.append(escapeCsv(act.optString("title_ar", act.optString("title_en", "")))).append(",");
                    writer.append(escapeCsv(act.optString("details_ar", act.optString("details_en", "")))).append(",");
                    writer.append(escapeCsv(act.optString("user", ""))).append(",");
                    writer.append(escapeCsv(String.valueOf(act.optDouble("amount", 0)))).append(",");
                    writer.append(escapeCsv(act.optString("currency", ""))).append(",");
                    writer.append(escapeCsv(act.optString("status", ""))).append("\n");
                }

                writer.flush();
                writer.close();

                mainHandler.post(() -> {
                    Toast.makeText(requireContext(),
                            (isArabic ? "✅ تم التصدير إلى: " : "✅ Exported to: ") + file.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "export error", e);
                mainHandler.post(() -> Toast.makeText(requireContext(),
                        isArabic ? "❌ فشل التصدير" : "❌ Export failed",
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String escapeCsv(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    /**
     * ✅ عرض/إخفاء التحميل
     */
    private void showLoading(boolean show) {
        if (pbLoadingAll != null) {
            pbLoadingAll.setVisibility(show && allActivities.isEmpty() 
                    ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * ✅ عرض/إخفاء الحالة الفارغة
     */
    private void showEmptyState(boolean show) {
        if (emptyStateAll != null) {
            emptyStateAll.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (rvAllActivities != null) {
            rvAllActivities.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
}
