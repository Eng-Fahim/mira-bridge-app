package com.example.uhf.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.manager.MiraServerConfig;
import com.google.android.material.button.MaterialButton;

import com.example.uhf.tools.SessionManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MIRA Server Configuration Fragment
 * واجهة إدارة عنوان السيرفر
 */
public class MiraServerConfigFragment extends Fragment {

    private MiraServerConfig serverConfig;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText etBaseUrl, etApiKey, etGateId;
    private MaterialButton btnTestConnection, btnSaveConfig;
    private ProgressBar pbTesting;
    private TextView tvConnectionStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_server_config, container, false);
    }

    @Override
public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    
    serverConfig = MiraServerConfig.getInstance(requireContext());
    
    initViews(view);
    loadCurrentConfig();
    setupListeners();
    
    // ✅ إخفاء الحقول الحساسة عن المستخدمين العاديين
    if (!isSystemAdmin()) {
        etApiKey.setVisibility(View.GONE);
        etGateId.setVisibility(View.GONE);
    }
}

private boolean isSystemAdmin() {
    try {
        SessionManager sessionManager = new SessionManager(requireContext());
        SessionManager.Role role = sessionManager.getUserRole();
        return role == SessionManager.Role.SYSTEM_ADMIN || 
               role == SessionManager.Role.ORG_ADMIN ||
               role == SessionManager.Role.ADMIN;
    } catch (Exception e) {
        return false;
    }
}

    private void initViews(View view) {
        etBaseUrl = view.findViewById(R.id.etBaseUrl);
        etApiKey = view.findViewById(R.id.etApiKey);
        etGateId = view.findViewById(R.id.etGateId);
        btnTestConnection = view.findViewById(R.id.btnTestConnection);
        btnSaveConfig = view.findViewById(R.id.btnSaveConfig);
        pbTesting = view.findViewById(R.id.pbTesting);
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus);
    }

    private void loadCurrentConfig() {
        etBaseUrl.setText(serverConfig.getBaseUrl());
        etApiKey.setText(serverConfig.getApiKey());
        etGateId.setText(serverConfig.getGateId());
    }

    private void setupListeners() {
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSaveConfig.setOnClickListener(v -> saveConfig());
    }

    private void testConnection() {
        String baseUrl = etBaseUrl.getText().toString().trim();
        
        if (baseUrl.isEmpty()) {
            Toast.makeText(getContext(), "أدخل الرابط أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        pbTesting.setVisibility(View.VISIBLE);
        btnTestConnection.setEnabled(false);
        tvConnectionStatus.setText("⏳ جاري اختبار الاتصال...");

        executor.execute(() -> {
            boolean success = serverConfig.testConnection(baseUrl);
            
            mainHandler.post(() -> {
                pbTesting.setVisibility(View.GONE);
                btnTestConnection.setEnabled(true);
                
                if (success) {
                    tvConnectionStatus.setText("✅ الاتصال ناجح!");
                    tvConnectionStatus.setTextColor(0xFF2E7D32);
                } else {
                    tvConnectionStatus.setText("❌ فشل الاتصال - تحقق من الرابط");
                    tvConnectionStatus.setTextColor(0xFFC62828);
                }
            });
        });
    }

    private void saveConfig() {
        String baseUrl = etBaseUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();
        String gateId = etGateId.getText().toString().trim();

        if (baseUrl.isEmpty()) {
            Toast.makeText(getContext(), "الرابط مطلوب", Toast.LENGTH_SHORT).show();
            return;
        }

        serverConfig.setBaseUrl(baseUrl);
        serverConfig.setApiKey(apiKey);
        serverConfig.setGateId(gateId);

        Toast.makeText(getContext(), "✅ تم حفظ الإعدادات", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
