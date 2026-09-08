package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * ✅ إدارة الشرائح المبرمجة والمتاحة (مع تحسين استهلاك الثيمات الرسمية والأداء العالي)
 */
public class MiraRfidBindingsFragment extends MiraBaseFragment {
    
    private static final String TAG = "MiraRfidBindings";
    
    private RecyclerView rvBindings;
    private BindingsAdapter adapter;
    private AvailableTagsAdapter availableAdapter;
    private MiraApiClient apiClient;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private TextView tvCount;
    private TextView tvTitle;
    private TextView tvAvailableCount;
    private MaterialButton btnAvailableTags;
    private String currentFilter = "";
    private String currentSearch = "";
    private boolean showingAvailableTags = false;
    
    // Pagination variables
    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean isLastPage = false;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_rfid_bindings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        Context context = getContext();
        if (context == null) return;
        
        apiClient = MiraApiClient.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        
        tvCount = view.findViewById(R.id.tvCount);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvAvailableCount = view.findViewById(R.id.tvAvailableCount);
        btnAvailableTags = view.findViewById(R.id.btnAvailableTags);
        rvBindings = view.findViewById(R.id.rvBindings);
        
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        rvBindings.setLayoutManager(layoutManager);
        
        adapter = new BindingsAdapter(this::showBindingDetails);
        availableAdapter = new AvailableTagsAdapter();
        rvBindings.setAdapter(adapter);
        
        if (btnAvailableTags != null) {
            btnAvailableTags.setOnClickListener(v -> {
                showingAvailableTags = true;
                loadAvailableTags();
            });
        }
        
        // مراقبة التمرير للـ Pagination
        rvBindings.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                int visibleItemCount = layoutManager.getChildCount();
                int totalItemCount = layoutManager.getItemCount();
                int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();

