package com.example.uhf.manager;

import android.content.Context;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;

public class HardwareManager {

    private static final String TAG = "HardwareManager";

    public enum HardwareStatus {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    public interface HardwareStatusListener {
        void onStatusChanged(HardwareStatus status);
        void onTagRead(String epc);
        void onError(String error);
    }

    private final RFIDManager rfidManager;
    private HardwareStatusListener listener;
    private HardwareStatus currentStatus = HardwareStatus.DISCONNECTED;
    private boolean isScanning = false;

    public HardwareManager(Context context, HardwareStatusListener listener) {
        this.listener = listener;
        this.rfidManager = RFIDManager.getInstance(context);
        setupRFIDListener();
    }

    private void setupRFIDListener() {
        rfidManager.setListener(new MiraIRFIDReader.OnTagReadListener() {
            @Override
            public void onTagRead(String epc, int rssi) {
                if (listener != null) listener.onTagRead(epc);
            }

            @Override
            public void onScanStarted() {
                Log.d(TAG, "RFID Scan Started");
            }

            @Override
            public void onScanStopped() {
                Log.d(TAG, "RFID Scan Stopped");
                isScanning = false;
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "RFID Error: " + error);
                if (listener != null) listener.onError(error);
            }

            @Override
            public void onConnectionChanged(boolean connected) {
                updateStatus(connected ? HardwareStatus.CONNECTED : HardwareStatus.DISCONNECTED);
            }
        });
    }

    public void updateStatus(HardwareStatus status) {
        if (currentStatus != status) {
            currentStatus = status;
            if (listener != null) listener.onStatusChanged(status);
        }
    }

    public HardwareStatus getCurrentStatus() {
        return currentStatus;
    }

    public boolean startScan() {
        if (rfidManager != null) {
            try {
                isScanning = rfidManager.startScan();
                return isScanning;
            } catch (Exception e) {
                Log.e(TAG, "Error starting scan", e);
            }
        }
        return false;
    }

    public void stopScan() {
        if (rfidManager != null) {
            try {
                rfidManager.stopScan();
                isScanning = false;
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scan", e);
            }
        }
    }

    public boolean isScanning() {
        return isScanning;
    }

    public void cleanup() {
        if (rfidManager != null) {
            rfidManager.setListener(null);
            rfidManager.stopScan();
        }
    }
}
