package com.example.uhf.license;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.example.uhf.tools.SessionManager;

/**
 * NetworkChangeReceiver — نبضة فورية عند عودة الإنترنت
 *
 * السيناريو: المستخدم كان offline ليومين، عاد الإنترنت،
 * يفتح التطبيق → نُسرّع heartbeat فوراً بدل انتظار 6 ساعات
 */
public class NetworkChangeReceiver extends BroadcastReceiver {

    private static final String TAG = "NetworkChangeReceiver";
    private static long lastTriggeredAt = 0;
    private static final long MIN_INTERVAL_MS = 60_000; // لا نُكرر أكثر من دقيقة

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            if (!ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) return;

            // قراءة حالة الاتصال
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkInfo active = cm.getActiveNetworkInfo();
            boolean connected = active != null && active.isConnected();

            if (!connected) {
                Log.d(TAG, "📴 Network disconnected");
                return;
            }

            // تجنب التكرار السريع
            long now = System.currentTimeMillis();
            if (now - lastTriggeredAt < MIN_INTERVAL_MS) {
                return;
            }
            lastTriggeredAt = now;

            // فقط إذا كان الترخيص مفعلاً
            SessionManager sm = new SessionManager(context);
            if (sm.getActivationToken().isEmpty()) {
                return;
            }

            Log.d(TAG, "📶 Network restored → triggering heartbeat");
            HeartbeatScheduler.triggerNow(context);

        } catch (Exception e) {
            Log.e(TAG, "onReceive error: " + e.getMessage(), e);
        }
    }
}
