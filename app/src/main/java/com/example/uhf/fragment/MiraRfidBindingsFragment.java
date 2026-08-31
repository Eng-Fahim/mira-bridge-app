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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.PermissionManager;
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
 * ✅ إدارة الشرائح المبرمجة والمتاحة
 * - تحترم الثيمات من MiraBaseFragment
 * - تدعم العربية والإنجليزية
 * - مع Logging شامل للتشخيص
 */
public class MiraRfidBindingsFragment extends MiraBaseFragment {
    
    private static final String TAG = "MiraRfidBindings";
    
    private RecyclerView rvBindings;
    private BindingsAdapter adapter;
    private MiraApiClient apiClient;
    private PermissionManager permissionManager;
    private final List<JSONObject> bindingList = new ArrayList<>();
    private final List<JSONObject> availableTagList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private TextView tvCount;
    private TextView tvTitle;
    private TextView tvAvailableCount;
    private String currentFilter = "";
    private String currentSearch = "";
    private boolean showingAvailableTags = false;
    
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
        
        Log.d(TAG, "🟢 onViewCreated started");
        
        apiClient = MiraApiClient.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        
        // ✅ تهيئة العناصر
        tvCount = view.findViewById(R.id.tvCount);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvAvailableCount = view.findViewById(R.id.tvAvailableCount);
        rvBindings = view.findViewById(R.id.rvBindings);
        rvBindings.setLayoutManager(new LinearLayoutManager(context));
        
        adapter = new BindingsAdapter(bindingList, this::showBindingDetails);
        rvBindings.setAdapter(adapter);
        
        // ✅ تطبيق الثيم
        applyTheme(view);
        
