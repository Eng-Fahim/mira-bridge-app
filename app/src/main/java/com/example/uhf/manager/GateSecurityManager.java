package com.example.uhf.manager;

import android.content.Context;
import android.content.Intent;

import com.example.uhf.service.GateSecurityService;

/**
 * 🚪 مدير البوابة الأمنية - للتحكم في تشغيل/إيقاف المراقبة
 */
public class GateSecurityManager {

    private static GateSecurityManager instance;
    private final Context context;

    private GateSecurityManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static GateSecurityManager getInstance(Context context) {
        if (instance == null) {
            instance = new GateSecurityManager(context);
        }
        return instance;
    }

    /**
     * ✅ تشغيل مراقبة البوابة
     */
    public void startGateMonitoring(String gateId) {
        Intent intent = new Intent(context, GateSecurityService.class);
        intent.putExtra("gate_id", gateId);
        context.startService(intent);
    }

    /**
     * ✅ إيقاف مراقبة البوابة
     */
    public void stopGateMonitoring() {
        Intent intent = new Intent(context, GateSecurityService.class);
        context.stopService(intent);
    }
}
