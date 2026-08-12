package com.example.uhf.MiraRfid.readers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.MiraRfid.MiraIRFIDReader;

import java.util.Random;

/**
 * قارئ محاكي - للاختبار بدون جهاز فعلي
 */
public class SimulatorReader implements MiraIRFIDReader {
    
    private static final String TAG = "SimulatorReader";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    
    private OnTagReadListener listener;
    private boolean isScanning = false;
    private boolean isConnected = false;
    private int powerDbm = 28;
    private int rssiThreshold = -70;
    private int scanIntervalMs = 200;
    private Runnable scanRunnable;
    
    @Override
    public boolean initialize() {
        Log.i(TAG, "Simulator initialized");
        return true;
    }
    
    @Override
    public boolean connect() {
        isConnected = true;
        Log.i(TAG, "Simulator connected");
        if (listener != null) listener.onConnectionChanged(true);
        return true;
    }
    
    @Override
    public void disconnect() {
        stopScan();
        isConnected = false;
        if (listener != null) listener.onConnectionChanged(false);
    }
    
    @Override
    public void release() {
        disconnect();
    }
    
    @Override
    public boolean isConnected() {
        return isConnected;
    }
    
    @Override
    public boolean startScan() {
        if (isScanning) return true;
        if (!isConnected) connect();
        
        isScanning = true;
        if (listener != null) listener.onScanStarted();
        
        scanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning) return;
                
                String epc = "076" + String.format("%011d", random.nextInt(1000000000));
                int rssi = -30 - random.nextInt(40);
                
                if (listener != null) {
                    listener.onTagRead(epc, rssi);
                }
                
                handler.postDelayed(this, scanIntervalMs);
            }
        };
        handler.post(scanRunnable);
        
        return true;
    }
    
    @Override
    public void stopScan() {
        isScanning = false;
        if (scanRunnable != null) {
            handler.removeCallbacks(scanRunnable);
        }
        if (listener != null) listener.onScanStopped();
    }
    
    @Override
    public boolean isScanning() {
        return isScanning;
    }
    
    @Override
    public void setPower(int powerDbm) {
        this.powerDbm = powerDbm;
        Log.i(TAG, "Power: " + powerDbm);
    }
    
    @Override
    public void setRssiThreshold(int threshold) {
        this.rssiThreshold = threshold;
    }
    
    @Override
    public void setScanInterval(int intervalMs) {
        this.scanIntervalMs = intervalMs;
    }
    
    @Override
    public String getDeviceName() {
        return "Simulator Reader";
    }
    
    @Override
    public String getFirmwareVersion() {
        return "1.0.0";
    }
    
    @Override
    public int getBatteryLevel() {
        return 100;
    }
    
    @Override
    public void setOnTagReadListener(OnTagReadListener listener) {
        this.listener = listener;
    }
}
