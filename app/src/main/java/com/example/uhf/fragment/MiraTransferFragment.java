package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraTransferFragment extends Fragment {

    private RecyclerView rvTransfers;
    private TextView tvEmpty, tvTransferCount;
    private Button btnNewTransfer, btnScanItems;
    private Button btnFilterAll, btnFilterDraft, btnFilterPending, btnFilterInTransit, btnFilterCompleted;
    private View transferDetailCard;

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

        rvTransfers = view.findViewById(R.id.rvTransfers);
        tvEmpty = view.findViewById(R.id.tvEmptyTransfers);
        tvTransferCount = view.findViewById(R.id.tvTransferCount);
        btnNewTransfer = view.findViewById(R.id.btnNewTransfer);
        btnScanItems = view.findViewById(R.id.btnScanItems);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterDraft = view.findViewById(R.id.btnFilterDraft);
        btnFilterPending = view.findViewById(R.id.btnFilterPending);
        btnFilterInTransit = view.findViewById(R.id.btnFilterInTransit);
        btnFilterCompleted = view.findViewById(R.id.btnFilterCompleted);

        rvTransfers.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransferAdapter(transferList, this::onTransferClick, this::onTransferAction);
        rvTransfers.setAdapter(adapter);

        btnNewTransfer.setOnClickListener(v -> showNewTransferDialog());
        btnScanItems.setOnClickListener(v -> showScanItemsDialog());
        btnFilterAll.setOnClickListener(v -> filterBy(""));
        btnFilterDraft.setOnClickListener(v -> filterBy("draft"));
        btnFilterPending.setOnClickListener(v -> filterBy("pending"));
        btnFilterInTransit.setOnClickListener(v -> filterBy("approved,in_transit"));
        btnFilterCompleted.setOnClickListener(v -> filterBy("received"));

        loadTransfers();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (tvTransferCount != null) {
            tvTransferCount.setText(branchesManager.getActiveBranchDisplayName());
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
                            d.totalWeight = t.optDouble("total_weight", 0);
                            d.notes = t.optString("notes");
                            d.createdAt = t.optString("created_at");
                            transferList.add(d);
                        }
                    }
                }
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(transferList.isEmpty() ? View.VISIBLE : View.GONE);
                rvTransfers.setVisibility(transferList.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void filterBy(String filter) {
        currentFilter = filter;
        updateFilterButtons(filter);
        loadTransfers();
    }

    private void updateFilterButtons(String active) {
        btnFilterAll.setAlpha(active.isEmpty() ? 1f : 0.5f);
        btnFilterDraft.setAlpha("draft".equals(active) ? 1f : 0.5f);
        btnFilterPending.setAlpha("pending".equals(active) ? 1f : 0.5f);
        btnFilterInTransit.setAlpha("approved,in_transit".equals(active) ? 1f : 0.5f);
        btnFilterCompleted.setAlpha("received".equals(active) ? 1f : 0.5f);
    }

    private void onTransferClick(TransferData t) {
        // فتح تفاصيل التحويل
        showTransferActions(t);
    }

    private void onTransferAction(TransferData t, String action) {
        switch (action) {
            case "approve":
                approveTransfer(t);
                break;
            case "receive":
                receiveTransfer(t);
                break;
            case "scan":
                showScanItemsDialogForTransfer(t);
                break;
        }
    }

    private void showTransferActions(TransferData t) {
        String[] actions;
        if ("pending".equals(t.status)) {
            actions = new String[]{ar("اعتماد", "Approve"), ar("مسح قطع", "Scan Items"), ar("إلغاء", "Cancel")};
        } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
            actions = new String[]{ar("استلام", "Receive"), ar("مسح قطع", "Scan Items"), ar("إلغاء", "Cancel")};
        } else {
            actions = new String[]{ar("عرض التفاصيل", "View Details"), ar("إلغاء", "Cancel")};
        }

        new AlertDialog.Builder(requireContext())
            .setTitle(t.transferNumber + " - " + t.fromBranch + " → " + t.toBranch)
            .setItems(actions, (d, w) -> {
                String selected = actions[w];
                if (selected.contains("اعتماد") || selected.contains("Approve")) {
                    approveTransfer(t);
                } else if (selected.contains("استلام") || selected.contains("Receive")) {
                    receiveTransfer(t);
                } else if (selected.contains("مسح") || selected.contains("Scan")) {
                    showScanItemsDialogForTransfer(t);
                }
            })
            .setNegativeButton(ar("إلغاء", "Cancel"), null)
            .show();
    }

    private void approveTransfer(TransferData t) {
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.approveTransfer(t.id);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), ar("✅ تم الاعتماد", "✅ Approved"), Toast.LENGTH_SHORT).show();
                    loadTransfers();
                } else {
                    Toast.makeText(getContext(), ar("❌ فشل الاعتماد", "❌ Failed"), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void receiveTransfer(TransferData t) {
        new AlertDialog.Builder(requireContext())
            .setTitle(ar("تأكيد الاستلام", "Confirm Receive"))
            .setMessage(ar("هل أنت متأكد من استلام جميع قطع التحويل؟", "Confirm receiving all items?"))
            .setPositiveButton(ar("نعم، استلام", "Yes, Receive"), (d, w) -> {
                Executors.newSingleThreadExecutor().execute(() -> {
                    MiraApiClient.ApiResponse response = apiClient.receiveTransfer(t.id);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم الاستلام", "✅ Received"), Toast.LENGTH_SHORT).show();
                            loadTransfers();
                        } else {
                            Toast.makeText(getContext(), ar("❌ فشل الاستلام", "❌ Failed"), Toast.LENGTH_SHORT).show();
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

        // عرض الفرع الحالي
        tvFromBranch.setText("🏢 " + branchesManager.getActiveBranchDisplayName());
        final int fromBranchId = branchesManager.getActiveBranchId();

        // تحميل الفروع المتاحة للتحويل إليها
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
                    branchNames.add(ar("لا توجد فروع أخرى", "No other branches"));
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
                Toast.makeText(getContext(), ar("اختر فرعاً صحيحاً", "Select a valid branch"), Toast.LENGTH_SHORT).show();
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
                    payload.put("items", new JSONArray());

                    MiraApiClient.ApiResponse response = apiClient.createTransfer(payload);
                    mainHandler.post(() -> {
                        if (response != null && response.isSuccess) {
                            Toast.makeText(getContext(), ar("✅ تم إنشاء التحويل", "✅ Transfer created"), Toast.LENGTH_SHORT).show();
                            loadTransfers();
                        } else {
                            Toast.makeText(getContext(), ar("❌ فشل", "❌ Failed"), Toast.LENGTH_SHORT).show();
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

    private void showScanItemsDialog() {
        Toast.makeText(getContext(), ar("اختر تحويلاً أولاً لمسح قطعه", "Select a transfer first"), Toast.LENGTH_SHORT).show();
    }

    private void showScanItemsDialogForTransfer(TransferData t) {
        // فتح واجهة مسح القطع للتحويل
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dv = LayoutInflater.from(getContext()).inflate(R.layout.dialog_scan_transfer, null);
        builder.setView(dv);

        EditText etEpc = dv.findViewById(R.id.etScanEpc);
        Button btnScan = dv.findViewById(R.id.btnScanEpc);
        TextView tvScannedItems = dv.findViewById(R.id.tvScannedItems);

        AlertDialog dialog = builder.create();

        btnScan.setOnClickListener(v -> {
            String epc = etEpc.getText().toString().trim();
            if (epc.isEmpty()) return;

            Executors.newSingleThreadExecutor().execute(() -> {
                MiraApiClient.ApiResponse response = apiClient.scanTransferItem(t.id, epc);
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        boolean found = response.data != null && response.data.optBoolean("found", false);
                        if (found) {
                            String title = response.data.optString("title", "");
                            tvScannedItems.append("✅ " + title + " (" + epc + ")\n");
                        } else {
                            tvScannedItems.append("❌ " + ar("غير موجود", "Not found") + ": " + epc + "\n");
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

    // ==================== Models ====================

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
            this.list = list; this.handler = handler;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            TransferData t = list.get(pos);
            h.tvNumber.setText(t.transferNumber);
            h.tvFrom.setText("🏢 " + t.fromBranch);
            h.tvTo.setText("🏢 " + t.toBranch);
            h.tvDetails.setText(t.itemCount + " " + ar("قطعة", "pcs") + " • " + String.format(Locale.US, "%.1fg", t.totalWeight));
            h.tvDate.setText(t.createdAt != null && t.createdAt.length() >= 10 ? t.createdAt.substring(0, 10) : "");

            String statusAr;
            int statusColor;
            switch (t.status) {
                case "draft": statusAr = ar("مسودة", "Draft"); statusColor = 0xFF9CA3AF; break;
                case "pending": statusAr = ar("معلق", "Pending"); statusColor = 0xFFF59E0B; break;
                case "approved": statusAr = ar("معتمد", "Approved"); statusColor = 0xFF3B82F6; break;
                case "in_transit": statusAr = ar("بالطريق", "In Transit"); statusColor = 0xFF8B5CF6; break;
                case "received": statusAr = ar("مستلم", "Received"); statusColor = 0xFF22C55E; break;
                case "rejected": statusAr = ar("مرفوض", "Rejected"); statusColor = 0xFFEF4444; break;
                default: statusAr = t.status; statusColor = 0xFF9CA3AF;
            }
            h.tvStatus.setText(statusAr);
            h.tvStatus.setTextColor(statusColor);

            h.itemView.setOnClickListener(v -> handler.onClick(t));

            // أزرار الإجراءات
            if ("pending".equals(t.status)) {
                h.btnAction1.setVisibility(View.VISIBLE);
                h.btnAction1.setText(ar("✅ اعتماد", "✅ Approve"));
                h.btnAction1.setOnClickListener(v -> handler.onAction(t, "approve"));
                h.btnAction2.setVisibility(View.GONE);
            } else if ("approved".equals(t.status) || "in_transit".equals(t.status)) {
                h.btnAction1.setVisibility(View.VISIBLE);
                h.btnAction1.setText(ar("📦 استلام", "📦 Receive"));
                h.btnAction1.setOnClickListener(v -> handler.onAction(t, "receive"));
                h.btnAction2.setVisibility(View.VISIBLE);
                h.btnAction2.setText(ar("📷 مسح", "📷 Scan"));
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
