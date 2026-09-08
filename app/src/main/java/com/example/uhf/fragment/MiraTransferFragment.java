package com.example.uhf.fragment;

import androidx.appcompat.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.SecureSessionManager;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.ApiResponse;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraTransferFragment extends Fragment {

    private SessionManager sessionManager;
    private static final String TAG = "MiraTransfer";
    private static final String OFFLINE_PREFS = "transfers_cache";
    public static final String ACTION_REFRESH_TRANSFERS = "com.example.uhf.REFRESH_TRANSFERS";

    private RecyclerView rvTransfers;
    private TextView tvEmpty, tvActiveBranch;
    private Button btnNewTransfer, btnScanVerify;
    private ChipGroup chipGroupFilters;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private final List<TransferData> allTransferList = new ArrayList<>();
    private final List<TransferData> displayedTransferList = new ArrayList<>();
    private TransferAdapter adapter;
    private String currentFilter = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private boolean isArabic;
    private SharedPreferences offlineCache;
    private TransferData selectedTransfer = null;

    private final Set<String> activeScanEpcSet = new HashSet<>();
    private TextView activeDialogScannedTv;
    private TextView tvScanCounter;
    private AlertDialog loadingDialog;
    private ToneGenerator toneGenerator;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_REFRESH_TRANSFERS.equals(intent.getAction())) {
                loadTransfers();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transfer_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        executor = Executors.newSingleThreadExecutor();
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        view.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        
        Context context = requireContext();
        apiClient = MiraApiClient.getInstance(context);
        branchesManager = MiraBranchesManager.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        offlineCache = context.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        sessionManager = new SessionManager(context);
        
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        } catch (Exception ignored) {}

        initViews(view);
        setupListeners();
        applyPermissions();
        applyLanguage();

        rvTransfers.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransferAdapter(displayedTransferList, new TransferClickHandler() {
            @Override public void onClick(TransferData t) { showTransferActions(t); }
            @Override public void onAction(TransferData t, String action) { handleTransferAction(t, action); }
        }, isArabic);
        rvTransfers.setAdapter(adapter);

        LocalBroadcastManager.getInstance(context).registerReceiver(
                refreshReceiver, new IntentFilter(ACTION_REFRESH_TRANSFERS));

        loadTransfers();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissLoadingDialog();
        if (getContext() != null) {
            LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(refreshReceiver);
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    private void applyLanguage() {
        if (btnNewTransfer != null) {
            btnNewTransfer.setText(isArabic ? "📝 تحويل جديد" : "📝 New Transfer");
        }
        if (btnScanVerify != null) {
            btnScanVerify.setText(isArabic ? "📷 مسح وتدقيق" : "📷 Scan & Verify");
        }
        if (tvEmpty != null) {
            tvEmpty.setText(isArabic ? "📭 لا توجد تحويلات" : "📭 No transfers");
        }
    }

    private void applyPermissions() {
        SecureSessionManager.Role userRole = sessionManager != null ? sessionManager.getUserRole() : SecureSessionManager.Role.BRANCH_MANAGER;
        
        boolean isTopLevel = userRole == SecureSessionManager.Role.SYSTEM_ADMIN || 
                             userRole == SecureSessionManager.Role.ORG_ADMIN;
        
        boolean canCreate = isTopLevel || 
                           userRole == SecureSessionManager.Role.BRANCH_MANAGER || 
                           permissionManager.canEdit("transfers");

        if (btnNewTransfer != null) {
            btnNewTransfer.setEnabled(canCreate);
            btnNewTransfer.setAlpha(canCreate ? 1.0f : 0.5f);
        }
        
        if (btnScanVerify != null) {
            btnScanVerify.setEnabled(permissionManager.canView("transfers") || isTopLevel);
        }
    }

    private boolean canCreateTransfer() {
        return permissionManager.canEdit("transfers") || 
               permissionManager.isManager() || 
               permissionManager.isInventoryManager();
    }

    private boolean canApproveTransfer() {
        return permissionManager.isManager() || 
               permissionManager.isBranchManager() || 
               permissionManager.canEdit("transfers");
    }

    private boolean canReceiveTransfer(TransferData t) {
        if (t == null) return false;
        int userBranchId = branchesManager.getActiveBranchId();
        
        // لا يجوز للفرع المرسل استلام الشحنة
        if (t.fromBranchId == userBranchId) {
            return false;
        }
        
        SecureSessionManager.Role userRole = sessionManager != null ? sessionManager.getUserRole() : SecureSessionManager.Role.BRANCH_MANAGER;
        
        return (userRole == SecureSessionManager.Role.SYSTEM_ADMIN || userRole == SecureSessionManager.Role.ORG_ADMIN) 
                || (t.toBranchId == userBranchId);
    }

    private void initViews(View view) {
        rvTransfers = view.findViewById(R.id.rvTransfers);
        tvEmpty = view.findViewById(R.id.tvEmptyTransfers);
        tvActiveBranch = view.findViewById(R.id.tvTransferCount);
        btnNewTransfer = view.findViewById(R.id.btnNewTransfer);
        btnScanVerify = view.findViewById(R.id.btnScanItems);
        chipGroupFilters = view.findViewById(R.id.chipGroupFilters);
        
        if (chipGroupFilters != null) {
            chipGroupFilters.setSingleSelection(true);
            chipGroupFilters.setSelectionRequired(true);
        }
    }

    private void setupFilterChips() {
        if (chipGroupFilters == null) return;
        Context context = getContext();
        if (context == null || !isAdded()) return;
        
        chipGroupFilters.removeAllViews();
        Map<String, Integer> counts = getStatusCounts();
        
        FilterItem[] filters = new FilterItem[]{
            new FilterItem("", isArabic ? "الكل" : "All", counts.get("all"), Color.parseColor("#455A64")),
            new FilterItem("draft", isArabic ? "مسودة" : "Draft", counts.get("draft"), Color.parseColor("#78909C")),
            new FilterItem("pending", isArabic ? "معلق" : "Pending", counts.get("pending"), Color.parseColor("#F57C00")),
            new FilterItem("in_transit", isArabic ? "قيد النقل" : "In Transit", counts.get("in_transit"), Color.parseColor("#1976D2")),
            new FilterItem("received", isArabic ? "مستلم" : "Received", counts.get("received"), Color.parseColor("#388E3C"))
        };

        for (FilterItem item : filters) {
            Chip chip = new Chip(context); 
            chip.setText(String.format(Locale.getDefault(), "%s (%d)", item.label, item.count));
            chip.setCheckable(true);
            chip.setClickable(true);
            
            int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] { -android.R.attr.state_checked }
            };
            
            int[] backgroundColors = new int[] { item.color, Color.parseColor("#F0F4F8") };
            int[] textColors = new int[] { Color.WHITE, item.color };

            chip.setChipBackgroundColor(new ColorStateList(states, backgroundColors));
            chip.setTextColor(new ColorStateList(states, textColors));
            chip.setChipStrokeColor(ColorStateList.valueOf(item.color));
            chip.setChipStrokeWidth(2f);
            
            if (item.statusValue.equals(currentFilter)) {
                chip.setChecked(true);
            }

            chip.setOnClickListener(v -> {
                if (!currentFilter.equals(item.statusValue)) {
                    currentFilter = item.statusValue;
                    applyFilterAndRefreshList();
                }
            });

            chipGroupFilters.addView(chip);
        }
    }

    private Map<String, Integer> getStatusCounts() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("all", allTransferList.size());
        counts.put("draft", 0);
        counts.put("pending", 0);
        counts.put("in_transit", 0);
        counts.put("received", 0);

        for (TransferData t : allTransferList) {
            if (counts.containsKey(t.status)) {
                counts.put(t.status, counts.get(t.status) + 1);
            } else if ("approved".equals(t.status)) {
                counts.put("in_transit", counts.get("in_transit") + 1);
            }
        }
        return counts;
    }

    private void applyFilterAndRefreshList() {
        displayedTransferList.clear();
        for (TransferData t : allTransferList) {
            if (currentFilter.isEmpty()) {
                displayedTransferList.add(t);
            } else if (currentFilter.equals("in_transit") && ("in_transit".equals(t.status) || "approved".equals(t.status))) {
                displayedTransferList.add(t);
            } else if (currentFilter.equals(t.status)) {
                displayedTransferList.add(t);
            }
        }
        
        adapter.notifyDataSetChanged();
        updateEmptyState();
        if (rvTransfers != null) {
            rvTransfers.scheduleLayoutAnimation();
        }
    }

    private void setupListeners() {
        btnNewTransfer.setOnClickListener(v -> {
            if (canCreateTransfer()) {
                showNewTransferDialog();
            } else {
                showUserMessage(
                    isArabic ? "⛔ لا تملك صلاحية" : "⛔ Access Denied",
                    isArabic ? "عذراً، ليس لديك صلاحية لإنشاء تحويلات جديدة.\nيرجى التواصل مع مدير النظام." : 
                               "Sorry, you don't have permission to create transfers.\nPlease contact your system administrator."
                );
            }
        });
        
        btnScanVerify.setOnClickListener(v -> {
            if (selectedTransfer != null) {
                showScanItemsDialogForTransfer(selectedTransfer);
            } else if (!allTransferList.isEmpty()) {
                showTransferPickerForScan();
            } else {
                showUserMessage(
                    isArabic ? "📭 لا توجد تحويلات" : "📭 No Transfers",
                    isArabic ? "لا توجد تحويلات نشطة للمسح حالياً." : "There are no active transfers to scan."
                );
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (tvActiveBranch != null) {
            tvActiveBranch.setText(ar("الفرع الحالي: ", "Current Branch: ") + branchesManager.getActiveBranchDisplayName());
        }
        loadTransfers();
    }

    private void loadTransfers() {
        if (executor == null || executor.isShutdown()) return;
        
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            SecureSessionManager.Role userRole = sessionManager != null ? sessionManager.getUserRole() : SecureSessionManager.Role.BRANCH_MANAGER;
            boolean isTopLevel = userRole == SecureSessionManager.Role.SYSTEM_ADMIN || userRole == SecureSessionManager.Role.ORG_ADMIN;
            
            ApiResponse response = apiClient.getTransfers(branchId, "");
            
            mainHandler.post(() -> {
                if (!isAdded() || getContext() == null) return;

                allTransferList.clear();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray transfers = response.data.optJSONArray("transfers");
                    if (transfers != null) {
                        for (int i = 0; i < transfers.length(); i++) {
                            TransferData d = parseTransferData(transfers.optJSONObject(i));
                            if (d != null) {
                                if (!isTopLevel) {
                                    if (d.fromBranchId == branchId || d.toBranchId == branchId) {
                                        allTransferList.add(d);
                                    }
                                } else {
                                    allTransferList.add(d);
                                }
                            }
                        }
                    }
                    saveTransfersOffline(transfers);
                } else {
                    loadOfflineTransfers();
                }
                
                setupFilterChips();
                applyFilterAndRefreshList();
            });
        });
    }

    private TransferData parseTransferData(JSONObject t) {
        if (t == null) return null;
        TransferData d = new TransferData();
        d.id = t.optInt("id");
        d.transferNumber = t.optString("transfer_number", "TR-" + t.optInt("id"));
        d.fromBranch = t.optString("from_branch_name_ar", t.optString("from_branch_name", ""));
        d.toBranch = t.optString("to_branch_name_ar", t.optString("to_branch_name", ""));
        d.fromBranchId = t.optInt("from_branch_id");
        d.toBranchId = t.optInt("to_branch_id");
        d.status = t.optString("status", "draft");
        d.itemCount = t.optInt("item_count", 0);
        d.totalWeight = t.optDouble("total_weight", 0);
        d.notes = t.optString("notes", "");
        d.createdAt = t.optString("created_at", "");
        return d;
    }

    private void updateEmptyState() {
        if (tvEmpty == null || rvTransfers == null) return;
        boolean empty = displayedTransferList.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvTransfers.setVisibility(empty ? View.GONE : View.VISIBLE);
        
        if (empty) {
            tvEmpty.setText(isArabic ? 
                "📭 لا توجد تحويلات مطابقة\nاختر خيار فلترة آخر أو أنشئ تحويل جديد" : 
                "📭 No matching transfers\nSelect another filter or create a new transfer");
        }
    }

    private void saveTransfersOffline(JSONArray transfers) {
        try {
            if (offlineCache == null || transfers == null) return;
            offlineCache.edit().putString("transfers", transfers.toString())
                .putLong("last_update", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    private void loadOfflineTransfers() {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("transfers", "");
            if (json.isEmpty()) return;
            JSONArray transfers = new JSONArray(json);
            allTransferList.clear();
            for (int i = 0; i < transfers.length(); i++) {
                TransferData d = parseTransferData(transfers.optJSONObject(i));
                if (d != null) {
                    allTransferList.add(d);
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleTransferAction(TransferData t, String action) {
        selectedTransfer = t;
        switch (action) {
            case "approve": 
                if (canApproveTransfer()) approveTransfer(t);
                else showAccessDenied();
                break;
            case "receive": 
                if (canReceiveTransfer(t)) receiveTransfer(t);
                else showUserMessage(isArabic ? "⛔ غير مصرح" : "⛔ Not Authorized", isArabic ? "فقط الفرع المستقبل (" + t.toBranch + ") يمكنه تأكيد الاستلام." : "Only the receiving branch (" + t.toBranch + ") can confirm receipt.");
                break;
            case "scan": 
                showScanItemsDialogForTransfer(t); 
                break;
            case "items":
                showTransferItems(t);
                break;
        }
    }

    private void showAccessDenied() {
        showUserMessage(isArabic ? "⛔ لا تملك صلاحية" : "⛔ Access Denied", isArabic ? "عذراً، ليس لديك صلاحية لتنفيذ هذا الإجراء." : "Sorry, you don't have permission to perform this action.");
    }

    private void showTransferActions(TransferData t) {
        if (!isAdded() || getContext() == null || t == null) return;
        selectedTransfer = t;
        List<String> options = new ArrayList<>();
        
        options.add(ar("📦 عرض وإدارة القطع (" + t.itemCount + ")", "📦 View & Manage Items (" + t.itemCount + ")"));
        
        if ("draft".equals(t.status) || "pending".equals(t.status)) {
            if (canCreateTransfer()) {
                options.add(ar("➕ إضافة قطعة للشحنة", "➕ Add Item to Transfer"));
            }
            if (canApproveTransfer()) {
                options.add(ar("✅ اعتماد وتجهيز الشحنة", "✅ Approve & Dispatch"));
            }
            options.add(ar("🚫 إلغاء الشحنة بالكامل", "🚫 Cancel Entire Transfer"));
        } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
            if (canReceiveTransfer(t)) {
                options.add(ar("📥 استلام الشحنة", "📥 Receive Shipment"));
            }
            options.add(ar("🔍 تدقيق ميداني", "🔍 Field Scan Audit"));
        }
        
        options.add(ar("📋 عرض التفاصيل", "📋 View Details"));

        String[] items = options.toArray(new String[0]);
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle((t.transferNumber != null ? t.transferNumber : "TR-" + t.id) + " (" + t.fromBranch + " ➔ " + t.toBranch + ")")
            .setItems(items, (dialog, which) -> {
                String sel = items[which];
                if (sel.contains("عرض وإدارة") || sel.contains("View & Manage")) {
                    showTransferItems(t);
                } else if (sel.contains("إضافة قطعة") || sel.contains("Add Item")) {
                    showScanItemsDialogForTransfer(t);
                } else if (sel.contains("اعتماد") || sel.contains("Approve")) {
                    if (canApproveTransfer()) approveTransfer(t);
                    else showAccessDenied();
                } else if (sel.contains("استلام") || sel.contains("Receive")) {
                    if (canReceiveTransfer(t)) receiveTransfer(t);
                    else showUserMessage(isArabic ? "⛔ غير مصرح" : "⛔ Not Allowed", isArabic ? "لا يجوز للفرع المرسل استلام الشحنة!" : "Sending branch cannot receive shipment!");
                } else if (sel.contains("إلغاء الشحنة") || sel.contains("Cancel Entire")) {
                    cancelTransfer(t);
                } else if (sel.contains("تفاصيل") || sel.contains("Details")) {
                    showTransferDetails(t);
                }
            })
            .setNegativeButton(ar("إغلاق", "Close"), null).show();
    }

    private void cancelTransfer(TransferData t) {
        if (!canCreateTransfer() && !permissionManager.isManager()) {
            showAccessDenied();
            return;
        }
        
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(ar("🚫 إلغاء الشحنة", "🚫 Cancel Transfer"))
            .setMessage(isArabic ? 
                "هل أنت متأكد من إلغاء الشحنة " + t.transferNumber + " بالكامل؟" : 
                "Are you sure you want to cancel transfer " + t.transferNumber + "?")
            .setPositiveButton(ar("🚫 تأكيد الإلغاء", "🚫 Confirm Cancel"), (d, w) -> {
                showLoadingDialog(isArabic ? "جاري إلغاء الشحنة..." : "Cancelling transfer...");
                executor.execute(() -> {
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("status", "cancelled");
                        
                        ApiResponse response = apiClient.updateTransfer(t.id, payload);
                        mainHandler.post(() -> {
                            dismissLoadingDialog();
                            if (!isAdded() || getContext() == null) return;
                            
                            if (response != null && response.isSuccess) {
                                showSuccessMessage(
                                    isArabic ? "تم الإلغاء" : "Transfer Cancelled", 
                                    isArabic ? "تم إلغاء الشحنة بنجاح." : "The transfer has been successfully cancelled."
                                );
                                loadTransfers();
                                notifyDashboardUpdate();
                            } else {
                                showErrorMessage(isArabic ? "فشل الإلغاء" : "Cancel Failed", getErrorMessage(response));
                            }
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            dismissLoadingDialog();
                            showErrorMessage(isArabic ? "خطأ" : "Error", e.getMessage());
                        });
                    }
                });
            })
            .setNegativeButton(ar("تراجع", "Cancel"), null)
            .show();
    }

    private void showTransferItems(TransferData t) {
        if (t == null) return;
        showLoadingDialog(isArabic ? "جاري تحميل القطع..." : "Loading items...");
        
        executor.execute(() -> {
            ApiResponse response = apiClient.getTransfer(t.id);
            mainHandler.post(() -> {
                dismissLoadingDialog();
                if (!isAdded() || getContext() == null) return;

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray items = response.data.optJSONArray("items");
                    if (items == null || items.length() == 0) {
                        showUserMessage(isArabic ? "📦 لا توجد قطع" : "📦 No Items", isArabic ? "لا توجد قطع مضافة في هذا التحويل بعد." : "No items added to this transfer yet.");
                        return;
                    }
                    
                    StringBuilder itemsText = new StringBuilder();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item != null) {
                            String title = item.optString("item_title", "");
                            String serial = item.optString("serial_number", "");
                            String rfid = item.optString("rfid_epc", "");
                            double weight = item.optDouble("gross_weight_grams", 0);
                            String karat = item.optString("gold_karat", "");
                            
                            itemsText.append("🔸 ").append(i + 1).append(". ").append(title.isEmpty() ? "قطعة" : title).append("\n");
                            if (!serial.isEmpty()) itemsText.append("   SN: ").append(serial).append(" ");
                            if (!rfid.isEmpty()) itemsText.append("| EPC: ").append(rfid).append("\n");
                            if (weight > 0) itemsText.append("   الوزن: ").append(String.format(Locale.US, "%.2f", weight)).append("g ").append(!karat.isEmpty() ? "| عيار: " + karat : "").append("\n\n");
                        }
                    }
                    
                    new MaterialAlertDialogBuilder(requireContext())
                        .setTitle((isArabic ? "📦 قطع التحويل " : "📦 Transfer Items ") + t.transferNumber)
                        .setMessage(itemsText.toString())
                        .setPositiveButton(ar("حسناً", "OK"), null)
                        .show();
                } else {
                    showErrorMessage(isArabic ? "خطأ في التحميل" : "Load Error", isArabic ? "تعذر تحميل قطع التحويل." : "Could not load transfer items.");
                }
            });
        });
    }

    private void showLoadingDialog(String message) {
        if (!isAdded() || getContext() == null) return;
        try {
            dismissLoadingDialog();
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
            builder.setMessage(message);
            builder.setCancelable(false);
            loadingDialog = builder.show();
        } catch (Exception ignored) {}
    }

    private void dismissLoadingDialog() {
        try {
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
        } catch (Exception ignored) {}
    }

    private void showTransferDetails(TransferData t) {
        if (!isAdded() || getContext() == null || t == null) return;
        String statusText = getStatusText(t.status);
        String details = isArabic ?
            "📋 رقم التحويل: " + t.transferNumber + "\n" +
            "🏢 من فرع: " + t.fromBranch + "\n" +
            "🏢 إلى فرع: " + t.toBranch + "\n" +
            "📊 الحالة: " + statusText + "\n" +
            "📦 إجمالي القطع: " + t.itemCount + " قطعة\n" +
            "⚖️ الوزن الإجمالي: " + String.format(Locale.US, "%.2f جرام", t.totalWeight) + "\n" +
            "📅 التاريخ: " + (t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "-") +
            (t.notes != null && !t.notes.isEmpty() ? "\n📝 ملاحظات: " + t.notes : "") :
            "📋 Transfer No: " + t.transferNumber + "\n" +
            "🏢 From: " + t.fromBranch + "\n" +
            "🏢 To: " + t.toBranch + "\n" +
            "📊 Status: " + statusText + "\n" +
            "📦 Total Items: " + t.itemCount + "\n" +
            "⚖️ Total Weight: " + String.format(Locale.US, "%.2f g", t.totalWeight) + "\n" +
            "📅 Date: " + (t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "-") +
            (t.notes != null && !t.notes.isEmpty() ? "\n📝 Notes: " + t.notes : "");

        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(ar("📋 بيان الشحنة", "📋 Transfer Manifest"))
            .setMessage(details)
            .setPositiveButton(ar("حسناً", "OK"), null)
            .show();
    }

    private void showTransferPickerForScan() {
        if (!isAdded() || getContext() == null) return;
        List<String> titles = new ArrayList<>();
        List<TransferData> activeList = new ArrayList<>();
        for (TransferData t : allTransferList) {
            if ("draft".equals(t.status) || "pending".equals(t.status) || "approved".equals(t.status) || "in_transit".equals(t.status)) {
                titles.add(t.transferNumber + " | " + t.fromBranch + " ➔ " + t.toBranch);
                activeList.add(t);
            }
        }
        if (activeList.isEmpty()) {
            showUserMessage(isArabic ? "📭 لا توجد شحنات نشطة" : "📭 No Active Transfers", isArabic ? "لا توجد شحنات نشطة حالياً للمسح." : "There are no active transfers to scan.");
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(ar("اختر الشحنة للمسح", "Select Transfer for Scan"))
            .setItems(titles.toArray(new String[0]), (d, w) -> {
                selectedTransfer = activeList.get(w);
                showScanItemsDialogForTransfer(selectedTransfer);
            }).show();
    }

    private void approveTransfer(TransferData t) {
        if (!canApproveTransfer() || !isAdded() || getContext() == null || t == null) {
            showAccessDenied();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "✅ اعتماد الشحنة" : "✅ Approve Shipment")
            .setMessage(isArabic ? "هل أنت متأكد من اعتماد الشحنة " + t.transferNumber + "؟" : "Are you sure you want to approve shipment " + t.transferNumber + "?")
            .setPositiveButton(isArabic ? "✅ اعتماد" : "✅ Approve", (d, w) -> {
                executor.execute(() -> {
                    ApiResponse response = apiClient.approveTransfer(t.id);
                    mainHandler.post(() -> {
                        if (!isAdded() || getContext() == null) return;
                        if (response != null && response.isSuccess) {
                            showSuccessMessage(isArabic ? "تم الاعتماد بنجاح" : "Approved Successfully", isArabic ? "تم اعتماد الشحنة بنجاح." : "Shipment approved successfully.");
                            loadTransfers();
                            notifyDashboardUpdate();
                        } else {
                            showErrorMessage(isArabic ? "فشل الاعتماد" : "Approval Failed", getErrorMessage(response));
                        }
                    });
                });
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    private void receiveTransfer(TransferData t) {
        if (!canReceiveTransfer(t) || !isAdded() || getContext() == null || t == null) {
            showAccessDenied();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📥 استلام الشحنة" : "📥 Receive Shipment")
            .setMessage(isArabic ? "هل تأكدت من استلام جميع القطع في الشحنة " + t.transferNumber + "؟" : "Have you verified all items in shipment " + t.transferNumber + "?")
            .setPositiveButton(isArabic ? "📥 تأكيد الاستلام" : "📥 Confirm Receive", (d, w) -> {
                executor.execute(() -> {
                    ApiResponse response = apiClient.receiveTransfer(t.id);
                    mainHandler.post(() -> {
                        if (!isAdded() || getContext() == null) return;
                        if (response != null && response.isSuccess) {
                            showSuccessMessage(isArabic ? "تم الاستلام بنجاح" : "Received Successfully", isArabic ? "تم استلام الشحنة وتحديث المخزون بنجاح." : "Shipment received and inventory updated.");
                            loadTransfers();
                            notifyDashboardUpdate();
                        } else {
                            showErrorMessage(isArabic ? "فشل الاستلام" : "Receive Failed", getErrorMessage(response));
                        }
                    });
                });
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    private String getErrorMessage(ApiResponse response) {
        if (response == null) return isArabic ? "تعذر الاتصال بالسيرفر." : "Could not connect to server.";
        if (response.error != null && !response.error.isEmpty()) return response.error;
        if (response.message != null && !response.message.isEmpty()) return response.message;
        return isArabic ? "حدث خطأ غير متوقع." : "An unexpected error occurred.";
    }

    private void showSuccessMessage(String title, String message) {
        if (!isAdded() || getContext() == null) return;
        new MaterialAlertDialogBuilder(requireContext()).setTitle("✅ " + title).setMessage(message).setPositiveButton(isArabic ? "حسناً" : "OK", null).show();
    }

    private void showErrorMessage(String title, String message) {
        if (!isAdded() || getContext() == null) return;
        new MaterialAlertDialogBuilder(requireContext()).setTitle("❌ " + title).setMessage(message).setPositiveButton(isArabic ? "حسناً" : "OK", null).show();
    }

    private void showUserMessage(String title, String message) {
        if (!isAdded() || getContext() == null) return;
        new MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(message).setPositiveButton(isArabic ? "حسناً" : "OK", null).show();
    }

    private void notifyDashboardUpdate() {
        if (getContext() == null) return;
        Intent intent = new Intent("com.example.uhf.REFRESH_DASHBOARD");
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);
        
        Intent invIntent = new Intent("com.example.uhf.REFRESH_INVENTORY");
        LocalBroadcastManager.getInstance(getContext()).sendBroadcast(invIntent);
    }

    private void showNewTransferDialog() {
        if (!isAdded() || getContext() == null) return;
        View dv = LayoutInflater.from(getContext()).inflate(R.layout.dialog_new_transfer, null);
        TextView tvFromBranch = dv.findViewById(R.id.tvFromBranchName);
        Spinner spTo = dv.findViewById(R.id.spToBranch);
        EditText etNotes = dv.findViewById(R.id.etTransferNotes);
        Button btnCancel = dv.findViewById(R.id.btnCancelTransfer);
        Button btnCreate = dv.findViewById(R.id.btnCreateTransfer);

        tvFromBranch.setText("🏢 " + branchesManager.getActiveBranchDisplayName());
        final int fromBranchId = branchesManager.getActiveBranchId();
        List<String> branchNames = new ArrayList<>();
        List<Integer> branchIds = new ArrayList<>();

        executor.execute(() -> {
            ApiResponse response = apiClient.getBranches();
            mainHandler.post(() -> {
                if (!isAdded() || getContext() == null) return;
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray branches = response.data.optJSONArray("branches");
                    if (branches != null) {
                        for (int i = 0; i < branches.length(); i++) {
                            JSONObject b = branches.optJSONObject(i);
                            if (b != null && b.optInt("id") != fromBranchId) {
                                branchNames.add(b.optString("branch_name_ar", b.optString("branch_name", "Branch")));
                                branchIds.add(b.optInt("id"));
                            }
                        }
                    }
                }
                if (branchNames.isEmpty()) {
                    branchNames.add(ar("لا توجد فروع أخرى متاحة", "No other branches available"));
                    branchIds.add(0);
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, branchNames);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spTo.setAdapter(adapter);
            });
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext()).setView(dv).create();
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        btnCreate.setOnClickListener(v -> {
            int pos = spTo.getSelectedItemPosition();
            if (pos < 0 || pos >= branchIds.size() || branchIds.get(pos) == 0) {
                showUserMessage(isArabic ? "⚠️ خطأ" : "⚠️ Error", isArabic ? "يرجى اختيار فرع وجهة صحيح." : "Please select a valid destination branch.");
                return;
            }
            
            int targetBranchId = branchIds.get(pos);
            String targetBranchName = branchNames.get(pos);
            String notes = etNotes.getText().toString().trim();
            dialog.dismiss();

            TransferData pendingTransfer = new TransferData();
            pendingTransfer.id = 0;
            pendingTransfer.fromBranchId = fromBranchId;
            pendingTransfer.toBranchId = targetBranchId;
            pendingTransfer.fromBranch = branchesManager.getActiveBranchDisplayName();
            pendingTransfer.toBranch = targetBranchName;
            pendingTransfer.notes = notes;
            pendingTransfer.status = "draft";
            pendingTransfer.transferNumber = "TR-NEW";

            showScanItemsDialogForTransfer(pendingTransfer);
        });

        dialog.show();
    }

    /**
     * ✅ استدعاء هذا الميثود مباشرة من أجهزة RFID Hardware عند القراءة عبر الشاشة.
     */
    public void onRfidTagScanned(String epc) {
        if (epc == null || epc.trim().isEmpty()) return;
        final String cleanEpc = epc.trim().toUpperCase();
        
        mainHandler.post(() -> {
            if (!isAdded()) return;
            if (activeScanEpcSet.contains(cleanEpc)) return;
            
            // تحقق من الصلاحيات والوجود من API إن لزم
            verifyAndAddEpcToTransfer(cleanEpc);
        });
    }

    private void verifyAndAddEpcToTransfer(String epc) {
        executor.execute(() -> {
            try {
                JSONObject params = new JSONObject();
                params.put("branch_id", branchesManager.getActiveBranchId());
                ApiResponse response = apiClient.authorize(epc, "transfer", params);
                
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    if (response != null && response.isSuccess && response.data != null) {
                        boolean allowed = response.data.optBoolean("allowed", false);
                        JSONObject itemObj = response.data.optJSONObject("item");
                        
                        if (allowed || itemObj != null) {
                            String title = itemObj != null ? itemObj.optString("title", epc) : epc;
                            activeScanEpcSet.add(epc);
                            playScanBeepSound();
                            if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n✅ " + title);
                            if (tvScanCounter != null) tvScanCounter.setText(String.valueOf(activeScanEpcSet.size()));
                        } else {
                            if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n❌ " + epc + " (غير مسموح)");
                        }
                    } else {
                        // إضافة الـ EPC مباشرة إذا كُنا في وضع 오프라인 أوفلاين
                        activeScanEpcSet.add(epc);
                        playScanBeepSound();
                        if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n✔ " + epc);
                        if (tvScanCounter != null) tvScanCounter.setText(String.valueOf(activeScanEpcSet.size()));
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    activeScanEpcSet.add(epc);
                    playScanBeepSound();
                    if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n✔ " + epc);
                    if (tvScanCounter != null) tvScanCounter.setText(String.valueOf(activeScanEpcSet.size()));
                });
            }
        });
    }

    private void playScanBeepSound() {
        if (toneGenerator != null) {
            try {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
            } catch (Exception ignored) {}
        }
    }

    private void showScanItemsDialogForTransfer(TransferData t) {
        if (t == null || !isAdded() || getContext() == null) return;
        activeScanEpcSet.clear();
        View dv = LayoutInflater.from(getContext()).inflate(R.layout.dialog_scan_transfer, null);

        TextView tvInfo = dv.findViewById(R.id.tvScanTransferInfo);
        EditText etEpc = dv.findViewById(R.id.etScanEpc);
        Button btnScan = dv.findViewById(R.id.btnScanEpc);
        Button btnClear = dv.findViewById(R.id.btnScanClear);
        Button btnSave = dv.findViewById(R.id.btnScanSave);
        activeDialogScannedTv = dv.findViewById(R.id.tvScannedItems);
        tvScanCounter = dv.findViewById(R.id.tvScanCounter);

        if (tvInfo != null) tvInfo.setText(t.transferNumber + " (" + t.fromBranch + " ➔ " + t.toBranch + ")");
        if (tvScanCounter != null) tvScanCounter.setText("0");

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext()).setView(dv).create();
        dialog.setOnDismissListener(d -> {
            activeDialogScannedTv = null;
            tvScanCounter = null;
            activeScanEpcSet.clear();
        });

        btnScan.setOnClickListener(v -> {
            String epc = etEpc.getText().toString().trim().toUpperCase();
            if (epc.isEmpty()) return;
            if (activeScanEpcSet.contains(epc)) {
                Toast.makeText(getContext(), isArabic ? "⚠️ هذه القطعة ممسوحة مسبقاً" : "⚠️ Item already scanned", Toast.LENGTH_SHORT).show();
                etEpc.setText("");
                return;
            }
            verifyAndAddEpcToTransfer(epc);
            etEpc.setText("");
        });

        btnClear.setOnClickListener(v -> {
            activeScanEpcSet.clear();
            if (activeDialogScannedTv != null) activeDialogScannedTv.setText("");
            if (tvScanCounter != null) tvScanCounter.setText("0");
        });

        btnSave.setOnClickListener(v -> {
    if (activeScanEpcSet.isEmpty()) {
        Toast.makeText(getContext(), isArabic ? "⚠️ لا توجد أي قطع صالحة أو متاحة لتحويل ضمن القائمة المحددة" : "⚠️ No valid items selected for transfer", Toast.LENGTH_SHORT).show();
        return;
    }
    
    showLoadingDialog(isArabic ? "جاري إنشاء الشحنة وحفظ القطع..." : "Creating transfer and saving items...");
    executor.execute(() -> {
        try {
            JSONArray itemsArray = new JSONArray();
            for (String epc : activeScanEpcSet) {
                itemsArray.put(epc);
            }
            
            int targetTransferId = t.id;
            
            if (targetTransferId == 0) {
                JSONObject createPayload = new JSONObject();
                createPayload.put("from_branch_id", t.fromBranchId);
                createPayload.put("to_branch_id", t.toBranchId);
                createPayload.put("notes", t.notes != null ? t.notes : "");
                createPayload.put("items", itemsArray);

                ApiResponse createResponse = apiClient.createTransfer(createPayload);
                
                if (createResponse != null && createResponse.isSuccess) {
                    // فحص مرن لجميع الاحتمالات الممكنة للـ ID القادم من السيرفر
                    if (createResponse.data != null) {
                        JSONObject transferObj = createResponse.data.optJSONObject("transfer");
                        if (transferObj != null) {
                            targetTransferId = transferObj.optInt("id", 0);
                        }
                        if (targetTransferId == 0) {
                            targetTransferId = createResponse.data.optInt("id", 0);
                        }
                        if (targetTransferId == 0) {
                            targetTransferId = createResponse.data.optInt("transfer_id", 0);
                        }
                    }
                    
                    // إذا نجح الإنشاء ولكن السيرفر أضاف القطع تلقائياً من الـ payload الأول
                    final int finalId = targetTransferId;
                    mainHandler.post(() -> {
                        dismissLoadingDialog();
                        if (!isAdded() || getContext() == null) return;
                        
                        showSuccessMessage(
                            isArabic ? "تمت العملية بنجاح" : "Success", 
                            isArabic ? "تم إنشاء أمر التحويل والحفظ بنجاح." : "Transfer created successfully."
                        );
                        loadTransfers();
                        notifyDashboardUpdate();
                        dialog.dismiss();
                    });
                    return;

                } else {
                    mainHandler.post(() -> {
                        dismissLoadingDialog();
                        showErrorMessage(isArabic ? "فشل الإنشاء" : "Create Failed", getErrorMessage(createResponse));
                    });
                    return;
                }
            } else {
                // إلحاق قطع لتحويل قائم مسبقاً
                ApiResponse addResponse = apiClient.addItemsToTransfer(targetTransferId, itemsArray);
                if (addResponse != null && addResponse.isSuccess) {
                    mainHandler.post(() -> {
                        dismissLoadingDialog();
                        if (!isAdded() || getContext() == null) return;
                        
                        showSuccessMessage(isArabic ? "تمت الإضافة بنجاح" : "Success", isArabic ? "تم إضافة القطع للتحويل بنجاح." : "Items added successfully.");
                        loadTransfers();
                        notifyDashboardUpdate();
                        dialog.dismiss();
                    });
                } else {
                    mainHandler.post(() -> {
                        dismissLoadingDialog();
                        showErrorMessage(isArabic ? "فشل الحفظ" : "Save Failed", getErrorMessage(addResponse));
                    });
                }
            }

        } catch (Exception e) {
            mainHandler.post(() -> {
                dismissLoadingDialog();
                showErrorMessage(isArabic ? "خطأ" : "Error", e.getMessage());
            });
        }
    });
});


        dialog.show();
    }

    private String getStatusText(String status) {
        if (status == null) return "";
        switch (status) {
            case "draft": return ar("📝 مسودة", "📝 Draft");
            case "pending": return ar("⏳ معلق للتأكيد", "⏳ Pending");
            case "approved": return ar("✅ معتمد ومجهز", "✅ Approved");
            case "in_transit": return ar("🚚 قيد النقل", "🚚 In Transit");
            case "received": return ar("📥 مستلم", "📥 Received");
            case "rejected": 
            case "cancelled": return ar("❌ ملغى", "❌ Cancelled");
            default: return status;
        }
    }

    private String ar(String arabic, String english) { return isArabic ? arabic : english; }

    static class FilterItem {
        String statusValue;
        String label;
        int count;
        int color;

        FilterItem(String statusValue, String label, int count, int color) {
            this.statusValue = statusValue;
            this.label = label;
            this.count = count;
            this.color = color;
        }
    }

    static class TransferData {
        int id, itemCount, fromBranchId, toBranchId;
        String transferNumber, fromBranch, toBranch, status, createdAt, notes;
        double totalWeight;
    }

    interface TransferClickHandler {
        void onClick(TransferData t);
        void onAction(TransferData t, String action);
    }

    class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.VH> {
        final List<TransferData> list;
        final TransferClickHandler handler;
        final boolean isArabic;

        TransferAdapter(List<TransferData> list, TransferClickHandler handler, boolean isArabic) {
            this.list = list;
            this.handler = handler;
            this.isArabic = isArabic;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            TransferData t = list.get(pos);
            h.tvNumber.setText(t.transferNumber != null ? t.transferNumber : "TR-" + t.id);
            h.tvFrom.setText("🏢 " + t.fromBranch);
            h.tvTo.setText("➔ 🏢 " + t.toBranch);
            h.tvDetails.setText(t.itemCount + " " + (isArabic ? "قطعة" : "pcs") + " • " + String.format(Locale.US, "%.1f g", t.totalWeight));
            h.tvDate.setText(t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "");
            
            h.tvStatus.setText(getStatusText(t.status));
            
            int statusColor;
            switch (t.status) {
                case "draft": statusColor = Color.parseColor("#78909C"); break;
                case "pending": statusColor = Color.parseColor("#F57C00"); break;
                case "approved": 
                case "in_transit": statusColor = Color.parseColor("#1976D2"); break;
                case "received": statusColor = Color.parseColor("#388E3C"); break;
                default: statusColor = Color.parseColor("#78909C"); break;
            }
            h.tvStatus.setTextColor(statusColor);

            h.itemView.setOnClickListener(v -> handler.onClick(t));

            if ("draft".equals(t.status)) {
                h.btnAction1.setVisibility(canCreateTransfer() ? View.VISIBLE : View.GONE);
                h.btnAction1.setText(isArabic ? "📷 إضافة" : "📷 Add");
                h.btnAction1.setOnClickListener(v -> handler.onAction(t, "scan"));
                h.btnAction2.setVisibility(View.VISIBLE);
                h.btnAction2.setText(isArabic ? "📦 القطع" : "📦 Items");
                h.btnAction2.setOnClickListener(v -> handler.onAction(t, "items"));
            } else if ("pending".equals(t.status)) {
                h.btnAction1.setVisibility(canApproveTransfer() ? View.VISIBLE : View.GONE);
                h.btnAction1.setText(isArabic ? "✅ اعتماد" : "✅ Approve");
                h.btnAction1.setOnClickListener(v -> handler.onAction(t, "approve"));
                h.btnAction2.setVisibility(View.VISIBLE);
                h.btnAction2.setText(isArabic ? "📦 القطع" : "📦 Items");
                h.btnAction2.setOnClickListener(v -> handler.onAction(t, "items"));
            } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
                h.btnAction1.setVisibility(canReceiveTransfer(t) ? View.VISIBLE : View.GONE);
                h.btnAction1.setText(isArabic ? "📥 استلام" : "📥 Receive");
                h.btnAction1.setOnClickListener(v -> handler.onAction(t, "receive"));
                h.btnAction2.setVisibility(View.VISIBLE);
                h.btnAction2.setText(isArabic ? "📦 القطع" : "📦 Items");
                h.btnAction2.setOnClickListener(v -> handler.onAction(t, "items"));
            } else {
                h.btnAction1.setVisibility(View.GONE);
                h.btnAction2.setVisibility(View.VISIBLE);
                h.btnAction2.setText(isArabic ? "📦 القطع" : "📦 Items");
                h.btnAction2.setOnClickListener(v -> handler.onAction(t, "items"));
            }
        }

        @Override
        public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvNumber, tvStatus, tvFrom, tvTo, tvDetails, tvDate;
            Button btnAction1, btnAction2;
            VH(View v) {
                super(v);
                tvNumber = v.findViewById(R.id.tvTransferNumber);
                tvStatus = v.findViewById(R.id.tvTransferStatus);
                tvFrom = v.findViewById(R.id.tvFromBranch);
                tvTo = v.findViewById(R.id.tvToBranch);
                tvDetails = v.findViewById(R.id.tvTransferDetails);
                tvDate = v.findViewById(R.id.tvTransferDate);
                btnAction1 = v.findViewById(R.id.btnTransferAction1);
                btnAction2 = v.findViewById(R.id.btnTransferAction2);
            }
        }
    }
}
