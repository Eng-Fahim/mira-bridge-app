package com.example.uhf.fragment;

import android.app.AlertDialog;
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
import java.util.concurrent.Executors;

public class MiraTransferFragment extends Fragment {

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
    private boolean isArabic;

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

        initViews(view);
        setupListeners();
        
        rvTransfers.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransferAdapter(transferList, new TransferClickHandler() {
            @Override
            public void onClick(TransferData t) { showTransferActions(t); }
            @Override
            public void onAction(TransferData t, String action) { handleTransferAction(t, action); }
        });
        rvTransfers.setAdapter(adapter);

        loadTransfers();
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
        btnScanVerify.setOnClickListener(v -> Toast.makeText(getContext(), ar("حدد أمر تحويل لبدء التدقيق الميداني", "Select a transfer order to verify"), Toast.LENGTH_SHORT).show());
        
        btnFilterAll.setOnClickListener(v -> filterBy(""));
        btnFilterDraft.setOnClickListener(v -> filterBy("draft"));
        btnFilterPending.setOnClickListener(v -> filterBy("pending"));
        btnFilterInTransit.setOnClickListener(v -> filterBy("in_transit"));
        btnFilterCompleted.setOnClickListener(v -> filterBy("received"));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (tvActiveBranch != null) {
            tvActiveBranch.setText(branchesManager.getActiveBranchDisplayName());
        }
    }

    private void loadTransfers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getTransfers(branchId, currentFilter);

            mainHandler.post(() -> {
                transferList.clear();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray transfers = response.data.optJSONArray("transfers");
                    if (transfers != null) {
                        for (int i = 0; i < transfers.length(); i++) {
                            JSONObject t = transfers.optJSONObject(i);
                            if (t == null) continue;
                            TransferData d = new TransferData();
                            d.id = t.optInt("id");
                            d.transferNumber = t.optString("transfer_number");
                            d.fromBranch = t.optString("from_branch_name_ar", t.optString("from_branch_name"));
                            d.toBranch = t.optString("to_branch_name_ar", t.optString("to_branch_name"));
                            d.fromBranchId = t.optInt("from_branch_id");
                            d.toBranchId = t.optInt("to_branch_id");
                            d.status = t.optString("status");
                            d.itemCount = t.optInt("item_count");
                            d.totalWeight = t.optDouble("total_weight", 0.0);
                            d.notes = t.optString("notes");
                            d.createdAt = t.optString("created_at");
                            transferList.add(d);
                        }
                    }
                }
                adapter.notifyDataSetChanged();
                boolean empty = transferList.isEmpty();
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rvTransfers.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void filterBy(String filter) {
        currentFilter = filter;
        updateFilterUI(filter);
        loadTransfers();
    }

    private void updateFilterUI(String active) {
        btnFilterAll.setAlpha(active.isEmpty() ? 1.0f : 0.4f);
        btnFilterDraft.setAlpha("draft".equals(active) ? 1.0f : 0.4f);
        btnFilterPending.setAlpha("pending".equals(active) ? 1.0f : 0.4f);
        btnFilterInTransit.setAlpha("in_transit".equals(active) ? 1.0f : 0.4f);
        btnFilterCompleted.setAlpha("received".equals(active) ? 1.0f : 0.4f);
    }

    private void handleTransferAction(TransferData t, String action) {
        switch (action) {
            case "approve": approveTransfer(t); break;
            case "receive": showAuditAndReceiveDialog(t); break;
            case "scan": showScanItemsDialogForTransfer(t); break;
        }
    }

    private void showTransferActions(TransferData t) {
        List<String> options = new ArrayList<>();
        if ("pending".equals(t.status)) {
            options.add(ar("اعتماد وسحب المعروضات", "Approve & Transit"));
            options.add(ar("مسح وتدقيق العهدة", "Scan & Verify"));
        } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
            options.add(ar("فحص واستلام العهدة", "Audit & Receive"));
            options.add(ar("مسح مطابقة الشحنة", "Scan Verification"));
        }
        options.add(ar("عرض السجل والأرشفة", "View Audit Trail"));

        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
            .setTitle(t.transferNumber + " | " + t.fromBranch + " ➔ " + t.toBranch)
            .setItems(items, (dialog, which) -> {
                String selected = items[which];
                if (selected.contains("اعتماد") || selected.contains("Approve")) approveTransfer(t);
                else if (selected.contains("استلام") || selected.contains("Receive")) showAuditAndReceiveDialog(t);
                else if (selected.contains("مسح") || selected.contains("Scan")) showScanItemsDialogForTransfer(t);
            })
            .setNegativeButton(ar("إغلاق", "Close"), null)
            .show();
    }

    private void approveTransfer(TransferData t) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.approveTransfer(t.id);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), ar("✅ تم اعتماد التحويل وتجميد العهدة للمسار", "✅ Approved & In Transit"), Toast.LENGTH_SHORT).show();
                    loadTransfers();
                } else {
                    Toast.makeText(getContext(), ar("❌ فشل عملية الاعتماد", "❌ Approval Failed"), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void showAuditAndReceiveDialog(TransferData t) {
        new AlertDialog.Builder(requireContext())
            .setTitle(ar("تأكيد مطابقة العهدة والاستلام", "Audit & Confirm Receipt"))
            .setMessage(ar("هل قمت بمسح ومطابقة جميع القطع الفيزيائية مع البيان البرمجي؟", "Have you scanned and matched all physical items against manifest?"))
            .setPositiveButton(ar("إتمام الاستلام والترحيل", "Finalize Receipt"), (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.receiveTransfer(t.id);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم نقل الملكية وتحديث أرصدة الفروع", "✅ Inventory Successfully Transferred"), Toast.LENGTH_SHORT).show();
                            loadTransfers();
                        } else {
                            Toast.makeText(getContext(), ar("❌ خطأ في نقل الأرصدة", "❌ Transfer Execution Error"), Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            })
            .setNegativeButton(ar("إلغاء", "Cancel"), null)
            .show();
    }

    private void showNewTransferDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dv = LayoutInflater.from(getContext()).inflate(R.layout.dialog_new_transfer, null);
        builder.setView(dv);

        TextView tvFromBranch = dv.findViewById(R.id.tvFromBranchName);
        Spinner spTo = dv.findViewById(R.id.spToBranch);
        EditText etNotes = dv.findViewById(R.id.etTransferNotes);
        Button btnCancel = dv.findViewById(R.id.btnCancelTransfer);
        Button btnCreate = dv.findViewById(R.id.btnCreateTransfer);

        tvFromBranch.setText(branchesManager.getActiveBranchDisplayName());
        final int fromBranchId = branchesManager.getActiveBranchId();

        List<String> branchNames = new ArrayList<>();
        List<Integer> branchIds = new ArrayList<>();

        Executors.newSingleThreadExecutor().execute(() -> {
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
                                    branchNames.add(b.optString("branch_name_ar", b.optString("branch_name")));
                                    branchIds.add(bid);
                                }
                            }
                        }
                    }
                }
                if (branchNames.isEmpty()) {
                    branchNames.add(ar("لا توجد فروع مستهدفة", "No target branches"));
                    branchIds.add(0);
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_item, branchNames);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spTo.setAdapter(adapter);
            });
        });

        AlertDialog dialog = builder.create();
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnCreate.setOnClickListener(v -> {
            int pos = spTo.getSelectedItemPosition();
            if (pos < 0 || pos >= branchIds.size() || branchIds.get(pos) == 0) {
                Toast.makeText(getContext(), ar("يرجى تحديد فرع صحيح", "Select valid destination"), Toast.LENGTH_SHORT).show();
                return;
            }
            int toBranchId = branchIds.get(pos);
            String notes = etNotes.getText().toString().trim();

            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("from_branch_id", fromBranchId);
                    payload.put("to_branch_id", toBranchId);
                    payload.put("notes", notes);

                    MiraApiClient.ApiResponse response = apiClient.createTransfer(payload);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم إنشاء أمر التحويل بنجاح", "✅ Draft Created Successfully"), Toast.LENGTH_SHORT).show();
                            loadTransfers();
                        } else {
                            Toast.makeText(getContext(), ar("❌ فشل إنشاء الطلب", "❌ Creation Failed"), Toast.LENGTH_SHORT).show();
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
// مجموعة لحفظ الأكواد الممسوحة لحظياً ومنع التكرار
private final Set<String> scannedEpcSet = new HashSet<>();

/**
 * يتم استدعاء هذه الدالة عند التقاط أي Tag بواسطة جهاز الـ RFID المحمول
 */
public void onRfidTagScanned(String epc) {
    if (epc == null || epc.trim().isEmpty()) return;
    
    final String cleanEpc = epc.trim().toUpperCase();

    mainHandler.post(() -> {
        // التحقق من عدم تكرار القطعة في الجلسة الحالية
        if (scannedEpcSet.contains(cleanEpc)) {
            return; 
        }

        scannedEpcSet.add(cleanEpc);
        
        // إصدار صوت تنبيه (Beep) لإشعارات المستخدم بنجاح القراءة
        playScanBeepSound();

        // تحديث الواجهة فوراً
        if (tvScannedCount != null) {
            tvScannedCount.setText(String.format(Locale.getDefault(), "%s: %d", 
                ar("القطع الممسوحة", "Scanned Items"), scannedEpcSet.size()));
        }

        if (scannedItemsAdapter != null) {
            scannedItemsAdapter.addItem(cleanEpc);
        }
    });
}

private void playScanBeepSound() {
    try {
        android.media.ToneGenerator toneGen = new android.media.ToneGenerator(
            android.media.AudioManager.STREAM_MUSIC, 100);
        toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150);
    } catch (Exception ignored) {}
}

    
    private void showScanItemsDialogForTransfer(TransferData t) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dv = LayoutInflater.from(getContext()).inflate(R.layout.dialog_scan_transfer, null);
        builder.setView(dv);

        EditText etEpc = dv.findViewById(R.id.etScanEpc);
        Button btnScan = dv.findViewById(R.id.btnScanEpc);
        TextView tvScannedItems = dv.findViewById(R.id.tvScannedItems);
        Set<String> scannedSet = new HashSet<>();

        AlertDialog dialog = builder.create();

        btnScan.setOnClickListener(v -> {
            String epc = etEpc.getText().toString().trim().toUpperCase();
            if (epc.isEmpty()) return;

            if (scannedSet.contains(epc)) {
                Toast.makeText(getContext(), ar("القطعة ممسوحة مسبقاً", "Item already scanned"), Toast.LENGTH_SHORT).show();
                etEpc.setText("");
                return;
            }

            Executors.newSingleThreadExecutor().execute(() -> {
                MiraApiClient.ApiResponse response = apiClient.scanTransferItem(t.id, epc);
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        scannedSet.add(epc);
                        boolean found = response.data != null && response.data.optBoolean("found", false);
                        String title = response.data != null ? response.data.optString("title", "Item") : "Item";
                        
                        if (found) {
                            tvScannedItems.append("\n✔ " + title + " [" + epc + "]");
                        } else {
                            tvScannedItems.append("\n⚠ " + ar("قطعة غير مدرجة", "Unregistered") + " [" + epc + "]");
                        }
                        etEpc.setText("");
                    }
                });
            });
        });

        dialog.show();
    }

    private String ar(String arabic, String english) {
        return isArabic ? arabic : english;
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

        TransferAdapter(List<TransferData> list, TransferClickHandler handler) {
            this.list = list; 
            this.handler = handler;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            TransferData t = list.get(pos);
            h.tvNumber.setText(t.transferNumber);
            h.tvFrom.setText(t.fromBranch);
            h.tvTo.setText(t.toBranch);
            h.tvDetails.setText(String.format(Locale.getDefault(), "%d %s • %.2fg", t.itemCount, ar("قطعة", "pcs"), t.totalWeight));
            h.tvDate.setText(t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "");

            String statusAr;
            switch (t.status) {
                case "draft": statusAr = ar("مسودة", "Draft"); break;
                case "pending": statusAr = ar("بانتظار الاعتماد", "Pending Approval"); break;
                case "approved": 
                case "in_transit": statusAr = ar("في الطريق", "In Transit"); break;
                case "received": statusAr = ar("مكتمل ومستلم", "Completed"); break;
                default: statusAr = t.status;
            }
            h.tvStatus.setText(statusAr);

            h.itemView.setOnClickListener(v -> handler.onClick(t));

            if ("pending".equals(t.status)) {
                h.btnAction1.setVisibility(View.VISIBLE);
                h.btnAction1.setText(ar("اعتماد", "Approve"));
                h.btnAction1.setOnClickListener(v -> handler.onAction(t, "approve"));
                h.btnAction2.setVisibility(View.GONE);
            } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
                h.btnAction1.setVisibility(View.VISIBLE);
                h.btnAction1.setText(ar("استلام", "Receive"));
                h.btnAction1.setOnClickListener(v -> handler.onAction(t, "receive"));
                h.btnAction2.setVisibility(View.VISIBLE);
                h.btnAction2.setText(ar("مسح", "Scan"));
                h.btnAction2.setOnClickListener(v -> handler.onAction(t, "scan"));
            } else {
                h.btnAction1.setVisibility(View.GONE);
                h.btnAction2.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() { return list.size(); }

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