                if (!isLoading && !isLastPage && !showingAvailableTags) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount
                            && firstVisibleItemPosition >= 0 && totalItemCount >= 50) {
                        loadMoreBindings();
                    }
                }
            }
        });
        
        SwipeRefreshLayout swipeRefresh = view.findViewById(R.id.swipeRefresh);
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(() -> {
                currentPage = 1;
                isLastPage = false;
                if (showingAvailableTags) {
                    loadAvailableTags();
                } else {
                    loadBindings(true);
                }
                swipeRefresh.setRefreshing(false);
            });
        }
        
        TabLayout tabFilter = view.findViewById(R.id.tabFilter);
        if (tabFilter != null) {
            tabFilter.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    currentPage = 1;
                    isLastPage = false;
                    switch (tab.getPosition()) {
                        case 0: currentFilter = ""; showingAvailableTags = false; loadBindings(true); break;
                        case 1: currentFilter = "active"; showingAvailableTags = false; loadBindings(true); break;
                        case 2: currentFilter = "locked"; showingAvailableTags = false; loadBindings(true); break;
                        case 3: currentFilter = "inactive"; showingAvailableTags = false; loadBindings(true); break;
                        case 4: showingAvailableTags = true; loadAvailableTags(); break;
                    }
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
        
        View btnSearch = view.findViewById(R.id.btnSearch);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> showSearchDialog());
        }
        
        ExtendedFloatingActionButton fabExport = view.findViewById(R.id.fabExport);
        if (fabExport != null) {
            boolean canExport = permissionManager != null && 
                (permissionManager.isManager() || permissionManager.canEdit("rfid"));
            fabExport.setVisibility(canExport ? View.VISIBLE : View.GONE);
            fabExport.setOnClickListener(v -> exportToCSV());
        }
        
        loadBindings(true);
        loadAvailableTags();
    }
    
    private void loadAvailableTags() {
        showingAvailableTags = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getWithAuth("/rfid/available");
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    if (response != null && response.isSuccess && response.data != null) {
                        int totalAvailable = response.data.optInt("total_available", 0);
                        JSONArray availableTags = response.data.optJSONArray("available_tags");
                        
                        List<JSONObject> list = new ArrayList<>();
                        if (availableTags != null) {
                            for (int i = 0; i < availableTags.length(); i++) {
                                list.add(availableTags.optJSONObject(i));
                            }
                        }
                        
                        rvBindings.setAdapter(availableAdapter);
                        availableAdapter.submitList(list);
                        
                        if (tvAvailableCount != null) {
                            tvAvailableCount.setText((isArabic() ? "📦 المتاحة: " : "📦 Available: ") + totalAvailable);
                            tvAvailableCount.setVisibility(View.VISIBLE);
                        }
                        if (tvCount != null) {
                            tvCount.setText((isArabic() ? "الإجمالي: " : "Total: ") + list.size());
                        }
                        if (tvTitle != null) {
                            tvTitle.setText(isArabic() ? "الشرائح المتاحة" : "Available Tags");
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadAvailableTags error: " + e.getMessage());
            }
        });
    }
    
    private void loadBindings(boolean isRefresh) {
        if (isLoading) return;
        isLoading = true;
        showingAvailableTags = false;
        
        if (isRefresh) {
            currentPage = 1;
            isLastPage = false;
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                StringBuilder endpoint = new StringBuilder("/rfid-bindings-list?page=" + currentPage + "&per_page=50");
                if (!currentFilter.isEmpty()) endpoint.append("&status=").append(currentFilter);
                if (!currentSearch.isEmpty()) endpoint.append("&search=").append(currentSearch);
                
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint.toString());
                
                mainHandler.post(() -> {
                    isLoading = false;
                    if (!isAdded() || getContext() == null) return;
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray bindings = response.data.optJSONArray("bindings");
                        int total = response.data.optInt("total", 0);
                        
                        List<JSONObject> fetchedList = new ArrayList<>();
                        if (bindings != null) {
                            for (int i = 0; i < bindings.length(); i++) {
                                fetchedList.add(bindings.optJSONObject(i));
                            }
                        }
                        
                        if (fetchedList.isEmpty()) {
                            isLastPage = true;
                        }
                        
                        if (rvBindings.getAdapter() != adapter) {
                            rvBindings.setAdapter(adapter);
                        }
                        
                        if (isRefresh) {
                            adapter.submitList(fetchedList);
                        } else {
                            List<JSONObject> currentList = new ArrayList<>(adapter.getCurrentList());
                            currentList.addAll(fetchedList);
                            adapter.submitList(currentList);
                        }
                        
                        if (tvCount != null) {
                            tvCount.setText(isArabic() ? "إجمالي المبرمجة: " + total : "Total Programmed: " + total);
                        }
                        if (tvTitle != null) {
                            tvTitle.setText(isArabic() ? "الشرائح المبرمجة" : "Programmed Tags");
                        }
                    }
                });
            } catch (Exception e) {
                isLoading = false;
                Log.e(TAG, "loadBindings error: " + e.getMessage());
            }
        });
    }
    
    private void loadMoreBindings() {
        currentPage++;
        loadBindings(false);
    }
    
    private void showSearchDialog() {
        Context context = getContext();
        if (!isAdded() || context == null) return;
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.rfid_search_hint);
        
        EditText etSearch = new EditText(context);
        etSearch.setHint(R.string.rfid_search_hint);
        etSearch.setPadding(40, 20, 40, 20);
        builder.setView(etSearch);
        
        builder.setPositiveButton(android.R.string.search_go, (dialog, which) -> {
            currentSearch = etSearch.getText().toString().trim();
            loadBindings(true);
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }
    
    private void showBindingDetails(JSONObject binding) {
        Context context = getContext();
        if (binding == null || !isAdded() || context == null) return;
        
        String title = binding.optString("item_title", "-");
        String miraId = binding.optString("mira_id", "-");
        String epc = binding.optString("epc", "-");
        String tid = binding.optString("tid", "-");
        String karat = binding.optString("gold_karat", "-");
        double weight = binding.optDouble("gross_weight_grams", 0);
        String status = binding.optString("status", "active");
        String deviceType = binding.optString("device_type", "-");
        String date = binding.optString("registered_at", "-");
        
        boolean canManage = permissionManager != null && 
            (permissionManager.isManager() || permissionManager.canEdit("rfid"));
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        
        StringBuilder message = new StringBuilder();
        message.append("🔑 MIRA ID: ").append(miraId).append("\n");
        message.append("📡 EPC: ").append(epc).append("\n");
        message.append("🏷️ TID: ").append(tid).append("\n");
        message.append("💎 ").append(getString(R.string.karat)).append(": ").append(karat).append("\n");
        message.append("⚖️ ").append(getString(R.string.weight)).append(": ").append(String.format(Locale.US, "%.2fg", weight)).append("\n");
        message.append("📱 ").append(getString(R.string.device)).append(": ").append(deviceType).append("\n");
        message.append("📅 ").append(getString(R.string.date)).append(": ").append(date).append("\n");
        message.append("📊 ").append(getString(R.string.status)).append(": ").append(getStatusLabel(status));
        
        builder.setMessage(message.toString());
        if (canManage) {
            builder.setPositiveButton(R.string.rfid_manage, (dialog, which) -> showManageOptions(binding));
        }
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }
    
    private void showManageOptions(JSONObject binding) {
        Context context = getContext();
        if (!isAdded() || context == null) return;
        int bindingId = binding.optInt("id", 0);
        
        String[] options = new String[]{
            getString(R.string.rfid_lock),
            getString(R.string.rfid_unlock),
            getString(R.string.rfid_deactivate),
            getString(R.string.rfid_delete)
        };
        
        new AlertDialog.Builder(context)
            .setTitle(R.string.rfid_manage)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: updateBindingStatus(bindingId, "locked"); break;
                    case 1: updateBindingStatus(bindingId, "active"); break;
                    case 2: updateBindingStatus(bindingId, "inactive"); break;
                    case 3: confirmDelete(bindingId); break;
                }
            })
            .show();
    }
    
    private void confirmDelete(int bindingId) {
        Context context = getContext();
        if (!isAdded() || context == null) return;

        new AlertDialog.Builder(context)
            .setTitle(android.R.string.dialog_alert_title)
            .setMessage(R.string.rfid_confirm_delete)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> updateBindingStatus(bindingId, "deleted"))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
    
    private void updateBindingStatus(int bindingId, String status) {
        if (permissionManager == null || !(permissionManager.isManager() || permissionManager.canEdit("rfid"))) {
            showToast(isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied");
            return;
        }
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("binding_id", bindingId);
                payload.put("status", status);
                
                MiraApiClient.ApiResponse response = apiClient.postWithAuth("/update-binding-status", payload);
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    if (response != null && response.isSuccess) {
                        showToast(getString(R.string.rfid_updated));
                        loadBindings(true);
                    } else {
                        showToast("❌ " + (response != null && response.error != null ? response.error : getString(R.string.rfid_load_failed)));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateBindingStatus error: " + e.getMessage());
            }
        });
    }
    
    private void exportToCSV() {
        Context context = getContext();
        List<JSONObject> currentList = adapter.getCurrentList();
        if (currentList.isEmpty() || !isAdded() || context == null) return;
        
        try {
            StringBuilder csv = new StringBuilder();
            csv.append('\uFEFF');
            csv.append("MIRA ID,Title,EPC,TID,Karat,Weight,Status,Device,Date\n");
            
            for (JSONObject binding : currentList) {
                csv.append(binding.optString("mira_id", "")).append(",");
                csv.append("\"").append(binding.optString("item_title", "")).append("\",");
                csv.append(binding.optString("epc", "")).append(",");
                csv.append(binding.optString("tid", "")).append(",");
                csv.append(binding.optString("gold_karat", "")).append(",");
                csv.append(binding.optDouble("gross_weight_grams", 0)).append(",");
                csv.append(binding.optString("status", "")).append(",");
                csv.append(binding.optString("device_type", "")).append(",");
                csv.append(binding.optString("registered_at", "")).append("\n");
            }
            
            File exportDir = new File(context.getCacheDir(), "exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            
            File file = new File(exportDir, "MIRA_RFID_Bindings_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(csv.toString().getBytes("UTF-8"));
            fos.close();
            
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share CSV"));
            
        } catch (Exception e) {
            Log.e(TAG, "exportToCSV error: " + e.getMessage());
        }
    }
    
    private String getStatusLabel(String status) {
        if (!isAdded()) return status;
        switch (status) {
            case "active": return getString(R.string.rfid_status_active);
            case "locked": return getString(R.string.rfid_status_locked);
            case "inactive": return getString(R.string.rfid_status_inactive);
            default: return status;
        }
    }
    
    private void showToast(String msg) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }
    
    // ==================== Optimized Adapters using ListAdapter & DiffUtil ====================
    
    private static final DiffUtil.ItemCallback<JSONObject> DIFF_CALLBACK = new DiffUtil.ItemCallback<JSONObject>() {
        @Override
        public boolean areItemsTheSame(@NonNull JSONObject oldItem, @NonNull JSONObject newItem) {
            return oldItem.optInt("id", 0) == newItem.optInt("id", 0);
        }

        @Override
        public boolean areContentsTheSame(@NonNull JSONObject oldItem, @NonNull JSONObject newItem) {
            return oldItem.toString().equals(newItem.toString());
        }
    };
    
    private class BindingsAdapter extends ListAdapter<JSONObject, BindingsAdapter.VH> {
        private final OnBindingClickListener listener;
        
        BindingsAdapter(OnBindingClickListener listener) {
            super(DIFF_CALLBACK);
            this.listener = listener;
        }
        
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_binding_card, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject binding = getItem(pos);
            h.tvItemTitle.setText(binding.optString("item_title", "-"));
            h.tvDetails.setText(binding.optString("mira_id", "-") + " | " + binding.optString("gold_karat", "-") + " | " + String.format(Locale.US, "%.2fg", binding.optDouble("gross_weight_grams", 0)));
            h.tvEpcTid.setText("EPC: " + binding.optString("epc", "-") + "\nTID: " + binding.optString("tid", "-"));
            h.tvDate.setText(binding.optString("registered_at", "-"));
            
            String status = binding.optString("status", "active");
if (h.tvStatus != null) {
    h.tvStatus.setText(getStatusLabel(status));
    
    Context context = h.itemView.getContext();
    
    // استخدام ContextCompat لمنع الأخطاء على الإصدارات القديمة (API < 23)
    if ("active".equals(status)) {
        h.tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.holo_green_dark));
    } else if ("locked".equals(status)) {
        h.tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.holo_orange_dark));
    } else {
        h.tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.holo_red_dark));
    }
}


            h.itemView.setOnClickListener(v -> listener.onClick(binding));
        }
        
        class VH extends RecyclerView.ViewHolder {
            TextView tvItemTitle, tvStatus, tvDetails, tvEpcTid, tvDate;
            VH(View v) {
                super(v);
                tvItemTitle = v.findViewById(R.id.tvItemTitle);
                tvStatus = v.findViewById(R.id.tvStatus);
                tvDetails = v.findViewById(R.id.tvDetails);
                tvEpcTid = v.findViewById(R.id.tvEpcTid);
                tvDate = v.findViewById(R.id.tvDate);
            }
        }
    }
    
    private static class AvailableTagsAdapter extends ListAdapter<JSONObject, AvailableTagsAdapter.VH> {
        AvailableTagsAdapter() { super(DIFF_CALLBACK); }
        
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_available_tag_card, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject tag = getItem(pos);
            h.tvTid.setText(tag.optString("tid", "-"));
            h.tvGtin.setText("GTIN: " + tag.optString("gtin13", "-"));
            h.tvSerial.setText("Serial: " + tag.optString("serial_number", "-"));
            h.tvBatch.setText("Batch: " + tag.optString("batch_number", "-"));
            h.tvImportedAt.setText("Imported: " + tag.optString("imported_at", "-"));
        }
        
        static class VH extends RecyclerView.ViewHolder {
            TextView tvTid, tvGtin, tvSerial, tvBatch, tvImportedAt;
            VH(View v) {
                super(v);
                tvTid = v.findViewById(R.id.tvTid);
                tvGtin = v.findViewById(R.id.tvGtin);
                tvSerial = v.findViewById(R.id.tvSerial);
                tvBatch = v.findViewById(R.id.tvBatch);
                tvImportedAt = v.findViewById(R.id.tvImportedAt);
            }
        }
    }
    
    interface OnBindingClickListener {
        void onClick(JSONObject binding);
    }
    
    private boolean isArabic() {
        return Locale.getDefault().getLanguage().equals("ar");
    }
}
