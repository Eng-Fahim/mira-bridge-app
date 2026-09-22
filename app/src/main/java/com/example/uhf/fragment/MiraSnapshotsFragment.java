package com.example.uhf.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.SnapshotAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.inventory.InventorySnapshotManager;
import com.example.uhf.manager.inventory.SnapshotComparisonManager;
import com.example.uhf.model.InventorySnapshot;
import com.example.uhf.utils.MiraExecutorService;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ MiraSnapshotsFragment — شاشة لقطات المخزون
 *
 * الميزات:
 * - عرض كل اللقطات
 * - مقارنة بين لقطتين
 * - حذف / حذف جماعي
 * - مزامنة مع السيرفر
 * - مشاركة تفاصيل
 */
public class MiraSnapshotsFragment extends Fragment {

    private static final String TAG = "MiraSnapshotsFragment";

    // ==================== Views ====================
    private RecyclerView rvSnapshots;
    private View layoutEmptyState;
    private TextView tvStatsHeader;
    private MaterialButton btnCompareMode;
    private MaterialButton btnDeleteAll;
    private SwipeRefreshLayout swipeRefresh;
    private MaterialButton btnBack;

    // ==================== Managers ====================
    private InventorySnapshotManager snapshotManager;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraExecutorService miraExecutor;

    // ==================== Adapter ====================
    private SnapshotAdapter snapshotAdapter;

    // ==================== State ====================
    private boolean isArabic = true;
    private boolean isViewInitialized = false;
    private boolean isFragmentDestroyed = false;
    private boolean isCompareMode = false;

    private List<InventorySnapshot> allSnapshots = new ArrayList<>();
    private InventorySnapshot firstSelected = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_snapshots, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isViewInitialized = true;
        isFragmentDestroyed = false;

        try {
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            Context ctx = requireContext().getApplicationContext();
            miraExecutor = MiraExecutorService.getInstance();

            if (miraExecutor != null && miraExecutor.isHealthy()) {
                snapshotManager = new InventorySnapshotManager(ctx,
                        miraExecutor.getBackgroundExecutor());
            } else {
                // fallback
                snapshotManager = new InventorySnapshotManager(ctx,
                        java.util.concurrent.Executors.newSingleThreadExecutor());
            }

            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);

            initViews(view);
            setupRecyclerView();
            setupListeners();

            // ✅ تحميل اللقطات
            loadSnapshots();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    @Override
    public void onDestroyView() {
        isViewInitialized = false;
        isFragmentDestroyed = true;

        try {
            if (rvSnapshots != null) {
                rvSnapshots.setAdapter(null);
                rvSnapshots.setLayoutManager(null);
            }
            if (snapshotAdapter != null) {
                snapshotAdapter = null;
            }
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }
        } catch (Exception ignored) {}

        super.onDestroyView();
    }

    // ==================== Init ====================

    private void initViews(View view) {
    rvSnapshots = view.findViewById(R.id.rvSnapshots);
    layoutEmptyState = view.findViewById(R.id.layoutEmptyState);
    tvStatsHeader = view.findViewById(R.id.tvStatsHeader);
    btnCompareMode = view.findViewById(R.id.btnCompareMode);
    btnDeleteAll = view.findViewById(R.id.btnDeleteAll);
    swipeRefresh = view.findViewById(R.id.swipeRefresh);
    
    // ✅ جديد
    btnBack = view.findViewById(R.id.btnBack);
}

    private void setupRecyclerView() {
        if (rvSnapshots == null) return;

        rvSnapshots.setLayoutManager(new LinearLayoutManager(requireContext()));

        snapshotAdapter = new SnapshotAdapter(
                isArabic,
                snapshot -> handleSnapshotClick(snapshot),
                snapshot -> handleSnapshotLongClick(snapshot));

        rvSnapshots.setAdapter(snapshotAdapter);
    }

    private void setupListeners() {
    if (btnCompareMode != null) {
        btnCompareMode.setOnClickListener(v -> toggleCompareMode());
    }

    if (btnDeleteAll != null) {
        btnDeleteAll.setOnClickListener(v -> confirmDeleteAll());
    }

    if (swipeRefresh != null) {
        swipeRefresh.setOnRefreshListener(this::refreshSnapshots);
    }
    
    // ✅ جديد: زر الرجوع
    if (btnBack != null) {
        btnBack.setOnClickListener(v -> handleBackNavigation());
    }
}

/**
 * ✅ الرجوع للشاشة السابقة
 */
