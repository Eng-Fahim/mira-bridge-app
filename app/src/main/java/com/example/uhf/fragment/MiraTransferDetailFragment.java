package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.PermissionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraTransferDetailFragment extends Fragment {

    private static final String ARG_TRANSFER_ID = "transfer_id";

    private int transferId;
    private TextView tvTransferNumber, tvFromBranch, tvToBranch;
    private TextView tvItemCount, tvScannedCount, tvStatus;
    private EditText etScanEPC;
    private Button btnManualScan, btnApprove, btnReject, btnReceive;
    private ProgressBar progressScan;
    private RecyclerView rvItems;

    private MiraApiClient apiClient;
    private PermissionManager permissionManager;
    private final List<TransferItemData> itemList = new ArrayList<>();
    private TransferItemAdapter itemAdapter;
    private int totalItems = 0;
    private int scannedItems = 0;
    private String currentStatus;
    private boolean isArabic;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static MiraTransferDetailFragment newInstance(int transferId) {
        MiraTransferDetailFragment f = new MiraTransferDetailFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_TRANSFER_ID, transferId);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_transfer_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getArguments() != null) {
            transferId = getArguments().getInt(ARG_TRANSFER_ID);
        }

        apiClient = MiraApiClient.getInstance(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        tvTransferNumber = view.findViewById(R.id.tvDetailTransferNumber);
        tvFromBranch = view.findViewById(R.id.tvDetailFromBranch);
        tvToBranch = view.findViewById(R.id.tvDetailToBranch);
        tvItemCount = view.findViewById(R.id.tvDetailItemCount);
        tvScannedCount = view.findViewById(R.id.tvDetailScannedCount);
        tvStatus = view.findViewById(R.id.tvDetailStatus);
        etScanEPC = view.findViewById(R.id.etScanEPC);
        btnManualScan = view.findViewById(R.id.btnManualScan);
        btnApprove = view.findViewById(R.id.btnApproveTransfer);
        btnReject = view.findViewById(R.id.btnRejectTransfer);
        btnReceive = view.findViewById(R.id.btnReceiveTransfer);
        progressScan = view.findViewById(R.id.progressScan);
        rvItems = view.findViewById(R.id.rvTransferItems);

        rvItems.setLayoutManager(new LinearLayoutManager(getContext()));
        itemAdapter = new TransferItemAdapter(itemList);
        rvItems.setAdapter(itemAdapter);

        btnManualScan.setOnClickListener(v -> scanItem());
        btnApprove.setOnClickListener(v -> {
            if (canApprove()) approveTransfer();
            else showAccessDenied();
        });
        btnReject.setOnClickListener(v -> {
            if (canApprove()) rejectTransfer();
            else showAccessDenied();
        });
        btnReceive.setOnClickListener(v -> {
            if (canReceive()) receiveTransfer();
            else showAccessDenied();
        });

        applyPermissions();
        loadTransferDetail();
    }

    private boolean canApprove() {
        return permissionManager.isManager() || 
               permissionManager.isBranchManager() || 
               permissionManager.canEdit("transfers");
    }

    private boolean canReceive() {
        return permissionManager.isManager() || 
               permissionManager.isBranchManager() || 
               permissionManager.canEdit("transfers");
    }

    private boolean canScan() {
        return permissionManager.canView("transfers") || permissionManager.isManager();
    }

    private void applyPermissions() {
        if (btnManualScan != null) {
            btnManualScan.setEnabled(canScan());
        }
        if (etScanEPC != null) {
            etScanEPC.setEnabled(canScan());
        }
        if (btnApprove != null) {
            btnApprove.setEnabled(canApprove());
        }
        if (btnReject != null) {
            btnReject.setEnabled(canApprove());
        }
        if (btnReceive != null) {
            btnReceive.setEnabled(canReceive());
        }
    }

    private void showAccessDenied() {
        Toast.makeText(getContext(), 
            ar("⛔ غير مصرح لك بهذا الإجراء", "⛔ Access Denied"),
            Toast.LENGTH_SHORT).show();
    }

    private String ar(String arabic, String english) { return isArabic ? arabic : english; }

    private void loadTransferDetail() {
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getTransfer(transferId);

            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject transfer = response.data.optJSONObject("transfer");
                    JSONArray items = response.data.optJSONArray("items");

                    if (transfer != null) {
                        tvTransferNumber.setText(transfer.optString("transfer_number"));
                        tvFromBranch.setText("🏢 " + transfer.optString("from_branch_name_ar", transfer.optString("from_branch_name")));
                        tvToBranch.setText("🏢 " + transfer.optString("to_branch_name_ar", transfer.optString("to_branch_name")));
                        currentStatus = transfer.optString("status");
                        updateStatusUI(currentStatus);
                        totalItems = transfer.optInt("item_count", 0);
                        tvItemCount.setText(String.valueOf(totalItems));
                    }

                    if (items != null) {
                        itemList.clear();
                        scannedItems = 0;
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item == null) continue;

                            TransferItemData data = new TransferItemData();
                            data.itemId = item.optInt("item_id");
                            data.title = item.optString("item_title", ar("قطعة", "Item"));
                            data.epc = item.optString("rfid_epc", "");
                            data.karat = item.optString("gold_karat", "");
                            data.weight = item.optDouble("gross_weight_grams", 0);
                            data.scanStatus = item.optString("scan_status", "pending");
                            if ("scanned".equals(data.scanStatus) || "verified".equals(data.scanStatus)) {
                                scannedItems++;
                            }
                            itemList.add(data);
                        }
                        itemAdapter.notifyDataSetChanged();
                        updateScanProgress();
                    }
                }
            });
        });
    }

    private void scanItem() {
        if (!canScan()) {
            showAccessDenied();
            return;
        }
        
        String epc = etScanEPC.getText().toString().trim();
        if (epc.isEmpty()) {
            Toast.makeText(getContext(), 
                ar("أدخل EPC أو امسح الشريحة", "Enter EPC or scan tag"), 
                Toast.LENGTH_SHORT).show();
            return;
        }

        etScanEPC.setText("");
        Toast.makeText(getContext(), 
            ar("🔍 جاري التحقق...", "🔍 Verifying..."), 
            Toast.LENGTH_SHORT).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.scanTransferItem(transferId, epc);

            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    boolean found = response.data.optBoolean("found", false);
                    if (found) {
                        int itemId = response.data.optInt("item_id");
                        for (TransferItemData item : itemList) {
                            if (item.itemId == itemId && !"scanned".equals(item.scanStatus)) {
                                item.scanStatus = "scanned";
                                scannedItems++;
                                break;
                            }
                        }
                        itemAdapter.notifyDataSetChanged();
                        updateScanProgress();
                        Toast.makeText(getContext(), 
                            "✅ " + response.data.optString("title", ar("تم المسح", "Scanned")), 
                            Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), 
                            ar("❌ القطعة ليست ضمن هذا التحويل", "❌ Item not in this transfer"), 
                            Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    private void updateScanProgress() {
        tvScannedCount.setText(String.valueOf(scannedItems));
        if (totalItems > 0) {
            progressScan.setProgress((scannedItems * 100) / totalItems);
        }
    }

    private void updateStatusUI(String status) {
        switch (status) {
            case "draft": tvStatus.setText(ar("مسودة", "Draft")); tvStatus.setTextColor(0xFF9CA3AF); break;
            case "pending": tvStatus.setText(ar("معلق", "Pending")); tvStatus.setTextColor(0xFFF59E0B); break;
            case "approved": tvStatus.setText(ar("معتمد", "Approved")); tvStatus.setTextColor(0xFF3B82F6); break;
            case "in_transit": tvStatus.setText(ar("بالطريق", "In Transit")); tvStatus.setTextColor(0xFF8B5CF6); break;
            case "received": tvStatus.setText(ar("مستلم", "Received")); tvStatus.setTextColor(0xFF22C55E); break;
            case "rejected": tvStatus.setText(ar("مرفوض", "Rejected")); tvStatus.setTextColor(0xFFEF4444); break;
            default: tvStatus.setText(status); tvStatus.setTextColor(0xFF9CA3AF);
        }

        boolean isPending = "pending".equals(status);
        boolean isApproved = "approved".equals(status) || "in_transit".equals(status);

        btnApprove.setVisibility(isPending && canApprove() ? View.VISIBLE : View.GONE);
        btnReject.setVisibility(isPending && canApprove() ? View.VISIBLE : View.GONE);
        btnReceive.setVisibility(isApproved && canReceive() ? View.VISIBLE : View.GONE);
    }

    private void approveTransfer() {
        if (!canApprove()) {
            showAccessDenied();
            return;
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.approveTransfer(transferId);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), 
                        ar("✅ تم اعتماد التحويل", "✅ Approved"), 
                        Toast.LENGTH_SHORT).show();
                    currentStatus = "approved";
                    updateStatusUI(currentStatus);
                } else {
                    Toast.makeText(getContext(), 
                        ar("❌ فشل الاعتماد", "❌ Failed"), 
                        Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void rejectTransfer() {
        if (!canApprove()) {
            showAccessDenied();
            return;
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            JSONObject payload = new JSONObject();
            try { payload.put("status", "rejected"); } catch (Exception ignored) {}
            MiraApiClient.ApiResponse response = apiClient.updateTransfer(transferId, payload);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), 
                        ar("تم رفض التحويل", "Transfer rejected"), 
                        Toast.LENGTH_SHORT).show();
                    currentStatus = "rejected";
                    updateStatusUI(currentStatus);
                }
            });
        });
    }

    private void receiveTransfer() {
        if (!canReceive()) {
            showAccessDenied();
            return;
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.receiveTransfer(transferId);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    Toast.makeText(getContext(), 
                        ar("✅ تم استلام التحويل بنجاح", "✅ Received"), 
                        Toast.LENGTH_SHORT).show();
                    currentStatus = "received";
                    updateStatusUI(currentStatus);
                } else {
                    Toast.makeText(getContext(), 
                        ar("❌ فشل الاستلام", "❌ Failed"), 
                        Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    static class TransferItemData {
        int itemId;
        String title, epc, karat, scanStatus;
        double weight;
    }

    class TransferItemAdapter extends RecyclerView.Adapter<TransferItemAdapter.VH> {
        final List<TransferItemData> list;

        TransferItemAdapter(List<TransferItemData> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transfer_scan, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            TransferItemData item = list.get(pos);
            h.tvTitle.setText(item.title);
            h.tvEpc.setText("EPC: " + (item.epc.isEmpty() ? "—" : item.epc.substring(0, Math.min(16, item.epc.length())) + "..."));

            switch (item.scanStatus) {
                case "scanned":
                case "verified":
                    h.ivStatus.setImageResource(android.R.drawable.ic_menu_help);
                    h.ivStatus.setColorFilter(0xFF22C55E);
                    h.tvScanStatus.setText("✅");
                    break;
                case "missing":
                    h.ivStatus.setImageResource(android.R.drawable.ic_menu_help);
                    h.ivStatus.setColorFilter(0xFFEF4444);
                    h.tvScanStatus.setText("❌");
                    break;
                default:
                    h.ivStatus.setImageResource(android.R.drawable.ic_menu_help);
                    h.ivStatus.setColorFilter(0xFF9CA3AF);
                    h.tvScanStatus.setText("⏳");
            }
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivStatus;
            TextView tvTitle, tvEpc, tvScanStatus;

            VH(View v) {
                super(v);
                ivStatus = v.findViewById(R.id.ivScanStatus);
                tvTitle = v.findViewById(R.id.tvItemTitle);
                tvEpc = v.findViewById(R.id.tvItemEpc);
                tvScanStatus = v.findViewById(R.id.tvItemScanStatus);
            }
        }
    }
}
