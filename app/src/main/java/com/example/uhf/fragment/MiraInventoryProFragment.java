package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.MiraRfid.MiraIRFIDReader;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";

    private TextView tvExpectedCount;
    private TextView tvScannedCount;
    private Button btnStartInventory;
    private Button btnStopInventory;

    private MiraInventoryEngine inventoryEngine;
    private ExecutorService scannerExecutor;
    private Handler mainThreadHandler;
    private boolean isScanning = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        initViews(view);

        scannerExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());

        String sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String deviceId = android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );

        inventoryEngine = new MiraInventoryEngine(requireContext(), sessionId, deviceId);
        
        return view;
    }

    private void initViews(View view) {
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        
        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);

        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> startScanning());
        }
        if (btnStopInventory != null) {
            btnStopInventory.setOnClickListener(v -> stopScanning());
        }
    }

    @Override
    public void onTagRead(String epc, String rssi) {
        if (!isScanning || epc == null) return;

        int parsedRssi = -60;
        try {
            parsedRssi = Integer.parseInt(rssi);
        } catch (Exception ignored) {}

        final int finalRssi = parsedRssi;
        scannerExecutor.execute(() -> {
            inventoryEngine.onTagScanned(epc, finalRssi);
            mainThreadHandler.post(this::updateUI);
        });
    }

    public void onError(String errorMessage) {
        Log.e(TAG, "RFID Read Error: " + errorMessage);
    }

    public void onConnectionChanged(boolean isConnected) {
        Log.i(TAG, "RFID Connection status: " + isConnected);
    }

    public void onScanStopped() {
        Log.i(TAG, "RFID Hardware scan cycle stopped");
    }

    @Override
    public void onScanStarted() {
        Log.i(TAG, "RFID Hardware scan cycle started");
    }

    private void updateUI() {
        if (!isAdded()) return;
        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(inventoryEngine.getUniqueCount()));
        }
    }

    private void startScanning() {
        isScanning = true;
        Toast.makeText(requireContext(), "بدء الجرد الفعلي...", Toast.LENGTH_SHORT).show();
    }

    private void stopScanning() {
        isScanning = false;
        scannerExecutor.execute(() -> {
            inventoryEngine.processSessionClose();
            mainThreadHandler.post(() -> 
                Toast.makeText(requireContext(), "تم إيقاف الجرد وحفظ الحزمة", Toast.LENGTH_SHORT).show()
            );
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (scannerExecutor != null && !scannerExecutor.isShutdown()) {
            scannerExecutor.shutdown();
        }
    }
}
