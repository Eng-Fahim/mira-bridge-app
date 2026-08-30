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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraRfidBindingsFragment extends MiraBaseFragment {
    
    private static final String TAG = "MiraRfidBindings";
    
    private RecyclerView rvBindings;
    private BindingsAdapter adapter;
    private MiraApiClient apiClient;
    private PermissionManager permissionManager;
    private final List<JSONObject> bindingList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private TextView tvCount;
    private String currentFilter = "";
    private String currentSearch = "";
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_rfid_bindings, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        apiClient = MiraApiClient.getInstance(getContext());
        permissionManager = PermissionManager.getInstance(getContext());
        
        tvCount = view.findViewById(R.id.tvCount);
        rvBindings = view.findViewById(R.id.rvBindings);
        rvBindings.setLayoutManager(new LinearLayoutManager(getContext()));
        
        adapter = new BindingsAdapter(bindingList, this::showBindingDetails);
        rvBindings.setAdapter(adapter);
        
        SwipeRefreshLayout swipeRefresh = view.findViewById(R.id.swipeRefresh);
        if (swipeRefresh != null) {
            swipeRefresh.setOnRefreshListener(() -> {
                loadBindings();
                swipeRefresh.setRefreshing(false);
            });
        }
        
        TabLayout tabFilter = view.findViewById(R.id.tabFilter);
        if (tabFilter != null) {
            tabFilter.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    switch (tab.getPosition()) {
                        case 0: currentFilter = ""; break;
                        case 1: currentFilter = "active"; break;
                        case 2: currentFilter = "locked"; break;
                        case 3: currentFilter = "inactive"; break;
                    }
                    loadBindings();
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
            fabExport.setOnClickListener(v -> exportToCSV());
        }
        
        loadBindings();
    }
    
    private void loadBindings() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                StringBuilder endpoint = new StringBuilder("/rfid-bindings-list?page=1&per_page=100");
                
                if (!currentFilter.isEmpty()) {
                    endpoint.append("&status=").append(currentFilter);
                }
                if (!currentSearch.isEmpty()) {
                    endpoint.append("&search=").append(currentSearch);
                }
                
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint.toString());
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray bindings = response.data.optJSONArray("bindings");
                        int total = response.data.optInt("total", 0);
                        
                        bindingList.clear();
                        if (bindings != null) {
                            for (int i = 0; i < bindings.length(); i++) {
                                bindingList.add(bindings.optJSONObject(i));
                            }
                        }
                        
                        adapter.notifyDataSetChanged();
                        
                        if (tvCount != null) {
                            tvCount.setText(isArabic ? "الإجمالي: " + total : "Total: " + total);
                        }
                    } else {
                        Toast.makeText(getContext(), 
                            isArabic ? "❌ فشل التحميل" : "❌ Failed to load", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadBindings error: " + e.getMessage());
            }
        });
    }
    
    private void showSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(isArabic ? "🔍 بحث" : "🔍 Search");
        
        EditText etSearch = new EditText(requireContext());
        etSearch.setHint(isArabic ? "MIRA ID / EPC / TID / العنوان" : "MIRA ID / EPC / TID / Title");
        etSearch.setPadding(40, 20, 40, 20);
        builder.setView(etSearch);
        
        builder.setPositiveButton(isArabic ? "بحث" : "Search", (dialog, which) -> {
            currentSearch = etSearch.getText().toString().trim();
            loadBindings();
        });
        builder.setNegativeButton(isArabic ? "إلغاء" : "Cancel", null);
        builder.show();
    }
    
    private void showBindingDetails(JSONObject binding) {
        if (binding == null) return;
        
        String title = binding.optString("item_title", "-");
        String miraId = binding.optString("mira_id", "-");
        String epc = binding.optString("epc", "-");
        String tid = binding.optString("tid", "-");
        String karat = binding.optString("gold_karat", "-");
        double weight = binding.optDouble("gross_weight_grams", 0);
        String status = binding.optString("status", "active");
        String deviceType = binding.optString("device_type", "-");
        String date = binding.optString("registered_at", "-");
        
        String statusLabel = status;
        if (isArabic) {
            switch (status) {
                case "active": statusLabel = "✅ نشطة"; break;
                case "locked": statusLabel = "🔒 مقفلة"; break;
                case "inactive": statusLabel = "⛔ غير نشطة"; break;
            }
        }
        
        boolean canManage = permissionManager != null && 
            (permissionManager.isManager() || permissionManager.canEdit("rfid"));
        
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(title);
        
        StringBuilder message = new StringBuilder();
        message.append(isArabic ? "🔑 MIRA ID: " : "🔑 MIRA ID: ").append(miraId).append("\n");
        message.append(isArabic ? "📡 EPC: " : "📡 EPC: ").append(epc).append("\n");
        message.append(isArabic ? "🏷️ TID: " : "🏷️ TID: ").append(tid).append("\n");
        message.append(isArabic ? "💎 العيار: " : "💎 Karat: ").append(karat).append("\n");
        message.append(isArabic ? "⚖️ الوزن: " : "⚖️ Weight: ").append(weight).append("g\n");
        message.append(isArabic ? "📱 الجهاز: " : "📱 Device: ").append(deviceType).append("\n");
        message.append(isArabic ? "📅 التاريخ: " : "📅 Date: ").append(date).append("\n");
        message.append(isArabic ? "📊 الحالة: " : "📊 Status: ").append(statusLabel);
        
        builder.setMessage(message.toString());
        
        if (canManage) {
            builder.setPositiveButton(isArabic ? "إدارة" : "Manage", (dialog, which) -> {
                showManageOptions(binding);
            });
        }
        
        builder.setNegativeButton(isArabic ? "إغلاق" : "Close", null);
        builder.show();
    }
    
    private void showManageOptions(JSONObject binding) {
        int bindingId = binding.optInt("id", 0);
        String currentStatus = binding.optString("status", "active");
        
        String[] options;
        if (isArabic) {
            options = new String[]{"🔒 قفل الشريحة", "🔓 فك القفل", "⛔ تعطيل", "❌ حذف الربط"};
        } else {
            options = new String[]{"🔒 Lock", "🔓 Unlock", "⛔ Deactivate", "❌ Delete Binding"};
        }
        
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "إدارة الشريحة" : "Manage Binding")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: updateBindingStatus(bindingId, "locked"); break;
                    case 1: updateBindingStatus(bindingId, "active"); break;
                    case 2: updateBindingStatus(bindingId, "inactive"); break;
                    case 3: deleteBinding(bindingId); break;
                }
            })
            .show();
    }
    
    private void updateBindingStatus(int bindingId, String status) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("binding_id", bindingId);
                payload.put("status", status);
                
                MiraApiClient.ApiResponse response = apiClient.postWithAuth("/unlink_rfid", payload);
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), 
                            isArabic ? "✅ تم التحديث" : "✅ Updated", 
                            Toast.LENGTH_SHORT).show();
                        loadBindings();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateBindingStatus error: " + e.getMessage());
            }
        });
    }
    
    private void deleteBinding(int bindingId) {
        new AlertDialog.Builder(requireContext())
            .setTitle(isArabic ? "⚠️ تأكيد الحذف" : "⚠️ Confirm Delete")
            .setMessage(isArabic ? "هل أنت متأكد من حذف هذا الربط؟" : "Are you sure to delete this binding?")
            .setPositiveButton(isArabic ? "حذف" : "Delete", (dialog, which) -> {
                updateBindingStatus(bindingId, "deleted");
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }
    
    private void exportToCSV() {
        if (bindingList.isEmpty()) return;
        
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
            
            File exportDir = new File(requireContext().getCacheDir(), "exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            
            File file = new File(exportDir, "MIRA_RFID_Bindings_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(csv.toString().getBytes("UTF-8"));
            fos.close();
            
            Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share CSV"));
            
        } catch (Exception e) {
            Log.e(TAG, "exportToCSV error: " + e.getMessage());
        }
    }
    
    // ==================== Adapter ====================
    
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
            h.tvDetails.setText(miraId + " | " + karat + " | " + String.format("%.2fg", weight));
            h.tvEpcTid.setText("EPC: " + epc + "\nTID: " + tid);
            h.tvDate.setText(date);
            
            String statusLabel = status;
            if (isArabic) {
                switch (status) {
                    case "active": statusLabel = "✅ نشطة"; break;
                    case "locked": statusLabel = "🔒 مقفلة"; break;
                    case "inactive": statusLabel = "⛔ غير نشطة"; break;
                }
            }
            h.tvStatus.setText(statusLabel);
            
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
}
