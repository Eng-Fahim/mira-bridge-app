package com.example.uhf.scanner;

import android.content.Context;
import android.util.Log;

import com.example.uhf.activity.UHFMainActivity;

/**
 * ✅ معالج مسح RFID
 * - يستخدم HardwareManager الموجود
 * - يوجّه EPC إلى UnifiedScanManager
 */
public class RfidScanHandler {
    
    private static final String TAG = "RfidScanHandler";
    
    private final Context context;
    private final UnifiedScanManager scanManager;
    private boolean isScanning = false;
    
    public RfidScanHandler(Context context) {
        this.context = context;
        this.scanManager = UnifiedScanManager.getInstance(context);
    }
    
    /**
     * ✅ بدء المسح
     */
    public void startScanning() {
        if (isScanning) {
            Log.w(TAG, "Already scanning");
            return;
        }
        
        try {
            UHFMainActivity activity = (UHFMainActivity) context;
            
            // ✅ سجّل listener
            activity.setActiveRfidListener(this::onRfidTagRead);
            
            // ✅ ابدأ القارئ
            activity.startUHFScan();
            isScanning = true;
            
            Log.d(TAG, "✅ RFID scanning started");
            
        } catch (Exception e) {
            Log.e(TAG, "startScanning error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ إيقاف المسح
     */
    public void stopScanning() {
        if (!isScanning) return;
        
        try {
            UHFMainActivity activity = (UHFMainActivity) context;
            
            // ✅ أوقف القارئ
            activity.stopUHFScan();
            
            // ✅ أزل listener
            activity.setActiveRfidListener(null);
            
            isScanning = false;
            
            Log.d(TAG, "RFID scanning stopped");
            
        } catch (Exception e) {
            Log.e(TAG, "stopScanning error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ استقبال EPC من القارئ
     */
    private void onRfidTagRead(String epc) {
        if (epc == null || epc.trim().isEmpty()) return;
        
        Log.d(TAG, "📡 RFID read: " + epc);
        
        // ✅ أرسل للمسح (Unified manager يمنع المكرر)
        scanManager.scanItem(ScanMethod.RFID, epc, null);
    }
    
    public boolean isScanning() { return isScanning; }
}
