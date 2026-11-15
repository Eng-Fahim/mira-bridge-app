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
import android.widget.TextView;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  شاشة عرض جميع العمليات
 * ───────────────────────────────────────────────────────────────────────────
 *  ✅ إصلاحات هذه النسخة:
 *   • عرض رسالة خطأ فعلية بدل شاشة فارغة صامتة (كانت المشكلة الأساسية)
 *   • دعم مفتاح "activities" + "data" معاً (بعد إصلاح ActivityController)
 *   • إعادة تعيين isLoadingMore (كان يبقى true للأبد)
 *   • مقارنة النوع case-insensitive
 *   • استخدام total من السيرفر إذا وُجد
 *   • حماية كاملة ضد null/empty response
 *   • مسح آمن للـ handlers والخيوط عند التدمير
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class MiraAllActivitiesFragment extends Fragment {

    private static final String TAG = "MiraAllActivities";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200; // حماية من الطلبات الضخمة

    // ─── UI ────────────────────────────────────────────────────────────
    private RecyclerView rvAllActivities;
    private SwipeRefreshLayout swipeRefreshAll;
    private LinearLayout emptyStateAll;
    private ProgressBar pbLoadingAll;
    private ImageButton btnBackAll, btnExportAll, btnRefreshAll;
    private EditText etSearchAll;
    private ImageView btnClearSearchAll;
    private Chip chipAll, chipSale, chipInventory, chipTransfer, chipRfid, chipSystem;

    // ─── Adapter ───────────────────────────────────────────────────────
    private AllActivitiesAdapter adapter;

    // ─── State ─────────────────────────────────────────────────────────
    private final List<JSONObject> allActivities = new ArrayList<>();
    private final List<JSONObject> filteredActivities = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicBoolean isRequestInFlight = new AtomicBoolean(false);

    private boolean isArabic = true;
    private String currentFilter = "all";
    private String currentSearch = "";
    private int currentPage = 1;
    private volatile boolean isLoadingMore = false;
    private volatile boolean hasMorePages = true;
    private int totalOnServer = -1; // -1 = غير معروف

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;

    private volatile boolean isDestroyed = false;

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_all_activities, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isDestroyed = false;
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        initViews(view);
        setupListeners();
        loadActivities(true);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Init
    // ═══════════════════════════════════════════════════════════════════

    private void initViews(View v) {
        rvAllActivities    = v.findViewById(R.id.rvAllActivities);
        swipeRefreshAll    = v.findViewById(R.id.swipeRefreshAll);
        emptyStateAll      = v.findViewById(R.id.emptyStateAll);
        pbLoadingAll       = v.findViewById(R.id.pbLoadingAll);
        btnBackAll         = v.findViewById(R.id.btnBackAll);
        btnExportAll       = v.findViewById(R.id.btnExportAll);
        btnRefreshAll      = v.findViewById(R.id.btnRefreshAll);
        etSearchAll        = v.findViewById(R.id.etSearchAll);
        btnClearSearchAll  = v.findViewById(R.id.btnClearSearchAll);

        chipAll            = v.findViewById(R.id.chipAll);
        chipSale           = v.findViewById(R.id.chipSale);
        chipInventory      = v.findViewById(R.id.chipInventory);
        chipTransfer       = v.findViewById(R.id.chipTransfer);
        chipRfid           = v.findViewById(R.id.chipRfid);
        chipSystem         = v.findViewById(R.id.chipSystem);

        adapter = new AllActivitiesAdapter(isArabic, this::showActivityDetails);
        rvAllActivities.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvAllActivities.setAdapter(adapter);

        rvAllActivities.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                if (isLoadingMore || !hasMorePages) return;

                int total = lm.getItemCount();
                if (total == 0) return;

                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible >= total - 3) {
                    loadMore();
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
                currentSearch = s.toString().trim().toLowerCase(Locale.ROOT);
                btnClearSearchAll.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                applyFilters();
            }
        });

        btnClearSearchAll.setOnClickListener(v -> {
            etSearchAll.setText("");
            currentSearch = "";
            applyFilters();
        });

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

    // ═══════════════════════════════════════════════════════════════════
    //  Loading
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @param reset true = إعادة من الصفحة الأولى (refresh)
     *              false = تحميل المزيد (pagination)
     */
    private void loadActivities(boolean reset) {
    if (apiClient == null) {
        showError(isArabic ? "العميل غير مهيأ" : "Client not initialized");
        return;
    }

    if (!isRequestInFlight.compareAndSet(false, true)) {
        Log.d(TAG, "loadActivities skipped — request already in flight");
        if (swipeRefreshAll != null) swipeRefreshAll.setRefreshing(false);
        return;
    }

    if (reset) {
        currentPage = 1;
        hasMorePages = true;
        isLoadingMore = false;
        totalOnServer = -1;
        allActivities.clear();
    }

    showLoading(true);

    final int pageToLoad = currentPage;
    final int requestLimit = Math.min(PAGE_SIZE * pageToLoad, MAX_PAGE_SIZE);

    executor.execute(() -> {
        try {
            int branchId = (branchesManager != null)
                    ? branchesManager.getActiveBranchId() : 0;

            Log.d(TAG, "→ loadActivities | branchId=" + branchId
                    + " page=" + pageToLoad
                    + " limit=" + requestLimit
                    + " reset=" + reset);

            MiraApiClient.ApiResponse response =
                    apiClient.getUnifiedActivity(branchId, requestLimit);

            postToMain(() -> handleResponse(response, reset, pageToLoad, requestLimit));

        } catch (Throwable e) {
            Log.e(TAG, "loadActivities exception", e);
            postToMain(() -> {
                finishLoading();
                showError(e.getMessage());
            });
        } finally {
            isRequestInFlight.set(false);
        }
    });
}

    private void handleResponse(MiraApiClient.ApiResponse response,
                            boolean reset,
                            int pageToLoad,
                            int requestLimit) {
    if (isDestroyed) return;

    finishLoading();

    if (response == null) {
        Log.e(TAG, "response == null");
        showError(isArabic ? "فشل الاتصال بالخادم" : "Server connection failed");
        return;
    }

    if (!response.isSuccess) {
        String err = (response.error != null && !response.error.isEmpty())
                ? response.error
                : ("HTTP " + response.code);
        Log.e(TAG, "❌ API error | code=" + response.code + " err=" + err);
        showError(err);
        return;
    }

    if (response.data == null) {
        Log.e(TAG, "response.data == null | body=" + safeBody(response));
        showError(isArabic
                ? "استجابة غير صالحة من الخادم"
                : "Invalid server response");
        return;
    }

    JSONArray activities = extractActivitiesArray(response.data);

    if (activities == null) {
        Log.e(TAG, "No activities array in response | body=" + safeBody(response));
        showError(isArabic
                ? "بنية الاستجابة غير متوقعة"
                : "Unexpected response structure");
        return;
    }

    if (reset) {
        allActivities.clear();
    }

    int parsed = 0, skipped = 0;
    for (int i = 0; i < activities.length(); i++) {
        try {
            JSONObject act = activities.optJSONObject(i);
            if (act != null) {
                allActivities.add(act);
                parsed++;
            }
        } catch (Throwable e) {
            skipped++;
            Log.w(TAG, "skip malformed activity at index " + i);
        }
    }

    Log.d(TAG, "✅ Fetched " + parsed + " activities"
            + (skipped > 0 ? " (skipped " + skipped + ")" : "")
            + " | total_in_list=" + allActivities.size());

    int serverTotal = response.data.optInt("total", -1);
    if (serverTotal >= 0) {
        totalOnServer = serverTotal;
        hasMorePages = allActivities.size() < serverTotal;
    } else {
        hasMorePages = activities.length() >= requestLimit;
        //                                   ⬆️ الآن يعمل — requestLimit مُمرَّر
    }

    applyFilters();
}

    /**
     * يستخرج مصفوفة الأنشطة من الاستجابة بحسب بنيتها الفعلية.
     *
     * البنى المدعومة:
     *   { success, activities: [...], data: [...], total }
     *   { success, data: [...] }                           ← array مباشر
     *   { success, data: { activities: [...], total } }    ← متداخل
     *   { success, items: [...] }
     *   { success, logs: [...] }
     *   { success, result: [...] }
     */
    @Nullable
    private JSONArray extractActivitiesArray(@NonNull JSONObject top) {
        // 1. مفاتيح top-level
        JSONArray arr = top.optJSONArray("activities");
        if (arr != null) return arr;

        arr = top.optJSONArray("data");
        if (arr != null) return arr;

        arr = top.optJSONArray("items");
        if (arr != null) return arr;

        arr = top.optJSONArray("logs");
        if (arr != null) return arr;

        arr = top.optJSONArray("result");
        if (arr != null) return arr;

        // 2. متداخل داخل "data"
        JSONObject dataObj = top.optJSONObject("data");
        if (dataObj != null) {
            arr = dataObj.optJSONArray("activities");
            if (arr != null) return arr;

            arr = dataObj.optJSONArray("items");
            if (arr != null) return arr;

            arr = dataObj.optJSONArray("logs");
            if (arr != null) return arr;
        }

        return null;
    }

    private void loadMore() {
        if (isLoadingMore || !hasMorePages) return;
        if (isRequestInFlight.get()) return;

        isLoadingMore = true;
        currentPage++;
        Log.d(TAG, "→ loadMore → page=" + currentPage);
        loadActivities(false);
    }

    private void finishLoading() {
        isLoadingMore = false;
        showLoading(false);
        if (swipeRefreshAll != null) swipeRefreshAll.setRefreshing(false);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Filters
    // ═══════════════════════════════════════════════════════════════════

    private void applyFilters() {
        filteredActivities.clear();

        for (JSONObject act : allActivities) {
            if (!matchesType(act)) continue;
            if (!matchesSearch(act)) continue;
            filteredActivities.add(act);
        }

        Log.d(TAG, "applyFilters → total=" + allActivities.size()
                + " filtered=" + filteredActivities.size()
                + " filter=" + currentFilter
                + " search=\"" + currentSearch + "\"");

        adapter.submitList(new ArrayList<>(filteredActivities));
        showEmptyState(filteredActivities.isEmpty());
    }

    private boolean matchesType(JSONObject act) {
        if ("all".equals(currentFilter)) return true;

        String type = act.optString("type", "").toLowerCase(Locale.ROOT);
        String filter = currentFilter.toLowerCase(Locale.ROOT);

        if (filter.equals(type)) return true;

        // مطابقة مرنة: "sale" يطابق "pos_sale", "inventory" يطابق "inventory_session", إلخ
        return type.contains(filter);
    }

    private boolean matchesSearch(JSONObject act) {
        if (currentSearch.isEmpty()) return true;

        String[] fields = {
                "title_ar", "title_en", "details_ar", "details_en",
                "user", "username", "title", "details", "status"
        };
        for (String field : fields) {
            String val = act.optString(field, "").toLowerCase(Locale.ROOT);
            if (val.contains(currentSearch)) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UI helpers
    // ═══════════════════════════════════════════════════════════════════

    private void showLoading(boolean show) {
        if (pbLoadingAll == null) return;
        // نُظهر التحميل فقط عند عدم وجود بيانات سابقة
        boolean shouldShow = show && allActivities.isEmpty();
        pbLoadingAll.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private void showEmptyState(boolean show) {
        if (emptyStateAll != null) {
            emptyStateAll.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (rvAllActivities != null) {
            rvAllActivities.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * ✅ يعرض خطأ فعلي للمستخدم (Toast + نص في emptyState إن وُجد)
     * بدلاً من الشاشة الفارغة الصامتة.
     */
    private void showError(@Nullable String message) {
        if (isDestroyed) return;

        String msg = (message == null || message.trim().isEmpty())
                ? (isArabic ? "تعذّر تحميل العمليات" : "Failed to load activities")
                : message.trim();

        Log.e(TAG, "showError: " + msg);

        allActivities.clear();
        filteredActivities.clear();
        if (adapter != null) adapter.submitList(new ArrayList<>());

        showEmptyState(true);

        // محاولة حقن الرسالة في TextView إن وُجد داخل emptyState
        if (emptyStateAll != null) {
            TextView tv = emptyStateAll.findViewById(R.id.tvEmptyMessage);
            if (tv == null) {
                // fallback: ابحث عن أي TextView
                for (int i = 0; i < emptyStateAll.getChildCount(); i++) {
                    View child = emptyStateAll.getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setText(msg);
                        break;
                    }
                }
            } else {
                tv.setText(msg);
            }
        }

        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
        }
    }

    private String safeBody(MiraApiClient.ApiResponse r) {
        try {
            String b = r.getBody();
            if (b == null) return "<null>";
            return b.length() > 300 ? b.substring(0, 300) + "..." : b;
        } catch (Throwable e) {
            return "<unreadable>";
        }
    }

    private void postToMain(Runnable r) {
        mainHandler.post(() -> {
            if (isDestroyed) return;
            try {
                r.run();
            } catch (Throwable e) {
                Log.e(TAG, "main-thread runnable crashed", e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════════════

    private void showActivityDetails(JSONObject activity) {
        try {
            ActivityDetailsBottomSheet sheet =
                    ActivityDetailsBottomSheet.newInstance(activity, isArabic);
            sheet.show(getParentFragmentManager(), "ActivityDetails");
        } catch (Throwable e) {
            Log.e(TAG, "details error", e);
        }
    }

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
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "could not create exports dir");
                }

                String fileName = "activities_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                        + ".csv";
                File file = new File(dir, fileName);

                try (FileWriter writer = new FileWriter(file)) {
                    writer.append("Timestamp,Type,Title,Details,User,Amount,Currency,Status\n");
                    for (JSONObject act : filteredActivities) {
                        writer.append(escapeCsv(act.optString("timestamp", ""))).append(",");
                        writer.append(escapeCsv(act.optString("type", ""))).append(",");
                        writer.append(escapeCsv(act.optString("title_ar",
                                act.optString("title_en", "")))).append(",");
                        writer.append(escapeCsv(act.optString("details_ar",
                                act.optString("details_en", "")))).append(",");
                        writer.append(escapeCsv(act.optString("user",
                                act.optString("username", "")))).append(",");
                        writer.append(escapeCsv(String.valueOf(
                                act.optDouble("amount", 0)))).append(",");
                        writer.append(escapeCsv(act.optString("currency", ""))).append(",");
                        writer.append(escapeCsv(act.optString("status", ""))).append("\n");
                    }
                    writer.flush();
                }

                postToMain(() -> Toast.makeText(requireContext(),
                        (isArabic ? "✅ تم التصدير إلى: " : "✅ Exported to: ")
                                + file.getAbsolutePath(),
                        Toast.LENGTH_LONG).show());

            } catch (Throwable e) {
                Log.e(TAG, "export error", e);
                postToMain(() -> Toast.makeText(requireContext(),
                        isArabic ? "❌ فشل التصدير" : "❌ Export failed",
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String escapeCsv(@Nullable String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onDestroyView() {
        isDestroyed = true;

        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        // إفراغ المراجع
        rvAllActivities = null;
        swipeRefreshAll = null;
        emptyStateAll = null;
        pbLoadingAll = null;
        adapter = null;

        super.onDestroyView();
    }
}
