package com.example.uhf.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.AuditLogAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.AuditLogDto;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class MiraAuditLogFragment extends Fragment {

    private Spinner spLogStatus;
    private RecyclerView rvAuditLogs;
    private ProgressBar pbLoading;
    private AuditLogAdapter adapter;

    private final String[] statuses = {"الكل", "نجاح (Success)", "فشل (Failed)"};
    private final String[] statusCodes = {"", "success", "failed"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_audit_logs, container, false);

        spLogStatus = view.findViewById(R.id.spLogStatus);
        rvAuditLogs = view.findViewById(R.id.rvAuditLogs);
        pbLoading = view.findViewById(R.id.pbLoading);
        view.findViewById(R.id.btnRefreshLogs).setOnClickListener(v -> loadLogs());

        // إعداد الفلتر
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, statuses);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLogStatus.setAdapter(spinnerAdapter);
        spLogStatus.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                loadLogs();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // إعداد القائمة
        adapter = new AuditLogAdapter();
        rvAuditLogs.setLayoutManager(new LinearLayoutManager(getContext()));
        rvAuditLogs.setAdapter(adapter);

        loadLogs();
        return view;
    }

    private void loadLogs() {
        pbLoading.setVisibility(View.VISIBLE);
        
        int pos = spLogStatus.getSelectedItemPosition();
        String statusFilter = (pos >= 0 && pos < statusCodes.length) ? statusCodes[pos] : "";

        new Thread(() -> {
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.getAuditLogs(statusFilter, "", 100);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray dataArray = response.data.optJSONArray("data");
                    if (dataArray != null) {
                        Type listType = new TypeToken<List<AuditLogDto>>() {}.getType();
                        List<AuditLogDto> logsList = new Gson().fromJson(dataArray.toString(), listType);
                        adapter.setItems(logsList);
                    }
                } else {
                    String err = (response != null && response.error != null) ? response.error : "فشل الاتصال";
                    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
