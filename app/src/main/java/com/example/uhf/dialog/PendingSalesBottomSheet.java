package com.example.uhf.dialog;

import android.content.Context;
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
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.OfflineSalesSyncer;
import com.example.uhf.manager.PendingSale;
import com.example.uhf.manager.PendingSalesQueue;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.PosUIManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  PendingSalesBottomSheet — نافذة إدارة البيوع المعلّقة
 * ═══════════════════════════════════════════════════════════════════
 *
 *  الميزات:
 *   ✅ عرض قائمة البيوع المعلّقة
 *   ✅ حالة كل بيع (pending / failed)
 *   ✅ عدد محاولات الفشل
 *   ✅ إعادة المحاولة لكل بيع
 *   ✅ حذف (للمدراء فقط)
 *   ✅ مزامنة الكل
 *   ✅ تحديث حيّ أثناء المزامنة
 *
 * @since v1.0
 */
public class PendingSalesBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "PendingSalesSheet";

    private PendingSalesQueue queue;
    private OfflineSalesSyncer syncer;
    private PermissionManager permissionManager;
    private boolean isArabic;

    private RecyclerView rvPendingSales;
    private LinearLayout emptyState;
    private TextView tvSubtitle;
    private TextView tvSyncStatus;
    private CircularProgressIndicator pbSyncing;
    private MaterialButton btnRetryAll;
    private MaterialButton btnClose;

    private PendingSalesAdapter adapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_pending_sales, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context ctx = requireContext().getApplicationContext();
            queue = PendingSalesQueue.getInstance(ctx);
            syncer = OfflineSalesSyncer.getInstance(ctx);
            permissionManager = PermissionManager.getInstance(ctx);
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            // Bind views
            rvPendingSales = view.findViewById(R.id.rvPendingSales);
            emptyState = view.findViewById(R.id.emptyState);
            tvSubtitle = view.findViewById(R.id.tvSubtitle);
            tvSyncStatus = view.findViewById(R.id.tvSyncStatus);
            pbSyncing = view.findViewById(R.id.pbSyncing);
            btnRetryAll = view.findViewById(R.id.btnRetryAll);
            btnClose = view.findViewById(R.id.btnClose);

            // RecyclerView
            rvPendingSales.setLayoutManager(new LinearLayoutManager(getContext()));
            adapter = new PendingSalesAdapter();
            rvPendingSales.setAdapter(adapter);

            // Buttons
            btnClose.setOnClickListener(v -> dismiss());
            btnRetryAll.setOnClickListener(v -> retryAll());

            // Load data
            reload();

        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Data
    // ═══════════════════════════════════════════════════════════════

    private void reload() {
        try {
            List<PendingSale> pending = queue.getPending(100);
            
            if (adapter != null) {
                adapter.setItems(pending);
            }

            updateUI(pending);
        } catch (Exception e) {
            Log.e(TAG, "reload error", e);
        }
    }

    private void updateUI(List<PendingSale> pending) {
        try {
            int count = pending.size();

            if (count == 0) {
                emptyState.setVisibility(View.VISIBLE);
                rvPendingSales.setVisibility(View.GONE);
                tvSubtitle.setText(isArabic
                        ? "لا توجد بيوع في الانتظار"
                        : "No pending sales");
                btnRetryAll.setEnabled(false);
            } else {
                emptyState.setVisibility(View.GONE);
                rvPendingSales.setVisibility(View.VISIBLE);
                tvSubtitle.setText(isArabic
                        ? count + " بيوع في الانتظار"
                        : count + " pending sales");
                btnRetryAll.setEnabled(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateUI error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════════

    private void retryAll() {
        try {
            if (syncer == null) return;

            if (syncer.isSyncing()) {
                showToast(isArabic
                        ? "المزامنة قيد التنفيذ..."
                        : "Sync already in progress...");
                return;
            }

            showSyncingState(true);

            syncer.syncAll(new OfflineSalesSyncer.SyncCallback() {
                @Override
                public void onSyncStart(int total) {
                    mainHandler.post(() -> {
                        tvSyncStatus.setText(isArabic
                                ? "جاري مزامنة " + total + " بيوع..."
                                : "Syncing " + total + " sales...");
                    });
                }

                @Override
                public void onSaleSynced(PendingSale sale, boolean success,
                                         @Nullable String error) {
                    // Silent — reload في النهاية
                }

                @Override
                public void onSyncComplete(int synced, int failed, int remaining) {
                    mainHandler.post(() -> {
                        showSyncingState(false);
                        
                        String msg;
                        if (remaining == 0 && failed == 0) {
                            msg = isArabic
                                    ? "✅ تمت مزامنة الكل (" + synced + ")"
                                    : "✅ All synced (" + synced + ")";
                        } else if (synced > 0) {
                            msg = isArabic
                                    ? "✅ " + synced + " نجحت، " + remaining + " متبقية"
                                    : "✅ " + synced + " synced, " + remaining + " remaining";
                        } else {
                            msg = isArabic
                                    ? "⚠️ فشلت المزامنة"
                                    : "⚠️ Sync failed";
                        }
                        showToast(msg);
                        
                        reload();
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "retryAll error", e);
            showSyncingState(false);
        }
    }

    private void retrySingle(PendingSale sale) {
        try {
            if (syncer == null || sale == null) return;

            if (syncer.isSyncing()) {
                showToast(isArabic
                        ? "المزامنة قيد التنفيذ..."
                        : "Sync already in progress...");
                return;
            }

            showSyncingState(true);

            syncer.syncAll(new OfflineSalesSyncer.SyncCallback() {
                @Override
                public void onSyncStart(int total) {
                    // Silent
                }

                @Override
                public void onSaleSynced(PendingSale s, boolean success,
                                         @Nullable String error) {
                    mainHandler.post(() -> {
                        // نتابع فقط البيع المطلوب
                    });
                }

                @Override
                public void onSyncComplete(int synced, int failed, int remaining) {
                    mainHandler.post(() -> {
                        showSyncingState(false);
                        reload();
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "retrySingle error", e);
            showSyncingState(false);
        }
    }

    private void deleteSale(PendingSale sale) {
        try {
            if (sale == null) return;

            // ✅ فحص صلاحية الحذف
            boolean canDelete = permissionManager != null
                    && (permissionManager.isManager()
                        || permissionManager.isAdmin());

            if (!canDelete) {
                showToast(isArabic
                        ? "⛔ غير مصرح بالحذف"
                        : "⛔ Not authorized to delete");
                return;
            }

            // تأكيد
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "⚠️ تأكيد الحذف" : "⚠️ Confirm Delete")
                    .setMessage(isArabic
                            ? "سيتم حذف هذا البيع نهائياً من الطابور.\n\n"
                            + "العميل: " + safe(sale.customerName) + "\n"
                            + "المبلغ: " + String.format(Locale.US, "%.2f", sale.totalUsd)
                            : "This pending sale will be deleted permanently.\n\n"
                            + "Customer: " + safe(sale.customerName) + "\n"
                            + "Amount: " + String.format(Locale.US, "%.2f", sale.totalUsd))
                    .setPositiveButton(isArabic ? "حذف" : "Delete",
                            (d, w) -> {
                                if (queue.delete(sale.localUuid)) {
                                    showToast(isArabic
                                            ? "🗑️ تم الحذف"
                                            : "🗑️ Deleted");
                                    reload();
                                } else {
                                    showToast(isArabic
                                            ? "❌ فشل الحذف"
                                            : "❌ Delete failed");
                                }
                            })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "deleteSale error", e);
        }
    }

    private void showSyncingState(boolean syncing) {
        try {
            pbSyncing.setVisibility(syncing ? View.VISIBLE : View.GONE);
            tvSyncStatus.setVisibility(syncing ? View.VISIBLE : View.GONE);
            btnRetryAll.setEnabled(!syncing);
            
            if (syncing) {
                tvSyncStatus.setText(isArabic ? "جاري المزامنة..." : "Syncing...");
            }
        } catch (Exception e) {
            Log.e(TAG, "showSyncingState error", e);
        }
    }

    private void showToast(String msg) {
        try {
            PosUIManager.toast(getContext(), msg);
        } catch (Exception ignored) {}
    }

    private String safe(String s) {
        return s != null && !s.isEmpty() ? s : "—";
    }

    // ═══════════════════════════════════════════════════════════════
    //  Adapter
    // ═══════════════════════════════════════════════════════════════

    private class PendingSalesAdapter extends RecyclerView.Adapter<PendingSalesAdapter.VH> {

        private final List<PendingSale> items = new ArrayList<>();

        public void setItems(List<PendingSale> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pending_sale, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            PendingSale sale = items.get(position);

            // العميل
            h.tvCustomer.setText(safe(sale.customerName));

            // المبلغ
            h.tvAmount.setText(String.format(Locale.US, "%,.2f", sale.totalUsd));

            // الوقت
            h.tvTime.setText("📅 " + sale.getFormattedTime());

            // عدد القطع
            h.tvItemCount.setText("🛒 " + sale.itemCount
                    + (isArabic ? " قطعة" : " items"));

            // عدد المحاولات
            h.tvRetryCount.setText("🔄 " + sale.retryCount + "/5");

            // الحالة
            if (sale.status == PendingSale.STATUS_FAILED) {
                h.vStatusDot.setBackgroundResource(R.drawable.circle_red);
                h.tvStatus.setVisibility(View.VISIBLE);
                h.tvStatus.setText("❌ " + safe(sale.lastError));
                h.tvStatus.setTextColor(
                        ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark));
            } else if (sale.retryCount > 0) {
                h.vStatusDot.setBackgroundResource(R.drawable.circle_yellow);
                h.tvStatus.setVisibility(View.VISIBLE);
                h.tvStatus.setText(isArabic
                        ? "⏳ فشلت " + sale.retryCount + " محاولة"
                        : "⏳ " + sale.retryCount + " failed attempts");
                h.tvStatus.setTextColor(
                        ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark));
            } else {
                h.vStatusDot.setBackgroundResource(R.drawable.circle_yellow);
                h.tvStatus.setVisibility(View.GONE);
            }

            // زر Retry
            h.btnRetry.setOnClickListener(v -> retrySingle(sale));

            // زر Delete
            boolean canDelete = permissionManager != null
                    && (permissionManager.isManager() || permissionManager.isAdmin());
            h.btnDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
            h.btnDelete.setOnClickListener(v -> deleteSale(sale));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            View vStatusDot;
            TextView tvCustomer, tvAmount, tvTime, tvItemCount, tvRetryCount, tvStatus;
            MaterialButton btnRetry, btnDelete;

            VH(View v) {
                super(v);
                vStatusDot = v.findViewById(R.id.vStatusDot);
                tvCustomer = v.findViewById(R.id.tvCustomer);
                tvAmount = v.findViewById(R.id.tvAmount);
                tvTime = v.findViewById(R.id.tvTime);
                tvItemCount = v.findViewById(R.id.tvItemCount);
                tvRetryCount = v.findViewById(R.id.tvRetryCount);
                tvStatus = v.findViewById(R.id.tvStatus);
                btnRetry = v.findViewById(R.id.btnRetry);
                btnDelete = v.findViewById(R.id.btnDelete);
            }
        }
    }
}
