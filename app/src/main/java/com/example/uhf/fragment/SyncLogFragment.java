package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.SyncLogAdapter;
import com.example.uhf.dialog.EditSyncItemDialog;
import com.example.uhf.manager.IdentitySyncManager;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.utils.MiraExecutorService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

/**
 * 🏗️ SyncLogFragment - شاشة سجل المزامنة التفاعلية
 * 
 * الميزات:
 * - عرض كل السجلات المعلقة
 * - إرسال سجل مفرد
 * - إرسال الكل
 * - حذف سجل مفرد
 * - تعديل سجل
 * - تفاصيل كاملة لكل سجل
 * 
 * @author Mira Team
 * @version 1.0
 */
public class SyncLogFragment extends Fragment {

    private static final String TAG = "SyncLogFragment";

    private RecyclerView rvSyncLogs;
    private LinearLayout layoutEmptyState;
    private TextView tvStatsHeader;
    private TextView tvEmptyMessage;
    private Button btnSyncAll;
    private ProgressBar progressBar;

    private SyncLogAdapter adapter;
    private IdentitySyncManager syncManager;
    private MiraBranchesManager branchesManager;
    private MiraExecutorService miraExecutor;

    private boolean isArabic = true;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, 
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sync_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            Context ctx = requireContext();
            miraExecutor = MiraExecutorService.getInstance();
            syncManager = new IdentitySyncManager(ctx, miraExecutor.getBackgroundExecutor());
            branchesManager = MiraBranchesManager.getInstance(ctx);

            bindViews(view);
            setupRecyclerView();
            setupListeners();
            applyLanguage();

