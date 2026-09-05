package com.example.uhf.tools;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * مدير أمني موحد لعمليات القارئ لمنع تجمد التطبيق (ANR)
 * يضمن تشغيل كافة الأوامر في خيط خلفي معزول وعادم للتعارض مع الكاميرا
 */
public class RfidHardwareHelper {

    private static final String TAG = "RfidHardwareHelper";
    private static volatile RfidHardwareHelper instance;

    // خيط خلفي أحادي مخصص حصراً للهاردوير لمنع Race Conditions
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RfidHardwareHelper() {}

    public static RfidHardwareHelper getInstance() {
        if (instance == null) {
            synchronized (RfidHardwareHelper.class) {
                if (instance == null) {
                    instance = new RfidHardwareHelper();
                }
            }
        }
        return instance;
    }

    /**
     * إيقاف آمن وغير حاجز للقارئ مع إمكانية تنفيذ callback بعد الإيقاف
     */
    public void stopAsync(RFIDWithUHFUART reader, Runnable onComplete) {
        executor.execute(() -> {
            try {
                if (reader != null) {
                    reader.stopInventory();
                    reader.setInventoryCallback(null);
                    Log.d(TAG, "UHF Reader stopped safely in background thread");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping UHF reader: " + e.getMessage());
            } finally {
                if (onComplete != null) {
                    mainHandler.post(onComplete);
                }
            }
        });
    }

    /**
     * تشغيل آمن وغير حاجز للقارئ
     */
    public void startAsync(RFIDWithUHFUART reader, int power, IUHFInventoryCallback callback, Runnable onSuccess) {
        executor.execute(() -> {
            try {
                if (reader != null) {
                    reader.setPower(power);
                    reader.setInventoryCallback(callback);
                    boolean success = reader.startInventoryTag();
                    if (success && onSuccess != null) {
                        mainHandler.post(onSuccess);
                    }
                    Log.d(TAG, "UHF Reader started safely: " + success);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting UHF reader: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
    try {
        executor.shutdown();
        instance = null;
    } catch (Exception e) {
        Log.e(TAG, "Shutdown error: " + e.getMessage());
    }
    }

    /**
     * التمهيد الآمن لفتح الكاميرا (إيقاف + انتظر مهلة الـ UART Port ثم فتح الكاميرا)
     */
    public void prepareForCamera(RFIDWithUHFUART reader, Runnable launchCameraAction) {
        stopAsync(reader, () -> {
            // مهلة زمنية 150ms لضمان تحرير UART Serial Port على مستوى الـ Native
            mainHandler.postDelayed(launchCameraAction, 150);
        });
    }
}