        // ✅ SwipeRefresh
        SwipeRefreshLayout swipeRefresh = view.findViewById(R.id.swipeRefresh);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(R.color.report_primary, R.color.report_accent);
            swipeRefresh.setOnRefreshListener(() -> {
                Log.d(TAG, "🔄 Swipe refresh triggered");
                loadBindings();
                loadAvailableTags();
                swipeRefresh.setRefreshing(false);
            });
        }
        
        // ✅ التبويبات
        TabLayout tabFilter = view.findViewById(R.id.tabFilter);
        if (tabFilter != null) {
            tabFilter.setTabTextColors(
                getResources().getColor(R.color.report_text_secondary),
                getResources().getColor(R.color.report_text_primary)
            );
            tabFilter.setSelectedTabIndicatorColor(getResources().getColor(R.color.report_accent));
            
            tabFilter.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    Log.d(TAG, "Tab selected: " + tab.getPosition());
                    
                    switch (tab.getPosition()) {
                        case 0: 
                            currentFilter = ""; 
                            showingAvailableTags = false;
                            loadBindings();
                            break;
                        case 1: 
                            currentFilter = "active"; 
                            showingAvailableTags = false;
                            loadBindings();
                            break;
                        case 2: 
                            currentFilter = "locked"; 
                            showingAvailableTags = false;
                            loadBindings();
                            break;
                        case 3: 
                            currentFilter = "inactive"; 
                            showingAvailableTags = false;
                            loadBindings();
                            break;
                        case 4:
                            showingAvailableTags = true;
                            loadAvailableTags();
                            break;
                    }
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
        
        // ✅ زر عرض الشرائح المتاحة
        View btnAvailableTags = view.findViewById(R.id.btnAvailableTags);
        if (btnAvailableTags != null) {
            btnAvailableTags.setOnClickListener(v -> {
                Log.d(TAG, "🟡 btnAvailableTags clicked");
                showingAvailableTags = true;
                loadAvailableTags();
            });
        }
        
        // ✅ زر البحث
        View btnSearch = view.findViewById(R.id.btnSearch);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> showSearchDialog());
        }
        
        // ✅ زر التصدير
        ExtendedFloatingActionButton fabExport = view.findViewById(R.id.fabExport);
        if (fabExport != null) {
            boolean canExport = permissionManager != null && 
                (permissionManager.isManager() || permissionManager.canEdit("rfid"));
            fabExport.setVisibility(canExport ? View.VISIBLE : View.GONE);
            fabExport.setOnClickListener(v -> exportToCSV());
        }
        
        // ✅ تحميل البيانات
        loadBindings();
        loadAvailableTags();
        
        Log.d(TAG, "🟢 onViewCreated completed");
    }
    
    /**
     * ✅ تطبيق الثيم على العناصر
     */
    private void applyTheme(View view) {
        try {
            Context context = getContext();
            if (context == null) return;
            
            // ✅ تطبيق ألوان الثيم
            if (tvTitle != null) {
                tvTitle.setTextColor(context.getColor(R.color.report_text_primary));
            }
            if (tvCount != null) {
                tvCount.setTextColor(context.getColor(R.color.report_text_secondary));
            }
            if (tvAvailableCount != null) {
                tvAvailableCount.setTextColor(context.getColor(R.color.report_accent));
            }
        } catch (Exception e) {
            Log.e(TAG, "applyTheme error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ تحميل الشرائح المتاحة مع Logging
     */
    private void loadAvailableTags() {
        Log.d(TAG, "🔄 === loadAvailableTags START ===");
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getWithAuth("/rfid/available");
                
                Log.d(TAG, "📊 Response: " + (response != null ? "NOT NULL" : "NULL"));
                
                if (response != null) {
                    Log.d(TAG, "📊 Code: " + response.code);
                    Log.d(TAG, "📊 isSuccess: " + response.isSuccess);
                    Log.d(TAG, "📊 Body: " + response.getBody());
                }
                
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) {
                        Log.w(TAG, "⚠️ Fragment detached - skipping UI update");
                        return;
                    }
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        int totalAvailable = response.data.optInt("total_available", 0);
                        int count = response.data.optInt("count", 0);
                        
                        Log.d(TAG, "📊 total_available: " + totalAvailable);
                        Log.d(TAG, "📊 count: " + count);
                        
                        JSONArray availableTags = response.data.optJSONArray("available_tags");
                        
                        availableTagList.clear();
                        if (availableTags != null && availableTags.length() > 0) {
                            for (int i = 0; i < availableTags.length(); i++) {
                                JSONObject tag = availableTags.optJSONObject(i);
                                if (tag != null) {
                                    availableTagList.add(tag);
                                }
                            }
                            Log.d(TAG, "📊 Loaded " + availableTagList.size() + " available tags");
                        } else {
                            Log.w(TAG, "⚠️ available_tags is null or empty");
                        }
                        
                        // ✅ تحديث العداد
                        if (tvAvailableCount != null) {
                            String text = isArabic() ? 
                                "📦 الشرائح المتاحة: " + totalAvailable : 
                                "📦 Available Tags: " + totalAvailable;
                            tvAvailableCount.setText(text);
                            tvAvailableCount.setVisibility(View.VISIBLE);
                            Log.d(TAG, "📊 Updated tvAvailableCount: " + text);
                        }
                        
                        // ✅ عرض إذا كنا في وضع المتاحة
                        if (showingAvailableTags) {
                            showAvailableTags();
                        }
                    } else {
                        Log.e(TAG, "❌ Failed to load available tags");
                        Log.e(TAG, "❌ isSuccess: " + (response != null ? response.isSuccess : "null"));
                        Log.e(TAG, "❌ error: " + (response != null && response.error != null ? response.error : "null"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "❌ loadAvailableTags exception: " + e.getMessage(), e);
            }
        });
        
        Log.d(TAG, "🔄 === loadAvailableTags END ===");
    }
    
    /**
     * ✅ عرض الشرائح المتاحة
     */
    private void showAvailableTags() {
        Log.d(TAG, "📦 showAvailableTags - count: " + availableTagList.size());
        
        if (tvCount != null) {
            tvCount.setText(
                isArabic() ? 
                "إجمالي الشرائح المتاحة: " + availableTagList.size() : 
                "Total Available: " + availableTagList.size()
            );
        }
        
        if (tvTitle != null) {
            tvTitle.setText(isArabic() ? "الشرائح المتاحة" : "Available Tags");
        }
        
        AvailableTagsAdapter availableAdapter = new AvailableTagsAdapter(availableTagList);
        rvBindings.setAdapter(availableAdapter);
    }
    
    /**
     * ✅ تحميل قائمة الشرائح المبرمجة
     */
    private void loadBindings() {
        showingAvailableTags = false;
        Log.d(TAG, "🔄 === loadBindings START ===");
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                StringBuilder endpoint = new StringBuilder("/rfid-bindings-list?page=1&per_page=100");
                
                if (!currentFilter.isEmpty()) {
                    endpoint.append("&status=").append(currentFilter);
                }
                if (!currentSearch.isEmpty()) {
                    endpoint.append("&search=").append(currentSearch);
                }
                
                Log.d(TAG, "📡 Endpoint: " + endpoint.toString());
                
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint.toString());
                
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) {
                        return;
                    }
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray bindings = response.data.optJSONArray("bindings");
                        int total = response.data.optInt("total", 0);
                        
                        bindingList.clear();
                        if (bindings != null) {
                            for (int i = 0; i < bindings.length(); i++) {
                                bindingList.add(bindings.optJSONObject(i));
                            }
                        }
                        
                        Log.d(TAG, "📊 Loaded " + bindingList.size() + " bindings, total: " + total);
                        
                        adapter = new BindingsAdapter(bindingList, this::showBindingDetails);
                        rvBindings.setAdapter(adapter);
                        
                        if (tvCount != null) {
                            tvCount.setText(
                                isArabic() ? 
                                "إجمالي الشرائح المبرمجة: " + total : 
                                "Total Programmed: " + total
                            );
                        }
                        
                        if (tvTitle != null) {
                            tvTitle.setText(isArabic() ? "الشرائح المبرمجة" : "Programmed Tags");
                        }
                    } else {
                        Log.e(TAG, "❌ Failed to load bindings");
                        showToast(getString(R.string.rfid_load_failed));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadBindings error: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * ✅ حوار البحث
     */
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
            loadBindings();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }
    
    /**
     * ✅ عرض تفاصيل الشريحة
     */
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
        
        String statusLabel = getStatusLabel(status);
        
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
        message.append("📊 ").append(getString(R.string.status)).append(": ").append(statusLabel);
        
        builder.setMessage(message.toString());
        
        if (canManage) {
            builder.setPositiveButton(R.string.rfid_manage, (dialog, which) -> {
                showManageOptions(binding);
            });
        }
        
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }
    
    /**
     * ✅ خيارات الإدارة
     */
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
    
    /**
     * ✅ تأكيد الحذف
     */
    private void confirmDelete(int bindingId) {
        Context context = getContext();
        if (!isAdded() || context == null) return;

        new AlertDialog.Builder(context)
            .setTitle(android.R.string.dialog_alert_title)
            .setMessage(R.string.rfid_confirm_delete)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                updateBindingStatus(bindingId, "deleted");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
    
    /**
     * ✅ تحديث حالة الشريحة
     */
    private void updateBindingStatus(int bindingId, String status) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("binding_id", bindingId);
                payload.put("status", status);
                
                MiraApiClient.ApiResponse response = apiClient.postWithAuth("/update-binding-status", payload);
                
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) {
                        return;
                    }

                    if (response != null && response.isSuccess) {
                        showToast(getString(R.string.rfid_updated));
                        loadBindings();
                    } else {
                        showToast(getString(R.string.rfid_load_failed));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateBindingStatus error: " + e.getMessage());
            }
        });
    }
    
    /**
     * ✅ تصدير CSV
     */
    private void exportToCSV() {
        Context context = getContext();
        if (bindingList.isEmpty() || !isAdded() || context == null) return;
        
        try {
            StringBuilder csv = new StringBuilder();
            csv.append('\uFEFF');
            csv.append("MIRA ID,Title,EPC,TID,Karat,Weight,Status,Device,Date\n");
            
            for (JSONObject binding : bindingList) {
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
            
            Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share CSV"));
            
        } catch (Exception e) {
            Log.e(TAG, "exportToCSV error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ ترجمة الحالة
     */
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
    
    // ==================== Adapters ====================
    
    private class BindingsAdapter extends RecyclerView.Adapter<BindingsAdapter.VH> {
        private final List<JSONObject> items;
        private final OnBindingClickListener listener;
        
        BindingsAdapter(List<JSONObject> items, OnBindingClickListener listener) {
            this.items = items;
            this.listener = listener;
        }
        
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_binding_card, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject binding = items.get(pos);
            
            String title = binding.optString("item_title", "-");
            String miraId = binding.optString("mira_id", "-");
            String epc = binding.optString("epc", "-");
            String tid = binding.optString("tid", "-");
            String karat = binding.optString("gold_karat", "-");
            double weight = binding.optDouble("gross_weight_grams", 0);
            String status = binding.optString("status", "active");
            String date = binding.optString("registered_at", "-");
            
            h.tvItemTitle.setText(title);
            h.tvDetails.setText(miraId + " | " + karat + " | " + String.format(Locale.US, "%.2fg", weight));
            h.tvEpcTid.setText("EPC: " + epc + "\nTID: " + tid);
            h.tvDate.setText(date);
            h.tvStatus.setText(getStatusLabel(status));
            
            h.itemView.setOnClickListener(v -> listener.onClick(binding));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
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
    
    interface OnBindingClickListener {
        void onClick(JSONObject binding);
    }
    
    private class AvailableTagsAdapter extends RecyclerView.Adapter<AvailableTagsAdapter.VH> {
        private final List<JSONObject> items;
        
        AvailableTagsAdapter(List<JSONObject> items) {
            this.items = items;
        }
        
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_available_tag_card, parent, false);
            return new VH(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject tag = items.get(pos);
            
            h.tvTid.setText(tag.optString("tid", "-"));
            h.tvGtin.setText("GTIN: " + tag.optString("gtin13", "-"));
            h.tvSerial.setText("Serial: " + tag.optString("serial_number", "-"));
            h.tvBatch.setText("Batch: " + tag.optString("batch_number", "-"));
            h.tvImportedAt.setText("Imported: " + tag.optString("imported_at", "-"));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class VH extends RecyclerView.ViewHolder {
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
    
    private boolean isArabic() {
        return Locale.getDefault().getLanguage().equals("ar");
    }
}