private void handleBackNavigation() {
    try {
        if (!isAdded() || getActivity() == null) return;

        // ✅ OnBackPressedDispatcher يُفوّض الأمر إلى NavigationManager تلقائياً
        requireActivity().getOnBackPressedDispatcher().onBackPressed();

    } catch (Exception e) {
        Log.e(TAG, "handleBackNavigation error", e);
    }
}


    // ==================== Load ====================

    private void loadSnapshots() {
        if (snapshotManager == null) return;

        snapshotManager.loadSnapshotsAsync(new InventorySnapshotManager.SnapshotListCallback() {
            @Override
            public void onLoaded(@NonNull List<InventorySnapshot> snapshots) {
                mainHandler.post(() -> {
                    if (!isAdded() || isFragmentDestroyed) return;
                    allSnapshots = snapshots;
                    updateUI(snapshots);
                });
            }

            @Override
            public void onError(@NonNull String error) {
                mainHandler.post(() -> {
                    if (!isAdded() || isFragmentDestroyed) return;
                    showToast("❌ " + error);
                    updateUI(new ArrayList<>());
                });
            }
        });
    }

    private void refreshSnapshots() {
        // ✅ مزامنة مع السيرفر
        if (apiClient != null && branchesManager != null) {
            int branchId = branchesManager.getActiveBranchId();

            snapshotManager.loadSnapshotsFromServer(apiClient, branchId,
                    new InventorySnapshotManager.SnapshotListCallback() {
                        @Override
                        public void onLoaded(@NonNull List<InventorySnapshot> snapshots) {
                            mainHandler.post(() -> {
                                if (!isAdded() || isFragmentDestroyed) return;
                                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                                loadSnapshots();
                            });
                        }

                        @Override
                        public void onError(@NonNull String error) {
                            mainHandler.post(() -> {
                                if (!isAdded() || isFragmentDestroyed) return;
                                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                                showToast(isArabic
                                        ? "⚠️ لا يمكن المزامنة — عرض المحلي"
                                        : "⚠️ Cannot sync — showing local");
                                loadSnapshots();
                            });
                        }
                    });
        } else {
            // ✅ لا يوجد سيرفر
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            loadSnapshots();
        }
    }

    // ==================== UI ====================

    private void updateUI(List<InventorySnapshot> snapshots) {
        boolean isEmpty = (snapshots == null || snapshots.isEmpty());

        // ✅ Empty State
        if (layoutEmptyState != null) {
            layoutEmptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
        if (rvSnapshots != null) {
            rvSnapshots.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }

        // ✅ Stats
        if (tvStatsHeader != null) {
            if (isEmpty) {
                tvStatsHeader.setText(isArabic ? "لا توجد لقطات" : "No snapshots");
            } else {
                InventorySnapshotManager.SnapshotStats stats = snapshotManager.getStats();
                tvStatsHeader.setText(isArabic
                        ? "📊 " + stats.totalSnapshots + " لقطة • آخر: " 
                                + stats.getFormattedLatest()
                        : "📊 " + stats.totalSnapshots + " snapshots • Latest: " 
                                + stats.getFormattedLatest());
            }
        }

        // ✅ أزرار
        if (btnCompareMode != null) {
            btnCompareMode.setEnabled(!isEmpty && snapshots.size() >= 2);
        }
        if (btnDeleteAll != null) {
            btnDeleteAll.setEnabled(!isEmpty);
        }

        // ✅ Adapter
        if (snapshotAdapter != null) {
            snapshotAdapter.submitList(snapshots);
        }
    }

    // ==================== Actions ====================

    private void handleSnapshotClick(InventorySnapshot snapshot) {
        if (snapshot == null) return;

        if (isCompareMode) {
            // ✅ وضع المقارنة
            if (firstSelected == null) {
                firstSelected = snapshot;
                showToast(isArabic
                        ? "✅ اخترت اللقطة الأولى — اختر الثانية"
                        : "✅ First selected — pick the second");
                highlightSelected(firstSelected.getId());
            } else if (firstSelected.getId().equals(snapshot.getId())) {
                // إلغاء
                firstSelected = null;
                showToast(isArabic ? "تم الإلغاء" : "Cancelled");
                highlightSelected(null);
            } else {
                // ✅ عرض المقارنة
                performComparison(firstSelected, snapshot);
                firstSelected = null;
                highlightSelected(null);
                toggleCompareMode();  // إغلاق الوضع
            }
        } else {
            // ✅ عرض التفاصيل
            showSnapshotDetails(snapshot);
        }
    }

    private void highlightSelected(String id) {
        if (snapshotAdapter == null) return;
        // Adapter يعتمد على DiffUtil — سنستخدم notifyDataSetChanged لتحديث الوضع البسيط
        snapshotAdapter.notifyDataSetChanged();
    }

    private void handleSnapshotLongClick(InventorySnapshot snapshot) {
        if (snapshot == null) return;

        String[] options = isArabic
                ? new String[]{
                    "📊 عرض التفاصيل",
                    "✏️ إعادة التسمية",
                    "📤 رفع للسيرفر",
                    "🗑️ حذف"
                }
                : new String[]{
                    "📊 View Details",
                    "✏️ Rename",
                    "📤 Upload to Server",
                    "🗑️ Delete"
                };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(snapshot.getName())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showSnapshotDetails(snapshot);
                            break;
                        case 1:
                            renameSnapshot(snapshot);
                            break;
                        case 2:
                            uploadSnapshotToServer(snapshot);
                            break;
                        case 3:
                            confirmDeleteSnapshot(snapshot);
                            break;
                    }
                })
                .show();
    }

    private void toggleCompareMode() {
        isCompareMode = !isCompareMode;
        firstSelected = null;

        if (btnCompareMode != null) {
            if (isCompareMode) {
                btnCompareMode.setText(isArabic ? "❌ إلغاء المقارنة" : "❌ Cancel Compare");
                showToast(isArabic
                        ? "اختر لقطتين للمقارنة"
                        : "Pick two snapshots to compare");
            } else {
                btnCompareMode.setText(isArabic ? "📊 مقارنة" : "📊 Compare");
            }
        }
    }

    // ==================== Details ====================

    private void showSnapshotDetails(InventorySnapshot snapshot) {
        if (snapshot == null || !isAdded()) return;

        try {
            StringBuilder sb = new StringBuilder();

            sb.append(isArabic ? "📅 " : "📅 ")
                    .append(snapshot.getFormattedDate()).append("\n");
            sb.append(isArabic ? "👤 " : "👤 ")
                    .append(snapshot.getCreatedBy()).append("\n");

            if (snapshot.getBranchName() != null && !snapshot.getBranchName().isEmpty()) {
                sb.append("🏢 ").append(snapshot.getBranchName()).append("\n");
            }

            sb.append("\n");

            sb.append(isArabic ? "📦 القطع: " : "📦 Items: ")
                    .append(snapshot.getTotalItems()).append("\n");
            sb.append(isArabic ? "⚖️ الوزن: " : "⚖️ Weight: ")
                    .append(String.format(Locale.US, "%.2f g", 
                            snapshot.getTotalWeight())).append("\n");
            sb.append(isArabic ? "💰 القيمة: " : "💰 Value: ")
                    .append(String.format(Locale.US, "$%,.2f", 
                            snapshot.getTotalValue())).append("\n");

            // ✅ العيارات
            sb.append("\n").append(isArabic ? "💎 توزيع العيارات:\n" 
                    : "💎 Karat Distribution:\n");

            appendKaratLine(sb, "24K", snapshot.getCount24K(), 
                    snapshot.getWeight24K(), isArabic);
            appendKaratLine(sb, "22K", snapshot.getCount22K(), 
                    snapshot.getWeight22K(), isArabic);
            appendKaratLine(sb, "21K", snapshot.getCount21K(), 
                    snapshot.getWeight21K(), isArabic);
            appendKaratLine(sb, "18K", snapshot.getCount18K(), 
                    snapshot.getWeight18K(), isArabic);
            appendKaratLine(sb, "14K", snapshot.getCount14K(), 
                    snapshot.getWeight14K(), isArabic);

            if (snapshot.getCountOther() > 0) {
                appendKaratLine(sb, "Other", snapshot.getCountOther(), 
                        snapshot.getWeightOther(), isArabic);
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(snapshot.getName())
                    .setMessage(sb.toString())
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .setNeutralButton(isArabic ? "🗑️ حذف" : "🗑️ Delete", 
                            (d, w) -> confirmDeleteSnapshot(snapshot))
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "showSnapshotDetails error", e);
        }
    }

    private void appendKaratLine(StringBuilder sb, String karat, 
                                   int count, double weight, boolean isArabic) {
        if (count == 0) return;
        sb.append("  • ").append(karat).append(": ")
                .append(count).append(isArabic ? " قطعة" : " items")
                .append(" — ")
                .append(String.format(Locale.US, "%.2f g", weight))
                .append("\n");
    }

    // ==================== Comparison ====================

    private void performComparison(InventorySnapshot s1, InventorySnapshot s2) {
        try {
            SnapshotComparisonManager.ComparisonResult result =
                    SnapshotComparisonManager.compare(s1, s2, isArabic);

            if (result == null) {
                showToast(isArabic ? "❌ فشل المقارنة" : "❌ Comparison failed");
                return;
            }

            String report = SnapshotComparisonManager.buildDetailedReport(result, isArabic);

            AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "📊 نتيجة المقارنة" : "📊 Comparison Result")
                    .setMessage(report)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .setNeutralButton(isArabic ? "📤 مشاركة" : "📤 Share",
                            (d, w) -> shareReport(report))
                    .setCancelable(true)
                    .create();

            // ✅ جعله قابل للتمرير
            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "performComparison error", e);
            showToast(isArabic ? "❌ خطأ في المقارنة" : "❌ Comparison error");
        }
    }

    private void shareReport(String report) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, 
                    isArabic ? "تقرير مقارنة اللقطات" : "Snapshot Comparison");
            intent.putExtra(android.content.Intent.EXTRA_TEXT, report);
            startActivity(android.content.Intent.createChooser(intent, 
                    isArabic ? "مشاركة عبر..." : "Share via..."));
        } catch (Exception e) {
            Log.e(TAG, "shareReport error", e);
        }
    }

    // ==================== Rename ====================

    private void renameSnapshot(InventorySnapshot snapshot) {
        if (snapshot == null || !isAdded()) return;

        try {
            android.widget.EditText input = new android.widget.EditText(requireContext());
            input.setText(snapshot.getName());
            input.setHint(isArabic ? "اسم اللقطة" : "Snapshot name");
            input.setPadding(40, 40, 40, 40);

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "✏️ إعادة التسمية" : "✏️ Rename")
                    .setView(input)
                    .setPositiveButton(isArabic ? "حفظ" : "Save", (d, w) -> {
                        String newName = input.getText().toString().trim();
                        if (!newName.isEmpty()) {
                            snapshot.setName(newName);
                            snapshotManager.saveSnapshot(snapshot);
                            loadSnapshots();
                            showToast(isArabic ? "✅ تم الحفظ" : "✅ Saved");
                        }
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "renameSnapshot error", e);
        }
    }

    // ==================== Upload ====================

    private void uploadSnapshotToServer(InventorySnapshot snapshot) {
        if (snapshot == null || apiClient == null) return;

        showToast(isArabic ? "⏳ جاري الرفع..." : "⏳ Uploading...");

        snapshotManager.uploadSnapshotToServer(snapshot, apiClient,
                new InventorySnapshotManager.SnapshotActionCallback() {
                    @Override
                    public void onSuccess() {
                        mainHandler.post(() -> {
                            if (!isAdded() || isFragmentDestroyed) return;
                            showToast(isArabic ? "✅ تم الرفع" : "✅ Uploaded");
                        });
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        mainHandler.post(() -> {
                            if (!isAdded() || isFragmentDestroyed) return;
                            showToast(isArabic ? "❌ فشل الرفع: " + error 
                                    : "❌ Upload failed: " + error);
                        });
                    }
                });
    }

    // ==================== Delete ====================

    private void confirmDeleteSnapshot(InventorySnapshot snapshot) {
        if (snapshot == null) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🗑️ حذف لقطة" : "🗑️ Delete Snapshot")
                .setMessage(isArabic
                        ? "هل أنت متأكد من حذف:\n\n" + snapshot.getName() + "?"
                        : "Delete this snapshot?\n\n" + snapshot.getName() + "?")
                .setPositiveButton(isArabic ? "حذف" : "Delete", (d, w) -> {
                    boolean success = snapshotManager.deleteSnapshot(snapshot.getId());
                    if (success) {
                        showToast(isArabic ? "✅ تم الحذف" : "✅ Deleted");
                        loadSnapshots();
                    } else {
                        showToast(isArabic ? "❌ فشل الحذف" : "❌ Delete failed");
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void confirmDeleteAll() {
        if (allSnapshots.isEmpty()) {
            showToast(isArabic ? "لا توجد لقطات" : "No snapshots");
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "⚠️ حذف الكل" : "⚠️ Delete All")
                .setMessage(isArabic
                        ? "هل أنت متأكد من حذف كل اللقطات (" + allSnapshots.size() + ")؟\n\n" +
                          "⚠️ لا يمكن التراجع عن هذا الإجراء!"
                        : "Delete all snapshots (" + allSnapshots.size() + ")?\n\n" +
                          "⚠️ This cannot be undone!")
                .setPositiveButton(isArabic ? "حذف الكل" : "Delete All", (d, w) -> {
                    boolean success = snapshotManager.clearAllSnapshots();
                    if (success) {
                        showToast(isArabic ? "✅ تم الحذف" : "✅ Deleted");
                        loadSnapshots();
                    }
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    // ==================== Utilities ====================

    private void showToast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        try {
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }
}