            // تحميل السجلات
            loadSyncLogsAsync();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage(), e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // تحديث السجلات عند العودة
        loadSyncLogsAsync();
    }

    // ==================== Initialization ====================

    private void bindViews(View view) {
        rvSyncLogs = view.findViewById(R.id.rvSyncLogs);
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState);
        tvStatsHeader = view.findViewById(R.id.tvStatsHeader);
        tvEmptyMessage = view.findViewById(R.id.tvEmptyMessage);
        btnSyncAll = view.findViewById(R.id.btnSyncAll);
        progressBar = view.findViewById(R.id.progressBar);
    }

    private void setupRecyclerView() {
        if (rvSyncLogs == null) return;

        adapter = new SyncLogAdapter(
                new java.util.ArrayList<>(),
                isArabic,
                new SyncLogAdapter.OnSyncLogActionListener() {
                    @Override
                    public void onSyncItem(IdentitySyncManager.SyncLogItem item) {
                        handleSyncItem(item);
                    }

                    @Override
                    public void onEditItem(IdentitySyncManager.SyncLogItem item) {
                        handleEditItem(item);
                    }

                    @Override
                    public void onDeleteItem(IdentitySyncManager.SyncLogItem item) {
                        handleDeleteItem(item);
                    }

                    @Override
                    public void onViewDetails(IdentitySyncManager.SyncLogItem item) {
                        handleViewDetails(item);
                    }
                }
        );

        rvSyncLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvSyncLogs.setAdapter(adapter);
    }

    private void setupListeners() {
        if (btnSyncAll != null) {
            btnSyncAll.setOnClickListener(v -> handleSyncAll());
        }
    }

    private void applyLanguage() {
        if (btnSyncAll != null) {
            btnSyncAll.setText(isArabic ? "🔄 مزامنة الكل" : "🔄 Sync All");
        }
        if (tvEmptyMessage != null) {
            tvEmptyMessage.setText(isArabic ? 
                    "لا توجد سجلات معلقة" : 
                    "No pending records");
        }
    }

    // ==================== Data Loading ====================

    private void loadSyncLogsAsync() {
        showLoading(true);

        miraExecutor.getBackgroundExecutor().execute(() -> {
            try {
                List<IdentitySyncManager.SyncLogItem> items = syncManager.getAllSyncLogs();
                String stats = syncManager.getSyncStats();

                handler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    updateUI(items, stats);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadSyncLogsAsync error: " + e.getMessage(), e);
                handler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showToast("❌ " + e.getMessage());
                });
            }
        });
    }

    private void updateUI(List<IdentitySyncManager.SyncLogItem> items, String stats) {
        try {
            // تحديث الإحصائيات
            if (tvStatsHeader != null) {
                tvStatsHeader.setText(stats);
            }

            // تحديث القائمة
            if (items.isEmpty()) {
                if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.VISIBLE);
                if (rvSyncLogs != null) rvSyncLogs.setVisibility(View.GONE);
                if (btnSyncAll != null) btnSyncAll.setEnabled(false);
            } else {
                if (layoutEmptyState != null) layoutEmptyState.setVisibility(View.GONE);
                if (rvSyncLogs != null) rvSyncLogs.setVisibility(View.VISIBLE);
                if (btnSyncAll != null) btnSyncAll.setEnabled(true);
            }

            adapter.updateItems(items);
        } catch (Exception e) {
            Log.e(TAG, "updateUI error: " + e.getMessage());
        }
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // ==================== Actions ====================

    private void handleSyncItem(IdentitySyncManager.SyncLogItem item) {
        if (item == null) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🔄 إرسال سجل" : "🔄 Send Record")
                .setMessage(isArabic ? 
                        "هل تريد إرسال هذا السجل الآن؟\n\n" +
                        "📝 " + item.title + "\n" +
                        "🔖 " + item.epc + "\n" +
                        "💰 " + String.format(Locale.US, "%.2f", item.priceUSD) + " USD" :
                        "Send this record now?\n\n" +
                        "📝 " + item.title + "\n" +
                        "🔖 " + item.epc + "\n" +
                        "💰 " + String.format(Locale.US, "%.2f", item.priceUSD) + " USD")
                .setPositiveButton(isArabic ? "إرسال" : "Send", (d, w) -> 
                        performSingleSync(item))
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void performSingleSync(IdentitySyncManager.SyncLogItem item) {
        showLoading(true);
        showToast(isArabic ? "⏳ جاري الإرسال..." : "⏳ Sending...");

        int branchId = (branchesManager != null) ? 
                branchesManager.getActiveBranchId() : 1;

        syncManager.syncSingleItem(item.id, branchId, 
                new IdentitySyncManager.SingleItemCallback() {
            @Override
            public void onSuccess(String epc) {
                handler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showToast(isArabic ? "✅ تم الإرسال بنجاح" : "✅ Sent successfully");
                    loadSyncLogsAsync();
                    notifyDashboardUpdate("sync_single");
                });
            }

            @Override
            public void onError(String epc, String error) {
                handler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showErrorDialog(
                            isArabic ? "❌ فشل الإرسال" : "❌ Send Failed",
                            error);
                    loadSyncLogsAsync();
                });
            }
        });
    }

    private void handleEditItem(IdentitySyncManager.SyncLogItem item) {
        if (item == null) return;

        // 🆕 فتح نافذة التعديل
        EditSyncItemDialog dialog = new EditSyncItemDialog(
                requireContext(), item, isArabic, updatedItem -> {
            // حفظ التعديل
            boolean success = syncManager.updateSyncLog(item.id, updatedItem.payload);
            if (success) {
                showToast(isArabic ? "✅ تم حفظ التعديلات" : "✅ Changes saved");
                loadSyncLogsAsync();
            } else {
                showToast(isArabic ? "❌ فشل الحفظ" : "❌ Save failed");
            }
        });
        dialog.show();
    }

    private void handleDeleteItem(IdentitySyncManager.SyncLogItem item) {
        if (item == null) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🗑️ تأكيد الحذف" : "🗑️ Confirm Delete")
                .setMessage(isArabic ? 
                        "هل أنت متأكد من حذف هذا السجل؟\n\n" +
                        "📝 " + item.title + "\n" +
                        "🔖 " + item.epc + "\n\n" +
                        "⚠️ لا يمكن التراجع عن هذا الإجراء!" :
                        "Are you sure you want to delete this record?\n\n" +
                        "📝 " + item.title + "\n" +
                        "🔖 " + item.epc + "\n\n" +
                        "⚠️ This action cannot be undone!")
                .setPositiveButton(isArabic ? "🗑️ حذف" : "🗑️ Delete", (d, w) -> 
                        performDelete(item))
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void performDelete(IdentitySyncManager.SyncLogItem item) {
        boolean success = syncManager.deleteSyncLog(item.id);
        if (success) {
            showToast(isArabic ? "🗑️ تم الحذف" : "🗑️ Deleted");
            loadSyncLogsAsync();
        } else {
            showToast(isArabic ? "❌ فشل الحذف" : "❌ Delete failed");
        }
    }

    private void handleViewDetails(IdentitySyncManager.SyncLogItem item) {
        if (item == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("📝 ").append(isArabic ? "العنوان: " : "Title: ").append(item.title).append("\n");
        sb.append("🔖 EPC: ").append(item.epc).append("\n");
        sb.append("💎 ").append(isArabic ? "العيار: " : "Karat: ").append(item.karat).append("\n");
        sb.append("⚖️ ").append(isArabic ? "الوزن: " : "Weight: ")
          .append(String.format(Locale.US, "%.2f g", item.grossWeight)).append("\n");
        sb.append("💰 ").append(isArabic ? "السعر: " : "Price: ")
          .append(String.format(Locale.US, "$%.2f", item.priceUSD)).append("\n");
        sb.append("📂 ").append(isArabic ? "الفئة: " : "Category: ").append(item.category).append("\n");
        sb.append("📍 ").append(isArabic ? "الموقع: " : "Location: ").append(item.location).append("\n");
        sb.append("🏢 ").append(isArabic ? "الفرع: " : "Branch: ").append(item.branchId).append("\n");
        sb.append("⏰ ").append(isArabic ? "تاريخ الحفظ: " : "Saved: ")
          .append(item.getFormattedSavedAt()).append("\n");
        sb.append("🔄 ").append(isArabic ? "عدد المحاولات: " : "Attempts: ")
          .append(item.attempts).append("\n");
        sb.append("📊 ").append(isArabic ? "الحالة: " : "Status: ").append(item.status).append("\n");

        if (item.lastAttempt > 0) {
            sb.append("🕐 ").append(isArabic ? "آخر محاولة: " : "Last attempt: ")
              .append(item.getFormattedLastAttempt()).append("\n");
        }

        if (item.lastError != null && !item.lastError.isEmpty()) {
            sb.append("\n❌ ").append(isArabic ? "آخر خطأ: " : "Last error: ")
              .append(item.lastError);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📋 تفاصيل السجل" : "📋 Record Details")
                .setMessage(sb.toString())
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .setNeutralButton(isArabic ? "📤 إرسال" : "📤 Send", (d, w) -> 
                        handleSyncItem(item))
                .show();
    }

    private void handleSyncAll() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🔄 مزامنة الكل" : "🔄 Sync All")
                .setMessage(isArabic ? 
                        "سيتم إرسال كل السجلات المعلقة إلى السيرفر.\n\nهل تريد المتابعة؟" :
                        "All pending records will be sent to the server.\n\nContinue?")
                .setPositiveButton(isArabic ? "مزامنة" : "Sync", (d, w) -> 
                        performSyncAll())
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void performSyncAll() {
        showLoading(true);
        showToast(isArabic ? "⏳ جاري المزامنة..." : "⏳ Syncing...");

        int branchId = (branchesManager != null) ? 
                branchesManager.getActiveBranchId() : 1;

        syncManager.syncPendingEncodesDetailed(branchId, 
                new IdentitySyncManager.DetailedSyncCallback() {
            @Override
            public void onSyncStart(int totalCount) {
                Log.i(TAG, "Sync started: " + totalCount + " items");
            }

            @Override
            public void onItemSynced(int itemIndex, int totalCount, String epc) {
                Log.d(TAG, "✅ " + (itemIndex + 1) + "/" + totalCount + " - " + epc);
            }

            @Override
            public void onItemFailed(int itemIndex, int totalCount, String epc, 
                                     String error) {
                Log.w(TAG, "❌ Failed: " + epc + " - " + error);
            }

            @Override
            public void onSyncComplete(int syncedCount, int failedCount) {
                handler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    String msg = isArabic ? 
                            "✅ تمت المزامنة: " + syncedCount + 
                            (failedCount > 0 ? " | فشل: " + failedCount : "") :
                            "✅ Synced: " + syncedCount + 
                            (failedCount > 0 ? " | Failed: " + failedCount : "");

                    showToast(msg);
                    loadSyncLogsAsync();
                    notifyDashboardUpdate("sync_all");
                });
            }

            @Override
            public void onSyncError(String error) {
                handler.post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    showErrorDialog(
                            isArabic ? "❌ خطأ في المزامنة" : "❌ Sync Error",
                            error);
                });
            }
        });
    }

    // ==================== Utilities ====================

    private void notifyDashboardUpdate(String source) {
        try {
            Context ctx = getContext();
            if (ctx == null) return;

            int branchId = (branchesManager != null) ? 
                    branchesManager.getActiveBranchId() : 0;

            Intent dashboardIntent = new Intent("com.example.uhf.REFRESH_DASHBOARD");
            dashboardIntent.setPackage(ctx.getPackageName());
            dashboardIntent.putExtra("source", source);
            dashboardIntent.putExtra("branch_id", branchId);
            dashboardIntent.putExtra("timestamp", System.currentTimeMillis());
            ctx.sendBroadcast(dashboardIntent);
        } catch (Exception e) {
            Log.e(TAG, "notifyDashboardUpdate error: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        try {
            if (getContext() != null && isAdded()) {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "showToast error: " + e.getMessage());
        }
    }

    private void showErrorDialog(String title, String message) {
        try {
            if (getContext() == null || !isAdded()) return;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showErrorDialog error: " + e.getMessage());
        }
    }
}
