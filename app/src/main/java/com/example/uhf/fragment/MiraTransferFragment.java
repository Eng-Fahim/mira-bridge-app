package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraTransferFragment extends Fragment {

    private static final String TAG = "MiraTransfer";
    private static final String OFFLINE_PREFS = "transfers_cache";

    private RecyclerView rvTransfers;
    private TextView tvEmpty, tvActiveBranch;
    private Button btnNewTransfer, btnScanVerify;
    private Button btnFilterAll, btnFilterDraft, btnFilterPending, btnFilterInTransit, btnFilterCompleted;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final List<TransferData> transferList = new ArrayList<>();
    private TransferAdapter adapter;
    private String currentFilter = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isArabic;
    private SharedPreferences offlineCache;
    private TransferData selectedTransfer = null;

    private final Set<String> activeScanEpcSet = new HashSet<>();
    private TextView activeDialogScannedTv;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transfer_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isArabic = Locale.getDefault().getLanguage().equals("ar");
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        offlineCache = requireContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);

        initViews(view);
        setupListeners();

        rvTransfers.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransferAdapter(transferList, new TransferClickHandler() {
            @Override public void onClick(TransferData t) { showTransferActions(t); }
            @Override public void onAction(TransferData t, String action) { handleTransferAction(t, action); }
        });
        rvTransfers.setAdapter(adapter);

        loadTransfers();
        loadOfflineTransfers();
    }

    private void initViews(View view) {
        rvTransfers = view.findViewById(R.id.rvTransfers);
        tvEmpty = view.findViewById(R.id.tvEmptyTransfers);
        tvActiveBranch = view.findViewById(R.id.tvTransferCount);
        btnNewTransfer = view.findViewById(R.id.btnNewTransfer);
        btnScanVerify = view.findViewById(R.id.btnScanItems);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterDraft = view.findViewById(R.id.btnFilterDraft);
        btnFilterPending = view.findViewById(R.id.btnFilterPending);
        btnFilterInTransit = view.findViewById(R.id.btnFilterInTransit);
        btnFilterCompleted = view.findViewById(R.id.btnFilterCompleted);
    }

    private void setupListeners() {
        btnNewTransfer.setOnClickListener(v -> showNewTransferDialog());
        btnScanVerify.setOnClickListener(v -> {
            if (selectedTransfer != null) {
                showScanItemsDialogForTransfer(selectedTransfer);
            } else if (!transferList.isEmpty()) {
                showTransferPickerForScan();
            } else {
                Toast.makeText(getContext(), ar("لا توجد تحويلات نشطة للمسح", "No active transfers to scan"), Toast.LENGTH_SHORT).show();
            }
        });
        btnFilterAll.setOnClickListener(v -> filterBy(""));
        btnFilterDraft.setOnClickListener(v -> filterBy("draft"));
        btnFilterPending.setOnClickListener(v -> filterBy("pending"));
        btnFilterInTransit.setOnClickListener(v -> filterBy("in_transit"));
        btnFilterCompleted.setOnClickListener(v -> filterBy("received"));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (tvActiveBranch != null) tvActiveBranch.setText(branchesManager.getActiveBranchDisplayName());
    }

    // ==================== DATA ====================

    private void loadTransfers() {
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getTransfers(branchId, currentFilter);
            mainHandler.post(() -> {
                transferList.clear();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray transfers = response.data.optJSONArray("transfers");
                    if (transfers != null) {
                        for (int i = 0; i < transfers.length(); i++) {
                            TransferData d = parseTransferData(transfers.optJSONObject(i));
                            if (d != null) transferList.add(d);
                        }
                    }
                    saveTransfersOffline(transfers);
                }
                adapter.notifyDataSetChanged();
                updateEmptyState();
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
        boolean empty = transferList.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvTransfers.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ==================== OFFLINE ====================

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
            for (int i = 0; i < transfers.length(); i++) {
                TransferData d = parseTransferData(transfers.optJSONObject(i));
                if (d != null) transferList.add(d);
            }
            adapter.notifyDataSetChanged();
            updateEmptyState();
        } catch (Exception ignored) {}
    }

    // ==================== FILTER ====================

    private void filterBy(String filter) { currentFilter = filter; updateFilterUI(filter); loadTransfers(); }

    private void updateFilterUI(String active) {
        btnFilterAll.setAlpha(active.isEmpty() ? 1.0f : 0.4f);
        btnFilterDraft.setAlpha("draft".equals(active) ? 1.0f : 0.4f);
        btnFilterPending.setAlpha("pending".equals(active) ? 1.0f : 0.4f);
        btnFilterInTransit.setAlpha("in_transit".equals(active) ? 1.0f : 0.4f);
        btnFilterCompleted.setAlpha("received".equals(active) ? 1.0f : 0.4f);
    }

    // ==================== ACTIONS ====================

    private void handleTransferAction(TransferData t, String action) {
        selectedTransfer = t;
        switch (action) {
            case "approve": approveTransfer(t); break;
            case "receive": receiveTransfer(t); break;
            case "scan": showScanItemsDialogForTransfer(t); break;
        }
    }

    private void showTransferActions(TransferData t) {
        selectedTransfer = t;
        List<String> options = new ArrayList<>();
        if ("draft".equals(t.status)) {
            options.add(ar("📷 إضافة قطع للتحويل", "📷 Add Items"));
            options.add(ar("🗑️ حذف المسودة", "🗑️ Delete Draft"));
        } else if ("pending".equals(t.status)) {
            options.add(ar("✅ اعتماد التحويل", "✅ Approve"));
            options.add(ar("📷 مسح القطع", "📷 Scan Items"));
        } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
            options.add(ar("📦 استلام الشحنة", "📦 Receive"));
            options.add(ar("📷 تدقيق المسح", "📷 Scan Audit"));
        } else {
            options.add(ar("📋 عرض التفاصيل", "📋 View Details"));
        }
        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
            .setTitle((t.transferNumber != null ? t.transferNumber : "TR-" + t.id) + " | " + t.fromBranch + " → " + t.toBranch)
            .setItems(items, (dialog, which) -> {
                String sel = items[which];
                if (sel.contains("إضافة") || sel.contains("Add")) showScanItemsDialogForTransfer(t);
                else if (sel.contains("اعتماد") || sel.contains("Approve")) approveTransfer(t);
                else if (sel.contains("استلام") || sel.contains("Receive")) receiveTransfer(t);
                else if (sel.contains("مسح") || sel.contains("Scan")) showScanItemsDialogForTransfer(t);
                else if (sel.contains("حذف") || sel.contains("Delete")) deleteTransfer(t);
                else if (sel.contains("تفاصيل") || sel.contains("Details")) showTransferDetails(t);
            })
            .setNegativeButton(ar("إغلاق", "Close"), null).show();
    }

    private void showTransferDetails(TransferData t) {
        String details = ar(
            "رقم التحويل: " + (t.transferNumber != null ? t.transferNumber : "TR-" + t.id) + "\n" +
            "من: " + t.fromBranch + "\nإلى: " + t.toBranch + "\n" +
            "الحالة: " + getStatusText(t.status) + "\n" +
            "عدد القطع: " + t.itemCount + "\n" +
            "الوزن: " + String.format(Locale.US, "%.2fg", t.totalWeight) + "\n" +
            "تاريخ الإنشاء: " + (t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "-") +
            (t.notes != null && !t.notes.isEmpty() ? "\nملاحظات: " + t.notes : ""),
            "Transfer: " + (t.transferNumber != null ? t.transferNumber : "TR-" + t.id) + "\n" +
            "From: " + t.fromBranch + "\nTo: " + t.toBranch + "\n" +
            "Status: " + t.status + "\nItems: " + t.itemCount + "\n" +
            "Weight: " + String.format(Locale.US, "%.2fg", t.totalWeight) + "\n" +
            "Created: " + (t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "-") +
            (t.notes != null && !t.notes.isEmpty() ? "\nNotes: " + t.notes : ""));
        new AlertDialog.Builder(requireContext())
            .setTitle(ar("تفاصيل التحويل", "Transfer Details"))
            .setMessage(details).setPositiveButton(ar("حسناً", "OK"), null).show();
    }

    private void showTransferPickerForScan() {
        List<String> titles = new ArrayList<>();
        List<TransferData> activeList = new ArrayList<>();
        for (TransferData t : transferList) {
            if ("draft".equals(t.status) || "pending".equals(t.status) || "approved".equals(t.status) || "in_transit".equals(t.status)) {
                titles.add((t.transferNumber != null ? t.transferNumber : "TR-" + t.id) + " | " + t.fromBranch + " → " + t.toBranch);
                activeList.add(t);
            }
        }
        if (activeList.isEmpty()) {
            Toast.makeText(getContext(), ar("لا توجد تحويلات نشطة", "No active transfers"), Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
            .setTitle(ar("اختر تحويلاً للمسح", "Select Transfer to Scan"))
            .setItems(titles.toArray(new String[0]), (d, w) -> {
                selectedTransfer = activeList.get(w);
                showScanItemsDialogForTransfer(selectedTransfer);
            }).show();
    }

    private void approveTransfer(TransferData t) {
        new AlertDialog.Builder(requireContext())
            .setTitle(ar("اعتماد التحويل", "Approve Transfer"))
            .setMessage(ar("هل أنت متأكد من اعتماد هذا التحويل؟", "Confirm approval?"))
            .setPositiveButton(ar("اعتماد", "Approve"), (d, w) -> {
                executor.execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.approveTransfer(t.id);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم الاعتماد", "✅ Approved"), Toast.LENGTH_SHORT).show();
                            t.status = "approved"; adapter.notifyDataSetChanged(); loadTransfers();
                        } else {
                            Toast.makeText(getContext(), ar("❌ فشل", "❌ Failed"), Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            }).setNegativeButton(ar("إلغاء", "Cancel"), null).show();
    }

    private void receiveTransfer(TransferData t) {
        new AlertDialog.Builder(requireContext())
            .setTitle(ar("استلام التحويل", "Receive Transfer"))
            .setMessage(ar("هل أنت متأكد من استلام جميع القطع؟", "Confirm receiving all items?"))
            .setPositiveButton(ar("استلام", "Receive"), (d, w) -> {
                executor.execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.receiveTransfer(t.id);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم الاستلام", "✅ Received"), Toast.LENGTH_SHORT).show();
                            t.status = "received"; adapter.notifyDataSetChanged(); loadTransfers();
                        } else {
                            Toast.makeText(getContext(), ar("❌ فشل", "❌ Failed"), Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            }).setNegativeButton(ar("إلغاء", "Cancel"), null).show();
    }

    private void deleteTransfer(TransferData t) {
        new AlertDialog.Builder(requireContext())
            .setTitle(ar("حذف التحويل", "Delete Transfer"))
            .setMessage(ar("هل أنت متأكد من حذف هذا التحويل؟", "Confirm deletion?"))
            .setPositiveButton(ar("حذف", "Delete"), (d, w) -> {
                executor.execute(() -> {
                    JSONObject payload = new JSONObject();
                    try { payload.put("status", "cancelled"); } catch (Exception ignored) {}
                    MiraApiClient.ApiResponse response = apiClient.updateTransfer(t.id, payload);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم الحذف", "✅ Deleted"), Toast.LENGTH_SHORT).show();
                            loadTransfers();
                        } else {
                            Toast.makeText(getContext(), ar("❌ فشل", "❌ Failed"), Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            }).setNegativeButton(ar("إلغاء", "Cancel"), null).show();
    }

    // ==================== NEW TRANSFER ====================

    private void showNewTransferDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dv = LayoutInflater.from(getContext()).inflate(R.layout.dialog_new_transfer, null);
        builder.setView(dv);
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
            MiraApiClient.ApiResponse response = apiClient.getBranches();
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray branches = response.data.optJSONArray("branches");
                    if (branches != null) {
                        for (int i = 0; i < branches.length(); i++) {
                            JSONObject b = branches.optJSONObject(i);
                            if (b != null) {
                                int bid = b.optInt("id");
                                if (bid != fromBranchId) {
                                    branchNames.add(b.optString("branch_name_ar", b.optString("branch_name", "Branch " + bid)));
                                    branchIds.add(bid);
                                }
                            }
                        }
                    }
                }
                if (branchNames.isEmpty()) { branchNames.add(ar("لا توجد فروع أخرى", "No other branches")); branchIds.add(0); }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, branchNames);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spTo.setAdapter(adapter);
            });
        });

        AlertDialog dialog = builder.create();
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnCreate.setOnClickListener(v -> {
            int pos = spTo.getSelectedItemPosition();
            if (pos < 0 || pos >= branchIds.size() || branchIds.get(pos) == 0) {
                Toast.makeText(getContext(), ar("اختر فرعاً صحيحاً", "Select valid branch"), Toast.LENGTH_SHORT).show();
                return;
            }
            int toBranchId = branchIds.get(pos);
            String notes = etNotes.getText().toString().trim();
            executor.execute(() -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("from_branch_id", fromBranchId);
                    payload.put("to_branch_id", toBranchId);
                    payload.put("notes", notes);
                    payload.put("items", new JSONArray());
                    MiraApiClient.ApiResponse response = apiClient.createTransfer(payload);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم إنشاء التحويل", "✅ Transfer created"), Toast.LENGTH_SHORT).show();
                            loadTransfers();
                        } else {
                            String err = response != null ? response.error : ar("فشل", "Failed");
                            Toast.makeText(getContext(), ar("❌ " + err, "❌ " + err), Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
            dialog.dismiss();
        });
        dialog.show();
    }

    // ==================== RFID SCAN ====================

    public void onRfidTagScanned(String epc) {
        if (epc == null || epc.trim().isEmpty()) return;
        final String cleanEpc = epc.trim().toUpperCase();
        mainHandler.post(() -> {
            if (activeScanEpcSet.contains(cleanEpc)) return;
            activeScanEpcSet.add(cleanEpc);
            playScanBeepSound();
            if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n✔ " + cleanEpc);
        });
    }

    private void playScanBeepSound() {
        try { ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100); toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150); } catch (Exception ignored) {}
    }

    private void showScanItemsDialogForTransfer(TransferData t) {
        if (t == null) { Toast.makeText(getContext(), ar("حدد تحويلاً أولاً", "Select a transfer first"), Toast.LENGTH_SHORT).show(); return; }
        activeScanEpcSet.clear();
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dv = LayoutInflater.from(getContext()).inflate(R.layout.dialog_scan_transfer, null);
        builder.setView(dv);
        TextView tvInfo = dv.findViewById(R.id.tvScanTransferInfo);
        EditText etEpc = dv.findViewById(R.id.etScanEpc);
        Button btnScan = dv.findViewById(R.id.btnScanEpc);
        Button btnClear = dv.findViewById(R.id.btnScanClear);
        Button btnSave = dv.findViewById(R.id.btnScanSave);
        activeDialogScannedTv = dv.findViewById(R.id.tvScannedItems);
        if (tvInfo != null) tvInfo.setText((t.transferNumber != null ? t.transferNumber : "TR-" + t.id) + " | " + t.fromBranch + " → " + t.toBranch);

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> { activeDialogScannedTv = null; activeScanEpcSet.clear(); });

        btnScan.setOnClickListener(v -> {
            String epc = etEpc.getText().toString().trim().toUpperCase();
            if (epc.isEmpty()) return;
            if (activeScanEpcSet.contains(epc)) { Toast.makeText(getContext(), ar("ممسوحة مسبقاً", "Already scanned"), Toast.LENGTH_SHORT).show(); etEpc.setText(""); return; }
            executor.execute(() -> {
                try {
                    JSONObject params = new JSONObject();
                    params.put("branch_id", branchesManager.getActiveBranchId());
                    MiraApiClient.ApiResponse response = apiClient.authorize(epc, "transfer", params);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess && response.data != null) {
                            JSONObject item = response.data.optJSONObject("item");
                            JSONObject decision = response.data.optJSONObject("decision");
                            if (item != null && decision != null) {
                                boolean allowed = decision.optBoolean("allowed", false);
                                String status = item.optString("status", "");
                                String title = item.optString("title", epc);
                                if (allowed && !"sold".equals(status)) {
                                    activeScanEpcSet.add(epc);
                                    if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n✅ " + title + " [" + epc + "]");
                                } else if ("sold".equals(status)) {
                                    if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n❌ مباعة: " + title + " [" + epc + "]");
                                    Toast.makeText(getContext(), ar("❌ هذه القطعة مباعة", "❌ Item is sold"), Toast.LENGTH_SHORT).show();
                                } else {
                                    if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n⚠️ غير متاحة: " + title + " [" + epc + "]");
                                    Toast.makeText(getContext(), ar("⚠️ قطعة غير متاحة للتحويل", "⚠️ Item not available"), Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n❓ غير مسجلة: [" + epc + "]");
                                Toast.makeText(getContext(), ar("❓ قطعة غير مسجلة", "❓ Item not registered"), Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            if (activeDialogScannedTv != null) activeDialogScannedTv.append("\n⚠️ فشل التحقق: [" + epc + "]");
                            Toast.makeText(getContext(), ar("⚠️ فشل التحقق", "⚠️ Verification failed"), Toast.LENGTH_SHORT).show();
                        }
                        etEpc.setText("");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> { Toast.makeText(getContext(), ar("❌ خطأ", "❌ Error"), Toast.LENGTH_SHORT).show(); etEpc.setText(""); });
                }
            });
        });

        btnClear.setOnClickListener(v -> {
            activeScanEpcSet.clear();
            if (activeDialogScannedTv != null) activeDialogScannedTv.setText(ar("القطع الممسوحة ستظهر هنا...", "Scanned items will appear here..."));
        });

        btnSave.setOnClickListener(v -> {
            if (activeScanEpcSet.isEmpty()) { Toast.makeText(getContext(), ar("لا توجد قطع للمسح", "No items to save"), Toast.LENGTH_SHORT).show(); return; }
            new AlertDialog.Builder(requireContext())
                .setTitle(ar("حفظ القطع", "Save Items"))
                .setMessage(ar("سيتم إضافة " + activeScanEpcSet.size() + " قطعة إلى التحويل.", "Will add " + activeScanEpcSet.size() + " items to transfer."))
                .setPositiveButton(ar("حفظ", "Save"), (d, w) -> {
                    executor.execute(() -> {
                        try {
                            JSONArray items = new JSONArray();
                            for (String epc : activeScanEpcSet) items.put(epc);
                            MiraApiClient.ApiResponse response = apiClient.addItemsToTransfer(t.id, items);
                            mainHandler.post(() -> {
                                if (response != null && response.isSuccess) {
                                    Toast.makeText(getContext(), ar("✅ تمت إضافة " + activeScanEpcSet.size() + " قطعة", "✅ Added " + activeScanEpcSet.size() + " items"), Toast.LENGTH_SHORT).show();
                                    loadTransfers(); dialog.dismiss();
                                } else {
                                    String errorMsg = ar("❌ فشل الحفظ", "❌ Save failed");
                                    if (response != null) {
                                        if (response.error != null && !response.error.isEmpty()) errorMsg += ": " + response.error;
                                        else errorMsg += " (Code: " + response.code + ")";
                                    }
                                    Toast.makeText(getContext(), errorMsg, Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> Toast.makeText(getContext(), ar("❌ خطأ: " + e.getMessage(), "❌ Error: " + e.getMessage()), Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(ar("إلغاء", "Cancel"), null).show();
        });

        dialog.show();
    }

    // ==================== HELPERS ====================

    private String getStatusText(String status) {
        switch (status) {
            case "draft": return ar("مسودة", "Draft");
            case "pending": return ar("معلق", "Pending");
            case "approved": return ar("معتمد", "Approved");
            case "in_transit": return ar("بالطريق", "In Transit");
            case "received": return ar("مستلم", "Received");
            case "rejected": return ar("مرفوض", "Rejected");
            default: return status;
        }
    }

    private String ar(String arabic, String english) { return isArabic ? arabic : english; }

    // ==================== MODELS & ADAPTER ====================

    static class TransferData {
        int id, itemCount, fromBranchId, toBranchId;
        String transferNumber, fromBranch, toBranch, status, createdAt, notes;
        double totalWeight;
    }

    interface TransferClickHandler { void onClick(TransferData t); void onAction(TransferData t, String action); }

    class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.VH> {
        final List<TransferData> list; final TransferClickHandler handler;
        TransferAdapter(List<TransferData> list, TransferClickHandler handler) { this.list = list; this.handler = handler; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer_row, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            TransferData t = list.get(pos);
            h.tvNumber.setText(t.transferNumber != null ? t.transferNumber : "TR-" + t.id);
            h.tvFrom.setText("🏢 " + t.fromBranch); h.tvTo.setText("🏢 " + t.toBranch);
            h.tvDetails.setText(t.itemCount + " " + ar("قطعة", "pcs") + " • " + String.format(Locale.US, "%.1fg", t.totalWeight));
            h.tvDate.setText(t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "");
            h.tvStatus.setText(getStatusText(t.status));
            h.itemView.setOnClickListener(v -> handler.onClick(t));
            if ("draft".equals(t.status)) {
                h.btnAction1.setVisibility(View.VISIBLE); h.btnAction1.setText(ar("📷 إضافة", "📷 Add")); h.btnAction1.setOnClickListener(v -> handler.onAction(t, "scan")); h.btnAction2.setVisibility(View.GONE);
            } else if ("pending".equals(t.status)) {
                h.btnAction1.setVisibility(View.VISIBLE); h.btnAction1.setText(ar("✅ اعتماد", "✅ Approve")); h.btnAction1.setOnClickListener(v -> handler.onAction(t, "approve"));
                h.btnAction2.setVisibility(View.VISIBLE); h.btnAction2.setText(ar("📷 مسح", "📷 Scan")); h.btnAction2.setOnClickListener(v -> handler.onAction(t, "scan"));
            } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
                h.btnAction1.setVisibility(View.VISIBLE); h.btnAction1.setText(ar("📦 استلام", "📦 Receive")); h.btnAction1.setOnClickListener(v -> handler.onAction(t, "receive"));
                h.btnAction2.setVisibility(View.VISIBLE); h.btnAction2.setText(ar("📷 مسح", "📷 Scan")); h.btnAction2.setOnClickListener(v -> handler.onAction(t, "scan"));
            } else { h.btnAction1.setVisibility(View.GONE); h.btnAction2.setVisibility(View.GONE); }
        }
        @Override public int getItemCount() { return list.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvNumber, tvStatus, tvFrom, tvTo, tvDetails, tvDate; Button btnAction1, btnAction2;
            VH(View v) {
                super(v);
                tvNumber = v.findViewById(R.id.tvTransferNumber); tvStatus = v.findViewById(R.id.tvTransferStatus);
                tvFrom = v.findViewById(R.id.tvFromBranch); tvTo = v.findViewById(R.id.tvToBranch);
                tvDetails = v.findViewById(R.id.tvTransferDetails); tvDate = v.findViewById(R.id.tvTransferDate);
                btnAction1 = v.findViewById(R.id.btnTransferAction1); btnAction2 = v.findViewById(R.id.btnTransferAction2);
            }
        }
    }
}
