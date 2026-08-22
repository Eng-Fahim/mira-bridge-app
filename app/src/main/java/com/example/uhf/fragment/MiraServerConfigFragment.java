package com.example.uhf.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraServerConfigFragment extends Fragment {

    private static final String TAG = "MiraServerConfigFrag";
    
    private MiraServerConfig serverConfig;
    private PermissionManager permissionManager;
    private SessionManager sessionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText etBaseUrl, etApiKey, etGateId;
    private MaterialButton btnTestConnection, btnSaveConfig;
    private ProgressBar pbTesting;
    private TextView tvConnectionStatus;
    private boolean isArabic;

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
        permissionManager = PermissionManager.getInstance(requireContext());
        sessionManager = new SessionManager(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        initViews(view);
        loadCurrentConfig();
        setupListeners();
        applyPermissions();
    }

    @Override
    public void onResume() {
        super.onResume();
        // ✅ إعادة تحميل الإعدادات
        loadCurrentConfig();
    }

    private void applyPermissions() {
        boolean isSystemAdmin = permissionManager.isSystemAdmin();
        
        if (!isSystemAdmin) {
            if (etBaseUrl != null) etBaseUrl.setEnabled(false);
            if (etApiKey != null) etApiKey.setVisibility(View.GONE);
            if (etGateId != null) etGateId.setVisibility(View.GONE);
            
            if (btnSaveConfig != null) {
                btnSaveConfig.setEnabled(false);
                btnSaveConfig.setAlpha(0.5f);
            }
            
            if (btnTestConnection != null) {
                btnTestConnection.setEnabled(false);
                btnTestConnection.setAlpha(0.5f);
            }
            
            if (tvConnectionStatus != null) {
                tvConnectionStatus.setText(isArabic ? "⛔ مخصص لمدير النظام فقط" : "⛔ System Admin Only");
                tvConnectionStatus.setTextColor(0xFFDC2626);
            }
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
        
        applyLanguage();
    }

    private void applyLanguage() {
        if (etBaseUrl != null) etBaseUrl.setHint(isArabic ? "رابط السيرفر" : "Server URL");
        if (etApiKey != null) etApiKey.setHint(isArabic ? "مفتاح API" : "API Key");
        if (etGateId != null) etGateId.setHint(isArabic ? "معرف البوابة" : "Gate ID");
        if (btnTestConnection != null) btnTestConnection.setText(isArabic ? "اختبار الاتصال" : "Test Connection");
        if (btnSaveConfig != null) btnSaveConfig.setText(isArabic ? "حفظ الإعدادات" : "Save Config");
    }

    private void loadCurrentConfig() {
        // ✅ إعادة إنشاء serverConfig للتأكد من القراءة الصحيحة
        serverConfig = MiraServerConfig.getInstance(requireContext());
        
        String baseUrl = serverConfig.getBaseUrl();
        String apiKey = serverConfig.getApiKey();
        String gateId = serverConfig.getGateId();
        
        // ✅ سجل تشخيصي
        Log.d(TAG, "=== Loading Config ===");
        Log.d(TAG, "URL: " + baseUrl);
        Log.d(TAG, "API Key: " + (apiKey.isEmpty() ? "EMPTY" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        Log.d(TAG, "Gate ID: " + (gateId.isEmpty() ? "EMPTY" : gateId));
        
        if (etBaseUrl != null) etBaseUrl.setText(baseUrl);
        if (etApiKey != null) etApiKey.setText(apiKey);
        if (etGateId != null) etGateId.setText(gateId);
    }

    private void setupListeners() {
        btnTestConnection.setOnClickListener(v -> {
            if (permissionManager.isSystemAdmin()) {
                testConnection();
            } else {
                Toast.makeText(getContext(), 
                    isArabic ? "⛔ مخصص لمدير النظام فقط" : "⛔ System Admin Only",
                    Toast.LENGTH_SHORT).show();
            }
        });
        
        btnSaveConfig.setOnClickListener(v -> {
            if (permissionManager.isSystemAdmin()) {
                saveConfig();
            } else {
                Toast.makeText(getContext(), 
                    isArabic ? "⛔ مخصص لمدير النظام فقط" : "⛔ System Admin Only",
                    Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void testConnection() {
        if (!permissionManager.isSystemAdmin()) return;
        
        String baseUrl = etBaseUrl.getText().toString().trim();
        
        if (baseUrl.isEmpty()) {
            Toast.makeText(getContext(), 
                isArabic ? "أدخل الرابط أولاً" : "Enter URL first", 
                Toast.LENGTH_SHORT).show();
            return;
        }

        pbTesting.setVisibility(View.VISIBLE);
        btnTestConnection.setEnabled(false);
        tvConnectionStatus.setText(isArabic ? "⏳ جاري اختبار الاتصال..." : "⏳ Testing connection...");

        executor.execute(() -> {
            boolean success = serverConfig.testConnection(baseUrl);
            
            mainHandler.post(() -> {
                pbTesting.setVisibility(View.GONE);
                btnTestConnection.setEnabled(true);
                
                if (success) {
                    tvConnectionStatus.setText(isArabic ? "✅ الاتصال ناجح!" : "✅ Connected!");
                    tvConnectionStatus.setTextColor(0xFF2E7D32);
                } else {
                    tvConnectionStatus.setText(isArabic ? "❌ فشل الاتصال - تحقق من الرابط" : "❌ Connection failed - check URL");
                    tvConnectionStatus.setTextColor(0xFFC62828);
                }
            });
        });
    }

    private void saveConfig() {
        if (!permissionManager.isSystemAdmin()) {
            Toast.makeText(getContext(), 
                isArabic ? "⛔ مخصص لمدير النظام فقط" : "⛔ System Admin Only",
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        String baseUrl = etBaseUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();
        String gateId = etGateId.getText().toString().trim();

        if (baseUrl.isEmpty()) {
            Toast.makeText(getContext(), 
                isArabic ? "الرابط مطلوب" : "URL required", 
                Toast.LENGTH_SHORT).show();
            return;
        }

        // ✅ حفظ في MiraServerConfig
        serverConfig.setBaseUrl(baseUrl);
        serverConfig.setApiKey(apiKey);
        serverConfig.setGateId(gateId);
        
        // ✅ حفظ في SessionManager أيضاً
        if (!apiKey.isEmpty()) {
            sessionManager.saveApiKey(apiKey);
        }
        if (!gateId.isEmpty()) {
            sessionManager.saveGateId(gateId);
        }

        // ✅ سجل تشخيصي
        Log.d(TAG, "=== Config Saved ===");
        Log.d(TAG, "API Key: " + (apiKey.isEmpty() ? "EMPTY" : apiKey.substring(0, Math.min(8, apiKey.length())) + "..."));
        Log.d(TAG, "Gate ID: " + gateId);

        Toast.makeText(getContext(), 
            isArabic ? "✅ تم حفظ الإعدادات" : "✅ Settings saved", 
            Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
