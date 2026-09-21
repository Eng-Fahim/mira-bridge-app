package com.example.uhf.MiraRfid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * ✅ مستقبل زر الزناد الفيزيائي في Chainway C72
 * يعمل حتى عندما يكون التطبيق في الخلفية
 */
public class TriggerKeyReceiver extends BroadcastReceiver {
    
    private static final String TAG = "TriggerKeyReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        
        try {
            String action = intent.getAction();
            Log.d(TAG, "🎯 Trigger received: " + action);
            
            int keyCode = intent.getIntExtra("keyCode", 0);
            boolean keyDown = intent.getBooleanExtra("keydown", false);
            
            // ✅ إذا لم يحدد keyCode، استخدم القيمة الافتراضية
            if (keyCode == 0) {
                keyCode = 139;
            }
            
            Log.d(TAG, "keyCode=" + keyCode + " keyDown=" + keyDown);
            
            // ✅ أبلغ RFIDManager
            RFIDManager manager = RFIDManager.getInstance(context);
            if (manager != null) {
                if (keyDown) {
                    manager.startScan();
                } else {
                    manager.stopScan();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onReceive error", e);
        }
    }
}
