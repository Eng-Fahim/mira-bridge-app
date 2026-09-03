package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.MiraAuditLogAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.MiraAuditLogItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraAccountingAuditLogFragment extends Fragment {

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvAuditLog;
    private TextView tvEmptyLog;
    private ProgressBar progressBar;

    private final List<MiraAuditLogItem> logList = new ArrayList<>();
    private MiraAuditLogAdapter adapter;
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
        progressBar = view.findViewById(R.id.progressBar);

        rvAuditLog.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new MiraAuditLogAdapter(logList, isArabic);
        rvAuditLog.setAdapter(adapter);
    }

    private void loadAuditLog() {
        showLoading(true);
        executor.execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getAccountingAuditLog();
            
            mainHandler.post(() -> {
                showLoading(false);
                if (response != null && response.isSuccess && response.data != null) {
                    parseAuditLog(response.data);
                } else {
                    showError(isArabic ? "تعذر تحميل سجل الرقابة" : "Failed to load audit trail");
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
                    MiraAuditLogItem item = new MiraAuditLogItem(
                            obj.optString("action", ""),
                            obj.optString("username", obj.optString("display_name", "النظام")),
                            obj.optString("object_type", ""),
                            obj.optString("object_id", ""),
                            obj.optString("details", ""),
                            obj.optString("created_at", ""),
                            obj.optString("ip_address", "")
                    );
                    logList.add(item);
                }
                adapter.notifyDataSetChanged();
                tvEmptyLog.setVisibility(View.GONE);
                rvAuditLog.setVisibility(View.VISIBLE);
            } else {
                showError(isArabic ? "لا توجد عمليات مسجلة بالسجل" : "No audit entries found");
            }
        } catch (Exception e) {
            showError(isArabic ? "خطأ في قراءة بيانات السجل" : "Error parsing logs");
        }
    }

    private void showLoading(boolean isLoading) {
        if (progressBar != null) progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        if (isLoading) {
            rvAuditLog.setVisibility(View.GONE);
            tvEmptyLog.setVisibility(View.GONE);
        }
    }

    private void showError(String message) {
        tvEmptyLog.setVisibility(View.VISIBLE);
        tvEmptyLog.setText(message);
        rvAuditLog.setVisibility(View.GONE);
    }
@Override
public void onDestroyView() {
    super.onDestroyView();
    // ✅ لا نغلق الـ Executor هنا
    // لأن onCreateView قد يُستدعى مرة أخرى
}

@Override
public void onDestroy() {
    super.onDestroy();
    // ✅ الإغلاق الصحيح في onDestroy
    if (executor != null && !executor.isShutdown()) {
        executor.shutdown();
    }
}
}
