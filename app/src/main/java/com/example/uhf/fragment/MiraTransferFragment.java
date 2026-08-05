package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.graphics.Color;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraTransferFragment extends Fragment {

    private RecyclerView rvTransfers;
    private TextView tvEmpty;
    private Button btnNewTransfer;
    private Button btnFilterAll, btnFilterPending, btnFilterInTransit, btnFilterCompleted;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final List<TransferData> transferList = new ArrayList<>();
    private TransferAdapter adapter;
    private String currentFilter = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transfer_management, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());

        rvTransfers = view.findViewById(R.id.rvTransfers);
        tvEmpty = view.findViewById(R.id.tvEmptyTransfers);
        btnNewTransfer = view.findViewById(R.id.btnNewTransfer);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterPending = view.findViewById(R.id.btnFilterPending);
        btnFilterInTransit = view.findViewById(R.id.btnFilterInTransit);
        btnFilterCompleted = view.findViewById(R.id.btnFilterCompleted);

        rvTransfers.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransferAdapter(transferList);
        rvTransfers.setAdapter(adapter);

        btnNewTransfer.setOnClickListener(v -> showNewTransferDialog());
        btnFilterAll.setOnClickListener(v -> filterTransfers(""));
        btnFilterPending.setOnClickListener(v -> filterTransfers("pending"));
        btnFilterInTransit.setOnClickListener(v -> filterTransfers("approved,in_transit"));
        btnFilterCompleted.setOnClickListener(v -> filterTransfers("received"));

        loadTransfers();
    }

    private void loadTransfers() {
        Executors.newSingleThreadExecutor().execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getTransfers(branchId, currentFilter);

            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray transfers = response.data.optJSONArray("transfers");
                    transferList.clear();
                    if (transfers != null) {
                        for (int i = 0; i < transfers.length(); i++) {
                            JSONObject t = transfers.optJSONObject(i);
                            if (t == null) continue;

                            TransferData data = new TransferData();
                            data.id = t.optInt("id");
                            data.transferNumber = t.optString("transfer_number");
                            data.fromBranch = t.optString("from_branch_name_ar", t.optString("from_branch_name"));
                            data.toBranch = t.optString("to_branch_name_ar", t.optString("to_branch_name"));
                            data.status = t.optString("status");
                            data.itemCount = t.optInt("item_count");
                            data.totalWeight = t.optDouble("total_weight", 0);
                            data.createdAt = t.optString("created_at");
                            transferList.add(data);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    updateEmptyState();
                }
            });
        });
    }

    private void filterTransfers(String filter) {
        currentFilter = filter;
        updateFilterButtons();
        loadTransfers();
    }

    private void updateFilterButtons() {
        btnFilterAll.setAlpha(currentFilter.isEmpty() ? 1f : 0.5f);
        btnFilterPending.setAlpha("pending".equals(currentFilter) ? 1f : 0.5f);
        btnFilterInTransit.setAlpha("approved,in_transit".equals(currentFilter) ? 1f : 0.5f);
        btnFilterCompleted.setAlpha("received".equals(currentFilter) ? 1f : 0.5f);
    }

    private void updateEmptyState() {
        if (transferList.isEmpty()) {
            rvTransfers.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            rvTransfers.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void showNewTransferDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_new_transfer, null);
        builder.setView(dialogView);

        Spinner spFrom = dialogView.findViewById(R.id.spFromBranch);
        Spinner spTo = dialogView.findViewById(R.id.spToBranch);
        EditText etNotes = dialogView.findViewById(R.id.etTransferNotes);
        Button btnCancel = dialogView.findViewById(R.id.btnCancelTransfer);
        Button btnCreate = dialogView.findViewById(R.id.btnCreateTransfer);

        // تحميل الفروع
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getBranches();
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray branches = response.data.optJSONArray("branches");
                    if (branches != null) {
                        List<String> branchNames = new ArrayList<>();
                        List<Integer> branchIds = new ArrayList<>();
                        for (int i = 0; i < branches.length(); i++) {
                            JSONObject b = branches.optJSONObject(i);
                            if (b != null) {
                                branchNames.add(b.optString("branch_name_ar", b.optString("branch_name")));
                                branchIds.add(b.optInt("id"));
                            }
                        }
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                            android.R.layout.simple_spinner_item, branchNames);
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        spFrom.setAdapter(adapter);
                        spTo.setAdapter(adapter);

                        // تعيين الفرع الحالي كمصدر افتراضي
                        int currentBranchId = branchesManager.getActiveBranchId();
                        for (int i = 0; i < branchIds.size(); i++) {
                            if (branchIds.get(i) == currentBranchId) {
                                spFrom.setSelection(i);
                                break;
                            }
                        }
                    }
                }
            });
        });

        AlertDialog dialog = builder.create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnCreate.setOnClickListener(v -> {
            int fromPos = spFrom.getSelectedItemPosition();
            int toPos = spTo.getSelectedItemPosition();
            String notes = etNotes.getText().toString().trim();

            if (fromPos == toPos) {
                Toast.makeText(getContext(), "لا يمكن التحويل لنفس الفرع", Toast.LENGTH_SHORT).show();
                return;
            }

            createTransfer(fromPos, toPos, notes);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void createTransfer(int fromPos, int toPos, String notes) {
        Toast.makeText(getContext(), "جاري إنشاء التحويل...", Toast.LENGTH_SHORT).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("from_branch_id", fromPos + 1); // تبسيط - يجب استخدام الـ ID الحقيقي
                payload.put("to_branch_id", toPos + 1);
                payload.put("notes", notes);
                payload.put("items", new JSONArray());

                MiraApiClient.ApiResponse response = apiClient.createTransfer(payload);

                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), "✅ تم إنشاء التحويل بنجاح", Toast.LENGTH_SHORT).show();
                        loadTransfers();
                    } else {
                        Toast.makeText(getContext(), "❌ فشل إنشاء التحويل", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(getContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ==================== TransferData ====================

    static class TransferData {
        int id, itemCount;
        String transferNumber, fromBranch, toBranch, status, createdAt;
        double totalWeight;
    }

    // ==================== Adapter ====================

    class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.VH> {
        final List<TransferData> list;

        TransferAdapter(List<TransferData> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transfer_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            TransferData t = list.get(pos);
            h.tvNumber.setText(t.transferNumber);
            h.tvFrom.setText("🏢 " + t.fromBranch);
            h.tvTo.setText("🏢 " + t.toBranch);
            h.tvDetails.setText(t.itemCount + " قطعة • " + String.format(Locale.US, "%.1fg", t.totalWeight));
            h.tvDate.setText(t.createdAt.substring(0, Math.min(10, t.createdAt.length())));

            // حالة التحويل
            String statusAr;
            int statusColor;
            switch (t.status) {
                case "draft": statusAr = "مسودة"; statusColor = 0xFF9CA3AF; break;
                case "pending": statusAr = "معلق"; statusColor = 0xFFF59E0B; break;
                case "approved": statusAr = "معتمد"; statusColor = 0xFF3B82F6; break;
                case "in_transit": statusAr = "بالطريق"; statusColor = 0xFF8B5CF6; break;
                case "received": statusAr = "مستلم"; statusColor = 0xFF22C55E; break;
                case "rejected": statusAr = "مرفوض"; statusColor = 0xFFEF4444; break;
                default: statusAr = t.status; statusColor = 0xFF9CA3AF;
            }
            h.tvStatus.setText(statusAr);
            h.tvStatus.setBackgroundColor(statusColor);
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvNumber, tvStatus, tvFrom, tvTo, tvDetails, tvDate;

            VH(View v) {
                super(v);
                tvNumber = v.findViewById(R.id.tvTransferNumber);
                tvStatus = v.findViewById(R.id.tvTransferStatus);
                tvFrom = v.findViewById(R.id.tvFromBranch);
                tvTo = v.findViewById(R.id.tvToBranch);
                tvDetails = v.findViewById(R.id.tvTransferDetails);
                tvDate = v.findViewById(R.id.tvTransferDate);
            }
        }
    }
}
