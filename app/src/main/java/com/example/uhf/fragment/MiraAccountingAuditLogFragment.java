package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraAccountingAuditLogFragment extends Fragment {

    private static final String TAG = "MiraAccountingAuditLogFragment";

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvAuditLog;
    private TextView tvEmptyLog;
    
    private final List<AuditLogItem> logList = new ArrayList<>();
    private AuditLogAdapter adapter;
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_audit_log, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        loadAuditLog();

        return view;
    }

    private void initViews(View view) {
        rvAuditLog = view.findViewById(R.id.rvAuditLog);
        tvEmptyLog = view.findViewById(R.id.tvEmptyLog);
        
        rvAuditLog.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AuditLogAdapter(logList);
        rvAuditLog.setAdapter(adapter);
    }

    private void loadAuditLog() {
        executor.execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getAccountingAuditLog();
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    parseAuditLog(response.data);
                } else {
                    tvEmptyLog.setVisibility(View.VISIBLE);
                    rvAuditLog.setVisibility(View.GONE);
                }
            });
        });
    }

    private void parseAuditLog(JSONObject data) {
        try {
            logList.clear();
            JSONArray items = data.optJSONArray("data");
            
            if (items != null && items.length() > 0) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    AuditLogItem item = new AuditLogItem();
                    item.action = obj.optString("action", "");
                    item.username = obj.optString("username", obj.optString("display_name", ""));
                    item.objectType = obj.optString("object_type", "");
                    item.objectId = obj.optString("object_id", "");
                    item.details = obj.optString("details", "");
                    item.createdAt = obj.optString("created_at", "");
                    logList.add(item);
                }
                adapter.notifyDataSetChanged();
                tvEmptyLog.setVisibility(View.GONE);
                rvAuditLog.setVisibility(View.VISIBLE);
            } else {
                tvEmptyLog.setVisibility(View.VISIBLE);
                rvAuditLog.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            tvEmptyLog.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // ==================== MODEL ====================
    private static class AuditLogItem {
        String action;
        String username;
        String objectType;
        String objectId;
        String details;
        String createdAt;
    }

    // ==================== ADAPTER ====================
    private class AuditLogAdapter extends RecyclerView.Adapter<AuditLogAdapter.ViewHolder> {
        private List<AuditLogItem> items;
        
        AuditLogAdapter(List<AuditLogItem> items) { this.items = items; }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audit_log, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AuditLogItem item = items.get(position);
            holder.tvAction.setText(getActionLabel(item.action));
            holder.tvUser.setText(item.username);
            holder.tvDate.setText(item.createdAt);
            holder.tvDetails.setText(item.details);
        }
        
        private String getActionLabel(String action) {
            if (isArabic) {
                switch (action) {
                    case "ACCOUNTING_CREATE": return "✅ إنشاء";
                    case "ACCOUNTING_UPDATE": return "✏️ تعديل";
                    case "ACCOUNTING_DELETE": return "🗑️ حذف";
                    case "ACCOUNTING_CLOSE_YEAR": return "🔒 إغلاق سنة";
                    default: return action;
                }
            } else {
                return action.replace("ACCOUNTING_", "");
            }
        }
        
        @Override
        public int getItemCount() { return items.size(); }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvAction, tvUser, tvDate, tvDetails;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvAction = itemView.findViewById(R.id.tvAuditAction);
                tvUser = itemView.findViewById(R.id.tvAuditUser);
                tvDate = itemView.findViewById(R.id.tvAuditDate);
                tvDetails = itemView.findViewById(R.id.tvAuditDetails);
            }
        }
    }
}
